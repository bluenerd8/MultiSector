package multisector.launcher;

import multisector.BuildInfo;
import multisector.net.Frames;
import multisector.net.NetConfig;
import multisector.net.Protocol;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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
                + 8 + Protocol.stringSize(snap.hash) + Protocol.stringSize(BuildInfo.VERSION);
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
