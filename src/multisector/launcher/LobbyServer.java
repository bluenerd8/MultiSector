package multisector.launcher;

import multisector.BuildInfo;
import multisector.net.Frames;
import multisector.net.NetConfig;
import multisector.net.Protocol;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Host side of the launcher: shares one save folder with friends on the
 * lobby port (game port + 1).
 *
 * The save is zipped once into memory and reused for every friend. If the
 * host saves again in the game, the next request re-zips it, so friends
 * always get the latest file. Friends download it as several byte ranges
 * over parallel connections, which is much faster on high-ping links.
 */
final class LobbyServer {

    interface Listener {
        void log(String line);
    }

    /** Socket buffer size; big buffers keep more data in flight on high-ping links. */
    static final int BUFFER = 4 * 1024 * 1024;

    private final String hostName;
    private final GameInstall.SaveInfo save;
    private final List<String> mods;
    private final int gamePort;
    private final Listener listener;
    private volatile ServerSocket server;
    private volatile DatagramSocket udp;

    // The zipped save, rebuilt when the files change.
    private byte[] blob;
    private String blobHash;
    private long blobStamp = -1;
    private long rawBytes;

    LobbyServer(String hostName, GameInstall.SaveInfo save, List<String> mods, int gamePort, Listener listener) {
        this.hostName = hostName;
        this.save = save;
        this.mods = mods;
        this.gamePort = gamePort;
        this.listener = listener;
    }

    void start() throws IOException {
        int port = NetConfig.lobbyPort(gamePort);
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.setReceiveBufferSize(BUFFER);
        try {
            s.bind(NetConfig.listenAddress(port));
        } catch (IOException e) {
            s.close();
            throw new IOException("Port " + port + " is already in use. Is another MultiSector launcher open?");
        }
        server = s;
        // UDP on the same port number: the save in small pieces (see Protocol.XFER_GET).
        try {
            DatagramSocket u = new DatagramSocket(NetConfig.listenAddress(port));
            u.setSendBufferSize(BUFFER);
            udp = u;
            background("lobby-udp", new Runnable() {
                public void run() {
                    udpLoop();
                }
            });
        } catch (IOException e) {
            listener.log("UDP port " + port + " is busy; friends will download over the slower TCP method.");
        }
        // Zip now, so the first friend doesn't wait for it.
        background("lobby-prepare", new Runnable() {
            public void run() {
                try {
                    ensureBlob();
                } catch (IOException e) {
                    listener.log("Couldn't read the save: " + e.getMessage());
                }
            }
        });
        background("lobby-accept", new Runnable() {
            public void run() {
                acceptLoop();
            }
        });
    }

    void stop() {
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        DatagramSocket u = udp;
        udp = null;
        if (u != null) u.close();
    }

    /**
     * Answers piece requests: for each requested index, send that 1 KB piece of
     * the zipped save. Stateless, so lost requests or replies just get asked again.
     */
    private void udpLoop() {
        byte[] in = new byte[2048];
        Set<String> started = new HashSet<String>();
        while (udp != null) {
            DatagramSocket u = udp;
            try {
                DatagramPacket p = new DatagramPacket(in, in.length);
                u.receive(p);
                ByteBuffer bb = ByteBuffer.wrap(in, 0, p.getLength());
                if (Protocol.readHeader(bb, null) != Protocol.XFER_GET) continue;
                long key = bb.getLong();
                int count = Math.min(bb.getInt(), Protocol.XFER_MAX_REQUEST);
                Snapshot snap = ensureBlob();
                if (Protocol.saveKey(snap.hash) != key) continue; // an older save: the friend will re-check
                int pieces = (snap.data.length + Protocol.XFER_CHUNK - 1) / Protocol.XFER_CHUNK;
                if (started.add(p.getAddress().getHostAddress() + "/" + snap.hash)) {
                    listener.log(p.getAddress().getHostAddress() + " is downloading the save ("
                            + GameInstall.megabytes(snap.data.length) + " compressed, in small UDP pieces)...");
                }
                byte[] out = new byte[Protocol.HEADER + 16 + Protocol.XFER_CHUNK];
                for (int i = 0; i < count && bb.remaining() >= 4; i++) {
                    int idx = bb.getInt();
                    if (idx < 0 || idx >= pieces) continue;
                    int off = idx * Protocol.XFER_CHUNK;
                    int len = Math.min(Protocol.XFER_CHUNK, snap.data.length - off);
                    ByteBuffer ob = ByteBuffer.wrap(out);
                    ob.putInt(NetConfig.MAGIC).putShort(NetConfig.PROTOCOL_VERSION).put(Protocol.XFER_DATA);
                    ob.putLong(key).putInt(idx).putInt(len).put(snap.data, off, len);
                    u.send(new DatagramPacket(out, ob.position(), p.getSocketAddress()));
                }
            } catch (IOException e) {
                if (udp == null) return; // stopped
            } catch (RuntimeException e) {
                // malformed request; ignore
            }
        }
    }

