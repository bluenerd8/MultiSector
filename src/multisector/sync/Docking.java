package multisector.sync;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;

/**
 * Keeps a player's fleet with the planet or station they're docked at.
 *
 * While you're in a planet's dialog your game is paused, but the shared
 * clock isn't, so on everyone else's game the planet keeps orbiting. Two
 * fixes:
 * <ul>
 * <li>Your position packets say what you're docked at and where you are
 *     relative to it, so other games draw your fleet riding along with it.</li>
 * <li>When you close the dialog, your date catches up and the planet's orbit
 *     is turned forward ({@link ClockAccess}). Your fleet is then put back at
 *     the same spot relative to the planet, instead of left in empty space.</li>
 * </ul>
 * Only for things that move with time (planets, stations, anything in
 * orbit). A dialog with a moving fleet doesn't count.
 */
final class Docking {

    /** After the dialog closes, the catch-up happens on the next map frame; this is plenty. */
    private static final long KEEP_NANOS = 5_000_000_000L;

    private static SectorEntityToken last;
    private static float dx, dy;
    private static long seenNanos;

    private Docking() {
    }

    /** What the local player is docked at right now, or null. */
    static SectorEntityToken current() {
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        InteractionDialogAPI dialog = ui == null ? null : ui.getCurrentInteractionDialog();
        SectorEntityToken t = dialog == null ? null : dialog.getInteractionTarget();
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (t == null || me == null || t == me) return null;
        if (t instanceof CampaignFleetAPI && !((CampaignFleetAPI) t).isStationMode()) return null;
        if (t.getContainingLocation() != me.getContainingLocation()) return null;
        return t;
    }

    /** Every frame: remember where we are relative to what we're docked at. */
    static void track() {
        SectorEntityToken t = current();
        if (t == null) return;
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        last = t;
        dx = me.getLocation().x - t.getLocation().x;
        dy = me.getLocation().y - t.getLocation().y;
        seenNanos = System.nanoTime();
    }

    /** After orbits were turned forward: put the fleet back beside what it was docked at. */
    static void reattach() {
        SectorEntityToken t = last;
        if (t == null || System.nanoTime() - seenNanos > KEEP_NANOS) return;
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (me == null || t.getContainingLocation() != me.getContainingLocation()) return;
        me.setLocation(t.getLocation().x + dx, t.getLocation().y + dy);
    }

    /** A save is loading: the remembered entity belonged to the old world. */
    static void forget() {
        last = null;
    }
}
