package multisector.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import com.fs.starfarer.api.campaign.BaseCustomDialogDelegate;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.characters.FullName.Gender;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import multisector.character.ProfileApplier;
import multisector.net.Session;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Choose your character" window, shown in the game the first time a friend
 * loads a freshly downloaded host world. Pick the character exported from
 * your own save, or make a new one (name, gender, portrait, starting fleet).
 * The name can be changed either way. On confirm, your character replaces
 * the host's in your copy of the world, then you connect to the host.
 */
public class CharacterChooser extends BaseCustomDialogDelegate implements DialogHost.Window {

    private static final float WIDTH = 780f;
    private static final float HEIGHT = 620f;
    private static final float HEADER_H = 175f;
    private static final float LEFT_W = 250f;

    // ------------------------------------------------------- pending choice

    /** Set by AutoConnect when a fresh download is loaded; cleared once a character is chosen. */
    private static String pendingDownloadId;
    private static String pendingHost;
    private static int pendingPort;
    private static String pendingName;
    private static boolean autoShown;

    public static void setPending(String downloadId, String host, int port, String name) {
        pendingDownloadId = downloadId;
        pendingHost = host;
        pendingPort = port;
        pendingName = name;
        autoShown = false;
    }

    public static boolean isPending() {
        return pendingHost != null;
    }

    /** Called every frame; opens the chooser once after loading, when the campaign UI is free. */
    public static void openIfPending() {
        if (!isPending() || autoShown) return;
        if (open()) autoShown = true;
    }

    /** Opens on the next free frame, e.g. after another window has closed. */
    public static void openSoon() {
        autoShown = false;
    }

    public static boolean open() {
        if (!isPending()) return false;
        return DialogHost.show(new CharacterChooser(), WIDTH, HEIGHT);
    }

    // ---------------------------------------------------------- window state

    private InteractionDialogAPI holder;
    private CustomPanelAPI panel;
    private final List<TooltipMakerAPI> elements = new ArrayList<TooltipMakerAPI>();
    private TextFieldAPI nameField;

    private final JSONObject export = ProfileApplier.readExport();
    private final List<JSONObject> starts = ProfileApplier.listStarts();
    private boolean importMode = export != null;
    private String nameText;
    private Gender gender = Gender.MALE;
    private int portraitIndex = 0;
    private int startIndex = 0;
    private String error;

    private final Plugin plugin = new Plugin();

    private CharacterChooser() {
        String exported = export == null ? null : exportName(export);
        nameText = importMode && exported != null && !exported.isEmpty() ? exported : pendingName;
        if (nameText == null) nameText = "";
    }

    public void setHolder(InteractionDialogAPI holder) {
        this.holder = holder;
    }

    @Override
    public void createCustomDialog(CustomPanelAPI panel, CustomDialogCallback callback) {
        this.panel = panel;
        build();
    }

    @Override
    public boolean hasCancelButton() {
        return true;
    }

    @Override
    public String getConfirmText() {
        return "Start playing";
    }

    @Override
    public String getCancelText() {
        return "Not now";
    }

    @Override
    public CustomUIPanelPlugin getCustomPanelPlugin() {
        return plugin;
    }

    private class Plugin extends BaseCustomUIPanelPlugin {
        @Override
        public void buttonPressed(Object id) {
            onButton(String.valueOf(id));
        }
    }

    // --------------------------------------------------------------- actions

    private void onButton(String id) {
        rememberName();
        error = null;
        if (id.equals("mode_import") && export != null) {
            if (!importMode) {
                String exported = exportName(export);
                if (!exported.isEmpty()) nameText = exported;
            }
            importMode = true;
        } else if (id.equals("mode_new")) {
            importMode = false;
        } else if (id.equals("male")) {
            gender = Gender.MALE;
            portraitIndex = 0;
        } else if (id.equals("female")) {
            gender = Gender.FEMALE;
            portraitIndex = 0;
        } else if (id.equals("prev") || id.equals("next")) {
            int n = portraits().size();
            if (n > 0) portraitIndex = (portraitIndex + (id.equals("next") ? 1 : n - 1)) % n;
        } else if (id.startsWith("start_")) {
            startIndex = Integer.parseInt(id.substring(6));
        }
        build();
    }

