package multisector.sync;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.OrbitAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.campaign.CampaignClock;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sets the campaign date to an exact timestamp, and moves planets and
 * stations along their orbits to match.
 *
 * The API only has set(cycle, month, day). But getCal() hands out the
 * clock's own calendar, and the game's CampaignClock (not obfuscated)
 * copies the calendar into its timestamp on advance(), so setting the
 * calendar and advancing by zero moves the clock to any moment.
 *
 * Orbits don't follow the date by themselves: each frame the game turns
 * every orbit by that frame's time. Every copy of the world starts from the
 * same angles in the shared save, so turning every orbit by the same time
 * the date jumped keeps planets in the same place on every player's game.
 */
final class ClockAccess {

    /** Jumps smaller than this aren't worth a pass over every orbit (planets move a hair). */
    private static final long ORBIT_MIN_MILLIS = 3600L * 1000L;

    /** Following the host's fast-forward: settle the rest of the sector's orbits after this much (one game day). */
    private static final float SETTLE_DAYS = 1f;

    private static boolean warned;
    /**
     * Orbit time (game seconds) every location is owed while we follow the
     * host's fast-forward in small steps, and how much of it the places
     * we've been have had already. Only the place we're in turns every step;
     * the rest is settled in one pass now and then (a pass over every orbit
     * in a big sector takes several milliseconds).
     */
    private static float owed;
    private static final Map<LocationAPI, Float> paid = new IdentityHashMap<LocationAPI, Float>();

    private ClockAccess() {
    }

    static void set(long gameMillis) {
        set(gameMillis, false);
    }

    /** quiet: a small routine step, not worth a log line. */
    static void set(long gameMillis, boolean quiet) {
        settle(true); // what small steps still owe first, so every orbit ends up in step
        CampaignClockAPI c = Global.getSector().getClock();
        long before = c.getTimestamp();
        setClock(c, gameMillis);
        long jumped = gameMillis - before;
        if (Math.abs(jumped) >= ORBIT_MIN_MILLIS) {
            float days = jumped / 86_400_000f;
            advanceOrbits(days * c.getSecondsPerDay(), quiet);
            Docking.reattach(); // a fleet docked at a planet moves with it
        }
    }

    /**
     * The host is fast-forwarding and we can't: our date follows theirs in
     * a small step every frame. Planets and stations where we are turn with
     * it at once, so what we see moves smoothly at the host's speed; the
     * rest of the sector is settled now and then, and wherever we go next
     * is brought up to date as we arrive.
     */
    static void follow(long gameMillis) {
        CampaignClockAPI c = Global.getSector().getClock();
        long before = c.getTimestamp();
        long jumped = gameMillis - before;
        if (jumped <= 0) return;
        setClock(c, gameMillis);
        float seconds = jumped / 86_400_000f * c.getSecondsPerDay();
        owed += seconds;
        LocationAPI here = here();
        if (here != null) payUp(here);
        Docking.reattach();
        if (owed >= SETTLE_DAYS * c.getSecondsPerDay()) settle(true);
    }

    /** Wherever we are has had all the orbit time it's owed (called as we arrive somewhere, and each step). */
    static void payUp(LocationAPI loc) {
        if (owed <= 0f || loc == null) return;
        Float had = paid.get(loc);
        float due = owed - (had == null ? 0f : had);
        if (due > 0.0001f) turnOrbits(loc, due);
        paid.put(loc, owed);
    }

    /** Every orbit gets what it's still owed from following in small steps. */
    static void settle(boolean quiet) {
        if (owed <= 0f) return;
        long t0 = System.nanoTime();
        int moved = 0;
        List<LocationAPI> all = new ArrayList<LocationAPI>(Global.getSector().getStarSystems());
        all.add(Global.getSector().getHyperspace());
        for (LocationAPI loc : all) {
            Float had = paid.get(loc);
            float due = owed - (had == null ? 0f : had);
            if (due > 0.0001f) moved += turnOrbits(loc, due);
        }
        if (!quiet) {
            Global.getLogger(ClockAccess.class).info(String.format(java.util.Locale.ROOT,
                    "[MultiSector] settled %d orbits by %.2f days in %d ms", moved,
                    owed / Global.getSector().getClock().getSecondsPerDay(), (System.nanoTime() - t0) / 1_000_000));
        }
        owed = 0f;
        paid.clear();
    }

    /** A save is loading: nothing is owed in the new world. */
    static void forget() {
        owed = 0f;
        paid.clear();
    }

    private static LocationAPI here() {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        return me == null ? null : me.getContainingLocation();
    }

    private static void setClock(CampaignClockAPI c, long gameMillis) {
        c.getCal().setTimeInMillis(gameMillis);
        try {
            ((CampaignClock) c).advance(0f);
        } catch (Throwable t) {
            // The next unpaused frame advances the clock, which updates the timestamp anyway.
            if (!warned) {
                warned = true;
                Global.getLogger(ClockAccess.class).warn("[MultiSector] couldn't refresh the clock directly: " + t);
            }
        }
    }

    /** Turns every planet, station, moon and asteroid orbit by this much game time (seconds). */
    private static void advanceOrbits(float seconds, boolean quiet) {
        Logger log = Global.getLogger(ClockAccess.class);
        long t0 = System.nanoTime();
        List<LocationAPI> all = new ArrayList<LocationAPI>(Global.getSector().getStarSystems());
        all.add(Global.getSector().getHyperspace());
        int moved = 0;
        for (LocationAPI loc : all) moved += turnOrbits(loc, seconds);
        if (!quiet || failed > 0) log.info(String.format(java.util.Locale.ROOT, "[MultiSector] moved %d orbits by %.2f days in %d ms%s",
                moved, seconds / Global.getSector().getClock().getSecondsPerDay(), (System.nanoTime() - t0) / 1_000_000,
                failed > 0 ? " (" + failed + " skipped so far)" : ""));
    }

    /** Orbits some mod's code won't turn this way, skipped (counted for the log). */
    private static int failed;

    /** One location's orbits (fleets follow their own orders, and the fleet sync handles them). */
    private static int turnOrbits(LocationAPI loc, float seconds) {
        int moved = 0;
        for (SectorEntityToken e : loc.getAllEntities()) {
            if (e instanceof CampaignFleetAPI) continue;
            OrbitAPI o = e.getOrbit();
            if (o == null) continue;
            try {
                o.advance(seconds);
                moved++;
            } catch (RuntimeException ex) {
                failed++; // an orbit type from some mod that doesn't like this; skip it
            }
        }
        return moved;
    }
}
