package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.OrbitAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.loading.VariantSource;
import multisector.net.ClockState;
import multisector.net.NpcInfo;
import multisector.net.NpcState;
import multisector.net.Session;
import multisector.ui.MultiSectorConfig;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NPC fleets in a star system shared with the host follow the host's game.
 *
 * Starsector only creates NPC fleets near the local player (within about
 * 1.6 light-years) and each copy rolls its own, so two players in one
 * system see different patrols and traders. While a friend is in the same
 * star system as the host:
 *
 * <ul>
 * <li><b>Host:</b> publishes every NPC fleet in its system (details over
 *     TCP when they change, positions over UDP 10 times a second).</li>
 * <li><b>Friend:</b> shows the host's fleets instead of its own. A fleet
 *     that was in the shared save has the same id on both games, so the
 *     friend's own copy of it is steered to the host's position. Any other
 *     host fleet gets a stand-in ("mirror") fleet. The friend's own extra
 *     fleets are hidden (undetectable, AI off) and given back untouched
 *     when the shared stretch ends.</li>
 * </ul>
 *
 * Deliberately narrow for now: star systems only (not hyperspace), and
 * host-driven fleets can't be talked to or fought on a friend's game
 * ({@link NpcInteractionGuard}), because the host doesn't resolve those
 * interactions yet. Nothing here is ever saved: everything is released
 * before a save and set up again on the next frame.
 *
 * Transient: added on each load.
 */
public class NpcSync implements EveryFrameScript {

    /** A stand-in fleet for one of the host's fleets. */
    public static final String MIRROR_FLAG = "$multisector_mirror";
    /** A friend's own fleet that is currently being steered by the host. */
    public static final String DRIVEN_FLAG = "$multisector_driven";
    /** A friend's own fleet hidden because the host's world doesn't have it. */
    public static final String HIDDEN_FLAG = "$multisector_hidden";
    private static final String MOD_ID = "multisector_hidden";

    private static final long CAPTURE_NANOS = 100_000_000L;
    private static final long DETAILS_NANOS = 1_000_000_000L;
    private static final long RESEND_NANOS = 15_000_000_000L;
    /** Don't predict a fleet further ahead than this from its last update. */
    private static final float MAX_PREDICT_SECONDS = 1f;
    private static final float FOLLOW_RATE = 6f;
    private static final float SNAP_DISTANCE = 2000f;

    private static NpcSync current;

    private final Logger log = Global.getLogger(NpcSync.class);

    // ---- host
    private String hostLoc;
    private final Map<String, Integer> handles = new HashMap<String, Integer>();
    private final Map<Integer, CampaignFleetAPI> byHandle = new HashMap<Integer, CampaignFleetAPI>();
    private final Map<Integer, Integer> sentHash = new HashMap<Integer, Integer>();
    private int nextHandle = 1;
    private long nextCapture;
    private long nextDetails;
    private long nextResend;

    // ---- friend
    private LocationAPI zone;
    private final Map<Integer, Driven> driven = new HashMap<Integer, Driven>();
    private final Map<CampaignFleetAPI, Hidden> hidden = new IdentityHashMap<CampaignFleetAPI, Hidden>();
    private long lastFrameNanos;

    private static final class Driven {
        final CampaignFleetAPI fleet;
        final boolean mirror;
        int hash;
        Boolean oldAi;
        OrbitAPI oldOrbit;

        Driven(CampaignFleetAPI fleet, boolean mirror) {
            this.fleet = fleet;
            this.mirror = mirror;
        }
    }

    private static final class Hidden {
        Boolean oldAi;
        OrbitAPI orbit;
        /** Where it really is, to put it back exactly. */
        float x, y;
    }

    /**
     * Hidden fleets wait this far out, beyond any sensor range. Being
     * undetectable alone wasn't enough: right next to a planet the game can
     * still show a fleet (seen as fleets "spawning" around a docked friend).
     */
    private static final float PARK_OFFSET = 150000f;
    /** At most this many hidden fleets are named in the log per shared stretch. */
    private static final int LOG_HIDDEN = 30;
    private int loggedHidden;

