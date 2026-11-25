package multisector.sync;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.FleetMemberPickerListener;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.listeners.ListenerUtil;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.DModManager;
import com.fs.starfarer.api.impl.campaign.FleetEncounterContext;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.util.Misc;
import multisector.character.ShipJson;
import multisector.net.Session;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * What a joint battle leaves for the players who joined it.
 *
 * <ul>
 * <li><b>Their own lost ships</b>, theirs alone: offered for recovery on
 *     their game as the game itself offers yours after a battle (the same
 *     chances, d-mods and story-point recoveries), right after the result.
 *     Officers go back on the ships recovered; an AI core on one that isn't
 *     goes back into the cargo.</li>
 * <li><b>Enemy ships the leader left</b>: the leader takes their spoils
 *     first, as always. Once the leader's battle dialog closes, the enemy
 *     ships lost in the battle that aren't in the leader's fleet go to each
 *     player who fought in it, to recover as they would any enemy ship.</li>
 * </ul>
 *
 * Kept in the save (sector persistent data) until recovered or left, so a
 * save in between, or a crash after it, doesn't lose them.
 */
final class JointSpoils {

    private static final Logger log = Global.getLogger(JointSpoils.class);
    /** Where the offers wait in the save. */
    private static final String SAVE_KEY = "multisector_recoveryOffers";
    /** The leader's battle dialog still open after this long: send what's left anyway. */
    private static final long LEADER_WAIT_NANOS = 15L * 60L * 1_000_000_000L;

    // ================================================================ joiner

    private static final List<FleetMemberAPI> normal = new ArrayList<FleetMemberAPI>();
    private static final List<FleetMemberAPI> story = new ArrayList<FleetMemberAPI>();
    /** Our own lost ships among them (the rest are enemy ships the leader left). */
    private static final Set<FleetMemberAPI> own = java.util.Collections.newSetFromMap(new IdentityHashMap<FleetMemberAPI, Boolean>());
    /** Each of our lost ships' captain (an officer still in our fleet, or an AI core), to put back. */
    private static final Map<FleetMemberAPI, PersonAPI> captains = new IdentityHashMap<FleetMemberAPI, PersonAPI>();
    private static String from = "";
    /** Ships arrived while no dialog of ours was open: offer them when the map is free. */
    private static boolean offerLater;

    private JointSpoils() {
    }

    static boolean hasAny() {
        return !normal.isEmpty() || !story.isEmpty();
    }

    static int count() {
        return normal.size() + story.size();
    }

    /**
     * Our ships lost in someone's battle (already out of our fleet): which
     * of them we can recover, prepared as the game prepares yours.
     */
    static void ownLost(String leaderName, List<FleetMemberAPI> lost, Set<String> destroyed) {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (me == null || lost.isEmpty()) return;
        from = leaderName;
        for (FleetMemberAPI m : lost) {
            try {
                if (Misc.isUnboardable(m)) continue;
                boolean wrecked = destroyed.contains(m.getId());
                PersonAPI c = m.getCaptain();
                boolean officer = c != null && !c.isDefault() && !c.isPlayer();
                if (officer) captains.put(m, c);
                boolean easy = Misc.isShipRecoverable(m, me, true, c != null && !c.isDefault(), wrecked ? 0.5f : 1f);
                prepare(m, true, wrecked, me);
                own.add(m);
                (easy ? normal : story).add(m);
            } catch (RuntimeException e) {
                log.warn("[MultiSector] couldn't offer " + m.getShipName() + " for recovery", e);
            }
        }
        if (hasAny()) {
            log.info("[MultiSector] " + count() + " of our ships from " + leaderName + "'s battle can be recovered ("
                    + story.size() + " with a story point)");
        }
        persist();
    }

    /** Enemy ships the leader left, from their game. */
    static void leftovers(String leaderName, JSONObject j) {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        JSONArray ships = j.optJSONArray("ships");
        if (me == null || ships == null || ships.length() == 0) return;
        from = leaderName;
        int added = 0;
        List<String> skipped = new ArrayList<String>();
        for (int i = 0; i < ships.length(); i++) {
            try {
                JSONObject sj = ships.getJSONObject(i);
                FleetMemberAPI m = ShipJson.buildShip(sj, skipped);
                if (m == null || Misc.isUnboardable(m)) continue;
                boolean wrecked = sj.optBoolean("destroyed");
                m.getStatus().setHullFraction(0.01f);
                boolean easy = Misc.isShipRecoverable(m, me, false, false, wrecked ? 0.5f : 1f);
                prepare(m, false, wrecked, me);
                (easy ? normal : story).add(m);
                added++;
            } catch (Exception e) {
                log.warn("[MultiSector] couldn't rebuild a ship " + leaderName + " left", e);
            }
        }
        if (added == 0) return;
        persist();
        log.info("[MultiSector] " + leaderName + " left " + added + " enemy ships to recover"
                + (skipped.isEmpty() ? "" : "; missing here: " + skipped));
        Session.get().notice(leaderName + " has taken their pick: " + added + " ship" + (added == 1 ? "" : "s")
                + " from the battle can be recovered.");
        offerLater = true;
    }

