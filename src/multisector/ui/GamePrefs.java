package multisector.ui;

import com.fs.starfarer.api.Global;
import org.json.JSONObject;

/**
 * Small choices the player makes in the game that should stick between
 * sessions (saves/common/multisector_game.json.data). Unlike
 * multisector.json, these are set from the game's own windows.
 */
public final class GamePrefs {

    private static final String FILE = "multisector_game.json";
    private static JSONObject cached;

    private GamePrefs() {
    }

    /** Joining a world again brings back your character from last time without asking. */
    public static boolean isAutoCharacter() {
        return read().optBoolean("autoCharacter", true);
    }

    public static void setAutoCharacter(boolean on) {
        write("autoCharacter", on);
    }

    /** Your mark for other players: colour and shape (indexes into Marks), or -1 for the one your id gives. */
    public static int getMarkColor() {
        return read().optInt("markColor", -1);
    }

    public static int getMarkShape() {
        return read().optInt("markShape", -1);
    }

    public static void setMark(int color, int shape) {
        JSONObject o = read();
        try {
            o.put("markColor", color);
            o.put("markShape", shape);
            Global.getSettings().writeTextFileToCommon(FILE, o.toString());
        } catch (Exception e) {
            Global.getLogger(GamePrefs.class).warn("[MultiSector] couldn't save a setting: " + e);
        }
    }

    private static JSONObject read() {
        if (cached != null) return cached;
        try {
            String text = Global.getSettings().readTextFileFromCommon(FILE);
            cached = text == null || text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception e) {
            cached = new JSONObject();
        }
        return cached;
    }

    private static void write(String key, boolean value) {
        JSONObject o = read();
        try {
            o.put(key, value);
            Global.getSettings().writeTextFileToCommon(FILE, o.toString());
        } catch (Exception e) {
            Global.getLogger(GamePrefs.class).warn("[MultiSector] couldn't save a setting: " + e);
        }
    }
}
