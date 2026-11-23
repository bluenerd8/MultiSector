package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.util.Misc;
import multisector.net.ClockState;
import multisector.net.FleetState;
import multisector.net.Session;
import multisector.ui.MultiSectorConfig;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

/**
 * Two small things about where your fleet goes, in a multiplayer world.
 *
 * <ul>
 * <li><b>Escort another player:</b> your fleet follows theirs closely, a
 *     little behind, without any dialog or encounter. It follows them
 *     through jump points too. Clicking somewhere else on the map (or their
 *     leaving the game) ends it.</li>
 * <li><b>Menus don't anchor you</b> ({@code keepMovingInMenus}): Starsector
 *     stops your fleet while the cargo, refit, character or map screen is
 *     open, but the shared world keeps going. Your fleet keeps flying its
 *     course meanwhile, so the others don't see you frozen in place. Off,
 *     or in dialogs (docked, talking), it stays put.</li>
 * </ul>
 *
 * Transient: added on each load.
 */
public final class Escort implements EveryFrameScript {

    private static final Logger log = Global.getLogger(Escort.class);
    /** How far behind the escorted fleet's edge we keep. */
    private static final float GAP = 120f;
    /** Most game time the fleet moves in one frame while a menu is open. */
    private static final float MAX_MENU_STEP = 0.1f;

    private static int target = -1;
    private static Vector2f lastSet;
    private static String lastLocation;
    private static float lastX, lastY;
    private static boolean seenThere;
    private long lastMenuNanos;
    private static boolean keepMoving = true;

    /** Start following that player's fleet. */
    static void start(int playerId) {
        target = playerId;
        lastSet = null;
        seenThere = false;
        try {
            CampaignUIAPI ui = Global.getSector().getCampaignUI();
            if (ui != null) ui.clearLaidInCourse(); // a plotted course would steer us away again every frame
        } catch (RuntimeException ignored) {
            // no course to clear
        }
        Session.get().notice("Escorting " + Session.get().playerName(playerId)
                + ". Click anywhere on the map to stop.");
        log.info("[MultiSector] escorting " + Session.get().playerName(playerId));
    }

    static void stop(String why) {
        if (target < 0) return;
        log.info("[MultiSector] stopped escorting " + Session.get().playerName(target) + ": " + why);
        Session.get().notice("Stopped escorting " + Session.get().playerName(target) + " (" + why + ").");
        target = -1;
        lastSet = null;
    }

    static boolean isEscorting(int playerId) {
        return target == playerId;
    }

    public static void forget() {
        target = -1;
        lastSet = null;
        keepMoving = MultiSectorConfig.isKeepMovingInMenus();
    }

    /** The Multiplayer window's switch: keep flying in menus, or stay put. */
    public static boolean isKeepMoving() {
        return keepMoving;
    }

    public static void setKeepMoving(boolean on) {
        keepMoving = on;
    }

    public boolean isDone() {
        return false;
    }

    public boolean runWhilePaused() {
        return true;
    }

    public void advance(float amount) {
        Session s = Session.get();
        if (!s.isActive()) {
            if (target >= 0) stop("not connected");
            lastMenuNanos = 0;
            return;
        }
        try {
            moveInMenus(s);
            if (target >= 0 && ClockSync.isMapView() && !Global.getSector().isPaused()) follow(s);
        } catch (RuntimeException e) {
            log.warn("[MultiSector] escort/menu movement failed this frame", e);
        }
    }

    // ================================================================ escort

