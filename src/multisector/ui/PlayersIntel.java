package multisector.ui;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import multisector.net.FleetState;
import multisector.net.Session;
import multisector.sync.PuppetManager;
import multisector.sync.Social;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The other players and their pings on the sector map, as intel: each
 * player's icon (their colour and shape) sits on their fleet, wherever they
 * are, and each ping on its spot for its 30 seconds. They're listed under
 * "Players" in the intel screen, which can lay in a course to them.
 *
 * Never saved: all of it comes off before every save (and goes back on next
 * frame), so a save never needs MultiSector to load.
 */
public final class PlayersIntel implements EveryFrameScript {

    static final String TAG = "Players";
    private static final Map<Integer, PlayerEntry> players = new HashMap<Integer, PlayerEntry>();
    private static final Map<Social.Ping, PingEntry> pings = new IdentityHashMap<Social.Ping, PingEntry>();
    private float timer;

    /** Icons are loaded once at startup, so the map can show them. */
    public static void loadIcons() {
        for (String pod : new String[]{"salvage_pod", "salvage_pod_glow", "salvage_pod_icon"}) {
            try {
                Global.getSettings().loadTexture("graphics/multisector/" + pod + ".png"); // the battle's wreckage
            } catch (Exception e) {
                Global.getLogger(PlayersIntel.class).warn("[MultiSector] couldn't load the salvage pod's look", e);
            }
        }
        for (int shape = 0; shape < multisector.sync.Marks.SHAPE_NAMES.length; shape++) {
            for (int color = 0; color < multisector.sync.Marks.COLORS.length; color++) {
                try {
                    Global.getSettings().loadTexture(icon("player", shape, color));
                    Global.getSettings().loadTexture(icon("ping", shape, color));
                } catch (Exception e) {
                    Global.getLogger(PlayersIntel.class).warn("[MultiSector] couldn't load a map icon", e);
                    return;
                }
            }
        }
    }

    static String icon(String kind, int shape, int color) {
        return "graphics/multisector/" + kind + "_" + shape + "_" + color + ".png";
    }

    /** Their icon in their chosen colour and shape. */
    static String playerIcon(int player) {
        return icon("player", multisector.sync.Marks.shapeIndex(player), multisector.sync.Marks.colorIndex(player));
    }

    static String pingIcon(int player) {
        return icon("ping", multisector.sync.Marks.shapeIndex(player), multisector.sync.Marks.colorIndex(player));
    }

    public boolean isDone() {
        return false;
    }

    public boolean runWhilePaused() {
        return true;
    }

    public void advance(float amount) {
        timer -= amount <= 0f ? 1f / 60f : amount;
        if (timer > 0f) return;
        timer = 0.5f;
        try {
            update();
        } catch (RuntimeException e) {
            Global.getLogger(PlayersIntel.class).warn("[MultiSector] players on the map failed this frame", e);
        }
    }

    private static void update() {
        Session s = Session.get();
        if (!s.isActive()) {
            removeAll();
            return;
        }
        Set<Integer> here = new HashSet<Integer>();
        for (Session.Player p : s.getPlayers()) {
            if (p.id == s.getMyId()) continue;
            here.add(p.id);
            PlayerEntry e = players.get(p.id);
            if (e == null) {
                e = new PlayerEntry(p.id, p.name);
                players.put(p.id, e);
                Global.getSector().getIntelManager().addIntel(e, true);
            }
            e.name = p.name;
        }
        for (Iterator<Map.Entry<Integer, PlayerEntry>> it = players.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, PlayerEntry> e = it.next();
            if (here.contains(e.getKey())) continue;
            Global.getSector().getIntelManager().removeIntel(e.getValue());
            it.remove();
        }
        List<Social.Ping> live = Social.pings();
        Set<Social.Ping> liveSet = java.util.Collections.newSetFromMap(new IdentityHashMap<Social.Ping, Boolean>());
        liveSet.addAll(live);
        for (Social.Ping pg : live) {
            if (pings.containsKey(pg)) continue;
            SectorEntityToken spot = Social.spot(pg.location, pg.x, pg.y);
            if (spot == null) continue;
            PingEntry e = new PingEntry(pg, spot);
            pings.put(pg, e);
            Global.getSector().getIntelManager().addIntel(e, true);
        }
        for (Iterator<Map.Entry<Social.Ping, PingEntry>> it = pings.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Social.Ping, PingEntry> e = it.next();
            if (liveSet.contains(e.getKey())) continue;
            Global.getSector().getIntelManager().removeIntel(e.getValue());
            it.remove();
        }
    }

