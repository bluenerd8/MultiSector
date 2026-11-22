package multisector.sync;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.input.InputEventAPI;
import multisector.net.Session;
import multisector.ui.MultiSectorConfig;

import java.util.List;

/**
 * Host: when a friend starts downloading the world, save a fresh copy first
 * so they don't get an hours-old save. Optionally also hold the shared clock
 * until they join, so they arrive in exactly the world they downloaded.
 *
 * Runs from campaign input handling rather than an every-frame script, so
 * the save happens outside the game's own update. Input is only handled on
 * the map, so a request waits while the host is in a dialog (the launcher
 * gives up after 20 seconds and shares the last save instead).
 */
public class HostSaver implements CampaignInputListener {

    /** Longest the world waits for a joining friend. */
    private static final long HOLD_NANOS = 10L * 60L * 1_000_000_000L;

    public int getListenerInputPriority() {
        return 0;
    }

    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        Session session = Session.get();
        if (session.getState() != Session.State.HOSTING) return;
        if (!ClockSync.isMapView()) return;
        Session.SaveRequest r = session.pollSaveRequest();
        if (r == null) return;

        if (MultiSectorConfig.isHoldWhileJoining()) {
            // ClockSync pauses this game too on its next frame. Pausing here directly
            // would look like the host pressing pause, which outlasts the hold.
            session.getSharedClock().hold(r.friend, HOLD_NANOS, System.nanoTime());
            session.notice(r.friend + " is downloading the world. Paused until they join. Unpause to stop waiting.");
        } else {
            session.notice(r.friend + " is downloading the world.");
        }
        boolean saved;
        try {
            saved = Global.getSector().getCampaignUI().cmdSave();
        } catch (RuntimeException e) {
            Global.getLogger(HostSaver.class).warn("[MultiSector] fresh save for " + r.friend + " failed", e);
            saved = false;
        }
        Global.getLogger(HostSaver.class).info("[MultiSector] fresh save for " + r.friend + ": " + saved);
        r.complete(saved, saved ? "saved" : "the game couldn't save just now");
    }

    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {
    }

    public void processCampaignInputPostCore(List<InputEventAPI> events) {
    }
}
