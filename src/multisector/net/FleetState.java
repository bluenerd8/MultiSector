package multisector.net;

import java.nio.ByteBuffer;

/**
 * Where one player's fleet is and what they're doing, as sent about 10
 * times a second over UDP. Velocity is in world units per real second
 * (measured from position changes), so it stays right when the sender is
 * fast-forwarding.
 */
public final class FleetState {

    // What the player is doing (activity).
    public static final byte ON_MAP = 0;
    public static final byte PAUSED = 1;
    /** A market, planet or other interaction dialog; place says where. */
    public static final byte DIALOG = 2;
    /** Cargo, refit, intel and other core screens, or the Escape menu; place says which. */
    public static final byte MENU = 3;
    /** Engaging or fighting a fleet; place says which. */
    public static final byte BATTLE = 4;

    public int playerId;
    public int seq;
    /** Star system id, or {@link #HYPERSPACE}. */
    public String location = "";
    public float x, y, vx, vy, facing;
    public String name = "";
    /** Flagship hull id, used to draw the puppet fleet. */
    public String hull = "";
    /** {@link FleetComp#hash} of the fleet's ships; the list itself comes over TCP. */
    public int compHash;
    public byte activity = ON_MAP;
    /** Detail for the activity: the market, the screen, or the enemy fleet. */
    public String place = "";
    public short level;
    public int credits;

    public static final String HYPERSPACE = "hyperspace";

    public int size() {
        return 4 + 4 + Protocol.stringSize(location) + 5 * 4 + Protocol.stringSize(name) + Protocol.stringSize(hull)
                + 4 + 1 + Protocol.stringSize(place) + 2 + 4;
    }

    public void write(ByteBuffer bb) {
        bb.putInt(playerId).putInt(seq);
        Protocol.putString(bb, location);
        bb.putFloat(x).putFloat(y).putFloat(vx).putFloat(vy).putFloat(facing);
        Protocol.putString(bb, name);
        Protocol.putString(bb, hull);
        bb.putInt(compHash);
        bb.put(activity);
        Protocol.putString(bb, place);
        bb.putShort(level).putInt(credits);
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
        s.compHash = bb.getInt();
        s.activity = bb.get();
        s.place = Protocol.getString(bb);
        s.level = bb.getShort();
        s.credits = bb.getInt();
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
        s.compHash = compHash;
        s.activity = activity;
        s.place = place;
        s.level = level;
        s.credits = credits;
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
