package multisector.launcher;

import multisector.BuildInfo;
import multisector.net.Frames;
import multisector.net.NetConfig;
import multisector.net.Protocol;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Friend side of the launcher: asks a host for its session, then downloads
 * the save.
 *
 * The download is split into byte ranges fetched over several connections
 * at once. On high-ping links (Hamachi relays, long distances) one TCP
 * connection spends most of its time waiting for acknowledgements, so
 * parallel connections use far more of the available bandwidth.
 */
final class LobbyClient {

    static final class Info {
        String address;
        int gamePort;
        String hostName;
        String characterName;
        String saveDate;
        String gameVersion;
        long saveBytes;
        long zipBytes;
        String zipHash;
        String hostVersion;
        /** The host's game is in the world and hosting: 1, not yet: 0, unknown (older launcher): -1. */
        int gameHosting = -1;
        List<String> mods = new ArrayList<String>();
        /** The host's enabled mods' versions (id -> version); empty from an older launcher. */
        java.util.Map<String, String> modVersions = new java.util.HashMap<String, String>();
    }

    interface Progress {
        /** Compressed bytes received so far, out of total. */
        void update(long done, long total);
    }

    static final class Result {
        final File folder;
        final boolean skipped;
        /** The save actually downloaded (the host saves a fresh copy for each download). */
        final String zipHash;
        final long zipBytes;

        Result(File folder, boolean skipped, String zipHash, long zipBytes) {
            this.folder = folder;
            this.skipped = skipped;
            this.zipHash = zipHash;
            this.zipBytes = zipBytes;
        }
    }

