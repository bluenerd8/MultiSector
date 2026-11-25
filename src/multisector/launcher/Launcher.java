package multisector.launcher;

import multisector.BuildInfo;
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
    private static final String MOD_ID = "multisector";
    private JTabbedPane tabs;
    /** What to carry on with after an update reopens the launcher: "host:<save folder>" or "join:<address>". */
    private String resume = "";
    private long resumeAt;

    private final GameInstall game;
    private final JFrame frame = new JFrame("MultiSector " + BuildInfo.VERSION);
    private final JTextField nameField = new JTextField(16);
    private final JTextArea log = new JTextArea(7, 60);

    // host tab
    private final DefaultListModel<GameInstall.SaveInfo> saveModel = new DefaultListModel<GameInstall.SaveInfo>();
    private final JList<GameInstall.SaveInfo> saveList = new JList<GameInstall.SaveInfo>(saveModel);
    private final JButton hostButton = new JButton("Start hosting");
    private final JButton hostLaunchButton = new JButton("Host and launch");
    /** Last save hosted, so it's picked again next time. */
    private String lastSave;
    private LobbyServer server;

    // join tab
    private final JTextField addressField = new JTextField(18);
    private final JButton checkButton = new JButton("Check host");
    private final JTextArea hostInfo = new JTextArea(6, 50);
    private final JButton downloadButton = new JButton("Download save");
    private final JButton joinLaunchButton = new JButton("Join and launch");
    /** Offered when the host runs another MultiSector build: installs theirs. */
    private final JButton matchButton = new JButton("Get the host's build");
    private String hostBuild = "";
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private LobbyClient.Info checkedInfo;
    /** Sets the game's enabled mods to the host's (the player's own list is kept aside). */
    private final JButton modsButton = new JButton("Use the host's mods");
    private final JButton restoreModsButton = new JButton("Put my mods back");
    /** Waiting for the host's game to be back (their session ended): we fetch their world when it is. */
    private final JButton waitButton = new JButton("Stop waiting for the host");
    private String waitingFor;
    private long waitUntil, nextWaitCheck;
    private boolean waitChecking;
    /** The download is a fresh copy our game asked for (it's at the main menu): say so, don't launch. */
    private boolean forRejoin;
    // host tab: Windows Firewall
    private final JLabel firewallLabel = new JLabel("Windows Firewall: checking...");
    private final JButton firewallButton = new JButton("Open it for friends (asks for admin)");


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
        this.logShare = new LogShare(game, new LogShare.Listener() {
            public void log(final String line) {
                ui(new Runnable() {
                    public void run() {
                        Launcher.this.log(line);
                    }
                });
            }
        });
    }

    /** Game logs to and from the host (LogShare): who we joined, and as whom. */
    private final LogShare logShare;
    private volatile String joinedAddress;
    private volatile String joinedName;

    // ================================================================ layout

    private void show() {
        loadPrefs();

        JButton update = new JButton("Update MultiSector");
        update.setToolTipText("Install the newest MultiSector test build from GitHub (closes and reopens this window)");
        update.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                updateTo(null);
            }
        });
        JPanel top = row(new JLabel("Your name:"), nameField,
                Box.createHorizontalStrut(20), new JLabel("Starsector: " + game.root),
                Box.createHorizontalStrut(20), update);

        tabs = new JTabbedPane();
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

        javax.swing.Timer poll = new javax.swing.Timer(2000, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                pollRejoin();
                pollWait();
            }
        });
        poll.start();
        // Logs: ours to the host we joined, every few minutes and at once after a crash; theirs back.
        logShare.setTarget(new LogShare.Target() {
            public String hostAddress() {
                return server == null ? joinedAddress : null; // the host keeps what friends send instead
            }

            public String myName() {
                String n = joinedName;
                return n == null || n.isEmpty() ? "friend" : n;
            }
        });
        logShare.start();
        checkFirewall();
        restoreModsButton.setVisible(game.enabledModsBackup().isFile());
        carryOn();

        log("MultiSector " + BuildInfo.VERSION + " (built " + BuildInfo.BUILT_AT + "). Starsector folder: " + game.root);
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
        hostLaunchButton.setFont(hostLaunchButton.getFont().deriveFont(Font.BOLD));
        hostLaunchButton.setToolTipText("Start hosting this save, make it the one Continue loads, and start Starsector");
        hostLaunchButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                hostAndLaunch();
            }
        });

        JPanel p = column(
                label("1. Pick the save to play together. Friends get a fresh copy when they download."),
                new JScrollPane(saveList),
                row(refresh, label("Any save works, whichever MultiSector version (if any) it was last played with.")),
                label("2. Click Host and launch, then Continue on the game's main menu. Send friends your address."),
                row(hostLaunchButton, Box.createHorizontalStrut(16), addrLabel, copy),
                label("    Keep this window open while friends download. The game starts hosting once the save loads."),
                row(label("Or step by step:"), hostButton),
                row(firewallLabel, firewallButton));
        firewallButton.setVisible(false);
        firewallButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                openFirewall();
            }
        });
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
                downloadSave(false);
            }
        });
        joinLaunchButton.setFont(joinLaunchButton.getFont().deriveFont(Font.BOLD));
        joinLaunchButton.setToolTipText("Check the host, download their world and start Starsector, in one go");
        joinLaunchButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                joinAndLaunch();
            }
        });
        matchButton.setVisible(false);
        matchButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (!hostBuild.isEmpty()) updateTo(hostBuild);
            }
        });
        modsButton.setVisible(false);
        modsButton.setToolTipText("Enable exactly the host's mods in Starsector (your own list is kept to put back)");
        modsButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (checkedInfo != null) useHostMods(checkedInfo);
            }
        });
        restoreModsButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                restoreMods();
            }
        });
        waitButton.setVisible(false);
        waitButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                stopWaiting("Stopped waiting for the host.");
            }
        });

        return column(
                label("1. Type the host's address (their Hamachi 25.x.x.x address), then click Join and launch."),
                row(new JLabel("Host's address:"), addressField, joinLaunchButton, checkButton),
                new JScrollPane(hostInfo),
                row(matchButton, modsButton, restoreModsButton, waitButton),
                label("2. It downloads their world (replacing any earlier download from them) and starts Starsector."),
                row(downloadButton, progress),
                label("3. Click Continue on the main menu. The game joins the host by itself."));
    }

    // =============================================================== hosting

    private void refreshSaves() {
        saveModel.clear();
        int pick = 0;
        for (GameInstall.SaveInfo s : game.listSaves()) {
            if (s.folder.getName().startsWith(LobbyClient.SAVE_PREFIX)) continue; // others' worlds
            if (s.folder.getName().equals(lastSave)) pick = saveModel.size();
            saveModel.addElement(s);
        }
        if (!saveModel.isEmpty()) saveList.setSelectedIndex(pick);
    }

    private void toggleHosting() {
        if (server != null) {
            server.stop();
            server = null;
            hostButton.setText("Start hosting");
            hostLaunchButton.setEnabled(true);
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
        final LobbyServer s = new LobbyServer(name, save, game.enabledMods(), port, new LobbyServer.Listener() {
            public void log(final String line) {
                Launcher.this.log(line);
            }
        });
        s.setModVersions(game.modVersions());
        logShare.setHostName(name);
        s.setLogShare(logShare);
        try {
            s.start();
            writeSession("host", null, port, name, save.characterName, GameInstall.readSeed(save.folder), null);
        } catch (IOException e) {
            s.stop();
            error(e.getMessage());
            return;
        }
        server = s;
        lastSave = save.folder.getName();
        savePrefs();
        hostButton.setText("Stop hosting");
        hostLaunchButton.setEnabled(false);
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
        matchButton.setVisible(false);
        hostInfo.setText("Contacting " + address + "...");
        background(new Runnable() {
            public void run() {
                try {
                    final LobbyClient.Info info = LobbyClient.fetchInfo(address, name);
                    // Their game is hosting: can we actually reach it? (The launcher's port working doesn't prove it.)
                    final boolean reach = info.gameHosting != 1
                            || LobbyClient.canReachGame(LobbyClient.splitAddress(address)[0], info.gamePort);
                    ui(new Runnable() {
                        public void run() {
                            checkedInfo = info;
                            joinedAddress = address;
                            joinedName = name;
                            hostInfo.setText(describe(info, reach));
                            downloadButton.setEnabled(true);
                            checkButton.setEnabled(true);
                            modsButton.setVisible(!modsMatch(info));
                            savePrefs();
                            if (!launchAfterDownload) return;
                            // One click: get the mods right first, or the save won't load.
                            if (!modsMatch(info) && !useHostMods(info)) {
                                launchAfterDownload = false;
                                joinLaunchButton.setEnabled(true);
                                return;
                            }
                            if (info.gameHosting == 0) {
                                // Their game isn't in the world yet: a download now would be their last save, not
                                // their world as it is. Wait for them, then download (and start the game if needed).
                                launchAfterDownload = false;
                                forRejoin = false;
                                joinLaunchButton.setEnabled(true);
                                startWaiting(address, "The host's game isn't in their world yet. This window checks "
                                        + "every few seconds, then downloads it and starts Starsector. (Download save "
                                        + "gets their last save now instead.)");
                                return;
                            }
                            downloadSave(!forRejoin);
                        }
                    });
                } catch (final IOException e) {
                    ui(new Runnable() {
                        public void run() {
                            hostInfo.setText(e.getMessage());
                            checkButton.setEnabled(true);
                            joinLaunchButton.setEnabled(true);
                            launchAfterDownload = false;
                            forRejoin = false;
                            offerHostBuild(e);
                        }
                    });
                }
            }
        });
    }

    /** The host runs another build: one click installs theirs (or the newest, if theirs is unknown). */
    private void offerHostBuild(IOException e) {
        if (!(e instanceof LobbyClient.VersionMismatch)) return;
        hostBuild = ((LobbyClient.VersionMismatch) e).hostVersion;
        matchButton.setText(hostBuild.isEmpty() ? "Get the newest MultiSector" : "Get MultiSector " + hostBuild + " (the host's)");
        if (hostBuild.isEmpty()) hostBuild = "newest";
        matchButton.setVisible(true);
        hostInfo.append("\n\nClick the button below to install the host's build. This window closes and reopens.");
    }

    // ================================================================ one click

    /** After the check: download, then start the game (Join and launch). */
    private boolean launchAfterDownload;

    private void hostAndLaunch() {
        if (server == null) toggleHosting();
        if (server == null) return; // didn't start: the error is shown
        GameInstall.SaveInfo save = saveList.getSelectedValue();
        try {
            if (save != null) GameInstall.touchSaveDate(save.folder); // the main menu's Continue loads it
        } catch (IOException e) {
            log("Couldn't make it the newest save (" + e.getMessage() + "); load it from the Load list instead.");
        }
        launchGame();
        log("When the main menu shows, click Continue. Your game starts hosting once the save loads.");
    }

    private void joinAndLaunch() {
        launchAfterDownload = true;
        joinLaunchButton.setEnabled(false);
        checkHost();
    }

    private String describe(LobbyClient.Info info, boolean reach) {
        StringBuilder sb = new StringBuilder();
        sb.append("Host: ").append(info.hostName);
        if (info.gameHosting == 1 && reach) {
            sb.append(" - their game is in the world and hosting.\n");
        } else if (info.gameHosting == 1) {
            sb.append(" - their game is hosting, but this PC can't reach it on port ").append(info.gamePort)
                    .append(". The host should click \"Open it for friends\" in their launcher (or run tools\\firewall.cmd), "
                            + "and you should both be online in Hamachi.\n");
        } else if (info.gameHosting == 0) {
            sb.append(" - their game isn't hosting yet. You can download now: your game waits for theirs.\n");
        } else {
            sb.append('\n');
        }
        sb.append("Save: ").append(info.characterName).append(", saved ").append(info.saveDate)
                .append(" (").append(GameInstall.megabytes(info.saveBytes)).append(", ")
                .append(GameInstall.megabytes(info.zipBytes)).append(" to download)\n");
        sb.append("Game version: ").append(info.gameVersion).append(", MultiSector ").append(info.hostVersion)
                .append(" (same as yours)\n");
        sb.append("Your character: if you've played in this world before, you come back as yourself. "
                + "Otherwise you choose in the game: your exported character or a new one.\n");
        org.json.JSONObject export = loadExport();
        if (export != null) {
            sb.append("Exported character available: ").append(exportSummary(export)).append('\n');
            List<String> needs = new ArrayList<String>();
            org.json.JSONArray m = export.optJSONArray("mods");
            for (int i = 0; m != null && i < m.length(); i++) {
                if (!info.mods.contains(m.optString(i))) needs.add(m.optString(i));
            }
            if (!needs.isEmpty()) {
                sb.append("Note: it was exported with mods the host doesn't use: ").append(needs)
                        .append(". Ships or items from those mods would be left behind.\n");
            }
        }

        List<String> different = versionsDiffering(info);
        if (!different.isEmpty()) {
            sb.append("Other mod versions than the host's (things may not match up, or crash): ")
                    .append(different).append(". Install the host's versions.\n");
        }
        List<String> mine = game.enabledMods();
        List<String> missing = new ArrayList<String>(info.mods);
        missing.removeAll(mine);
        List<String> extra = new ArrayList<String>(mine);
        extra.removeAll(info.mods);
        if (missing.isEmpty() && extra.isEmpty()) {
            sb.append("Mods: match the host's.");
        } else {
            java.util.Map<String, String> installed = game.installedMods();
            List<String> notInstalled = new ArrayList<String>();
            for (String id : missing) if (!installed.containsKey(id)) notInstalled.add(id);
            if (!missing.isEmpty()) sb.append("The host uses mods you don't have enabled: ").append(missing).append(".\n");
            if (!extra.isEmpty()) sb.append("You have mods enabled that the host doesn't: ").append(extra).append(".\n");
            if (notInstalled.isEmpty()) {
                sb.append("Join and launch switches your mods to the host's (\"Use the host's mods\" does just that); "
                        + "\"Put my mods back\" undoes it.");
            } else {
                sb.append("Not installed here, so install these first: ").append(notInstalled).append('.');
            }
        }
        return sb.toString();
    }

    // ================================================================ mods

    /** "Nexerelin: yours 0.12.2, host's 0.12.3" for each of the host's mods installed here in another version. */
    private List<String> versionsDiffering(LobbyClient.Info info) {
        List<String> out = new ArrayList<String>();
        if (info.modVersions.isEmpty()) return out;
        java.util.Map<String, String> mine = game.modVersions();
        java.util.Map<String, String> names = game.installedMods();
        for (String id : info.mods) {
            String theirs = info.modVersions.get(id);
            String ours = mine.get(id);
            if (theirs == null || ours == null || theirs.equals(ours) || "?".equals(theirs)) continue;
            out.add((names.containsKey(id) ? names.get(id) : id) + ": yours " + ours + ", host's " + theirs);
        }
        return out;
    }

    private boolean modsMatch(LobbyClient.Info info) {
        return new java.util.HashSet<String>(game.enabledMods()).equals(new java.util.HashSet<String>(info.mods));
    }

    /**
     * Enables exactly the host's mods (the player's own list is kept aside
     * once, for "Put my mods back"). False if some aren't installed, or the
     * game is running (it only reads the list when it starts).
     */
    private boolean useHostMods(LobbyClient.Info info) {
        java.util.Map<String, String> installed = game.installedMods();
        List<String> notInstalled = new ArrayList<String>();
        for (String id : info.mods) if (!installed.containsKey(id)) notInstalled.add(id);
        if (!notInstalled.isEmpty()) {
            error("The host's world needs mods that aren't installed here: " + notInstalled
                    + ". Install them in Starsector\\mods, then try again.");
            return false;
        }
        if (game.isGameRunning()) {
            error("Close Starsector first: it only reads the mod list when it starts.");
            return false;
        }
        List<String> want = new ArrayList<String>(info.mods);
        if (!want.contains(MOD_ID)) want.add(MOD_ID);
        try {
            game.writeEnabledMods(want);
        } catch (IOException e) {
            error("Couldn't change the mod list: " + e.getMessage());
            return false;
        }
        log("Switched Starsector's mods to the host's (" + want.size() + " mods). \"Put my mods back\" restores yours.");
        modsButton.setVisible(false);
        restoreModsButton.setVisible(true);
        return true;
    }

    private void restoreMods() {
        if (game.isGameRunning()) {
            error("Close Starsector first: it only reads the mod list when it starts.");
            return;
        }
        try {
            if (game.restoreEnabledMods()) log("Your own mod list is back.");
        } catch (IOException e) {
            error("Couldn't put your mods back: " + e.getMessage());
        }
        restoreModsButton.setVisible(game.enabledModsBackup().isFile());
    }

    /** MultiSector must be ticked in the game's own launcher; a forgotten tick is the commonest snag. */
    private void ensureModEnabled() {
        List<String> on = game.enabledMods();
        if (on.contains(MOD_ID)) return;
        List<String> want = new ArrayList<String>(on);
        want.add(MOD_ID);
        try {
            game.writeEnabledMods(want);
            log("Ticked MultiSector in Starsector's mod list for you.");
            restoreModsButton.setVisible(true);
        } catch (IOException e) {
            log("Couldn't tick MultiSector in the mod list (" + e.getMessage() + "): tick it in the game's launcher.");
        }
    }

    // ================================================================ fresh copies and waiting

    /** Our game asked for a fresh copy of the host's world (it's at the main menu now, or will be shortly). */
    private void pollRejoin() {
        String text = game.readCommon(NetConfig.REJOIN_FILE);
        if (text == null) return;
        game.deleteCommon(NetConfig.REJOIN_FILE);
        String address = jsonString(text, "address");
        Matcher at = Pattern.compile("\"at\"\\s*:\\s*(\\d+)").matcher(text);
        long when = at.find() ? Long.parseLong(at.group(1)) : 0L;
        if (address == null || address.isEmpty() || System.currentTimeMillis() - when > 30L * 60L * 1000L) return;
        boolean wait = text.matches("(?s).*\"wait\"\\s*:\\s*true.*");
        addressField.setText(address);
        frame.toFront();
        if (wait) {
            startWaiting(address, "The host ended the session. This window checks every few seconds and downloads "
                    + "their world as soon as they're back in it. Then click Continue in the game.");
        } else {
            log("Your game asked for a fresh copy of the host's world. Downloading it...");
            rejoinNow();
        }
    }

    /** Checks the host every 10 s until their game is in the world, then downloads it (rejoinNow). */
    private void startWaiting(String address, String why) {
        waitingFor = address;
        waitUntil = System.currentTimeMillis() + 60L * 60L * 1000L;
        nextWaitCheck = System.currentTimeMillis() + 10000L;
        waitButton.setVisible(true);
        log(why);
        hostInfo.append("\n\nWaiting for the host to be in their world...");
    }

    private void rejoinNow() {
        forRejoin = true;
        launchAfterDownload = true;
        joinLaunchButton.setEnabled(false);
        checkHost();
    }

    /** While waiting for the host: every 10 s, is their game hosting again? */
    private void pollWait() {
        if (waitingFor == null || waitChecking) return;
        long now = System.currentTimeMillis();
        if (now > waitUntil) {
            stopWaiting("Stopped waiting for the host after an hour. Click Join and launch when they're back.");
            return;
        }
        if (now < nextWaitCheck) return;
        nextWaitCheck = now + 10000L;
        waitChecking = true;
        final String address = waitingFor;
        final String name = nameField.getText().trim().isEmpty() ? "Player" : nameField.getText().trim();
        background(new Runnable() {
            public void run() {
                int hosting;
                try {
                    hosting = LobbyClient.fetchInfo(address, name).gameHosting;
                } catch (final LobbyClient.VersionMismatch e) {
                    // They're back, on another MultiSector build (they updated): offer it, and carry on joining after.
                    ui(new Runnable() {
                        public void run() {
                            waitChecking = false;
                            if (waitingFor == null) return;
                            stopWaiting("The host is back, with MultiSector " + (e.hostVersion.isEmpty() ? "another build"
                                    : e.hostVersion) + ". Get their build below: then this window joins them by itself.");
                            hostInfo.setText(e.getMessage());
                            offerHostBuild(e);
                            frame.toFront();
                        }
                    });
                    return;
                } catch (IOException e) {
                    hosting = 0; // their launcher isn't up yet
                }
                final boolean back = hosting != 0;
                ui(new Runnable() {
                    public void run() {
                        waitChecking = false;
                        if (!back || waitingFor == null) return;
                        stopWaiting("The host is back. Downloading their world...");
                        rejoinNow();
                    }
                });
            }
        });
    }

    private void stopWaiting(String why) {
        waitingFor = null;
        waitButton.setVisible(false);
        log(why);
    }

    // ================================================================ firewall (host)

    /**
     * What Windows Firewall lets in, for the game's Java (the game) and javaw
     * (this window, which shares the save): MultiSector's port rules, or
     * "allow" rules for those programs on public networks (what clicking
     * Allow on Windows' prompt makes; Hamachi is usually a public network).
     * A block rule for them beats any allow. Read with PowerShell's firewall
     * commands, which need no admin rights and don't depend on the Windows
     * language. Prints e.g. "ports=0 java=1 javaw=1 block=0".
     */
    private String firewallScript() {
        String java = new File(game.root, "jre\\bin\\java.exe").getAbsolutePath().replace("'", "''");
        String javaw = new File(game.root, "jre\\bin\\javaw.exe").getAbsolutePath().replace("'", "''");
        return "$ErrorActionPreference='SilentlyContinue';"
                + "$on={param($r) $r.Enabled -eq 'True' -and $r.Direction -eq 'Inbound'};"
                + "$pub={param($r) ($r.Profile -match 'Public|Any') -or ([int]$r.Profile -eq 0)};"
                + "$ports=@(Get-NetFirewallRule -Group 'MultiSector' | Where-Object {(& $on $_) -and $_.Action -eq 'Allow'}).Count -ge 2;"
                + "$f=@(Get-NetFirewallApplicationFilter | Where-Object {$_.Program -ieq '" + java + "' -or $_.Program -ieq '" + javaw + "'});"
                + "$j=0;$w=0;$b=0;"
                + "foreach($x in $f){$r=$x|Get-NetFirewallRule; if(-not (& $on $r)){continue};"
                + "if($r.Action -eq 'Block'){$b=1}elseif(& $pub $r){if($x.Program -ieq '" + java + "'){$j=1}else{$w=1}}};"
                + "Write-Output ('ports='+[int]$ports+' java='+$j+' javaw='+$w+' block='+$b)";
    }

    /** Is Windows Firewall letting friends in? Shown on the Host tab, with a fix if not. */
    private void checkFirewall() {
        background(new Runnable() {
            public void run() {
                String out;
                try {
                    Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                            firewallScript()).redirectErrorStream(true).start();
                    out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    p.waitFor();
                } catch (Exception e) {
                    return; // can't tell: say nothing
                }
                final boolean ports = out.contains("ports=1");
                final boolean apps = out.contains("java=1") && out.contains("javaw=1");
                final boolean blocked = out.contains("block=1");
                if (!out.contains("ports=")) return; // PowerShell didn't run the check
                ui(new Runnable() {
                    public void run() {
                        if (blocked) {
                            firewallLabel.setText("Windows Firewall: a rule blocks Starsector's Java, so friends can't connect.");
                        } else if (ports || apps) {
                            firewallLabel.setText("Windows Firewall: open for friends.");
                        } else {
                            firewallLabel.setText("Windows Firewall: friends will probably be blocked.");
                        }
                        firewallButton.setVisible(blocked || !(ports || apps));
                    }
                });
            }
        });
    }

    private void openFirewall() {
        File cmd = new File(new File(new File(game.mods, "MultiSector"), "tools"), "firewall.cmd");
        if (!cmd.isFile()) {
            error("Can't find " + cmd + ".");
            return;
        }
        try {
            new ProcessBuilder("cmd", "/c", cmd.getAbsolutePath()).directory(cmd.getParentFile()).start();
            log("Opening the firewall: Windows asks for admin rights. This window checks again in a moment.");
        } catch (IOException e) {
            error("Couldn't run " + cmd + ": " + e.getMessage());
            return;
        }
        javax.swing.Timer again = new javax.swing.Timer(12000, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                checkFirewall();
            }
        });
        again.setRepeats(false);
        again.start();
    }

    private void downloadSave(final boolean thenLaunch) {
        launchAfterDownload = false;
        final LobbyClient.Info info = checkedInfo;
        final String name = myName();
        if (info == null || name == null) {
            joinLaunchButton.setEnabled(true);
            return;
        }
        downloadButton.setEnabled(false);
        checkButton.setEnabled(false);
        progress.setValue(0);
        progress.setString("Waiting for the host to save a fresh copy...");
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
                            GameInstall.readSeed(result.folder), result.zipHash);
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
                                log(String.format(Locale.ROOT, "Downloaded %s in %.1f s (%s) over %s.",
                                        GameInstall.megabytes(result.zipBytes), elapsed / 1000.0,
                                        speed(result.zipBytes, elapsed), LobbyClient.lastMethod));
                            }
                            joinLaunchButton.setEnabled(true);
                            if (forRejoin) {
                                forRejoin = false;
                                frame.toFront();
                                log("Fresh copy ready. Click Continue in the game (it's \"" + label + "\" in the Load list).");
                                if (!game.isGameRunning()) launchGame();
                            } else if (thenLaunch) {
                                launchGame();
                                log("When the main menu shows, click Continue (it's \"" + label + "\" in the Load list).");
                            } else {
                                log("In the game's Load list it's \"" + label + "\". Click Launch Starsector, then "
                                        + "Continue (or Load it).");
                            }
                        }
                    });
                } catch (final IOException e) {
                    ui(new Runnable() {
                        public void run() {
                            progress.setString("Failed");
                            downloadButton.setEnabled(true);
                            checkButton.setEnabled(true);
                            joinLaunchButton.setEnabled(true);
                            forRejoin = false;
                            error("Download failed: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    // ================================================================ updates

    /** Just updated: pick up what the player was doing (hosting that save, or joining that host). */
    private void carryOn() {
        String what = resume;
        long when = resumeAt;
        if (what == null || what.isEmpty()) return;
        resume = "";
        savePrefs();
        if (System.currentTimeMillis() - when > 20L * 60L * 1000L) return; // an old one: not what they're doing now
        if (what.startsWith("host:")) {
            lastSave = what.substring(5);
            refreshSaves();
            tabs.setSelectedIndex(0);
            log("Updated to MultiSector " + BuildInfo.VERSION + ". Carrying on: hosting your save again.");
            hostAndLaunch();
        } else if (what.startsWith("join:")) {
            addressField.setText(what.substring(5));
            tabs.setSelectedIndex(1);
            log("Updated to MultiSector " + BuildInfo.VERSION + ". Carrying on: joining " + what.substring(5) + " again.");
            joinAndLaunch();
        }
    }

    /** What an update should carry on with: hosting the picked save, or joining the typed host. */
    private String resumeFor(boolean joinSide) {
        if (!joinSide && (server != null || tabs.getSelectedIndex() == 0)) {
            GameInstall.SaveInfo save = saveList.getSelectedValue();
            return save == null ? "" : "host:" + save.folder.getName();
        }
        String address = addressField.getText().trim();
        return address.isEmpty() ? "" : "join:" + address;
    }

    /** Installs that MultiSector version (null or "newest": the newest release), then reopens the launcher. */
    private void updateTo(final String wanted) {
        final String carry = resumeFor(wanted != null);
        final String carryText = carry.startsWith("host:") ? "Then it hosts that save again (Host and launch)."
                : carry.startsWith("join:") ? "Then it joins " + carry.substring(5) + " again (Join and launch)." : "";
        log("Looking for MultiSector " + (wanted == null || wanted.equals("newest") ? "updates" : wanted) + " on GitHub...");
        background(new Runnable() {
            public void run() {
                try {
                    final String version = wanted == null || wanted.equals("newest") ? Updater.newestVersion() : wanted;
                    if (version.equals(BuildInfo.VERSION)) {
                        log("You already have MultiSector " + version + ".");
                        return;
                    }
                    final boolean[] go = new boolean[1];
                    SwingUtilities.invokeAndWait(new Runnable() {
                        public void run() {
                            go[0] = JOptionPane.showConfirmDialog(frame, "Install MultiSector " + version + " (you have "
                                    + BuildInfo.VERSION + ")?\n\nThis window closes, the mod is replaced (your "
                                    + "multisector.json settings are kept), and the launcher opens again. " + carryText
                                    + (game.isGameRunning() ? "\n\nStarsector is running: save and quit it when "
                                    + "asked, and the update finishes as soon as it closes." : ""),
                                    "MultiSector", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
                        }
                    });
                    if (!go[0]) return;
                    java.io.File script = Updater.prepare(game, version, new Updater.Progress() {
                        public void update(String text) {
                            log(text);
                        }
                    });
                    // The mod can only be swapped once the game is closed: downloaded already, so it's quick after.
                    if (game.isGameRunning()) {
                        log("Downloaded. Now save and quit Starsector: the update finishes as soon as it closes.");
                        ui(new Runnable() {
                            public void run() {
                                frame.toFront();
                            }
                        });
                        while (game.isGameRunning()) Thread.sleep(2000);
                    }
                    if (server != null) {
                        server.stop();
                        log("Stopped sharing the save for the update (it starts again afterwards).");
                    }
                    log("Installing MultiSector " + version + "; the launcher reopens in a moment.");
                    resume = carry;
                    resumeAt = System.currentTimeMillis();
                    savePrefs();
                    Updater.start(script);
                    System.exit(0);
                } catch (final Exception e) {
                    ui(new Runnable() {
                        public void run() {
                            error("Update failed: " + e.getMessage());
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
        ensureModEnabled();
        try {
            game.launchGame();
            log("Starting Starsector. Click Play in its launcher.");
        } catch (IOException e) {
            error(e.getMessage());
        }
    }

    /**
     * Shows the downloaded save in the game's Load list as "Bob - Alice's world"
     * instead of under the host's character name. Returns the label.
     */
    private String labelDownload(File folder, String hostName) {
        String me = nameField.getText().trim();
        String label = (me.isEmpty() ? "MultiSector" : me) + " - " + hostName + "'s world";
        try {
            GameInstall.relabel(folder, label, null);
        } catch (IOException e) {
            log("Couldn't rename the save in the Load list: " + e.getMessage());
        }
        return label;
    }

    /** The character exported in the game (F8, Export character), or null. */
    private org.json.JSONObject loadExport() {
        String text = game.readCommon("multisector_character.json");
        if (text == null) return null;
        try {
            return new org.json.JSONObject(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** "Jane Doe, level 7, 5 ships, 2 officers, 120,000 credits" */
    private static String exportSummary(org.json.JSONObject export) {
        org.json.JSONObject ch = export.optJSONObject("character");
        org.json.JSONArray fleet = export.optJSONArray("fleet");
        org.json.JSONArray officers = export.optJSONArray("officers");
        String name = ch == null ? "?" : (ch.optString("first", "") + " " + ch.optString("last", "")).trim();
        return name + ", level " + (ch == null ? "?" : ch.optInt("level", 1)) + ", "
                + (fleet == null ? 0 : fleet.length()) + " ships, " + (officers == null ? 0 : officers.length())
                + " officers, " + String.format("%,d", export.optLong("credits", 0)) + " credits";
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
        lastSave = json == null ? null : jsonString(json, "lastSave");
        String r = json == null ? null : jsonString(json, "resume");
        resume = r == null ? "" : r;
        Matcher at = json == null ? null : Pattern.compile("\"resumeAt\"\\s*:\\s*(\\d+)").matcher(json);
        resumeAt = at != null && at.find() ? Long.parseLong(at.group(1)) : 0L;
    }

    private void savePrefs() {
        try {
            game.writeCommon(PREFS, "{\"name\": " + quote(nameField.getText().trim())
                    + ", \"lastHost\": " + quote(addressField.getText().trim())
                    + ", \"lastSave\": " + quote(lastSave == null ? "" : lastSave)
                    + ", \"resume\": " + quote(resume == null ? "" : resume) + ", \"resumeAt\": " + resumeAt + "}\n");
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