    /** As the game readies a ship for its recovery screen: d-mods, and what survives of weapons and wings. */
    private static void prepare(FleetMemberAPI m, boolean ours, boolean wrecked, CampaignFleetAPI me) {
        ShipVariantAPI v = m.getVariant().clone();
        v.setSource(VariantSource.REFIT);
        m.setVariant(v, false, true);
        Random r = new Random(1000000L * m.getId().hashCode() + Global.getSector().getPlayerBattleSeed());
        float avoid = m.getStats().getDynamic().getMod(Stats.DMOD_AVOID_PROB_MOD).computeEffective(0f);
        float acquire = m.getStats().getDynamic().getMod(Stats.DMOD_ACQUIRE_PROB_MOD).computeEffective(1f);
        if (r.nextFloat() >= avoid && r.nextFloat() < acquire) {
            DModManager.addDMods(m, wrecked, ours, me, r);
            if (DModManager.getNumDMods(m.getVariant()) > 0) DModManager.setDHull(m.getVariant());
        }
        float weapons = Global.getSettings().getFloat(ours ? "salvageOwnWeaponProb" : "salvageWeaponProb");
        float wings = Global.getSettings().getFloat(ours ? "salvageOwnWingProb" : "salvageWingProb");
        if (ours) {
            weapons = me.getStats().getDynamic().getValue(Stats.OWN_WEAPON_RECOVERY_MOD, weapons);
            wings = me.getStats().getDynamic().getValue(Stats.OWN_WING_RECOVERY_MOD, wings);
        }
        boolean retain = m.getHullSpec().hasTag(Tags.TAG_RETAIN_SMODS_ON_RECOVERY)
                || m.getVariant().hasTag(Tags.TAG_RETAIN_SMODS_ON_RECOVERY);
        FleetEncounterContext.prepareShipForRecovery(m, ours, true, !ours && !retain, weapons, wings, r);
    }

    /** The game's own recovery screen, over this dialog; then back to it. */
    static void pick(final InteractionDialogAPI dialog, final Runnable after) {
        if (!hasAny()) {
            after.run();
            return;
        }
        dialog.showFleetMemberRecoveryDialog("Select ships to recover", new ArrayList<FleetMemberAPI>(normal),
                new ArrayList<FleetMemberAPI>(story), new FleetMemberPickerListener() {
                    public void pickedFleetMembers(List<FleetMemberAPI> members) {
                        if (members != null && !members.isEmpty()) recover(members, dialog);
                        after.run();
                    }

                    public void cancelledFleetMemberPicking() {
                        after.run();
                    }
                });
    }

    private static void recover(List<FleetMemberAPI> members, InteractionDialogAPI dialog) {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (me == null) return;
        FleetEncounterContext.recoverShips(members, null, me, null);
        List<String> names = new ArrayList<String>();
        for (FleetMemberAPI m : members) {
            normal.remove(m);
            story.remove(m);
            own.remove(m);
            // Our officer or AI core goes back on its own ship (the game gives a recovered ship a new captain).
            PersonAPI c = captains.remove(m);
            boolean officer = c != null && me.getFleetData().getOfficerData(c) != null && !commands(me, c);
            if (c != null && (c.isAICore() || officer)) m.setCaptain(c);
            names.add(m.getShipName() + " (" + m.getHullSpec().getHullName() + ")");
        }
        try {
            ListenerUtil.reportShipsRecovered(members, dialog);
        } catch (RuntimeException e) {
            log.warn("[MultiSector] a mod's ship-recovered listener failed", e);
        }
        me.getFleetData().setSyncNeeded();
        me.forceSync();
        persist();
        if (dialog != null && dialog.getTextPanel() != null) dialog.getTextPanel().addPara("Recovered: " + join(names) + ".");
        log.info("[MultiSector] recovered " + names + " from " + from + "'s battle");
    }

    private static boolean commands(CampaignFleetAPI me, PersonAPI c) {
        for (FleetMemberAPI m : me.getFleetData().getMembersListCopy()) if (m.getCaptain() == c) return true;
        return false;
    }

    /** We're done with what's on offer: our own ships left behind are lost, their AI cores come back. */
    static void leave() {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        int cores = 0;
        for (Map.Entry<FleetMemberAPI, PersonAPI> e : captains.entrySet()) {
            PersonAPI c = e.getValue();
            if (me != null && c.isAICore() && !Misc.isUnremovable(c) && c.getAICoreId() != null) {
                me.getCargo().addCommodity(c.getAICoreId(), 1f);
                cores++;
            }
        }
        if (hasAny()) log.info("[MultiSector] left " + count() + " ships from " + from + "'s battle" + (cores > 0
                ? "; " + cores + " AI cores back in the cargo" : ""));
        normal.clear();
        story.clear();
        own.clear();
        captains.clear();
        offerLater = false;
        persist();
    }

