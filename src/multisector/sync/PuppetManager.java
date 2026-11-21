package multisector.sync;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.loading.VariantSource;
import multisector.net.FleetState;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows other players as "puppet" fleets on the campaign map: a stand-in
 * fleet with their flagship, moved to wherever their real fleet is.
 *
 * Puppets are display only. They never fight or get chased, other fleets
 * ignore them, and they're removed before every save so they never end up
 * in anyone's save file.
 *
 * Movement: aim at the last known position plus velocity times time since
 * it arrived (dead reckoning), and glide toward that each frame so ~300 ms
 * of ping doesn't make them jump.
 */
public final class PuppetManager {

    /** Memory flag marking a fleet as a MultiSector puppet. */
    public static final String PUPPET_FLAG = "$multisector_puppet";
    /** No update for this long: hide the puppet (that player is probably loading or gone). */
    private static final long STALE_NANOS = 5_000_000_000L;
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
        String hull;
        String name;
    }

    private PuppetManager() {
    }

    /** Called every frame with the latest remote states. */
    static void update(Map<Integer, FleetState.Received> remote) {
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
            if (p != null && (!s.hull.equals(p.hull) || !s.name.equals(p.name))) {
                remove(e.getKey()); // flagship or name changed: rebuild
                p = null;
            }
            if (p == null) {
                p = spawn(s, loc);
                if (p == null) continue;
                puppets.put(e.getKey(), p);
            }

            CampaignFleetAPI f = p.fleet;
            if (f.getContainingLocation() != loc) {
                if (f.getContainingLocation() != null) f.getContainingLocation().removeEntity(f);
                loc.addEntity(f);
                f.setLocation(s.x, s.y);
            }

            float ahead = Math.min((now - r.arrivedNanos) / 1e9f, MAX_PREDICT_SECONDS);
            float tx = s.x + s.vx * ahead;
            float ty = s.y + s.vy * ahead;
            Vector2f pos = f.getLocation();
            float dx = tx - pos.x;
            float dy = ty - pos.y;
            if (dx * dx + dy * dy > SNAP_DISTANCE * SNAP_DISTANCE) {
                f.setLocation(tx, ty);
            } else {
                float k = Math.min(1f, FOLLOW_RATE * frameDt);
                f.setLocation(pos.x + dx * k, pos.y + dy * k);
            }
            f.getVelocity().set(s.vx, s.vy);
            f.setFacing(s.facing);
        }
    }

    private static Puppet spawn(FleetState s, LocationAPI loc) {
        CampaignFleetAPI f = Global.getFactory().createEmptyFleet(Factions.PLAYER, s.name + "'s fleet", false);
        FleetMemberAPI m = flagship(s.hull);
        if (m == null) return null;
        f.getFleetData().addFleetMember(m);
        f.getFleetData().setFlagship(m);
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
        // Visible across the whole system, like a fleet with its transponder on.
        f.setSensorProfile(100000f);
        loc.addEntity(f);
        f.setLocation(s.x, s.y);
        Puppet p = new Puppet();
        p.fleet = f;
        p.hull = s.hull;
        p.name = s.name;
        Global.getLogger(PuppetManager.class).info("[MultiSector] showing " + s.name + "'s fleet in " + s.location);
        return p;
    }

    /** The other player's flagship hull, or a stand-in if this game doesn't have it. */
    private static FleetMemberAPI flagship(String hullId) {
        String[] tries = {hullId, "kite", "wolf"};
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

    /** Removes every puppet from the map: before saving, on disconnect, and when loading another save. */
    public static void removeAll() {
        for (Integer id : new ArrayList<Integer>(puppets.keySet())) remove(id);
        lastFrameNanos = 0;
    }

    /** Forgets puppets from a previous save without touching the (already unloaded) world. */
    public static void forget() {
        puppets.clear();
        lastFrameNanos = 0;
    }
}
