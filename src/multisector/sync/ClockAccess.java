package multisector.sync;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.campaign.CampaignClock;

/**
 * Sets the campaign date to an exact timestamp.
 *
 * The API only has set(cycle, month, day). But getCal() hands out the
 * clock's own calendar, and the game's CampaignClock (not obfuscated)
 * copies the calendar into its timestamp on advance(), so setting the
 * calendar and advancing by zero moves the clock to any moment.
 */
final class ClockAccess {

    private static boolean warned;

    private ClockAccess() {
    }

    static void set(long gameMillis) {
        CampaignClockAPI c = Global.getSector().getClock();
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
}
