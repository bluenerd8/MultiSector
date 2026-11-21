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
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't read " + PATH + ", using defaults: " + e);
        }
    }

    public static int getHotkey() { return hotkey; }
    public static String getHotkeyName() { return hotkeyName; }
    public static int getPort() { return port; }
    public static boolean isShowHintOnLoad() { return showHintOnLoad; }
}
