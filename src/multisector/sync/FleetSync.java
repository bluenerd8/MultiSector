package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import multisector.net.FleetState;
import multisector.net.Session;

/**
 * Every frame while connected: records where this player's fleet is (sent
 * about 10 times a second), and moves the other players' puppet fleets.
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
        PuppetManager.update(session.getRemoteFleets());
    }

    private FleetState capture() {
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        if (fleet == null || fleet.getContainingLocation() == null) return null;
        FleetState s = new FleetState();
        s.location = locationId(fleet.getContainingLocation());
        s.x = fleet.getLocation().x;
        s.y = fleet.getLocation().y;
        s.facing = fleet.getFacing();
        FleetMemberAPI flag = fleet.getFlagship();
        s.hull = flag == null ? "" : flag.getHullSpec().getHullId();

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

    /** Star system id, or "hyperspace". Same in every copy of the shared save. */
    static String locationId(LocationAPI loc) {
        if (loc.isHyperspace()) return FleetState.HYPERSPACE;
        return loc.getId();
    }

    static LocationAPI findLocation(String id) {
        if (FleetState.HYPERSPACE.equals(id)) return Global.getSector().getHyperspace();
        for (StarSystemAPI s : Global.getSector().getStarSystems()) {
            if (s.getId().equals(id)) return s;
        }
        return null;
    }
}
