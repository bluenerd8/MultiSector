package multisector.sync;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import multisector.net.Session;

import java.util.List;

/**
 * In every combat (registered in {@code data/config/settings.json}): when
 * it's a campaign battle of ours during a multiplayer session, runs
 * {@link JointCombat.Leader} so other players can join it once it's under
 * way. Not in simulations, missions, or our own view of a friend's battle.
 * The battle's set-up is left entirely to the game and other mods.
 */
public final class JointCombatHook extends BaseEveryFrameCombatPlugin {

    private CombatEngineAPI engine;
    private JointCombat.Leader leader;
    private boolean decided;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null) return;
        // A battle in the campaign (ours, or our view of a friend's): our world is still open.
        if (engine.isInCampaign() || engine.isInCampaignSim() || JointCombat.isViewing()) Session.get().gameAlive();
        if (!decided) {
            decided = true; // plugins are all set up by the first frame, our view's included
            if (leads()) {
                leader = new JointCombat.Leader();
                leader.init(engine);
            }
        }
        if (leader != null) leader.advance(amount, events);
    }

    @Override
    public void renderInWorldCoords(ViewportAPI viewport) {
        if (leader != null) leader.renderInWorldCoords(viewport);
    }

    private boolean leads() {
        try {
            if (!Session.get().isActive()) return false;
            if (engine.isSimulation() || engine.isMission() || !engine.isInCampaign() || engine.isInCampaignSim()) return false;
            if (JointCombat.isViewing()) return false; // our view of a friend's battle: theirs to lead
            CampaignFleetAPI me = Global.getSector() == null ? null : Global.getSector().getPlayerFleet();
            return me != null && me.getBattle() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
