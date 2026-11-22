package multisector.ui;

import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.input.InputEventAPI;

import java.util.List;

/**
 * Campaign hotkeys: the Multiplayer window (F8 by default) and showing or
 * hiding the players panel (F7 by default).
 */
public class MultiplayerHotkey implements CampaignInputListener {

    public int getListenerInputPriority() {
        return 0;
    }

    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        for (InputEventAPI e : events) {
            if (e.isConsumed() || !e.isKeyDownEvent() || e.isRepeat()) continue;
            if (e.isCtrlDown() || e.isAltDown()) continue; // leave modified combos to other mods
            if (e.getEventValue() == MultiSectorConfig.getHotkey()) {
                e.consume();
                MultiplayerDialog.open();
                return;
            }
            if (e.getEventValue() == MultiSectorConfig.getHudHotkey()) {
                e.consume();
                PlayersHud.toggle();
                return;
            }
        }
    }

    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {
    }

    public void processCampaignInputPostCore(List<InputEventAPI> events) {
    }
}
