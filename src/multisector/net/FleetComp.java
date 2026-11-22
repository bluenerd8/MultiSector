package multisector.net;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which ships a player's fleet has: hull ids, flagship first. Sent over TCP
 * only when it changes. The 10-a-second position packets carry just its
 * hash, so a puppet can tell when its ships are out of date.
 */
public final class FleetComp {

    /** More ships than this are left off the puppet. */
    public static final int MAX_SHIPS = 100;

    public final int hash;
    public final List<String> hulls;

    public FleetComp(List<String> hulls) {
        List<String> h = new ArrayList<String>(hulls.subList(0, Math.min(hulls.size(), MAX_SHIPS)));
        this.hulls = Collections.unmodifiableList(h);
        this.hash = h.hashCode();
    }

    public int size() {
        int n = 4 + 2;
        for (String s : hulls) n += Protocol.stringSize(s);
        return n;
    }

    public void write(ByteBuffer bb) {
        bb.putInt(hash).putShort((short) hulls.size());
        for (String s : hulls) Protocol.putString(bb, s);
    }

    public static FleetComp read(ByteBuffer bb) {
        bb.getInt(); // hash: recomputed, so a bad sender can't make it disagree with the list
        int n = bb.getShort();
        if (n < 0 || n > MAX_SHIPS) throw new IllegalArgumentException("bad ship count " + n);
        List<String> h = new ArrayList<String>(n);
        for (int i = 0; i < n; i++) h.add(Protocol.getString(bb));
        return new FleetComp(h);
    }
}
