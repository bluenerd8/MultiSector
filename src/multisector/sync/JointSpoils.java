package multisector.sync;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.FleetMemberPickerListener;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
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
 * <li><b>Their own lost ships</b>, theirs alone: they wait in the battle's
 *     wreckage ({@link Wreckage}) at the battle's site, to recover there when
 *     they want (the game's usual chances, d-mods and story-point
 *     recoveries), with nothing popping up after the battle. Nobody else
 *     sees them in it. Officers go back on the ships recovered; left there
 *     for {@link Wreckage#DAYS} days, they're lost, and an AI core on one
 *     goes back into the cargo.</li>
 * <li><b>Enemy ships the leader left</b>: the leader takes their spoils
 *     first, as always. Once the leader's battle dialog closes, the enemy
 *     ships lost in the battle that aren't in the leader's fleet stay at the
 *     battle's site in one object, the battle's wreckage ({@link Wreckage}),
 *     for anyone to fly to and recover, as cargo pods hold cargo.</li>
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
    /** Ships arrived while no dialog of ours was open: offer them when the map is free (only offers from before the wreckage). */
    private static boolean offerLater;
    /** The battle's wreckage our ships wait in: its id, place, and when the battle was. Null: offered in a dialog instead. */
    private static String anchorId;
    private static String anchorLoc;
    private static float anchorX, anchorY;
    private static long anchorSince;
    private static long nextAnchorCheck;

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
    static void ownLost(String leaderName, List<FleetMemberAPI> lost, Set<String> destroyed, JSONObject wreck) {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (me == null || lost.isEmpty()) return;
        from = leaderName;
        // Where they'll wait: the leader's wreckage for this battle, or one of our own right here.
        LocationAPI here = me.getContainingLocation();
        anchorId = wreck != null && !wreck.optString("id", "").isEmpty() ? wreck.optString("id")
                : "multisector_wreckage_own_" + Session.get().getMyId() + "_" + System.currentTimeMillis();
        String loc = wreck == null ? "" : wreck.optString("loc", "");
        boolean there = !loc.isEmpty() && FleetSync.findLocation(loc) != null;
        anchorLoc = there ? loc : here == null ? "" : FleetSync.locationId(here);
        anchorX = there ? (float) wreck.optDouble("x") : me.getLocation().x;
        anchorY = there ? (float) wreck.optDouble("y") : me.getLocation().y;
        anchorSince = Global.getSector().getClock().getTimestamp();
        nextAnchorCheck = 0;
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

    /** The leader has taken their pick and left the rest in the battle's wreckage. */
    static void leftovers(String leaderName, JSONObject j) {
        int n = j.optInt("count");
        if (n <= 0) return;
        log.info("[MultiSector] " + leaderName + " left " + n + " enemy ships in the battle's wreckage");
        Session.get().notice(leaderName + " has taken their pick: " + n + " ship" + (n == 1 ? "" : "s")
                + " left in the Battle Wreckage where the battle was. Fly to it to recover them.");
    }

    /** As the game readies a ship for its recovery screen: d-mods, and what survives of weapons and wings. */
    static void prepare(FleetMemberAPI m, boolean ours, boolean wrecked, CampaignFleetAPI me) {
        prepare(m, ours, wrecked, me, new Random(1000000L * m.getId().hashCode() + Global.getSector().getPlayerBattleSeed()));
    }

    /** The same, with the dice given: a wreckage's ships roll the same every time it's opened. */
    static void prepare(FleetMemberAPI m, boolean ours, boolean wrecked, CampaignFleetAPI me, Random r) {
        ShipVariantAPI v = m.getVariant().clone();
        v.setSource(VariantSource.REFIT);
        m.setVariant(v, false, true);
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

    /** Our own ships waiting in this wreckage. */
    static boolean waitingIn(SectorEntityToken wreckage) {
        return hasAny() && anchorId != null && wreckage != null && anchorId.equals(wreckage.getId());
    }

    static List<FleetMemberAPI> normalList() {
        return normal;
    }

    static List<FleetMemberAPI> storyList() {
        return story;
    }

    static boolean offered(FleetMemberAPI m) {
        return normal.contains(m) || story.contains(m);
    }

    /** Our ships waiting in a wreckage: recovered from its dialog. */
    static void recoverFromWreckage(List<FleetMemberAPI> members, InteractionDialogAPI dialog) {
        recover(members, dialog);
        if (!hasAny()) leave();
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
        anchorId = null;
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
            JSONObject root = new JSONObject().put("from", from).put("ships", ships);
            if (anchorId != null) {
                root.put("anchor", new JSONObject().put("id", anchorId).put("loc", anchorLoc).put("x", anchorX)
                        .put("y", anchorY).put("since", anchorSince));
            }
            data.put(SAVE_KEY, root.toString());
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
            JSONObject anchor = root.optJSONObject("anchor");
            if (anchor != null) {
                anchorId = anchor.optString("id");
                anchorLoc = anchor.optString("loc");
                anchorX = (float) anchor.optDouble("x");
                anchorY = (float) anchor.optDouble("y");
                anchorSince = anchor.optLong("since");
                nextAnchorCheck = 0;
            }
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
                offerLater = anchorId == null;
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
        anchorId = null;
        try {
            Global.getSector().getPersistentData().remove(SAVE_KEY);
        } catch (RuntimeException ignored) {
            // nothing kept
        }
    }

    /**
     * Our ships waiting in a wreckage: it stays there for them (put back if
     * it went, as when another player emptied it of the enemy ships), and
     * they're lost once it's been there too long. Offers from before the
     * wreckage: on the map with nothing open, in a dialog.
     */
    static void frame() {
        if (anchorId != null && hasAny()) {
            anchorFrame();
            return;
        }
        if (!offerLater || !hasAny()) return;
        if (multisector.ui.CharacterChooser.isPending()) return; // our character first (a fresh copy)
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (ui == null || ui.isShowingDialog() || ui.isShowingMenu() || !ClockSync.isMapView()) return;
        offerLater = false;
        ui.showInteractionDialog(new Dialog(), Global.getSector().getPlayerFleet());
    }

    private static void anchorFrame() {
        long now = System.nanoTime();
        if (now - nextAnchorCheck < 0) return;
        nextAnchorCheck = now + 2_000_000_000L;
        if (multisector.ui.CharacterChooser.isPending()) return; // our character first (a fresh copy)
        if (Global.getSector().getClock().getElapsedDaysSince(anchorSince) > Wreckage.DAYS) {
            log.info("[MultiSector] our ships in the wreckage of " + from + "'s battle drifted away unrecovered");
            Session.get().notice("Your ships left in the wreckage of " + from + "'s battle have drifted away.");
            leave();
            return;
        }
        LocationAPI loc = FleetSync.findLocation(anchorLoc);
        if (loc == null || Wreckage.find(loc, anchorId) != null) return;
        FleetMemberAPI shown = !normal.isEmpty() ? normal.get(0) : story.get(0);
        Wreckage.forOwnShips(loc, anchorId, from, anchorX, anchorY, shown, anchorSince);
    }

    /** The battle's dialog closed before we got to our ships: offer them on the map instead (offers from before the wreckage). */
    static void offerOnMap() {
        if (hasAny() && anchorId == null) offerLater = true;
    }

    /** Our ships wait in the wreckage, not in a dialog. */
    static boolean anchored() {
        return anchorId != null;
    }

    /** Line for the battle's result, if there's anything to recover. */
    static String summary() {
        if (!hasAny()) return null;
        int mine = own.size();
        if (mine == 0) return null;
        return anchorId != null ? (mine == 1 ? "It can" : "Some of them can") + " be recovered: "
                + (mine == 1 ? "it waits" : "they wait") + " in the Battle Wreckage where the battle was, for you"
                + " alone. Open it whenever you like (within " + (int) Wreckage.DAYS + " days)."
                : "You can recover " + (mine == 1 ? "it" : "some of them") + ": the game's usual chances,"
                + " and some only with a story point.";
    }

    /** A save is loading: nothing carries over. */
    static void forget() {
        normal.clear();
        story.clear();
        own.clear();
        captains.clear();
        offerLater = false;
        anchorId = null;
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
    /** Our battle's wreckage: id and place, fixed when the fighting ends (joiners are told, in their result). */
    private static String wreckId;
    private static String wreckLoc;
    private static float wreckX, wreckY;
    private static final long RUN = System.currentTimeMillis();

    /** Our battle's wreckage, for the joiners' results: where their lost ships wait, and ours will go. */
    static JSONObject wreckSpot() {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        String id = "multisector_wreckage_" + Session.get().getMyId() + "_" + JointBattle.battleNumber() + "_" + RUN;
        if (!id.equals(wreckId) && me != null && me.getContainingLocation() != null) {
            wreckId = id;
            wreckLoc = FleetSync.locationId(me.getContainingLocation());
            wreckX = me.getLocation().x;
            wreckY = me.getLocation().y;
        }
        try {
            return wreckId == null ? null : new JSONObject().put("id", wreckId).put("loc", wreckLoc).put("x", wreckX)
                    .put("y", wreckY);
        } catch (Exception e) {
            return null;
        }
    }

    /** Our combat started: losses from an earlier one are no business of this one. */
    static void leaderCombatStarted() {
        if (leaderPending) return; // another round of the same encounter (a pursuit): it all goes in together
        enemyLost.clear();
        spoilsFor.clear();
    }

    /**
     * Now and then in our combat while players are in it: the enemy's
     * losses so far. A combat can end without our plugin seeing it end
     * (ended from the menu, say), so what's noted here is what's left then.
     */
    static void watchEnemyLosses(CombatEngineAPI engine, Set<Integer> joiners) {
        if (engine == null || joiners.isEmpty()) return;
        spoilsFor.addAll(joiners);
        collect(engine, false);
    }

    /** Combat ended without our plugin seeing it: what we noted in it is what's left. */
    static void combatEndedUnseen() {
        if (spoilsFor.isEmpty() || leaderPending) return;
        log.info("[MultiSector] our combat ended unseen; " + enemyLost.size() + " enemy losses noted in it");
        wreckSpot();
        leaderPending = true;
        leaderSince = System.nanoTime();
    }

    /** Our combat with joiners is over: note the enemy's losses before our recovery screen changes them. */
    static void noteEnemyLosses(CombatEngineAPI engine, Set<Integer> joiners) {
        if (engine == null || joiners.isEmpty()) return;
        collect(engine, true);
        spoilsFor.addAll(joiners);
        wreckSpot();
        leaderPending = true;
        leaderSince = System.nanoTime();
    }

    private static void collect(CombatEngineAPI engine, boolean last) {
        CombatFleetManagerAPI fm = engine.getFleetManager(1);
        if (fm == null) return;
        // The game's lists of the enemy's disabled and destroyed ships, and any enemy wreck still on the field
        // (in case those lists come back empty, as they did with a fleet copied from another player's world).
        List<FleetMemberAPI> disabled = new java.util.ArrayList<FleetMemberAPI>(fm.getDisabledCopy());
        List<FleetMemberAPI> destroyed = new java.util.ArrayList<FleetMemberAPI>(fm.getDestroyedCopy());
        Set<String> listed = new HashSet<String>();
        for (FleetMemberAPI m : disabled) listed.add(m.getId());
        for (FleetMemberAPI m : destroyed) listed.add(m.getId());
        int wrecks = 0;
        for (com.fs.starfarer.api.combat.ShipAPI ship : engine.getShips()) {
            FleetMemberAPI m = ship.getFleetMember();
            if (m == null || ship.getOriginalOwner() != 1 || ship.isFighter() || ship.getParentStation() != null) continue;
            if ((ship.isHulk() || !ship.isAlive()) && listed.add(m.getId())) {
                disabled.add(m);
                wrecks++;
            }
        }
        int n = 0, unboardable = 0, noRecovery = 0, errors = 0;
        String firstError = null;
        for (int pass = 0; pass < 2; pass++) {
            List<FleetMemberAPI> list = pass == 0 ? disabled : destroyed;
            for (FleetMemberAPI m : list) {
                try {
                    if (m.isFighterWing() || enemyLost.containsKey(m.getId())) continue;
                    if (Misc.isUnboardable(m)) {
                        unboardable++;
                        continue;
                    }
                    CampaignFleetAPI src = m.getFleetData() == null ? null : m.getFleetData().getFleet();
                    if (src != null && src.getMemoryWithoutUpdate().getBoolean(MemFlags.MEMORY_KEY_NO_SHIP_RECOVERY)) {
                        noRecovery++;
                        continue;
                    }
                    JSONObject sj = ShipJson.ship(m);
                    sj.put("destroyed", pass == 1);
                    sj.put("id", m.getId()); // the wreckage's dice for it: the same each time it's opened
                    enemyLost.put(m.getId(), sj);
                    n++;
                } catch (Exception e) {
                    errors++;
                    if (firstError == null) firstError = e.toString();
                }
            }
        }
        if (!last && n == 0 && errors == 0) return;
        log.info("[MultiSector] enemy losses with joiners in the battle" + (last ? " (at the end)" : "") + ": "
                + fm.getDisabledCopy().size() + " disabled, " + fm.getDestroyedCopy().size() + " destroyed, " + wrecks
                + " more wrecks on the field; " + n + " new, " + enemyLost.size() + " in all"
                + (unboardable > 0 ? ", unboardable " + unboardable : "") + (noRecovery > 0 ? ", no recovery " + noRecovery : "")
                + (errors > 0 ? ", errors " + errors + " (" + firstError + ")" : ""));
    }

    /**
     * Our battle dialog is closed: we've taken our pick. The enemy ships
     * that aren't in our fleet stay where the battle was, in its wreckage,
     * and everyone who fought with us is told.
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
            if (left.length() > 0 && wreckId != null
                    && Wreckage.leave(wreckId, wreckLoc, wreckX, wreckY, s.playerName(s.getMyId()), left) != null) {
                String text = JointBattle.msg("spoils", 0).put("count", left.length()).toString();
                for (Integer id : spoilsFor) if (s.isPlayerHere(id)) s.sendBattleMessage(id, text);
                log.info("[MultiSector] took " + taken + " enemy ships; " + left.length() + " left in the wreckage for "
                        + spoilsFor.size() + " joiner(s)");
                s.notice(left.length() + " ship" + (left.length() == 1 ? "" : "s")
                        + " you didn't take stay in the Battle Wreckage here, for the others to recover.");
            }
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't leave the ships we didn't take", e);
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
