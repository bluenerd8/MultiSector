package multisector.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The launcher's Character tab: pick a portrait and a starting fleet for your
 * MultiSector captain. The choices come from the catalog the mod writes when
 * the game starts, so modded portraits and ships appear here too.
 *
 * The result is saved as your profile (saves/common/multisector_profile.json.data)
 * and sent to the host the first time you join their world.
 */
final class CharacterTab extends JPanel {

    private static final long serialVersionUID = 1L;

    static final String PROFILE = "multisector_profile.json";
    static final String CATALOG = "multisector_catalog.json";
    private static final int THUMB = 72;

    interface Host {
        String currentName();
        void log(String line);
    }

    private final GameInstall game;
    private final Host host;

    private JSONObject catalog;
    private final List<File> searchRoots = new ArrayList<File>();
    private final Map<String, ImageIcon> thumbs = new ConcurrentHashMap<String, ImageIcon>();
    private final Set<String> loading = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private final JLabel status = new JLabel();
    private final JRadioButton male = new JRadioButton("Male");
    private final JRadioButton female = new JRadioButton("Female");
    private final DefaultListModel<String> portraitModel = new DefaultListModel<String>();
    private final JList<String> portraitList = new JList<String>(portraitModel);
    private final JLabel preview = new JLabel();
    private final DefaultListModel<Start> startModel = new DefaultListModel<Start>();
    private final JList<Start> startList = new JList<Start>(startModel);
    private final JTextArea startDetails = new JTextArea(8, 30);

    private String playerId;

    static final class Start {
        final String id;
        final String name;
        final JSONObject json;

        Start(JSONObject json) {
            this.json = json;
            this.id = json.optString("id");
            this.name = json.optString("name", id);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    CharacterTab(GameInstall game, Host host) {
        super(new BorderLayout(0, 6));
        this.game = game;
        this.host = host;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        build();
        reload();
    }

    // ================================================================ layout

    private void build() {
        ButtonGroup g = new ButtonGroup();
        g.add(male);
        g.add(female);
        male.setSelected(true);
        ActionListener genderChanged = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                fillPortraits(null);
            }
        };
        male.addActionListener(genderChanged);
        female.addActionListener(genderChanged);

