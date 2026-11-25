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
        if (!(target instanceof CampaignFleetAPI)) {
            // Salvage is one player's at a time: ask the host first (ObjectLocks).
            if (Session.get().isActive() && ObjectLocks.isSalvage(target) && !ObjectLocks.holds(target)) {
                return pick(new ObjectLocks.Claim(target));
            }
            return null;
        }
        CampaignFleetAPI fleet = (CampaignFleetAPI) target;
        MemoryAPI mem = fleet.getMemoryWithoutUpdate();
        Session s = Session.get();
        if (mem.getBoolean(PuppetManager.PUPPET_FLAG)) {
            // Another player: hail them, never the normal fleet encounter (that could start a fight).
            int id = PuppetManager.playerOf(fleet);
            if (!s.isActive() || id < 0 || !s.isPlayerHere(id)) {
                return pick(new Note("That player's fleet isn't answering: they may have just left."));
            }
            // In a battle: join it (once the fighting starts) rather than hail.
            InteractionDialogPlugin join = JointBattle.joinDialogFor(id);
            return pick(join != null ? join : new PlayerChoice(id));
        }
        if (!s.isActive()) return null;
        if (mem.contains(FleetLocks.CLAIMED_BY)) {
            // Our fleet, and another player is fighting or talking to it: offer to join them.
            int holder = FleetLocks.holderIdOf(fleet);
            String name = mem.getString(FleetLocks.CLAIMED_BY);
            if (holder >= 0) return pick(new Busy(holder, name, fleet.getName()));
            return pick(new Note(name + " is dealing with this fleet right now. Try again once they're done."));
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
     * Only our view of a friend's battle ({@link JointBattle}). Real battles
     * are set up by the game (and other mods); joining them happens in
     * combat ({@link JointCombatHook}).
     */
    @Override
    public PluginPick<BattleCreationPlugin> pickBattleCreationPlugin(SectorEntityToken opponent) {
        BattleCreationPlugin view = JointBattle.viewFor(opponent);
        if (view != null) return new PluginPick<BattleCreationPlugin>(view, PickPriority.HIGHEST);
        return null;
    }

    /** Nexerelin replaces the game's fleet dialog with its own (invasions, diplomacy, its battle rules). */
    private static final String NEX_ID = "nexerelin";
    private static final String NEX_FLEET_DIALOG = "exerelin.campaign.battle.NexFleetInteractionDialogPluginImpl";

    /**
     * The fleet encounter dialog this game would open for a fleet: the
     * game's own, or Nexerelin's when it's enabled (Nexerelin picks its own
     * for every fleet, and expects it). Loaded by name, so MultiSector
     * doesn't need Nexerelin to run.
     */
    @SuppressWarnings("deprecation")
    static InteractionDialogPlugin fleetDialog() {
        try {
            if (com.fs.starfarer.api.Global.getSettings().getModManager().isModEnabled(NEX_ID)) {
                Class<?> c = com.fs.starfarer.api.Global.getSettings().getScriptClassLoader().loadClass(NEX_FLEET_DIALOG);
                Object o = c.newInstance();
                if (o instanceof InteractionDialogPlugin) return (InteractionDialogPlugin) o;
            }
        } catch (Throwable t) {
            com.fs.starfarer.api.Global.getLogger(NpcInteractionGuard.class)
                    .warn("[MultiSector] couldn't open Nexerelin's fleet dialog, using the game's: " + t);
        }
        return new FleetInteractionDialogPluginImpl();
    }

    /**
     * Losing every ship in a multiplayer world: get picked up by another
     * player, or at the nearest friendly port ({@link Respawn}).
     */
    @Override
    public PluginPick<InteractionDialogPlugin> pickRespawnPlugin() {
        if (!Respawn.wanted()) return null;
        return new PluginPick<InteractionDialogPlugin>(new Respawn(), PickPriority.HIGHEST);
    }

    /** An NPC fleet of another player's world caught us: the same claim as flying into it, then the encounter. */
    static InteractionDialogPlugin engagedBy(int owner, int handle, CampaignFleetAPI fleet) {
        Ask a = new Ask(owner, handle, fleet);
        a.engaged = true;
        return a;
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

    /** Another player's fleet: hail them, or escort them (follow without any dialog on their side). */
    private static final class PlayerChoice extends Base {
        private final int id;

        PlayerChoice(int id) {
            this.id = id;
        }

        public void init(InteractionDialogAPI dialog) {
            this.dialog = dialog;
            String name = Session.get().playerName(id);
            dialog.getTextPanel().addPara("Your fleet closes on " + name + "'s.");
            dialog.getOptionPanel().addOption("Hail " + name, "hail");
            if (Escort.isEscorting(id)) {
                dialog.getOptionPanel().addOption("Stop escorting " + name, "unescort");
            } else {
                dialog.getOptionPanel().addOption("Escort " + name + " (follow them closely)", "escort");
            }
            dialog.getOptionPanel().addOption("Leave", "leave");
            dialog.setOptionOnEscape("Leave", "leave");
        }

        public void optionSelected(String optionText, Object optionData) {
            if ("hail".equals(optionData)) {
                InteractionDialogPlugin hail = PlayerTalk.hail(id);
                dialog.setPlugin(hail);
                hail.init(dialog);
                return;
            }
            if ("escort".equals(optionData)) Escort.start(id);
            if ("unescort".equals(optionData)) Escort.stop("you stopped");
            dialog.dismiss();
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

    /** Another player has this fleet: say so, and offer to join their battle. */
    private static final class Busy extends Base {
        private final int holder;
        private final String name;
        private final String enemy;

        Busy(int holder, String name, String enemy) {
            this.holder = holder;
            this.name = name;
            this.enemy = enemy;
        }

        public void init(InteractionDialogAPI dialog) {
            this.dialog = dialog;
            dialog.getTextPanel().addPara(name + " is dealing with " + enemy + " right now.");
            dialog.getOptionPanel().clearOptions();
            dialog.getOptionPanel().addOption("Join their battle", "join");
            dialog.getOptionPanel().addOption("Leave", "leave");
            dialog.setOptionOnEscape("Leave", "leave");
        }

        public void optionSelected(String optionText, Object optionData) {
            if ("join".equals(optionData)) {
                JointBattle.offerToJoin(dialog, holder, name, enemy);
                return;
            }
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
        private int holderId = -1;
        private String holderName = "";

        /** It came after us (PlayerAggro), rather than we after it. */
        private boolean engaged;

        Ask(int owner, int handle, CampaignFleetAPI fleet) {
            this.owner = owner;
            this.handle = handle;
            this.fleet = fleet;
        }

        public void init(InteractionDialogAPI dialog) {
            this.dialog = dialog;
            if (engaged) dialog.getTextPanel().addPara(fleet.getName() + " has caught up with your fleet!");
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
                        : r.holder + " is already dealing with this fleet.");
                if (r.holderId >= 0 && r.holderId != Session.get().getMyId()) {
                    holderId = r.holderId;
                    holderName = r.holder;
                    dialog.getOptionPanel().clearOptions();
                    dialog.getOptionPanel().addOption("Join their battle", "join");
                    dialog.getOptionPanel().addOption("Leave", "leave");
                    dialog.setOptionOnEscape("Leave", "leave");
                }
                return;
            }
            if (!FleetLocks.takeOver(owner, handle, fleet, r.fleetJson)) {
                FleetLocks.release(owner, handle);
                dialog.getTextPanel().addPara("Couldn't copy that fleet into this game. Leaving it alone.");
                return;
            }
            granted = true;
            int inBattle = r.battleJson.isEmpty() ? 0 : FleetLocks.takeOverBattle(owner, handle, fleet, r.battleJson);
            dialog.getTextPanel().addPara(inBattle > 0
                    ? "Connected. The battle (" + inBattle + " fleets) holds while you decide which side to join."
                    : "Connected. The fleet holds position while you deal with it.");
            dialog.getOptionPanel().clearOptions();
            dialog.getOptionPanel().addOption("Continue", "continue");
            dialog.getOptionPanel().addOption("Leave", "leave");
            dialog.setOptionOnEscape("Leave", "leave");
        }

        public void optionSelected(String optionText, Object optionData) {
            if ("join".equals(optionData) && holderId >= 0) {
                JointBattle.offerToJoin(dialog, holderId, holderName, fleet.getName());
                return;
            }
            if ("continue".equals(optionData) && granted) {
                // The fleet dialog this game uses for any fleet (Nexerelin's, if it's on). Our watcher reports the outcome.
                InteractionDialogPlugin normal = fleetDialog();
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
