package multisector.sync;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.loading.VariantSource;
import multisector.character.ShipJson;
import multisector.net.FleetComp;
import multisector.net.FleetState;
import multisector.net.Session;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shows other players as "puppet" fleets on the campaign map: a stand-in
 * fleet with their ships (flagship first), moved to wherever their real fleet is.
 *
 * Puppets are display only. They never fight or get chased (clicking one hails that player),
 * other fleets ignore them, and they're removed before every save so they never end up
 * in anyone's save file.
 *
 * <p><b>Their ships are the owner's, always.</b> A puppet is built from the
 * ships its player's game sends (loadouts, plus readiness and hull damage to
 * the nearest 10%), and nothing on this game may change them. Once a second
 * each puppet is checked against what it was built from: if this game
 * removed, added or changed a ship (a hyperspace storm, a battle it was
 * pulled into, some mod's script), it is rebuilt exactly; drifting readiness
 * and hull damage are put back. While a newer ship list is still on its way,
 * the puppet keeps the last one rather than falling back to anything rougher.
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
    /** How often each puppet's ships are checked against what they should be. */
    private static final long CHECK_NANOS = 1_000_000_000L;
    /** Log at most this many restores per puppet, so a mod fighting us can't flood the log. */
    private static final int LOG_RESTORES = 5;

    private static final Logger log = Global.getLogger(PuppetManager.class);
    private static final Map<Integer, Puppet> puppets = new HashMap<Integer, Puppet>();
    private static long lastFrameNanos;

    private static final class Puppet {
        CampaignFleetAPI fleet;
        /** What the ships were built from: the player's list, or null (not arrived yet: just the flagship hull). */
        FleetComp comp;
        /** comp.hash, or the flagship hull's hash. */
        int builtKey;
        /** Exactly what we put in the fleet, with each ship's readiness, hull and loadout signature. */
        List<FleetMemberAPI> members = Collections.emptyList();
        float[] cr = new float[0];
        float[] hull = new float[0];
        String[] sig = new String[0];
        long nextCheck;
        int restores;
        /** In a joint battle: its ships are the exact copy sent for it, and nothing here may touch them. */
        boolean battleHold;
        FleetState last;
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
            if (p != null) p.last = s;
            boolean held = p != null && p.battleHold;
            if (held) {
                // Fighting in our battle (JointBattle): leave its ships alone, just keep it in place.
            } else if (p != null && !s.name.equals(p.name)) {
                remove(e.getKey()); // renamed: rebuild
                p = null;
            } else if (p != null && (p.fleet.isExpired() || p.fleet.isDespawning())) {
                log.info("[MultiSector] " + p.name + "'s fleet was removed on this game; putting it back");
                remove(e.getKey());
                p = null;
            }
            // The ships they have now, if their list has arrived. If a newer one is still on its way,
            // keep what we have: never fall back to something rougher in between.
            FleetComp comp = session.getComposition(e.getKey());
            boolean current = comp != null && comp.hash == s.compHash;
            if (held) {
                // nothing
            } else if (p == null) {
                p = spawn(s, comp, loc);
                if (p == null) continue;
                puppets.put(e.getKey(), p);
            } else if (current && comp.hash != p.builtKey) {
                rebuild(p, comp, s); // ships bought, sold, lost or refitted
            } else if (comp == null && p.comp == null && s.hull.hashCode() != p.builtKey) {
                rebuild(p, null, s); // no list yet, but the flagship changed
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

            if (!held && now - p.nextCheck >= 0) {
                p.nextCheck = now + CHECK_NANOS;
                enforce(p, s);
            }
        }
    }

    /** Puts the puppet's ships back exactly as its player has them, if this game changed anything. */
    private static void enforce(Puppet p, FleetState s) {
        CampaignFleetAPI f = p.fleet;
        if (f.getBattle() != null) return; // can't leave a battle; checked again once it's over
        String why = changed(p);
        if (why != null) {
            if (p.restores++ < LOG_RESTORES) {
                log.info("[MultiSector] " + p.name + "'s fleet was changed on this game (" + why
                        + "); restoring their ships");
            }
            rebuild(p, p.comp, s);
            return;
        }
        boolean touched = false;
        for (int i = 0; i < p.members.size(); i++) {
            FleetMemberAPI m = p.members.get(i);
            if (Math.abs(m.getRepairTracker().getCR() - p.cr[i]) > 0.005f) {
                m.getRepairTracker().setCR(p.cr[i]);
                touched = true;
            }
            if (Math.abs(m.getStatus().getHullFraction() - p.hull[i]) > 0.005f) {
                m.getStatus().repairFully();
                m.getStatus().setHullFraction(p.hull[i]);
                touched = true;
            }
        }
        if (touched) f.forceSync();
    }

    /** What's different from what we built, or null if nothing. */
    private static String changed(Puppet p) {
        List<FleetMemberAPI> now = p.fleet.getFleetData().getMembersListCopy();
        if (now.size() != p.members.size()) return p.members.size() + " ships became " + now.size();
        Set<FleetMemberAPI> ours = Collections.newSetFromMap(new IdentityHashMap<FleetMemberAPI, Boolean>());
        ours.addAll(p.members);
        for (FleetMemberAPI m : now) if (!ours.contains(m)) return "a ship was swapped";
        if (!p.members.isEmpty() && p.fleet.getFlagship() != p.members.get(0)) return "the flagship changed";
        for (int i = 0; i < p.members.size(); i++) {
            if (!sig(p.members.get(i)).equals(p.sig[i])) return "a ship's loadout changed";
        }
        return null;
    }

    /** A ship's identity and loadout, cheaply: enough to notice any change. */
    private static String sig(FleetMemberAPI m) {
        ShipVariantAPI v = m.getVariant();
        if (v == null) return m.getHullId();
        return m.getHullId() + "|" + m.getShipName() + "|" + v.getHullMods().size() + "|" + v.getPermaMods().size()
                + "|" + v.getSMods().size() + "|" + v.getFittedWeaponSlots().size() + "|" + v.getWings().size()
                + "|" + v.getNumFluxVents() + "|" + v.getNumFluxCapacitors() + "|" + m.getRepairTracker().isMothballed();
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

    private static Puppet spawn(FleetState s, FleetComp comp, LocationAPI loc) {
        // With a fleet AI (never advanced), like any NPC fleet: the battle code expects one when it joins a battle.
        CampaignFleetAPI f = Global.getFactory().createEmptyFleet(Factions.PLAYER, s.name + "'s fleet", true);
        Puppet p = new Puppet();
        p.fleet = f;
        p.name = s.name;
        if (!build(p, comp, s)) return null;
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
        p.nextCheck = System.nanoTime() + CHECK_NANOS;
        log.info("[MultiSector] showing " + s.name + "'s fleet in " + s.location);
        return p;
    }

    private static void rebuild(Puppet p, FleetComp comp, FleetState s) {
        if (!build(p, comp, s)) log.warn("[MultiSector] couldn't rebuild " + p.name + "'s fleet");
    }

    /**
     * The player's exact ships (loadouts, readiness, hull) when their list
     * has arrived, else bare hulls. Ships this game can't build fall back to
     * the bare hull. Records what was built, to check against later.
     */
    private static boolean build(Puppet p, FleetComp comp, FleetState s) {
        List<String> hulls = comp != null && !comp.hulls.isEmpty() ? comp.hulls : Collections.singletonList(s.hull);
        List<float[]> state = new ArrayList<float[]>();
        List<FleetMemberAPI> built = comp != null && !comp.exact.isEmpty() ? exact(comp, hulls, state) : null;
        if (built == null || built.isEmpty()) {
            built = bare(hulls);
            state.clear();
            for (int i = 0; i < built.size(); i++) state.add(new float[]{-1f, 1f});
        }
        if (built.isEmpty()) return false;
        FleetDataAPI data = p.fleet.getFleetData();
        for (FleetMemberAPI old : data.getMembersListCopy()) data.removeFleetMember(old);
        for (FleetMemberAPI m : built) data.addFleetMember(m);
        data.setFlagship(built.get(0));
        int n = built.size();
        p.cr = new float[n];
        p.hull = new float[n];
        p.sig = new String[n];
        for (int i = 0; i < n; i++) {
            FleetMemberAPI m = built.get(i);
            float cr = state.get(i)[0] < 0 ? m.getRepairTracker().getMaxCR() : state.get(i)[0];
            m.getRepairTracker().setCR(cr);
            m.getStatus().setHullFraction(state.get(i)[1]);
            p.cr[i] = m.getRepairTracker().getCR();
            p.hull[i] = m.getStatus().getHullFraction();
            p.sig[i] = sig(m);
        }
        p.fleet.forceSync();
        p.members = built;
        p.comp = comp;
        p.builtKey = comp != null ? comp.hash : s.hull.hashCode();
        return true;
    }

    /** The exact ships from the player's list; state gets each one's {cr (-1: full), hull}. Null if unreadable. */
    private static List<FleetMemberAPI> exact(FleetComp comp, List<String> hulls, List<float[]> state) {
        List<FleetMemberAPI> built = new ArrayList<FleetMemberAPI>();
        List<String> skipped = new ArrayList<String>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(comp.exact);
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject js = a.getJSONObject(i);
                FleetMemberAPI m = ShipJson.buildShip(js, skipped);
                if (m == null && i < hulls.size()) m = ship(hulls.get(i), i == 0);
                if (m == null) continue;
                built.add(m);
                state.add(new float[]{(float) js.optDouble("cr", -1), (float) js.optDouble("hullFraction", 1)});
            }
        } catch (Exception e) {
            state.clear();
            return null;
        }
        return built;
    }

    /** These hulls, the first as flagship. Ships this game doesn't have are skipped; an unknown flagship gets a stand-in. */
    private static List<FleetMemberAPI> bare(List<String> hulls) {
        List<FleetMemberAPI> built = new ArrayList<FleetMemberAPI>();
        for (int i = 0; i < hulls.size(); i++) {
            FleetMemberAPI m = ship(hulls.get(i), i == 0);
            if (m != null) built.add(m);
        }
        return built;
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

    /**
     * A joint battle: from now until {@link #releaseBattle}, nothing here
     * rebuilds or restores that player's puppet, so the battle can fight with
     * (and damage) the exact copy it was given. Returns the puppet, or null.
     */
    static CampaignFleetAPI holdForBattle(int playerId) {
        Puppet p = puppets.get(playerId);
        if (p == null) return null;
        p.battleHold = true;
        return p.fleet;
    }

    /** The battle is over and its results went to that player: back to showing their ships as they are. */
    static void releaseBattle(int playerId) {
        Puppet p = puppets.get(playerId);
        if (p == null || !p.battleHold) return;
        p.battleHold = false;
        p.restores = 0;
        if (p.last != null) rebuild(p, p.comp, p.last);
        else p.builtKey = 0;
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
