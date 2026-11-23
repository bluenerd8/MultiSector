package multisector.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomDialogDelegate;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import multisector.BuildInfo;
import multisector.character.CharacterExporter;
import multisector.net.AddressFinder;
import multisector.net.NetConfig;
import multisector.net.Session;

import java.awt.Color;
import java.util.List;
import java.util.Map;

/**
 * The Multiplayer window: host a game, join one by IP, and see who's
 * connected. Opened by the hotkey or the ability-bar button.
 *
 * The content is rebuilt whenever its layout changes (state, error, player
 * list). Ping numbers change constantly, so those update labels in place
 * instead of rebuilding, which would steal focus from the address box.
 */
public class MultiplayerDialog extends BaseCustomDialogDelegate implements DialogHost.Window {

    private static final float WIDTH = 520f;
    private static final float HEIGHT = 640f;
    private static final float BUTTON_W = 180f;
    private static final float BUTTON_H = 28f;
    private static final String PERSIST_KEY = "multisector_lastJoinAddress";

    private static final String BTN_HOST = "host";
    private static final String BTN_JOIN = "join";
    private static final String BTN_COPY = "copy";
    private static final String BTN_EXPORT = "export";
    private static final String BTN_CHOOSE = "choose";
    private static final String BTN_LET_GO = "letgo";
    private static final String BTN_MENUS = "menus";
    private static final String BTN_AUTO_CHAR = "autochar";
    private static final String BTN_FRESH = "fresh";

    /** Last address typed into Join, for this game session. Also saved per save file. */
    private static String lastJoinAddress = "";

    private CustomPanelAPI panel;
    private TooltipMakerAPI content;
    private TextFieldAPI addressField;
    private LabelAPI statusLabel;
    private LabelAPI playersLabel;
    private String layoutSignature = "";
    private String localError;
    private String localInfo;
    private String shareAddress;
    private float refreshTimer;

    private final Plugin plugin = new Plugin();

    /** Pre-fills the Join box, e.g. from the launcher session. */
    public static void setLastJoinAddress(String address) {
        lastJoinAddress = address == null ? "" : address;
    }

    /** The invisible interaction dialog hosting this window; dismissed together with it. */
    private InteractionDialogAPI host;

    /** Opens the window unless another dialog or menu is already up. */
    public static void open() {
        DialogHost.show(new MultiplayerDialog(), WIDTH, HEIGHT);
    }

    public void setHolder(InteractionDialogAPI holder) {
        this.host = holder;
    }

    // ---------------------------------------------------------- dialog hooks

    @Override
    public void createCustomDialog(CustomPanelAPI panel, CustomDialogCallback callback) {
        this.panel = panel;
        if (lastJoinAddress.isEmpty()) {
            Object saved = persistentData().get(PERSIST_KEY);
            if (saved instanceof String) lastJoinAddress = (String) saved;
        }
        build();
    }

    @Override
    public boolean hasCancelButton() {
        return false;
    }

    @Override
    public String getConfirmText() {
        return "Close";
    }

    @Override
    public void customDialogConfirm() {
        close();
    }

    /** Also called when the player presses Escape. */
    @Override
    public void customDialogCancel() {
        close();
    }

    private void close() {
        rememberAddress();
        if (host != null) host.dismiss();
    }

    @Override
    public CustomUIPanelPlugin getCustomPanelPlugin() {
        return plugin;
    }

    private class Plugin extends BaseCustomUIPanelPlugin {
        @Override
        public void buttonPressed(Object buttonId) {
            onButton(buttonId);
        }

        @Override
        public void advance(float amount) {
            refreshTimer -= amount;
            if (refreshTimer > 0) return;
            refreshTimer = 0.25f;
            if (!computeSignature().equals(layoutSignature)) {
                build();
            } else {
                if (statusLabel != null) statusLabel.setText(Session.get().getStatus() + pingSuffix());
                if (playersLabel != null) playersLabel.setText(playersText());
            }
        }
    }

    // --------------------------------------------------------------- actions

