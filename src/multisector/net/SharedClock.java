package multisector.net;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host only: the one campaign clock every player follows.
 *
 * It runs on real time, not on the host's game loop. Starsector stops the
 * whole campaign while a market, dialog or menu is open, so if the shared
 * clock were the host's own clock, every friend would freeze each time the
 * host went shopping. Instead:
 *
 * <ul>
 * <li>while the host is on the map, the host's game is the truth and this
 *     clock is re-anchored to it every frame ({@link #anchor});</li>
 * <li>while the host is in a menu, this clock keeps running by itself, and
 *     the host's game catches up when the menu closes (like everyone else).</li>
 * </ul>
 *
 * It stops when someone pauses ({@link #setPaused}) or while a friend is
 * joining ({@link #hold}). Thread-safe: the game thread and the network
 * threads both use it.
 */
public final class SharedClock {

    /** Game days per real second at normal speed comes from the game (10 s per day). */
    private float secondsPerDay = 10f;

    private boolean started;
    private long anchorMillis;
    private long anchorNanos;

    private boolean paused;
    private int changedBy = -1;
    private boolean fast;
    private float fastMult = 1f;
    /** Friend name -> System.nanoTime when the hold gives up. */
    private final Map<String, Long> holds = new LinkedHashMap<String, Long>();
    private int seq;

    public synchronized void reset() {
        started = false;
        paused = false;
        changedBy = -1;
        fast = false;
        fastMult = 1f;
        holds.clear();
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public synchronized void setSecondsPerDay(float s, long nanos) {
        if (s <= 0 || s == secondsPerDay) return;
        reanchor(nanos);
        secondsPerDay = s;
    }

    /** Game milliseconds per real millisecond, right now. */
    public synchronized float rate(long nanos) {
        if (!running(nanos)) return 0f;
        float normal = 86400f / secondsPerDay; // 86400 game s per day / real s per day
        return fast ? normal * fastMult : normal;
    }

    /** The shared time (game clock timestamp) at real time nanos. */
    public synchronized long now(long nanos) {
        if (!started) return 0;
        return anchorMillis + (long) (rate(nanos) * ((nanos - anchorNanos) / 1e6f));
    }

    /** The host's game says "the time is gameMillis now". */
    public synchronized void anchor(long gameMillis, long nanos) {
        anchorMillis = gameMillis;
        anchorNanos = nanos;
        started = true;
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    /**
     * Pauses or resumes for everyone. Resuming also stops waiting for any
     * joining friend. Returns false if nothing changed.
     */
    public synchronized boolean setPaused(boolean p, int by, long nanos) {
        boolean wasHeld = !holds.isEmpty();
        if (p == paused && (p || !wasHeld)) return false;
        reanchor(nanos);
        paused = p;
        changedBy = by;
        if (!p) holds.clear();
        return true;
    }

    public synchronized void setFast(boolean f, float mult, long nanos) {
        if (f == fast && mult == fastMult) return;
        reanchor(nanos);
        fast = f;
        fastMult = Math.max(1f, mult);
    }

    /** Stops the clock until that friend connects, or until maxNanos have passed. */
    public synchronized void hold(String friend, long maxNanos, long nanos) {
        reanchor(nanos);
        holds.put(friend, nanos + maxNanos);
    }

    /** That friend connected. Returns true if the clock was waiting for them. */
    public synchronized boolean release(String friend, long nanos) {
        if (!holds.containsKey(friend)) return false;
        reanchor(nanos);
        holds.remove(friend);
        return true;
    }

    /** Names whose hold just timed out, so the game can say so. */
    public synchronized List<String> expireHolds(long nanos) {
        List<String> out = new ArrayList<String>();
        for (Iterator<Map.Entry<String, Long>> it = holds.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> e = it.next();
            if (nanos - e.getValue() >= 0) {
                if (out.isEmpty()) reanchor(nanos);
                out.add(e.getKey());
                it.remove();
            }
        }
        return out;
    }

    public synchronized boolean isHeld() {
        return !holds.isEmpty();
    }

    /** Running: not paused and nobody being waited for. */
    public synchronized boolean isRunning() {
        return !paused && holds.isEmpty();
    }

    public synchronized ClockState snapshot(long nanos) {
        ClockState s = new ClockState();
        s.seq = seq++;
        s.gameMillis = now(nanos);
        s.rate = rate(nanos);
        s.flags = (paused ? ClockState.PAUSED : 0) | (fast ? ClockState.FAST : 0)
                | (holds.isEmpty() ? 0 : ClockState.HELD);
        s.changedBy = changedBy;
        s.fastMult = fastMult;
        s.heldFor = holds.isEmpty() ? "" : holds.keySet().iterator().next();
        return s;
    }

    private boolean running(long nanos) {
        return started && !paused && holds.isEmpty();
    }

    /** Before the rate changes: fold the time so far into the anchor. */
    private void reanchor(long nanos) {
        if (!started) return;
        anchorMillis = now(nanos);
        anchorNanos = nanos;
    }
}
