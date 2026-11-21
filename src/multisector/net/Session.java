package multisector.net;

import java.io.IOException;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one multiplayer session for this game instance: idle, hosting, or
 * joined to a host. Right now it handles connecting, the player list and
 * ping. Game-state sync comes later and builds on top of this.
 *
 * Networking runs on background threads. The game thread only reads the
 * volatile fields and drains {@link #pollNotice()}, so nothing here blocks
 * the game. No game classes are used, so the standalone tester can run it too.
 */
public final class Session {

    public enum State { IDLE, HOSTING, CONNECTING, CONNECTED }

    public static final class Player {
        public final int id;
        public final String name;
        public final int pingMs;
        public final boolean isHost;

        Player(int id, String name, int pingMs, boolean isHost) {
            this.id = id;
            this.name = name;
            this.pingMs = pingMs;
            this.isHost = isHost;
        }
    }

    public static final int MAX_PLAYERS = 16;
    private static final int HOST_ID = 0;
    /** No traffic for this long means the other side is gone. Pings flow every second. */
    private static final int IDLE_TIMEOUT_MS = 15000;
    private static final int LIST_BROADCAST_MS = 2000;
    private static final int PING_INTERVAL_MS = 1000;

    private static final Session INSTANCE = new Session();

    public static Session get() {
        return INSTANCE;
    }

    // ---- state read by the UI
    private volatile State state = State.IDLE;
    private volatile String status = "Not connected";
    private volatile String lastError = null;
    private volatile List<Player> players = Collections.emptyList();
    private volatile int port = NetConfig.DEFAULT_PORT;
    private volatile String remote = null;
    private volatile int pingMs = -1;
    private final ConcurrentLinkedQueue<String> notices = new ConcurrentLinkedQueue<String>();

    /** Bumped on every start and stop. Threads from an older session see the change and exit. */
    private volatile int generation = 0;
    private String localName = "Player";

    // ---- host side
    private ServerSocket server;
    private DatagramSocket hostUdp;
    private final List<Conn> conns = new CopyOnWriteArrayList<Conn>();
    private int nextId = 1;

    // ---- client side
    private Conn toHost;
    private DatagramSocket clientUdp;
    private volatile int myId = -1;

    private Session() {
    }

    // ================================================================ public API

    public State getState() { return state; }
    public String getStatus() { return status; }
    /** Last failure, or null. Cleared when a new host/join starts. */
    public String getLastError() { return lastError; }
    public List<Player> getPlayers() { return players; }
    public int getPort() { return port; }
    /** Client: the host address we're connected to. */
    public String getRemote() { return remote; }
    /** Client: last measured round trip to the host, or -1. */
    public int getPingMs() { return pingMs; }
    public int getMyId() { return myId; }
    public boolean isActive() { return state != State.IDLE; }

    /** Next "X joined"-style message for the game to show, or null. */
    public String pollNotice() {
        return notices.poll();
    }

    /**
     * Starts hosting. Binds on the calling thread so "port already in use"
     * is reported straight away.
     */
    public synchronized void host(int port, String name) throws IOException {
        if (state != State.IDLE) throw new IllegalStateException("already " + state);
        lastError = null;
        localName = Protocol.cleanName(name);
        ServerSocket s = new ServerSocket();
        DatagramSocket u = null;
        try {
            s.setReuseAddress(true);
            s.bind(NetConfig.listenAddress(port));
            u = new DatagramSocket(NetConfig.listenAddress(port));
        } catch (IOException e) {
            closeQuietly(s);
            if (u != null) u.close();
            throw new IOException("Port " + port + " is already in use. Is another copy of the game or "
                    + "\"dev nettest host\" running?", e);
        }
        server = s;
        hostUdp = u;
        nextId = 1;
        conns.clear();
        this.port = port;
        remote = null;
        myId = HOST_ID;
        final int gen = ++generation;
        state = State.HOSTING;
        status = "Hosting on port " + port;
        refreshHostPlayers();

        startThread("MultiSector-accept", new Runnable() { public void run() { acceptLoop(gen); } });
        startThread("MultiSector-udp", new Runnable() { public void run() { udpEchoLoop(gen); } });
        startThread("MultiSector-list", new Runnable() { public void run() { listBroadcastLoop(gen); } });
    }

    /**
     * Starts joining. Returns immediately; watch {@link #getState()}.
     * The address may include a port ("25.1.2.3:7777"), which overrides the port argument.
     */
    public synchronized void join(String address, int port, String name) {
        if (state != State.IDLE) throw new IllegalStateException("already " + state);
        lastError = null;
        localName = Protocol.cleanName(name);
        String host = address == null ? "" : address.trim();
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) { // one colon: ip:port (not IPv6)
            try {
                port = Integer.parseInt(host.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
            }
            host = host.substring(0, colon).trim();
        }
        final String targetHost = host;
        final int targetPort = port;
        this.port = port;
        remote = host + ":" + port;
        pingMs = -1;
        myId = -1;
        final int gen = ++generation;
        state = State.CONNECTING;
        status = "Connecting to " + remote + "...";
        players = Collections.emptyList();
        startThread("MultiSector-connect", new Runnable() {
            public void run() { clientMain(gen, targetHost, targetPort); }
        });
    }

    /** Stops hosting or disconnects. Safe to call in any state. */
    public void stop() {
        stopWith(generation, null, "Not connected");
    }

    // ================================================================ host

    private void acceptLoop(int gen) {
        while (alive(gen)) {
            try {
                final Socket s = server.accept();
                startThread("MultiSector-client", new Runnable() { public void run() { hostClient(gen, s); } });
            } catch (IOException e) {
                if (alive(gen)) stopWith(gen, "Hosting stopped: " + e.getMessage(), "Not connected");
            }
        }
    }

    private void hostClient(int gen, Socket s) {
        Conn conn = null;
        String from = s.getInetAddress().getHostAddress();
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(NetConfig.CONNECT_TIMEOUT_MS);
            ByteBuffer hello = Frames.read(s.getInputStream());
            if (hello == null) return;
            short[] ver = new short[1];
            if (Protocol.readHeader(hello, ver) != Protocol.HELLO) return; // not us: drop silently
            String name = Protocol.cleanName(hello.remaining() >= 2 ? Protocol.getString(hello) : "Player");

            if (ver[0] != NetConfig.PROTOCOL_VERSION) {
                reject(s, "Version mismatch: host uses protocol " + NetConfig.PROTOCOL_VERSION
                        + ", you use " + ver[0] + ". Both players need the same MultiSector version.");
                notices.add(name + " couldn't join: different MultiSector version");
                return;
            }
            int id;
            synchronized (this) {
                if (!alive(gen)) return;
                if (conns.size() + 1 >= MAX_PLAYERS) {
                    reject(s, "Server full (" + MAX_PLAYERS + " players).");
                    return;
                }
                id = nextId++;
                conn = new Conn(s, id, name);
                conns.add(conn);
            }
            ByteBuffer welcome = Protocol.begin(Protocol.WELCOME, 4 + Protocol.stringSize(localName));
            welcome.putInt(id);
            Protocol.putString(welcome, localName);
            welcome.flip();
            conn.send(welcome);
            notices.add(name + " joined (" + from + ")");
            refreshHostPlayers();
            broadcastPlayers();

            s.setSoTimeout(IDLE_TIMEOUT_MS);
            while (alive(gen)) {
                ByteBuffer msg = Frames.read(s.getInputStream());
                if (msg == null) break;
                int type = Protocol.readHeader(msg, null);
                if (type == Protocol.PING_REPORT && msg.remaining() >= 4) {
                    conn.pingMs = msg.getInt();
                } else if (type == Protocol.BYE) {
                    break;
                }
            }
        } catch (SocketTimeoutException e) {
            if (conn != null) notices.add(conn.name + " timed out");
        } catch (Exception e) {
            // connection dropped; handled below
        } finally {
            closeQuietly(s);
            if (conn != null && conns.remove(conn) && alive(gen)) {
                notices.add(conn.name + " left");
                refreshHostPlayers();
                broadcastPlayers();
            }
        }
    }

    private void reject(Socket s, String reason) {
        try {
            ByteBuffer bb = Protocol.begin(Protocol.REJECT, Protocol.stringSize(reason));
            Protocol.putString(bb, reason);
            bb.flip();
            Frames.write(s.getOutputStream(), bb);
        } catch (IOException ignored) {
        }
        closeQuietly(s);
    }

    private void udpEchoLoop(int gen) {
        byte[] buf = new byte[2048];
        DatagramSocket u = hostUdp;
        while (alive(gen)) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                u.receive(p);
                if (p.getLength() < Protocol.PING_SIZE) continue;
                ByteBuffer bb = ByteBuffer.wrap(buf, 0, p.getLength());
                if (Protocol.readHeader(bb, null) != Protocol.PING) continue;
                buf[Protocol.TYPE_OFFSET] = Protocol.PONG;
                u.send(new DatagramPacket(buf, p.getLength(), p.getSocketAddress()));
            } catch (IOException e) {
                if (!alive(gen)) return; // socket closed by stop()
            }
        }
    }

    private void listBroadcastLoop(int gen) {
        while (alive(gen)) {
            sleep(LIST_BROADCAST_MS);
            if (!alive(gen)) return;
            refreshHostPlayers();
            broadcastPlayers();
        }
    }

    private void refreshHostPlayers() {
        List<Player> list = new ArrayList<Player>();
        list.add(new Player(HOST_ID, localName, 0, true));
        for (Conn c : conns) list.add(new Player(c.id, c.name, c.pingMs, false));
        players = Collections.unmodifiableList(list);
        int n = list.size();
        status = "Hosting on port " + port + " - " + n + (n == 1 ? " player" : " players");
    }

    private void broadcastPlayers() {
        List<Player> list = players;
        int size = 4;
        for (Player p : list) size += 4 + Protocol.stringSize(p.name) + 4 + 1;
        ByteBuffer bb = Protocol.begin(Protocol.PLAYER_LIST, size);
        bb.putInt(list.size());
        for (Player p : list) {
            bb.putInt(p.id);
            Protocol.putString(bb, p.name);
            bb.putInt(p.pingMs);
            bb.put((byte) (p.isHost ? 1 : 0));
        }
        bb.flip();
        for (Conn c : conns) {
            try {
                c.send(bb.duplicate());
            } catch (IOException e) {
                closeQuietly(c.socket); // its reader thread cleans up
            }
        }
    }

    // ================================================================ client

    private void clientMain(int gen, String host, int port) {
        Socket s = new Socket();
        String endReason = null;
        try {
            InetAddress addr;
            try {
                addr = InetAddress.getByName(host);
            } catch (UnknownHostException e) {
                throw new JoinFailure("Can't understand the address \"" + host + "\". Use the host's IP, e.g. 25.12.34.56.");
            }
            InetSocketAddress target = new InetSocketAddress(addr, port);
            try {
                s.connect(target, NetConfig.CONNECT_TIMEOUT_MS);
            } catch (ConnectException e) {
                throw new JoinFailure("Connection refused: the host's PC answered, but nobody is hosting on port "
                        + port + ". Check they clicked Host and that you're both using the same port.");
            } catch (SocketTimeoutException e) {
                throw new JoinFailure(unreachableHelp());
            } catch (NoRouteToHostException e) {
                throw new JoinFailure(unreachableHelp());
            }
            s.setTcpNoDelay(true);
            s.setSoTimeout(NetConfig.CONNECT_TIMEOUT_MS);

            Conn conn = new Conn(s, HOST_ID, host);
            ByteBuffer hello = Protocol.begin(Protocol.HELLO, Protocol.stringSize(localName));
            Protocol.putString(hello, localName);
            hello.flip();
            conn.send(hello);

            ByteBuffer reply = Frames.read(s.getInputStream());
            if (reply == null) throw new JoinFailure("The host closed the connection during the handshake.");
            int type = Protocol.readHeader(reply, null);
            if (type == Protocol.REJECT) throw new JoinFailure("Host refused: " + Protocol.getString(reply));
            if (type != Protocol.WELCOME) throw new JoinFailure("Something answered on that port, but it isn't a MultiSector host.");
            int id = reply.getInt();
            String hostName = Protocol.getString(reply);

            DatagramSocket udp = new DatagramSocket();
            synchronized (this) {
                if (!alive(gen)) {
                    udp.close();
                    return;
                }
                toHost = conn;
                clientUdp = udp;
                myId = id;
                state = State.CONNECTED;
                status = "Connected to " + hostName + " (" + host + ")";
            }
            notices.add("Connected to " + hostName);
            final InetSocketAddress udpTarget = target;
            startThread("MultiSector-ping", new Runnable() { public void run() { pingLoop(gen, udpTarget); } });

            s.setSoTimeout(IDLE_TIMEOUT_MS);
            while (alive(gen)) {
                ByteBuffer msg = Frames.read(s.getInputStream());
                if (msg == null) {
                    endReason = "The host closed the connection.";
                    break;
                }
                type = Protocol.readHeader(msg, null);
                if (type == Protocol.PLAYER_LIST) {
                    players = readPlayerList(msg);
                } else if (type == Protocol.BYE) {
                    endReason = "Host: " + Protocol.getString(msg);
                    break;
                }
            }
        } catch (JoinFailure e) {
            endReason = e.getMessage();
        } catch (SocketTimeoutException e) {
            endReason = state == State.CONNECTED ? "Lost connection to the host (no response for "
                    + IDLE_TIMEOUT_MS / 1000 + " s)." : unreachableHelp();
        } catch (Exception e) {
            endReason = state == State.CONNECTED
                    ? "Lost connection to the host. Their game may have closed or their network dropped. (" + e.getMessage() + ")"
                    : "Couldn't connect: " + e.getMessage();
        } finally {
            closeQuietly(s);
        }
        if (alive(gen)) {
            if (state == State.CONNECTED) notices.add("Disconnected: " + endReason);
            stopWith(gen, endReason, "Not connected");
        }
    }

    private static String unreachableHelp() {
        return "No answer from the host. Check: (1) you're both online in the same Hamachi network "
                + "with a green dot, (2) you typed the host's 25.x.x.x address, (3) the host ran "
                + "\"dev firewall\" once, since Windows blocks Hamachi by default.";
    }

    private static List<Player> readPlayerList(ByteBuffer bb) {
        int n = bb.getInt();
        if (n < 0 || n > MAX_PLAYERS * 2) throw new IllegalArgumentException("bad player count " + n);
        List<Player> list = new ArrayList<Player>(n);
        for (int i = 0; i < n; i++) {
            int id = bb.getInt();
            String name = Protocol.getString(bb);
            int ping = bb.getInt();
            boolean isHost = bb.get() != 0;
            list.add(new Player(id, name, ping, isHost));
        }
        return Collections.unmodifiableList(list);
    }

    private void pingLoop(int gen, InetSocketAddress target) {
        DatagramSocket udp = clientUdp;
        byte[] out = new byte[Protocol.PING_SIZE];
        byte[] in = new byte[256];
        int seq = 0;
        try {
            udp.setSoTimeout(PING_INTERVAL_MS);
        } catch (IOException e) {
            return;
        }
        while (alive(gen)) {
            long sentAt = System.nanoTime();
            try {
                ByteBuffer bb = ByteBuffer.wrap(out);
                bb.putInt(NetConfig.MAGIC).putShort(NetConfig.PROTOCOL_VERSION).put(Protocol.PING)
                        .putInt(seq).putLong(sentAt);
                udp.send(new DatagramPacket(out, out.length, target));

                long deadline = System.currentTimeMillis() + PING_INTERVAL_MS;
                while (alive(gen) && System.currentTimeMillis() < deadline) {
                    DatagramPacket p = new DatagramPacket(in, in.length);
                    try {
                        udp.receive(p);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    ByteBuffer r = ByteBuffer.wrap(in, 0, p.getLength());
                    if (p.getLength() < Protocol.PING_SIZE || Protocol.readHeader(r, null) != Protocol.PONG) continue;
                    if (r.getInt() != seq) continue; // reply to an older ping
                    pingMs = (int) ((System.nanoTime() - r.getLong()) / 1000000L);
                    break;
                }
                Conn c = toHost;
                if (c != null && pingMs >= 0) {
                    ByteBuffer rep = Protocol.begin(Protocol.PING_REPORT, 4);
                    rep.putInt(pingMs).flip();
                    c.send(rep);
                }
            } catch (IOException e) {
                if (!alive(gen)) return;
            }
            seq++;
            long elapsed = (System.nanoTime() - sentAt) / 1000000L;
            if (elapsed < PING_INTERVAL_MS) sleep(PING_INTERVAL_MS - elapsed);
        }
    }

    // ================================================================ shared

    private synchronized void stopWith(int gen, String error, String newStatus) {
        if (gen != generation) return; // already stopped or restarted
        generation++;
        if (state == State.HOSTING) {
            for (Conn c : conns) sendBye(c, "The host stopped hosting.");
        } else if (state == State.CONNECTED && toHost != null && error == null) {
            sendBye(toHost, "left");
        }
        for (Conn c : conns) closeQuietly(c.socket);
        conns.clear();
        closeQuietly(server);
        server = null;
        if (hostUdp != null) hostUdp.close();
        hostUdp = null;
        if (toHost != null) closeQuietly(toHost.socket);
        toHost = null;
        if (clientUdp != null) clientUdp.close();
        clientUdp = null;
        players = Collections.emptyList();
        pingMs = -1;
        myId = -1;
        state = State.IDLE;
        lastError = error;
        status = newStatus;
    }

    private static void sendBye(Conn c, String reason) {
        try {
            ByteBuffer bb = Protocol.begin(Protocol.BYE, Protocol.stringSize(reason));
            Protocol.putString(bb, reason);
            bb.flip();
            c.send(bb);
        } catch (IOException ignored) {
        }
    }

    private boolean alive(int gen) {
        return gen == generation;
    }

    private static void startThread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true); // never keeps the game running after it closes
        t.start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(ServerSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    /** One TCP connection. Writes are synchronized because several threads send. */
    private static final class Conn {
        final Socket socket;
        final int id;
        final String name;
        volatile int pingMs = -1;

        Conn(Socket socket, int id, String name) {
            this.socket = socket;
            this.id = id;
            this.name = name;
        }

        synchronized void send(ByteBuffer msg) throws IOException {
            Frames.write(socket.getOutputStream(), msg);
        }
    }

    private static final class JoinFailure extends Exception {
        private static final long serialVersionUID = 1L;

        JoinFailure(String message) {
            super(message);
        }
    }
}
