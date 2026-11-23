package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.ColonyPlayerHostileActListener;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.econ.RecentUnrest;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.intel.SystemBountyIntel;
import com.fs.starfarer.api.impl.campaign.intel.SystemBountyManager;
import com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker;
import com.fs.starfarer.api.impl.campaign.population.CoreImmigrationPluginImpl;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import com.fs.starfarer.api.util.Misc;
import multisector.net.Session;
import multisector.ui.MultiSectorConfig;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Facts about the world as a whole, kept the same on every player's game.
 * They travel as world events ({@code "e":"world"}): a player's game sends
 * them, the host applies and relays them (see {@link WorldObjects}).
 *
 * <ul>
 * <li><b>Raids and bombardments</b> ({@code shareRaids}): when a player
 *     raids, disrupts or bombards a colony, their game sends what it left
 *     behind: disrupted industries, unrest, raid memory, pollution, a smaller
 *     size, or a colony destroyed. Every other game raises its copy to match
 *     (never lowers it, so nothing is undone).</li>
 * <li><b>Discoveries</b> ({@code shareSurveys}): surveys, explored ruins and
 *     sites, and defeated defenders, where the player is. One world, one set
 *     of finds: the same ruins can't be looted twice.</li>
 * <li><b>Faction relations</b> ({@code shareFactionRelations}, host only):
 *     wars and peace between NPC factions (Nexerelin's diplomacy included)
 *     are the host's. Each player's own reputation stays theirs.</li>
 * <li><b>System bounties</b> ({@code shareSystemBounties}, host only): the
 *     faction bounties on star systems are the host's. Each player is paid by
 *     their own game for their own kills. Mission bounties, which are scaled
 *     to each player's fleet, stay each player's own.</li>
 * </ul>
 *
 * Transient: added on each load.
 */
public final class WorldState implements EveryFrameScript {

    private static final Logger log = Global.getLogger(WorldState.class);
    private static final long SCAN_NANOS = 2_000_000_000L;
    private static final long RELATIONS_NANOS = 5_000_000_000L;
    private static final long BOUNTIES_NANOS = 10_000_000_000L;
    /** Sent again this often even if unchanged, for anyone who missed it. */
    private static final long RESEND_NANOS = 60_000_000_000L;
    /** A hostile act's result is sent once its dialog closes, or after this long regardless. */
    private static final long ACT_WAIT_NANOS = 30_000_000_000L;

    /** Flags on a planet's or site's memory that record a one-off find. */
    private static final String[] ENTITY_FLAGS = {"$explored", "$siteExplored", "$defenderFleetDefeated"};
    private static final String RUINS = "$ruinsExplored";

    // raids: market id -> {act, queued at}
    private static final Map<String, Object[]> acts = new HashMap<String, Object[]>();
    // discoveries where we are: entity id -> state
    private static LocationAPI scanned;
    private static final Map<String, String> known = new HashMap<String, String>();
    /** Changes for something we're in a dialog with: applied when it closes. */
    private static final List<JSONObject> deferred = new ArrayList<JSONObject>();
    // host
    private static String relationsSent = "";
    private static String bountiesSent = "";
    private static long nextScan, nextRelations, nextBounties, relationsAt, bountiesAt;
    private static boolean loggedRelations;

    public static void forget() {
        acts.clear();
        scanned = null;
        known.clear();
        deferred.clear();
        relationsSent = "";
        bountiesSent = "";
        loggedRelations = false;
    }

    public boolean isDone() {
        return false;
    }

    public boolean runWhilePaused() {
        return true;
    }

    public void advance(float amount) {
        Session s = Session.get();
        boolean on = s.getState() == Session.State.HOSTING
                || (s.getState() == Session.State.CONNECTED && s.getMyId() >= 0);
        if (!on) {
            scanned = null;
            known.clear();
            return;
        }
        long now = System.nanoTime();
        try {
            if (!deferred.isEmpty() && NpcSync.dialogTarget() == null) {
                List<JSONObject> again = new ArrayList<JSONObject>(deferred);
                deferred.clear();
                for (JSONObject j : again) apply(j);
            }
            sendActs(s, now);
            if (now - nextScan > 0) {
                nextScan = now + SCAN_NANOS;
                if (MultiSectorConfig.isShareSurveys()) scanPlaces(s);
            }
            if (s.getState() == Session.State.HOSTING) {
                if (MultiSectorConfig.isShareFactionRelations() && now - nextRelations > 0) {
                    nextRelations = now + RELATIONS_NANOS;
                    sendRelations(s, now);
                }
                if (MultiSectorConfig.isShareSystemBounties() && now - nextBounties > 0) {
                    nextBounties = now + BOUNTIES_NANOS;
                    sendBounties(s, now);
                }
            }
        } catch (Exception e) {
            log.warn("[MultiSector] world state sync failed this frame", e);
        }
    }

    /** A world event of ours ("e":"world"), from another player's game. */
    static boolean handles(JSONObject j) {
        return "world".equals(j.optString("e"));
    }

    static void apply(JSONObject j) {
        String w = j.optString("w");
        try {
            if ("market".equals(w)) {
                if (MultiSectorConfig.isShareRaids()) applyMarket(j);
            } else if ("place".equals(w)) {
                if (MultiSectorConfig.isShareSurveys()) applyPlace(j);
            } else if ("rel".equals(w)) {
                if (MultiSectorConfig.isShareFactionRelations()) applyRelations(j);
            } else if ("bounties".equals(w)) {
                if (MultiSectorConfig.isShareSystemBounties()) applyBounties(j);
            }
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't apply a world change (" + w + "): " + e);
        }
    }

    // ================================================================ raids and bombardments

    /** The game tells us about the player's hostile acts against colonies. Transient: added on each load. */
    public static final class HostileActs implements ColonyPlayerHostileActListener {
        public void reportRaidForValuablesFinishedBeforeCargoShown(InteractionDialogAPI dialog, MarketAPI market,
                                                                   MarketCMD.TempData actionData, CargoAPI cargo) {
            touched(market, "raid");
        }

        public void reportRaidToDisruptFinished(InteractionDialogAPI dialog, MarketAPI market,
                                                MarketCMD.TempData actionData, Industry industry) {
            touched(market, "disrupt");
        }

        public void reportTacticalBombardmentFinished(InteractionDialogAPI dialog, MarketAPI market,
                                                      MarketCMD.TempData actionData) {
            touched(market, "tactical bombardment");
        }

        public void reportSaturationBombardmentFinished(InteractionDialogAPI dialog, MarketAPI market,
                                                        MarketCMD.TempData actionData) {
            touched(market, "saturation bombardment");
        }

        private static void touched(MarketAPI market, String act) {
            if (market == null || !MultiSectorConfig.isShareRaids() || !Session.get().isActive()) return;
            acts.put(market.getId(), new Object[]{act, System.nanoTime(), market});
        }
    }

    private static void sendActs(Session s, long now) throws Exception {
        if (acts.isEmpty()) return;
        boolean free = ClockSync.isMapView();
        for (Iterator<Map.Entry<String, Object[]>> it = acts.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Object[]> e = it.next();
            Object[] a = e.getValue();
            if (!free && now - (Long) a[1] < ACT_WAIT_NANOS) continue; // its dialog is still finishing up
            it.remove();
            MarketAPI m = (MarketAPI) a[2];
            JSONObject j = new JSONObject().put("e", "world").put("w", "market").put("id", e.getKey())
                    .put("act", a[0]).put("by", s.playerName(s.getMyId()));
            MarketAPI live = Global.getSector().getEconomy().getMarket(e.getKey());
            boolean deciv = live == null || live.isPlanetConditionMarketOnly();
            j.put("deciv", deciv);
            if (!deciv) {
                JSONObject ind = new JSONObject();
                for (Industry i : m.getIndustries()) {
                    if (i.isDisrupted()) ind.put(i.getId(), i.getDisruptedDays());
                }
                j.put("ind", ind);
                j.put("unrest", RecentUnrest.getPenalty(m));
                j.put("size", m.getSize());
                j.put("pollution", m.hasCondition(Conditions.POLLUTION));
                MemoryAPI mem = m.getMemoryWithoutUpdate();
                if (mem.contains(MarketCMD.DEFENDER_INCREASE_KEY)) j.put("defenders", mem.getExpire(MarketCMD.DEFENDER_INCREASE_KEY));
            }
            s.sendWorldEvent(j.toString());
            log.info("[MultiSector] sent what our " + a[0] + " of " + m.getName() + " left behind: " + j);
        }
    }

    private static void applyMarket(JSONObject j) throws Exception {
        String id = j.optString("id");
        MarketAPI m = Global.getSector().getEconomy().getMarket(id);
        if (m == null || m.isPlanetConditionMarketOnly()) return; // gone here already
        String by = j.optString("by", "Another player");
        String act = j.optString("act", "raid");
        SectorEntityToken talking = NpcSync.dialogTarget();
        if (talking != null && talking.getMarket() == m) {
            deferred.add(j); // never change a colony under an open dialog
            return;
        }
        if (j.optBoolean("deciv")) {
            log.info("[MultiSector] " + m.getName() + " was destroyed by " + by + "'s " + act + "; here too");
            DecivTracker.decivilize(m, true, true);
            return;
        }
        int changed = 0;
        JSONObject ind = j.optJSONObject("ind");
        if (ind != null) {
            for (Iterator<?> it = ind.keys(); it.hasNext(); ) {
                String iid = (String) it.next();
                Industry i = m.getIndustry(iid);
                float days = (float) ind.optDouble(iid, 0);
                if (i == null || days <= i.getDisruptedDays() + 0.5f) continue;
                i.setDisrupted(days, true);
                changed++;
            }
        }
        int unrest = j.optInt("unrest", 0);
        int own = RecentUnrest.getPenalty(m);
        if (unrest > own) {
            RecentUnrest.get(m).add(unrest - own, Misc.ucFirst(act) + " by " + by);
            changed++;
        }
        if (j.optBoolean("pollution") && !m.hasCondition(Conditions.POLLUTION)) {
            m.addCondition(Conditions.POLLUTION);
            changed++;
        }
        int size = j.optInt("size", m.getSize());
        for (int n = 0; n < 3 && m.getSize() > size && m.getSize() > 1; n++) {
            CoreImmigrationPluginImpl.reduceMarketSize(m);
            changed++;
        }
        Misc.setRaidedTimestamp(m);
        double def = j.optDouble("defenders", -1);
        MemoryAPI mem = m.getMemoryWithoutUpdate();
        if (def > 0 && (!mem.contains(MarketCMD.DEFENDER_INCREASE_KEY) || mem.getExpire(MarketCMD.DEFENDER_INCREASE_KEY) < def)) {
            mem.set(MarketCMD.DEFENDER_INCREASE_KEY, true);
            mem.expire(MarketCMD.DEFENDER_INCREASE_KEY, (float) def);
        }
        log.info("[MultiSector] " + by + "'s " + act + " of " + m.getName() + " reached this world (" + changed + " changes)");
        Session.get().notice(by + "'s " + act + " of " + m.getName() + " left its mark on this world too.");
    }

    // ================================================================ discoveries

    /** Surveys and one-off finds where we are; arriving somewhere only takes stock. */
    private static void scanPlaces(Session s) throws Exception {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        LocationAPI here = me == null ? null : me.getContainingLocation();
        if (here == null || here.isHyperspace()) return;
        boolean arrived = here != scanned;
        if (arrived) {
            scanned = here;
            known.clear();
        }
        String locId = FleetSync.locationId(here);
        for (SectorEntityToken e : here.getAllEntities()) {
            if (e instanceof CampaignFleetAPI) continue;
            String st = placeState(e);
            if (st == null) continue;
            String old = known.put(e.getId(), st);
            if (arrived || old == null || old.equals(st)) continue;
            JSONObject j = describePlace(e, locId);
            s.sendWorldEvent(j.toString());
            log.info("[MultiSector] shared a discovery at " + e.getName() + ": " + j);
        }
    }

    private static int surveyOf(SectorEntityToken e) {
        MarketAPI m = e.getMarket();
        if (m == null || !m.isPlanetConditionMarketOnly() || m.getSurveyLevel() == null) return 0;
        switch (m.getSurveyLevel()) {
            case FULL:
                return 2;
            case PRELIMINARY:
                return 1;
            default:
                return 0;
        }
    }

    /** What can be found at this place, as a short string, or null if nothing worth sharing. */
    private static String placeState(SectorEntityToken e) {
        StringBuilder b = new StringBuilder();
        b.append(surveyOf(e));
        MarketAPI m = e.getMarket();
        b.append(m != null && m.getMemoryWithoutUpdate().getBoolean(RUINS) ? 'R' : '-');
        MemoryAPI mem = e.getMemoryWithoutUpdate();
        for (String f : ENTITY_FLAGS) b.append(mem.getBoolean(f) ? 'Y' : '-');
        b.append(mem.contains("$hasDefenders") && !mem.getBoolean("$hasDefenders") ? 'D' : '-');
        String st = b.toString();
        return st.equals("0-----") ? null : st;
    }

    private static JSONObject describePlace(SectorEntityToken e, String locId) throws Exception {
        JSONObject j = new JSONObject().put("e", "world").put("w", "place").put("loc", locId).put("id", e.getId());
        j.put("survey", surveyOf(e));
        MarketAPI m = e.getMarket();
        if (m != null && m.getMemoryWithoutUpdate().getBoolean(RUINS)) j.put("ruins", true);
        MemoryAPI mem = e.getMemoryWithoutUpdate();
        JSONArray flags = new JSONArray();
        for (String f : ENTITY_FLAGS) if (mem.getBoolean(f)) flags.put(f);
        j.put("flags", flags);
        if (mem.contains("$hasDefenders") && !mem.getBoolean("$hasDefenders")) j.put("defendersGone", true);
        return j;
    }

    private static void applyPlace(JSONObject j) throws Exception {
        LocationAPI loc = FleetSync.findLocation(j.optString("loc"));
        if (loc == null) return;
        SectorEntityToken e = loc.getEntityById(j.optString("id"));
        if (e == null) return;
        if (e == NpcSync.dialogTarget()) {
            deferred.add(j);
            return;
        }
        MarketAPI m = e.getMarket();
        int survey = j.optInt("survey", 0);
        if (m != null && m.isPlanetConditionMarketOnly() && survey > surveyOf(e)) {
            if (survey >= 2) Misc.setFullySurveyed(m, null, false);
            else Misc.setPreliminarySurveyed(m, null, false);
        }
        if (j.optBoolean("ruins") && m != null) m.getMemoryWithoutUpdate().set(RUINS, true);
        MemoryAPI mem = e.getMemoryWithoutUpdate();
        JSONArray flags = j.optJSONArray("flags");
        for (int i = 0; flags != null && i < flags.length(); i++) {
            String f = flags.optString(i, "");
            for (String allowed : ENTITY_FLAGS) if (allowed.equals(f)) mem.set(f, true);
        }
        if (j.optBoolean("defendersGone")) mem.set("$hasDefenders", false);
        if (loc == scanned) {
            String st = placeState(e);
            if (st != null) known.put(e.getId(), st); // ours now: not news to send back
        }
        log.info("[MultiSector] " + e.getName() + " was surveyed or explored on another player's game; here too");
    }

    // ================================================================ faction relations (host)

    private static void sendRelations(Session s, long now) throws Exception {
        List<FactionAPI> all = Global.getSector().getAllFactions();
        JSONObject r = new JSONObject();
        for (int a = 0; a < all.size(); a++) {
            FactionAPI fa = all.get(a);
            if (fa.isPlayerFaction()) continue;
            for (int b = a + 1; b < all.size(); b++) {
                FactionAPI fb = all.get(b);
                if (fb.isPlayerFaction()) continue;
                float v = fa.getRelationship(fb.getId());
                r.put(fa.getId() + "|" + fb.getId(), Math.round(v * 1000f) / 1000.0);
            }
        }
        String text = r.toString();
        if (text.equals(relationsSent) && now - relationsAt < RESEND_NANOS) return;
        relationsSent = text;
        relationsAt = now;
        s.sendWorldEvent(new JSONObject().put("e", "world").put("w", "rel").put("r", r).toString());
    }

    private static void applyRelations(JSONObject j) throws Exception {
        if (Session.get().getState() == Session.State.HOSTING) return; // the host's own are the truth
        JSONObject r = j.optJSONObject("r");
        if (r == null) return;
        int changed = 0;
        for (Iterator<?> it = r.keys(); it.hasNext(); ) {
            String key = (String) it.next();
            int bar = key.indexOf('|');
            if (bar <= 0) continue;
            FactionAPI a = Global.getSector().getFaction(key.substring(0, bar));
            String bid = key.substring(bar + 1);
            if (a == null || Global.getSector().getFaction(bid) == null || a.isPlayerFaction()) continue;
            if (Global.getSector().getFaction(bid).isPlayerFaction()) continue;
            float v = (float) r.optDouble(key, 0);
            if (Math.abs(a.getRelationship(bid) - v) < 0.002f) continue;
            a.setRelationship(bid, v);
            changed++;
        }
        if (changed > 0 && (!loggedRelations || changed < 10)) {
            loggedRelations = true;
            log.info("[MultiSector] " + changed + " relations between factions set to the host's");
        }
    }

    // ================================================================ system bounties (host)

    private static void sendBounties(Session s, long now) throws Exception {
        SystemBountyManager mgr = SystemBountyManager.getInstance();
        if (mgr == null) return;
        JSONArray list = new JSONArray();
        for (EveryFrameScript es : mgr.getActive()) {
            if (!(es instanceof SystemBountyIntel)) continue;
            SystemBountyIntel b = (SystemBountyIntel) es;
            if (b.isDone() || b.isEnding() || b.isCommerceMode() || b.getMarket() == null) continue;
            list.put(new JSONObject().put("m", b.getMarket().getId()).put("el", b.getElapsedDays())
                    .put("dur", b.getDuration()).put("base", b.getBaseBounty()));
        }
        // Elapsed days change all the time: only the set of bounties counts as a change.
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < list.length(); i++) sig.append(list.getJSONObject(i).optString("m")).append(',');
        if (sig.toString().equals(bountiesSent) && now - bountiesAt < RESEND_NANOS) return;
        bountiesSent = sig.toString();
        bountiesAt = now;
        s.sendWorldEvent(new JSONObject().put("e", "world").put("w", "bounties").put("list", list).toString());
    }

    private static void applyBounties(JSONObject j) throws Exception {
        if (Session.get().getState() == Session.State.HOSTING) return;
        SystemBountyManager mgr = SystemBountyManager.getInstance();
        JSONArray list = j.optJSONArray("list");
        if (mgr == null || list == null) return;
        Set<String> want = new HashSet<String>();
        for (int i = 0; i < list.length(); i++) want.add(list.getJSONObject(i).optString("m"));
        // Our game's own system bounties that the host's world doesn't have: ended (usually before they show).
        for (EveryFrameScript es : new ArrayList<EveryFrameScript>(mgr.getActive())) {
            if (!(es instanceof SystemBountyIntel)) continue;
            SystemBountyIntel b = (SystemBountyIntel) es;
            if (b.isDone() || b.isEnding() || b.isCommerceMode() || b.getMarket() == null) continue;
            if (want.contains(b.getMarket().getId())) continue;
            b.endImmediately();
            log.info("[MultiSector] ended this game's own bounty at " + b.getMarket().getName() + " (not in the host's world)");
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            MarketAPI m = Global.getSector().getEconomy().getMarket(o.optString("m"));
            if (m == null || m.getFaction() == null || m.getFaction().isPlayerFaction()) continue;
            SystemBountyIntel b = null;
            for (EveryFrameScript es : mgr.getActive()) {
                // Not one from a player's own mission (commerce mode): those keep their own timer.
                if (es instanceof SystemBountyIntel && ((SystemBountyIntel) es).getMarket() == m
                        && !((SystemBountyIntel) es).isCommerceMode() && !((SystemBountyIntel) es).isDone()) {
                    b = (SystemBountyIntel) es;
                }
            }
            if (b == null) {
                b = new SystemBountyIntel(m, (int) o.optDouble("base", -1), false);
                if (b.isDone()) continue;
                mgr.addActive(b);
                log.info("[MultiSector] the host's bounty at " + m.getName() + " is posted here too");
            }
            b.setDuration((float) o.optDouble("dur", b.getDuration()));
            b.setElapsedDays((float) o.optDouble("el", b.getElapsedDays()));
        }
    }
}
