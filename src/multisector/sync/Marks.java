package multisector.sync;

import multisector.net.Session;
import multisector.ui.GamePrefs;
import org.json.JSONObject;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * Each player's mark: a colour and a shape. Everyone can choose their own
 * (the players window, F6); it's kept on their PC and sent to everyone, so
 * every game draws them the same: on the map, the sector map, their pings,
 * and their ships' rings in joint battles. Until someone has chosen, their
 * player number picks one.
 */
public final class Marks {

    public static final Color[] COLORS = {
            new Color(60, 200, 255), new Color(255, 90, 220), new Color(255, 170, 40), new Color(150, 110, 255),
            new Color(255, 240, 80), new Color(80, 255, 170), new Color(255, 70, 70), new Color(235, 235, 235),
            new Color(120, 230, 60), new Color(70, 110, 255)};
    public static final String[] COLOR_NAMES = {"cyan", "pink", "orange", "violet", "yellow", "mint", "red", "white",
            "green", "blue"};
    public static final String[] SHAPE_NAMES = {"double ring", "diamond", "square", "triangle", "hexagon", "star"};

    /** What other players chose: player id -> {colour, shape}. */
    private static final Map<Integer, int[]> chosen = new HashMap<Integer, int[]>();
    private static long nextSend;
    private static String sentAs = "";

    private Marks() {
    }

    public static int colorIndex(int player) {
        int[] c = mark(player);
        return c != null && c[0] >= 0 && c[0] < COLORS.length ? c[0] : Math.abs(player) % 6;
    }

    public static int shapeIndex(int player) {
        int[] c = mark(player);
        return c != null && c[1] >= 0 && c[1] < SHAPE_NAMES.length ? c[1] : Math.abs(player) % 6;
    }

    public static Color color(int player) {
        return COLORS[colorIndex(player)];
    }

    public static String shapeName(int player) {
        return SHAPE_NAMES[shapeIndex(player)];
    }

    public static String colorName(int player) {
        return COLOR_NAMES[colorIndex(player)];
    }

    private static int[] mark(int player) {
        Session s = Session.get();
        if (player == s.getMyId() && s.isActive()) {
            return new int[]{GamePrefs.getMarkColor(), GamePrefs.getMarkShape()};
        }
        return chosen.get(player);
    }

    /** Our choice: kept, and sent to everyone at once. */
    public static void choose(int color, int shape) {
        GamePrefs.setMark(color, shape);
        nextSend = 0;
    }

    /** Every frame (Social): our mark to everyone, when it changes and every half minute (for anyone new). */
    static void send(Session s) {
        if (!s.isActive() || s.getMyId() < 0) return;
        String now = GamePrefs.getMarkColor() + "|" + GamePrefs.getMarkShape() + "|" + s.getMyId();
        long t = System.nanoTime();
        if (now.equals(sentAs) && t - nextSend < 0) return;
        sentAs = now;
        nextSend = t + 30_000_000_000L;
        try {
            s.sendSocial(new JSONObject().put("k", "mark").put("c", GamePrefs.getMarkColor())
                    .put("s", GamePrefs.getMarkShape()).toString());
        } catch (Exception ignored) {
            // next time
        }
    }

    /** From Social: another player's mark. */
    static void received(int player, JSONObject j) {
        chosen.put(player, new int[]{j.optInt("c", -1), j.optInt("s", -1)});
    }

    /** The session ended: their marks went with them (ids are handed out again). */
    static void forget() {
        chosen.clear();
        sentAs = "";
    }
}
