package multisector.ui;

import com.fs.starfarer.api.Global;
import multisector.net.NetConfig;
import multisector.net.Session;
import org.apache.log4j.Logger;
import org.json.JSONObject;

/**
 * Starts hosting or joining by itself when a save loads, following the
 * session file the launcher wrote to saves/common.
 *
 * It only acts when the loaded save is the session's save (same character
 * name) and the file is recent, so loading some other save never connects.
 */
public final class AutoConnect {

    /** Session files older than this are ignored. */
    private static final long MAX_AGE_MS = 12L * 60 * 60 * 1000;

    private AutoConnect() {
    }

    public static void onGameLoad() {
        Logger log = Global.getLogger(AutoConnect.class);
        JSONObject s;
        try {
            String text = Global.getSettings().readTextFileFromCommon(NetConfig.SESSION_FILE);
            if (text == null || text.trim().isEmpty()) return;
            s = new JSONObject(text);
        } catch (Exception e) {
            return; // no launcher session
        }

        String mode = s.optString("mode", "");
        String host = s.optString("host", "");
        int port = s.optInt("port", MultiSectorConfig.getPort());
        String name = s.optString("name", "Player");
        String character = s.optString("characterName", "");
        long age = System.currentTimeMillis() - s.optLong("createdAt", 0);

        if (age > MAX_AGE_MS) {
            log.info("[MultiSector] launcher session is " + age / 3600000 + " h old, ignoring");
            return;
        }
        String loaded = Global.getSector().getPlayerPerson().getNameString().trim();
        // Compare without spaces: the descriptor and the API can format the name slightly differently.
        if (!character.replaceAll("\\s+", "").equalsIgnoreCase(loaded.replaceAll("\\s+", ""))) {
            log.info("[MultiSector] launcher session is for \"" + character + "\", this save is \"" + loaded + "\"; not connecting");
            notice("this isn't the save from the launcher (\"" + character + "\"), so not connecting automatically.");
            return;
        }

        Session session = Session.get();
        if (mode.equals("host")) {
            if (session.getState() == Session.State.HOSTING) return;
            if (session.isActive()) session.stop();
            try {
                session.host(port, name);
                notice("Hosting on port " + port + ". Friends join from the MultiSector launcher.");
            } catch (Exception e) {
                notice("Couldn't start hosting: " + e.getMessage());
            }
        } else if (mode.equals("join") && !host.isEmpty()) {
            if (session.getState() == Session.State.CONNECTED || session.getState() == Session.State.CONNECTING) return;
            if (session.isActive()) session.stop();
            MultiplayerDialog.setLastJoinAddress(host + (port != NetConfig.DEFAULT_PORT ? ":" + port : ""));
            session.join(host, port, name);
            notice("Joining " + host + "...");
        }
    }

    /** Shown by SessionNoticeScript once the campaign UI is up. */
    private static void notice(String text) {
        Session.get().notice(text);
    }
}