    boolean isRunning() {
        return server != null;
    }

    private void acceptLoop() {
        while (server != null) {
            try {
                final Socket c = server.accept();
                background("lobby-client", new Runnable() {
                    public void run() {
                        serve(c);
                    }
                });
            } catch (IOException e) {
                return; // closed by stop()
            }
        }
    }

    private void serve(Socket c) {
        String who = c.getInetAddress().getHostAddress();
        try {
            c.setTcpNoDelay(true);
            c.setSendBufferSize(BUFFER);
            c.setSoTimeout(60000);
            ByteBuffer hello = Frames.read(c.getInputStream());
            if (hello == null || Protocol.readHeader(hello, null) != Protocol.LOBBY_HELLO) return;
            String name = Protocol.cleanName(Protocol.getString(hello));
            who = name;
            String theirBuild = hello.remaining() >= 2 ? Protocol.getString(hello) : "an older build";
            if (!BuildInfo.VERSION.equals(theirBuild)) {
                // Checked on their side; their launcher offers them this build.
                listener.log(name + " has MultiSector " + theirBuild + " and you have " + BuildInfo.VERSION
                        + ". Their launcher offers them your build (Get the host's build).");
            }
            byte purpose = hello.remaining() >= 1 ? hello.get() : Protocol.HELLO_FOR_CHECK;
            if (purpose == Protocol.HELLO_FOR_DOWNLOAD) freshSave(name);

            Snapshot snap = ensureBlob();
            Frames.write(c.getOutputStream(), info(snap));

            // Then any number of range requests until the friend hangs up.
            OutputStream out = c.getOutputStream();
            while (true) {
                ByteBuffer req = Frames.read(c.getInputStream());
                if (req == null) return;
                if (Protocol.readHeader(req, null) != Protocol.LOBBY_GET_RANGE) return;
                String hash = Protocol.getString(req);
                long offset = req.getLong();
                int length = req.getInt();
                Snapshot now = ensureBlob();
                if (!now.hash.equals(hash)) {
                    throw new IOException("the save changed during the download; ask them to try again");
                }
                if (offset < 0 || length < 0 || offset + length > now.data.length) throw new IOException("bad range");

                long t0 = System.nanoTime();
                ByteBuffer head = Protocol.begin(Protocol.LOBBY_RANGE, 12);
                head.putLong(offset).putInt(length).flip();
                Frames.write(out, head);
                out.write(now.data, (int) offset, length);
                out.flush();
                if (offset == 0) {
                    listener.log(who + " is downloading the save (" + GameInstall.megabytes(now.data.length)
                            + " compressed)...");
                }
                double secs = (System.nanoTime() - t0) / 1e9;
                if (length >= now.data.length / 2 || secs > 2) {
                    listener.log(String.format(Locale.ROOT, "%s: sent %s in %.1f s", who,
                            GameInstall.megabytes(length), secs));
                }
            }
        } catch (IOException e) {
            listener.log(who + ": transfer stopped (" + e.getMessage() + ")");
        } finally {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Before a friend downloads: ask this PC's game to save now, so they get
     * the world as it is rather than as it was at the last save, then wait
     * for the files to settle. If the game isn't hosting, or can't save,
     * they get the last save, and the shared clock catches them up anyway.
     * One at a time, so two friends joining together don't save twice at once.
     */
    private void freshSave(String friend) {
        // Not the object's own lock: ensureBlob uses that, and other friends'
        // downloads must keep going while this waits.
        synchronized (saveLock) {
            freshSaveLocked(friend);
        }
    }

    private final Object saveLock = new Object();

    private void freshSaveLocked(String friend) {
        long before;
        try {
            before = stamp(saveFiles());
        } catch (IOException e) {
            return;
        }
        listener.log(friend + " wants to download. Asking the game for a fresh save...");
        GameLink.Result r = GameLink.requestSave(gamePort, friend);
        if (!r.saved) {
            listener.log("Sharing the last save instead: " + r.message + ".");
            return;
        }
        // The game may still be writing. Wait for the files to change, then to stop changing.
        long deadline = System.currentTimeMillis() + 30000;
        long last = before;
        long stableSince = 0;
        while (System.currentTimeMillis() < deadline) {
            sleepQuietly(250);
            long now;
            try {
                now = stamp(saveFiles());
            } catch (IOException e) {
                continue; // mid-write
            }
            if (now != last) {
                last = now;
                stableSince = System.currentTimeMillis();
            } else if (now != before && System.currentTimeMillis() - stableSince >= 1500) {
                listener.log("Fresh save ready.");
                return;
            }
        }
        listener.log(last == before ? "The game said it saved, but the files didn't change; sharing them as they are."
                : "The save is still changing after 30 s; sharing it as it is now.");
    }

    private static long stamp(File[] files) {
        long stamp = 0;
        for (File f : files) stamp = Math.max(stamp, f.lastModified()) * 31 + f.length();
        return stamp;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Snapshot {
        final byte[] data;
        final String hash;
        final long rawBytes;

        Snapshot(byte[] data, String hash, long rawBytes) {
            this.data = data;
            this.hash = hash;
            this.rawBytes = rawBytes;
        }
    }

    /** The zipped save, re-zipped if the host saved again since last time. */
    private synchronized Snapshot ensureBlob() throws IOException {
        File[] files = saveFiles();
        long stamp = 0;
        long raw = 0;
        for (File f : files) {
            stamp = Math.max(stamp, f.lastModified()) * 31 + f.length();
            raw += f.length();
        }
        if (blob == null || stamp != blobStamp) {
            long t0 = System.currentTimeMillis();
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(raw / 3 + 1024, Integer.MAX_VALUE));
            ZipOutputStream zip = new ZipOutputStream(bos);
            zip.setLevel(6);
            for (File f : files) {
                zip.putNextEntry(new ZipEntry(f.getName()));
                Files.copy(f.toPath(), zip);
                zip.closeEntry();
            }
            zip.finish();
            blob = bos.toByteArray();
            blobHash = sha256(blob);
            blobStamp = stamp;
            rawBytes = raw;
            listener.log("Save ready to share: " + GameInstall.megabytes(raw) + ", compressed to "
                    + GameInstall.megabytes(blob.length) + " (" + (System.currentTimeMillis() - t0) + " ms).");
        }
        return new Snapshot(blob, blobHash, rawBytes);
    }

    private ByteBuffer info(Snapshot snap) {
        int size = Protocol.stringSize(hostName) + Protocol.stringSize(save.characterName)
                + Protocol.stringSize(save.saveDate) + Protocol.stringSize(save.gameVersion) + 8 + 4 + 4
                + 8 + Protocol.stringSize(snap.hash) + Protocol.stringSize(BuildInfo.VERSION) + 1;
        for (String m : mods) size += Protocol.stringSize(m);
        ByteBuffer bb = Protocol.begin(Protocol.LOBBY_INFO, size);
        Protocol.putString(bb, hostName);
        Protocol.putString(bb, save.characterName);
        Protocol.putString(bb, save.saveDate);
        Protocol.putString(bb, save.gameVersion);
        bb.putLong(snap.rawBytes);
        bb.putInt(gamePort);
        bb.putInt(mods.size());
        for (String m : mods) Protocol.putString(bb, m);
        bb.putLong(snap.data.length);
        Protocol.putString(bb, snap.hash);
        Protocol.putString(bb, BuildInfo.VERSION);
        bb.put((byte) (GameLink.isHosting(gamePort) ? 1 : 0)); // is our game in the world and hosting?
        bb.flip();
        return bb;
    }

    private File[] saveFiles() throws IOException {
        File[] files = save.folder.listFiles();
        if (files == null) throw new IOException("save folder is gone: " + save.folder);
        List<File> out = new ArrayList<File>();
        for (File f : files) if (f.isFile()) out.add(f);
        return out.toArray(new File[0]);
    }

    static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void background(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }
}
