package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityPlugin;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.CampaignObjective;
import com.fs.starfarer.api.impl.campaign.CargoPodsEntityPlugin;
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin;
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin.DerelictShipData;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.procgen.SalvageEntityGenDataSpec.DropData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.PerShipData;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.ShipCondition;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.ShipRecoverySpecialData;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldParams;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin.DebrisFieldSource;
import com.fs.starfarer.api.util.Misc;
import multisector.character.ShipJson;
import multisector.net.Session;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Battle wreckage and other objects in space, the same on every game.
 *
 * <p>Each game watches the place its player is in, once a second, for the
 * kinds of object below. When one appears there (a debris field or derelict
 * after a battle, jettisoned cargo pods, a newly built comm relay), changes
 * (a debris field salvaged, an objective taken over or hacked) or disappears
 * (salvaged, picked up, deconstructed), it tells the host. The host does the
 * same to its own world and passes it on, so everyone's world gets it too,
 * wherever they are. New objects get the same id everywhere, so later
 * changes reach them.
 *
 * <ul>
 * <li>debris fields: size, density, salvage value and drops, recoverable ships;</li>
 * <li>derelict ships ("wreck"): the ship, its condition, how long it lasts;</li>
 * <li>cargo pods: their cargo;</li>
 * <li>comm relays, nav buoys, sensor arrays (and makeshift ones) and the
 *     stable locations they're built on: owner, hacked or not;</li>
 * <li>anything else salvageable from the save (derelict stations, probes,
 *     caches): only its removal, since this can't rebuild arbitrary ones.</li>
 * </ul>
 *
 * Objects aren't locked while someone salvages them: two players salvaging
 * the same thing at the same moment could both get the loot.
 *
 * Transient: added on each load.
 */
public final class WorldObjects implements EveryFrameScript {

    private static final long SCAN_NANOS = 1_000_000_000L;
    private static final Logger log = Global.getLogger(WorldObjects.class);

    private static final String DEBRIS = "debris";
    private static final String WRECK = "wreck";
    private static final String PODS = "pods";
    private static final String OBJECTIVE = "objective";
    private static final String STABLE = "stable";
    /** Salvageable things from the save: we only pass on that they're gone. */
    private static final String OTHER = "other";

