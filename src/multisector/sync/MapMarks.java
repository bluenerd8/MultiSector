package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
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
 * A player inside a star system also gets one on the hyperspace map, next
 * to that system's star, so you can see from hyperspace which system each
 * player is in. Markers are seen at any distance, whatever your sensors.
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
    /** Players inside a star system: their marker beside it in hyperspace. */
    private static final Map<Integer, Mark> systems = new HashMap<Integer, Mark>();
    private static final String SEEN_ID = "multisector_mark";
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
        Set<Integer> inSystem = new HashSet<Integer>();
        LocationAPI hyper = Global.getSector().getHyperspace();
        for (Session.Player p : s.getPlayers()) {
            if (p.id == s.getMyId()) continue;
            CampaignFleetAPI f = PuppetManager.fleetOf(p.id);
            if (f == null || f.getContainingLocation() == null) continue;
            seen.add(p.id);
            String type = "multisector_mark_" + Marks.shapeIndex(p.id) + "_" + Marks.colorIndex(p.id);
            Mark m = players.get(p.id);
            if (m == null) players.put(p.id, m = new Mark());
            LocationAPI loc = f.getContainingLocation();
            place(m, type, loc, "multisector_mark_" + p.id, p.name, f.getLocation().x, f.getLocation().y);
            // In a star system: their mark beside its star on the hyperspace map too.
            if (loc instanceof StarSystemAPI && hyper != null && loc != hyper) {
                StarSystemAPI sys = (StarSystemAPI) loc;
                inSystem.add(p.id);
                Mark sm = systems.get(p.id);
                if (sm == null) systems.put(p.id, sm = new Mark());
                // Around the star, each player at their own angle, so two in one system don't cover each other.
                double a = Math.toRadians(45 + 72 * (p.id % 5));
                float r = 450f;
                place(sm, type, hyper, "multisector_sysmark_" + p.id, p.name + " (in " + sys.getBaseName() + ")",
                        sys.getLocation().x + (float) Math.cos(a) * r, sys.getLocation().y + (float) Math.sin(a) * r);
            }
        }
        for (Iterator<Map.Entry<Integer, Mark>> it = players.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Mark> e = it.next();
            if (seen.contains(e.getKey())) continue;
            remove(e.getValue());
            it.remove();
        }
        for (Iterator<Map.Entry<Integer, Mark>> it = systems.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Mark> e = it.next();
            if (inSystem.contains(e.getKey())) continue;
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
        }
        if (!name.equals(m.entity.getName())) m.entity.setName(name);
        // Seen from anywhere, whatever our sensors: it's only a marker. The profile alone doesn't do it (the
        // game works it out again); the detected range is what it reads, as for the players' fleets.
        m.entity.setSensorProfile(1_000_000f);
        m.entity.getDetectedRangeMod().modifyFlat(SEEN_ID, 1_000_000f);
        // The game also caps every sensor range at 5000 (2000 in hyperspace) unless the entity extends that cap:
        // without this, marks vanished past those distances whatever their detected range.
        m.entity.setExtendedDetectedAtRange(1_000_000f);
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
            for (Mark m : new ArrayList<Mark>(systems.values())) remove(m);
            for (Mark m : new ArrayList<Mark>(pings.values())) remove(m);
        } catch (RuntimeException ignored) {
            // the sector is going away
        }
        players.clear();
        systems.clear();
        pings.clear();
    }

    /** A save is loading: the markers belonged to the old sector. */
    public static void forget() {
        players.clear();
        systems.clear();
        pings.clear();
        failed = false;
    }
}