    /** What's on offer, into the save: rebuilt by {@link #restore()} when it loads. */
    private static void persist() {
        try {
            Map<String, Object> data = Global.getSector().getPersistentData();
            if (!hasAny()) {
                data.remove(SAVE_KEY);
                return;
            }
            JSONArray ships = new JSONArray();
            for (int pass = 0; pass < 2; pass++) {
                for (FleetMemberAPI m : pass == 0 ? normal : story) {
                    JSONObject sj = ShipJson.ship(m);
                    sj.put("easy", pass == 0).put("own", own.contains(m));
                    PersonAPI c = captains.get(m);
                    if (c != null && c.isAICore()) sj.put("aiCaptain", ShipJson.person(c, c.getStats()));
                    else if (c != null) sj.put("officerId", c.getId());
                    ships.put(sj);
                }
            }
            data.put(SAVE_KEY, new JSONObject().put("from", from).put("ships", ships).toString());
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't keep the recovery offer in the save", e);
        }
    }

    /** A save loaded with an offer still open: back as it was, offered on the map. */
    static void restore() {
        try {
            Object text = Global.getSector().getPersistentData().get(SAVE_KEY);
            if (!(text instanceof String)) return;
            CampaignFleetAPI me = Global.getSector().getPlayerFleet();
            if (me == null) return;
            JSONObject root = new JSONObject((String) text);
            from = root.optString("from", "a joint battle");
            JSONArray ships = root.optJSONArray("ships");
            List<String> skipped = new ArrayList<String>();
            for (int i = 0; ships != null && i < ships.length(); i++) {
                JSONObject sj = ships.getJSONObject(i);
                FleetMemberAPI m = ShipJson.buildShip(sj, skipped);
                if (m == null) continue;
                if (DModManager.getNumDMods(m.getVariant()) > 0) DModManager.setDHull(m.getVariant());
                m.getStatus().setHullFraction(0.01f);
                (sj.optBoolean("easy") ? normal : story).add(m);
                if (sj.optBoolean("own")) own.add(m);
                String officer = sj.optString("officerId", "");
                if (!officer.isEmpty()) {
                    for (com.fs.starfarer.api.characters.OfficerDataAPI o : me.getFleetData().getOfficersCopy()) {
                        if (officer.equals(o.getPerson().getId())) captains.put(m, o.getPerson());
                    }
                }
                JSONObject ai = sj.optJSONObject("aiCaptain");
                if (ai != null && !ai.optString("aiCore", "").isEmpty()) {
                    PersonAPI p = Global.getFactory().createPerson();
                    ShipJson.applyPerson(p, p.getStats(), ai, null);
                    p.setAICoreId(ai.optString("aiCore"));
                    captains.put(m, p);
                }
            }
            if (hasAny()) {
                offerLater = true;
                log.info("[MultiSector] " + count() + " ships from " + from + "'s battle still to recover (kept in the save)"
                        + (skipped.isEmpty() ? "" : "; missing here: " + skipped));
            }
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't bring back the recovery offer from the save", e);
        }
    }

    /** The offers were another character's (a fresh copy of the host's save): gone, from the save too. */
    static void drop() {
        normal.clear();
        story.clear();
        own.clear();
        captains.clear();
        offerLater = false;
        try {
            Global.getSector().getPersistentData().remove(SAVE_KEY);
        } catch (RuntimeException ignored) {
            // nothing kept
        }
    }

    /** On the map with nothing open: the recovery we couldn't offer in the battle's dialog. */
    static void frame() {
        if (!offerLater || !hasAny()) return;
        if (multisector.ui.CharacterChooser.isPending()) return; // our character first (a fresh copy)
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (ui == null || ui.isShowingDialog() || ui.isShowingMenu() || !ClockSync.isMapView()) return;
        offerLater = false;
        ui.showInteractionDialog(new Dialog(), Global.getSector().getPlayerFleet());
    }

    /** The battle's dialog closed before we got to our ships: offer them on the map instead. */
    static void offerOnMap() {
        if (hasAny()) offerLater = true;
    }

    /** Line for the battle's result, if there's anything to recover. */
    static String summary() {
        if (!hasAny()) return null;
        int mine = own.size();
        return mine > 0 ? "You can recover " + (mine == 1 ? "it" : "some of them") + ": the game's usual chances,"
                + " and some only with a story point."
                : null;
    }

