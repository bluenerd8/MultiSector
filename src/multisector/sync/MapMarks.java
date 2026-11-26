package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import multisector.net.Session;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Other players and their pings on the system map and the hyperspace map
 * (the full-screen map), where the game shows only your own fleet: a small
 * marker that follows each player's fleet, with their icon (their colour
 * and shape) and name, and one on each ping for its 30 seconds.
 *
 * The markers are entities of our own types ({@code data/config/custom_entities.json}:
 * multisector_mark_S_C and multisector_pingmark_S_C), shown on the map only,
 * not in space (the campaign view draws its own rings). They come off before
 * every save and go back next frame, so a save never holds them.
 */
public final class MapMarks implements EveryFrameScript {

    private static final Logger log = Global.getLogger(MapMarks.class);

    private static final class Mark {
        CustomCampaignEntityAPI entity;
        String type;
    }

    private static final Map<Integer, Mark> players = new HashMap<Integer, Mark>();
    private static final Map<Social.Ping, Mark> pings = new IdentityHashMap<Social.Ping, Mark>();
    private static boolean failed;

    public boolean isDone() {
        return false;
    }

    public boolean runWhilePaused() {
        return true;
    }

    public void advance(float amount) {
        if (failed) return;
        try {
            update();
        } catch (RuntimeException e) {
            failed = true;
            log.warn("[MultiSector] map markers failed; turning them off", e);
            removeAll();
        }
    }

    private static void update() {
        Session s = Session.get();
        if (!s.isActive()) {
            removeAll();
            return;
        }
        Set<Integer> seen = new HashSet<Integer>();
        for (Session.Player p : s.getPlayers()) {
            if (p.id == s.getMyId()) continue;
            CampaignFleetAPI f = PuppetManager.fleetOf(p.id);
            if (f == null || f.getContainingLocation() == null) continue;
            seen.add(p.id);
            String type = "multisector_mark_" + Marks.shapeIndex(p.id) + "_" + Marks.colorIndex(p.id);
            Mark m = players.get(p.id);
            if (m == null) players.put(p.id, m = new Mark());
            place(m, type, f.getContainingLocation(), "multisector_mark_" + p.id, p.name, f.getLocation().x, f.getLocation().y);
        }
        for (Iterator<Map.Entry<Integer, Mark>> it = players.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Mark> e = it.next();
            if (seen.contains(e.getKey())) continue;
            remove(e.getValue());
            it.remove();
        }
        List<Social.Ping> live = Social.pings();
        Set<Social.Ping> liveSet = java.util.Collections.newSetFromMap(new IdentityHashMap<Social.Ping, Boolean>());
        liveSet.addAll(live);
        for (Social.Ping pg : live) {
            if (pings.containsKey(pg)) continue;
            LocationAPI loc = FleetSync.findLocation(pg.location);
            if (loc == null) continue;
            Mark m = new Mark();
            String type = "multisector_pingmark_" + Marks.shapeIndex(pg.player) + "_" + Marks.colorIndex(pg.player);
            String name = ("You".equals(pg.from) ? "your" : pg.from + "'s") + " ping";
            place(m, type, loc, null, name, pg.x, pg.y);
            pings.put(pg, m);
        }
        for (Iterator<Map.Entry<Social.Ping, Mark>> it = pings.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Social.Ping, Mark> e = it.next();
            if (liveSet.contains(e.getKey())) continue;
            remove(e.getValue());
            it.remove();
        }
    }

    /** The marker where it should be: made (again) if its place or look changed, else just moved. */
    private static void place(Mark m, String type, LocationAPI loc, String id, String name, float x, float y) {
        if (m.entity == null || !type.equals(m.type) || m.entity.getContainingLocation() != loc) {
            remove(m);
            m.entity = loc.addCustomEntity(id, name, type, Factions.INDEPENDENT);
            m.type = type;
            m.entity.setDiscoverable(null);
            m.entity.setSensorProfile(1_000_000f); // seen from anywhere: it's only a marker
        }
        m.entity.setLocation(x, y);
    }

    private static void remove(Mark m) {
        if (m.entity != null && m.entity.getContainingLocation() != null) {
            m.entity.getContainingLocation().removeEntity(m.entity);
        }
        m.entity = null;
    }

    /** Before every save, and when the session ends: none of them in a save. */
    public static void removeAll() {
        try {
            for (Mark m : new ArrayList<Mark>(players.values())) remove(m);
            for (Mark m : new ArrayList<Mark>(pings.values())) remove(m);
        } catch (RuntimeException ignored) {
            // the sector is going away
        }
        players.clear();
        pings.clear();
    }

    /** A save is loading: the markers belonged to the old sector. */
    public static void forget() {
        players.clear();
        pings.clear();
        failed = false;
    }
}
