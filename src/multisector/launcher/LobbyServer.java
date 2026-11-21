package multisector.launcher;

import multisector.net.Frames;
import multisector.net.NetConfig;
import multisector.net.Protocol;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Host side of the launcher: shares one save folder with friends on the
 * lobby port (game port + 1). Each friend gets the session info, then the
 * save as a zip stream.
 */
final class LobbyServer {

    interface Listener {
        void log(String line);
    }

    private final String hostName;
    private final GameInstall.SaveInfo save;
    private final List<String> mods;
    private final int gamePort;
    private final Listener listener;
    private volatile ServerSocket server;

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
        try {
            s.bind(NetConfig.listenAddress(port));
        } catch (IOException e) {
            s.close();
            throw new IOException("Port " + port + " is already in use. Is another MultiSector launcher open?");
        }
        server = s;
        Thread t = new Thread(new Runnable() {
            public void run() {
                acceptLoop();
            }
        }, "lobby-accept");
        t.setDaemon(true);
        t.start();
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
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        serve(c);
                    }
                }, "lobby-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return; // closed by stop()
            }
        }
    }

    private void serve(Socket c) {
        String who = c.getInetAddress().getHostAddress();
        try {
            c.setSoTimeout(30000);
            ByteBuffer hello = Frames.read(c.getInputStream());
            if (hello == null || Protocol.readHeader(hello, null) != Protocol.LOBBY_HELLO) return;
            String name = Protocol.cleanName(Protocol.getString(hello));
            who = name + " (" + who + ")";

            Frames.write(c.getOutputStream(), info());
            ByteBuffer req = Frames.read(c.getInputStream());
            if (req == null || Protocol.readHeader(req, null) != Protocol.LOBBY_GET_SAVE) {
                listener.log(who + " checked the session");
                return;
            }

            File[] files = saveFiles();
            long total = 0;
            for (File f : files) total += f.length();
            ByteBuffer begin = Protocol.begin(Protocol.LOBBY_SAVE_BEGIN, 12);
            begin.putLong(total).putInt(files.length).flip();
            Frames.write(c.getOutputStream(), begin);

            listener.log(who + " is downloading the save (" + GameInstall.megabytes(total) + ")...");
            long t0 = System.currentTimeMillis();
            OutputStream raw = new BufferedOutputStream(c.getOutputStream(), 64 * 1024);
            ZipOutputStream zip = new ZipOutputStream(raw);
            zip.setLevel(6);
            for (File f : files) {
                zip.putNextEntry(new ZipEntry(f.getName()));
                Files.copy(f.toPath(), zip);
                zip.closeEntry();
            }
            zip.finish();
            zip.flush();
            c.shutdownOutput();
            // Wait for the friend to close, so we know the bytes arrived.
            c.setSoTimeout(60000);
            while (c.getInputStream().read() >= 0) {
                // drain
            }
            listener.log(who + " got the save in " + ((System.currentTimeMillis() - t0) / 1000.0) + " s");
        } catch (IOException e) {
            listener.log(who + ": transfer failed (" + e.getMessage() + ")");
        } finally {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    private ByteBuffer info() {
        int size = Protocol.stringSize(hostName) + Protocol.stringSize(save.characterName)
                + Protocol.stringSize(save.saveDate) + Protocol.stringSize(save.gameVersion) + 8 + 4 + 4;
        for (String m : mods) size += Protocol.stringSize(m);
        ByteBuffer bb = Protocol.begin(Protocol.LOBBY_INFO, size);
        Protocol.putString(bb, hostName);
        Protocol.putString(bb, save.characterName);
        Protocol.putString(bb, save.saveDate);
        Protocol.putString(bb, save.gameVersion);
        bb.putLong(save.sizeBytes);
        bb.putInt(gamePort);
        bb.putInt(mods.size());
        for (String m : mods) Protocol.putString(bb, m);
        bb.flip();
        return bb;
    }

    private File[] saveFiles() throws IOException {
        File[] files = save.folder.listFiles();
        if (files == null) throw new IOException("save folder is gone: " + save.folder);
        java.util.List<File> out = new java.util.ArrayList<File>();
        for (File f : files) if (f.isFile()) out.add(f);
        return out.toArray(new File[0]);
    }
}
