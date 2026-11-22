package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import multisector.net.FleetComp;
import multisector.net.FleetState;
import multisector.net.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every frame while connected: records where this player's fleet is and
 * which ships it has (sent about 10 times a second), and moves the other
 * players' puppet fleets.
 * Transient: added on each load, never saved.
 */
public class FleetSync implements EveryFrameScript {

    private static final float SAMPLE_INTERVAL = 0.1f;

    private float sampleTimer;
    private long lastSampleNanos;
    private String lastLocation;
    private float lastX, lastY;

    public boolean isDone() {
        return false;
    }

    /** Keep running while paused, so positions keep flowing both ways. */
    public boolean runWhilePaused() {
        return true;
    }

    public void advance(float amount) {
        Session session = Session.get();
        if (!session.isActive()) {
            PuppetManager.removeAll();
            return;
        }
        // amount is game time (0 while paused, larger when fast-forwarding); sample on real time.
        sampleTimer += Global.getSector().isPaused() ? 1f / 60f : Math.max(amount, 1f / 120f);
        if (sampleTimer >= SAMPLE_INTERVAL) {
            sampleTimer = 0;
            FleetState s = capture();
            if (s != null) session.setLocalFleet(s);
        }
        PuppetManager.update(session);
    }

    private FleetState capture() {
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        if (fleet == null || fleet.getContainingLocation() == null) return null;
        FleetState s = new FleetState();
        s.location = locationId(fleet.getContainingLocation());
        s.x = fleet.getLocation().x;
        s.y = fleet.getLocation().y;
        s.facing = fleet.getFacing();
        activity(fleet, s);
        s.level = (short) Math.min(Short.MAX_VALUE, Global.getSector().getPlayerStats().getLevel());
        s.credits = (int) Math.min(Integer.MAX_VALUE, fleet.getCargo().getCredits().get());
        FleetMemberAPI flag = fleet.getFlagship();
        s.hull = flag == null ? "" : flag.getHullSpec().getHullId();
        FleetComp comp = composition(fleet, flag);
        s.compHash = comp.hash;
        Session.get().setLocalComposition(comp); // sent only when it changed

        // Velocity from position change per real second, so it's right at any game speed.
        long now = System.nanoTime();
        if (lastSampleNanos != 0 && s.location.equals(lastLocation)) {
            float dt = (now - lastSampleNanos) / 1e9f;
            if (dt > 0.01f) {
                s.vx = (s.x - lastX) / dt;
                s.vy = (s.y - lastY) / dt;
            }
        }
        lastSampleNanos = now;
        lastLocation = s.location;
        lastX = s.x;
        lastY = s.y;
        return s;
    }

    /**
     * What the player is doing, for the other players' HUD. A battle keeps
     * showing while it's fought: the campaign (and this script) stops during
     * combat, but the network thread keeps sending the last state.
     */
    private static void activity(CampaignFleetAPI fleet, FleetState s) {
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        InteractionDialogAPI dialog = ui == null ? null : ui.getCurrentInteractionDialog();
        SectorEntityToken target = dialog == null ? null : dialog.getInteractionTarget();
        if (fleet.getBattle() != null) {
            s.activity = FleetState.BATTLE;
            s.place = target != null && target != fleet ? target.getName() : "";
        } else if (ui != null && ui.getCurrentCoreTab() != null) {
            s.activity = FleetState.MENU;
            s.place = ui.getCurrentCoreTab().name().toLowerCase(Locale.ROOT);
        } else if (ui != null && ui.isShowingMenu()) {
            s.activity = FleetState.MENU;
            s.place = "";
        } else if (ui != null && ui.isShowingDialog()) {
            s.activity = FleetState.DIALOG;
            // Dialogs aimed at your own fleet (this mod's windows, for one) have no place.
            s.place = target != null && target != fleet ? target.getName() : "";
        } else if (Global.getSector().isPaused()) {
            s.activity = FleetState.PAUSED;
            s.place = "";
        } else {
            s.activity = FleetState.ON_MAP;
            s.place = "";
        }
        if (s.place == null) s.place = "";
        if (s.place.length() > 40) s.place = s.place.substring(0, 40);
    }

    /** The fleet's ships as hull ids, flagship first, in the fleet's own order. */
    private static FleetComp composition(CampaignFleetAPI fleet, FleetMemberAPI flag) {
        List<String> hulls = new ArrayList<String>();
        if (flag != null) hulls.add(flag.getHullSpec().getHullId());
        for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
            if (m == flag || m.isFighterWing()) continue;
            hulls.add(m.getHullSpec().getHullId());
        }
        return new FleetComp(hulls);
    }

    /** Star system id, or "hyperspace". Same in every copy of the shared save. */
    static String locationId(LocationAPI loc) {
        if (loc.isHyperspace()) return FleetState.HYPERSPACE;
        return loc.getId();
    }

    public static LocationAPI findLocation(String id) {
        if (FleetState.HYPERSPACE.equals(id)) return Global.getSector().getHyperspace();
        for (StarSystemAPI s : Global.getSector().getStarSystems()) {
            if (s.getId().equals(id)) return s;
        }
        return null;
    }
}
