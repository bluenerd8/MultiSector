package multisector.sync;

import com.fs.starfarer.api.PluginPick;
import com.fs.starfarer.api.campaign.BaseCampaignPlugin;
import com.fs.starfarer.api.campaign.BattleCreationPlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl;
import multisector.net.Session;

import java.util.HashMap;
import java.util.Map;

/**
 * Decides what opens when a player clicks an NPC fleet in a shared star system,
 * or another player's fleet.
 *
 * <ul>
 * <li><b>Another player's fleet:</b> hails them ({@link PlayerTalk}).</li>
 * <li><b>A fleet from the world we follow:</b> first asks its owner (the
 *     host, or the friend whose world it is) to lock it ({@link FleetLocks}).
 *     Once granted, the fleet becomes the owner's exact copy and the game's
 *     normal fleet dialog takes over: talk, fight, pursue, all as usual. If
 *     another player already has it, says who.</li>
 * <li><b>Our own fleet that another player holds:</b> says who's dealing with it.</li>
 * </ul>
 * Everything else opens as normal.
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
        CampaignFleetAPI fleet = (CampaignFleetAPI) target;
        MemoryAPI mem = fleet.getMemoryWithoutUpdate();
        Session s = Session.get();
        if (mem.getBoolean(PuppetManager.PUPPET_FLAG)) {
            // Another player: hail them, never the normal fleet encounter (that could start a fight).
            int id = PuppetManager.playerOf(fleet);
            if (!s.isActive() || id < 0 || !s.isPlayerHere(id)) {
                return pick(new Note("That player's fleet isn't answering: they may have just left."));
            }
            return pick(PlayerTalk.hail(id));
        }
        if (!s.isActive()) return null;
        if (mem.contains(FleetLocks.CLAIMED_BY)) {
            return pick(new Note(mem.getString(FleetLocks.CLAIMED_BY) + " is dealing with this fleet right now. "
                    + "Try again once they're done."));
        }
        if (!mem.getBoolean(NpcSync.MIRROR_FLAG) && !mem.getBoolean(NpcSync.DRIVEN_FLAG)) return null;
        int handle = NpcSync.handleOf(fleet);
        int owner = NpcSync.followedOwner();
        if (handle < 0 || owner < 0) {
            return pick(new Note("This fleet just left the shared world. Try again in a moment."));
        }
        return pick(new Ask(owner, handle, fleet));
    }

    /**
     * While another player's fleet has joined our battle, the battle is set
     * up as usual plus a live report for them ({@link JointBattle}). Any
     * other time we leave battle set-up to the game (and other mods).
     */
    @Override
    public PluginPick<BattleCreationPlugin> pickBattleCreationPlugin(SectorEntityToken opponent) {
        if (!(opponent instanceof CampaignFleetAPI) || !JointBattle.hasJoiners()) return null;
        return new PluginPick<BattleCreationPlugin>(new JointBattle.Creation(), PickPriority.MOD_GENERAL);
    }

    private static PluginPick<InteractionDialogPlugin> pick(InteractionDialogPlugin p) {
        return new PluginPick<InteractionDialogPlugin>(p, PickPriority.HIGHEST);
    }

    // ------------------------------------------------------------------ dialogs

    private abstract static class Base implements InteractionDialogPlugin {
        protected InteractionDialogAPI dialog;

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

        protected void leaveOnly() {
            dialog.getOptionPanel().clearOptions();
            dialog.getOptionPanel().addOption("Leave", "leave");
            dialog.setOptionOnEscape("Leave", "leave");
        }
    }

    /** Just a message and Leave. */
    private static final class Note extends Base {
        private final String text;

        Note(String text) {
            this.text = text;
        }

        public void init(InteractionDialogAPI dialog) {
            this.dialog = dialog;
            dialog.getTextPanel().addPara(text);
            leaveOnly();
        }

        public void optionSelected(String optionText, Object optionData) {
            dialog.dismiss();
        }
    }

    /** Asks the host for the fleet, then hands over to the normal fleet dialog. */
    private static final class Ask extends Base {
        private static final float TIMEOUT_SECONDS = 8f;

        private final int owner;
        private final int handle;
        private final CampaignFleetAPI fleet;
        private float waited;
        private boolean answered;
        private boolean granted;

        Ask(int owner, int handle, CampaignFleetAPI fleet) {
            this.owner = owner;
            this.handle = handle;
            this.fleet = fleet;
        }

        public void init(InteractionDialogAPI dialog) {
            this.dialog = dialog;
            dialog.getTextPanel().addPara("Your comms officer reaches " + Session.get().playerName(owner)
                    + "'s world about " + fleet.getName() + "...");
            FleetLocks.request(owner, handle);
            leaveOnly();
        }

        @Override
        public void advance(float amount) {
            if (answered) return;
            FleetLocks.Reply r = FleetLocks.takeReply(handle);
            if (r == null) {
                waited += Math.min(amount <= 0 ? 1f / 60f : amount, 0.1f);
                if (waited > TIMEOUT_SECONDS) {
                    answered = true;
                    FleetLocks.release(owner, handle); // in case their yes is still on its way
                    dialog.getTextPanel().addPara("No answer. Try again in a moment.");
                }
                return;
            }
            answered = true;
            if (!r.granted) {
                dialog.getTextPanel().addPara(r.holder.isEmpty()
                        ? "That fleet is gone from the shared world."
                        : r.holder + " is already dealing with this fleet. Try again once they're done.");
                return;
            }
            if (!FleetLocks.takeOver(owner, handle, fleet, r.fleetJson)) {
                FleetLocks.release(owner, handle);
                dialog.getTextPanel().addPara("Couldn't copy that fleet into this game. Leaving it alone.");
                return;
            }
            granted = true;
            dialog.getTextPanel().addPara("Connected. The fleet holds position while you deal with it.");
            dialog.getOptionPanel().clearOptions();
            dialog.getOptionPanel().addOption("Continue", "continue");
            dialog.getOptionPanel().addOption("Leave", "leave");
            dialog.setOptionOnEscape("Leave", "leave");
        }

        public void optionSelected(String optionText, Object optionData) {
            if ("continue".equals(optionData) && granted) {
                // The game's own fleet dialog, exactly as for any fleet. Our watcher reports the outcome.
                FleetInteractionDialogPluginImpl normal = new FleetInteractionDialogPluginImpl();
                dialog.setPlugin(normal);
                normal.init(dialog);
                return;
            }
            dialog.dismiss(); // FleetLocks sees the dialog close and lets the owner know
            if (!granted && answered) return;
            if (!answered) {
                answered = true;
                FleetLocks.release(owner, handle); // in case they say yes after we left
            }
        }
    }
}