    public NpcSync() {
        current = this;
    }

    public boolean isDone() {
        return false;
    }

    public boolean runWhilePaused() {
        return true;
    }

    public void advance(float amount) {
        Session s = Session.get();
        try {
            if (!MultiSectorConfig.isSyncNpcFleets()) {
                releaseAll();
                stopWorldMoving();
                FleetLocks.hostReleaseAll();
                return;
            }
            if (s.getState() == Session.State.HOSTING) {
                releaseAll();
                host(s);
            } else if (s.getState() == Session.State.CONNECTED) {
                stopWorldMoving();
                FleetLocks.hostReleaseAll();
                hostLoc = null;
                friend(s);
            } else {
                releaseAll();
                stopWorldMoving();
                FleetLocks.hostReleaseAll();
                hostLoc = null;
            }
        } catch (RuntimeException e) {
            log.warn("[MultiSector] NPC fleet sync failed this frame", e);
        }
    }

    /** Before every save: nothing of ours may end up in a save file. Set up again next frame. */
    public static void releaseForSave() {
        NpcSync s = current;
        if (s == null) return;
        s.releaseAll();
        s.stopWorldMoving(); // the host's own fleet must not be saved as "ignored by other fleets"
        FleetLocks.hostBeforeSave();
    }

    /** Friend: the host's handle for a fleet we're showing on its behalf, or -1 if it isn't one. */
    static int handleOf(CampaignFleetAPI f) {
        NpcSync s = current;
        if (s == null) return -1;
        for (Map.Entry<Integer, Driven> e : s.driven.entrySet()) {
            if (e.getValue().fleet == f) return e.getKey();
        }
        return -1;
    }

    /** A save is loading: drop references into the old world without touching it. */
    public static void forget() {
        current = null;
        FleetLocks.forget();
    }

    // ================================================================ host

    private void host(Session s) {
        long now = System.nanoTime();
        keepWorldMoving(s, now);
        FleetLocks.hostFrame(s, byHandle, s.playerName(s.getMyId()));
        if (now - nextCapture < 0) return;
        nextCapture = now + CAPTURE_NANOS;
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        LocationAPI loc = me == null ? null : me.getContainingLocation();
        String locId = loc == null || loc.isHyperspace() ? "" : FleetSync.locationId(loc);
        if (!locId.equals(hostLoc)) {
            hostLoc = locId;
            handles.clear();
            byHandle.clear();
            sentHash.clear();
            FleetLocks.hostReleaseAll(); // fleets held in the system we just left
            s.hostNpcReset(locId);
            nextDetails = now;
            if (!locId.isEmpty()) log.info("[MultiSector] publishing NPC fleets in " + locId);
        }
        if (locId.isEmpty()) return;

        boolean details = now - nextDetails >= 0;
        if (details) nextDetails = now + DETAILS_NANOS;
        Set<Integer> seen = new HashSet<Integer>();
        List<NpcState> states = new ArrayList<NpcState>();
        for (CampaignFleetAPI f : loc.getFleets()) {
            if (!isNpc(f)) continue;
            Integer h = handles.get(f.getId());
            boolean fresh = h == null;
            if (fresh) {
                h = nextHandle++;
                handles.put(f.getId(), h);
            }
            seen.add(h);
            byHandle.put(h, f);
            if (fresh || details) {
                NpcInfo info = describe(h, f);
                Integer old = sentHash.get(h);
                if (old == null || old != info.hash) {
                    s.hostNpcInfo(info);
                    sentHash.put(h, info.hash);
                }
            }
            NpcState st = new NpcState();
            st.handle = h;
            st.x = f.getLocation().x;
            st.y = f.getLocation().y;
            st.vx = f.getVelocity().x;
            st.vy = f.getVelocity().y;
            st.facing = f.getFacing();
            states.add(st);
        }
        for (Iterator<Map.Entry<String, Integer>> it = handles.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Integer> e = it.next();
            if (seen.contains(e.getValue())) continue;
            s.hostNpcGone(e.getValue());
            sentHash.remove(e.getValue());
            byHandle.remove(e.getValue());
            it.remove();
        }
        s.hostNpcStates(states);
        if (now - nextResend >= 0) {
            nextResend = now + RESEND_NANOS;
            s.hostNpcResendAll();
        }
    }

