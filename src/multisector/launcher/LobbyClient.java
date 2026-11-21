package multisector.launcher;

import multisector.net.Frames;
import multisector.net.NetConfig;
import multisector.net.Protocol;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Friend side of the launcher: asks a host for its session, then downloads the save. */
final class LobbyClient {

    static final class Info {
        String address;
        int gamePort;
        String hostName;
        String characterName;
        String saveDate;
        String gameVersion;
        long saveBytes;
        List<String> mods = new ArrayList<String>();
    }

    interface Progress {
        void update(long done, long total);
    }

    /** Folder prefix for downloaded saves; only folders with this prefix are ever replaced. */
    static final String SAVE_PREFIX = "save_MultiSector_";

    private LobbyClient() {
    }

    /** Parses "ip" or "ip:gamePort" into a host and game port. */
    static String[] splitAddress(String text) {
        String host = text.trim();
        int port = NetConfig.DEFAULT_PORT;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            try {
                port = Integer.parseInt(host.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
            }
            host = host.substring(0, colon).trim();
        }
        return new String[]{host, String.valueOf(port)};
    }

    static Info fetchInfo(String address, String myName) throws IOException {
        Socket s = connect(address);
        try {
            return hello(s, address, myName);
        } finally {
            s.close();
        }
    }

    /**
     * Downloads the host's save into saves/save_MultiSector_&lt;host&gt;, replacing
     * an earlier download from the same host. Returns the save folder.
     */
    static File download(GameInstall game, String address, String myName, Progress progress) throws IOException {
        Socket s = connect(address);
        try {
            Info info = hello(s, address, myName);
            ByteBuffer req = Protocol.begin(Protocol.LOBBY_GET_SAVE, 0);
            req.flip();
            Frames.write(s.getOutputStream(), req);

            s.setSoTimeout(60000);
            ByteBuffer begin = Frames.read(s.getInputStream());
            if (begin == null || Protocol.readHeader(begin, null) != Protocol.LOBBY_SAVE_BEGIN) {
                throw new IOException("The host didn't send the save.");
            }
            long total = begin.getLong();
            int fileCount = begin.getInt();

            String folderName = SAVE_PREFIX + safeName(info.hostName);
            File target = new File(game.saves, folderName);
            File partial = new File(game.saves, folderName + ".partial");
            deleteFolder(partial);
            if (!partial.mkdirs()) throw new IOException("Can't create " + partial);

            final long[] done = {0};
            final Progress p = progress;
            final long tot = total;
            InputStream in = new BufferedInputStream(s.getInputStream(), 64 * 1024);
            ZipInputStream zip = new ZipInputStream(in);
            ZipEntry e;
            int files = 0;
            byte[] buf = new byte[64 * 1024];
            while ((e = zip.getNextEntry()) != null) {
                String name = new File(e.getName()).getName(); // no paths: never write outside the folder
                if (name.isEmpty() || e.isDirectory()) continue;
                java.io.OutputStream out = Files.newOutputStream(new File(partial, name).toPath());
                try {
                    int n;
                    while ((n = zip.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done[0] += n;
                        p.update(done[0], tot);
                    }
                } finally {
                    out.close();
                }
                files++;
            }
            if (files != fileCount || done[0] != total) {
                throw new IOException("Download incomplete (" + files + "/" + fileCount + " files). Try again.");
            }
            if (!new File(partial, "descriptor.xml").isFile()) throw new IOException("The host's save has no descriptor.xml.");

            if (target.exists()) deleteFolder(target);
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            GameInstall.touchSaveDate(target);
            return target;
        } finally {
            s.close();
        }
    }

    private static Socket connect(String address) throws IOException {
        String[] hp = splitAddress(address);
        int lobby = NetConfig.lobbyPort(Integer.parseInt(hp[1]));
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(hp[0], lobby), NetConfig.CONNECT_TIMEOUT_MS);
            s.setSoTimeout(15000);
            return s;
        } catch (ConnectException e) {
            s.close();
            throw new IOException("Reached the host's PC, but their launcher isn't sharing a game. "
                    + "Ask them to pick a save and click Start hosting.");
        } catch (SocketTimeoutException e) {
            s.close();
            throw new IOException("No answer from " + hp[0] + ". Check you're both online in the same Hamachi "
                    + "network (green dot), the address is right, and the host ran tools\\firewall.cmd.");
        } catch (IOException e) {
            s.close();
            throw new IOException("Can't connect to " + hp[0] + ": " + e.getMessage());
        }
    }

    private static Info hello(Socket s, String address, String myName) throws IOException {
        ByteBuffer hello = Protocol.begin(Protocol.LOBBY_HELLO, Protocol.stringSize(myName));
        Protocol.putString(hello, myName);
        hello.flip();
        Frames.write(s.getOutputStream(), hello);
        ByteBuffer bb = Frames.read(s.getInputStream());
        short[] ver = new short[1];
        if (bb == null || Protocol.readHeader(bb, ver) != Protocol.LOBBY_INFO) {
            throw new IOException("Something answered, but it isn't a MultiSector launcher.");
        }
        if (ver[0] != NetConfig.PROTOCOL_VERSION) {
            throw new IOException("The host has a different MultiSector version. Both of you need the same release.");
        }
        Info info = new Info();
        info.address = address;
        info.hostName = Protocol.getString(bb);
        info.characterName = Protocol.getString(bb);
        info.saveDate = Protocol.getString(bb);
        info.gameVersion = Protocol.getString(bb);
        info.saveBytes = bb.getLong();
        info.gamePort = bb.getInt();
        int n = bb.getInt();
        for (int i = 0; i < n; i++) info.mods.add(Protocol.getString(bb));
        return info;
    }

    static String safeName(String s) {
        String out = s.replaceAll("[^A-Za-z0-9_-]", "_");
        if (out.length() > 32) out = out.substring(0, 32);
        return out.isEmpty() ? "host" : out.toLowerCase(Locale.ROOT);
    }

    /** Deletes a folder, but only inside saves/ and only MultiSector downloads. */
    private static void deleteFolder(File dir) throws IOException {
        if (!dir.exists()) return;
        if (!dir.getName().startsWith(SAVE_PREFIX)) throw new IOException("refusing to delete " + dir);
        deleteTree(dir);
    }

    private static void deleteTree(File dir) throws IOException {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteTree(f);
                else if (!f.delete()) throw new IOException("Can't delete " + f + ". Is the game using it?");
            }
        }
        if (!dir.delete()) throw new IOException("Can't delete " + dir);
    }
}
