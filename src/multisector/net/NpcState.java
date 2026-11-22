package multisector.net;

import java.nio.ByteBuffer;

/**
 * Where one NPC fleet in the host's star system is, sent over UDP several
 * times a second. Velocity is the game's own (units per game second), so
 * friends scale it by the shared clock's speed when predicting ahead.
 */
public final class NpcState {

    public static final int SIZE = 4 + 5 * 4;

    public int handle;
    public float x, y, vx, vy, facing;

    public void write(ByteBuffer bb) {
        bb.putInt(handle).putFloat(x).putFloat(y).putFloat(vx).putFloat(vy).putFloat(facing);
    }

    public static NpcState read(ByteBuffer bb) {
        NpcState s = new NpcState();
        s.handle = bb.getInt();
        s.x = bb.getFloat();
        s.y = bb.getFloat();
        s.vx = bb.getFloat();
        s.vy = bb.getFloat();
        s.facing = bb.getFloat();
        return s;
    }

    /** A received state plus when it arrived (System.nanoTime). */
    public static final class Received {
        public final NpcState state;
        public final long arrivedNanos;

        public Received(NpcState state, long arrivedNanos) {
            this.state = state;
            this.arrivedNanos = arrivedNanos;
        }
    }
}
