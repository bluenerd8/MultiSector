package multisector.ui;

import com.fs.starfarer.api.campaign.BaseCustomDialogDelegate;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import multisector.net.Session;
import multisector.sync.Social;
import org.lwjgl.input.Keyboard;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The chat window (Enter by default): the last lines, a line to type in,
 * Enter sends. Escape or Close shuts it. Lines also show in the game's
 * message list, so the window is only needed to talk.
 */
public final class ChatWindow extends BaseCustomDialogDelegate implements DialogHost.Window {

    private static final float WIDTH = 620f;
    private static final float HEIGHT = 420f;
    private static final float INPUT_H = 60f;
    private static final int SHOW_LINES = 40;

    private InteractionDialogAPI host;
    private CustomPanelAPI panel;
    private TooltipMakerAPI history;
    private TextFieldAPI field;
    private int shownLines = -1;
    private final CustomUIPanelPlugin plugin = new Plugin();

    private static boolean pending;

    public static void open() {
        DialogHost.show(new ChatWindow(), WIDTH, HEIGHT);
    }

    /** Once the window that asked has closed. */
    public static void openSoon() {
        pending = true;
    }

    /** Every frame (SessionNoticeScript): a chat window asked for, opened once nothing else is up. */
    public static void openIfPending() {
        if (pending && DialogHost.show(new ChatWindow(), WIDTH, HEIGHT)) pending = false;
    }

    public void setHolder(InteractionDialogAPI holder) {
        this.host = holder;
    }

    @Override
    public void createCustomDialog(CustomPanelAPI panel, CustomDialogCallback callback) {
        this.panel = panel;
        TooltipMakerAPI input = panel.createUIElement(WIDTH, INPUT_H, false);
        input.addPara(Session.get().isActive() ? "Type, then Enter to send to everyone." : "Not in a session: nobody will hear.",
                Misc.getGrayColor(), 0f);
        field = input.addTextField(WIDTH - 20f, 6f);
        field.setMaxChars(300);
        field.setHandleCtrlV(true);
        panel.addUIElement(input).inTL(0f, HEIGHT - INPUT_H);
        buildHistory();
        field.grabFocus();
    }

    private void buildHistory() {
        Panels.remove(panel, history);
        TooltipMakerAPI t = panel.createUIElement(WIDTH, HEIGHT - INPUT_H - 10f, true);
        t.addSectionHeading("Chat", com.fs.starfarer.api.ui.Alignment.MID, 0f);
        List<Social.Line> lines = Social.lines();
        if (lines.isEmpty()) t.addPara("Nothing said yet.", Misc.getGrayColor(), 8f);
        SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.ROOT);
        for (int i = Math.max(0, lines.size() - SHOW_LINES); i < lines.size(); i++) {
            Social.Line l = lines.get(i);
            String time = hhmm.format(new Date(l.at));
            if (l.player == -2) {
                t.addPara(time + "  " + l.text, Misc.getGrayColor(), 4f);
            } else {
                t.addPara(time + "  %s: " + l.text.replace("%", "%%"), 4f, Social.colorOf(l.player), l.from);
            }
        }
        panel.addUIElement(t).inTL(0f, 0f);
        history = t;
        shownLines = Social.lineCount();
        // The newest line in view.
        try {
            if (t.getExternalScroller() != null) t.getExternalScroller().setYOffset(Math.max(0f, t.getHeightSoFar() - (HEIGHT - INPUT_H - 10f)));
        } catch (RuntimeException ignored) {
            // the top, then
        }
    }

    private void send() {
        if (field == null) return;
        String text = field.getText();
        if (text == null || text.trim().isEmpty()) return;
        Social.say(text);
        field.setText("");
        buildHistory();
        field.grabFocus();
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
        if (host != null) host.dismiss();
    }

    @Override
    public void customDialogCancel() {
        if (host != null) host.dismiss();
    }

    @Override
    public CustomUIPanelPlugin getCustomPanelPlugin() {
        return plugin;
    }

    private final class Plugin extends BaseCustomUIPanelPlugin {
        @Override
        public void processInput(List<InputEventAPI> events) {
            for (InputEventAPI e : events) {
                if (e.isConsumed() || !e.isKeyDownEvent()) continue;
                int k = e.getEventValue();
                if (k == Keyboard.KEY_RETURN || k == Keyboard.KEY_NUMPADENTER) {
                    e.consume();
                    send();
                }
            }
        }

        @Override
        public void advance(float amount) {
            if (Social.lineCount() != shownLines && panel != null) buildHistory(); // someone said something
        }
    }
}
