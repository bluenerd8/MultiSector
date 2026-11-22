package multisector.sync;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import multisector.character.ShipJson;
import multisector.net.FleetComp;
import multisector.net.FleetState;
import multisector.net.Session;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows other players as "puppet" fleets on the campaign map: a stand-in
 * fleet with their ships (flagship first), moved to wherever their real fleet is.
 *
 * Puppets are display only. They never fight or get chased (clicking one hails that player), other fleets
 * ignore them, and they're removed before every save so they never end up
 * in anyone's save file.
 *
 * Movement: aim at the last known position plus velocity times time since
 * it arrived (dead reckoning), and glide toward that each frame so ~300 ms
 * of ping doesn't make them jump.
 */
public final class PuppetManager {

    /**
     * Memory flag marking a fleet as a MultiSector puppet. Clicking one hails
     * that player (see {@link PlayerTalk}).
     */
    public static final String PUPPET_FLAG = "$multisector_puppet";
    /** No update for this long: hide the puppet (that player is probably loading or gone). Jumps can take a few seconds. */
    private static final long STALE_NANOS = 10_000_000_000L;
    private static final String VISIBLE_ID = "multisector_player";
    /** Don't extrapolate further than this from the last update. */
    private static final float MAX_PREDICT_SECONDS = 1f;
    /** How quickly the puppet catches up with its target (per second). */
    private static final float FOLLOW_RATE = 8f;
    /** Further than this from the target: jump instead of gliding (e.g. after a jump point). */
    private static final float SNAP_DISTANCE = 3000f;

    private static final Map<Integer, Puppet> puppets = new HashMap<Integer, Puppet>();
    private static long lastFrameNanos;

    private static final class Puppet {
        CampaignFleetAPI fleet;
        /** The hull list the puppet's ships were built from. */
        List<String> hulls;
        /** What the ships were built from (FleetComp hash, or the flagship alone), to rebuild on change. */
        int builtKey;
        /** What the player is docked at, looked up once per change. */
        String dockId;
        SectorEntityToken dock;
        String name;
    }

    private PuppetManager() {
    }

    /** Called every frame with the latest remote states. */
    static void update(Session session) {
        Map<Integer, FleetState.Received> remote = session.getRemoteFleets();
        long now = System.nanoTime();
        float frameDt = lastFrameNanos == 0 ? 0f : Math.min((now - lastFrameNanos) / 1e9f, 0.25f);
        lastFrameNanos = now;

        // Remove puppets whose player left or went quiet.
        List<Integer> gone = new ArrayList<Integer>();
        for (Integer id : puppets.keySet()) {
            FleetState.Received r = remote.get(id);
            if (r == null || now - r.arrivedNanos > STALE_NANOS) gone.add(id);
        }
        for (Integer id : gone) remove(id);

        for (Map.Entry<Integer, FleetState.Received> e : remote.entrySet()) {
            FleetState.Received r = e.getValue();
            if (now - r.arrivedNanos > STALE_NANOS) continue;
            FleetState s = r.state;
            LocationAPI loc = FleetSync.findLocation(s.location);
            if (loc == null) continue; // a place this copy of the world doesn't have

            Puppet p = puppets.get(e.getKey());
            FleetComp comp = session.getComposition(e.getKey());
            if (comp != null && comp.hash != s.compHash) comp = null; // not the ships it has now
            List<String> hulls = comp != null && !comp.hulls.isEmpty() ? comp.hulls : Collections.singletonList(s.hull);
            int key = comp != null ? comp.hash : s.hull.hashCode();
            if (p != null && !s.name.equals(p.name)) {
                remove(e.getKey()); // renamed: rebuild
                p = null;
            }
            if (p == null) {
                p = spawn(s, comp, hulls, loc);
                if (p == null) continue;
                puppets.put(e.getKey(), p);
            } else if (key != p.builtKey) {
                build(p.fleet, comp, hulls); // ships bought, sold, lost or refitted
                p.hulls = hulls;
                p.builtKey = key;
            }

            CampaignFleetAPI f = p.fleet;
            if (f.getContainingLocation() != loc) {
                if (f.getContainingLocation() != null) f.getContainingLocation().removeEntity(f);
                loc.addEntity(f);
                f.setLocation(s.x, s.y);
            }

            float tx, ty, vx = s.vx, vy = s.vy;
            SectorEntityToken dock = dockOf(p, s, loc);
            if (dock != null) {
                // Docked: their game is paused in the dialog, but the planet keeps orbiting here.
                tx = dock.getLocation().x + s.dockX;
                ty = dock.getLocation().y + s.dockY;
                vx = 0f;
                vy = 0f;
            } else {
                float ahead = Math.min((now - r.arrivedNanos) / 1e9f, MAX_PREDICT_SECONDS);
                tx = s.x + s.vx * ahead;
                ty = s.y + s.vy * ahead;
            }
            Vector2f pos = f.getLocation();
            float dx = tx - pos.x;
            float dy = ty - pos.y;
            if (dx * dx + dy * dy > SNAP_DISTANCE * SNAP_DISTANCE) {
                f.setLocation(tx, ty);
            } else {
                float k = Math.min(1f, FOLLOW_RATE * frameDt);
                f.setLocation(pos.x + dx * k, pos.y + dy * k);
            }
            f.getVelocity().set(vx, vy);
            f.setFacing(s.facing);
        }
    }

