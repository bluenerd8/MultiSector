package multisector.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.util.Misc;
import multisector.net.FleetComp;
import multisector.net.FleetState;
import multisector.net.Session;
import multisector.sync.FleetSync;
import multisector.sync.PuppetManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Other players' basic info, worded for the HUD panel and the Multiplayer
 * window: where they are, what they're doing, their fleet, level and credits.
 */
public final class PlayerInfo {

    /** No position for this long: they're loading, in a long battle, or gone. */
    private static final long QUIET_NANOS = 5_000_000_000L;

    public final int id;
    public final String name;
    public final boolean isHost;
    public final boolean isMe;
    public final int pingMs;
    /** True once their game sends positions; false while loading or quiet. */
    public final boolean live;
    public final String where;
    public final String doing;
    public final boolean fighting;
    /** "1.4 ly away", "nearby", or "" if unknown. */
    public final String distance;
    public final int ships;
    public final String flagship;
    public final int level;
    public final int credits;

    private PlayerInfo(Session.Player p, Session s, FleetState.Received r, long now) {
        id = p.id;
        name = p.name;
        isHost = p.isHost;
        isMe = p.id == s.getMyId();
        pingMs = p.pingMs;
        FleetState st = r == null ? null : r.state;
        live = st != null && now - r.arrivedNanos < QUIET_NANOS;
        if (st == null) {
            where = "-";
            doing = isMe ? "" : "loading";
            fighting = false;
            distance = "";
            ships = 0;
            flagship = "";
            level = 0;
            credits = 0;
            return;
        }
        where = locationName(st.location);
        String d = doingText(st);
        // A battle stops the fighter's campaign, so their packets keep saying "battle" until it's over.
        doing = live || st.activity == FleetState.BATTLE ? d : "no signal";
        fighting = st.activity == FleetState.BATTLE;
        distance = distanceText(p.id);
        FleetComp comp = s.getComposition(p.id);
        boolean compOk = comp != null && comp.hash == st.compHash;
        ships = compOk ? comp.hulls.size() : 1;
        flagship = hullName(st.hull);
        level = st.level;
        credits = st.credits;
    }

    /** Everyone in the session, host first, optionally without yourself. */
    public static List<PlayerInfo> all(boolean includeMe) {
        Session s = Session.get();
        Map<Integer, FleetState.Received> fleets = s.getRemoteFleets();
        long now = System.nanoTime();
        List<PlayerInfo> out = new ArrayList<PlayerInfo>();
        for (Session.Player p : s.getPlayers()) {
            boolean me = p.id == s.getMyId();
            if (me && !includeMe) continue;
            out.add(new PlayerInfo(p, s, me ? null : fleets.get(p.id), now));
        }
        return out;
    }

    /** "12 ships (Onslaught)" */
    public String fleetText() {
        if (flagship.isEmpty()) return "";
        return ships + (ships == 1 ? " ship" : " ships") + " (" + flagship + ")";
    }

    public String pingText() {
        if (isMe) return "";
        if (isHost) return "host";
        return pingMs >= 0 ? pingMs + " ms" : "...";
    }

    private static String doingText(FleetState st) {
        String place = st.place == null ? "" : st.place;
        switch (st.activity) {
            case FleetState.BATTLE:
                return place.isEmpty() ? "in battle" : "fighting " + place;
            case FleetState.DIALOG:
                return place.isEmpty() ? "in a dialog" : "at " + place;
            case FleetState.MENU:
                return place.isEmpty() ? "in the menu" : menuName(place);
            case FleetState.PAUSED:
                return "paused";
            default:
                float speed = (float) Math.sqrt(st.vx * st.vx + st.vy * st.vy);
                return speed > 5f ? "travelling" : "idle";
        }
    }

    private static String menuName(String tab) {
        if (tab.equals("outposts")) return "managing colonies";
        if (tab.equals("fleet")) return "in fleet screen";
        if (tab.equals("map")) return "looking at the map";
        return "in " + tab;
    }

    // Location ids map to names through a search over every star system; remember them.
    private static final Map<String, String> locationNames = new HashMap<String, String>();

    private static String locationName(String id) {
        if (FleetState.HYPERSPACE.equals(id)) return "Hyperspace";
        String cached = locationNames.get(id);
        if (cached != null) return cached;
        LocationAPI loc = FleetSync.findLocation(id);
        String n = loc == null ? id : loc instanceof StarSystemAPI ? ((StarSystemAPI) loc).getBaseName() : loc.getName();
        locationNames.put(id, n);
        return n;
    }

    /** Forget cached names: another save's systems may use the same ids. */
    public static void forget() {
        locationNames.clear();
    }

    private static String distanceText(int playerId) {
        CampaignFleetAPI them = PuppetManager.fleetOf(playerId);
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (them == null || me == null || them.getContainingLocation() == null) return "";
        if (them.getContainingLocation() == me.getContainingLocation() && !me.isInHyperspace()) return "same system";
        float ly = Misc.getDistanceLY(me, them);
        return ly < 0.1f ? "nearby" : String.format(Locale.ROOT, "%.1f ly away", ly);
    }

    private static String hullName(String hullId) {
        if (hullId == null || hullId.isEmpty()) return "";
        try {
            ShipHullSpecAPI spec = Global.getSettings().getHullSpec(hullId);
            if (spec != null) return spec.getHullName();
        } catch (RuntimeException ignored) {
            // a hull from a mod this game doesn't have
        }
        return hullId;
    }
}