    /** A save is loading: nothing carries over. */
    static void forget() {
        normal.clear();
        story.clear();
        own.clear();
        captains.clear();
        offerLater = false;
        enemyLost.clear();
        spoilsFor.clear();
        leaderPending = false;
    }

    /** On the map: what's on offer from a battle, in a dialog of its own. */
    private static final class Dialog implements InteractionDialogPlugin {
        private InteractionDialogAPI dialog;

        public void init(InteractionDialogAPI dialog) {
            this.dialog = dialog;
            dialog.getTextPanel().addPara("After " + from + "'s battle, " + count() + " ship"
                    + (count() == 1 ? " is" : "s are") + " there to recover"
                    + (own.isEmpty() ? " (" + from + " has taken their pick)." : "."));
            menu();
        }

        private void menu() {
            dialog.getOptionPanel().clearOptions();
            if (hasAny()) dialog.getOptionPanel().addOption("Recover ships (" + count() + ")", "recover");
            dialog.getOptionPanel().addOption("Leave", "leave");
            dialog.setOptionOnEscape("Leave", "leave");
        }

        public void optionSelected(String optionText, Object optionData) {
            if ("recover".equals(optionData)) {
                pick(dialog, new Runnable() {
                    public void run() {
                        menu();
                    }
                });
                return;
            }
            leave();
            dialog.dismiss();
        }

        public void optionMousedOver(String optionText, Object optionData) {
        }

        public void advance(float amount) {
        }

        public void backFromEngagement(EngagementResultAPI battleResult) {
        }

        public Object getContext() {
            return null;
        }

        public Map<String, MemoryAPI> getMemoryMap() {
            return null;
        }
    }

    // ================================================================ leader

    /** Enemy ships lost in our battle, by member id, as they were when they went down. */
    private static final Map<String, JSONObject> enemyLost = new LinkedHashMap<String, JSONObject>();
    /** Players who fought in it with us. */
    private static final Set<Integer> spoilsFor = new HashSet<Integer>();
    private static boolean leaderPending;
    private static long leaderSince;

    /** Our combat with joiners is over: note the enemy's losses before our recovery screen changes them. */
    static void noteEnemyLosses(CombatEngineAPI engine, Set<Integer> joiners) {
        if (engine == null || joiners.isEmpty()) return;
        CombatFleetManagerAPI fm = engine.getFleetManager(1);
        if (fm == null) return;
        int n = 0;
        for (int pass = 0; pass < 2; pass++) {
            List<FleetMemberAPI> list = pass == 0 ? fm.getDisabledCopy() : fm.getDestroyedCopy();
            for (FleetMemberAPI m : list) {
                try {
                    if (m.isFighterWing() || enemyLost.containsKey(m.getId()) || Misc.isUnboardable(m)) continue;
                    CampaignFleetAPI src = m.getFleetData() == null ? null : m.getFleetData().getFleet();
                    if (src != null && src.getMemoryWithoutUpdate().getBoolean(MemFlags.MEMORY_KEY_NO_SHIP_RECOVERY)) continue;
                    JSONObject sj = ShipJson.ship(m);
                    sj.put("destroyed", pass == 1);
                    enemyLost.put(m.getId(), sj);
                    n++;
                } catch (Exception e) {
                    // that one stays out
                }
            }
        }
        spoilsFor.addAll(joiners);
        leaderPending = true;
        leaderSince = System.nanoTime();
        if (n > 0) log.info("[MultiSector] " + n + " enemy ships went down with joiners in the battle; what we leave goes to them");
    }

    /**
     * Our battle dialog is closed: we've taken our pick. The enemy ships
     * that aren't in our fleet now go to everyone who fought with us.
     */
    static void leaderFrame(Session s) {
        if (!leaderPending) return;
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        boolean open = ui != null && ui.getCurrentInteractionDialog() != null;
        if (open && System.nanoTime() - leaderSince < LEADER_WAIT_NANOS) return;
        leaderPending = false;
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        Set<String> mine = new HashSet<String>();
        if (me != null) for (FleetMemberAPI m : me.getFleetData().getMembersListCopy()) mine.add(m.getId());
        JSONArray left = new JSONArray();
        for (Map.Entry<String, JSONObject> e : enemyLost.entrySet()) if (!mine.contains(e.getKey())) left.put(e.getValue());
        int taken = enemyLost.size() - left.length();
        enemyLost.clear();
        try {
            if (left.length() > 0) {
                String text = JointBattle.msg("spoils", 0).put("ships", left).toString();
                for (Integer id : spoilsFor) if (s.isPlayerHere(id)) s.sendBattleMessage(id, text);
                log.info("[MultiSector] took " + taken + " enemy ships; " + left.length() + " left for " + spoilsFor.size()
                        + " joiner(s)");
            }
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't send the ships we left", e);
        }
        spoilsFor.clear();
    }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(parts.get(i));
        }
        return b.toString();
    }
}
