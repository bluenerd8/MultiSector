package multisector.net;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * On the host: who is salvaging which object in space right now (a
 * derelict, debris field, cargo pods, a cache), so two players can't both
 * take what's in it. First come, first served; a claim lapses when its
 * holder lets go, leaves, or after a while (a dialog left open).
 *
 * Kept by the host's network thread, so claims are answered even while the
 * host's own game is in a battle or a menu.
 */
public final class ObjectLockTable {

    /** A claim nobody let go of (a dialog left open, a crash) lapses after this. */
    static final long LAPSE_NANOS = 15L * 60L * 1_000_000_000L;

    private static final class Claim {
        final int holder;
        final String name;
        long since;

        Claim(int holder, String name, long since) {
            this.holder = holder;
            this.name = name;
            this.since = since;
        }
    }

    private final Map<String, Claim> claims = new HashMap<String, Claim>();

    /** Claims the object for this player. Returns null if they have it now, else the holder's name. */
    public synchronized String claim(String key, int player, String name, long nanos) {
        Claim c = claims.get(key);
        if (c != null && c.holder != player && nanos - c.since < LAPSE_NANOS) return c.name;
        if (c != null && c.holder == player) {
            c.since = nanos; // claimed again: still theirs
            return null;
        }
        claims.put(key, new Claim(player, name, nanos));
        return null;
    }

    /** That player is done with the object. */
    public synchronized void release(String key, int player) {
        Claim c = claims.get(key);
        if (c != null && c.holder == player) claims.remove(key);
    }

    /** That player left: everything they held is free. */
    public synchronized void releaseAll(int player) {
        for (Iterator<Claim> it = claims.values().iterator(); it.hasNext(); ) {
            if (it.next().holder == player) it.remove();
        }
    }

    public synchronized void clear() {
        claims.clear();
    }

    /** Who holds it (a player id), or -1. */
    public synchronized int holder(String key) {
        Claim c = claims.get(key);
        return c == null ? -1 : c.holder;
    }
}
