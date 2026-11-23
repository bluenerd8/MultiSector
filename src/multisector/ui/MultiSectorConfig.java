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
    private static boolean jointBattleView = true;
    private static boolean jointBattleDeployAll = true;
    private static boolean jointBattleChecks = true;
    private static boolean holdWhileEveryoneBusy = true;
    private static boolean friendsCanFastForward = true;
    private static boolean waitForHost = true;
    private static boolean endWhenHostLeaves = true;
    private static boolean saveWithHost = true;
    private static boolean waitForAllPlayers = false;
    private static boolean shareSurveys = true;
    private static boolean keepMovingInMenus = true;
    private static boolean joinNpcBattles = true;
    private static boolean shareRaids = true;
    private static boolean shareSystemBounties = true;
    private static boolean shareFactionRelations = true;
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
            jointBattleView = json.optBoolean("jointBattleView", true);
            jointBattleDeployAll = json.optBoolean("jointBattleDeployAll", true);
            jointBattleChecks = json.optBoolean("jointBattleChecks", true);
            holdWhileEveryoneBusy = json.optBoolean("holdWhileEveryoneBusy", true);
            friendsCanFastForward = json.optBoolean("friendsCanFastForward", true);
            waitForHost = json.optBoolean("waitForHost", true);
            endWhenHostLeaves = json.optBoolean("endWhenHostLeaves", true);
            saveWithHost = json.optBoolean("saveWithHost", true);
            waitForAllPlayers = json.optBoolean("waitForAllPlayers", false);
            shareSurveys = json.optBoolean("shareSurveys", true);
            keepMovingInMenus = json.optBoolean("keepMovingInMenus", true);
            joinNpcBattles = json.optBoolean("joinNpcBattles", true);
            shareRaids = json.optBoolean("shareRaids", true);
            shareSystemBounties = json.optBoolean("shareSystemBounties", true);
            shareFactionRelations = json.optBoolean("shareFactionRelations", true);
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
    /** Joining someone's battle opens a live view of it, where you pilot one of your ships. */
    public static boolean isJointBattleView() { return jointBattleView; }
    /** The leader's game deploys every combat-ready ship of a player who joined (not just what fits). */
    public static boolean isJointBattleDeployAll() { return jointBattleDeployAll; }
    /** Joint battles write comparison tables of every ship to starsector.log, on both games. */
    public static boolean isJointBattleChecks() { return jointBattleChecks; }
    /** Host: the shared world waits while nobody is on the map (everyone in a battle, dialog or menu). */
    public static boolean isHoldWhileEveryoneBusy() { return holdWhileEveryoneBusy; }
    /** Host: a friend's fast-forward speeds the world up for everyone, the host's game included. */
    public static boolean isFriendsCanFastForward() { return friendsCanFastForward; }
    /** Friends: a shared world loaded before the host is up waits, paused, and keeps trying to join. */
    public static boolean isWaitForHost() { return waitForHost; }
    /** Friends: when the host ends the session, your character is saved and you go back to the main menu. */
    public static boolean isEndWhenHostLeaves() { return endWhenHostLeaves; }
    /** Friends: when the host saves, your game saves too (your copy of the world and your character). */
    public static boolean isSaveWithHost() { return saveWithHost; }
    /** Host: after loading, the world waits until everyone who was playing at the last save has joined. */
    public static boolean isWaitForAllPlayers() { return waitForAllPlayers; }
    /** Planet surveys, explored ruins and similar one-off finds are shared by every player. */
    public static boolean isShareSurveys() { return shareSurveys; }
    /** Raids and bombardments on planets reach everyone's world. */
    public static boolean isShareRaids() { return shareRaids; }
    /** Faction bounties on star systems are the host's, on every player's world. */
    public static boolean isShareSystemBounties() { return shareSystemBounties; }
    /** Relations between NPC factions (wars, peace, Nexerelin diplomacy) follow the host's world. */
    public static boolean isShareFactionRelations() { return shareFactionRelations; }
    /** Your fleet keeps flying its course while the cargo, refit, character or map screen is open. */
    public static boolean isKeepMovingInMenus() { return keepMovingInMenus; }
    /** Owner: a friend can join a battle between NPC fleets in our world (it's held for them while they do). */
    public static boolean isJoinNpcBattles() { return joinNpcBattles; }
    public static int getHudHotkey() { return hudHotkey; }
    public static String getHudHotkeyName() { return hudHotkeyName; }
    /** topLeft, topRight, bottomLeft or bottomRight. */
    public static String getHudCorner() { return hudCorner; }
    /** Nudges the panel right by this many pixels. */
    public static float getHudOffsetX() { return hudOffsetX; }
    /** Nudges the panel down by this many pixels. */
    public static float getHudOffsetY() { return hudOffsetY; }
}
