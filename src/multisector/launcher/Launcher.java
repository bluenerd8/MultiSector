package multisector.launcher;

import multisector.net.AddressFinder;
import multisector.net.NetConfig;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The MultiSector launcher: a window outside the game for setting up a
 * session. The host picks a save and shares it; friends download it. Both
 * then start Starsector from here, load the save, and the mod connects by
 * itself using the session file this writes to saves/common.
 *
 * Started by "MultiSector Launcher.cmd" in the mod folder, with the game's
 * own Java. Never loaded by the game.
 */
public final class Launcher {

    private static final String PREFS = "multisector_launcher.json";

    private final GameInstall game;
    private final JFrame frame = new JFrame("MultiSector");
    private final JTextField nameField = new JTextField(16);
    private final JTextArea log = new JTextArea(7, 60);

    // host tab
    private final DefaultListModel<GameInstall.SaveInfo> saveModel = new DefaultListModel<GameInstall.SaveInfo>();
    private final JList<GameInstall.SaveInfo> saveList = new JList<GameInstall.SaveInfo>(saveModel);
    private final JButton hostButton = new JButton("Start hosting");
    private LobbyServer server;

    // join tab
    private final JTextField addressField = new JTextField(18);
    private final JButton checkButton = new JButton("Check host");
    private final JTextArea hostInfo = new JTextArea(6, 50);
    private final JButton downloadButton = new JButton("Download save");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private LobbyClient.Info checkedInfo;

