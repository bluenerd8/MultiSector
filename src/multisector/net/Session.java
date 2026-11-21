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
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    // ---- fleet positions (both sides)
    /** This game's own fleet, set by the game thread about 10 times a second. */
    private volatile FleetState localFleet;
    /** Other players' fleets, by player id, with arrival times. */
    private final ConcurrentHashMap<Integer, FleetState.Received> remoteFleets =
            new ConcurrentHashMap<Integer, FleetState.Received>();
    /** Host: where each player's UDP packets come from, to send world states back. */
    private final ConcurrentHashMap<Integer, SocketAddress> udpAddresses = new ConcurrentHashMap<Integer, SocketAddress>();
    private static final int SYNC_INTERVAL_MS = 100;
    private int hostSeq;

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

    /** The game thread reports where this player's fleet is. Sent at the next sync tick. */
    public void setLocalFleet(FleetState s) {
        localFleet = s;
    }

    /** Other players' latest fleet positions, by player id. Read by the game thread. */
    public Map<Integer, FleetState.Received> getRemoteFleets() {
        return Collections.unmodifiableMap(remoteFleets);
    }

    /** Queues a message for the game to show, next to the session's own ones. */
    public void notice(String text) {
        notices.add(text);
    }

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
                    + "the connection test running?", e);
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
        startThread("MultiSector-sync", new Runnable() { public void run() { hostSyncLoop(gen); } });
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
            if (conn != null) {
                remoteFleets.remove(conn.id);
                udpAddresses.remove(conn.id);
            }
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
                ByteBuffer bb = ByteBuffer.wrap(buf, 0, p.getLength());
                int type = Protocol.readHeader(bb, null);
                if (type == Protocol.PING && p.getLength() >= Protocol.PING_SIZE) {
                    buf[Protocol.TYPE_OFFSET] = Protocol.PONG;
                    u.send(new DatagramPacket(buf, p.getLength(), p.getSocketAddress()));
                } else if (type == Protocol.FLEET_STATE) {
                    receiveFleetState(bb, p);
                }
            } catch (IOException e) {
                if (!alive(gen)) return; // socket closed by stop()
            } catch (RuntimeException e) {
                // malformed packet; ignore it
            }
        }
    }

    /** A client's own fleet position. Accepted only from the IP its TCP connection uses. */
    private void receiveFleetState(ByteBuffer bb, DatagramPacket p) {
        FleetState s = FleetState.read(bb);
        Conn owner = null;
        for (Conn c : conns) if (c.id == s.playerId) owner = c;
        if (owner == null || !owner.socket.getInetAddress().equals(p.getAddress())) return;
        s.name = owner.name; // trust the handshake name, not the packet
        FleetState.Received old = remoteFleets.get(s.playerId);
        if (old != null && s.seq <= old.state.seq && old.state.seq - s.seq < 1000) return; // out of order
        remoteFleets.put(s.playerId, new FleetState.Received(s, System.nanoTime()));
        udpAddresses.put(s.playerId, p.getSocketAddress());
    }

    /** Every tick, sends each client every other player's fleet (including the host's). */
    private void hostSyncLoop(int gen) {
        while (alive(gen)) {
            sleep(SYNC_INTERVAL_MS);
            if (!alive(gen)) return;
            List<FleetState> all = new ArrayList<FleetState>();
            FleetState mine = localFleet;
            if (mine != null) {
                FleetState m = mine.copy();
                m.playerId = HOST_ID;
                m.name = localName;
                m.seq = hostSeq++; // clients drop updates that don't move the sequence forward
                all.add(m);
            }
            for (FleetState.Received r : remoteFleets.values()) all.add(r.state);
            DatagramSocket u = hostUdp;
            if (u == null) continue;
            for (Map.Entry<Integer, SocketAddress> e : udpAddresses.entrySet()) {
                List<FleetState> others = new ArrayList<FleetState>();
                for (FleetState s : all) if (s.playerId != e.getKey()) others.add(s);
                sendWorldState(u, e.getValue(), others);
            }
        }
    }

    /** Packs fleet states into as few datagrams as fit under the size limit. */
    private static void sendWorldState(DatagramSocket u, SocketAddress to, List<FleetState> states) {
        int i = 0;
        do {
            ByteBuffer bb = ByteBuffer.allocate(NetConfig.MAX_DATAGRAM);
            bb.putInt(NetConfig.MAGIC).putShort(NetConfig.PROTOCOL_VERSION).put(Protocol.WORLD_STATE);
            int countPos = bb.position();
            bb.putShort((short) 0);
            short count = 0;
            while (i < states.size() && bb.remaining() >= states.get(i).size()) {
                states.get(i++).write(bb);
                count++;
            }
            if (count == 0 && i < states.size()) i++; // a single state too big to send; skip it
            bb.putShort(countPos, count);
            try {
                u.send(new DatagramPacket(bb.array(), bb.position(), to));
            } catch (IOException ignored) {
                // UDP: a lost tick is replaced by the next one
            }
        } while (i < states.size());
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
            udp.setSoTimeout(1000);
            startThread("MultiSector-send", new Runnable() { public void run() { clientSendLoop(gen, udpTarget); } });
            startThread("MultiSector-recv", new Runnable() { public void run() { clientReceiveLoop(gen); } });

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
                + "tools\\firewall.cmd once, since Windows blocks Hamachi by default.";
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

    /** Client: sends this fleet's position every tick, and a ping (plus a ping report over TCP) every second. */
    private void clientSendLoop(int gen, InetSocketAddress target) {
        DatagramSocket udp = clientUdp;
        int seq = 0;
        int pingSeq = 0;
        long nextPing = 0;
        while (alive(gen)) {
            long now = System.nanoTime();
            try {
                FleetState mine = localFleet;
                if (mine != null && myId >= 0) {
                    FleetState s = mine.copy();
                    s.playerId = myId;
                    s.seq = seq++;
                    s.name = localName;
                    ByteBuffer bb = Protocol.begin(Protocol.FLEET_STATE, s.size());
                    s.write(bb);
                    udp.send(new DatagramPacket(bb.array(), bb.position(), target));
                }
                if (now >= nextPing) {
                    nextPing = now + PING_INTERVAL_MS * 1000000L;
                    byte[] out = new byte[Protocol.PING_SIZE];
                    ByteBuffer bb = ByteBuffer.wrap(out);
                    bb.putInt(NetConfig.MAGIC).putShort(NetConfig.PROTOCOL_VERSION).put(Protocol.PING)
                            .putInt(pingSeq++).putLong(now);
                    udp.send(new DatagramPacket(out, out.length, target));
                    Conn c = toHost;
                    if (c != null && pingMs >= 0) {
                        ByteBuffer rep = Protocol.begin(Protocol.PING_REPORT, 4);
                        rep.putInt(pingMs).flip();
                        c.send(rep);
                    }
                }
            } catch (IOException e) {
                if (!alive(gen)) return;
            }
            sleep(SYNC_INTERVAL_MS);
        }
    }

    /** Client: receives ping replies and the other players' fleet positions. */
    private void clientReceiveLoop(int gen) {
        DatagramSocket udp = clientUdp;
        byte[] in = new byte[2048];
        while (alive(gen)) {
            try {
                DatagramPacket p = new DatagramPacket(in, in.length);
                udp.receive(p);
                ByteBuffer r = ByteBuffer.wrap(in, 0, p.getLength());
                int type = Protocol.readHeader(r, null);
                if (type == Protocol.PONG && p.getLength() >= Protocol.PING_SIZE) {
                    r.getInt(); // seq
                    pingMs = (int) ((System.nanoTime() - r.getLong()) / 1000000L);
                } else if (type == Protocol.WORLD_STATE) {
                    long arrived = System.nanoTime();
                    int n = r.getShort();
                    for (int i = 0; i < n; i++) {
                        FleetState s = FleetState.read(r);
                        if (s.playerId == myId) continue;
                        FleetState.Received old = remoteFleets.get(s.playerId);
                        if (old != null && s.seq <= old.state.seq && old.state.seq - s.seq < 1000) continue;
                        remoteFleets.put(s.playerId, new FleetState.Received(s, arrived));
                    }
                }
            } catch (SocketTimeoutException e) {
                // just loop
            } catch (IOException e) {
                if (!alive(gen)) return;
            } catch (RuntimeException e) {
                // malformed packet; ignore it
            }
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
        remoteFleets.clear();
        udpAddresses.clear();
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
