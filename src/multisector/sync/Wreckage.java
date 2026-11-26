package multisector.sync;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.FleetMemberPickerListener;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.listeners.ListenerUtil;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin;
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin.DerelictShipData;
import com.fs.starfarer.api.impl.campaign.FleetEncounterContext;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.PerShipData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.ShipCondition;
import com.fs.starfarer.api.util.Misc;
import multisector.character.ShipJson;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a joint battle's leader leaves behind: one object at the battle's
 * site ("Battle Wreckage", our own entity type) holding every enemy ship
 * the leader didn't take, for anyone to recover, as cargo pods hold cargo.
 *
 * <p>It's shared like other objects in space ({@link WorldObjects}): every
 * game gets it, one player at a time opens it ({@link ObjectLocks}), and
 * what they recover goes from everyone's copy. Once it's empty it goes; if
 * nobody comes for it, it drifts away after {@link #DAYS} days, as a
 * derelict does. It's part of the save like any object in space.
 *
 * <p>The ships it holds are in its memory, as {@link ShipJson} (plus
 * "destroyed"): readied for recovery each time it's opened, with the
 * game's usual chances, d-mods and story-point recoveries.
 *
 * <p>A joiner's own lost ships wait in it too, on their game only
 * ({@link JointSpoils}): recovered from the same screen, by them alone. Its
 * id and place are fixed when the fighting ends and sent with each joiner's
 * result, so their game puts it there at once (empty of enemy ships until
 * the leader has taken their pick), and puts it back if it goes while
 * their ships are still in it.
 */
final class Wreckage {

    private static final Logger log = Global.getLogger(Wreckage.class);
    static final String TYPE = "multisector_battle_wreckage";
    private static final String SHIPS = "$multisector_wreckShips";
    private static final String FROM = "$multisector_wreckFrom";
    /** How long it lasts if nobody empties it. */
    static final float DAYS = 30f;

    private Wreckage() {
    }

    static boolean is(SectorEntityToken t) {
        return t instanceof CustomCampaignEntityAPI && TYPE.equals(((CustomCampaignEntityAPI) t).getCustomEntityType());
    }

    /** The leader, once they've taken their pick: the rest, where the battle was. */
    static SectorEntityToken leave(String id, String locId, float x, float y, String from, JSONArray ships) {
        LocationAPI loc = FleetSync.findLocation(locId);
        if (loc == null) return null;
        SectorEntityToken e = find(loc, id);
        if (e != null) {
            e.getMemoryWithoutUpdate().set(SHIPS, ships.toString());
        } else {
            e = spawn(loc, id, from, ships, DAYS, null);
            if (e == null) return null;
            e.getLocation().set(x, y);
        }
        log.info("[MultiSector] left " + ships.length() + " enemy ships in the wreckage " + id + " in " + locId);
        return e;
    }

    /** A joiner's game: the wreckage their own lost ships wait in (no enemy ships in it yet, or any more). */
    static SectorEntityToken forOwnShips(LocationAPI loc, String id, String from, float x, float y, FleetMemberAPI shown,
                                         long since) {
        float days = Math.max(1f, DAYS - Global.getSector().getClock().getElapsedDaysSince(since));
        SectorEntityToken e = spawn(loc, id, from, new JSONArray(), days, shown);
        if (e != null) {
            e.getLocation().set(x, y);
            log.info("[MultiSector] the wreckage of " + from + "'s battle is where our lost ships wait (" + id + ")");
        }
        return e;
    }

    static SectorEntityToken find(LocationAPI loc, String id) {
        for (CustomCampaignEntityAPI c : loc.getCustomEntities()) if (id.equals(c.getId())) return c;
        return null;
    }

    /** Makes one: shown as the biggest of its ships, drifting. Null if none of them can be built here. */
    private static SectorEntityToken spawn(LocationAPI loc, String id, String from, JSONArray ships, float days,
                                           FleetMemberAPI shown) {
        for (int i = 0; i < ships.length(); i++) {
            try {
                FleetMemberAPI m = ShipJson.buildShip(ships.getJSONObject(i), new ArrayList<String>());
                if (m != null && (shown == null || m.getHullSpec().getHullSize().ordinal()
                        > shown.getHullSpec().getHullSize().ordinal())) {
                    shown = m;
                }
            } catch (Exception ex) {
                // that one can't be shown
            }
        }
        if (shown == null) return null;
        PerShipData psd = new PerShipData(shown.getVariant(), ShipCondition.WRECKED, shown.getShipName(), Factions.NEUTRAL, 0f);
        DerelictShipData data = new DerelictShipData(psd, false);
        data.durationDays = days;
        CustomCampaignEntityAPI e = loc.addCustomEntity(id, "Battle Wreckage (" + from + ")", TYPE, Factions.NEUTRAL, data);
        e.getDropValue().clear(); // the ships are what's in it
        e.getDropRandom().clear();
        e.setDiscoverable(false);
        e.setSensorProfile(1f);
        MemoryAPI mem = e.getMemoryWithoutUpdate();
        mem.set(SHIPS, ships.toString());
        mem.set(FROM, from);
        return e;
    }

    private static JSONArray ships(SectorEntityToken e) {
        try {
            String s = e.getMemoryWithoutUpdate().getString(SHIPS);
            return s == null ? new JSONArray() : new JSONArray(s);
        } catch (Exception ex) {
            return new JSONArray();
        }
    }

    private static String from(SectorEntityToken e) {
        String s = e.getMemoryWithoutUpdate().getString(FROM);
        return s == null || s.isEmpty() ? "a joint battle" : s;
    }

    // ---------------------------------------------------------------- shared with the others (WorldObjects)

    static String state(SectorEntityToken e) {
        String s = e.getMemoryWithoutUpdate().getString(SHIPS);
        return String.valueOf(s == null ? 0 : s.hashCode());
    }

    static void describe(SectorEntityToken e, JSONObject j) throws Exception {
        j.put("ships", ships(e)).put("from", from(e));
        if (e.getCustomPlugin() instanceof DerelictShipEntityPlugin) {
            j.put("days", ((DerelictShipEntityPlugin) e.getCustomPlugin()).getData().durationDays);
        }
    }

    static SectorEntityToken create(LocationAPI loc, String id, JSONObject j) {
        JSONArray ships = j.optJSONArray("ships");
        if (ships == null || ships.length() == 0) return null;
        return spawn(loc, id, j.optString("from", "a joint battle"), ships, (float) j.optDouble("days", DAYS), null);
    }

    static void update(SectorEntityToken e, JSONObject j) {
        JSONArray ships = j.optJSONArray("ships");
        if (ships != null) e.getMemoryWithoutUpdate().set(SHIPS, ships.toString());
    }

    // ---------------------------------------------------------------- opening it

    /** Opening it: what's in it, and the game's recovery screen. */
    static final class Dialog implements InteractionDialogPlugin {
        private InteractionDialogAPI dialog;
        private SectorEntityToken target;

        public void init(InteractionDialogAPI dialog) {
            this.dialog = dialog;
            this.target = dialog.getInteractionTarget();
            try {
                dialog.getVisualPanel().showImagePortion("illustrations", "space_wreckage", 640, 400, 0, 0, 480, 300);
            } catch (RuntimeException ignored) {
                // no picture, then
            }
            int n = count();
            int mine = JointSpoils.waitingIn(target) ? JointSpoils.count() : 0;
            dialog.getTextPanel().addPara("Wreckage from " + from(target) + "'s battle. " + (n == 0
                    ? "Nothing in it can be recovered any more."
                    : n + " ship" + (n == 1 ? " is" : "s are") + " here to recover"
                    + (mine > 0 ? " (" + mine + " of them your own)" : "") + ": the game's usual chances,"
                    + " and some only with a story point."));
            menu();
        }

        /** Enemy ships left in it, and our own waiting in it. */
        private int count() {
            return ships(target).length() + (JointSpoils.waitingIn(target) ? JointSpoils.count() : 0);
        }

        private void menu() {
            int n = count();
            dialog.getOptionPanel().clearOptions();
            if (n > 0) dialog.getOptionPanel().addOption("Recover ships (" + n + ")", "recover");
            dialog.getOptionPanel().addOption("Leave", "leave");
            dialog.setOptionOnEscape("Leave", "leave");
        }

        public void optionSelected(String optionText, Object optionData) {
            if ("recover".equals(optionData)) {
                pick();
                return;
            }
            if (count() == 0 && !target.isExpired()) Misc.fadeAndExpire(target, 1f);
            dialog.dismiss();
        }

        private void pick() {
            final CampaignFleetAPI me = Global.getSector().getPlayerFleet();
            final JSONArray all = ships(target);
            final Map<FleetMemberAPI, Integer> index = new IdentityHashMap<FleetMemberAPI, Integer>();
            List<FleetMemberAPI> normal = new ArrayList<FleetMemberAPI>();
            List<FleetMemberAPI> story = new ArrayList<FleetMemberAPI>();
            List<String> skipped = new ArrayList<String>();
            for (int i = 0; me != null && i < all.length(); i++) {
                try {
                    JSONObject sj = all.getJSONObject(i);
                    FleetMemberAPI m = ShipJson.buildShip(sj, skipped);
                    if (m == null || Misc.isUnboardable(m)) continue;
                    boolean wrecked = sj.optBoolean("destroyed");
                    m.getStatus().setHullFraction(0.01f);
                    boolean easy = Misc.isShipRecoverable(m, me, false, false, wrecked ? 0.5f : 1f);
                    JointSpoils.prepare(m, false, wrecked, me);
                    (easy ? normal : story).add(m);
                    index.put(m, i);
                } catch (Exception ex) {
                    log.warn("[MultiSector] couldn't ready a ship in the wreckage", ex);
                }
            }
            if (!skipped.isEmpty()) log.info("[MultiSector] wreckage: missing here: " + skipped);
            if (JointSpoils.waitingIn(target)) {
                normal.addAll(JointSpoils.normalList());
                story.addAll(JointSpoils.storyList());
            }
            if (normal.isEmpty() && story.isEmpty()) {
                dialog.getTextPanel().addPara("None of these ships can be recovered here (this game doesn't have them).");
                menu();
                return;
            }
            dialog.showFleetMemberRecoveryDialog("Select ships to recover", normal, story, new FleetMemberPickerListener() {
                public void pickedFleetMembers(List<FleetMemberAPI> members) {
                    if (members != null && !members.isEmpty()) recover(me, members, all, index);
                    menu();
                }

                public void cancelledFleetMemberPicking() {
                    menu();
                }
            });
        }

        private void recover(CampaignFleetAPI me, List<FleetMemberAPI> picked, JSONArray all,
                             Map<FleetMemberAPI, Integer> index) {
            List<FleetMemberAPI> ours = new ArrayList<FleetMemberAPI>();
            List<FleetMemberAPI> members = new ArrayList<FleetMemberAPI>();
            for (FleetMemberAPI m : picked) (index.containsKey(m) ? members : ours).add(m);
            if (!ours.isEmpty()) JointSpoils.recoverFromWreckage(ours, dialog); // our own: officers back on them
            if (members.isEmpty()) return;
            FleetEncounterContext.recoverShips(members, null, me, null);
            java.util.Set<Integer> taken = new java.util.HashSet<Integer>();
            List<String> names = new ArrayList<String>();
            for (FleetMemberAPI m : members) {
                Integer i = index.get(m);
                if (i != null) taken.add(i);
                names.add(m.getShipName() + " (" + m.getHullSpec().getHullName() + ")");
            }
            try {
                ListenerUtil.reportShipsRecovered(members, dialog);
            } catch (RuntimeException e) {
                log.warn("[MultiSector] a mod's ship-recovered listener failed", e);
            }
            me.getFleetData().setSyncNeeded();
            me.forceSync();
            JSONArray rest = new JSONArray();
            for (int i = 0; i < all.length(); i++) if (!taken.contains(i)) rest.put(all.opt(i));
            // Everyone's copy follows (WorldObjects sees it change).
            target.getMemoryWithoutUpdate().set(SHIPS, rest.toString());
            StringBuilder b = new StringBuilder();
            for (String s : names) b.append(b.length() == 0 ? "" : ", ").append(s);
            dialog.getTextPanel().addPara("Recovered: " + b + ".");
            log.info("[MultiSector] recovered " + names + " from the wreckage of " + from(target) + "'s battle; "
                    + rest.length() + " left in it");
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
}
