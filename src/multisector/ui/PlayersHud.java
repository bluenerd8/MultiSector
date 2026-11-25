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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    /** Who's fast-forwarding, on a line of its own under the title (too long to share it), or null. */
    private LabelAPI fastLine;
    private boolean showFast;
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

    /** Map markers failed once: off for this session rather than an error every frame. */
    private boolean markersOff;
    private final Map<Integer, LabelAPI> markerLabels = new HashMap<Integer, LabelAPI>();
    private final List<LabelAPI> pingLabels = new ArrayList<LabelAPI>();

    public void renderInUICoordsBelowUI(ViewportAPI viewport) {
        if (markersOff) return;
        try {
            markers();
        } catch (RuntimeException e) {
            markersOff = true;
            Global.getLogger(PlayersHud.class).warn("[MultiSector] player markers failed; turning them off", e);
        }
    }

    /**
     * Every other player here, unmistakable: a ring in their colour, their
     * name and how far, and an arrow at the edge of the screen while they're
     * off it. Spots pinged here pulse the same way.
     */
    private void markers() {
        Session s = Session.get();
        if (!s.isActive()) return;
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (ui == null || ui.isHideUI() || !ClockSync.isMapView()) return;
        ViewportAPI vp = Global.getSector().getViewport();
        com.fs.starfarer.api.campaign.CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (vp == null || me == null || me.getContainingLocation() == null) return;
        com.fs.starfarer.api.campaign.LocationAPI here = me.getContainingLocation();
        float sw = Global.getSettings().getScreenWidth();
        float sh = Global.getSettings().getScreenHeight();
        for (Session.Player p : s.getPlayers()) {
            if (p.id == s.getMyId()) continue;
            com.fs.starfarer.api.campaign.CampaignFleetAPI f = multisector.sync.PuppetManager.fleetOf(p.id);
            if (f == null || f.getContainingLocation() != here) continue;
            Color c = multisector.sync.Social.colorOf(p.id);
            float x = vp.convertWorldXtoScreenX(f.getLocation().x);
            float y = vp.convertWorldYtoScreenY(f.getLocation().y);
            float r = Math.max(16f, vp.convertWorldWidthToScreenWidth(f.getRadius()) + 10f);
            float d = Misc.getDistance(me.getLocation(), f.getLocation());
            LabelAPI l = markerLabels.get(p.id);
            if (l == null) markerLabels.put(p.id, l = label());
            set(l, p.name + (d > 2000f ? "  " + Math.round(d / 1000f) + "k" : ""), c);
            marker(x, y, r, c, 0.9f, l, sw, sh, p.id);
        }
        int n = 0;
        String hereId = here.isHyperspace() ? multisector.net.FleetState.HYPERSPACE : here.getId();
        for (multisector.sync.Social.Ping pg : multisector.sync.Social.pings()) {
            if (!hereId.equals(pg.location)) continue;
            if (pingLabels.size() <= n) pingLabels.add(label());
            LabelAPI l = pingLabels.get(n++);
            Color c = multisector.sync.Social.colorOf(pg.player);
            set(l, "You".equals(pg.from) ? "your ping" : pg.from + "'s ping", c);
            float alpha = 1f - pg.age() * 0.7f;
            float pulse = (System.nanoTime() / 1_000_000L % 1500L) / 1500f;
            float x = vp.convertWorldXtoScreenX(pg.x);
            float y = vp.convertWorldYtoScreenY(pg.y);
            // The pinger's own shape, pulsing out, and a steady one at the spot: whose it is at a glance.
            marker(x, y, 14f + 34f * pulse, c, alpha * (1f - pulse * 0.6f), l, sw, sh, pg.player);
            shape(x, y, 9f, c, alpha, pg.player);
        }
    }

    /**
     * A ring, the player's own shape around it, and the label; or an arrow
     * at the screen's edge pointing at it, with their shape and the label.
     */
    private static void marker(float x, float y, float r, Color c, float alpha, LabelAPI l, float sw, float sh, int player) {
        float margin = 36f;
        boolean on = x >= 0f && x <= sw && y >= 0f && y <= sh;
        if (on) {
            ring(x, y, r, c, alpha);
            shape(x, y, r + 9f, c, alpha * 0.8f, player);
            place(l, x - 90f, y + r + 4f, 180f);
            l.setAlignment(com.fs.starfarer.api.ui.Alignment.MID);
            l.render(alpha);
            return;
        }
        // Off screen: from the centre toward it, stopped at the edge.
        float cx = sw / 2f, cy = sh / 2f;
        float dx = x - cx, dy = y - cy;
        float kx = Math.abs(dx) < 0.001f ? Float.MAX_VALUE : (sw / 2f - margin) / Math.abs(dx);
        float ky = Math.abs(dy) < 0.001f ? Float.MAX_VALUE : (sh / 2f - margin) / Math.abs(dy);
        float k = Math.min(kx, ky);
        float ex = cx + dx * k, ey = cy + dy * k;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float ux = dx / len, uy = dy / len;
        arrow(ex, ey, ux, uy, c, alpha);
        shape(ex - ux * 22f, ey - uy * 22f, 8f, c, alpha, player);
        float lx = ex - ux * 44f, ly = ey - uy * 44f;
        place(l, lx - 90f, ly - LINE / 2f, 180f);
        l.setAlignment(com.fs.starfarer.api.ui.Alignment.MID);
        l.render(alpha);
    }

    /**
     * Each player's own mark, so two players are told apart even where
     * colours are hard to see: a second ring, a diamond, a square, a
     * triangle, a hexagon or a star (by player, the same on every game).
     */
    /** "diamond": the player's mark, by name, for the players window. */
    static String shapeName(int player) {
        return new String[]{"double ring", "diamond", "square", "triangle", "hexagon", "star"}[Math.abs(player) % 6];
    }

    static void shape(float x, float y, float r, Color c, float alpha, int player) {
        int kind = Math.abs(player) % 6;
        if (kind == 0) {
            ring(x, y, r, c, alpha);
            return;
        }
        int points = kind == 1 ? 4 : kind == 2 ? 4 : kind == 3 ? 3 : kind == 4 ? 6 : 10;
        double turn = kind == 2 ? Math.PI / 4 : Math.PI / 2;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LINE_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(2f);
            GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, Math.max(0f, Math.min(1f, alpha)));
            GL11.glBegin(GL11.GL_LINE_LOOP);
            for (int i = 0; i < points; i++) {
                double a = turn + i * Math.PI * 2 / points;
                float rr = kind == 5 && i % 2 == 1 ? r * 0.45f : r; // a star's inner points
                GL11.glVertex2f(x + (float) Math.cos(a) * rr, y + (float) Math.sin(a) * rr);
            }
            GL11.glEnd();
        } finally {
            GL11.glPopAttrib();
        }
    }

    private static void ring(float x, float y, float r, Color c, float alpha) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LINE_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(2f);
            GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, Math.max(0f, Math.min(1f, alpha)));
            GL11.glBegin(GL11.GL_LINE_LOOP);
            for (int i = 0; i < 48; i++) {
                double a = i * Math.PI * 2 / 48;
                GL11.glVertex2f(x + (float) Math.cos(a) * r, y + (float) Math.sin(a) * r);
            }
            GL11.glEnd();
        } finally {
            GL11.glPopAttrib();
        }
    }

    private static void arrow(float x, float y, float ux, float uy, Color c, float alpha) {
        float px = -uy, py = ux; // across the arrow
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, Math.max(0f, Math.min(1f, alpha)));
            GL11.glBegin(GL11.GL_TRIANGLES);
            GL11.glVertex2f(x + ux * 12f, y + uy * 12f);
            GL11.glVertex2f(x - ux * 10f + px * 10f, y - uy * 10f + py * 10f);
            GL11.glVertex2f(x - ux * 10f - px * 10f, y - uy * 10f - py * 10f);
            GL11.glEnd();
        } finally {
            GL11.glPopAttrib();
        }
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
        int extra = showFast ? 1 : 0;
        float height = PAD * 2 + LINE * (info.size() + 1 + extra);
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
        if (showFast) place(fastLine, left + PAD, top - LINE * 2, width - PAD * 2);
        for (int i = 0; i < info.size(); i++) {
            float x = left + PAD;
            float y = top - LINE * (i + 2 + extra);
            LabelAPI[] row = rows.get(i);
            for (int c = 0; c < COLS.length; c++) {
                place(row[c], x, y, COLS[c]);
                x += COLS[c];
            }
        }
        title.render(1f);
        if (showFast) fastLine.render(1f);
        for (int i = 0; i < info.size(); i++) for (LabelAPI l : rows.get(i)) l.render(1f);
    }

    private void updateLabels() {
        if (title == null) title = label();
        if (fastLine == null) fastLine = label();
        String fast = ClockSync.fastText();
        set(title, "Players (" + (info.size() + 1) + ")   " + MultiSectorConfig.getHudHotkeyName() + " hides",
                Misc.getGrayColor());
        showFast = fast != null;
        if (showFast) set(fastLine, ">> " + shorten(fast, 70), Misc.getHighlightColor());
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