    private void onButton(Object id) {
        Session s = Session.get();
        localError = null;
        localInfo = null;
        if (BTN_HOST.equals(id)) {
            if (s.getState() == Session.State.HOSTING) {
                s.stop();
            } else if (s.getState() == Session.State.IDLE) {
                try {
                    s.host(MultiSectorConfig.getPort(), playerName());
                    multisector.sync.SessionGuard.waitForLastPlayers();
                } catch (Exception e) {
                    localError = e.getMessage();
                }
            }
        } else if (BTN_AUTO_CHAR.equals(id)) {
            GamePrefs.setAutoCharacter(!GamePrefs.isAutoCharacter());
            localInfo = GamePrefs.isAutoCharacter()
                    ? "Joining a world again brings back your character from last time."
                    : "Joining a world again asks which character to bring.";
        } else if (BTN_FRESH.equals(id)) {
            close();
            multisector.sync.SessionGuard.freshCopyNow(); // to the main menu; the launcher fetches the world
            return;
        } else if (BTN_MENUS.equals(id)) {
            multisector.sync.Escort.setKeepMoving(!multisector.sync.Escort.isKeepMoving());
            localInfo = multisector.sync.Escort.isKeepMoving()
                    ? "Your fleet keeps flying its course while you're in the cargo, refit or map screens."
                    : "Your fleet stays put while you're in the cargo, refit or map screens.";
        } else if (BTN_LET_GO.equals(id)) {
            multisector.sync.SessionGuard.letGo();
            if (s.getState() == Session.State.CONNECTING) s.stop();
            localInfo = "Stopped waiting. This world now runs on its own, without the host's.";
        } else if (BTN_JOIN.equals(id)) {
            if (s.getState() == Session.State.CONNECTING || s.getState() == Session.State.CONNECTED) {
                multisector.sync.SessionGuard.letGo(); // leaving on purpose: don't hold the world for the host
                s.stop();
            } else if (s.getState() == Session.State.IDLE) {
                String address = addressField != null ? addressField.getText().trim() : "";
                if (address.isEmpty()) {
                    localError = "Type the host's address first, e.g. 25.12.34.56";
                } else {
                    lastJoinAddress = address;
                    rememberAddress();
                    s.join(address, MultiSectorConfig.getPort(), playerName());
                }
            }
        } else if (BTN_CHOOSE.equals(id)) {
            close();
            CharacterChooser.openSoon(); // once this window has finished closing
            return;
        } else if (BTN_EXPORT.equals(id)) {
            try {
                localInfo = "Exported " + CharacterExporter.export() + ". When you load a friend's MultiSector "
                        + "world, choose \"Use my exported character\".";
            } catch (Exception e) {
                localError = "Export failed: " + e.getMessage();
            }
        } else if (BTN_COPY.equals(id) && shareAddress != null) {
            localInfo = Clipboard.copy(shareAddress)
                    ? "Copied " + shareAddress + " to the clipboard."
                    : "Couldn't access the clipboard. The address is " + shareAddress + ".";
        }
        build();
    }

    // ---------------------------------------------------------------- layout