    /** "Start playing" */
    @Override
    public void customDialogConfirm() {
        rememberName();
        String name = nameText.trim();
        if (name.isEmpty()) name = pendingName == null || pendingName.isEmpty() ? "Captain" : pendingName;
        String msg;
        try {
            if (importMode && export != null) {
                msg = ProfileApplier.applyImport(export, name, pendingDownloadId);
            } else {
                List<String> p = portraits();
                String portrait = p.isEmpty() ? null : p.get(Math.min(portraitIndex, p.size() - 1));
                String startId = starts.isEmpty() ? "" : starts.get(Math.min(startIndex, starts.size() - 1)).optString("id");
                msg = ProfileApplier.applyNew(name, gender, portrait, startId, pendingDownloadId);
            }
        } catch (Exception e) {
            Global.getLogger(CharacterChooser.class).warn("[MultiSector] character setup failed", e);
            Session.get().notice("couldn't set up your character: " + e.getMessage()
                    + ". Press F8 and click Choose your character to try again.");
            autoShown = true;
            if (holder != null) holder.dismiss();
            return;
        }
        Session.get().notice(msg);

        // Now connect, under the chosen name.
        String host = pendingHost;
        int port = pendingPort;
        setPending(null, null, 0, null);
        pendingHost = null;
        Session s = Session.get();
        if (host != null && !s.isActive()) {
            s.join(host, port, name);
            Session.get().notice("Joining " + host + "...");
        }
        if (holder != null) holder.dismiss();
    }

    /** "Not now" or Escape */
    @Override
    public void customDialogCancel() {
        autoShown = true;
        Session.get().notice("character not chosen yet. When you're ready, press F8 and click Choose your character.");
        if (holder != null) holder.dismiss();
    }

    // ---------------------------------------------------------------- layout

    private void build() {
        for (TooltipMakerAPI e : elements) panel.removeComponent(e);
        elements.clear();
        Color hl = Misc.getHighlightColor();
        Color gray = Misc.getGrayColor();

        TooltipMakerAPI head = panel.createUIElement(WIDTH, HEADER_H, false);
        head.addTitle("Choose your character");
        head.addPara("You're joining a MultiSector world. Your character and fleet replace the host's in your copy; "
                + "the world itself stays exactly as the host has it.", gray, 6f);
        ButtonAPI imp = checkbox(head, "Use my exported character", "mode_import", 260f, importMode, 12f);
        imp.setEnabled(export != null);
        ButtonAPI fresh = checkbox(head, "New character", "mode_new", 200f, !importMode, 0f);
        fresh.getPosition().rightOfTop(imp, 10f);
        head.addPara("Name (you can change it):", 12f);
        nameField = head.addTextField(320f, 4f);
        nameField.setMaxChars(40);
        nameField.setHandleCtrlV(true);
        nameField.setText(nameText);
        if (error != null) head.addPara(error, Misc.getNegativeHighlightColor(), 6f);
        add(head, 0f, 0f);

        if (importMode) buildImport(hl, gray);
        else buildNew(hl, gray);
    }

    private void buildImport(Color hl, Color gray) {
        TooltipMakerAPI t = panel.createUIElement(WIDTH, HEIGHT - HEADER_H - 10f, true);
        if (export == null) {
            t.addPara("No exported character found. Load your own save, press F8 and click Export character, "
                    + "then load this world again.", gray, 10f);
            add(t, 0f, HEADER_H);
            return;
        }
        JSONObject ch = export.optJSONObject("character");
        String portrait = ch == null ? "" : ch.optString("portrait", "");
        if (loadSprite(portrait)) t.addImage(portrait, 128f, 128f, 10f);
        t.addPara("Level %s, %s credits", 10f, hl,
                String.valueOf(ch == null ? 1 : ch.optInt("level", 1)),
                String.format("%,d", export.optLong("credits", 0)));

        JSONArray fleet = export.optJSONArray("fleet");
        StringBuilder ships = new StringBuilder();
        int missing = 0;
        for (int i = 0; fleet != null && i < fleet.length(); i++) {
            JSONObject s = fleet.optJSONObject(i);
            if (s == null) continue;
            ShipHullSpecAPI spec = hullSpec(s.optString("hull"));
            if (spec == null) spec = hullSpec(s.optString("baseHull"));
            if (ships.length() > 0) ships.append('\n');
            ships.append("  ").append(s.optString("shipName", "")).append(" - ");
            if (spec != null) {
                ships.append(spec.getHullName()).append(" ").append(spec.getDesignation().toLowerCase());
            } else {
                ships.append(s.optString("hull")).append(" (not in this game, will be left behind)");
                missing++;
            }
        }
        t.addPara("Fleet:", 10f);
        t.addPara(ships.toString(), 2f);
        JSONArray officers = export.optJSONArray("officers");
        t.addPara("Officers: %s", 8f, hl, String.valueOf(officers == null ? 0 : officers.length()));

        Set<String> enabled = new HashSet<String>();
        for (ModSpecAPI m : Global.getSettings().getModManager().getEnabledModsCopy()) enabled.add(m.getId());
        List<String> needs = new ArrayList<String>();
        JSONArray mods = export.optJSONArray("mods");
        for (int i = 0; mods != null && i < mods.length(); i++) {
            if (!enabled.contains(mods.optString(i))) needs.add(mods.optString(i));
        }
        if (!needs.isEmpty() || missing > 0) {
            t.addPara("Exported with mods this game doesn't have: " + (needs.isEmpty() ? "none listed" : needs)
                    + ". Anything from them is left behind.", Misc.getNegativeHighlightColor(), 10f);
        }
        t.addPara("Colonies, reputation and story progress from your own save don't come along.", gray, 10f);
        add(t, 0f, HEADER_H);
    }

