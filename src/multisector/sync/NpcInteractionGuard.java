package multisector.sync;

import com.fs.starfarer.api.PluginPick;
import com.fs.starfarer.api.campaign.BaseCampaignPlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;

import java.util.HashMap;
import java.util.Map;

/**
 * On a friend's game, NPC fleets that follow the host (see {@link NpcSync})
 * open a short note instead of the usual talk/fight dialog. The host's game
 * owns those fleets, and it doesn't resolve a friend's battles or trades yet,
 * so anything done to them here would only happen on this copy of the world.
 *
 * Transient: registered on each load, never saved.
 */
public class NpcInteractionGuard extends BaseCampaignPlugin {

    @Override
    public String getId() {
        return "multisector_npc_guard";
    }

    @Override
    public boolean isTransient() {
        return true;
    }

    @Override
    public PluginPick<InteractionDialogPlugin> pickInteractionDialogPlugin(SectorEntityToken target) {
        if (!(target instanceof CampaignFleetAPI)) return null;
        MemoryAPI mem = target.getMemoryWithoutUpdate();
        if (!mem.getBoolean(NpcSync.MIRROR_FLAG) && !mem.getBoolean(NpcSync.DRIVEN_FLAG)) return null;
        return new PluginPick<InteractionDialogPlugin>(new Note(), PickPriority.HIGHEST);
    }

    private static final class Note implements InteractionDialogPlugin {
        private InteractionDialogAPI dialog;

        public void init(InteractionDialogAPI dialog) {
            this.dialog = dialog;
            dialog.getTextPanel().addPara("This fleet belongs to the host's world, and their game is flying it. "
                    + "Talking to or fighting NPC fleets in a system you share with the host comes in a later "
                    + "MultiSector version.");
            dialog.getOptionPanel().addOption("Leave", "leave");
            dialog.setOptionOnEscape("Leave", "leave");
        }

        public void optionSelected(String optionText, Object optionData) {
            dialog.dismiss();
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
            return new HashMap<String, MemoryAPI>();
        }
    }
}
