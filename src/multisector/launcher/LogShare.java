package multisector.launcher;

import multisector.net.Frames;
import multisector.net.Protocol;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Everyone's game log, on everyone's PC: so a crash on a friend's game can
 * be read on the host's, and the other way round.
 *
 * <ul>
 * <li>A friend's launcher (one that has joined someone) sends the new part of
 *     its starsector.log to the host's launcher every few minutes, at once
 *     when the log shows a crash, and when the game closes. Only what helps:
 *     MultiSector's lines, warnings, errors and their stack traces.</li>
 * <li>The host's launcher keeps it in {@code Starsector/multisector_logs},
 *     and answers with the new part of its own log, which the friend keeps
 *     there too.</li>
 * </ul>
 * The folder keeps the newest 60 files.
 */
final class LogShare {

    interface Listener {
        void log(String line);
    }

    interface Target {
        /** The host's address to send to, or null (not joined anyone). */
        String hostAddress();

        String myName();
    }

    /** How often the log is looked at, and sent at most. */
    static final long POLL_MS = 15_000L;
    static final long PERIOD_MS = 5L * 60L * 1000L;
    /** The first time: this much of the end of the log (this session so far, usually). */
    private static final long FIRST_BYTES = 4L * 1024L * 1024L;
    /** Most of the filtered log sent in one go (the newest part). */
    private static final int MAX_CHARS = 3 * 1024 * 1024;
    private static final int KEEP_FILES = 60;

    private final GameInstall game;
    private final Listener listener;
    private volatile Target target;
    private long offset = -1;
    private final StringBuilder pending = new StringBuilder();
    private boolean pendingCrash;
    private long lastSent;
    private boolean wasRunning;
    private volatile boolean stopped;
    /** Host: how far into our log each friend has had (by name). */
    private final Map<String, Long> sentTo = new HashMap<String, Long>();

    LogShare(GameInstall game, Listener listener) {
        this.game = game;
        this.listener = listener;
    }

    void setTarget(Target t) {
        target = t;
    }

    File logFile() {
        return new File(new File(game.root, "starsector-core"), "starsector.log");
    }

    File folder() {
        return new File(game.root, "multisector_logs");
    }

