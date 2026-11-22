package multisector.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides, on the host, whose game each player's surroundings follow.
 *
 * <p>Every game makes up its own NPC fleets around its own player. Where
 * players are together, one of their games has to be the real one, and the
 * others show its fleets instead of their own. That's the "owner".
 *
 * <ul>
 * <li><b>Star systems:</b> the owner is whoever has been in the system the
 *     longest, and stays the owner until they leave. So arriving in a system
 *     a friend is exploring shows you their world, and theirs doesn't change
 *     under them. Ties go to the lower id (the host is 0).</li>
 * <li><b>Hyperspace:</b> players near each other form a group around the
 *     one who has been in hyperspace the longest. A player joins a group
 *     within {@link #JOIN} units of its owner and leaves it beyond
 *     {@link #LEAVE}, so walking along the edge doesn't flip back and forth.</li>
 * </ul>
 *
 * Not thread-safe: the host's sync thread is its only user.
 */
public final class ZonePlanner {

    /** Hyperspace: come this close to an owner to share their world (2000 units is a light-year). */
    public static final float JOIN = 4000f;
    /** Hyperspace: stop sharing beyond this. */
    public static final float LEAVE = 5500f;

    private final Map<Integer, String> lastLocation = new HashMap<Integer, String>();
    private final Map<Integer, Long> arrived = new HashMap<Integer, Long>();
    private Map<Integer, Integer> follows = Collections.emptyMap();

    /**
     * The new plan, from every player's latest fleet state (players with no
     * recent state are left out, so they follow themselves). Returns player
     * id to owner id, for every player given.
     */
    public Map<Integer, Integer> plan(List<FleetState> players, long now) {
        Map<Integer, FleetState> byId = new HashMap<Integer, FleetState>();
        for (FleetState s : players) {
            if (s.location == null || s.location.isEmpty()) continue;
            byId.put(s.playerId, s);
            if (!s.location.equals(lastLocation.get(s.playerId))) {
                lastLocation.put(s.playerId, s.location);
                arrived.put(s.playerId, now);
            }
        }
        lastLocation.keySet().retainAll(byId.keySet());
        arrived.keySet().retainAll(byId.keySet());

        Map<String, List<FleetState>> byLocation = new HashMap<String, List<FleetState>>();
        for (FleetState s : byId.values()) {
            List<FleetState> l = byLocation.get(s.location);
            if (l == null) byLocation.put(s.location, l = new ArrayList<FleetState>());
            l.add(s);
        }
        Map<Integer, Integer> next = new HashMap<Integer, Integer>();
        for (Map.Entry<String, List<FleetState>> e : byLocation.entrySet()) {
            List<FleetState> here = e.getValue();
            Collections.sort(here, firstArrived());
            if (FleetState.HYPERSPACE.equals(e.getKey())) planHyperspace(here, next);
            else planSystem(here, next);
        }
        follows = Collections.unmodifiableMap(next);
        return follows;
    }

    /** One owner per system: the previous one while they're still here, else whoever came first. */
    private void planSystem(List<FleetState> here, Map<Integer, Integer> next) {
        Set<Integer> ids = new HashSet<Integer>();
        for (FleetState s : here) ids.add(s.playerId);
        Integer owner = null;
        for (FleetState s : here) { // in arrival order
            Integer prev = follows.get(s.playerId);
            if (prev != null && ids.contains(prev) && prev.equals(follows.get(prev))) {
                owner = prev;
                break;
            }
        }
        if (owner == null) owner = here.get(0).playerId;
        for (FleetState s : here) next.put(s.playerId, owner);
    }

    private void planHyperspace(List<FleetState> here, Map<Integer, Integer> next) {
        Map<Integer, FleetState> byId = new HashMap<Integer, FleetState>();
        for (FleetState s : here) byId.put(s.playerId, s);

        // Keep following a previous owner who is still an owner here and still close enough.
        Map<Integer, Integer> tentative = new HashMap<Integer, Integer>();
        List<FleetState> candidates = new ArrayList<FleetState>();
        for (FleetState s : here) {
            Integer o = follows.get(s.playerId);
            FleetState os = o == null ? null : byId.get(o);
            if (os != null && o != s.playerId && o.equals(follows.get(o)) && dist(s, os) < LEAVE) {
                tentative.put(s.playerId, o);
            } else {
                candidates.add(s);
            }
        }
        // Owners, in arrival order: each joins an earlier owner it's close to, or stays one.
        List<FleetState> owners = new ArrayList<FleetState>();
        for (FleetState c : candidates) {
            FleetState near = nearest(owners, c, JOIN);
            if (near != null) {
                next.put(c.playerId, near.playerId);
            } else {
                owners.add(c);
                next.put(c.playerId, c.playerId);
            }
        }
        // Earlier followers: stay with their owner, or with whoever their owner now follows if close enough.
        for (FleetState s : here) {
            Integer o = tentative.get(s.playerId);
            if (o == null) continue;
            int top = next.get(o);
            if (top == o || dist(s, byId.get(top)) < LEAVE) {
                next.put(s.playerId, top);
                continue;
            }
            FleetState near = nearest(owners, s, JOIN);
            if (near != null) {
                next.put(s.playerId, near.playerId);
            } else {
                owners.add(s);
                next.put(s.playerId, s.playerId);
            }
        }
    }

    private static FleetState nearest(List<FleetState> owners, FleetState s, float within) {
        FleetState best = null;
        float bestD = within;
        for (FleetState o : owners) {
            float d = dist(o, s);
            if (d < bestD) {
                bestD = d;
                best = o;
            }
        }
        return best;
    }

    private static float dist(FleetState a, FleetState b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private Comparator<FleetState> firstArrived() {
        return new Comparator<FleetState>() {
            public int compare(FleetState a, FleetState b) {
                long ta = arrived.get(a.playerId);
                long tb = arrived.get(b.playerId);
                if (ta != tb) return ta < tb ? -1 : 1;
                return a.playerId - b.playerId;
            }
        };
    }

    public void reset() {
        lastLocation.clear();
        arrived.clear();
        follows = Collections.emptyMap();
    }
}
