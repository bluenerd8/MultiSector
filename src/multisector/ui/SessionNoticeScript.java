package multisector.ui;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.util.Misc;
import multisector.net.Session;

/**
 * Runs every frame in the campaign (transient, not saved). Shows session
 * events like "Alice joined" as campaign messages, and the "press F8" hint
 * once after a save loads.
 */
public class SessionNoticeScript implements EveryFrameScript {

    private boolean hintShown;

    public SessionNoticeScript(boolean showHint) {
        this.hintShown = !showHint;
    }

    public boolean isDone() {
        return false;
    }

    public boolean runWhilePaused() {
        return true;
    }

    public void advance(float amount) {
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (ui == null) return;
        if (!hintShown) {
            hintShown = true;
            String key = MultiSectorConfig.getHotkeyName();
            ui.addMessage("MultiSector: press " + key + " or use the Multiplayer button on the ability bar to host or join.",
                    Misc.getTextColor(), key, "Multiplayer", Misc.getHighlightColor(), Misc.getHighlightColor());
        }
        String n;
        while ((n = Session.get().pollNotice()) != null) {
            ui.addMessage("MultiSector: " + n, Misc.getHighlightColor());
        }
    }
}
