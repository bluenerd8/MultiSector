package multisector.ui;

import com.fs.starfarer.api.Global;
import multisector.net.NetConfig;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.lwjgl.input.Keyboard;

/** Settings from data/config/multisector.json, loaded once at startup. */
public final class MultiSectorConfig {

    public static final String MOD_ID = "multisector";
    private static final String PATH = "data/config/multisector.json";

    private static int hotkey = Keyboard.KEY_F8;
    private static String hotkeyName = "F8";
    private static int port = NetConfig.DEFAULT_PORT;
    private static boolean showHintOnLoad = true;
    private static boolean holdWhileJoining = true;
    private static boolean syncNpcFleets = true;
    private static boolean moveFleetsDuringHostDialogs = true;
    private static boolean showPlayersHud = true;
    private static int hudHotkey = Keyboard.KEY_F7;
    private static String hudHotkeyName = "F7";
    private static String hudCorner = "topRight";
    private static float hudOffsetX = 0f;
    private static float hudOffsetY = 0f;

    private MultiSectorConfig() {
    }

    public static void load() {
        Logger log = Global.getLogger(MultiSectorConfig.class);
        try {
            JSONObject json = Global.getSettings().loadJSON(PATH, MOD_ID);
            String key = json.optString("hotkey", "F8").trim().toUpperCase();
            int idx = Keyboard.getKeyIndex(key);
            if (idx != Keyboard.KEY_NONE) {
                hotkey = idx;
                hotkeyName = key;
            } else {
                log.warn("[MultiSector] unknown hotkey \"" + key + "\" in " + PATH + ", using F8");
            }
            int p = json.optInt("port", NetConfig.DEFAULT_PORT);
            port = p > 0 && p < 65536 ? p : NetConfig.DEFAULT_PORT;
            showHintOnLoad = json.optBoolean("showHintOnLoad", true);
            holdWhileJoining = json.optBoolean("holdWhileJoining", true);

            syncNpcFleets = json.optBoolean("syncNpcFleets", true);
            moveFleetsDuringHostDialogs = json.optBoolean("moveFleetsDuringHostDialogs", true);
            showPlayersHud = json.optBoolean("showPlayersHud", true);
            String hk = json.optString("hudHotkey", "F7").trim().toUpperCase();
            int hidx = Keyboard.getKeyIndex(hk);
            if (hidx != Keyboard.KEY_NONE) {
                hudHotkey = hidx;
                hudHotkeyName = hk;
            } else {
                log.warn("[MultiSector] unknown hudHotkey \"" + hk + "\" in " + PATH + ", using F7");
            }
            String corner = json.optString("hudCorner", "topRight").trim();
            if (corner.equals("topLeft") || corner.equals("topRight") || corner.equals("bottomLeft")
                    || corner.equals("bottomRight")) {
                hudCorner = corner;
            } else {
                log.warn("[MultiSector] hudCorner should be topLeft, topRight, bottomLeft or bottomRight; using topRight");
            }
            hudOffsetX = (float) json.optDouble("hudOffsetX", 0);
            hudOffsetY = (float) json.optDouble("hudOffsetY", 0);
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't read " + PATH + ", using defaults: " + e);
        }
    }

    public static int getHotkey() { return hotkey; }
    public static String getHotkeyName() { return hotkeyName; }
    public static int getPort() { return port; }
    public static boolean isShowHintOnLoad() { return showHintOnLoad; }
    /** Host: pause the shared world from a friend's download until they connect. */
    public static boolean isHoldWhileJoining() { return holdWhileJoining; }
    /** In a star system shared with the host, show the host's NPC fleets instead of this game's own. */
    public static boolean isSyncNpcFleets() { return syncNpcFleets; }
    /** Host: keep NPC fleets in the host's system moving for friends while the host is in a dialog. */
    public static boolean isMoveFleetsDuringHostDialogs() { return moveFleetsDuringHostDialogs; }
    /** Whether the players panel starts shown. */
    public static boolean isShowPlayersHud() { return showPlayersHud; }
    public static int getHudHotkey() { return hudHotkey; }
    public static String getHudHotkeyName() { return hudHotkeyName; }
    /** topLeft, topRight, bottomLeft or bottomRight. */
    public static String getHudCorner() { return hudCorner; }
    /** Nudges the panel right by this many pixels. */
    public static float getHudOffsetX() { return hudOffsetX; }
    /** Nudges the panel down by this many pixels. */
    public static float getHudOffsetY() { return hudOffsetY; }
}
