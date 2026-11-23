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
import java.util.List;

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

    private static boolean warned;

    private ClockAccess() {
    }

    static void set(long gameMillis) {
        set(gameMillis, false);
    }

    /** quiet: a small routine step (a friend's fast-forward), not worth a log line. */
    static void set(long gameMillis, boolean quiet) {
        CampaignClockAPI c = Global.getSector().getClock();
        long before = c.getTimestamp();
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
        long jumped = gameMillis - before;
        if (Math.abs(jumped) >= ORBIT_MIN_MILLIS) {
            float days = jumped / 86_400_000f;
            advanceOrbits(days * c.getSecondsPerDay(), quiet);
            Docking.reattach(); // a fleet docked at a planet moves with it
        }
    }

    /** Turns every planet, station, moon and asteroid orbit by this much game time (seconds). */
    private static void advanceOrbits(float seconds, boolean quiet) {
        Logger log = Global.getLogger(ClockAccess.class);
        long t0 = System.nanoTime();
        List<LocationAPI> all = new ArrayList<LocationAPI>(Global.getSector().getStarSystems());
        all.add(Global.getSector().getHyperspace());
        int moved = 0;
        int failed = 0;
        for (LocationAPI loc : all) {
            for (SectorEntityToken e : loc.getAllEntities()) {
                // Fleets follow their own orders, and the fleet sync handles them.
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
        }
        if (!quiet || failed > 0) log.info(String.format(java.util.Locale.ROOT, "[MultiSector] moved %d orbits by %.2f days in %d ms%s",
                moved, seconds / Global.getSector().getClock().getSecondsPerDay(), (System.nanoTime() - t0) / 1_000_000,
                failed > 0 ? " (" + failed + " skipped)" : ""));
    }
}
