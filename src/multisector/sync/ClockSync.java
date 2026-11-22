package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import multisector.net.ClockState;
import multisector.net.Session;
import multisector.net.SharedClock;
import org.apache.log4j.Logger;

import java.util.Locale;

/**
 * Keeps every player on one campaign date, and shares pause and fast-forward.
 *
 * <ul>
 * <li><b>Host:</b> feeds its own clock into the {@link SharedClock} while on
 *     the map, and catches up to it after a menu or dialog (the shared clock
 *     kept running meanwhile).</li>
 * <li><b>Friends:</b> follow the clock the host sends. A date that drifts
 *     too far is set to the host's, so nobody runs ahead or falls behind,
 *     however long their game spent loading or in a market.</li>
 * <li><b>Pause:</b> pressing pause on the map pauses everyone, and anyone
 *     can resume. Pauses caused by your own menus and dialogs stay local.</li>
 * <li><b>Fast-forward:</b> follows the host. A friend's fast-forward only
 *     works while the host is fast-forwarding too.</li>
 * </ul>
 *
 * Transient: added on each load, never saved.
 */
public class ClockSync implements EveryFrameScript {

    private static final long HOUR = 3600L * 1000L;
    private static final long DAY = 24L * HOUR;
    /** Host: after a menu, catch up if the shared clock got this far ahead. */
    private static final long HOST_CATCH_UP = HOUR;
    /** Friends: correct the date when it's this far from the host's (ping jitter stays well under it). */
    private static final long CLIENT_DRIFT = 3 * HOUR;
    /** While stopped nothing moves, so line up exactly. */
    private static final long STOPPED_SNAP = 60L * 1000L;
    private static final String SPEEDUP = "campaignSpeedupMult";

    private final Logger log = Global.getLogger(ClockSync.class);

    private Session.State lastState = Session.State.IDLE;
    private boolean wasMapView;
    private boolean lastLocalPaused;

    // friends
    private Boolean pendingPause;
    private long pendingUntil;
    private boolean synced;
    private int lastSeq = -1;
    private ClockState lastShown;
    private Float originalSpeedup;
    private float appliedSpeedup = -1f;
    private boolean speedupHintShown;

    public boolean isDone() {
        return false;
    }

    public boolean runWhilePaused() {
        return true;
    }

    public void advance(float amount) {
        Session session = Session.get();
        Session.State state = session.getState();
        if (state != Session.State.IDLE) Docking.track(); // before any catch-up below moves the planets
        if (state != lastState) {
            lastState = state;
            resetFriendState();
            wasMapView = false;
        }
        long nanos = System.nanoTime();
        try {
            if (state == Session.State.HOSTING) {
                host(session, nanos);
            } else if (state == Session.State.CONNECTED) {
                friend(session, nanos);
            }
        } catch (RuntimeException e) {
            log.warn("[MultiSector] clock sync failed this frame", e);
        }
    }

    // ================================================================ host

    private void host(Session session, long nanos) {
        SharedClock clock = session.getSharedClock();
        SectorAPI sector = Global.getSector();
        CampaignClockAPI c = sector.getClock();
        long local = c.getTimestamp();
        boolean map = isMapView();
        if (!clock.isStarted()) {
            if (!map) return; // start from a moment on the map, where pause means "the player paused"
            clock.anchor(local, nanos);
            clock.setPaused(sector.isPaused(), session.getMyId(), nanos);
            lastLocalPaused = sector.isPaused();
            wasMapView = true;
        }
        clock.setSecondsPerDay(c.getSecondsPerDay(), nanos);
        for (String gone : clock.expireHolds(nanos)) {
            session.notice("Stopped waiting for " + gone + " to join (it took too long).");
        }

        if (map) {
            boolean localPaused = sector.isPaused();
            if (wasMapView && localPaused != lastLocalPaused) {
                boolean waited = clock.isHeld();
                if (clock.setPaused(localPaused, session.getMyId(), nanos) && !localPaused && waited) {
                    session.notice("Stopped waiting for joining players.");
                }
            }
            boolean running = clock.isRunning();
            if (localPaused == running) sector.setPaused(!running);
            CampaignUIAPI ui = sector.getCampaignUI();
            clock.setFast(running && ui.isFastForward(), Global.getSettings().getFloat(SPEEDUP), nanos);

            long shared = clock.now(nanos);
            long behind = shared - local;
            if (running ? behind > HOST_CATCH_UP : Math.abs(behind) > STOPPED_SNAP) {
                // The world clock kept going while this game sat in a menu.
                ClockAccess.set(shared);
                if (behind > DAY) log.info("[MultiSector] caught up " + days(behind) + " days to the shared clock");
            } else if (running) {
                clock.anchor(local, nanos); // on the map, the host's game is the truth
            }
            lastLocalPaused = sector.isPaused();
        }
        wasMapView = map;
    }

