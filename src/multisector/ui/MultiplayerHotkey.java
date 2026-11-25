package multisector.ui;

import com.fs.starfarer.api.Global;
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
            if (e.isConsumed()) continue;
            // Alt+click on the map: a ping everyone sees. (Not a move order.)
            if (e.isLMBDownEvent() && e.isAltDown() && multisector.sync.ClockSync.isMapView()
                    && multisector.net.Session.get().isActive()) {
                com.fs.starfarer.api.combat.ViewportAPI vp = Global.getSector().getViewport();
                com.fs.starfarer.api.campaign.CampaignFleetAPI me = Global.getSector().getPlayerFleet();
                if (vp != null && me != null) {
                    float x = vp.convertScreenXToWorldX(Global.getSettings().getMouseX());
                    float y = vp.convertScreenYToWorldY(Global.getSettings().getMouseY());
                    multisector.sync.Social.ping(me.getContainingLocation(), x, y);
                    e.consume();
                    continue;
                }
            }
            if (!e.isKeyDownEvent() || e.isRepeat()) continue;
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
            if (e.getEventValue() == MultiSectorConfig.getPlayersHotkey() && multisector.net.Session.get().isActive()) {
                e.consume();
                PlayersWindow.open();
                return;
            }
            if (e.getEventValue() == MultiSectorConfig.getChatHotkey() && multisector.net.Session.get().isActive()
                    && multisector.sync.ClockSync.isMapView()) {
                e.consume();
                ChatWindow.open();
                return;
            }
        }
    }

    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {
    }

    public void processCampaignInputPostCore(List<InputEventAPI> events) {
    }
}