    private void build() {
        if (content != null) {
            if (addressField != null) lastJoinAddress = addressField.getText();
            panel.removeComponent(content);
        }
        Session s = Session.get();
        Session.State st = s.getState();
        Color hl = Misc.getHighlightColor();
        Color gray = Misc.getGrayColor();
        Color bad = Misc.getNegativeHighlightColor();
        Color good = Misc.getPositiveHighlightColor();
        float pad = 10f;

        TooltipMakerAPI t = panel.createUIElement(WIDTH, HEIGHT, true);

        // Status
        t.addSectionHeading("Status", Alignment.MID, 0f);
        statusLabel = t.addPara(s.getStatus() + pingSuffix(), st == Session.State.IDLE ? gray : good, 8f);
        String error = localError != null ? localError : s.getLastError();
        if (error != null) t.addPara(error, bad, 6f);
        if (localInfo != null) t.addPara(localInfo, hl, 6f);

        // Host
        t.addSectionHeading("Host a game", Alignment.MID, 18f);
        List<AddressFinder.Candidate> addrs = AddressFinder.find();
        AddressFinder.Candidate hamachi = AddressFinder.first(addrs, AddressFinder.Kind.HAMACHI);
        AddressFinder.Candidate best = hamachi != null ? hamachi : AddressFinder.preferred(addrs);
        int port = MultiSectorConfig.getPort();
        shareAddress = best == null ? null
                : best.ip() + (port != NetConfig.DEFAULT_PORT ? ":" + port : "");
        if (hamachi != null) {
            t.addPara("Your Hamachi address: %s", 8f, hl, shareAddress);
        } else if (best != null) {
            t.addPara("No Hamachi address found. Is Hamachi switched on? Your %s address: %s", 8f, hl,
                    best.kind.label, shareAddress);
        } else {
            t.addPara("No network address found. Check your connection.", bad, 8f);
        }
        if (st == Session.State.HOSTING) {
            t.addPara("Friends type this address in their Join box. If Windows asks whether to allow Java, "
                    + "tick both Private and Public networks.", gray, 6f);
        }
        ButtonAPI host = t.addButton(st == Session.State.HOSTING ? "Stop hosting" : "Host game",
                BTN_HOST, BUTTON_W, BUTTON_H, pad);
        host.setEnabled(st == Session.State.IDLE || st == Session.State.HOSTING);
        if (shareAddress != null) {
            ButtonAPI copy = t.addButton("Copy address", BTN_COPY, BUTTON_W, BUTTON_H, 6f);
            copy.setEnabled(true);
        }

        // Join
        t.addSectionHeading("Join a game", Alignment.MID, 18f);
        t.addPara("Host's address (e.g. 25.12.34.56). Ctrl+V pastes.", 8f);
        addressField = t.addTextField(300f, 6f);
        addressField.setMaxChars(64);
        addressField.setHandleCtrlV(true);
        addressField.setText(lastJoinAddress);
        String joinText = st == Session.State.CONNECTING ? "Cancel"
                : st == Session.State.CONNECTED ? "Disconnect" : "Join";
        ButtonAPI join = t.addButton(joinText, BTN_JOIN, BUTTON_W, BUTTON_H, pad);
        join.setEnabled(st != Session.State.HOSTING);
        if (multisector.sync.SessionGuard.isWaiting()) {
            t.addPara("This world stays paused until you're back in the host's game; it tries to join every few "
                    + "seconds. Stop waiting to play it on your own (it won't follow the host's world any more).", hl, 8f);
            t.addButton("Stop waiting", BTN_LET_GO, BUTTON_W, BUTTON_H, 6f);
        }

        // Players
        t.addSectionHeading("Players", Alignment.MID, 18f);
        playersLabel = t.addPara(playersText(), 8f);
        t.addPara("Click another player's fleet to hail or escort them.", gray, 6f);
        t.addButton(multisector.sync.Escort.isKeepMoving() ? "In menus: keep moving" : "In menus: stay put",
                BTN_MENUS, BUTTON_W + 40f, BUTTON_H, 6f);

        // Character
        t.addSectionHeading("Your character", Alignment.MID, 18f);
        if (CharacterChooser.isPending()) {
            t.addPara("You haven't chosen your character for this world yet.", hl, 8f);
            t.addButton("Choose your character", BTN_CHOOSE, BUTTON_W + 40f, BUTTON_H, pad);
        }
        t.addPara("Export takes this character, fleet, officers and cargo into a friend's MultiSector world. "
                + "Colonies, reputation and story progress stay in this save.", gray, 8f);
        t.addButton("Export character", BTN_EXPORT, BUTTON_W, BUTTON_H, pad);
        t.addButton(GamePrefs.isAutoCharacter() ? "Bring my character back: on" : "Bring my character back: off",
                BTN_AUTO_CHAR, BUTTON_W + 40f, BUTTON_H, 6f);
        t.addPara("On: joining a world you've played in before brings back your character from last time, "
                + "without asking.", gray, 4f);
        if (st == Session.State.CONNECTED || multisector.sync.SessionGuard.isStaleCopy()) {
            t.addSectionHeading("This copy of the world", Alignment.MID, 18f);
            if (multisector.sync.SessionGuard.isStaleCopy()) {
                t.addPara("The host has loaded their save since this copy was made, so it doesn't match theirs.",
                        Misc.getNegativeHighlightColor(), 8f);
            } else {
                t.addPara("Something out of step? A fresh copy brings the host's world exactly as it is now.", gray, 8f);
            }
            t.addButton("Get a fresh copy", BTN_FRESH, BUTTON_W, BUTTON_H, 6f);
            t.addPara("You go back to the main menu (your character is kept) and the MultiSector launcher downloads "
                    + "the host's world. Then click Continue. Keep the launcher open for this.", gray, 4f);
        }

        t.addPara("MultiSector %s. Shortcut: %s. The players panel on the map: %s.", 18f,
                gray, Misc.getHighlightColor(), BuildInfo.VERSION, MultiSectorConfig.getHotkeyName(),
                MultiSectorConfig.getHudHotkeyName());

        panel.addUIElement(t).inTL(0f, 0f);
        content = t;
        layoutSignature = computeSignature();
    }

