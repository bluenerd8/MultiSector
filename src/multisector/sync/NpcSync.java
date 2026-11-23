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
import multisector.net.FleetState;
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
 * NPC fleets where players are together follow one player's game.
 *
 * <p>Starsector only creates NPC fleets near the local player (within about
 * 1.6 light-years), and each copy of the world rolls its own, so two players
 * in one place see different patrols, traders and pirates. Where players are
 * together, the host decides whose game is the real one there (the "owner",
 * see {@link multisector.net.ZonePlanner}): in a star system, whoever got
 * there first; in hyperspace, a group of nearby players follows the one who
 * has been in hyperspace longest.
 *
 * <ul>
 * <li><b>Owner</b> (any player, host or not, with someone following them):
 *     publishes its NPC fleets there. In a system that's every fleet; in
 *     hyperspace, those within {@link #PUB_RADIUS} of it. Details go over TCP
 *     when they change, positions over UDP 10 times a second, both relayed
 *     by the host.</li>
 * <li><b>Follower:</b> shows the owner's fleets instead of its own. A fleet
 *     that was in the shared save has the same id on both games, so the
 *     follower's own copy is steered to the owner's position and given the
 *     owner's ships. Any other owner fleet gets a stand-in ("mirror"). The
 *     follower's own extra fleets are hidden (undetectable, AI off, parked
 *     out of the way) and given back untouched when the shared stretch ends.
 *     In hyperspace, only its own fleets near the owner are hidden.</li>
 * </ul>
 *
 * Talking to or fighting a followed fleet goes through {@link FleetLocks}.
 * Nothing here is ever saved: everything is released before a save and set
 * up again on the next frame.
 *
 * Transient: added on each load.
 */
public class NpcSync implements EveryFrameScript {

    /** A stand-in fleet for one of the owner's fleets. */
    public static final String MIRROR_FLAG = "$multisector_mirror";
    /** A follower's own fleet that is currently being steered by the owner. */
    public static final String DRIVEN_FLAG = "$multisector_driven";
    /** A follower's own fleet hidden because the owner's world doesn't have it. */
    public static final String HIDDEN_FLAG = "$multisector_hidden";
    private static final String MOD_ID = "multisector_hidden";

    /** Hyperspace: an owner publishes its fleets this close to it (2000 units is a light-year). */
    static final float PUB_RADIUS = 8000f;
    /** Hyperspace: a follower hides its own fleets this close to the owner. */
    static final float HIDE_RADIUS = 7500f;

    private static final long CAPTURE_NANOS = 100_000_000L;
    private static final long DETAILS_NANOS = 1_000_000_000L;
    private static final long RESEND_NANOS = 15_000_000_000L;
    /** Don't predict a fleet further ahead than this from its last update. */
    private static final float MAX_PREDICT_SECONDS = 1f;
    private static final float FOLLOW_RATE = 6f;
    private static final float SNAP_DISTANCE = 2000f;

    private static NpcSync current;

    private final Logger log = Global.getLogger(NpcSync.class);

    // ---- owner: publishing
    /** The location we publish ("" = nothing, null = we're not the owner right now). */
    private String pubLoc;
    private final Map<String, Integer> handles = new HashMap<String, Integer>();
    private final Map<Integer, CampaignFleetAPI> byHandle = new HashMap<Integer, CampaignFleetAPI>();
    private final Map<Integer, Integer> sentHash = new HashMap<Integer, Integer>();
    private int nextHandle = 1;
    private long nextCapture;
    private long nextDetails;
    private long nextResend;

    // ---- follower
    private LocationAPI zone;
    /** Whose world we follow right now, or -1. */
    private int zoneOwner = -1;
    private final Map<Integer, Driven> driven = new HashMap<Integer, Driven>();
    private final Map<CampaignFleetAPI, Hidden> hidden = new IdentityHashMap<CampaignFleetAPI, Hidden>();
    private long lastFrameNanos;

