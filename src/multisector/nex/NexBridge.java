package multisector.nex;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import exerelin.campaign.SectorManager;
import exerelin.utilities.NexConfig;
import org.apache.log4j.Logger;

/**
 * Every use of Nexerelin's own classes, in one place. Only ever loaded
 * through {@link Nex}, and only when Nexerelin is enabled, so MultiSector
 * still loads without it.
 */
final class NexBridge {

    private static final Logger log = Global.getLogger(NexBridge.class);

    /** Nexerelin's own settings before we changed them: put back when we stop following the host. */
    private static boolean saved;
    private static boolean invasions, diplomacy, alliances, allianceOffers, agents, expeditions, strategicAI,
            colonyGrowth, satBomb, victory;

    private NexBridge() {
    }

    /**
     * A friend's game, connected: Nexerelin's world decisions are the host's. Its planners here stand down
     * (invasions and raids, diplomacy and alliances, NPC agents, colony expeditions, the strategic AI, NPC
     * colony growth, victory); what's personal stays on (vengeance fleets, your own colonies' crises,
     * punitive expeditions against you, your followers' own moves). Off again: back as Nexerelin had them.
     */
    static void followHost(boolean on) {
        if (on && !saved) {
            invasions = NexConfig.enableInvasions;
            diplomacy = NexConfig.enableDiplomacy;
            alliances = NexConfig.enableAlliances;
            allianceOffers = NexConfig.npcAllianceOffers;
            agents = NexConfig.npcAgentActivity;
            expeditions = NexConfig.enableColonyExpeditions;
            strategicAI = NexConfig.enableStrategicAI;
            colonyGrowth = NexConfig.allowNPCColonyGrowth;
            satBomb = NexConfig.allowNPCSatBomb;
            victory = NexConfig.enableVictory;
            saved = true;
            NexConfig.enableInvasions = false;
            NexConfig.enableDiplomacy = false;
            NexConfig.enableAlliances = false;
            NexConfig.npcAllianceOffers = false;
            NexConfig.npcAgentActivity = false;
            NexConfig.enableColonyExpeditions = false;
            NexConfig.enableStrategicAI = false;
            NexConfig.allowNPCColonyGrowth = false;
            NexConfig.allowNPCSatBomb = false;
            NexConfig.enableVictory = false;
            log.info("[MultiSector] Nexerelin's world decisions are the host's while we're connected: invasions, raids,"
                    + " diplomacy, alliances, agents, colony expeditions, strategic AI and NPC growth stand down here");
        } else if (!on && saved) {
            NexConfig.enableInvasions = invasions;
            NexConfig.enableDiplomacy = diplomacy;
            NexConfig.enableAlliances = alliances;
            NexConfig.npcAllianceOffers = allianceOffers;
            NexConfig.npcAgentActivity = agents;
            NexConfig.enableColonyExpeditions = expeditions;
            NexConfig.enableStrategicAI = strategicAI;
            NexConfig.allowNPCColonyGrowth = colonyGrowth;
            NexConfig.allowNPCSatBomb = satBomb;
            NexConfig.enableVictory = victory;
            saved = false;
            log.info("[MultiSector] Nexerelin runs its own world here again");
        }
    }

    /** A market changes hands the way Nexerelin does it (its own bookkeeping, intel and side effects included). */
    static void transfer(MarketAPI m, FactionAPI to, FactionAPI from, boolean capture) {
        SectorManager.transferMarket(m, to, from, false, capture, null, 0f);
    }
}
