package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.util.Misc;
import multisector.net.ClockState;
import multisector.net.Session;
import multisector.net.SharedClock;
import multisector.ui.MultiSectorConfig;
import org.apache.log4j.Logger;
import org.lwjgl.input.Keyboard;

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
 * <li><b>Fast-forward:</b> anyone. A friend's fast-forward is sent to the
 *     host, the shared clock speeds up, and the host's game moves its fleets
 *     the extra time itself (its own fast-forward key can't be pressed for
 *     it). Everyone else is told to fast-forward too.</li>
 * <li><b>Nobody on the map:</b> the world waits (Session, on the host's
 *     network thread, since campaigns don't run during battles).</li>
 * </ul>
 *
 * Transient: added on each load, never saved.
 */
public class ClockSync implements EveryFrameScript {

    private static final long HOUR = 3600L * 1000L;
    private static final long DAY = 24L * HOUR;
    /** Host: after a menu, catch up if the shared clock got this far ahead. */
    private static final long HOST_CATCH_UP = HOUR;
    /** Host, while a friend fast-forwards: the date steps forward in pieces this big (planets with it). */
    private static final long BOOST_CATCH_UP = 6 * HOUR;
    /** Most extra game time the host's fleets get in one frame of a friend's fast-forward. */
    private static final float MAX_BOOST_SECONDS = 1f;
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
    private boolean sentFast;
    private long nextFastSend;
    // host
    private int shownFastBy = -1;

    public boolean isDone() {
        return false;
    }

    public boolean runWhilePaused() {
        return true;
    }

    public void advance(float amount) {
        Session session = Session.get();
        session.gameAlive(); // this world is open (the host tells friends when it closes)
        Session.State state = session.getState();
        if (state != Session.State.IDLE) Docking.track(); // before any catch-up below moves the planets
        if (state == Session.State.HOSTING || state == Session.State.CONNECTED) dialogPauseKey(session);
        if (state != lastState) {
            lastState = state;
            resetFriendState();
            wasMapView = false;
        }
        long nanos = System.nanoTime();
        try {
            if (state == Session.State.HOSTING) {
                host(session, nanos, amount);
            } else if (state == Session.State.CONNECTED) {
                friend(session, nanos, amount);
            }
        } catch (RuntimeException e) {
            log.warn("[MultiSector] clock sync failed this frame", e);
        }
    }

    // ================================================================ host

    private void host(Session session, long nanos, float amount) {
        SharedClock clock = session.getSharedClock();
        SectorAPI sector = Global.getSector();
        CampaignClockAPI c = sector.getClock();
        long local = c.getTimestamp();
        boolean map = isMapView();
        if (map) {
            session.markOnMap();
            clock.setIdle(false, nanos); // we're back on the map: the world runs again now, not at the next report
        }
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
            boolean hostFast = ui.isFastForward();
            float[] asked = MultiSectorConfig.isFriendsCanFastForward() ? session.fastRequest(nanos) : null;
            // A friend fast-forwarding while we aren't: the world speeds up anyway, and this game keeps up below.
            boolean boosted = running && !hostFast && asked != null;
            float mult = hostFast ? Global.getSettings().getFloat(SPEEDUP) : asked != null ? asked[1] : 1f;
            int by = hostFast ? session.getMyId() : asked != null ? (int) asked[0] : -1;
            clock.setFast(running && (hostFast || asked != null), mult, by, nanos);
            if (boosted && shownFastBy != by) {
                session.notice(session.playerName(by) + " is fast-forwarding, so the world speeds up for everyone.");
            }
            shownFastBy = boosted ? by : -1;

            long shared = clock.now(nanos);
            long behind = shared - local;
            if (boosted) {
                // Our fast-forward key can't be pressed for us, so our fleets get the extra time directly,
                // and the date (with the planets) steps forward to the shared clock in small pieces.
                NpcSync.boostLocalFleets(Math.min(amount * (mult - 1f), MAX_BOOST_SECONDS));
                if (behind > BOOST_CATCH_UP) ClockAccess.set(shared, true);
            } else if (running ? behind > HOST_CATCH_UP : Math.abs(behind) > STOPPED_SNAP) {
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

    private void friend(Session session, long nanos, float amount) {
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
        askToFastForward(session, sector, nanos);
        // Someone else is fast-forwarding and we aren't: our game can't press the key for us, so our fleet and the
        // fleets of our own world here get the extra time directly (stand-ins follow their owner's game anyway).
        CampaignUIAPI fui = sector.getCampaignUI();
        boolean boosted = st.isFast() && st.fastBy != session.getMyId() && !sector.isPaused()
                && fui != null && !fui.isFastForward();
        if (boosted) NpcSync.boostLocalFleets(Math.min(amount * (st.fastMult - 1f), MAX_BOOST_SECONDS));

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
        if (Math.abs(diff) > (st.isStopped() ? STOPPED_SNAP : CLIENT_DRIFT)) ClockAccess.set(target, boosted);
        lastLocalPaused = sector.isPaused();
        wasMapView = true;
    }

    /**
     * We're holding fast-forward: ask the host to speed the world up for
     * everyone (our own speed-up stays off until it does, so we never run
     * ahead). Repeated every second while held, so a lost or stale request
     * just ends.
     */
    private void askToFastForward(Session session, SectorAPI sector, long nanos) {
        if (!MultiSectorConfig.isFriendsCanFastForward()) return;
        CampaignUIAPI ui = sector.getCampaignUI();
        boolean want = ui != null && ui.isFastForward() && !sector.isPaused();
        float mult = originalSpeedup != null ? originalSpeedup : Global.getSettings().getFloat(SPEEDUP);
        if (want == sentFast && (!want || nanos - nextFastSend < 0)) return;
        sentFast = want;
        nextFastSend = nanos + 1_000_000_000L;
        session.requestFast(want, mult);
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
        if ((!prev.isFast() || prev.fastBy != st.fastBy) && st.isFast() && st.fastBy != session.getMyId()) {
            session.notice(session.playerName(st.fastBy < 0 ? 0 : st.fastBy)
                    + " is fast-forwarding, so the world speeds up for everyone.");
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
        sentFast = false;
        shownFastBy = -1;
        synced = false;
        lastSeq = -1;
        lastShown = null;
        if (originalSpeedup != null) {
            Global.getSettings().setFloat(SPEEDUP, originalSpeedup);
            originalSpeedup = null;
        }
        appliedSpeedup = -1f;
    }

    // ================================================================ pause from inside a dialog

    private boolean keyWasDown;

    /**
     * Ctrl+Space inside a dialog, market or menu pauses or resumes the shared
     * world for everyone. (On the map, the game's own pause key already does.)
     * The game routes input to the open dialog, so the keyboard is read
     * directly. Plain Space would fire while typing a ship name, so it
     * needs Ctrl.
     */
    private void dialogPauseKey(Session session) {
        boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
        boolean down = ctrl && Keyboard.isKeyDown(Keyboard.KEY_SPACE);
        boolean pressed = down && !keyWasDown;
        keyWasDown = down;
        if (!pressed || isMapView()) return;

        boolean pause;
        if (session.getState() == Session.State.HOSTING) {
            SharedClock clock = session.getSharedClock();
            pause = clock.isRunning();
            clock.setPaused(pause, session.getMyId(), System.nanoTime());
        } else {
            ClockState.Received r = session.getRemoteClock();
            pause = r == null || !r.state.isStopped();
            session.requestPause(pause);
        }
        String text = pause ? "Paused the game for everyone (Ctrl+Space resumes)."
                : "Resumed the game for everyone.";
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (ui != null && ui.getCurrentInteractionDialog() != null) {
            ui.getCurrentInteractionDialog().getTextPanel().addPara("MultiSector: " + text, Misc.getGrayColor());
        } else {
            session.notice(text);
        }
    }

    // ================================================================ shared

    /** "Febl is fast-forwarding (x3)" while someone else speeds the world up, for the players panel; else null. */
    public static String fastText() {
        Session s = Session.get();
        int by;
        float mult;
        if (s.getState() == Session.State.HOSTING) {
            SharedClock c = s.getSharedClock();
            if (!c.isFast()) return null;
            by = c.getFastBy();
            mult = c.getFastMult();
        } else {
            ClockState.Received r = s.getRemoteClock();
            if (r == null || !r.state.isFast()) return null;
            by = r.state.fastBy;
            mult = r.state.fastMult;
        }
        if (by == s.getMyId() || by < 0) return null; // our own shows on the game's own speed indicator
        return s.playerName(by) + " is fast-forwarding (x" + Math.round(mult) + ")";
    }

    /** On the campaign map itself: no dialog, menu or core screen (cargo, refit, intel...) open. */
    public static boolean isMapView() {
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        return ui != null && !ui.isShowingDialog() && !ui.isShowingMenu() && ui.getCurrentCoreTab() == null;
    }

    private static String days(long gameMillis) {
        return String.format(Locale.ROOT, "%.1f", gameMillis / (double) DAY);
    }
}