    /** Folder prefix for downloaded saves; only folders with this prefix are ever replaced. */
    static final String SAVE_PREFIX = "save_MultiSector_";
    /** Which host save each download folder holds, so unchanged saves aren't downloaded again. */
    private static final String DOWNLOADS = "multisector_downloads.json";
    /** Parallel connections. More than ~4 rarely helps and is less friendly to the host's upload. */
    private static final int MAX_STREAMS = Integer.getInteger("multisector.streams", 4); // property only for testing
    /**
     * Each connection gets at least this much, so tiny saves don't open
     * connections for nothing. Connections open concurrently, so the extra
     * ones cost about one round trip in total. On a link that caps speed per
     * connection (like a relay), a simulated 300 ms / 150 KB/s link went from
     * 14.5 s on one connection to 7.9 s on two.
     */
    private static final int MIN_PART = 256 * 1024;

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
            return hello(s, address, myName, Protocol.HELLO_FOR_CHECK);
        } finally {
            s.close();
        }
    }

    /**
     * Downloads the host's save into saves/save_MultiSector_&lt;host&gt;, replacing
     * an earlier download from the same host. If that earlier download is
     * already the same save, nothing is downloaded.
     */
    static Result download(GameInstall game, String address, String myName, final Progress progress) throws IOException {
        Socket first = connect(address);
        final List<Socket> sockets = new ArrayList<Socket>();
        sockets.add(first);
        try {
            final Info info = hello(first, address, myName, Protocol.HELLO_FOR_DOWNLOAD);
            String folderName = SAVE_PREFIX + safeName(info.hostName);
            File target = new File(game.saves, folderName);

            if (target.isDirectory() && info.zipHash.equals(downloadedHash(game, folderName))) {
                GameInstall.touchSaveDate(target);
                progress.update(info.zipBytes, info.zipBytes);
                return new Result(target, true, info.zipHash, info.zipBytes);
            }

            final long total = info.zipBytes;
            if (total > Integer.MAX_VALUE) throw new IOException("save too large");
            final byte[] data = new byte[(int) total];

            // First choice: small UDP pieces, which get through links that drop
            // full-size TCP packets. Falls back to TCP if UDP is blocked.
            boolean viaUdp = false;
            if (!Boolean.getBoolean("multisector.noUdp")) {
                String[] hp = splitAddress(address);
                try {
                    UdpTransfer.download(new InetSocketAddress(hp[0], NetConfig.lobbyPort(Integer.parseInt(hp[1]))),
                            info.zipHash, data, progress);
                    viaUdp = true;
                } catch (UdpTransfer.Blocked e) {
                    lastMethod = "TCP (UDP got no reply; the host may need to run tools\firewall.cmd again)";
                }
            }
            if (viaUdp) lastMethod = "UDP";
            if (!viaUdp) tcpDownload(info, address, myName, data, sockets, progress);
            if (!LobbyServer.sha256(data).equals(info.zipHash)) {
                throw new IOException("The download was corrupted on the way. Try again.");
            }

            File partial = new File(game.saves, folderName + ".partial");
            deleteFolder(partial);
            if (!partial.mkdirs()) throw new IOException("Can't create " + partial);
            unzip(data, partial);
            if (!new File(partial, "descriptor.xml").isFile()) throw new IOException("The host's save has no descriptor.xml.");

            if (target.exists()) deleteFolder(target);
            moveIntoPlace(partial, target);
            GameInstall.touchSaveDate(target);
            rememberDownload(game, folderName, info.zipHash);
            return new Result(target, false, info.zipHash, info.zipBytes);
        } finally {
            synchronized (sockets) {
                for (Socket s : sockets) closeQuietly(s);
            }
        }
    }

    /** Which method the last download used, for the launcher's log. */
    static volatile String lastMethod = "";

    /** Fallback: byte ranges over several parallel TCP connections. */
    private static void tcpDownload(final Info info, String address, String myName, final byte[] data,
                                    final List<Socket> sockets, final Progress progress) throws IOException {
        final long total = data.length;
        int streams = (int) Math.max(1, Math.min(MAX_STREAMS, total / MIN_PART));
        final AtomicLong done = new AtomicLong();
        final AtomicReference<IOException> failure = new AtomicReference<IOException>();
        List<Thread> workers = new ArrayList<Thread>();
        long partSize = (total + streams - 1) / streams;
        final String addr = address;
        final String me = myName;
        for (int i = 0; i < streams; i++) {
            final boolean reuseFirst = i == 0;
            final long off = i * partSize;
            final int len = (int) Math.max(0, Math.min(partSize, total - off));
            // Each worker opens its own connection, all at the same time, so the
            // extra connections cost one round trip in total rather than one each.
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        Socket s;
                        if (reuseFirst) {
                            s = sockets.get(0);
                        } else {
                            s = connect(addr);
                            synchronized (sockets) {
                                sockets.add(s);
                            }
                            hello(s, addr, me, Protocol.HELLO_FOR_EXTRA);
                        }
                        fetchRange(s, info.zipHash, off, len, data, done, progress, total);
                    } catch (IOException e) {
                        failure.compareAndSet(null, e);
                        synchronized (sockets) {
                            for (Socket other : sockets) closeQuietly(other); // stop the others too
                        }
                    }
                }
            }, "lobby-download-" + i);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
        for (Thread t : workers) {
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new IOException("interrupted");
            }
        }
        if (failure.get() != null) throw failure.get();
    }

    private static void fetchRange(Socket s, String hash, long offset, int length, byte[] into,
                                   AtomicLong done, Progress progress, long total) throws IOException {
        if (length == 0) return;
        ByteBuffer req = Protocol.begin(Protocol.LOBBY_GET_RANGE, Protocol.stringSize(hash) + 12);
        Protocol.putString(req, hash);
        req.putLong(offset).putInt(length).flip();
        Frames.write(s.getOutputStream(), req);

        s.setSoTimeout(60000);
        InputStream in = s.getInputStream();
        ByteBuffer head = Frames.read(in);
        if (head == null || Protocol.readHeader(head, null) != Protocol.LOBBY_RANGE
                || head.getLong() != offset || head.getInt() != length) {
            throw new IOException("The host sent an unexpected reply. Its save may have changed; try again.");
        }
        int pos = (int) offset;
        int end = pos + length;
        while (pos < end) {
            int n = in.read(into, pos, Math.min(64 * 1024, end - pos));
            if (n < 0) throw new IOException("The host closed the connection mid-download.");
            pos += n;
            progress.update(done.addAndGet(n), total);
        }
    }

    /**
     * Renames the finished download into place. Windows sometimes keeps a
     * just-written or just-deleted folder locked for a moment (antivirus,
     * search indexing), so retry briefly, then fall back to copying.
     */
    private static void moveIntoPlace(File partial, File target) throws IOException {
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    throw new IOException("interrupted");
                }
            }
        }
        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Can't create " + target);
        File[] files = partial.listFiles();
        if (files != null) {
            for (File f : files) {
                Files.copy(f.toPath(), new File(target, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        try {
            deleteFolder(partial);
        } catch (IOException ignored) {
            // A leftover .partial folder is harmless; it's cleared on the next download.
        }
    }

    private static void unzip(byte[] data, File dir) throws IOException {
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data));
        ZipEntry e;
        byte[] buf = new byte[64 * 1024];
        while ((e = zip.getNextEntry()) != null) {
            String name = new File(e.getName()).getName(); // no paths: never write outside the folder
            if (name.isEmpty() || e.isDirectory()) continue;
            OutputStream out = Files.newOutputStream(new File(dir, name).toPath());
            try {
                int n;
                while ((n = zip.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        }
    }

    // ------------------------------------------------------- download record

    private static String downloadedHash(GameInstall game, String folder) {
        try {
            String text = game.readCommon(DOWNLOADS);
            return text == null ? null : new JSONObject(text).optString(folder, null);
        } catch (Exception e) {
            return null;
        }
    }

    private static void rememberDownload(GameInstall game, String folder, String hash) {
        try {
            String text = game.readCommon(DOWNLOADS);
            JSONObject o = text == null ? new JSONObject() : new JSONObject(text);
            o.put(folder, hash);
            game.writeCommon(DOWNLOADS, o.toString(2));
        } catch (Exception ignored) {
            // Only costs a re-download next time.
        }
    }

    // ------------------------------------------------------------ connection

    private static Socket connect(String address) throws IOException {
        String[] hp = splitAddress(address);
        int lobby = NetConfig.lobbyPort(Integer.parseInt(hp[1]));
        Socket s = new Socket();
        try {
            s.setReceiveBufferSize(LobbyServer.BUFFER); // must be set before connecting to take effect
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(hp[0], lobby), NetConfig.CONNECT_TIMEOUT_MS);
            // The host may need a few seconds to compress a large save on first request.
            s.setSoTimeout(60000);
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

    private static Info hello(Socket s, String address, String myName, byte purpose) throws IOException {
        ByteBuffer hello = Protocol.begin(Protocol.LOBBY_HELLO,
                Protocol.stringSize(myName) + Protocol.stringSize(BuildInfo.VERSION) + 1);
        Protocol.putString(hello, myName);
        Protocol.putString(hello, BuildInfo.VERSION);
        hello.put(purpose);
        hello.flip();
        Frames.write(s.getOutputStream(), hello);
        // A download first waits for the host's game to save a fresh copy.
        if (purpose == Protocol.HELLO_FOR_DOWNLOAD) s.setSoTimeout(90000);
        ByteBuffer bb = Frames.read(s.getInputStream());
        short[] ver = new short[1];
        if (bb == null) throw new IOException("The host closed the connection. Their launcher may be a different MultiSector version.");
        int type = Protocol.readHeader(bb, ver);
        if (ver[0] != NetConfig.PROTOCOL_VERSION) {
            String theirs = type == Protocol.LOBBY_INFO ? readHostVersion(bb) : "";
            throw new VersionMismatch("The host has a different MultiSector build" + (theirs.isEmpty() ? "" : " (" + theirs + ")")
                    + "; you have " + BuildInfo.VERSION + ". You both need the same build.", theirs);
        }
        if (type != Protocol.LOBBY_INFO) throw new IOException("Something answered, but it isn't a MultiSector launcher.");
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
        info.zipBytes = bb.getLong();
        info.zipHash = Protocol.getString(bb);
        info.hostVersion = bb.remaining() >= 2 ? Protocol.getString(bb) : "?";
        if (bb.remaining() >= 1) info.gameHosting = bb.get() != 0 ? 1 : 0;
        if (bb.remaining() >= 4) {
            int nv = bb.getInt();
            for (int i = 0; i < nv && i < 1000 && bb.remaining() >= 4; i++) {
                String id = Protocol.getString(bb);
                info.modVersions.put(id, Protocol.getString(bb));
            }
        }
        if (!BuildInfo.VERSION.equals(info.hostVersion)) {
            throw new VersionMismatch("Different MultiSector builds: the host has " + info.hostVersion + ", you have "
                    + BuildInfo.VERSION + ". You both need the same build.", "?".equals(info.hostVersion) ? "" : info.hostVersion);
        }
        return info;
    }

    /** The host runs another MultiSector build. hostVersion is theirs, or "" if it couldn't be read. */
    static final class VersionMismatch extends IOException {
        private static final long serialVersionUID = 1L;
        final String hostVersion;

        VersionMismatch(String message, String hostVersion) {
            super(message);
            this.hostVersion = hostVersion == null ? "" : hostVersion;
        }
    }

    /** The host's build from a LOBBY_INFO of another protocol version, if its layout is still readable. */
    private static String readHostVersion(ByteBuffer bb) {
        try {
            for (int i = 0; i < 4; i++) Protocol.getString(bb); // host, character, save date, game version
            bb.getLong();
            bb.getInt();
            int n = bb.getInt();
            if (n < 0 || n > 1000) return "";
            for (int i = 0; i < n; i++) Protocol.getString(bb);
            bb.getLong();
            Protocol.getString(bb);
            String v = bb.remaining() >= 2 ? Protocol.getString(bb) : "";
            return v.matches("[0-9A-Za-z.-]{1,32}") ? v : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Can this PC reach the host's game port? (Only meaningful while their game is hosting.) */
    static boolean canReachGame(String host, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 3000);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    static String safeName(String s) {
        String out = s.replaceAll("[^A-Za-z0-9_-]", "_");
        if (out.length() > 32) out = out.substring(0, 32);
        return out.isEmpty() ? "host" : out.toLowerCase(Locale.ROOT);
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
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
