package multisector.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomDialogDelegate;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import multisector.net.FleetState;
import multisector.net.Session;
import multisector.sync.Escort;
import multisector.sync.PuppetManager;
import multisector.sync.Social;

import java.awt.Color;
import java.util.List;
import java.util.Map;

/**
 * Every other player, wherever they are (F6 by default): where they are and
 * what they're doing, with a course to them or an escort in one click, and
 * the spots pinged lately.
 */
public final class PlayersWindow extends BaseCustomDialogDelegate implements DialogHost.Window {

    private static final float WIDTH = 720f;
    private static final float HEIGHT = 520f;
    private static final float BTN_W = 110f;
    private static final float BTN_H = 24f;

    private InteractionDialogAPI host;
    private CustomPanelAPI panel;
    private TooltipMakerAPI content;
    private float refresh;
    private String info;
    private final CustomUIPanelPlugin plugin = new Plugin();

    public static void open() {
        DialogHost.show(new PlayersWindow(), WIDTH, HEIGHT);
    }

    public void setHolder(InteractionDialogAPI holder) {
        this.host = holder;
    }

    @Override
    public void createCustomDialog(CustomPanelAPI panel, CustomDialogCallback callback) {
        this.panel = panel;
        build();
    }

    private void build() {
        Panels.remove(panel, content);
        Session s = Session.get();
        Color gray = Misc.getGrayColor();
        TooltipMakerAPI t = panel.createUIElement(WIDTH, HEIGHT, true);
        t.addSectionHeading("Players", Alignment.MID, 0f);
        if (info != null) t.addPara(info, Misc.getHighlightColor(), 6f);
        if (!s.isActive()) {
            t.addPara("Not in a multiplayer session.", gray, 8f);
        }
        Map<Integer, FleetState.Received> fleets = s.getRemoteFleets();
        Map<Integer, PlayerInfo> infos = new java.util.HashMap<Integer, PlayerInfo>();
        for (PlayerInfo pi : PlayerInfo.all(false)) infos.put(pi.id, pi);
        int shown = 0;
        for (Session.Player p : s.getPlayers()) {
            if (p.id == s.getMyId()) continue;
            shown++;
            FleetState.Received r = fleets.get(p.id);
            String where = r == null || r.state == null ? "somewhere (no position yet)"
                    : Social.describe(r.state.location, r.state.x, r.state.y);
            PlayerInfo pi = infos.get(p.id);
            String doing = pi == null || pi.doing == null ? "" : pi.doing;
            t.addPara("%s (" + PlayersHud.shapeName(p.id) + ")   " + where + (doing.isEmpty() ? "" : "   (" + doing + ")")
                    + (p.pingMs >= 0 ? "   " + p.pingMs + " ms" : ""), 10f, Social.colorOf(p.id), p.name);
            boolean here = PuppetManager.fleetOf(p.id) != null;
            if (here) {
                t.addButton("Go to", "go:" + p.id, BTN_W, BTN_H, 4f);
                t.addButton(Escort.isEscortingPlayer(p.id) ? "Stop escorting" : "Escort", "escort:" + p.id, BTN_W, BTN_H, 4f);
                t.addButton("Ping their spot", "ping:" + p.id, BTN_W + 30f, BTN_H, 4f);
            } else {
                t.addPara("Their fleet isn't on your map yet.", gray, 2f);
            }
        }
        if (shown == 0 && s.isActive()) t.addPara("Nobody else is here yet.", gray, 8f);

        List<Social.Ping> pings = Social.pings();
        t.addSectionHeading("Pings (last 30 seconds)", Alignment.MID, 16f);
        if (pings.isEmpty()) t.addPara("None. Alt+click on the map to mark a spot for everyone.", gray, 6f);
        for (int i = pings.size() - 1; i >= 0; i--) {
            Social.Ping p = pings.get(i);
            t.addPara("%s: " + Social.describe(p.location, p.x, p.y), 8f, Social.colorOf(p.player), p.from);
            t.addButton("Go there", "pinggo:" + i, BTN_W, BTN_H, 4f);
        }
        t.addSectionHeading("Talk", Alignment.MID, 16f);
        t.addButton("Open chat (" + MultiSectorConfig.getChatHotkeyName() + ")", "chat", BTN_W + 60f, BTN_H, 6f);
        t.addPara("Players are ringed in their colour on your map, with an arrow at the edge when they're off screen.",
                gray, 10f);
        panel.addUIElement(t).inTL(0f, 0f);
        content = t;
    }

    private void onButton(Object id) {
        String b = String.valueOf(id);
        info = null;
        if ("chat".equals(b)) {
            if (host != null) host.dismiss();
            ChatWindow.openSoon();
            return;
        }
        try {
            if (b.startsWith("go:")) {
                int pid = Integer.parseInt(b.substring(3));
                CampaignFleetAPI f = PuppetManager.fleetOf(pid);
                if (f != null) {
                    Global.getSector().layInCourseFor(f);
                    info = "Course laid in for " + Session.get().playerName(pid) + ". Close this window to go.";
                }
            } else if (b.startsWith("escort:")) {
                int pid = Integer.parseInt(b.substring(7));
                if (Escort.isEscortingPlayer(pid)) Escort.stopEscorting();
                else Escort.startEscorting(pid);
                info = Escort.isEscortingPlayer(pid) ? "Escorting " + Session.get().playerName(pid) + ". Close this window to go."
                        : "Stopped escorting.";
            } else if (b.startsWith("ping:")) {
                int pid = Integer.parseInt(b.substring(5));
                CampaignFleetAPI f = PuppetManager.fleetOf(pid);
                if (f != null) {
                    Social.ping(f.getContainingLocation(), f.getLocation().x, f.getLocation().y);
                    info = "Pinged " + Session.get().playerName(pid) + "'s spot for everyone.";
                }
            } else if (b.startsWith("pinggo:")) {
                int i = Integer.parseInt(b.substring(7));
                List<Social.Ping> pings = Social.pings();
                if (i >= 0 && i < pings.size()) {
                    Social.Ping p = pings.get(i);
                    SectorEntityToken spot = Social.spot(p.location, p.x, p.y);
                    if (spot != null) {
                        Global.getSector().layInCourseFor(spot);
                        info = "Course laid in for the spot " + p.from + " pinged. Close this window to go.";
                    } else {
                        info = "That place isn't in your copy of the world.";
                    }
                }
            }
        } catch (RuntimeException e) {
            info = "Couldn't do that: " + e.getMessage();
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
        public void buttonPressed(Object buttonId) {
            onButton(buttonId);
        }

        @Override
        public void advance(float amount) {
            refresh -= amount;
            if (refresh > 0f) return;
            refresh = 2f;
            build(); // where everyone is now
        }
    }
}
