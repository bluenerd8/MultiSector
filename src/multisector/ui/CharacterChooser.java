package multisector.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import com.fs.starfarer.api.campaign.BaseCustomDialogDelegate;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.characters.FullName.Gender;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
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
    private static final float HEADER_H = 210f; // room for the "bring my character back" checkbox
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

    /** Called once a character has been set up. */
    static void clearPending() {
        pendingDownloadId = null;
        pendingHost = null;
        pendingPort = 0;
        pendingName = null;
    }

    public static boolean isPending() {
        return pendingHost != null;
    }

    /**
     * Called every frame; opens the chooser once after loading, when the
     * campaign UI is free. A player coming back to a world they've played
     * in before gets their character from last time straight away (unless
     * they asked to be asked).
     */
    public static void openIfPending() {
        if (!isPending() || autoShown) return;
        if (GamePrefs.isAutoCharacter() && bringBack()) return;
        if (open()) autoShown = true;
    }

    /** Your character from your last session in this world, brought in without asking. False if there isn't one. */
    private static boolean bringBack() {
        JSONObject world = ProfileApplier.readWorldExport();
        if (world == null) return false;
        autoShown = true;
        String name = exportName(world);
        if (name.isEmpty()) name = pendingName == null ? "Captain" : pendingName;
        try {
            Session.get().notice(ProfileApplier.applyImport(world, name, pendingDownloadId));
        } catch (Exception e) {
            Global.getLogger(CharacterChooser.class).warn("[MultiSector] couldn't bring back our character", e);
            autoShown = false; // show the chooser instead
            return false;
        }
        Session.get().notice("Welcome back, " + name + ": your character from last time. To choose each time instead, "
                + "press F8 and switch off \"Bring my character back\".");
        joinNow(name);
        return true;
    }

    private static void joinNow(String name) {
        String host = pendingHost;
        int port = pendingPort;
        clearPending();
        Session s = Session.get();
        if (host != null && !s.isActive()) {
            s.join(host, port, name);
            s.notice("Joining " + host + "...");
        }
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

    /** Your character from your last session in this world, or null. */
    private final JSONObject world = ProfileApplier.readWorldExport();
    /** The character you exported by hand (F8), or null. */
    private final JSONObject manual = ProfileApplier.readExport();
    /** Which of the two "import" means: world (true) or the manual export. */
    private boolean worldMode = world != null;
    private JSONObject export = world != null ? world : manual;
    private boolean importMode = export != null;
    private String nameText;
    private Gender gender = Gender.MALE;
    private int portraitIndex = 0;
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
        return "Continue";
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
        if (id.equals("mode_world") && world != null) {
            String exported = exportName(world);
            if (!exported.isEmpty()) nameText = exported;
            worldMode = true;
            export = world;
            importMode = true;
        } else if (id.equals("mode_import") && manual != null) {
            if (!importMode || worldMode) {
                String exported = exportName(manual);
                if (!exported.isEmpty()) nameText = exported;
            }
            worldMode = false;
            export = manual;
            importMode = true;
        } else if (id.equals("auto")) {
            GamePrefs.setAutoCharacter(!GamePrefs.isAutoCharacter());
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
        }
        build();
    }

    /** "Continue" */
    @Override
    public void customDialogConfirm() {
        rememberName();
        String name = nameText.trim();
        if (name.isEmpty()) name = pendingName == null || pendingName.isEmpty() ? "Captain" : pendingName;

        if (!importMode || export == null) {
            // New character: next comes the game's own starting-options dialog.
            List<String> p = portraits();
            String portrait = p.isEmpty() ? null : p.get(Math.min(portraitIndex, p.size() - 1));
            StartOptionsDialog.openSoon(new StartOptionsDialog(name, gender, portrait, pendingDownloadId,
                    pendingHost, pendingPort));
            autoShown = true; // don't reopen this window while the start options are up
            if (holder != null) holder.dismiss();
            return;
        }

        String msg;
        try {
            msg = ProfileApplier.applyImport(export, name, pendingDownloadId);
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
        joinNow(name);
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
        ButtonAPI back = null;
        if (world != null) {
            back = checkbox(head, "Continue as " + exportName(world), "mode_world", 230f, importMode && worldMode, 12f);
        }
        ButtonAPI imp = checkbox(head, "Use my exported character", "mode_import", 230f, importMode && !worldMode,
                back == null ? 12f : 0f);
        imp.setEnabled(manual != null);
        if (back != null) imp.getPosition().rightOfTop(back, 10f);
        ButtonAPI fresh = checkbox(head, "New character", "mode_new", 160f, !importMode, 0f);
        fresh.getPosition().rightOfTop(imp, 10f);
        head.addPara("Name (you can change it):", 12f);
        nameField = head.addTextField(320f, 4f);
        nameField.setMaxChars(40);
        nameField.setHandleCtrlV(true);
        nameField.setText(nameText);
        if (error != null) head.addPara(error, Misc.getNegativeHighlightColor(), 6f);
        if (world != null) {
            ButtonAPI auto = checkbox(head, "Next time, bring my character back without asking", "auto", 420f,
                    GamePrefs.isAutoCharacter(), 6f);
            auto.setChecked(GamePrefs.isAutoCharacter());
        }
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
        if (worldMode) t.addPara("Your character as you left them at your last session in this world.", hl, 10f);
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

        // Right: what happens next
        float rightW = WIDTH - LEFT_W - 20f;
        TooltipMakerAPI right = panel.createUIElement(rightW, HEIGHT - HEADER_H - 10f, false);
        right.addPara("Next: your starting fleet", hl, 0f);
        right.addPara("Click Continue to choose your background and starting ships with the game's own "
                + "new-game options, including any starts your mods add.", 8f);
        right.addPara("Only your character and fleet are set up. The host's world isn't changed: there's no "
                + "difficulty or tutorial choice, and no world generation.", gray, 10f);
        right.addPara("Randomised starts aren't available here, since their ships are rolled during world setup.",
                gray, 10f);
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