    private void follow(Session s) {
        if (!s.isPlayerHere(target)) {
            stop("they left the game");
            return;
        }
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (me == null || me.getBattle() != null) return;
        // Did the player send us somewhere else since our last order? Then they've taken over.
        if (lastSet != null && me.getMoveDestination() != null
                && Misc.getDistance(me.getMoveDestination(), lastSet) > 50f) {
            stop("you set another course");
            return;
        }
        CampaignFleetAPI them = PuppetManager.fleetOf(target);
        FleetState.Received r = s.getRemoteFleets().get(target);
        if (them != null && them.getContainingLocation() == me.getContainingLocation()) {
            seenThere = true;
            lastLocation = FleetSync.locationId(me.getContainingLocation());
            lastX = them.getLocation().x;
            lastY = them.getLocation().y;
            // A little behind them (opposite their heading), clear of both fleets.
            Vector2f v = them.getVelocity();
            float len = v.length();
            float dist = them.getRadius() + me.getRadius() + GAP;
            float dx = len > 1f ? -v.x / len : 0f, dy = len > 1f ? -v.y / len : -1f;
            Vector2f want = new Vector2f(lastX + dx * dist, lastY + dy * dist);
            me.setMoveDestination(want.x, want.y);
            lastSet = want;
            return;
        }
        if (r == null || r.state == null) return;
        // They're somewhere else: through the jump point nearest where we last saw them, if one leads there.
        LocationAPI here = me.getContainingLocation();
        if (!seenThere || here == null) {
            stop("they're not in this system");
            return;
        }
        JumpPointAPI jp = null;
        JumpPointAPI.JumpDestination dest = null;
        float best = 3000f;
        for (SectorEntityToken e : here.getJumpPoints()) {
            if (!(e instanceof JumpPointAPI)) continue;
            JumpPointAPI p = (JumpPointAPI) e;
            float d = Misc.getDistance(p.getLocation(), new Vector2f(lastX, lastY));
            if (d > best) continue;
            for (JumpPointAPI.JumpDestination jd : p.getDestinations()) {
                SectorEntityToken to = jd.getDestination();
                if (to != null && to.getContainingLocation() != null
                        && FleetSync.locationId(to.getContainingLocation()).equals(r.state.location)) {
                    jp = p;
                    dest = jd;
                    best = d;
                }
            }
        }
        if (jp == null) {
            stop("lost them: they didn't leave through a jump point");
            return;
        }
        if (Misc.getDistance(me.getLocation(), jp.getLocation()) < jp.getRadius() + me.getRadius() + 150f) {
            log.info("[MultiSector] following " + s.playerName(target) + " through " + jp.getName());
            lastSet = null;
            seenThere = false;
            Global.getSector().doHyperspaceTransition(me, jp, dest);
            return;
        }
        me.setMoveDestination(jp.getLocation().x, jp.getLocation().y);
        lastSet = new Vector2f(jp.getLocation());
    }

    // ================================================================ menus

    /** In the cargo, refit, character or map screen: our fleet keeps flying its course with the world. */
    private void moveInMenus(Session s) {
        long now = System.nanoTime();
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        boolean inMenu = keepMoving && ui != null && ui.getCurrentCoreTab() != null
                && ui.getCurrentInteractionDialog() == null && me != null && me.getBattle() == null
                && worldRunning(s);
        if (!inMenu) {
            lastMenuNanos = 0;
            return;
        }
        float dt = lastMenuNanos == 0 ? 0f : Math.min((now - lastMenuNanos) / 1e9f, MAX_MENU_STEP);
        lastMenuNanos = now;
        if (dt <= 0f || me.getMoveDestination() == null) return;
        if (Misc.getDistance(me.getLocation(), me.getMoveDestination()) < 5f) return; // nowhere to go
        me.advance(dt * speedMult(s));
    }

    private static boolean worldRunning(Session s) {
        if (s.getState() == Session.State.HOSTING) return s.getSharedClock().isRunning();
        ClockState.Received r = s.getRemoteClock();
        return r != null && !r.state.isStopped();
    }

    private static float speedMult(Session s) {
        if (s.getState() == Session.State.HOSTING) return 1f; // the shared clock's fast-forward is the host's own
        ClockState.Received r = s.getRemoteClock();
        return r != null && r.state.isFast() ? Math.max(1f, r.state.fastMult) : 1f;
    }
}
