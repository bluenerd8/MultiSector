package multisector.net;

import java.nio.ByteBuffer;

/**
 * The shared campaign clock, as the host sends it about 10 times a second
 * over UDP. A lost packet doesn't matter: the next one carries the full
 * state again.
 *
 * Time is the game's own clock timestamp (milliseconds of game time). rate
 * says how fast it runs right now, in game milliseconds per real
 * millisecond, so a client can tell where the host's clock is between packets.
 */
public final class ClockState {

    public static final int PAUSED = 1;
    public static final int FAST = 2;
    /** Held while a friend joins (see {@link SharedClock#hold}). */
    public static final int HELD = 4;
    /** Nobody is on the map, so the world waits (see {@link SharedClock#setIdle}). */
    public static final int IDLE = 8;

    public int seq;
    public long gameMillis;
    public float rate;
    public int flags;
    /** Who last paused or resumed the game (player id), or -1. */
    public int changedBy = -1;
    /** The host's fast-forward multiplier (campaignSpeedupMult). */
    public float fastMult = 1f;
    /** While {@link #HELD}: the name of the friend everyone is waiting for. */
    public String heldFor = "";
    /** While {@link #FAST}: who is fast-forwarding (player id), or -1. */
    public int fastBy = -1;

    public boolean isPaused() { return (flags & PAUSED) != 0; }
    public boolean isFast() { return (flags & FAST) != 0; }
    public boolean isHeld() { return (flags & HELD) != 0; }
    public boolean isIdle() { return (flags & IDLE) != 0; }
    /** Not moving at all: paused, held, or waiting for someone to be on the map. */
    public boolean isStopped() { return (flags & (PAUSED | HELD | IDLE)) != 0; }

    public int size() {
        return 4 + 8 + 4 + 1 + 4 + 4 + Protocol.stringSize(heldFor) + 4;
    }

    public void write(ByteBuffer bb) {
        bb.putInt(seq).putLong(gameMillis).putFloat(rate).put((byte) flags).putInt(changedBy).putFloat(fastMult);
        Protocol.putString(bb, heldFor);
        bb.putInt(fastBy);
    }

    public static ClockState read(ByteBuffer bb) {
        ClockState s = new ClockState();
        s.seq = bb.getInt();
        s.gameMillis = bb.getLong();
        s.rate = bb.getFloat();
        s.flags = bb.get() & 0xFF;
        s.changedBy = bb.getInt();
        s.fastMult = bb.getFloat();
        s.heldFor = Protocol.getString(bb);
        s.fastBy = bb.remaining() >= 4 ? bb.getInt() : -1;
        return s;
    }

    /** Where this clock is at real time nowNanos, given it arrived at arrivedNanos. */
    public long extrapolate(long arrivedNanos, long nowNanos, float extraMs) {
        float realMs = (nowNanos - arrivedNanos) / 1e6f + extraMs;
        // A clock packet older than this is stale; don't run far ahead on a guess.
        realMs = Math.max(0f, Math.min(realMs, 2000f));
        return gameMillis + (long) (rate * realMs);
    }

    /** A received state plus when it arrived (System.nanoTime). */
    public static final class Received {
        public final ClockState state;
        public final long arrivedNanos;

        public Received(ClockState state, long arrivedNanos) {
            this.state = state;
            this.arrivedNanos = arrivedNanos;
        }
    }
}