    // ================================================================ friends

    private void friend(Session session, long nanos) {
        ClockState.Received r = session.getRemoteClock();
        if (r == null) return; // nothing from the host yet
        ClockState st = r.state;
        if (st.seq != lastSeq) {
            lastSeq = st.seq;
            announce(session, st);
        }

        boolean map = isMapView();
        if (!map) {
            wasMapView = false;
            return;
        }
        SectorAPI sector = Global.getSector();
        boolean localPaused = sector.isPaused();
        if (wasMapView && localPaused != lastLocalPaused) {
            // The player pressed pause: ask the host to do it for everyone.
            session.requestPause(localPaused);
            pendingPause = localPaused;
            pendingUntil = nanos + 3_000_000_000L;
        }
        if (pendingPause != null && (st.isStopped() == pendingPause || nanos - pendingUntil > 0)) pendingPause = null;
        boolean wantPaused = pendingPause != null ? pendingPause : st.isStopped();
        if (localPaused != wantPaused) sector.setPaused(wantPaused);

        capSpeedup(st.isFast() ? st.fastMult : 1f);

        int ping = session.getPingMs();
        long target = st.extrapolate(r.arrivedNanos, nanos, ping > 0 ? ping / 2f : 0f);
        long local = sector.getClock().getTimestamp();
        long diff = target - local;
        if (!synced) {
            synced = true;
            if (Math.abs(diff) > DAY) {
                session.notice((diff > 0 ? "Moved the date forward " : "Moved the date back ") + days(Math.abs(diff))
                        + " days to match the host's world.");
            }
            log.info("[MultiSector] first clock sync: " + days(diff) + " days off the host");
        }
        if (Math.abs(diff) > (st.isStopped() ? STOPPED_SNAP : CLIENT_DRIFT)) ClockAccess.set(target);
        lastLocalPaused = sector.isPaused();
        wasMapView = true;
    }

    /** "Alice paused the game", "waiting for Bob", and so on, when the shared state changes. */
    private void announce(Session session, ClockState st) {
        ClockState prev = lastShown;
        lastShown = st;
        if (prev == null) {
            if (st.isHeld()) session.notice(waitingText(st));
            return;
        }
        boolean byMe = st.changedBy == session.getMyId();
        if (!prev.isPaused() && st.isPaused() && !byMe) {
            session.notice(session.playerName(st.changedBy) + " paused the game.");
        } else if (prev.isPaused() && !st.isPaused() && !byMe) {
            session.notice(session.playerName(st.changedBy) + " resumed the game.");
        }
        if (!prev.isHeld() && st.isHeld()) session.notice(waitingText(st));
        if (prev.isHeld() && !st.isHeld() && !st.isPaused()) session.notice("Done waiting; the game carries on.");
        if (!prev.isFast() && st.isFast()) {
            session.notice("The host is fast-forwarding. Turn on fast-forward too to keep up.");
        }
    }

    private static String waitingText(ClockState st) {
        return "Paused while " + st.heldFor + " joins. Unpause to stop waiting.";
    }

    /**
     * Fast-forward multiplies how many times the game advances per frame by
     * campaignSpeedupMult. Setting it to 1 makes a friend's fast-forward do
     * nothing while the host isn't fast-forwarding, so they can't run ahead.
     */
    private void capSpeedup(float mult) {
        if (mult == appliedSpeedup) return;
        SettingsAPI settings = Global.getSettings();
        if (originalSpeedup == null) originalSpeedup = settings.getFloat(SPEEDUP);
        float m = mult <= 1f ? 1f : mult;
        settings.setFloat(SPEEDUP, m);
        appliedSpeedup = mult;
        if (m <= 1f && !speedupHintShown && originalSpeedup > 1f) {
            speedupHintShown = true;
            log.info("[MultiSector] fast-forward follows the host now (" + SPEEDUP + " " + originalSpeedup + " -> 1)");
        }
    }

    private void resetFriendState() {
        pendingPause = null;
        synced = false;
        lastSeq = -1;
        lastShown = null;
        if (originalSpeedup != null) {
            Global.getSettings().setFloat(SPEEDUP, originalSpeedup);
            originalSpeedup = null;
        }
        appliedSpeedup = -1f;
    }

    // ================================================================ shared

    /** On the campaign map itself: no dialog, menu or core screen (cargo, refit, intel...) open. */
    public static boolean isMapView() {
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        return ui != null && !ui.isShowingDialog() && !ui.isShowingMenu() && ui.getCurrentCoreTab() == null;
    }

    private static String days(long gameMillis) {
        return String.format(Locale.ROOT, "%.1f", gameMillis / (double) DAY);
    }
}