    /** The place we last looked at, and what was there: id -> state. */
    private static LocationAPI scanned;
    private static final Map<String, String> known = new HashMap<String, String>();
    private long nextScan;
    /** Owner: when we last sent followers everything around us, and to whom (to resend when that changes). */
    private long nextSnapshot;
    private String snapshotFor = "";
    private static final long SNAPSHOT_NANOS = 20_000_000_000L;
    /** Changes to the object this player is in a dialog with right now: applied once the dialog closes. */
    private static final List<JSONObject> deferred = new ArrayList<JSONObject>();

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
        if (!deferred.isEmpty()) {
            List<JSONObject> again = new ArrayList<JSONObject>(deferred);
            deferred.clear();
            for (JSONObject j : again) applySafely(j);
        }
        String ev;
        for (int i = 0; i < 30 && (ev = s.pollWorldEvent()) != null; i++) {
            try {
                applySafely(new JSONObject(ev));
            } catch (Exception e) {
                log.warn("[MultiSector] couldn't read a world object change: " + e);
            }
        }
        long now = System.nanoTime();
        if (now - nextScan < 0) return;
        nextScan = now + SCAN_NANOS;
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        LocationAPI here = me == null ? null : me.getContainingLocation();
        if (here == null) return;
        try {
            scan(s, here);
            snapshot(s, me, here, now);
        } catch (RuntimeException e) {
            log.warn("[MultiSector] world object scan failed", e);
        }
    }

    /**
     * Owner: everything around us that others follow, now and then. Changes
     * only reach players who were there when they happened, so a player
     * arriving later wouldn't have, say, a derelict that was already here.
     * Their game adds what it's missing (and updates what it has).
     */
    private void snapshot(Session s, CampaignFleetAPI me, LocationAPI here, long now) {
        int myId = s.getMyId();
        if (s.ownerOf(myId) != myId || !s.hasFollowers(myId)) {
            snapshotFor = "";
            return;
        }
        String key = FleetSync.locationId(here) + s.followersOf(myId);
        if (key.equals(snapshotFor) && now - nextSnapshot < 0) return;
        snapshotFor = key;
        nextSnapshot = now + SNAPSHOT_NANOS;
        String locId = FleetSync.locationId(here);
        int sent = 0;
        for (SectorEntityToken e : objects(here).values()) {
            String k = kind(e);
            if (OTHER.equals(k)) continue; // can't be rebuilt elsewhere anyway
            if (here.isHyperspace() && Misc.getDistance(e.getLocation(), me.getLocation()) > NpcSync.PUB_RADIUS) continue;
            JSONObject j = describe(e, locId, "add");
            if (j == null) continue;
            s.sendWorldEvent(j.toString());
            sent++;
        }
        if (sent > 0) log.info("[MultiSector] sent " + sent + " objects around us in " + locId + " to the players here");
    }

    /** What changed here since last time goes to everyone. Arriving somewhere just takes stock. */
    private static void scan(Session s, LocationAPI here) {
        Map<String, SectorEntityToken> now = objects(here);
        if (here != scanned) {
            scanned = here;
            known.clear();
            for (Map.Entry<String, SectorEntityToken> e : now.entrySet()) known.put(e.getKey(), state(e.getValue()));
            return;
        }
        String locId = FleetSync.locationId(here);
        for (Map.Entry<String, SectorEntityToken> e : now.entrySet()) {
            String id = e.getKey();
            String st = state(e.getValue());
            String old = known.put(id, st);
            if (old != null && old.equals(st)) continue;
            JSONObject j = describe(e.getValue(), locId, old == null ? "add" : "set");
            if (j == null) continue;
            s.sendWorldEvent(j.toString());
            log.info("[MultiSector] " + (old == null ? "new " : "changed ") + kind(e.getValue()) + " "
                    + e.getValue().getName() + " (" + id + ") in " + locId);
        }
        for (Iterator<Map.Entry<String, String>> it = known.entrySet().iterator(); it.hasNext(); ) {
            String id = it.next().getKey();
            if (now.containsKey(id)) continue;
            it.remove();
            try {
                s.sendWorldEvent(new JSONObject().put("e", "del").put("loc", locId).put("id", id).toString());
                log.info("[MultiSector] " + id + " is gone from " + locId);
            } catch (Exception ex) {
                // never happens with these values
            }
        }
    }

    /** The objects of the kinds we share, by id. */
    private static Map<String, SectorEntityToken> objects(LocationAPI loc) {
        Map<String, SectorEntityToken> out = new HashMap<String, SectorEntityToken>();
        for (CampaignTerrainAPI t : loc.getTerrainCopy()) {
            if (t.getPlugin() instanceof DebrisFieldTerrainPlugin && t.getId() != null) out.put(t.getId(), t);
        }
        for (CustomCampaignEntityAPI c : loc.getCustomEntities()) {
            if (c.getId() == null || kind(c) == null) continue;
            if (PODS.equals(kind(c)) && c.getCargo() == null) {
                broken(c);
                continue;
            }
            out.put(c.getId(), c);
        }
        return out;
    }

    /**
     * Cargo pods with no cargo at all (not empty: none). The game's own pods
     * dialog fails on them, and there's nothing in them to share: let them
     * fade away, on this game, which tells the others they're gone.
     */
    private static void broken(CustomCampaignEntityAPI pods) {
        if (pods.isExpired() || pods.getMemoryWithoutUpdate().getBoolean("$multisector_brokenPods")) return;
        pods.getMemoryWithoutUpdate().set("$multisector_brokenPods", true);
        try {
            Misc.fadeAndExpire(pods);
            log.info("[MultiSector] cargo pods " + pods.getId() + " have no cargo hold (the game can't open them): removing them");
        } catch (RuntimeException e) {
            log.warn("[MultiSector] couldn't remove broken cargo pods " + pods.getId(), e);
        }
    }

    private static String kind(SectorEntityToken e) {
        if (e instanceof CampaignTerrainAPI) {
            return ((CampaignTerrainAPI) e).getPlugin() instanceof DebrisFieldTerrainPlugin ? DEBRIS : null;
        }
        if (e.hasTag(Tags.FADING_OUT_AND_EXPIRING)) return null; // on its way out; its removal follows
        String type = e.getCustomEntityType();
        if (Entities.WRECK.equals(type) && e.getCustomPlugin() instanceof DerelictShipEntityPlugin) return WRECK;
        if (Entities.CARGO_PODS.equals(type)) return PODS;
        if (e.hasTag(Tags.OBJECTIVE)) return OBJECTIVE;
        if (e.hasTag(Tags.STABLE_LOCATION) || Entities.STABLE_LOCATION.equals(type)) return STABLE;
        if (e.hasTag(Tags.SALVAGEABLE) && !e.getMemoryWithoutUpdate().getBoolean(MemFlags.ENTITY_MISSION_IMPORTANT)) {
            return OTHER;
        }
        return null;
    }

    /** What we watch for changes, per kind. */
    private static String state(SectorEntityToken e) {
        String k = kind(e);
        if (DEBRIS.equals(k)) {
            DebrisFieldTerrainPlugin p = (DebrisFieldTerrainPlugin) ((CampaignTerrainAPI) e).getPlugin();
            return Math.round(p.getParams().density * 100f) + "|" + p.isScavenged();
        }
        if (OBJECTIVE.equals(k)) return e.getFaction().getId() + "|" + hacked(e);
        if (PODS.equals(k)) {
            try {
                return String.valueOf(MarketSync.stacks(e.getCargo()).toString().hashCode());
            } catch (Exception ex) {
                return "";
            }
        }
        return k == null ? "" : k;
    }

    private static boolean hacked(SectorEntityToken e) {
        CustomCampaignEntityPlugin p = e.getCustomPlugin();
        return p instanceof CampaignObjective && Boolean.TRUE.equals(((CampaignObjective) p).isHacked());
    }

    // ================================================================ describing

    private static JSONObject describe(SectorEntityToken e, String locId, String event) {
        try {
            String k = kind(e);
            JSONObject j = new JSONObject();
            j.put("e", event).put("loc", locId).put("id", e.getId()).put("kind", k);
            j.put("type", e.getCustomEntityType() == null ? "" : e.getCustomEntityType());
            j.put("name", e.getName() == null ? "" : e.getName());
            j.put("faction", e.getFaction() == null ? Factions.NEUTRAL : e.getFaction().getId());
            j.put("x", e.getLocation().x).put("y", e.getLocation().y);
            j.put("vx", e.getVelocity().x).put("vy", e.getVelocity().y);
            if (e.getOrbit() != null && e.getOrbitFocus() != null && e.getOrbitFocus().getId() != null) {
                SectorEntityToken f = e.getOrbitFocus();
                JSONObject o = new JSONObject();
                o.put("f", f.getId());
                o.put("r", Misc.getDistance(f.getLocation(), e.getLocation()));
                o.put("a", Misc.getAngleInDegrees(f.getLocation(), e.getLocation()));
                o.put("p", e.getOrbit().getOrbitalPeriod());
                j.put("orbit", o);
            }
            j.put("expires", e.hasTag(Tags.EXPIRES));
            if (DEBRIS.equals(k)) describeDebris((CampaignTerrainAPI) e, j);
            else if (WRECK.equals(k)) describeWreck(e, j);
            else if (PODS.equals(k)) describePods(e, j);
            else if (OBJECTIVE.equals(k)) j.put("hacked", hacked(e));
            return j;
        } catch (Exception ex) {
            log.warn("[MultiSector] couldn't describe " + e.getId() + ": " + ex);
            return null;
        }
    }

    private static void describeDebris(CampaignTerrainAPI t, JSONObject j) throws Exception {
        DebrisFieldTerrainPlugin p = (DebrisFieldTerrainPlugin) t.getPlugin();
        DebrisFieldParams d = p.getParams();
        JSONObject o = new JSONObject();
        o.put("bw", d.bandWidthInEngine).put("mid", d.middleRadius);
        o.put("density", d.density).put("baseDensity", d.baseDensity);
        o.put("lasts", d.lastsDays).put("glows", d.glowsDays);
        o.put("source", d.source == null ? "" : d.source.name());
        o.put("xp", d.baseSalvageXP);
        j.put("debris", o);
        j.put("scavenged", p.isScavenged());
        j.put("seed", t.getMemoryWithoutUpdate().getLong(MemFlags.SALVAGE_SEED));
        JSONArray values = new JSONArray();
        for (DropData dd : t.getDropValue()) {
            if (dd.group != null) values.put(new JSONObject().put("g", dd.group).put("v", dd.value));
        }
        j.put("drops", values);
        JSONArray random = new JSONArray();
        for (DropData dd : t.getDropRandom()) {
            if (dd.group != null) random.put(new JSONObject().put("g", dd.group).put("c", dd.chances).put("v", dd.value));
        }
        j.put("random", random);
        ShipRecoverySpecialData rec = ShipRecoverySpecial.getSpecialData(t, null, false, false);
        if (rec != null) j.put("ships", ships(rec.ships));
    }

    private static void describeWreck(SectorEntityToken e, JSONObject j) throws Exception {
        DerelictShipData d = ((DerelictShipEntityPlugin) e.getCustomPlugin()).getData();
        j.put("days", d.durationDays).put("extraCargo", d.canHaveExtraCargo);
        JSONArray one = ships(java.util.Collections.singletonList(d.ship));
        if (one.length() > 0) j.put("ship", one.getJSONObject(0));
    }

    private static JSONArray ships(List<PerShipData> list) throws Exception {
        JSONArray a = new JSONArray();
        for (PerShipData psd : list) {
            if (psd == null) continue;
            JSONObject o = new JSONObject();
            o.put("cond", psd.condition == null ? ShipCondition.WRECKED.name() : psd.condition.name());
            if (psd.shipName != null) o.put("name", psd.shipName);
            if (psd.variantId != null) {
                o.put("v", psd.variantId);
            } else if (psd.variant != null) {
                FleetMemberAPI m = Global.getFactory().createFleetMember(FleetMemberType.SHIP, psd.variant);
                o.put("exact", ShipJson.ship(m));
            } else {
                continue;
            }
            a.put(o);
        }
        return a;
    }

    private static void describePods(SectorEntityToken e, JSONObject j) throws Exception {
        j.put("cargo", MarketSync.stacks(e.getCargo()));
        if (e.getCustomPlugin() instanceof CargoPodsEntityPlugin) {
            CargoPodsEntityPlugin p = (CargoPodsEntityPlugin) e.getCustomPlugin();
            j.put("elapsed", p.getElapsed()).put("extraDays", p.getExtraDays());
            j.put("neverExpire", Boolean.TRUE.equals(p.isNeverExpire()));
        }
    }

    // ================================================================ applying

    private static void applySafely(JSONObject j) {
        if (WorldState.handles(j)) {
            WorldState.apply(j); // world-wide facts: raids, discoveries, relations, bounties
            return;
        }
        try {
            SectorEntityToken talking = NpcSync.dialogTarget();
            if (talking != null && j.optString("id").equals(talking.getId())) {
                deferred.add(j); // never pull an object out from under an open dialog
                return;
            }
            apply(j);
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't apply a world object change: " + e);
        }
    }

    private static void apply(JSONObject j) throws Exception {
        String ev = j.optString("e");
        LocationAPI loc = FleetSync.findLocation(j.optString("loc"));
        if (loc == null) return; // a place this copy of the world doesn't have
        String id = j.optString("id");
        SectorEntityToken e = find(loc, id);
        if ("del".equals(ev)) {
            if (e == null) return;
            loc.removeEntity(e);
            if (loc == scanned) known.remove(id);
            log.info("[MultiSector] removed " + id + " from " + j.optString("loc") + ", as on another player's game");
            return;
        }
        if (e == null && "add".equals(ev)) {
            e = create(loc, id, j);
            if (e == null) return;
            log.info("[MultiSector] added " + j.optString("kind") + " " + e.getName() + " (" + id + ") to "
                    + j.optString("loc") + ", as on another player's game");
        }
        if (e == null) return;
        update(e, j);
        if (loc == scanned) known.put(id, state(e));
    }

    /** Finds by id. The lookup by id may not know about ids given after creation, so it also searches. */
    private static SectorEntityToken find(LocationAPI loc, String id) {
        if (id == null || id.isEmpty()) return null;
        SectorEntityToken e = loc.getEntityById(id);
        if (e != null && id.equals(e.getId())) return e;
        for (CampaignTerrainAPI t : loc.getTerrainCopy()) if (id.equals(t.getId())) return t;
        for (CustomCampaignEntityAPI c : loc.getCustomEntities()) if (id.equals(c.getId())) return c;
        return null;
    }

    private static SectorEntityToken create(LocationAPI loc, String id, JSONObject j) throws Exception {
        String kind = j.optString("kind");
        SectorEntityToken e;
        if (DEBRIS.equals(kind)) {
            e = createDebris(loc, id, j);
        } else if (WRECK.equals(kind)) {
            e = createWreck(loc, id, j);
        } else if (PODS.equals(kind)) {
            CustomCampaignEntityAPI pods = loc.addCustomEntity(id, null, Entities.CARGO_PODS, Factions.NEUTRAL);
            if (pods.getCargo() == null) {
                loc.removeEntity(pods); // pods nobody could open: better none
                return null;
            }
            pods.setDiscoverable(null);
            pods.setDiscoveryXP(null);
            pods.setSensorProfile(1f);
            e = pods;
        } else if (OBJECTIVE.equals(kind) || STABLE.equals(kind)) {
            String type = j.optString("type");
            if (type.isEmpty() || Global.getSettings().getCustomEntitySpec(type) == null) return null;
            String name = j.optString("name", "");
            e = loc.addCustomEntity(id, name.isEmpty() ? null : name, type, j.optString("faction", Factions.NEUTRAL));
        } else {
            return null; // can't rebuild that kind
        }
        if (e == null) return null;
        if (j.optBoolean("expires")) e.addTag(Tags.EXPIRES);
        place(loc, e, j);
        return e;
    }

    private static void place(LocationAPI loc, SectorEntityToken e, JSONObject j) {
        JSONObject o = j.optJSONObject("orbit");
        SectorEntityToken focus = o == null ? null : find(loc, o.optString("f"));
        if (focus == null && o != null) focus = loc.getEntityById(o.optString("f"));
        if (focus != null) {
            float a = (float) o.optDouble("a"), r = (float) o.optDouble("r"), p = (float) o.optDouble("p");
            if (OBJECTIVE.equals(j.optString("kind"))) e.setCircularOrbitPointingDown(focus, a, r, p);
            else e.setCircularOrbit(focus, a, r, p);
            return;
        }
        e.getLocation().set((float) j.optDouble("x"), (float) j.optDouble("y"));
        e.getVelocity().set((float) j.optDouble("vx"), (float) j.optDouble("vy"));
    }

    private static SectorEntityToken createDebris(LocationAPI loc, String id, JSONObject j) throws Exception {
        JSONObject o = j.getJSONObject("debris");
        DebrisFieldParams params = new DebrisFieldParams((float) o.optDouble("bw", 200), (float) o.optDouble("density", 1),
                (float) o.optDouble("lasts", 1), (float) o.optDouble("glows", 1));
        params.middleRadius = (float) o.optDouble("mid", params.middleRadius);
        params.baseDensity = (float) o.optDouble("baseDensity", 1);
        params.baseSalvageXP = o.optLong("xp", 0);
        try {
            params.source = DebrisFieldSource.valueOf(o.optString("source", "MIXED"));
        } catch (IllegalArgumentException ex) {
            params.source = DebrisFieldSource.MIXED;
        }
        SectorEntityToken d = Misc.addDebrisField(loc, params, null);
        d.setId(id);
        d.setDiscoverable(null);
        d.setDiscoveryXP(null);
        if (j.has("seed")) d.getMemoryWithoutUpdate().set(MemFlags.SALVAGE_SEED, j.optLong("seed"));
        d.getDropValue().clear();
        d.getDropRandom().clear();
        JSONArray values = j.optJSONArray("drops");
        for (int i = 0; values != null && i < values.length(); i++) {
            JSONObject v = values.getJSONObject(i);
            d.addDropValue(v.getString("g"), v.optInt("v"));
        }
        JSONArray random = j.optJSONArray("random");
        for (int i = 0; random != null && i < random.length(); i++) {
            JSONObject v = random.getJSONObject(i);
            if (v.optInt("v", -1) > 0) d.addDropRandom(v.getString("g"), v.optInt("c"), v.optInt("v"));
            else d.addDropRandom(v.getString("g"), v.optInt("c"));
        }
        JSONArray ships = j.optJSONArray("ships");
        if (ships != null && ships.length() > 0) {
            ShipRecoverySpecialData rec = ShipRecoverySpecial.getSpecialData(d, null, true, false);
            if (rec != null) {
                for (PerShipData psd : perShip(ships)) rec.addShip(psd);
            }
        }
        return d;
    }

    private static SectorEntityToken createWreck(LocationAPI loc, String id, JSONObject j) throws Exception {
        JSONObject ship = j.optJSONObject("ship");
        if (ship == null) return null;
        List<PerShipData> list = perShip(new JSONArray().put(ship));
        if (list.isEmpty()) return null;
        DerelictShipData data = new DerelictShipData(list.get(0), j.optBoolean("extraCargo"));
        data.durationDays = (float) j.optDouble("days", data.durationDays);
        CustomCampaignEntityAPI e = loc.addCustomEntity(id, null, Entities.WRECK, Factions.NEUTRAL, data);
        e.setSensorProfile(1f);
        e.setDiscoverable(true);
        return e;
    }

    private static List<PerShipData> perShip(JSONArray a) throws Exception {
        List<PerShipData> out = new ArrayList<PerShipData>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            ShipCondition cond;
            try {
                cond = ShipCondition.valueOf(o.optString("cond", "WRECKED"));
            } catch (IllegalArgumentException ex) {
                cond = ShipCondition.WRECKED;
            }
            PerShipData psd;
            String v = o.optString("v", "");
            if (!v.isEmpty()) {
                if (!ShipJson.exists("variant", v)) continue; // a ship from a mod this game doesn't have
                psd = new PerShipData(v, cond, 0f);
            } else if (o.has("exact")) {
                FleetMemberAPI m = ShipJson.buildShip(o.getJSONObject("exact"), new ArrayList<String>());
                if (m == null) continue;
                psd = new PerShipData(m.getVariant(), cond, o.optString("name", null), Factions.NEUTRAL, 0f);
            } else {
                continue;
            }
            if (o.has("name")) psd.shipName = o.optString("name");
            out.add(psd);
        }
        return out;
    }

    /** The parts that can change later: debris salvaged, objective owner and hack, pod contents. */
    private static void update(SectorEntityToken e, JSONObject j) throws Exception {
        String kind = j.optString("kind");
        if (DEBRIS.equals(kind) && e instanceof CampaignTerrainAPI
                && ((CampaignTerrainAPI) e).getPlugin() instanceof DebrisFieldTerrainPlugin) {
            DebrisFieldTerrainPlugin p = (DebrisFieldTerrainPlugin) ((CampaignTerrainAPI) e).getPlugin();
            JSONObject o = j.optJSONObject("debris");
            if (o != null) p.getParams().density = (float) o.optDouble("density", p.getParams().density);
            if (j.optBoolean("scavenged")) p.setScavenged(true);
        } else if (OBJECTIVE.equals(kind) || STABLE.equals(kind)) {
            String f = j.optString("faction", "");
            if (!f.isEmpty() && Global.getSector().getFaction(f) != null && !f.equals(e.getFaction().getId())) {
                e.setFaction(f);
            }
            CustomCampaignEntityPlugin p = e.getCustomPlugin();
            if (p instanceof CampaignObjective && j.has("hacked")) ((CampaignObjective) p).setHacked(j.optBoolean("hacked"));
        } else if (PODS.equals(kind)) {
            CargoAPI c = e.getCargo();
            if (c == null) {
                if (e instanceof CustomCampaignEntityAPI) broken((CustomCampaignEntityAPI) e);
                return;
            }
            c.clear();
            JSONArray stacks = j.optJSONArray("cargo");
            for (int i = 0; stacks != null && i < stacks.length(); i++) MarketSync.add(c, stacks.getJSONObject(i));
            if (e.getCustomPlugin() instanceof CargoPodsEntityPlugin) {
                CargoPodsEntityPlugin p = (CargoPodsEntityPlugin) e.getCustomPlugin();
                if (j.has("elapsed")) p.setElapsed((float) j.optDouble("elapsed"));
                if (j.has("extraDays")) p.setExtraDays((float) j.optDouble("extraDays"));
                if (j.optBoolean("neverExpire")) p.setNeverExpire(true);
            }
        }
    }

    /** A save is loading: the old world's objects are gone. */
    public static void forget() {
        scanned = null;
        known.clear();
        deferred.clear();
    }
}
