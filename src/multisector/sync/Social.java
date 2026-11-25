package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.util.Misc;
import multisector.net.Session;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Players talking to everyone: chat, map pings, and the check that
 * everyone's mods match the host's.
 *
 * <ul>
 * <li><b>Chat:</b> a line to everyone (the chat window, Enter by default).
 *     It shows in the game's message list in the sender's colour, in a
 *     battle too, and the chat window keeps the last lines.</li>
 * <li><b>Pings:</b> Alt+click on the map marks a spot for everyone for 30
 *     seconds, in the pinger's colour. Players elsewhere are told where.</li>
 * <li><b>Mods:</b> on joining, a friend asks for the host's enabled mods and
 *     their versions, and is told of any that differ.</li>
 * </ul>
 *
 * Over {@link Session#CHANNEL_SOCIAL}; the host relays. Transient.
 */
public final class Social implements EveryFrameScript {

    private static final Logger log = Global.getLogger(Social.class);
    private static final long PING_NANOS = 30_000_000_000L;
    private static final int KEEP_LINES = 60;
    private static final int MAX_CHARS = 300;

    /** One chat line. player is -2 for a line from the game itself. */
    public static final class Line {
        public final int player;
        public final String from;
        public final String text;
        public final long at;

        Line(int player, String from, String text) {
            this.player = player;
            this.from = from;
            this.text = text;
            this.at = System.currentTimeMillis();
        }
    }

    /** A spot someone marked on the map. */
    public static final class Ping {
        public final int player;
        public final String from;
        public final String location;
        public final float x, y;
        public final long until;

        Ping(int player, String from, String location, float x, float y) {
            this.player = player;
            this.from = from;
            this.location = location;
            this.x = x;
            this.y = y;
            this.until = System.nanoTime() + PING_NANOS;
        }

        /** How far through its 30 seconds, 0 to 1. */
        public float age() {
            return 1f - Math.max(0f, Math.min(1f, (until - System.nanoTime()) / (float) PING_NANOS));
        }
    }

    private static final List<Line> lines = Collections.synchronizedList(new ArrayList<Line>());
    private static final List<Ping> pings = Collections.synchronizedList(new ArrayList<Ping>());
    private static Session.State lastState = Session.State.IDLE;
    private static boolean modsAsked;

    public boolean isDone() {
        return false;
    }

    public boolean runWhilePaused() {
        return true;
    }

    public void advance(float amount) {
        Session s = Session.get();
        Session.State st = s.getState();
        if (st != lastState) {
            lastState = st;
            modsAsked = false;
            if (st == Session.State.IDLE) pings.clear();
        }
        if (st == Session.State.CONNECTED && !modsAsked && s.getMyId() >= 0) {
            modsAsked = true;
            s.sendSocialTo(Session.hostId(), "{\"k\":\"modsreq\"}");
        }
        pump(s, null);
        synchronized (pings) {
            long now = System.nanoTime();
            for (Iterator<Ping> it = pings.iterator(); it.hasNext(); ) if (now - it.next().until > 0) it.remove();
        }
    }

    /** Handles what other players said. Also from combat, so chat shows in a battle. */
    static void pump(Session s, CombatEngineAPI engine) {
        Session.PlayerMessage m;
        for (int n = 0; n < 40 && (m = s.pollSocialMessage()) != null; n++) {
            try {
                JSONObject j = new JSONObject(m.json);
                String k = j.optString("k");
                if ("chat".equals(k)) {
                    String text = clean(j.optString("t"));
                    if (text.isEmpty()) continue;
                    add(new Line(m.from, m.fromName, text));
                    show(m.from, m.fromName, text, engine);
                } else if ("ping".equals(k)) {
                    Ping p = new Ping(m.from, m.fromName, j.optString("loc"), (float) j.optDouble("x"), (float) j.optDouble("y"));
                    pings.add(p);
                    announcePing(p, engine);
                } else if ("rep".equals(k)) {
                    PlayerAggro.standing(m.from, j); // who they're at war with, how visible they are
                } else if ("modsreq".equals(k) && s.getState() == Session.State.HOSTING) {
                    s.sendSocialTo(m.from, new JSONObject().put("k", "mods").put("m", ourMods()).toString());
                } else if ("mods".equals(k)) {
                    compareMods(j.optJSONObject("m"));
                }
            } catch (Exception e) {
                log.warn("[MultiSector] bad message from " + m.fromName + ": " + e);
            }
        }
    }

    // ================================================================ chat

    /** Says something to everyone. */
    public static void say(String text) {
        String t = clean(text);
        if (t.isEmpty()) return;
        Session s = Session.get();
        if (!s.isActive()) {
            add(new Line(-2, "", "Not in a multiplayer session: nobody to hear that."));
            return;
        }
        try {
            s.sendSocial(new JSONObject().put("k", "chat").put("t", t).toString());
        } catch (Exception e) {
            return;
        }
        add(new Line(s.getMyId(), "You", t));
    }

    /** The last lines, oldest first. */
    public static List<Line> lines() {
        synchronized (lines) {
            return new ArrayList<Line>(lines);
        }
    }

    public static int lineCount() {
        return lines.size();
    }

    private static void add(Line l) {
        synchronized (lines) {
            lines.add(l);
            while (lines.size() > KEEP_LINES) lines.remove(0);
        }
    }

    private static String clean(String text) {
        if (text == null) return "";
        String t = text.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > MAX_CHARS ? t.substring(0, MAX_CHARS) : t;
    }

    /** A chat line in the game's message list, or the battle's. */
    private static void show(int player, String name, String text, CombatEngineAPI engine) {
        Color c = colorOf(player);
        if (engine != null && engine.getCombatUI() != null) {
            engine.getCombatUI().addMessage(1, c, name + ": ", Misc.getTextColor(), text);
            return;
        }
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (ui != null) ui.addMessage(name + ": " + text, Misc.getTextColor(), name + ":", "", c, c);
    }

    /** Each player's colour, the same on every game (battle rings too). */
    public static Color colorOf(int player) {
        return player < 0 ? Misc.getGrayColor() : JointCombat.colorOf(player);
    }

    // ================================================================ pings

    /** Marks a spot for everyone (Alt+click on the map). */
    public static void ping(LocationAPI loc, float x, float y) {
        Session s = Session.get();
        if (!s.isActive() || loc == null) return;
        String id = FleetSync.locationId(loc);
        try {
            s.sendSocial(new JSONObject().put("k", "ping").put("loc", id).put("x", x).put("y", y).toString());
        } catch (Exception e) {
            return;
        }
        pings.add(new Ping(s.getMyId(), "You", id, x, y));
    }

    /** Pings still showing, newest last. */
    public static List<Ping> pings() {
        synchronized (pings) {
            return new ArrayList<Ping>(pings);
        }
    }

    private static void announcePing(Ping p, CombatEngineAPI engine) {
        String where = describe(p.location, p.x, p.y);
        CampaignFleetAPI me = Global.getSector() == null ? null : Global.getSector().getPlayerFleet();
        boolean here = me != null && me.getContainingLocation() != null
                && FleetSync.locationId(me.getContainingLocation()).equals(p.location);
        String text = here ? "pinged a spot " + nearText(p.location, p.x, p.y) + " (on your map)"
                : "pinged a spot in " + where + ". F6 lists pings, with a course there.";
        show(p.player, p.from, text, engine);
    }

    // ================================================================ where things are

    /** "Corvus", "hyperspace": a location's name for the players. */
    public static String placeName(String locationId) {
        LocationAPI loc = FleetSync.findLocation(locationId);
        if (loc == null) return locationId;
        return loc.isHyperspace() ? "hyperspace" : loc.getName();
    }

    /** "Corvus, near Jangala" or "hyperspace, near the Corvus jump point". */
    public static String describe(String locationId, float x, float y) {
        String near = nearText(locationId, x, y);
        return placeName(locationId) + (near.isEmpty() ? "" : ", " + near);
    }

    /** "near Jangala", or "" if nothing named is close. */
    public static String nearText(String locationId, float x, float y) {
        LocationAPI loc = FleetSync.findLocation(locationId);
        if (loc == null) return "";
        Vector2f at = new Vector2f(x, y);
        SectorEntityToken best = null;
        float bestD = loc.isHyperspace() ? 4000f : 6000f;
        List<SectorEntityToken> candidates = new ArrayList<SectorEntityToken>();
        for (PlanetAPI p : loc.getPlanets()) candidates.add(p);
        candidates.addAll(loc.getJumpPoints());
        for (SectorEntityToken e : candidates) {
            if (e.getName() == null || e.getName().isEmpty()) continue;
            float d = Misc.getDistance(e.getLocation(), at) - e.getRadius();
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best == null ? "" : "near " + best.getName();
    }

    /** A point to lay in a course for: that spot in that location, or null if this game doesn't have the place. */
    public static SectorEntityToken spot(String locationId, float x, float y) {
        LocationAPI loc = FleetSync.findLocation(locationId);
        return loc == null ? null : loc.createToken(x, y);
    }

    // ================================================================ mods

    private static JSONObject ourMods() throws Exception {
        JSONObject o = new JSONObject();
        for (ModSpecAPI m : Global.getSettings().getModManager().getEnabledModsCopy()) {
            o.put(m.getId(), new JSONObject().put("v", String.valueOf(m.getVersion())).put("n", m.getName()));
        }
        return o;
    }

    /** The host's mods against ours: say which differ (missing, extra, other versions). */
    private static void compareMods(JSONObject host) throws Exception {
        if (host == null) return;
        Map<String, ModSpecAPI> mine = new TreeMap<String, ModSpecAPI>();
        for (ModSpecAPI m : Global.getSettings().getModManager().getEnabledModsCopy()) mine.put(m.getId(), m);
        List<String> missing = new ArrayList<String>();
        List<String> versions = new ArrayList<String>();
        List<String> extra = new ArrayList<String>();
        Iterator<?> it = host.keys();
        while (it.hasNext()) {
            String id = (String) it.next();
            JSONObject h = host.optJSONObject(id);
            String hv = h == null ? "" : h.optString("v");
            String hn = h == null ? id : h.optString("n", id);
            ModSpecAPI m = mine.remove(id);
            if (m == null) missing.add(hn + " " + hv);
            else if (!hv.equals(String.valueOf(m.getVersion()))) versions.add(hn + " (yours " + m.getVersion() + ", host's " + hv + ")");
        }
        for (ModSpecAPI m : mine.values()) extra.add(m.getName() + " " + m.getVersion());
        if (missing.isEmpty() && versions.isEmpty() && extra.isEmpty()) {
            log.info("[MultiSector] our mods match the host's exactly");
            return;
        }
        StringBuilder b = new StringBuilder("Your mods differ from the host's.");
        if (!versions.isEmpty()) b.append(" Other versions: ").append(join(versions)).append('.');
        if (!missing.isEmpty()) b.append(" The host has, you don't: ").append(join(missing)).append('.');
        if (!extra.isEmpty()) b.append(" You have, the host doesn't: ").append(join(extra)).append('.');
        if (!versions.isEmpty() || !missing.isEmpty()) b.append(" Things may not match up; the launcher can fix the list.");
        log.warn("[MultiSector] " + b);
        Session.get().notice(b.toString());
    }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(parts.get(i));
        }
        return b.toString();
    }

    /** A save is loading: pings were about the old world. Chat stays. */
    public static void forget() {
        pings.clear();
    }
}