    private CharacterTab characterTab;

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        final GameInstall game;
        try {
            game = GameInstall.locate();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, e.getMessage(), "MultiSector", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                new Launcher(game).show();
            }
        });
    }

    private Launcher(GameInstall game) {
        this.game = game;
    }

    // ================================================================ layout

    private void show() {
        loadPrefs();

        JPanel top = row(new JLabel("Your name:"), nameField,
                Box.createHorizontalStrut(20), new JLabel("Starsector: " + game.root));

        characterTab = new CharacterTab(game, new CharacterTab.Host() {
            public String currentName() {
                return myName();
            }

            public void setName(String name) {
                if (name != null && !name.trim().isEmpty()) nameField.setText(name.trim());
            }

            public void log(String line) {
                Launcher.this.log(line);
            }
        });
        String profileName = characterTab.savedName();
        if (profileName != null && !profileName.isEmpty()) nameField.setText(profileName);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Character", characterTab);
        tabs.addTab("Host a game", hostTab());
        tabs.addTab("Join a game", joinTab());

        log.setEditable(false);
        // The default monospaced font draws "\" as a yen sign on some systems.
        log.setFont(UIManager.getFont("Label.font"));
        hostInfo.setFont(UIManager.getFont("Label.font"));
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        JButton launch = new JButton("Launch Starsector");
        launch.setFont(launch.getFont().deriveFont(Font.BOLD));
        launch.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                launchGame();
            }
        });
        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.add(new JScrollPane(log), BorderLayout.CENTER);
        bottom.add(row(launch), BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(top, BorderLayout.NORTH);
        root.add(tabs, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (server != null) server.stop();
                savePrefs();
                System.exit(0);
            }
        });
        frame.setMinimumSize(new Dimension(760, 720));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        log("Starsector folder: " + game.root);
        if (game.isGameRunning()) {
            log("Starsector is already running. Pick or download the save here, then load it in the game.");
        }
    }

    private JComponent hostTab() {
        saveList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        saveList.setVisibleRowCount(8);
        refreshSaves();

        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                refreshSaves();
            }
        });

        String address = shareAddress();
        JLabel addrLabel = new JLabel(address == null ? "No network address found."
                : "Your address for friends:  " + address);
        addrLabel.setFont(addrLabel.getFont().deriveFont(Font.BOLD));
        JButton copy = new JButton("Copy address");
        copy.setEnabled(address != null);
        final String addr = address;
        copy.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(addr), null);
                log("Copied " + addr + " to the clipboard.");
            }
        });

        hostButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                toggleHosting();
            }
        });

        JPanel p = column(
                label("1. Pick the save to play together. Save in the game first so friends get the latest state."),
                new JScrollPane(saveList),
                row(refresh),
                label("2. Start hosting and send friends your address. Keep this window open while they download."),
                row(hostButton, Box.createHorizontalStrut(16), addrLabel, copy),
                label("3. Click Launch Starsector and load the same save (Continue, if it's your newest)."),
                label("    The game starts hosting by itself once the save is loaded."));
        return p;
    }

    private JComponent joinTab() {
        hostInfo.setEditable(false);
        hostInfo.setLineWrap(true);
        hostInfo.setWrapStyleWord(true);
        hostInfo.setText("Type the host's address and click Check host.");
        downloadButton.setEnabled(false);
        progress.setStringPainted(true);
        progress.setString("");

        ActionListener check = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                checkHost();
            }
        };
        checkButton.addActionListener(check);
        addressField.addActionListener(check);
        downloadButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                downloadSave();
            }
        });

        return column(
                label("1. Type the host's address (their Hamachi 25.x.x.x address)."),
                row(new JLabel("Host's address:"), addressField, checkButton),
                new JScrollPane(hostInfo),
                label("2. Download their save. It replaces any earlier download from the same host."),
                row(downloadButton, progress),
                label("3. Click Launch Starsector, then Continue on the main menu. The game joins the host by itself."));
    }

    // =============================================================== hosting

    private void refreshSaves() {
        saveModel.clear();
        for (GameInstall.SaveInfo s : game.listSaves()) {
            if (s.folder.getName().startsWith(LobbyClient.SAVE_PREFIX)) continue; // others' worlds
            saveModel.addElement(s);
        }
        if (!saveModel.isEmpty()) saveList.setSelectedIndex(0);
    }

    private void toggleHosting() {
        if (server != null) {
            server.stop();
            server = null;
            hostButton.setText("Start hosting");
            saveList.setEnabled(true);
            log("Stopped sharing the save.");
            return;
        }
        GameInstall.SaveInfo save = saveList.getSelectedValue();
        if (save == null) {
            error("Pick a save first.");
            return;
        }
        String name = myName();
        if (name == null) return;
        int port = NetConfig.DEFAULT_PORT;
        LobbyServer s = new LobbyServer(name, save, game.enabledMods(), port, new LobbyServer.Listener() {
            public void log(final String line) {
                Launcher.this.log(line);
            }
        });
        try {
            s.start();
            writeSession("host", null, port, name, save.characterName, GameInstall.readSeed(save.folder), null);
        } catch (IOException e) {
            s.stop();
            error(e.getMessage());
            return;
        }
        server = s;
        hostButton.setText("Stop hosting");
        saveList.setEnabled(false);
        log("Sharing \"" + save.characterName + "\" (" + GameInstall.megabytes(save.sizeBytes) + ") on port "
                + NetConfig.lobbyPort(port) + ". Friends can download it now.");
        log("Next: Launch Starsector and load that save. If Windows asks to allow Java, tick Private AND Public.");
    }

    // ================================================================ joining

    private void checkHost() {
        final String address = addressField.getText().trim();
        final String name = myName();
        if (name == null) return;
        if (address.isEmpty()) {
            error("Type the host's address first, e.g. 25.12.34.56");
            return;
        }
        checkButton.setEnabled(false);
        downloadButton.setEnabled(false);
        hostInfo.setText("Contacting " + address + "...");
        background(new Runnable() {
            public void run() {
                try {
                    final LobbyClient.Info info = LobbyClient.fetchInfo(address, name);
                    ui(new Runnable() {
                        public void run() {
                            checkedInfo = info;
                            hostInfo.setText(describe(info));
                            downloadButton.setEnabled(true);
                            checkButton.setEnabled(true);
                            savePrefs();
                        }
                    });
                } catch (final IOException e) {
                    ui(new Runnable() {
                        public void run() {
                            hostInfo.setText(e.getMessage());
                            checkButton.setEnabled(true);
                        }
                    });
                }
            }
        });
    }

    private String describe(LobbyClient.Info info) {
        StringBuilder sb = new StringBuilder();
        sb.append("Host: ").append(info.hostName).append('\n');
        sb.append("Save: ").append(info.characterName).append(", saved ").append(info.saveDate)
                .append(" (").append(GameInstall.megabytes(info.saveBytes)).append(", ")
                .append(GameInstall.megabytes(info.zipBytes)).append(" to download)\n");
        sb.append("Game version: ").append(info.gameVersion).append('\n');
        org.json.JSONObject profile = characterTab.loadProfile();
        if (profile != null && "import".equals(profile.optString("mode"))) {
            org.json.JSONObject export = characterTab.loadExport();
            sb.append("You'll play as: ").append(profile.optString("name")).append(", imported");
            if (export != null) sb.append(" (").append(CharacterTab.exportSummary(export)).append(")");
            sb.append(" in ").append(info.hostName).append("'s world.\n");
            if (export != null && export.optJSONArray("mods") != null) {
                List<String> needs = new ArrayList<String>();
                org.json.JSONArray m = export.optJSONArray("mods");
                for (int i = 0; i < m.length(); i++) {
                    if (!info.mods.contains(m.optString(i))) needs.add(m.optString(i));
                }
                if (!needs.isEmpty()) {
                    sb.append("Note: your character was exported with mods the host doesn't use: ").append(needs)
                            .append(". Ships or items from those mods will be left behind.\n");
                }
            }
        } else if (profile != null) {
            sb.append("You'll play as: ").append(profile.optString("name")).append(" (starting fleet: ")
                    .append(profile.optString("start")).append(") in ").append(info.hostName).append("'s world.\n");
        } else {
            sb.append("No character saved yet: set one up in the Character tab first, or you'll play as the host's character.\n");
        }

        List<String> mine = game.enabledMods();
        List<String> missing = new ArrayList<String>(info.mods);
        missing.removeAll(mine);
        List<String> extra = new ArrayList<String>(mine);
        extra.removeAll(info.mods);
        if (missing.isEmpty() && extra.isEmpty()) {
            sb.append("Mods: match the host's.");
        } else {
            if (!missing.isEmpty()) sb.append("WARNING - the host uses mods you don't have enabled: ").append(missing)
                    .append(". Install and enable them, or the save may not load.\n");
            if (!extra.isEmpty()) sb.append("You have mods enabled that the host doesn't: ").append(extra)
                    .append(". Turn them off to be safe.");
        }
        return sb.toString();
    }

    private void downloadSave() {
        final LobbyClient.Info info = checkedInfo;
        final String name = myName();
        if (info == null || name == null) return;
        downloadButton.setEnabled(false);
        checkButton.setEnabled(false);
        progress.setValue(0);
        progress.setString("Starting...");
        log("Downloading " + info.hostName + "'s save...");
        background(new Runnable() {
            public void run() {
                try {
                    final long started = System.currentTimeMillis();
                    final LobbyClient.Result result = LobbyClient.download(game, info.address, name, new LobbyClient.Progress() {
                        long lastUi;

                        public synchronized void update(final long done, final long total) {
                            long now = System.currentTimeMillis();
                            if (now - lastUi < 150 && done < total) return;
                            lastUi = now;
                            final String text = progressText(done, total, now - started);
                            ui(new Runnable() {
                                public void run() {
                                    progress.setValue(total == 0 ? 1000 : (int) (done * 1000 / total));
                                    progress.setString(text);
                                }
                            });
                        }
                    });
                    final long elapsed = System.currentTimeMillis() - started;
                    final String label = labelDownload(result.folder, info.hostName);
                    String[] hp = LobbyClient.splitAddress(info.address);
                    writeSession("join", hp[0], info.gamePort, name, info.characterName,
                            GameInstall.readSeed(result.folder), info.zipHash);
                    ui(new Runnable() {
                        public void run() {
                            progress.setValue(1000);
                            downloadButton.setEnabled(true);
                            checkButton.setEnabled(true);
                            if (result.skipped) {
                                progress.setString("Already up to date");
                                log("You already have this exact save, so nothing was downloaded.");
                            } else {
                                progress.setString("Done");
                                log(String.format(Locale.ROOT, "Downloaded %s in %.1f s (%s).",
                                        GameInstall.megabytes(info.zipBytes), elapsed / 1000.0,
                                        speed(info.zipBytes, elapsed)));
                            }
                            log("In the game's Load list it's \"" + label + "\". Click Launch Starsector, then "
                                    + "Continue (or Load it).");
                        }
                    });
                } catch (final IOException e) {
                    ui(new Runnable() {
                        public void run() {
                            progress.setString("Failed");
                            downloadButton.setEnabled(true);
                            checkButton.setEnabled(true);
                            error("Download failed: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    /** "1.2 / 2.1 MB  -  340 KB/s  -  3 s left" */
    private static String progressText(long done, long total, long elapsedMs) {
        StringBuilder sb = new StringBuilder();
        sb.append(GameInstall.megabytes(done)).append(" / ").append(GameInstall.megabytes(total));
        if (elapsedMs > 500 && done > 0) {
            sb.append("  -  ").append(speed(done, elapsedMs));
            double rate = done / (elapsedMs / 1000.0);
            long left = (long) Math.ceil((total - done) / Math.max(rate, 1));
            if (done < total) sb.append("  -  ").append(left < 60 ? left + " s left" : (left / 60) + " min left");
        }
        return sb.toString();
    }

    private static String speed(long bytes, long elapsedMs) {
        double perSec = bytes / Math.max(elapsedMs / 1000.0, 0.001);
        return perSec >= 1024 * 1024
                ? String.format(Locale.ROOT, "%.1f MB/s", perSec / (1024 * 1024))
                : String.format(Locale.ROOT, "%.0f KB/s", perSec / 1024);
    }

    // ================================================================ shared

    private void launchGame() {
        if (game.isGameRunning()) {
            log("Starsector is already running. Load the save from its main menu.");
            return;
        }
        try {
            game.launchGame();
            log("Starting Starsector. Make sure MultiSector is ticked in the launcher's Mods list.");
        } catch (IOException e) {
            error(e.getMessage());
        }
    }

    /**
     * Shows the downloaded save in the game's Load list under your character
     * ("Bob - Alice's world") with your portrait, instead of the host's name.
     * Returns the label.
     */
    private String labelDownload(File folder, String hostName) {
        org.json.JSONObject profile = characterTab.loadProfile();
        String me = profile == null ? null : profile.optString("name", null);
        String portrait = profile == null ? null : profile.optString("portrait", null);
        String label = (me == null || me.isEmpty() ? "MultiSector" : me) + " - " + hostName + "'s world";
        try {
            GameInstall.relabel(folder, label, portrait);
        } catch (IOException e) {
            log("Couldn't rename the save in the Load list: " + e.getMessage());
        }
        if (profile == null) {
            log("You haven't saved a character yet (Character tab), so you'll play as the host's character.");
        }
        return label;
    }

    /**
     * Tells the mod what to do when the save loads. The mod reads it with
     * readTextFileFromCommon, which is allowed inside the game's sandbox.
     * seed identifies the shared world; downloadId makes the mod swap in your
     * character once per download.
     */
    private void writeSession(String mode, String host, int port, String name, String characterName,
                              String seed, String downloadId) throws IOException {
        String json = "{\n"
                + "  \"mode\": " + quote(mode) + ",\n"
                + "  \"host\": " + quote(host == null ? "" : host) + ",\n"
                + "  \"port\": " + port + ",\n"
                + "  \"name\": " + quote(name) + ",\n"
                + "  \"characterName\": " + quote(characterName) + ",\n"
                + "  \"seed\": " + quote(seed == null ? "" : seed) + ",\n"
                + "  \"downloadId\": " + quote(downloadId == null ? "" : downloadId) + ",\n"
                + "  \"createdAt\": " + System.currentTimeMillis() + "\n"
                + "}\n";
        game.writeCommon(NetConfig.SESSION_FILE, json);
    }

    private String myName() {
        String n = nameField.getText().trim();
        if (n.isEmpty()) {
            error("Type your name at the top first.");
            return null;
        }
        return n;
    }

    private static String shareAddress() {
        List<AddressFinder.Candidate> all = AddressFinder.find();
        AddressFinder.Candidate c = AddressFinder.first(all, AddressFinder.Kind.HAMACHI);
        if (c == null) c = AddressFinder.preferred(all);
        return c == null ? null : c.ip();
    }

    private void loadPrefs() {
        String json = game.readCommon(PREFS);
        String name = json == null ? null : jsonString(json, "name");
        String last = json == null ? null : jsonString(json, "lastHost");
        nameField.setText(name != null ? name : System.getProperty("user.name", ""));
        if (last != null) addressField.setText(last);
    }

    private void savePrefs() {
        try {
            game.writeCommon(PREFS, "{\"name\": " + quote(nameField.getText().trim())
                    + ", \"lastHost\": " + quote(addressField.getText().trim()) + "}\n");
        } catch (IOException ignored) {
        }
    }

    private void log(final String line) {
        final String stamped = new SimpleDateFormat("HH:mm:ss").format(new Date()) + "  " + line;
        ui(new Runnable() {
            public void run() {
                log.append(stamped + "\n");
                log.setCaretPosition(log.getDocument().getLength());
            }
        });
    }

    private void error(String message) {
        log(message);
        JOptionPane.showMessageDialog(frame, message, "MultiSector", JOptionPane.WARNING_MESSAGE);
    }

    private static void ui(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    private static void background(Runnable r) {
        Thread t = new Thread(r, "launcher-worker");
        t.setDaemon(true);
        t.start();
    }

    private static JPanel row(Component... parts) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        for (Component c : parts) p.add(c);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static JPanel column(Component... parts) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        for (Component c : parts) {
            if (c instanceof JComponent) ((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(c);
            p.add(Box.createVerticalStrut(6));
        }
        return p;
    }

    private static JLabel label(String text) {
        return new JLabel(text);
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 32) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    private static String jsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }
}
