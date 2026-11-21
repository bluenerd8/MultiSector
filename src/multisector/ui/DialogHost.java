package multisector.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CustomDialogDelegate;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;

import java.util.Map;

/**
 * Shows a custom window on the campaign map. The campaign UI can only show
 * custom windows from inside an interaction dialog, so this opens an empty
 * one (text and picture hidden) to hold it.
 */
final class DialogHost {

    /** A window that needs the holding dialog, to close it when done. */
    interface Window extends CustomDialogDelegate {
        void setHolder(InteractionDialogAPI holder);
    }

    private DialogHost() {
    }

    /** Opens the window unless another dialog or menu is up. Returns true if it opened. */
    static boolean show(final Window window, final float width, final float height) {
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (ui == null || ui.isShowingDialog() || ui.isShowingMenu()) return false;
        return ui.showInteractionDialog(new InteractionDialogPlugin() {
            public void init(InteractionDialogAPI dialog) {
                dialog.hideTextPanel();
                dialog.hideVisualPanel();
                dialog.setPromptText("");
                window.setHolder(dialog);
                dialog.showCustomDialog(width, height, window);
            }

            public void optionSelected(String optionText, Object optionData) {
            }

            public void optionMousedOver(String optionText, Object optionData) {
            }

            public void advance(float amount) {
            }

            public void backFromEngagement(EngagementResultAPI battleResult) {
            }

            public Object getContext() {
                return null;
            }

            public Map<String, MemoryAPI> getMemoryMap() {
                return null;
            }
        }, Global.getSector().getPlayerFleet());
    }
}
