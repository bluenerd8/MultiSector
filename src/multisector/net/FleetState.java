package multisector.net;

import java.nio.ByteBuffer;

/**
 * Where one player's fleet is, as sent about 10 times a second over UDP.
 * Velocity is in world units per real second (measured from position
 * changes), so it stays right when the sender is fast-forwarding.
 */
public final class FleetState {

    public int playerId;
    public int seq;
    /** Star system id, or {@link #HYPERSPACE}. */
    public String location = "";
    public float x, y, vx, vy, facing;
    public String name = "";
    /** Flagship hull id, used to draw the puppet fleet. */
    public String hull = "";

    public static final String HYPERSPACE = "hyperspace";

    public int size() {
        return 4 + 4 + Protocol.stringSize(location) + 5 * 4 + Protocol.stringSize(name) + Protocol.stringSize(hull);
    }

    public void write(ByteBuffer bb) {
        bb.putInt(playerId).putInt(seq);
        Protocol.putString(bb, location);
        bb.putFloat(x).putFloat(y).putFloat(vx).putFloat(vy).putFloat(facing);
        Protocol.putString(bb, name);
        Protocol.putString(bb, hull);
    }

    public static FleetState read(ByteBuffer bb) {
        FleetState s = new FleetState();
        s.playerId = bb.getInt();
        s.seq = bb.getInt();
        s.location = Protocol.getString(bb);
        s.x = bb.getFloat();
        s.y = bb.getFloat();
        s.vx = bb.getFloat();
        s.vy = bb.getFloat();
        s.facing = bb.getFloat();
        s.name = Protocol.getString(bb);
        s.hull = Protocol.getString(bb);
        return s;
    }

    public FleetState copy() {
        FleetState s = new FleetState();
        s.playerId = playerId;
        s.seq = seq;
        s.location = location;
        s.x = x;
        s.y = y;
        s.vx = vx;
        s.vy = vy;
        s.facing = facing;
        s.name = name;
        s.hull = hull;
        return s;
    }

    /** A received state plus when it arrived (System.nanoTime), for smoothing. */
    public static final class Received {
        public final FleetState state;
        public final long arrivedNanos;

        public Received(FleetState state, long arrivedNanos) {
            this.state = state;
            this.arrivedNanos = arrivedNanos;
        }
    }
}