    // ---- host in a dialog: the system keeps going

    /** Longest single step; a hitch never moves fleets in one big jump. */
    private static final float MAX_STEP_SECONDS = 0.1f;
    /** This many failed fleet updates in one dialog and we stop trying until it closes. */
    private static final int MAX_FAILURES = 20;

    private long lastRunNanos;
    private boolean setIgnoredFlag;
    private int failures;

    /**
     * Starsector stops the whole campaign while a dialog or menu is open. On
     * the host that would also stop every NPC fleet that friends in the same
     * system are watching. So while the host is in a dialog (and the game
     * isn't paused, and a friend is in this system), the host's game moves
     * those fleets itself, in real time:
     * <ul>
     * <li>the fleet the host is talking to stays frozen, so it's still there
     *     when the dialog closes;</li>
     * <li>fleets in a battle are left alone;</li>
     * <li>other fleets ignore the host's fleet meanwhile, so nobody tries to
     *     start a second encounter with a host who's already in one.</li>
     * </ul>
     * Only fleets advance: no spawning, no battles resolving, no economy.
     * Those wait for the host to close the dialog, as before.
     */
    private void keepWorldMoving(Session s, long now) {
        LocationAPI loc = hostLocation();
        boolean active = MultiSectorConfig.isMoveFleetsDuringHostDialogs()
                && loc != null && !ClockSync.isMapView() && s.getSharedClock().isRunning()
                && friendIn(s, FleetSync.locationId(loc));
        if (!active) {
            stopWorldMoving();
            return;
        }
        float dt = lastRunNanos == 0 ? 0f : Math.min((now - lastRunNanos) / 1e9f, MAX_STEP_SECONDS);
        lastRunNanos = now;
        if (dt <= 0f || failures >= MAX_FAILURES) return;

        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (!setIgnoredFlag && !me.getMemoryWithoutUpdate().contains(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS)) {
            me.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, true);
            setIgnoredFlag = true;
        }
        SectorEntityToken talkingTo = dialogTarget();
        for (CampaignFleetAPI f : new ArrayList<CampaignFleetAPI>(loc.getFleets())) {
            if (!isNpc(f) || f == talkingTo || f.getBattle() != null || FleetLocks.isHeld(f)) continue;
            try {
                f.advance(dt);
            } catch (RuntimeException e) {
                if (++failures == MAX_FAILURES) {
                    log.warn("[MultiSector] stopped moving fleets during this dialog after repeated errors", e);
                }
            }
        }
    }

    /** The dialog closed (or something else changed): back to the game's own rules. */
    private void stopWorldMoving() {
        lastRunNanos = 0;
        failures = 0;
        if (setIgnoredFlag) {
            CampaignFleetAPI me = Global.getSector().getPlayerFleet();
            if (me != null) me.getMemoryWithoutUpdate().unset(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS);
            setIgnoredFlag = false;
        }
    }

    private static LocationAPI hostLocation() {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        LocationAPI loc = me == null ? null : me.getContainingLocation();
        return loc == null || loc.isHyperspace() ? null : loc;
    }

    private static boolean friendIn(Session s, String locId) {
        for (multisector.net.FleetState.Received r : s.getRemoteFleets().values()) {
            if (locId.equals(r.state.location)) return true;
        }
        return false;
    }

    static SectorEntityToken dialogTarget() {
        com.fs.starfarer.api.campaign.CampaignUIAPI ui = Global.getSector().getCampaignUI();
        com.fs.starfarer.api.campaign.InteractionDialogAPI d = ui == null ? null : ui.getCurrentInteractionDialog();
        return d == null ? null : d.getInteractionTarget();
    }

    /** An ordinary NPC fleet: not a player, not one of our stand-ins, not a station. */
    private static boolean isNpc(CampaignFleetAPI f) {
        if (f.isPlayerFleet() || f.isStationMode()) return false;
        if (f.getMemoryWithoutUpdate().getBoolean(PuppetManager.PUPPET_FLAG)) return false;
        if (f.getMemoryWithoutUpdate().getBoolean(MIRROR_FLAG)) return false;
        return f.getFleetData() != null && f.getFleetData().getNumMembers() > 0;
    }

    private static NpcInfo describe(int handle, CampaignFleetAPI f) {
        List<String> variants = new ArrayList<String>();
        List<String> hulls = new ArrayList<String>();
        FleetMemberAPI flag = f.getFlagship();
        List<FleetMemberAPI> members = f.getFleetData().getMembersListCopy();
        if (flag != null) {
            members.remove(flag);
            members.add(0, flag);
        }
        for (FleetMemberAPI m : members) {
            if (m.isFighterWing() || m.getVariant() == null) continue;
            String v = m.getVariant().getHullVariantId();
            variants.add(v == null ? "" : v);
            hulls.add(m.getHullSpec().getHullId());
        }
        String faction = f.getFaction() == null ? "" : f.getFaction().getId();
        return new NpcInfo(handle, f.getId(), faction, f.getName(), variants, hulls);
    }

    // ================================================================ friend

    private void friend(Session s) {
        long now = System.nanoTime();
        float frameDt = lastFrameNanos == 0 ? 0f : Math.min((now - lastFrameNanos) / 1e9f, 0.25f);
        lastFrameNanos = now;
        FleetLocks.friendFrame(s);

        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        LocationAPI here = me == null ? null : me.getContainingLocation();
        String hostLocation = s.getNpcLocation();
        boolean shared = here != null && !here.isHyperspace() && !hostLocation.isEmpty()
                && hostLocation.equals(FleetSync.locationId(here));
        if (!shared) {
            releaseAll();
            return;
        }
        if (zone != here) {
            releaseAll();
            zone = here;
            loggedHidden = 0;
            log.info("[MultiSector] sharing NPC fleets with the host in " + hostLocation);
        }

        Map<Integer, NpcInfo> infos = s.getNpcInfos();
        Map<Integer, NpcState.Received> states = s.getNpcStates();
        float speed = clockSpeed(s.getRemoteClock());

        // Fleets the host no longer has here.
        for (Iterator<Map.Entry<Integer, Driven>> it = driven.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Driven> e = it.next();
            Driven d = e.getValue();
            if (!infos.containsKey(e.getKey()) || d.fleet.getContainingLocation() != here) {
                release(d);
                it.remove();
            }
        }

        // The host's fleets: steer our copy, or show a stand-in.
        Set<CampaignFleetAPI> ours = java.util.Collections.newSetFromMap(new IdentityHashMap<CampaignFleetAPI, Boolean>());
        for (NpcInfo info : infos.values()) {
            NpcState.Received st = states.get(info.handle);
            Driven d = driven.get(info.handle);
            if (d != null) ours.add(d.fleet); // ours even in a frame without a position
            if (st == null) continue; // no position yet
            if (d == null) {
                if (FleetLocks.isAwaiting(info.handle, info.hash)) continue; // its battle result is on its way
                d = adopt(info, st.state, here);
                if (d == null) continue;
                driven.put(info.handle, d);
            } else if (d.mirror && d.hash != info.hash) {
                fill(d.fleet, info); // it lost or gained ships on the host
                d.hash = info.hash;
            }
            follow(d.fleet, st, speed, now, frameDt);
            ours.add(d.fleet);
        }

        // Everything else here is this game's own roll of the dice: hide it while shared.
        for (CampaignFleetAPI f : new ArrayList<CampaignFleetAPI>(here.getFleets())) {
            if (ours.contains(f) || !isNpc(f) || f.getMemoryWithoutUpdate().getBoolean(DRIVEN_FLAG)) continue;
            if (f.getBattle() != null && !hidden.containsKey(f)) continue; // never pull a fleet out of a battle
            Hidden h = hidden.get(f);
            if (h == null) {
                hidden.put(f, hide(f));
                if (loggedHidden++ < LOG_HIDDEN) {
                    log.info("[MultiSector] hiding this game's own " + f.getName() + " (" + f.getFaction().getId()
                            + ", " + f.getFleetData().getNumMembers() + " ships): the host's world doesn't have it");
                }
            } else {
                park(f, h); // the game may move it; keep it out of sight
            }
        }
        for (Iterator<Map.Entry<CampaignFleetAPI, Hidden>> it = hidden.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<CampaignFleetAPI, Hidden> e = it.next();
            if (e.getKey().getContainingLocation() != here) {
                unhide(e.getKey(), e.getValue());
                it.remove();
            }
        }
    }

    /** How fast the shared world runs: 0 paused, 1 normal, more while fast-forwarding. */
    private static float clockSpeed(ClockState.Received r) {
        if (r == null) return 1f;
        if (r.state.isStopped()) return 0f;
        return r.state.isFast() ? r.state.fastMult : 1f;
    }

    /** Takes over our own copy of the fleet if we have one (same id), else makes a stand-in. */
    private Driven adopt(NpcInfo info, NpcState st, LocationAPI here) {
        CampaignFleetAPI local = null;
        if (!info.fleetId.isEmpty()) {
            for (CampaignFleetAPI f : here.getFleets()) {
                if (info.fleetId.equals(f.getId()) && isNpc(f)) {
                    local = f;
                    break;
                }
            }
        }
        if (local != null && local.getBattle() != null) return null; // try again once its battle is over
        if (local != null) {
            Hidden h = hidden.remove(local);
            if (h != null) unhide(local, h);
            Driven d = new Driven(local, false);
            d.oldAi = local.isDoNotAdvanceAI();
            d.oldOrbit = local.getOrbit();
            local.setOrbit(null);
            local.setDoNotAdvanceAI(true);
            local.getMemoryWithoutUpdate().set(DRIVEN_FLAG, true);
            return d;
        }
        CampaignFleetAPI m = spawnMirror(info, st, here);
        if (m == null) return null;
        Driven d = new Driven(m, true);
        d.hash = info.hash;
        return d;
    }

    private CampaignFleetAPI spawnMirror(NpcInfo info, NpcState st, LocationAPI here) {
        String faction = Global.getSector().getFaction(info.faction) != null ? info.faction : Factions.NEUTRAL;
        CampaignFleetAPI f = Global.getFactory().createEmptyFleet(faction, info.name, true);
        if (!fill(f, info)) return null;
        f.setName(info.name);
        f.setNoAutoDespawn(true);
        f.setDoNotAdvanceAI(true);
        f.getMemoryWithoutUpdate().set(MIRROR_FLAG, true);
        f.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
        here.addEntity(f);
        f.setLocation(st.x, st.y);
        f.setFacing(st.facing);
        return f;
    }

    /** Replaces the fleet's ships with the host's list. Returns false if none could be made. */
    private static boolean fill(CampaignFleetAPI f, NpcInfo info) {
        List<FleetMemberAPI> built = new ArrayList<FleetMemberAPI>();
        for (int i = 0; i < info.variants.size(); i++) {
            FleetMemberAPI m = ship(info.variants.get(i), info.hulls.get(i));
            if (m != null) built.add(m);
        }
        if (built.isEmpty()) return false;
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

    /** The host's variant if this game has it, else a bare hull of the same type. */
    private static FleetMemberAPI ship(String variantId, String hullId) {
        try {
            if (variantId != null && !variantId.isEmpty() && Global.getSettings().doesVariantExist(variantId)) {
                ShipVariantAPI v = Global.getSettings().getVariant(variantId).clone();
                v.setSource(VariantSource.REFIT);
                return Global.getFactory().createFleetMember(FleetMemberType.SHIP, v);
            }
            ShipHullSpecAPI spec = Global.getSettings().getHullSpec(hullId);
            if (spec == null) return null;
            ShipVariantAPI v = Global.getSettings().createEmptyVariant(hullId + "_multisector_mirror", spec);
            v.setSource(VariantSource.REFIT);
            return Global.getFactory().createFleetMember(FleetMemberType.SHIP, v);
        } catch (Throwable t) {
            return null; // a ship from a mod this game doesn't have
        }
    }

    /** Glides toward where the host's fleet is now, predicted from its last update. */
    private static void follow(CampaignFleetAPI f, NpcState.Received r, float speed, long now, float frameDt) {
        NpcState s = r.state;
        float ahead = Math.min((now - r.arrivedNanos) / 1e9f, MAX_PREDICT_SECONDS) * speed;
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
        f.getVelocity().set(s.vx * speed, s.vy * speed);
        f.setFacing(s.facing);
    }

    private static Hidden hide(CampaignFleetAPI f) {
        Hidden h = new Hidden();
        h.oldAi = f.isDoNotAdvanceAI();
        h.orbit = f.getOrbit();
        h.x = f.getLocation().x;
        h.y = f.getLocation().y;
        f.setOrbit(null);
        // Same stat the game's own "Go Dark" ability lowers; 0 means nobody can detect it.
        f.getStats().getDetectedRangeMod().modifyMult(MOD_ID, 0f, "MultiSector: not in the host's world");
        f.setDoNotAdvanceAI(true);
        f.getMemoryWithoutUpdate().set(HIDDEN_FLAG, true);
        park(f, h);
        return h;
    }

    /** Out beyond every sensor range, standing still. Its routes and managers don't look at where it is. */
    private static void park(CampaignFleetAPI f, Hidden h) {
        f.setLocation(h.x + PARK_OFFSET, h.y + PARK_OFFSET);
        f.getVelocity().set(0f, 0f);
    }

    private static void unhide(CampaignFleetAPI f, Hidden h) {
        f.setLocation(h.x, h.y);
        if (h.orbit != null && f.getOrbit() == null) f.setOrbit(h.orbit);
        f.getStats().getDetectedRangeMod().unmodifyMult(MOD_ID);
        f.setDoNotAdvanceAI(h.oldAi);
        f.getMemoryWithoutUpdate().unset(HIDDEN_FLAG);
    }

    private static void release(Driven d) {
        CampaignFleetAPI f = d.fleet;
        if (d.mirror) {
            if (f.getContainingLocation() != null) f.getContainingLocation().removeEntity(f);
            return;
        }
        f.setDoNotAdvanceAI(d.oldAi);
        if (d.oldOrbit != null && f.getOrbit() == null) f.setOrbit(d.oldOrbit);
        f.getMemoryWithoutUpdate().unset(DRIVEN_FLAG);
    }

    /** Gives this game its own fleets back and removes every stand-in. */
    private void releaseAll() {
        if (driven.isEmpty() && hidden.isEmpty() && zone == null) return;
        for (Driven d : driven.values()) release(d);
        for (Map.Entry<CampaignFleetAPI, Hidden> e : hidden.entrySet()) unhide(e.getKey(), e.getValue());
        if (zone != null) log.info("[MultiSector] stopped sharing NPC fleets (" + driven.size() + " followed, "
                + hidden.size() + " hidden)");
        driven.clear();
        hidden.clear();
        zone = null;
        lastFrameNanos = 0;
    }
}
