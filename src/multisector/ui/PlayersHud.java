package multisector.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.util.Misc;
import multisector.net.Session;
import multisector.sync.ClockSync;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * A small always-on panel on the campaign map listing the other players:
 * name, where they are, what they're doing, and ping. More detail is in the
 * Multiplayer window (F8). The HUD hotkey (F7 by default) hides it.
 *
 * Drawn with the game's own text labels, so no extra library is needed.
 * Shown only on the map itself, not over dialogs or core screens.
 */
public class PlayersHud implements CampaignUIRenderingListener {

    private static final float[] COLS = {120f, 105f, 150f, 50f};
    private static final float PAD = 8f;
    private static final float LINE = 17f;
    private static final float MARGIN = 12f;
    private static final int MAX_NAME = 16;
    private static final long REFRESH_NANOS = 250_000_000L;

    private static boolean visible = true;
    private static boolean visibilityLoaded;

    private final List<LabelAPI[]> rows = new ArrayList<LabelAPI[]>();
    private LabelAPI title;
    private List<PlayerInfo> info = new ArrayList<PlayerInfo>();
    private long nextRefresh;

    /** The HUD hotkey: show or hide the panel. */
    public static void toggle() {
        loadVisibility();
        visible = !visible;
        Global.getSector().getCampaignUI().addMessage("MultiSector: players panel " + (visible ? "shown" : "hidden")
                + " (" + MultiSectorConfig.getHudHotkeyName() + ")", Misc.getGrayColor());
    }

    private static void loadVisibility() {
        if (visibilityLoaded) return;
        visibilityLoaded = true;
        visible = MultiSectorConfig.isShowPlayersHud();
    }

    public void renderInUICoordsBelowUI(ViewportAPI viewport) {
    }

    public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI viewport) {
        try {
            draw();
        } catch (RuntimeException e) {
            visible = false; // don't spam the log every frame
            Global.getLogger(PlayersHud.class).warn("[MultiSector] players panel failed; hiding it", e);
        }
    }

    public void renderInUICoordsAboveUIAndTooltips(ViewportAPI viewport) {
    }

    private void draw() {
        loadVisibility();
        if (!visible || !Session.get().isActive()) return;
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (ui == null || ui.isHideUI() || !ClockSync.isMapView()) return;

        long now = System.nanoTime();
        if (now - nextRefresh >= 0) {
            nextRefresh = now + REFRESH_NANOS;
            info = PlayerInfo.all(false);
            updateLabels();
        }
        if (info.isEmpty()) return;

        float width = PAD * 2;
        for (float c : COLS) width += c;
        float height = PAD * 2 + LINE * (info.size() + 1);
        float sw = Global.getSettings().getScreenWidth();
        float sh = Global.getSettings().getScreenHeight();
        String corner = MultiSectorConfig.getHudCorner();
        float left = corner.endsWith("Left") ? MARGIN : sw - MARGIN - width;
        float bottom = corner.startsWith("bottom") ? MARGIN : sh - MARGIN - height;
        left += MultiSectorConfig.getHudOffsetX();
        bottom -= MultiSectorConfig.getHudOffsetY(); // positive offset moves it down, like screen coordinates

        background(left, bottom, width, height);

        float top = bottom + height - PAD;
        place(title, left + PAD, top - LINE, width - PAD * 2);
        for (int i = 0; i < info.size(); i++) {
            float x = left + PAD;
            float y = top - LINE * (i + 2);
            LabelAPI[] row = rows.get(i);
            for (int c = 0; c < COLS.length; c++) {
                place(row[c], x, y, COLS[c]);
                x += COLS[c];
            }
        }
        title.render(1f);
        for (int i = 0; i < info.size(); i++) for (LabelAPI l : rows.get(i)) l.render(1f);
    }

    private void updateLabels() {
        if (title == null) title = label();
        String fast = ClockSync.fastText();
        title.setText("Players (" + (info.size() + 1) + ")   " + MultiSectorConfig.getHudHotkeyName() + " hides"
                + (fast == null ? "" : "   >> " + fast));
        title.setColor(fast == null ? Misc.getGrayColor() : Misc.getHighlightColor());
        while (rows.size() < info.size()) {
            rows.add(new LabelAPI[]{label(), label(), label(), label()});
        }
        Color text = Misc.getTextColor();
        Color gray = Misc.getGrayColor();
        Color hl = Misc.getHighlightColor();
        Color bad = Misc.getNegativeHighlightColor();
        for (int i = 0; i < info.size(); i++) {
            PlayerInfo p = info.get(i);
            LabelAPI[] row = rows.get(i);
            set(row[0], shorten(p.name, MAX_NAME), p.live ? hl : gray);
            set(row[1], shorten(p.where, 14), p.live ? text : gray);
            set(row[2], shorten(p.doing, 22), p.fighting ? bad : p.live ? text : gray);
            set(row[3], p.pingText(), p.pingMs > 250 ? bad : gray);
        }
    }

    private static LabelAPI label() {
        return Global.getSettings().createLabel("", Fonts.DEFAULT_SMALL);
    }

    private static void set(LabelAPI l, String text, Color color) {
        if (!text.equals(l.getText())) l.setText(text);
        l.setColor(color);
    }

    private static void place(LabelAPI l, float x, float y, float w) {
        l.getPosition().setSize(w, LINE);
        l.getPosition().setLocation(x, y);
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 2) + "..";
    }

    private static void background(float x, float y, float w, float h) {
        Color edge = Misc.getDarkPlayerColor();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(0f, 0f, 0f, 0.6f);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(x, y);
            GL11.glVertex2f(x + w, y);
            GL11.glVertex2f(x + w, y + h);
            GL11.glVertex2f(x, y + h);
            GL11.glEnd();
            GL11.glColor4f(edge.getRed() / 255f, edge.getGreen() / 255f, edge.getBlue() / 255f, 0.9f);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(x, y);
            GL11.glVertex2f(x + w, y);
            GL11.glVertex2f(x + w, y + h);
            GL11.glVertex2f(x, y + h);
            GL11.glEnd();
        } finally {
            GL11.glPopAttrib();
        }
    }
}