    /** The friend side: looks at the log every few seconds, and sends when it's time. */
    void start() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (!stopped) {
                    try {
                        tick(System.currentTimeMillis());
                    } catch (Exception e) {
                        // try again next time
                    }
                    try {
                        Thread.sleep(POLL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "multisector-logs");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        stopped = true;
    }

    synchronized void tick(long now) throws IOException {
        File f = logFile();
        if (f.isFile()) {
            long size = f.length();
            if (offset < 0) offset = Math.max(0L, size - FIRST_BYTES);
            if (size < offset) offset = 0L; // the game started a new log
            if (size > offset) {
                String fresh = read(f, offset, size);
                offset = size;
                String kept = filter(fresh);
                if (kept.length() > 0) pending.append(kept);
                if (crashed(fresh)) pendingCrash = true;
                trim(pending);
            }
        }
        boolean running = game.isGameRunning();
        boolean closed = wasRunning && !running;
        wasRunning = running;
        Target t = target;
        String address = t == null ? null : t.hostAddress();
        if (address == null || address.trim().isEmpty()) return;
        boolean due = now - lastSent >= PERIOD_MS && pending.length() > 0;
        if (!(pendingCrash || closed || due)) return;
        String reason = pendingCrash ? "crash" : closed ? "game-closed" : "periodic";
        exchange(address.trim(), t.myName(), reason, pending.toString());
        pending.setLength(0);
        pendingCrash = false;
        lastSent = now;
    }

    /** Sends ours, keeps the host's answer. */
    void exchange(String address, String myName, String reason, String text) throws IOException {
        String[] hp = LobbyClient.splitAddress(address);
        Socket s = new Socket();
        try {
            s.connect(new java.net.InetSocketAddress(hp[0], multisector.net.NetConfig.lobbyPort(Integer.parseInt(hp[1]))), 8000);
            s.setSoTimeout(30000);
            ByteBuffer hello = Protocol.begin(Protocol.LOBBY_HELLO,
                    Protocol.stringSize(myName) + Protocol.stringSize(multisector.BuildInfo.VERSION) + 1);
            Protocol.putString(hello, myName);
            Protocol.putString(hello, multisector.BuildInfo.VERSION);
            hello.put(Protocol.HELLO_FOR_LOG);
            hello.flip();
            Frames.write(s.getOutputStream(), hello);
            Frames.write(s.getOutputStream(), frame(reason, text));
            ByteBuffer back = Frames.read(s.getInputStream());
            if (back == null || Protocol.readHeader(back, null) != Protocol.LOBBY_LOG) return;
            String[] got = unframe(back);
            if (got[1].length() > 0) keep("host-" + safe(got[2]) + "-" + got[0], got[1]);
            listener.log("Shared game logs with the host (" + reason + ").");
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** The host side, on a lobby connection that said HELLO_FOR_LOG: keep theirs, answer with ours. */
    synchronized void serve(Socket c, String friend) throws IOException {
        ByteBuffer in = Frames.read(c.getInputStream());
        if (in == null || Protocol.readHeader(in, null) != Protocol.LOBBY_LOG) return;
        String[] got = unframe(in);
        if (got[1].length() > 0) {
            keep(safe(friend) + "-" + got[0], got[1]);
            if ("crash".equals(got[0])) listener.log(friend + "'s game crashed: their log is in " + folder().getPath());
        }
        // Ours since the last time they had it.
        String ours = "";
        File f = logFile();
        if (f.isFile()) {
            long size = f.length();
            Long from = sentTo.get(friend);
            long start = from == null ? Math.max(0L, size - FIRST_BYTES) : from > size ? 0L : from;
            ours = filter(read(f, start, size));
            sentTo.put(friend, size);
            StringBuilder b = new StringBuilder(ours);
            trim(b);
            ours = b.toString();
        }
        Frames.write(c.getOutputStream(), frame("host", ours, hostName));
    }

    private volatile String hostName = "host";

    void setHostName(String name) {
        hostName = name == null || name.isEmpty() ? "host" : name;
    }

    // ================================================================ the log itself

    private static String read(File f, long from, long to) throws IOException {
        long len = Math.min(to - from, 64L * 1024L * 1024L);
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            r.seek(to - len);
            byte[] b = new byte[(int) len];
            r.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        } finally {
            r.close();
        }
    }

    /**
     * What helps reading a problem: MultiSector's lines, warnings, errors,
     * crashes, and the stack traces under them. (Not the thousands of lines
     * of the game loading its files.)
     */
    static String filter(String text) {
        StringBuilder out = new StringBuilder();
        boolean keeping = false;
        for (String line : text.split("\r?\n")) {
            if (line.matches("^\\d+ \\[.*")) {
                // A new entry: kept if it's one of ours, a warning, an error or a crash...
                keeping = line.contains("MultiSector") || line.contains("multisector") || line.contains(" ERROR ")
                        || line.contains(" WARN ") || line.contains("Exception") || line.contains("Fatal")
                        || line.contains("StarfarerLauncher") || line.contains("CampaignGameManager");
            }
            // ...with everything under it (the stack trace).
            if (keeping) out.append(line).append('\n');
        }
        return out.toString();
    }

    static boolean crashed(String text) {
        return text.contains("Fatal") || text.contains("ERROR com.fs.starfarer.combat.CombatMain")
                || text.contains("OutOfMemoryError") || text.contains("StackOverflowError");
    }

    private static void trim(StringBuilder b) {
        if (b.length() > MAX_CHARS) b.delete(0, b.length() - MAX_CHARS);
    }

    // ================================================================ files

    /** Writes one received log, and keeps the folder to the newest few. */
    void keep(String name, String text) throws IOException {
        File dir = folder();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't make " + dir);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File out = new File(dir, stamp + "-" + name + ".log");
        Files.write(out.toPath(), text.getBytes(StandardCharsets.UTF_8));
        File[] all = dir.listFiles();
        if (all == null || all.length <= KEEP_FILES) return;
        Arrays.sort(all, new Comparator<File>() {
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (int i = 0; i < all.length - KEEP_FILES; i++) {
            if (all[i].getName().endsWith(".log") && !all[i].delete()) break;
        }
    }

    static String safe(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : (s == null ? "" : s).toCharArray()) b.append(Character.isLetterOrDigit(c) || c == '-' ? c : '_');
        return b.length() == 0 ? "player" : b.toString();
    }

    // ================================================================ wire

    private static ByteBuffer frame(String reason, String text) {
        return frame(reason, text, "");
    }

    /** reason, sender name, raw length, deflated text. */
    private static ByteBuffer frame(String reason, String text, String name) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        Deflater d = new Deflater(6);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream(raw.length / 6 + 64);
        byte[] buf = new byte[64 * 1024];
        while (!d.finished()) z.write(buf, 0, d.deflate(buf));
        d.end();
        byte[] zb = z.toByteArray();
        ByteBuffer bb = Protocol.begin(Protocol.LOBBY_LOG,
                Protocol.stringSize(reason) + Protocol.stringSize(name) + 8 + zb.length);
        Protocol.putString(bb, reason);
        Protocol.putString(bb, name);
        bb.putInt(raw.length).putInt(zb.length).put(zb);
        bb.flip();
        return bb;
    }

    /** {reason, text, sender name}. */
    private static String[] unframe(ByteBuffer bb) throws IOException {
        String reason = Protocol.getString(bb);
        String name = Protocol.getString(bb);
        int rawLen = bb.getInt();
        int zLen = bb.getInt();
        if (rawLen < 0 || rawLen > 32 * 1024 * 1024 || zLen < 0 || zLen > bb.remaining()) throw new IOException("bad log");
        byte[] zb = new byte[zLen];
        bb.get(zb);
        Inflater inf = new Inflater();
        inf.setInput(zb);
        byte[] raw = new byte[rawLen];
        try {
            int n = 0;
            while (n < rawLen && !inf.finished()) {
                int k = inf.inflate(raw, n, rawLen - n);
                if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                n += k;
            }
        } catch (DataFormatException e) {
            throw new IOException("bad log data");
        } finally {
            inf.end();
        }
        return new String[]{safe(reason), new String(raw, StandardCharsets.UTF_8), name};
    }
}