    private void buildNew(Color hl, Color gray) {
        // Left: gender and portrait
        TooltipMakerAPI left = panel.createUIElement(LEFT_W, HEIGHT - HEADER_H - 10f, false);
        ButtonAPI male = checkbox(left, "Male", "male", 110f, gender == Gender.MALE, 4f);
        ButtonAPI female = checkbox(left, "Female", "female", 110f, gender == Gender.FEMALE, 0f);
        female.getPosition().rightOfTop(male, 10f);
        List<String> p = portraits();
        if (!p.isEmpty()) {
            portraitIndex = Math.min(portraitIndex, p.size() - 1);
            String sprite = p.get(portraitIndex);
            if (loadSprite(sprite)) left.addImage(sprite, 200f, 200f, 12f);
            ButtonAPI prev = left.addButton("< Prev", "prev", 95f, 26f, 8f);
            ButtonAPI next = left.addButton("Next >", "next", 95f, 26f, 0f);
            next.getPosition().rightOfTop(prev, 10f);
            left.addPara("Portrait %s of %s", 6f, gray, hl, String.valueOf(portraitIndex + 1), String.valueOf(p.size()));
        } else {
            left.addPara("No portraits found.", gray, 12f);
        }
        add(left, 0f, HEADER_H);

        // Right: starting fleet
        float rightW = WIDTH - LEFT_W - 20f;
        TooltipMakerAPI right = panel.createUIElement(rightW, HEIGHT - HEADER_H - 10f, true);
        right.addPara("Starting fleet", hl, 0f);
        if (starts.isEmpty()) {
            right.addPara("No starting fleets found (data/config/multisector/starts.csv).", gray, 8f);
        }
        for (int i = 0; i < starts.size(); i++) {
            checkbox(right, starts.get(i).optString("name"), "start_" + i, rightW - 10f, i == startIndex, 4f);
        }
        if (!starts.isEmpty()) {
            JSONObject s = starts.get(Math.min(startIndex, starts.size() - 1));
            if (!s.optString("description").isEmpty()) right.addPara(s.optString("description"), 10f);
            StringBuilder ships = new StringBuilder();
            JSONArray arr = s.optJSONArray("ships");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                String label = arr.optString(i);
                try {
                    ShipVariantAPI v = Global.getSettings().getVariant(label);
                    label = v.getHullSpec().getHullName() + " " + v.getHullSpec().getDesignation().toLowerCase();
                } catch (Exception ignored) {
                }
                if (ships.length() > 0) ships.append('\n');
                ships.append("  - ").append(label);
            }
            right.addPara(ships.toString(), 6f);
            right.addPara("Credits: %s", 6f, hl, String.format("%,d", s.optInt("credits", 0)));
            if (s.optBoolean("officer")) right.addPara("Includes an officer.", 4f);
        }
        add(right, LEFT_W + 20f, HEADER_H);
    }

    // --------------------------------------------------------------- helpers

    private ButtonAPI checkbox(TooltipMakerAPI t, String text, String id, float w, boolean checked, float pad) {
        ButtonAPI b = t.addAreaCheckbox(text, id, Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                Misc.getBrightPlayerColor(), w, 26f, pad);
        b.setChecked(checked);
        return b;
    }

    private void add(TooltipMakerAPI e, float x, float y) {
        panel.addUIElement(e).inTL(x, y);
        elements.add(e);
    }

    private void rememberName() {
        if (nameField != null) nameText = nameField.getText();
    }

    private List<String> portraits() {
        return Global.getSettings().getFactionSpec("player").getAllPortraits(gender);
    }

    /** Portraits are loaded lazily by the game; make sure the image is in memory before showing it. */
    private static boolean loadSprite(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            Global.getSettings().loadTexture(path);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static ShipHullSpecAPI hullSpec(String id) {
        if (id == null || id.isEmpty()) return null;
        try {
            return Global.getSettings().getHullSpec(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String exportName(JSONObject export) {
        JSONObject ch = export.optJSONObject("character");
        if (ch == null) return "";
        return (ch.optString("first", "") + " " + ch.optString("last", "")).trim();
    }
}