        portraitList.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        portraitList.setVisibleRowCount(-1);
        portraitList.setFixedCellWidth(THUMB + 8);
        portraitList.setFixedCellHeight(THUMB + 8);
        portraitList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        portraitList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, "", index, selected, focus);
                l.setHorizontalAlignment(SwingConstants.CENTER);
                l.setIcon(thumb((String) value, THUMB));
                l.setToolTipText(new File((String) value).getName());
                return l;
            }
        });
        portraitList.addListSelectionListener(new ListSelectionListener() {
            public void valueChanged(ListSelectionEvent e) {
                String p = portraitList.getSelectedValue();
                preview.setIcon(p == null ? null : bigIcon(p));
            }
        });
        preview.setPreferredSize(new Dimension(136, 136));
        preview.setHorizontalAlignment(SwingConstants.CENTER);
        preview.setBorder(BorderFactory.createEtchedBorder());

        JScrollPane portraitScroll = new JScrollPane(portraitList);
        portraitScroll.setPreferredSize(new Dimension(500, 2 * (THUMB + 8) + 6));

        JPanel portraitPanel = new JPanel(new BorderLayout(8, 4));
        JPanel genderRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        genderRow.add(new JLabel("Portrait:"));
        genderRow.add(male);
        genderRow.add(female);
        portraitPanel.add(genderRow, BorderLayout.NORTH);
        portraitPanel.add(portraitScroll, BorderLayout.CENTER);
        portraitPanel.add(preview, BorderLayout.EAST);

        startList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        startList.addListSelectionListener(new ListSelectionListener() {
            public void valueChanged(ListSelectionEvent e) {
                Start s = startList.getSelectedValue();
                startDetails.setText(s == null ? "" : describe(s.json));
                startDetails.setCaretPosition(0);
            }
        });
        startDetails.setEditable(false);
        startDetails.setLineWrap(true);
        startDetails.setWrapStyleWord(true);
        startDetails.setFont(UIManager.getFont("Label.font"));
        JSplitPane startPanel = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(startList), new JScrollPane(startDetails));
        startPanel.setResizeWeight(0.35);
        startPanel.setBorder(BorderFactory.createTitledBorder("Starting fleet"));

        JButton save = new JButton("Save character");
        save.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                saveProfile();
            }
        });
        JButton refresh = new JButton("Reload choices");
        refresh.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                reload();
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(save);
        buttons.add(refresh);

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.add(status, BorderLayout.NORTH);
        top.add(portraitPanel, BorderLayout.CENTER);

        add(top, BorderLayout.NORTH);
        add(startPanel, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
    }

    // ============================================================== catalog

    /** Reads the catalog and the saved profile, and fills the lists. */
    void reload() {
        String text = game.readCommon(CATALOG);
        catalog = null;
        searchRoots.clear();
        if (text != null) {
            try {
                catalog = new JSONObject(text);
            } catch (Exception e) {
                catalog = null;
            }
        }
        if (catalog == null) {
            status.setText("<html><b>Start Starsector once with MultiSector enabled</b> (the main menu is enough), "
                    + "then click Reload choices. The game tells the launcher which portraits and ships your mods offer.</html>");
            portraitModel.clear();
            startModel.clear();
            return;
        }

        JSONArray mods = catalog.optJSONArray("mods");
        Set<String> catalogMods = new HashSet<String>();
        if (mods != null) {
            for (int i = 0; i < mods.length(); i++) {
                JSONObject m = mods.optJSONObject(i);
                if (m == null) continue;
                catalogMods.add(m.optString("id"));
                searchRoots.add(new File(m.optString("path")));
            }
        }
        searchRoots.add(new File(game.root, "starsector-core"));

        Set<String> enabled = new HashSet<String>(game.enabledMods());
        if (!enabled.equals(catalogMods)) {
            status.setText("<html>Your enabled mods changed since the game last started. "
                    + "Start Starsector once, then click Reload choices, to see the current portraits and ships.</html>");
        } else {
            status.setText("Choices come from your game and its mods (" + catalogMods.size() + " mods enabled).");
        }

        JSONObject profile = loadProfile();
        if (profile != null && "female".equals(profile.optString("gender"))) female.setSelected(true);
        else male.setSelected(true);
        fillPortraits(profile == null ? null : profile.optString("portrait", null));

        startModel.clear();
        JSONArray starts = catalog.optJSONArray("starts");
        List<Start> list = new ArrayList<Start>();
        if (starts != null) {
            for (int i = 0; i < starts.length(); i++) {
                JSONObject s = starts.optJSONObject(i);
                if (s != null) list.add(new Start(s));
            }
        }
        Collections.sort(list, new Comparator<Start>() {
            public int compare(Start a, Start b) {
                return Double.compare(a.json.optDouble("order", 1000), b.json.optDouble("order", 1000));
            }
        });
        String wanted = profile == null ? null : profile.optString("start", null);
        int sel = 0;
        for (int i = 0; i < list.size(); i++) {
            startModel.addElement(list.get(i));
            if (list.get(i).id.equals(wanted)) sel = i;
        }
        if (!startModel.isEmpty()) startList.setSelectedIndex(sel);
    }

    private void fillPortraits(String select) {
        if (select == null) select = portraitList.getSelectedValue();
        portraitModel.clear();
        if (catalog == null) return;
        JSONObject p = catalog.optJSONObject("portraits");
        JSONArray arr = p == null ? null : p.optJSONArray(female.isSelected() ? "female" : "male");
        int sel = 0;
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String path = arr.optString(i);
                portraitModel.addElement(path);
                if (path.equals(select)) sel = i;
            }
        }
        if (!portraitModel.isEmpty()) {
            portraitList.setSelectedIndex(sel);
            portraitList.ensureIndexIsVisible(sel);
        }
    }

    private static String describe(JSONObject s) {
        StringBuilder sb = new StringBuilder();
        String desc = s.optString("description", "");
        if (!desc.isEmpty()) sb.append(desc).append("\n\n");
        JSONArray ships = s.optJSONArray("ships");
        sb.append("Ships:\n");
        if (ships != null) {
            for (int i = 0; i < ships.length(); i++) {
                JSONObject sh = ships.optJSONObject(i);
                if (sh == null) continue;
                sb.append("  - ").append(sh.optString("hull")).append(" (")
                        .append(sh.optString("designation").toLowerCase()).append(")\n");
            }
        }
        sb.append("\nCredits: ").append(String.format("%,d", s.optInt("credits")));
        String cargo = s.optString("cargo", "");
        if (!cargo.isEmpty()) sb.append("\nCargo: ").append(cargo.replace(":", " x").replace(",", ", "));
        if (s.optBoolean("officer")) sb.append("\nIncludes an officer.");
        return sb.toString();
    }

    // ============================================================== profile

    JSONObject loadProfile() {
        String text = game.readCommon(PROFILE);
        if (text == null) return null;
        try {
            JSONObject o = new JSONObject(text);
            playerId = o.optString("playerId", null);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** The saved profile's name, or null. Used to fill the launcher's name box. */
    String savedName() {
        JSONObject p = loadProfile();
        return p == null ? null : p.optString("name", null);
    }

    boolean saveProfile() {
        String name = host.currentName();
        if (name == null) return false;
        String portrait = portraitList.getSelectedValue();
        Start start = startList.getSelectedValue();
        if (catalog == null || portrait == null || start == null) {
            host.log("Pick a portrait and a starting fleet first. If the lists are empty, start Starsector once.");
            return false;
        }
        try {
            if (playerId == null || playerId.isEmpty()) playerId = UUID.randomUUID().toString();
            JSONObject o = new JSONObject();
            o.put("playerId", playerId);
            o.put("name", name);
            o.put("gender", female.isSelected() ? "female" : "male");
            o.put("portrait", portrait);
            o.put("start", start.id);
            o.put("savedAt", System.currentTimeMillis());
            game.writeCommon(PROFILE, o.toString(2));
            host.log("Saved your character: " + name + ", " + start.name + ". It's used the first time you join a host's world.");
            return true;
        } catch (Exception e) {
            host.log("Couldn't save your character: " + e.getMessage());
            return false;
        }
    }

    // =============================================================== images

    private File resolve(String path) {
        for (File root : searchRoots) {
            File f = new File(root, path);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** Thumbnail from cache; loads it in the background on first use. */
    private ImageIcon thumb(final String path, final int size) {
        ImageIcon icon = thumbs.get(path);
        if (icon != null || !loading.add(path)) return icon;
        Thread t = new Thread(new Runnable() {
            public void run() {
                ImageIcon i = loadIcon(path, size);
                if (i != null) thumbs.put(path, i);
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        portraitList.repaint();
                    }
                });
            }
        }, "portrait-loader");
        t.setDaemon(true);
        t.start();
        return null;
    }

    private ImageIcon bigIcon(String path) {
        return loadIcon(path, 128);
    }

    private ImageIcon loadIcon(String path, int size) {
        File f = resolve(path);
        if (f == null) return null;
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) return null;
            return new ImageIcon(img.getScaledInstance(size, size, Image.SCALE_SMOOTH));
        } catch (Exception e) {
            return null;
        }
    }
}