    /** Before every save, and when the session ends: none of it in a save. */
    public static void removeAll() {
        try {
            for (PlayerEntry e : new ArrayList<PlayerEntry>(players.values())) Global.getSector().getIntelManager().removeIntel(e);
            for (PingEntry e : new ArrayList<PingEntry>(pings.values())) Global.getSector().getIntelManager().removeIntel(e);
        } catch (RuntimeException ignored) {
            // the sector is going away
        }
        players.clear();
        pings.clear();
    }

    /** A save is loading: the entries belonged to the old sector. */
    public static void forget() {
        players.clear();
        pings.clear();
    }

    private abstract static class Entry extends BaseIntelPlugin {
        @Override
        public Set<String> getIntelTags(SectorMapAPI map) {
            Set<String> tags = new HashSet<String>();
            tags.add(TAG);
            return tags;
        }

        @Override
        public FactionAPI getFactionForUIColors() {
            return Global.getSector().getPlayerFaction();
        }

        @Override
        public boolean hasSmallDescription() {
            return true;
        }

        @Override
        public boolean shouldRemoveIntel() {
            return false;
        }
    }

    /** Another player: on their fleet, wherever they are. */
    private static final class PlayerEntry extends Entry {
        final int player;
        String name;

        PlayerEntry(int player, String name) {
            this.player = player;
            this.name = name;
            setImportant(true); // on the map without picking a filter
        }

        @Override
        protected String getName() {
            return name + " (" + PlayersHud.shapeName(player) + ")";
        }

        @Override
        public String getIcon() {
            return playerIcon(player);
        }

        private String where() {
            FleetState.Received r = Session.get().getRemoteFleets().get(player);
            return r == null || r.state == null ? "somewhere (no position yet)" : Social.describe(r.state.location, r.state.x, r.state.y);
        }

        @Override
        public void createIntelInfo(TooltipMakerAPI info, IntelInfoPlugin.ListInfoMode mode) {
            info.addPara(getName(), Social.colorOf(player), 0f);
            info.addPara(where(), Misc.getGrayColor(), 3f);
        }

        @Override
        public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
            info.addPara("%s, another player in this world.", 0f, Social.colorOf(player), name);
            info.addPara("Where: " + where() + ".", 10f);
            info.addPara("Their mark on the map: a " + PlayersHud.shapeName(player) + " in their colour. "
                    + "F6 lists every player, with a course to them or an escort.", Misc.getGrayColor(), 10f);
        }

        @Override
        public SectorEntityToken getMapLocation(SectorMapAPI map) {
            CampaignFleetAPI f = PuppetManager.fleetOf(player);
            return f;
        }

        @Override
        public String getSortString() {
            return "A " + name;
        }
    }

    /** A pinged spot, for its 30 seconds. */
    private static final class PingEntry extends Entry {
        final Social.Ping ping;
        final SectorEntityToken spot;

        PingEntry(Social.Ping ping, SectorEntityToken spot) {
            this.ping = ping;
            this.spot = spot;
            setImportant(true);
        }

        @Override
        protected String getName() {
            return ("You".equals(ping.from) ? "Your" : ping.from + "'s") + " ping";
        }

        @Override
        public String getIcon() {
            return pingIcon(ping.player);
        }

        @Override
        public void createIntelInfo(TooltipMakerAPI info, IntelInfoPlugin.ListInfoMode mode) {
            info.addPara(getName(), Social.colorOf(ping.player), 0f);
            info.addPara(Social.describe(ping.location, ping.x, ping.y), Misc.getGrayColor(), 3f);
        }

        @Override
        public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
            info.addPara("%s marked this spot: " + Social.describe(ping.location, ping.x, ping.y) + ".", 0f,
                    Social.colorOf(ping.player), ping.from);
        }

        @Override
        public SectorEntityToken getMapLocation(SectorMapAPI map) {
            return spot;
        }

        @Override
        public String getSortString() {
            return "B";
        }
    }
}