    /** The planet or station that player is docked at, in this game's copy of the world, or null. */
    private static SectorEntityToken dockOf(Puppet p, FleetState s, LocationAPI loc) {
        if (s.dockedAt == null || s.dockedAt.isEmpty()) return null;
        if (!s.dockedAt.equals(p.dockId) || p.dock == null || p.dock.getContainingLocation() != loc) {
            p.dockId = s.dockedAt;
            p.dock = loc.getEntityById(s.dockedAt); // null if this copy doesn't have it (made after the save)
        }
        return p.dock;
    }

    private static Puppet spawn(FleetState s, FleetComp comp, List<String> hulls, LocationAPI loc) {
        CampaignFleetAPI f = Global.getFactory().createEmptyFleet(Factions.PLAYER, s.name + "'s fleet", false);
        if (!build(f, comp, hulls)) return null;
        f.setName(s.name + "'s fleet");
        f.setNoAutoDespawn(true);
        f.setDoNotAdvanceAI(true);
        // Display only: never hostile, never chased, never in anyone's way.
        f.getMemoryWithoutUpdate().set(PUPPET_FLAG, true);
        f.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE, true);
        f.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_NON_AGGRESSIVE, true);
        f.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
        f.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, true);
        f.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_NO_JUMP, true);
        // Always visible, with full details, anywhere in the system. setSensorProfile alone
        // didn't last: the game recomputes it every frame, so a far-off player became an
        // unidentified sensor contact. The detected-range stat is what the game reads.
        f.setTransponderOn(true);
        f.getStats().getDetectedRangeMod().modifyFlat(VISIBLE_ID, 1_000_000f, "MultiSector: another player");
        f.getStats().getSensorProfileMod().modifyFlat(VISIBLE_ID, 1_000_000f, "MultiSector: another player");
        loc.addEntity(f);
        f.setLocation(s.x, s.y);
        Puppet p = new Puppet();
        p.fleet = f;
        p.hulls = hulls;
        p.builtKey = comp != null ? comp.hash : s.hull.hashCode();
        p.name = s.name;
        Global.getLogger(PuppetManager.class).info("[MultiSector] showing " + s.name + "'s fleet in " + s.location);
        return p;
    }

    /**
     * The player's exact ships (loadouts included) when their list has
     * arrived, else bare hulls. Ships this game can't build fall back to the
     * bare hull.
     */
    private static boolean build(CampaignFleetAPI f, FleetComp comp, List<String> hulls) {
        if (comp == null || comp.exact.isEmpty()) return fill(f, hulls);
        List<FleetMemberAPI> built = new ArrayList<FleetMemberAPI>();
        List<String> skipped = new ArrayList<String>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(comp.exact);
            for (int i = 0; i < a.length(); i++) {
                FleetMemberAPI m = ShipJson.buildShip(a.getJSONObject(i), skipped);
                if (m == null && i < hulls.size()) m = ship(hulls.get(i), i == 0);
                if (m != null) built.add(m);
            }
        } catch (Exception e) {
            return fill(f, hulls);
        }
        if (built.isEmpty()) return fill(f, hulls);
        FleetDataAPI data = f.getFleetData();
        for (FleetMemberAPI old : data.getMembersListCopy()) data.removeFleetMember(old);
        for (FleetMemberAPI m : built) {
            data.addFleetMember(m);
            m.getRepairTracker().setCR(m.getRepairTracker().getMaxCR());
        }
        data.setFlagship(built.get(0));
        f.forceSync();
        return true;
    }

    /**
     * Replaces the fleet's ships with these hulls, the first as flagship.
     * Ships this game doesn't have are skipped; an unknown flagship gets a
     * stand-in. Returns false if not even a stand-in could be made.
     */
    private static boolean fill(CampaignFleetAPI f, List<String> hulls) {
        FleetDataAPI data = f.getFleetData();
        List<FleetMemberAPI> built = new ArrayList<FleetMemberAPI>();
        for (int i = 0; i < hulls.size(); i++) {
            FleetMemberAPI m = ship(hulls.get(i), i == 0);
            if (m != null) built.add(m);
        }
        if (built.isEmpty()) return false;
        for (FleetMemberAPI old : data.getMembersListCopy()) data.removeFleetMember(old);
        for (FleetMemberAPI m : built) data.addFleetMember(m);
        data.setFlagship(built.get(0));
        f.forceSync();
        return true;
    }

    /** A ship of that hull, or (for the flagship) a stand-in if this game doesn't have it. */
    private static FleetMemberAPI ship(String hullId, boolean standIn) {
        String[] tries = standIn ? new String[]{hullId, "kite", "wolf"} : new String[]{hullId};
        for (String id : tries) {
            if (id == null || id.isEmpty()) continue;
            try {
                ShipHullSpecAPI spec = Global.getSettings().getHullSpec(id);
                if (spec == null) continue;
                ShipVariantAPI v = Global.getSettings().createEmptyVariant(id + "_multisector_puppet", spec);
                v.setSource(VariantSource.REFIT);
                return Global.getFactory().createFleetMember(FleetMemberType.SHIP, v);
            } catch (Throwable ignored) {
                // try the next one
            }
        }
        return null;
    }

    private static void remove(Integer id) {
        Puppet p = puppets.remove(id);
        if (p != null && p.fleet.getContainingLocation() != null) {
            p.fleet.getContainingLocation().removeEntity(p.fleet);
        }
    }

    /** The puppet fleet standing in for that player, or null if they aren't shown right now. */
    public static CampaignFleetAPI fleetOf(int playerId) {
        Puppet p = puppets.get(playerId);
        return p == null ? null : p.fleet;
    }

    /** Which player that puppet fleet stands in for, or -1 if it isn't one. */
    public static int playerOf(CampaignFleetAPI fleet) {
        for (Map.Entry<Integer, Puppet> e : puppets.entrySet()) {
            if (e.getValue().fleet == fleet) return e.getKey();
        }
        return -1;
    }

    /** Removes every puppet from the map: before saving, on disconnect, and when loading another save. */
    public static void removeAll() {
        for (Integer id : new ArrayList<Integer>(puppets.keySet())) remove(id);
        lastFrameNanos = 0;
    }

    /** Forgets puppets from a previous save without touching the (already unloaded) world. */
    public static void forget() {
        puppets.clear();
        Docking.forget(); // same moment: the remembered planet belonged to the old world
        lastFrameNanos = 0;
    }
}
