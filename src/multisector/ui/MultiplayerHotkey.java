package multisector.ui;

import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.input.InputEventAPI;

import java.util.List;

/** Opens the Multiplayer window when the configured hotkey (F8 by default) is pressed in the campaign. */
public class MultiplayerHotkey implements CampaignInputListener {

    public int getListenerInputPriority() {
        return 0;
    }

    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        for (InputEventAPI e : events) {
            if (e.isConsumed() || !e.isKeyDownEvent() || e.isRepeat()) continue;
            if (e.getEventValue() != MultiSectorConfig.getHotkey()) continue;
            if (e.isCtrlDown() || e.isAltDown()) continue; // leave modified combos to other mods
            e.consume();
            MultiplayerDialog.open();
            return;
        }
    }

    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {
    }

    public void processCampaignInputPostCore(List<InputEventAPI> events) {
    }
}
