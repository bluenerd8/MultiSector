package multisector;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.PersistentUIDataAPI.AbilitySlotAPI;
import com.fs.starfarer.api.campaign.PersistentUIDataAPI.AbilitySlotsAPI;
import multisector.net.AddressFinder;
import multisector.probe.SandboxProbe;
import multisector.sync.ClockSync;
import multisector.sync.FleetSync;
import multisector.sync.HostSaver;
import multisector.sync.MarketSync;
import multisector.sync.NpcInteractionGuard;
import multisector.sync.PlayerTalk;
import multisector.sync.JointBattle;
import multisector.sync.WorldObjects;
import multisector.sync.NpcSync;
import multisector.sync.PuppetManager;
import multisector.ui.AutoConnect;
import multisector.ui.MultiSectorConfig;
import multisector.ui.MultiplayerAbility;
import multisector.ui.MultiplayerHotkey;
import multisector.ui.PlayerInfo;
import multisector.ui.PlayersHud;
import multisector.ui.SessionNoticeScript;
import org.apache.log4j.Logger;

import java.util.List;

/**
 * Mod entry point.
 *
 * At startup: runs the sandbox probe, loads the config and logs this PC's
 * addresses. On every save load: registers the hotkey, puts the Multiplayer
 * button on the ability bar (first time only), and starts the script that
 * shows join/leave messages.
 */
public class MultiSectorModPlugin extends BaseModPlugin {

    /** Set once the button has been placed in this save, so removing it from the bar sticks. */
    private static final String PLACED_FLAG = "$multisector_abilityPlaced";

    @Override
    public void onApplicationLoad() throws Exception {
        Global.getLogger(MultiSectorModPlugin.class).info("[MultiSector] version " + BuildInfo.VERSION
                + " (built " + BuildInfo.BUILT_AT + ")");
        SandboxProbe.runAll();
        MultiSectorConfig.load();
        logAddresses();
    }

    @Override
    public void onGameLoad(boolean newGame) {
        PuppetManager.forget(); // any puppets belonged to the previously loaded world
        Global.getSector().getListenerManager().addListener(new MultiplayerHotkey(), true);
        Global.getSector().addTransientScript(new SessionNoticeScript(MultiSectorConfig.isShowHintOnLoad()));
        Global.getSector().addTransientScript(new FleetSync());
        Global.getSector().addTransientScript(new ClockSync());
        Global.getSector().getListenerManager().addListener(new HostSaver(), true);
        Global.getSector().getListenerManager().addListener(new PlayersHud(), true);
        PlayerInfo.forget(); // location names belonged to the previous world
        NpcSync.forget();
        Global.getSector().addTransientScript(new NpcSync());
        Global.getSector().addTransientListener(new MarketSync.TradeReporter());
        PlayerTalk.forget(); // a conversation from the previous world is over
        Global.getSector().addTransientScript(new PlayerTalk());
        WorldObjects.forget();
        Global.getSector().addTransientScript(new WorldObjects());
        JointBattle.forget();
        Global.getSector().addTransientScript(new JointBattle());
        // Transient plugins aren't saved, but unregister first in case this sector already has one.
        NpcInteractionGuard guard = new NpcInteractionGuard();
        Global.getSector().unregisterPlugin(guard.getId());
        Global.getSector().registerPlugin(guard);
        try {
            ensureAbilityOnBar();
        } catch (Exception e) {
            Global.getLogger(MultiSectorModPlugin.class).warn("[MultiSector] couldn't add the Multiplayer button", e);
        }
        try {
            AutoConnect.onGameLoad();
        } catch (Exception e) {
            Global.getLogger(MultiSectorModPlugin.class).warn("[MultiSector] auto-connect failed", e);
        }
    }

    /**
     * Nothing of ours may be written into the save: other players' puppet
     * fleets, the host's mirrored NPC fleets, and the hidden/steered state
     * of this game's own fleets. All of it is set up again next frame.
     */
    @Override
    public void beforeGameSave() {
        PuppetManager.removeAll();
        NpcSync.releaseForSave();
    }

    /** Gives the player the Multiplayer ability and puts it in the first free ability-bar slot. */
    private static void ensureAbilityOnBar() {
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        if (fleet == null) return;
        String id = MultiplayerAbility.ID;
        if (!fleet.hasAbility(id)) Global.getSector().getCharacterData().addAbility(id);

        if (Global.getSector().getMemoryWithoutUpdate().getBoolean(PLACED_FLAG)) return;
        AbilitySlotsAPI slots = Global.getSector().getUIData().getAbilitySlotsAPI();
        int curr = slots.getCurrBarIndex();
        boolean placed = false;
        try {
            // Already on some bar? Then nothing to do.
            for (int bar = 0; bar < 5 && !placed; bar++) {
                slots.setCurrBarIndex(bar);
                for (AbilitySlotAPI slot : slots.getCurrSlotsCopy()) {
                    if (id.equals(slot.getAbilityId())) {
                        placed = true;
                        break;
                    }
                }
            }
            // Otherwise the first empty slot, searching the current bar first.
            for (int i = 0; i < 5 && !placed; i++) {
                slots.setCurrBarIndex((curr + i) % 5);
                for (AbilitySlotAPI slot : slots.getCurrSlotsCopy()) {
                    if (slot.getAbilityId() == null) {
                        slot.setAbilityId(id);
                        placed = true;
                        break;
                    }
                }
            }
        } finally {
            slots.setCurrBarIndex(curr);
        }
        if (placed) Global.getSector().getMemoryWithoutUpdate().set(PLACED_FLAG, true);
    }

    private static void logAddresses() {
        Logger log = Global.getLogger(MultiSectorModPlugin.class);
        try {
            List<AddressFinder.Candidate> all = AddressFinder.find();
            for (AddressFinder.Candidate c : all) {
                log.info("[MultiSector net] address " + c);
            }
            AddressFinder.Candidate hamachi = AddressFinder.first(all, AddressFinder.Kind.HAMACHI);
            log.info("[MultiSector net] Hamachi address: " + (hamachi != null ? hamachi.ip() : "none (Hamachi off or not installed)"));
        } catch (Throwable t) {
            // NetworkInterface may be blocked by the sandbox; the probe log shows which.
            log.info("[MultiSector net] could not list addresses: " + t);
        }
    }
}