    private static final class Driven {
        final CampaignFleetAPI fleet;
        final boolean mirror;
        /** The owner's details last matched (0: not yet). */
        int hash;
        /** The owner's changing details (damage, readiness, commander) last applied (0: not yet). */
        int extraHash;
        Boolean oldAi;
        OrbitAPI oldOrbit;
        /** Our copy's own tooltip text, put back when we stop steering it. */
        String oldText;
        boolean textSaved;

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
            // Markets are shared everywhere, whatever the fleet settings.
            if (s.getState() == Session.State.HOSTING) MarketSync.hostFrame(s);
            else if (s.getState() == Session.State.CONNECTED) MarketSync.friendFrame(s);
        } catch (RuntimeException e) {
            log.warn("[MultiSector] market sync failed this frame", e);
        }
        try {
            boolean on = s.getState() == Session.State.HOSTING
                    || (s.getState() == Session.State.CONNECTED && s.getMyId() >= 0);
            if (!on || !MultiSectorConfig.isSyncNpcFleets()) {
                releaseAll();
                stopPublishing(s, on);
                stopWorldMoving();
                FleetLocks.releaseHolds();
                return;
            }
            int me = s.getMyId();
            FleetLocks.handleMessages(s, byHandle, s.playerName(me));
            int owner = s.ownerOf(me);
            if (owner == me) {
                releaseAll();
                publish(s);
            } else {
                stopPublishing(s, true);
                stopWorldMoving();
                FleetLocks.releaseHolds();
                follow(s, owner);
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
        s.stopWorldMoving(); // our own fleet must not be saved as "ignored by other fleets"
        FleetLocks.beforeSave();
    }

    /** Follower: the owner's handle for a fleet we're showing on their behalf, or -1 if it isn't one. */
    /** Follower: our fleet (stand-in or steered copy) for one of the owner's fleets, or null. */
    static CampaignFleetAPI fleetOfHandle(int handle) {
        NpcSync s = current;
        if (s == null) return null;
        Driven d = s.driven.get(handle);
        return d == null ? null : d.fleet;
    }

    static int handleOf(CampaignFleetAPI f) {
        NpcSync s = current;
        if (s == null) return -1;
        for (Map.Entry<Integer, Driven> e : s.driven.entrySet()) {
            if (e.getValue().fleet == f) return e.getKey();
        }
        return -1;
    }

    /** Follower: whose world we're showing, or -1. */
    static int followedOwner() {
        NpcSync s = current;
        return s == null || s.zone == null ? -1 : s.zoneOwner;
    }

    /** A save is loading: drop references into the old world without touching it. */
    public static void forget() {
        current = null;
        FleetLocks.forget();
        MarketSync.forget();
    }

    // ================================================================ owner

    private void publish(Session s) {
        long now = System.nanoTime();
        keepWorldMoving(s, now);
        FleetLocks.ownerFrame();
        if (now - nextCapture < 0) return;
        nextCapture = now + CAPTURE_NANOS;
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        LocationAPI loc = me == null ? null : me.getContainingLocation();
        // Only worth sending while someone follows us.
        String locId = loc == null || !s.hasFollowers(s.getMyId()) ? "" : FleetSync.locationId(loc);
        if (!locId.equals(pubLoc)) {
            pubLoc = locId;
            handles.clear();
            byHandle.clear();
            sentHash.clear();
            FleetLocks.releaseHolds(); // fleets held in the place we just left
            s.publishReset(locId);
            nextDetails = now;
            if (!locId.isEmpty()) log.info("[MultiSector] publishing NPC fleets in " + locId + " for the players here");
        }
        if (locId.isEmpty()) return;
        String myName = s.playerName(s.getMyId());
        boolean hyper = loc.isHyperspace();

        boolean details = now - nextDetails >= 0;
        if (details) nextDetails = now + DETAILS_NANOS;
        Set<Integer> seen = new HashSet<Integer>();
        List<NpcState> states = new ArrayList<NpcState>();
        for (CampaignFleetAPI f : loc.getFleets()) {
            if (!isNpc(f)) continue;
            if (hyper && !FleetLocks.isHeld(f) && dist(f.getLocation(), me.getLocation()) > PUB_RADIUS) continue;
            Integer h = handles.get(f.getId());
            boolean fresh = h == null;
            if (fresh) {
                h = nextHandle++;
                handles.put(f.getId(), h);
            }
            seen.add(h);
            byHandle.put(h, f);
            if (fresh || details) {
                NpcInfo info = describe(h, f, myName);
                int sent = 31 * (31 * info.hash + info.status.hashCode()) + info.extra.hashCode(); // and details
                Integer old = sentHash.get(h);
                if (old == null || old != sent) {
                    s.publishInfo(info);
                    sentHash.put(h, sent);
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
            s.publishGone(e.getValue());
            sentHash.remove(e.getValue());
            byHandle.remove(e.getValue());
            it.remove();
        }
        s.publishStates(states);
        if (now - nextResend >= 0) {
            nextResend = now + RESEND_NANOS;
            s.publishResendAll();
        }
    }

    /** No longer the owner (or the session ended): followers stop showing our fleets. */
    private void stopPublishing(Session s, boolean tell) {
        if (pubLoc == null) return;
        if (tell && !pubLoc.isEmpty()) s.publishReset("");
        pubLoc = null;
        handles.clear();
        byHandle.clear();
        sentHash.clear();
    }

    private static NpcInfo describe(int handle, CampaignFleetAPI f, String myName) {
        List<String> variants = new ArrayList<String>();
        List<String> hulls = new ArrayList<String>();
        org.json.JSONArray ships = new org.json.JSONArray();
        for (FleetMemberAPI m : ordered(f)) {
            String v = m.getVariant().getHullVariantId();
            variants.add(v == null ? "" : v);
            hulls.add(m.getHullSpec().getHullId());
            // To the nearest 5%, so small drifts don't resend.
            org.json.JSONArray st = new org.json.JSONArray();
            st.put(Math.round(m.getStatus().getHullFraction() * 20f));
            st.put(Math.round(m.getRepairTracker().getCR() * 20f));
            st.put(m.getRepairTracker().isMothballed() ? 1 : 0);
            ships.put(st);
        }
        String faction = f.getFaction() == null ? "" : f.getFaction().getId();
        String extra = "";
        try {
            org.json.JSONObject x = new org.json.JSONObject().put("s", ships);
            com.fs.starfarer.api.characters.PersonAPI c = f.getCommander();
            if (c != null && !c.isDefault() && c.getName() != null) {
                x.put("c", new org.json.JSONObject().put("f", c.getName().getFirst()).put("l", c.getName().getLast())
                        .put("g", c.getName().getGender() == null ? "ANY" : c.getName().getGender().name())
                        .put("p", c.getPortraitSprite()).put("r", c.getRankId()).put("po", c.getPostId()));
            }
            extra = x.toString();
        } catch (Exception e) {
            // the ships alone still work
        }
        return new NpcInfo(handle, f.getId(), faction, f.getName(), variants, hulls, status(f, myName), extra);
    }

    /** A fleet's ships the way we describe them: flagship first, then in the fleet's order, no fighter wings. */
    private static List<FleetMemberAPI> ordered(CampaignFleetAPI f) {
        FleetMemberAPI flag = f.getFlagship();
        List<FleetMemberAPI> members = f.getFleetData().getMembersListCopy();
        if (flag != null) {
            members.remove(flag);
            members.add(0, flag);
        }
        List<FleetMemberAPI> out = new ArrayList<FleetMemberAPI>();
        for (FleetMemberAPI m : members) {
            if (!m.isFighterWing() && m.getVariant() != null) out.add(m);
        }
        return out;
    }

    /** Each ship's hull, readiness and mothballing, and the commander, as the owner has them. */
    private static void applyExtra(CampaignFleetAPI f, NpcInfo info) {
        if (info.extra.isEmpty()) return;
        try {
            org.json.JSONObject x = new org.json.JSONObject(info.extra);
            org.json.JSONArray ships = x.optJSONArray("s");
            List<FleetMemberAPI> members = ordered(f);
            for (int i = 0; ships != null && i < ships.length() && i < members.size(); i++) {
                org.json.JSONArray st = ships.optJSONArray(i);
                if (st == null) continue;
                FleetMemberAPI m = members.get(i);
                m.getStatus().setHullFraction(Math.max(0.01f, st.optInt(0, 20) / 20f));
                m.getRepairTracker().setCR(st.optInt(1, 14) / 20f);
                boolean moth = st.optInt(2, 0) == 1;
                if (moth != m.getRepairTracker().isMothballed()) m.getRepairTracker().setMothballed(moth);
            }
            org.json.JSONObject c = x.optJSONObject("c");
            if (c != null) {
                com.fs.starfarer.api.characters.PersonAPI p = f.getCommander();
                if (p == null || p.isDefault()) {
                    p = Global.getFactory().createPerson();
                    p.setFaction(f.getFaction().getId());
                    f.setCommander(p);
                    FleetMemberAPI flag = f.getFlagship();
                    if (flag != null) flag.setCaptain(p);
                }
                com.fs.starfarer.api.characters.FullName.Gender g;
                try {
                    g = com.fs.starfarer.api.characters.FullName.Gender.valueOf(c.optString("g", "ANY"));
                } catch (IllegalArgumentException e) {
                    g = com.fs.starfarer.api.characters.FullName.Gender.ANY;
                }
                p.setName(new com.fs.starfarer.api.characters.FullName(c.optString("f", ""), c.optString("l", ""), g));
                if (!c.optString("p", "").isEmpty()) p.setPortraitSprite(c.optString("p"));
                if (!c.optString("r", "").isEmpty()) p.setRankId(c.optString("r"));
                if (!c.optString("po", "").isEmpty()) p.setPostId(c.optString("po"));
            }
            f.forceSync();
        } catch (Exception e) {
            // keep what we have
        }
    }

    /** What the fleet's tooltip should say it's doing, for every copy of it. */
    private static String status(CampaignFleetAPI f, String myName) {
        String holder = FleetLocks.holderOf(f);
        if (holder != null) return FleetLocks.statusFor(holder);
        if (f == dialogTarget()) return FleetLocks.statusFor(myName);
        try {
            if (f.getAI() == null) return "";
            String o = f.getAI().getActionTextOverride();
            if (o != null && !o.isEmpty()) return o;
            if (f.getAI().getCurrentAssignment() != null) {
                String t = f.getAI().getCurrentAssignment().getActionText();
                return t == null ? "" : t;
            }
        } catch (Throwable ignored) {
            // some custom fleet AI
        }
        return "";
    }

    // ---- owner in a dialog: the world keeps going for the followers

    /** Longest single step; a hitch never moves fleets in one big jump. */
    private static final float MAX_STEP_SECONDS = 0.1f;
    /** This many failed fleet updates in one dialog and we stop trying until it closes. */
    private static final int MAX_FAILURES = 20;

    private long lastRunNanos;
    private boolean setIgnoredFlag;
    private int failures;

    /**
     * Starsector stops the whole campaign while a dialog or menu is open. On
     * the owner that would also stop every NPC fleet its followers are
     * watching. So while the owner is in a dialog (and the shared clock is
     * running), its game moves those fleets itself, in real time:
     * <ul>
     * <li>the fleet the owner is talking to stays frozen, so it's still there
     *     when the dialog closes;</li>
     * <li>fleets in a battle, or held by a player, are left alone;</li>
     * <li>other fleets ignore the owner's fleet meanwhile, so nobody tries to
     *     start a second encounter with a player who's already in one.</li>
     * </ul>
     * Only fleets advance: no spawning, no battles resolving, no economy.
     * Those wait for the owner to close the dialog, as before.
     */
    private void keepWorldMoving(Session s, long now) {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        LocationAPI loc = me == null ? null : me.getContainingLocation();
        boolean active = MultiSectorConfig.isMoveFleetsDuringHostDialogs()
                && loc != null && !ClockSync.isMapView() && clockRunning(s)
                && pubLoc != null && !pubLoc.isEmpty() && pubLoc.equals(FleetSync.locationId(loc));
        if (!active) {
            stopWorldMoving();
            return;
        }
        float dt = lastRunNanos == 0 ? 0f : Math.min((now - lastRunNanos) / 1e9f, MAX_STEP_SECONDS);
        lastRunNanos = now;
        if (dt <= 0f || failures >= MAX_FAILURES) return;

        if (!setIgnoredFlag && !me.getMemoryWithoutUpdate().contains(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS)) {
            me.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, true);
            setIgnoredFlag = true;
        }
        SectorEntityToken talkingTo = dialogTarget();
        boolean hyper = loc.isHyperspace();
        for (CampaignFleetAPI f : new ArrayList<CampaignFleetAPI>(loc.getFleets())) {
            if (!isNpc(f) || f == talkingTo || f.getBattle() != null || FleetLocks.isHeld(f)) continue;
            if (hyper && dist(f.getLocation(), me.getLocation()) > PUB_RADIUS) continue;
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

    /** Is the shared clock running (not paused)? */
    private static boolean clockRunning(Session s) {
        if (s.getState() == Session.State.HOSTING) return s.getSharedClock().isRunning();
        ClockState.Received r = s.getRemoteClock();
        return r != null && !r.state.isStopped();
    }

    static SectorEntityToken dialogTarget() {
        com.fs.starfarer.api.campaign.CampaignUIAPI ui = Global.getSector().getCampaignUI();
        com.fs.starfarer.api.campaign.InteractionDialogAPI d = ui == null ? null : ui.getCurrentInteractionDialog();
        return d == null ? null : d.getInteractionTarget();
    }

    /** An ordinary NPC fleet: not a player, not one of our stand-ins, not a station. */
    private static int boostFailures;

    /**
     * Host, while a friend fast-forwards and we don't: our own fleet and the
     * NPC fleets around us get this much extra game time, as if this game
     * were fast-forwarding too (the game's own fast-forward only follows its
     * key). Fleets in a battle, held by a player, steered to another world or
     * hidden are left alone. Moved in small steps, like the game's own.
     */
    static void boostLocalFleets(float seconds) {
        if (seconds <= 0f || boostFailures >= MAX_FAILURES) return;
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        LocationAPI loc = me == null ? null : me.getContainingLocation();
        if (loc == null) return;
        boolean hyper = loc.isHyperspace();
        for (CampaignFleetAPI f : new ArrayList<CampaignFleetAPI>(loc.getFleets())) {
            boolean mine = f == me;
            if (!mine && !isNpc(f)) continue;
            com.fs.starfarer.api.campaign.rules.MemoryAPI mem = f.getMemoryWithoutUpdate();
            if (mem.getBoolean(DRIVEN_FLAG) || mem.getBoolean(HIDDEN_FLAG)) continue;
            if (f.getBattle() != null || FleetLocks.isHeld(f)) continue;
            if (!mine && hyper && dist(f.getLocation(), me.getLocation()) > PUB_RADIUS) continue;
            try {
                for (float left = seconds; left > 0f; left -= MAX_STEP_SECONDS) f.advance(Math.min(left, MAX_STEP_SECONDS));
            } catch (RuntimeException e) {
                if (++boostFailures == MAX_FAILURES) {
                    Global.getLogger(NpcSync.class).warn("[MultiSector] stopped speeding up fleets for a friend's fast-forward after repeated errors", e);
                }
            }
        }
    }

    private static boolean isNpc(CampaignFleetAPI f) {
        if (f.isPlayerFleet() || f.isStationMode()) return false;
        if (f.getMemoryWithoutUpdate().getBoolean(PuppetManager.PUPPET_FLAG)) return false;
        if (f.getMemoryWithoutUpdate().getBoolean(MIRROR_FLAG)) return false;
        return f.getFleetData() != null && f.getFleetData().getNumMembers() > 0;
    }

    private static float dist(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // ================================================================ follower

    private void follow(Session s, int owner) {
        long now = System.nanoTime();
        float frameDt = lastFrameNanos == 0 ? 0f : Math.min((now - lastFrameNanos) / 1e9f, 0.25f);
        lastFrameNanos = now;
        FleetLocks.followerFrame(s);

        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        LocationAPI here = me == null ? null : me.getContainingLocation();
        Session.Feed feed = s.getFeed(owner);
        String ownerLocation = feed.getLocation();
        boolean shared = here != null && !ownerLocation.isEmpty() && ownerLocation.equals(FleetSync.locationId(here));
        if (!shared) {
            releaseAll();
            return;
        }
        if (zone != here || zoneOwner != owner) {
            releaseAll();
            zone = here;
            zoneOwner = owner;
            loggedHidden = 0;
            log.info("[MultiSector] following " + s.playerName(owner) + "'s NPC fleets in " + ownerLocation);
        }

        Map<Integer, NpcInfo> infos = feed.getInfos();
        Map<Integer, NpcState.Received> states = feed.getStates();
        float speed = clockSpeed(s.getRemoteClock(), s);

        // Fleets the owner no longer has here.
        for (Iterator<Map.Entry<Integer, Driven>> it = driven.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Driven> e = it.next();
            Driven d = e.getValue();
            if (!infos.containsKey(e.getKey()) || d.fleet.getContainingLocation() != here) {
                release(d);
                it.remove();
            }
        }

        // The owner's fleets: steer our copy, or show a stand-in.
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
            }
            if (d.hash != info.hash && !FleetLocks.isActive(d.fleet)) {
                match(d, info); // ships lost or gained on the owner's game, or never matched yet
                d.hash = info.hash;
                d.extraHash = 0; // the ships may be new: put their damage back on
            }
            if (d.extraHash != info.extra.hashCode() && !FleetLocks.isActive(d.fleet)) {
                applyExtra(d.fleet, info);
                d.extraHash = info.extra.hashCode();
            }
            follow(d.fleet, st, speed, now, frameDt);
            showStatus(d, info);
            ours.add(d.fleet);
        }

        // Everything else here is this game's own roll of the dice: hide it while shared.
        // In hyperspace, only near the owner: further out the owner's game has no fleets to show.
        Vector2f ownerAt = null;
        if (here.isHyperspace()) {
            FleetState.Received os = s.getRemoteFleets().get(owner);
            if (os != null) ownerAt = new Vector2f(os.state.x, os.state.y);
        }
        for (CampaignFleetAPI f : new ArrayList<CampaignFleetAPI>(here.getFleets())) {
            if (ours.contains(f) || !isNpc(f) || f.getMemoryWithoutUpdate().getBoolean(DRIVEN_FLAG)) continue;
            Hidden h = hidden.get(f);
            if (h != null) {
                park(f, h); // the game may move it; keep it out of sight
                continue;
            }
            if (f.getBattle() != null) continue; // never pull a fleet out of a battle
            if (here.isHyperspace() && (ownerAt == null || dist(f.getLocation(), ownerAt) > HIDE_RADIUS)) continue;
            hidden.put(f, hide(f));
            if (loggedHidden++ < LOG_HIDDEN) {
                log.info("[MultiSector] hiding this game's own " + f.getName() + " (" + f.getFaction().getId()
                        + ", " + f.getFleetData().getNumMembers() + " ships): " + s.playerName(owner)
                        + "'s world doesn't have it");
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
    private static float clockSpeed(ClockState.Received r, Session s) {
        ClockState st = s.getState() == Session.State.HOSTING
                ? s.getSharedClock().snapshot(System.nanoTime()) // the host follows a friend's world too
                : r == null ? null : r.state;
        if (st == null) return 1f;
        if (st.isStopped()) return 0f;
        return st.isFast() ? st.fastMult : 1f;
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

    /**
     * Makes our copy match the owner's details. A stand-in is simply
     * rebuilt. Our own copy of a save fleet keeps everything about it (its
     * officers, its part in missions and bounties) except the ships, name and
     * faction, which follow the owner's: it may have fought on one game and
     * not the other.
     */
    private void match(Driven d, NpcInfo info) {
        CampaignFleetAPI f = d.fleet;
        if (d.mirror) {
            fill(f, info);
            return;
        }
        NpcInfo mine = describe(info.handle, f, "");
        if (!mine.variants.equals(info.variants) || !mine.hulls.equals(info.hulls)) {
            log.info("[MultiSector] " + f.getName() + " has different ships on the owner's game ("
                    + mine.hulls.size() + " here, " + info.hulls.size() + " there): using theirs");
            fill(f, info);
        }
        if (!info.name.isEmpty() && !info.name.equals(f.getName())) f.setName(info.name);
        if (!info.faction.isEmpty() && (f.getFaction() == null || !info.faction.equals(f.getFaction().getId()))) {
            try {
                f.setFaction(info.faction, true);
            } catch (RuntimeException e) {
                // a faction this game doesn't have: keep ours
            }
        }
    }

    /** Our copy's tooltip says what the owner's fleet is doing, not what our copy was doing. */
    private static void showStatus(Driven d, NpcInfo info) {
        if (d.fleet.getAI() == null) return;
        if (!d.textSaved) {
            d.oldText = d.fleet.getAI().getActionTextOverride();
            d.textSaved = true;
        }
        String want = info.status.isEmpty() ? d.oldText : info.status;
        String now = d.fleet.getAI().getActionTextOverride();
        if (want == null ? now != null : !want.equals(now)) d.fleet.getAI().setActionTextOverride(want);
    }

    private CampaignFleetAPI spawnMirror(NpcInfo info, NpcState st, LocationAPI here) {
        String faction = info.faction;
        if (Global.getSector().getFaction(faction) == null) faction = Factions.INDEPENDENT;
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

    /** Replaces the fleet's ships with the owner's list. Returns false if none could be made. */
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
        if (f.getCommander() != null) built.get(0).setCaptain(f.getCommander()); // the commander keeps their ship
        f.forceSync();
        return true;
    }

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

    /** Glides toward where the owner's fleet is now, predicted from its last update. */
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
        f.getStats().getDetectedRangeMod().modifyMult(MOD_ID, 0f, "MultiSector: not in the owner's world");
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
        if (d.textSaved && f.getAI() != null) f.getAI().setActionTextOverride(d.oldText);
        f.getMemoryWithoutUpdate().unset(DRIVEN_FLAG);
    }

    /** Gives this game its own fleets back and removes every stand-in. */
    private void releaseAll() {
        if (driven.isEmpty() && hidden.isEmpty() && zone == null) return;
        for (Driven d : driven.values()) release(d);
        for (Map.Entry<CampaignFleetAPI, Hidden> e : hidden.entrySet()) unhide(e.getKey(), e.getValue());
        if (zone != null) log.info("[MultiSector] stopped following NPC fleets (" + driven.size() + " followed, "
                + hidden.size() + " hidden)");
        driven.clear();
        hidden.clear();
        zone = null;
        zoneOwner = -1;
        lastFrameNanos = 0;
    }
}