    /** Everything that changes the layout. Pings are left out on purpose. */
    private String computeSignature() {
        Session s = Session.get();
        StringBuilder sb = new StringBuilder();
        sb.append(s.getState()).append('|').append(s.getLastError()).append('|')
                .append(localError).append('|').append(localInfo).append('|');
        for (Session.Player p : s.getPlayers()) sb.append(p.id).append(',');
        sb.append('|').append(multisector.sync.SessionGuard.isWaiting());
        sb.append('|').append(multisector.sync.Escort.isKeepMoving());
        sb.append('|').append(GamePrefs.isAutoCharacter()).append(multisector.sync.SessionGuard.isStaleCopy());
        return sb.toString();
    }

    private String pingSuffix() {
        Session s = Session.get();
        return s.getState() == Session.State.CONNECTED && s.getPingMs() >= 0 ? " - ping " + s.getPingMs() + " ms" : "";
    }

    private String playersText() {
        Session s = Session.get();
        List<Session.Player> players = s.getPlayers();
        if (players.isEmpty()) return "Nobody yet. Host a game or join one.";
        StringBuilder sb = new StringBuilder();
        for (PlayerInfo p : PlayerInfo.all(true)) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(p.name);
            if (p.isHost) sb.append("  [host]");
            if (p.isMe) sb.append("  (you)");
            if (!p.isHost && p.pingMs >= 0) sb.append("  -  ").append(p.pingMs).append(" ms");
            if (p.isMe) continue;
            // Where and what
            sb.append("\n    ").append(p.where);
            if (!p.distance.isEmpty()) sb.append(", ").append(p.distance);
            if (!p.doing.isEmpty()) sb.append("  -  ").append(p.doing);
            // Fleet and character
            if (!p.flagship.isEmpty()) {
                sb.append("\n    ").append(p.fleetText()).append("  -  level ").append(p.level)
                        .append("  -  ").append(Misc.getWithDGS(p.credits)).append(" credits");
            }
        }
        return sb.toString();
    }

    // --------------------------------------------------------------- helpers

    private void rememberAddress() {
        if (addressField != null) lastJoinAddress = addressField.getText().trim();
        persistentData().put(PERSIST_KEY, lastJoinAddress);
    }

    private static Map<String, Object> persistentData() {
        return Global.getSector().getPersistentData();
    }

    private static String playerName() {
        try {
            return Global.getSector().getPlayerPerson().getNameString();
        } catch (Exception e) {
            return "Player";
        }
    }

    /** Separate class so a sandbox block on java.awt only breaks the Copy button. */
    private static final class Clipboard {
        static boolean copy(String text) {
            try {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(text), null);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    }
}
