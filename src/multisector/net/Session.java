package multisector.net;

import multisector.BuildInfo;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The one multiplayer session for this game instance: idle, hosting, or
 * joined to a host. It handles connecting, the player list, ping, fleet
 * positions and ships, and the shared campaign clock (pause, speed, date).
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

    // ---- shared clock
    /** Host: the clock everyone follows. */
    private final SharedClock clock = new SharedClock();
    /** Client: the host's clock as last received. */
    private volatile ClockState.Received remoteClock;

    // ---- fleet ships (both sides)
    private volatile FleetComp localComp;
    /** Other players' ships, by player id. */
    private final ConcurrentHashMap<Integer, FleetComp> comps = new ConcurrentHashMap<Integer, FleetComp>();
    /** TCP messages from the game thread. Client: sent to the host. Host: sent to every client. */
    private final ConcurrentLinkedQueue<ByteBuffer> outbox = new ConcurrentLinkedQueue<ByteBuffer>();

    // ---- NPC fleets. Each part of the world follows one player's game (see ZonePlanner): that
    // player publishes its NPC fleets there, the host relays them, and everyone else there shows them.
    /** Who follows whose world, by player id. Anyone missing follows their own. Host: planned; client: from the host. */
    private volatile Map<Integer, Integer> follows = Collections.emptyMap();
    /** Host: plans {@link #follows}. Only the sync thread uses it. */
    private final ZonePlanner planner = new ZonePlanner();
    /** What we publish: the location ("" = nothing), each fleet's details, and their latest positions. */
    private volatile String pubLocation = "";
    private final ConcurrentHashMap<Integer, NpcInfo> pubInfos = new ConcurrentHashMap<Integer, NpcInfo>();
    private volatile List<NpcState> pubStates = Collections.emptyList();
    /** What the other players publish, by publisher id. */
    private final ConcurrentHashMap<Integer, Feed> feeds = new ConcurrentHashMap<Integer, Feed>();

    // ---- host: the host's own launcher asking for a fresh save
    private final ConcurrentLinkedQueue<SaveRequest> saveRequests = new ConcurrentLinkedQueue<SaveRequest>();

    /** Host: messages the game thread wants sent to one player: {playerId, message}. */
    private final ConcurrentLinkedQueue<Object[]> directed = new ConcurrentLinkedQueue<Object[]>();

    // ---- markets
    /** Host: stock requests and trades from players, for the game thread: {kind, playerId, name, text}. */
    private final ConcurrentLinkedQueue<Object[]> marketEvents = new ConcurrentLinkedQueue<Object[]>();
    /** Client: market stock from the host, by market id. */
    private final ConcurrentHashMap<String, String> marketSnapshots = new ConcurrentHashMap<String, String>();
    public static final int MARKET_EVENT_REQUEST = 0;
    public static final int MARKET_EVENT_TRADE = 1;
    /** Messages from other players, for the game thread: conversations, and fleet locks. */
    private final ConcurrentLinkedQueue<PlayerMessage> playerMessages = new ConcurrentLinkedQueue<PlayerMessage>();
    private final ConcurrentLinkedQueue<PlayerMessage> lockMessages = new ConcurrentLinkedQueue<PlayerMessage>();
    private final ConcurrentLinkedQueue<PlayerMessage> battleMessages = new ConcurrentLinkedQueue<PlayerMessage>();
    private final ConcurrentLinkedQueue<PlayerMessage> socialMessages = new ConcurrentLinkedQueue<PlayerMessage>();
    private final ConcurrentLinkedQueue<PlayerMessage> objectReplies = new ConcurrentLinkedQueue<PlayerMessage>();
    /** Host: who is salvaging what. */
    private final ObjectLockTable objectLocks = new ObjectLockTable();
    /** Objects in space that appeared, changed or went on another player's game, for the game thread. */
    private final ConcurrentLinkedQueue<String> worldEvents = new ConcurrentLinkedQueue<String>();
    /** Joint battle datagrams from other players (live battle state, pilot controls), for the combat plugins. */
    private final ConcurrentLinkedQueue<CombatPacket> combatPackets = new ConcurrentLinkedQueue<CombatPacket>();
    /** Client: where the host's UDP is, so combat datagrams can be sent from any thread. */
    private volatile InetSocketAddress udpToHost;
    /** PLAYER_MSG channels. */
    public static final byte CHANNEL_TALK = 0;
    public static final byte CHANNEL_LOCK = 1;
    public static final byte CHANNEL_BATTLE = 2;
    /** Chat, map pings and other things said to everyone (or one player). */
    public static final byte CHANNEL_SOCIAL = 3;
    /** Claims on objects in space while someone salvages them; answered by the host (ObjectLockTable). */
    public static final byte CHANNEL_OBJECTS = 4;
    /** A PLAYER_MSG to everyone in the session (the host relays a client's to the rest). */
    public static final int TO_ALL = -1;
    /** A player's position this old counts as gone (they're loading, or left). */
    private static final long STALE_NANOS = 10_000_000_000L;
    /** Sent on a player's behalf when they leave, so whoever was talking to them knows. */
    public static final String LEFT_JSON = "{\"k\":\"left\"}";
    /** How long the launcher waits for the game to save. */
    private static final int SAVE_WAIT_MS = 20000;

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
    /** The last join: {host, port, name}, or null. To try again after the host drops. */
    public String[] getLastJoin() { return lastJoin; }
    private volatile String[] lastJoin;
    /** Client: last measured round trip to the host, or -1. */
    public int getPingMs() { return pingMs; }
    public int getMyId() { return myId; }
    public boolean isActive() { return state != State.IDLE; }

    /** The game thread reports where this player's fleet is. Sent at the next sync tick. */
    public void setLocalFleet(FleetState s) {
        localFleet = s;
        localFleetAt = System.nanoTime();
    }

    private volatile long localFleetAt;
    /** Host: the world waits while nobody is on the map (MultiSectorConfig.isHoldWhileEveryoneBusy). */
    private volatile boolean holdWhileBusy = true;
    /** A player whose position hasn't come for this long isn't on the map (in a battle, loading, gone). */
    private static final long ON_MAP_NANOS = 3_000_000_000L;

    public void setHoldWhileBusy(boolean on) {
        holdWhileBusy = on;
    }

    /** Last time the game itself was running a world (campaign or battle), System.nanoTime, or 0. */
    private volatile long gameAliveAt;

    /** The game thread: a campaign or combat frame of this world just ran. */
    public void gameAlive() {
        gameAliveAt = System.nanoTime();
    }

    private static boolean onMap(FleetState st, long at, long now) {
        return st != null && now - at < ON_MAP_NANOS
                && (st.activity == FleetState.ON_MAP || st.activity == FleetState.PAUSED);
    }

    private volatile long onMapAt;

    /** The game thread: we're on the campaign map this frame (sooner than our next position report). */
    public void markOnMap() {
        onMapAt = System.nanoTime();
    }

    /** Host: is anyone (us included) on the campaign map right now? */
    public boolean anyoneOnMap(long now) {
        if (now - onMapAt < 500_000_000L) return true;
        if (onMap(localFleet, localFleetAt, now)) return true;
        for (Conn c : conns) {
            FleetState.Received r = remoteFleets.get(c.id);
            if (r != null && onMap(r.state, r.arrivedNanos, now)) return true;
        }
        return false;
    }

    /** Other players' latest fleet positions, by player id. Read by the game thread. */
    public Map<Integer, FleetState.Received> getRemoteFleets() {
        return Collections.unmodifiableMap(remoteFleets);
    }

    /** Host: the shared campaign clock. */
    public SharedClock getSharedClock() {
        return clock;
    }

    /** Client: the host's clock as last received, or null before the first one arrives. */
    public ClockState.Received getRemoteClock() {
        return remoteClock;
    }

    /** Host: no world frame for this long means our world closed (main menu, or another save loading). */
    private static final long HOST_GONE_NANOS = 90_000_000_000L;

    /** Host: friends fast-forwarding right now: id -> {mult, last heard (nanos)}. */
    private final ConcurrentHashMap<Integer, float[]> fastMults = new ConcurrentHashMap<Integer, float[]>();
    private final ConcurrentHashMap<Integer, Long> fastHeard = new ConcurrentHashMap<Integer, Long>();
    /** A fast-forward request not refreshed for this long has ended (the friend opened a menu, say). */
    private static final long FAST_STALE_NANOS = 2_500_000_000L;

    /** Client: we're fast-forwarding (or stopped). Sent on change and every second while on. */
    public void requestFast(boolean on, float mult) {
        if (state != State.CONNECTED) return;
        ByteBuffer bb = Protocol.begin(Protocol.FAST_REQUEST, 5);
        bb.put((byte) (on ? 1 : 0)).putFloat(mult).flip();
        outbox.add(bb);
    }

    /** Host: a friend fast-forwarding right now, as {playerId, mult}, or null. The fastest one wins. */
    public float[] fastRequest(long nanos) {
        float[] best = null;
        for (Map.Entry<Integer, Long> e : fastHeard.entrySet()) {
            if (nanos - e.getValue() > FAST_STALE_NANOS) continue;
            float[] m = fastMults.get(e.getKey());
            if (m == null) continue;
            if (best == null || m[0] > best[1]) best = new float[]{e.getKey(), m[0]};
        }
        return best;
    }

    // ---- orders from the host to everyone (save now, the host is leaving)
    private final ConcurrentLinkedQueue<String> hostOrders = new ConcurrentLinkedQueue<String>();
    /** Client: the host ended the session on purpose (stopped hosting, or their world closed). */
    private volatile boolean hostEnded;

    /** Client: did the host end the last session on purpose? Cleared by {@link #clearHostEnded}. */
    public boolean hostEnded() {
        return hostEnded;
    }

    public void clearHostEnded() {
        hostEnded = false;
    }

    /** Host: tells every friend something about the whole session (json with "k"). */
    public void sendHostOrder(String json) {
        if (state != State.HOSTING) return;
        outbox.add(hostOrderMessage(json));
    }

    /** Host: this load of our world (see SessionGuard), sent to every friend as they join. */
    private volatile String worldEpoch;

    public void setWorldEpoch(String epoch) {
        worldEpoch = epoch;
    }

    private static ByteBuffer hostOrderMessage(String json) {
        ByteBuffer bb = Protocol.begin(Protocol.HOST_ORDER, Protocol.longStringSize(json));
        Protocol.putLongString(bb, json);
        bb.flip();
        return bb;
    }

    /** Host: a friend asked us to save the world (their name), or null. */
    private volatile String saveAskedBy;

    /** Client: we saved on our own: the host should save the world (theirs is the save that counts). */
    public void requestHostSave() {
        if (state != State.CONNECTED) return;
        ByteBuffer bb = Protocol.begin(Protocol.SAVE_REQUEST, 0);
        bb.flip();
        outbox.add(bb);
    }

    /** Host: who asked us to save, once, or null. */
    public String pollFriendSaveRequest() {
        String who = saveAskedBy;
        saveAskedBy = null;
        return who;
    }

    /** Client: the next order from the host, or null. */
    public String pollHostOrder() {
        return hostOrders.poll();
    }

    /** Client: ask the host to pause or resume the game for everyone. */
    public void requestPause(boolean paused) {
        if (state != State.CONNECTED) return;
        ByteBuffer bb = Protocol.begin(Protocol.PAUSE_REQUEST, 1);
        bb.put((byte) (paused ? 1 : 0)).flip();
        outbox.add(bb);
    }

    /** The game thread reports which ships this player's fleet has. Sent only when it changes. */
    public void setLocalComposition(FleetComp c) {
        FleetComp old = localComp;
        if (old != null && old.hash == c.hash) return;
        localComp = c;
        if (state == State.HOSTING || state == State.CONNECTED) outbox.add(compMessage(myId, c));
    }

    /** Another player's ships, or null if they haven't arrived yet. */
    public FleetComp getComposition(int playerId) {
        return comps.get(playerId);
    }

    // ---- NPC fleets: publishing (any player whose world others follow)

    /** From now on we publish the NPC fleets of this location ("" for none). */
    public void publishReset(String locationId) {
        String loc = locationId == null ? "" : locationId;
        pubLocation = loc;
        pubInfos.clear();
        pubStates = Collections.emptyList();
        sendNpc(npcResetMessage(publisherId(), loc));
    }

    /** A published fleet appeared or changed. */
    public void publishInfo(NpcInfo info) {
        pubInfos.put(info.handle, info);
        sendNpc(npcInfoMessage(publisherId(), pubLocation, info));
    }

    /** A published fleet left or is gone. */
    public void publishGone(int handle) {
        pubInfos.remove(handle);
        String loc = pubLocation;
        ByteBuffer bb = Protocol.begin(Protocol.NPC_GONE, 4 + Protocol.stringSize(loc) + 4);
        bb.putInt(publisherId());
        Protocol.putString(bb, loc);
        bb.putInt(handle).flip();
        sendNpc(bb);
    }

    /** Re-sends every fleet's details, in case a message crossed paths with someone joining. */
    public void publishResendAll() {
        for (NpcInfo i : pubInfos.values()) sendNpc(npcInfoMessage(publisherId(), pubLocation, i));
    }

    /** Where every published fleet is now. */
    public void publishStates(List<NpcState> states) {
        pubStates = Collections.unmodifiableList(new ArrayList<NpcState>(states));
    }

    /** The location we're publishing, or "". */
    public String getPublishedLocation() {
        return pubLocation;
    }

    private int publisherId() {
        return state == State.HOSTING ? HOST_ID : myId;
    }

    /** Host: to every client. Client: to the host, which relays it. */
    private void sendNpc(ByteBuffer bb) {
        if (state == State.HOSTING || state == State.CONNECTED) outbox.add(bb);
    }

    // ---- NPC fleets: whose world to follow

    /** The player whose world that player follows (themselves if nobody's). */
    public int ownerOf(int playerId) {
        Integer o = follows.get(playerId);
        return o == null ? playerId : o;
    }

    /** True if some other player follows that player's world. */
    public boolean hasFollowers(int playerId) {
        for (Map.Entry<Integer, Integer> e : follows.entrySet()) {
            if (e.getValue() == playerId && e.getKey() != playerId) return true;
        }
        return false;
    }

    /** The players who follow that player's world, sorted, as a short text (to notice changes). */
    public String followersOf(int playerId) {
        List<Integer> ids = new ArrayList<Integer>();
        for (Map.Entry<Integer, Integer> e : follows.entrySet()) {
            if (e.getValue() == playerId && e.getKey() != playerId) ids.add(e.getKey());
        }
        Collections.sort(ids);
        return ids.toString();
    }

    /** What another player publishes (empty if nothing). */
    public Feed getFeed(int publisher) {
        Feed f = feeds.get(publisher);
        return f == null ? Feed.EMPTY : f;
    }

    /** The NPC fleets one other player publishes. */
    public static final class Feed {
        static final Feed EMPTY = new Feed();
        volatile String location = "";
        final ConcurrentHashMap<Integer, NpcInfo> infos = new ConcurrentHashMap<Integer, NpcInfo>();
        final ConcurrentHashMap<Integer, NpcState.Received> states = new ConcurrentHashMap<Integer, NpcState.Received>();

        public String getLocation() {
            return location;
        }

        public Map<Integer, NpcInfo> getInfos() {
            return Collections.unmodifiableMap(infos);
        }

        public Map<Integer, NpcState.Received> getStates() {
            return Collections.unmodifiableMap(states);
        }

        void reset(String loc) {
            location = loc;
            infos.clear();
            states.clear();
        }
    }

    private Feed feed(int publisher) {
        Feed f = feeds.get(publisher);
        if (f == null) {
            feeds.putIfAbsent(publisher, new Feed());
            f = feeds.get(publisher);
        }
        return f;
    }

    private static ByteBuffer zonesMessage(Map<Integer, Integer> map) {
        ByteBuffer bb = Protocol.begin(Protocol.ZONES, 2 + map.size() * 8);
        bb.putShort((short) map.size());
        for (Map.Entry<Integer, Integer> e : map.entrySet()) bb.putInt(e.getKey()).putInt(e.getValue());
        bb.flip();
        return bb;
    }

    private static Map<Integer, Integer> readZones(ByteBuffer bb) {
        int n = bb.getShort();
        if (n < 0 || n > MAX_PLAYERS * 2) throw new IllegalArgumentException("bad zone count " + n);
        Map<Integer, Integer> m = new HashMap<Integer, Integer>();
        for (int i = 0; i < n; i++) m.put(bb.getInt(), bb.getInt());
        return Collections.unmodifiableMap(m);
    }

    /** NPC positions as datagrams. */
    private static List<ByteBuffer> npcStatePackets(int publisher, String loc, List<NpcState> states) {
        List<ByteBuffer> out = new ArrayList<ByteBuffer>();
        int i = 0;
        while (i < states.size()) {
            ByteBuffer bb = ByteBuffer.allocate(NetConfig.MAX_DATAGRAM);
            bb.putInt(NetConfig.MAGIC).putShort(NetConfig.PROTOCOL_VERSION).put(Protocol.NPC_STATE);
            bb.putInt(publisher);
            Protocol.putString(bb, loc);
            int countPos = bb.position();
            bb.putShort((short) 0);
            short count = 0;
            while (i < states.size() && bb.remaining() >= NpcState.SIZE) {
                states.get(i++).write(bb);
                count++;
            }
            if (count == 0) break; // a location id too long to leave room; never in practice
            bb.putShort(countPos, count);
            out.add(bb);
        }
        return out;
    }

    /** Positions into a feed: only fleets we have details for, so a late packet can't bring back a gone one. */
    private static void readNpcStates(ByteBuffer r, Feed f, long arrived) {
        int n = r.getShort();
        for (int i = 0; i < n; i++) {
            NpcState ns = NpcState.read(r);
            if (f.infos.containsKey(ns.handle)) f.states.put(ns.handle, new NpcState.Received(ns, arrived));
        }
    }

    // ---- markets

    /**
     * Host: next market event for the game thread, or null:
     * {MARKET_EVENT_REQUEST or MARKET_EVENT_TRADE (Integer), playerId (Integer), playerName, marketId or trade json}.
     */
    public Object[] pollMarketEvent() {
        return marketEvents.poll();
    }

    /** Host: sends a market's stock to one player ("" if there's no such market). */
    public void replyMarket(int playerId, String marketId, String json) {
        String j = json == null ? "" : json;
        ByteBuffer bb = Protocol.begin(Protocol.MARKET_SNAPSHOT, Protocol.stringSize(marketId) + Protocol.longStringSize(j));
        Protocol.putString(bb, marketId);
        Protocol.putLongString(bb, j);
        bb.flip();
        directed.add(new Object[]{playerId, bb});
    }

    /** Client: ask the host for a market's stock; it arrives through {@link #takeMarketSnapshot}. */
    public void requestMarket(String marketId) {
        marketSnapshots.remove(marketId);
        ByteBuffer bb = Protocol.begin(Protocol.MARKET_REQUEST, Protocol.stringSize(marketId));
        Protocol.putString(bb, marketId);
        bb.flip();
        outbox.add(bb);
    }

    /** Client: the host's stock for that market, once (null until it arrives). */
    public String takeMarketSnapshot(String marketId) {
        return marketSnapshots.remove(marketId);
    }

    /** Client: one trade we made, for the host to apply to its market. */
    public void sendMarketTrade(String json) {
        ByteBuffer bb = Protocol.begin(Protocol.MARKET_TRADE, Protocol.longStringSize(json));
        Protocol.putLongString(bb, json);
        bb.flip();
        outbox.add(bb);
    }

    // ---- player to player

    /** One message from another player: a hail, an answer, an offer. */
    public static final class PlayerMessage {
        public final int from;
        public final String fromName;
        public final String json;

        PlayerMessage(int from, String fromName, String json) {
            this.from = from;
            this.fromName = fromName;
            this.json = json;
        }
    }

    /** Sends a conversation message to one other player (through the host when we're a client). */
    public void sendPlayerMessage(int to, String json) {
        send(to, CHANNEL_TALK, json);
    }

    /** Sends a fleet-lock message to another player (the one whose world has that fleet, or who asked). */
    public void sendLockMessage(int to, String json) {
        send(to, CHANNEL_LOCK, json);
    }

    /** Sends a joint-battle message to another player (sync.JointBattle). */
    public void sendBattleMessage(int to, String json) {
        send(to, CHANNEL_BATTLE, json);
    }

    /** Next joint-battle message from another player, or null. */
    public PlayerMessage pollBattleMessage() {
        return battleMessages.poll();
    }

    /** Says something to everyone else in the session (chat, a map ping). */
    public void sendSocial(String json) {
        if (state == State.HOSTING) {
            outbox.add(playerMessage(TO_ALL, HOST_ID, CHANNEL_SOCIAL, json));
        } else if (state == State.CONNECTED) {
            outbox.add(playerMessage(TO_ALL, myId, CHANNEL_SOCIAL, json));
        }
    }

    /** Says something to one other player (the host is player 0). */
    public void sendSocialTo(int to, String json) {
        send(to, CHANNEL_SOCIAL, json);
    }

    /** Next chat line, ping or the like from another player, or null. */
    public PlayerMessage pollSocialMessage() {
        return socialMessages.poll();
    }

    /** The host's player id. */
    public static int hostId() {
        return HOST_ID;
    }

    /**
     * Asks to salvage an object nobody else is salvaging right now. The
     * answer comes back through {@link #pollObjectReply()}: grant or deny
     * (with who has it). On the host it's answered at once.
     */
    public void claimObject(String key) {
        String json = objectJson("claim", key, null);
        if (state == State.HOSTING) {
            String by = objectLocks.claim(key, HOST_ID, localName, System.nanoTime());
            objectReplies.add(new PlayerMessage(HOST_ID, localName, objectJson(by == null ? "grant" : "deny", key, by)));
        } else if (state == State.CONNECTED) {
            outbox.add(playerMessage(HOST_ID, myId, CHANNEL_OBJECTS, json));
        }
    }

    /** We're done salvaging that object. */
    public void releaseObject(String key) {
        if (state == State.HOSTING) {
            objectLocks.release(key, HOST_ID);
        } else if (state == State.CONNECTED) {
            outbox.add(playerMessage(HOST_ID, myId, CHANNEL_OBJECTS, objectJson("release", key, null)));
        }
    }

    /** Next answer to a claim, or null. */
    public PlayerMessage pollObjectReply() {
        return objectReplies.poll();
    }

    private static String objectJson(String kind, String key, String by) {
        StringBuilder b = new StringBuilder("{\"k\":\"").append(kind).append("\",\"key\":").append(quote(key));
        if (by != null) b.append(",\"by\":").append(quote(by));
        return b.append('}').toString();
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** The value of a string field in our own small JSON ("k", "key", "by"), or null. */
    static String field(String json, String name) {
        String tag = "\"" + name + "\":\"";
        int i = json.indexOf(tag);
        if (i < 0) return null;
        StringBuilder b = new StringBuilder();
        for (int j = i + tag.length(); j < json.length(); j++) {
            char c = json.charAt(j);
            if (c == '\\' && j + 1 < json.length()) {
                b.append(json.charAt(++j));
            } else if (c == '"') {
                return b.toString();
            } else {
                b.append(c);
            }
        }
        return null;
    }

    /** Host, network thread: a client's claim or release. */
    private void objectMessage(Conn conn, String json) throws IOException {
        String kind = field(json, "k");
        String key = field(json, "key");
        if (key == null) return;
        if ("claim".equals(kind)) {
            String by = objectLocks.claim(key, conn.id, conn.name, System.nanoTime());
            conn.send(playerMessage(conn.id, HOST_ID, CHANNEL_OBJECTS, objectJson(by == null ? "grant" : "deny", key, by)));
        } else if ("release".equals(kind)) {
            objectLocks.release(key, conn.id);
        }
    }

    private void send(int to, byte channel, String json) {
        if (state == State.HOSTING) {
            directed.add(new Object[]{to, playerMessage(to, HOST_ID, channel, json)});
        } else if (state == State.CONNECTED) {
            outbox.add(playerMessage(to, myId, channel, json));
        }
    }

    /** Tells everyone else about an object in space that appeared, changed or went here. */
    public void sendWorldEvent(String json) {
        if (state != State.HOSTING && state != State.CONNECTED) return;
        ByteBuffer bb = Protocol.begin(Protocol.WORLD_EVENT, Protocol.longStringSize(json));
        Protocol.putLongString(bb, json);
        bb.flip();
        outbox.add(bb); // host: to every client; client: to the host, which applies and relays it
    }

    /** One joint battle datagram from another player. */
    public static final class CombatPacket {
        public final int from;
        public final ByteBuffer data;

        CombatPacket(int from, ByteBuffer data) {
            this.from = from;
            this.data = data;
        }
    }

    /**
     * Sends a joint battle datagram to one other player (through the host
     * when we're a client). From any thread; the payload must fit a datagram.
     */
    public void sendCombatPacket(int to, ByteBuffer payload) {
        ByteBuffer bb = Protocol.begin(Protocol.COMBAT_UDP, 8 + payload.remaining());
        bb.putInt(to).putInt(publisherId()).put(payload.duplicate());
        try {
            if (state == State.HOSTING) {
                SocketAddress a = udpAddresses.get(to);
                DatagramSocket u = hostUdp;
                if (a != null && u != null) u.send(new DatagramPacket(bb.array(), bb.position(), a));
            } else if (state == State.CONNECTED) {
                InetSocketAddress a = udpToHost;
                DatagramSocket u = clientUdp;
                if (a != null && u != null) u.send(new DatagramPacket(bb.array(), bb.position(), a));
            }
        } catch (IOException ignored) {
            // UDP: the next one replaces it
        }
    }

    /** Next joint battle datagram, or null. */
    public CombatPacket pollCombatPacket() {
        return combatPackets.poll();
    }

    private void receivedCombat(int from, ByteBuffer bb) {
        byte[] copy = new byte[bb.remaining()];
        bb.get(copy);
        combatPackets.add(new CombatPacket(from, ByteBuffer.wrap(copy)));
        while (combatPackets.size() > 400) combatPackets.poll(); // nobody reading: drop the oldest
    }

    /** Next object change from another player's game, or null. */
    public String pollWorldEvent() {
        return worldEvents.poll();
    }

    /** Next conversation message from another player, or null. */
    public PlayerMessage pollPlayerMessage() {
        return playerMessages.poll();
    }

    /** Next fleet-lock message from another player, or null. */
    public PlayerMessage pollLockMessage() {
        return lockMessages.poll();
    }

    private void received(byte channel, PlayerMessage m) {
        if (channel == CHANNEL_LOCK) lockMessages.add(m);
        else if (channel == CHANNEL_BATTLE) battleMessages.add(m);
        else if (channel == CHANNEL_SOCIAL) {
            socialMessages.add(m);
            while (socialMessages.size() > 300) socialMessages.poll(); // nobody reading: drop the oldest
        } else if (channel == CHANNEL_OBJECTS) objectReplies.add(m);
        else playerMessages.add(m);
    }

    /** True if that player is in the session right now. */
    public boolean isPlayerHere(int id) {
        for (Player p : players) if (p.id == id) return true;
        return false;
    }

    private static ByteBuffer playerMessage(int to, int from, byte channel, String json) {
        ByteBuffer bb = Protocol.begin(Protocol.PLAYER_MSG, 4 + 4 + 1 + Protocol.longStringSize(json));
        bb.putInt(to).putInt(from).put(channel);
        Protocol.putLongString(bb, json);
        bb.flip();
        return bb;
    }

    private static ByteBuffer npcResetMessage(int publisher, String loc) {
        ByteBuffer bb = Protocol.begin(Protocol.NPC_RESET, 4 + Protocol.stringSize(loc));
        bb.putInt(publisher);
        Protocol.putString(bb, loc);
        bb.flip();
        return bb;
    }

    private static ByteBuffer npcInfoMessage(int publisher, String loc, NpcInfo info) {
        ByteBuffer bb = Protocol.begin(Protocol.NPC_INFO, 4 + Protocol.stringSize(loc) + info.size());
        bb.putInt(publisher);
        Protocol.putString(bb, loc);
        info.write(bb);
        bb.flip();
        return bb;
    }

    /** A player's name from the player list, or "Someone". */
    public String playerName(int id) {
        for (Player p : players) if (p.id == id) return p.name;
        return "Someone";
    }

    /** Host: next fresh-save request from the launcher, for the game thread to carry out. */
    public SaveRequest pollSaveRequest() {
        SaveRequest r;
        while ((r = saveRequests.poll()) != null) {
            if (!r.isExpired()) return r;
        }
        return null;
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
        clock.reset();
        comps.clear();
        outbox.clear();
        clearNpc();
        this.port = port;
        remote = null;
        myId = HOST_ID;
        gameAliveAt = System.nanoTime();
        fastHeard.clear();
        fastMults.clear();
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
        hostEnded = false;
        hostOrders.clear();
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
        lastJoin = new String[]{host, String.valueOf(port), name == null ? "Player" : name};
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
            int first = Protocol.readHeader(hello, ver);
            if (first == Protocol.LOCAL_SAVE && ver[0] == NetConfig.PROTOCOL_VERSION) {
                localSave(s, hello);
                return;
            }
            if (first != Protocol.HELLO) return; // not us: drop silently
            String name = Protocol.cleanName(hello.remaining() >= 2 ? Protocol.getString(hello) : "Player");
            String theirBuild = ver[0] == NetConfig.PROTOCOL_VERSION && hello.remaining() >= 2
                    ? Protocol.getString(hello) : "an older build";

            if (ver[0] != NetConfig.PROTOCOL_VERSION || !BuildInfo.VERSION.equals(theirBuild)) {
                reject(s, "Different MultiSector builds: the host has " + BuildInfo.VERSION + ", you have "
                        + theirBuild + ". You both need the same build.");
                notices.add(name + " couldn't join: they have MultiSector " + theirBuild + ", you have "
                        + BuildInfo.VERSION);
                return;
            }
            int id;
            synchronized (this) {
                if (!alive(gen)) return;
                // The same player again from the same address: a reconnect after a drop. Their old connection
                // would linger as a ghost until it timed out, so it goes now (its thread tidies up as for a leave).
                for (Conn old : conns) {
                    if (old.name.equals(name) && old.socket.getInetAddress().equals(s.getInetAddress())) {
                        old.replaced = true;
                        closeQuietly(old.socket);
                        notices.add(name + " reconnected");
                    }
                }
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
            // Everyone's ships so far, so their puppets look right straight away.
            FleetComp mine = localComp;
            if (mine != null) conn.send(compMessage(HOST_ID, mine));
            for (Map.Entry<Integer, FleetComp> e : comps.entrySet()) {
                if (e.getKey() != id) conn.send(compMessage(e.getKey(), e.getValue()));
            }
            // And every published NPC fleet: ours, and those we relay for other players.
            String npcLoc = pubLocation;
            conn.send(npcResetMessage(HOST_ID, npcLoc));
            for (NpcInfo i : pubInfos.values()) conn.send(npcInfoMessage(HOST_ID, npcLoc, i));
            for (Map.Entry<Integer, Feed> e : feeds.entrySet()) {
                Feed f = e.getValue();
                conn.send(npcResetMessage(e.getKey(), f.location));
                for (NpcInfo i : f.infos.values()) conn.send(npcInfoMessage(e.getKey(), f.location, i));
            }
            conn.send(zonesMessage(follows));
            String epoch = worldEpoch;
            if (epoch != null) conn.send(hostOrderMessage("{\"k\":\"epoch\",\"id\":\"" + epoch + "\"}"));
            notices.add(name + " joined (" + from + ")");
            if (clock.release(name, System.nanoTime())) {
                notices.add(clock.isRunning() ? "Everyone's in, so the game carries on." : name + " is in.");
            }
            refreshHostPlayers();
            broadcastPlayers();

            s.setSoTimeout(IDLE_TIMEOUT_MS);
            while (alive(gen)) {
                ByteBuffer msg = Frames.read(s.getInputStream());
                if (msg == null) break;
                int type = Protocol.readHeader(msg, null);
                if (type == Protocol.PING_REPORT && msg.remaining() >= 4) {
                    conn.pingMs = msg.getInt();
                } else if (type == Protocol.SAVE_REQUEST) {
                    saveAskedBy = conn.name;
                } else if (type == Protocol.FAST_REQUEST && msg.remaining() >= 5) {
                    boolean on = msg.get() != 0;
                    float mult = msg.getFloat();
                    if (on && mult > 1f && mult <= 50f) {
                        fastMults.put(conn.id, new float[]{mult});
                        fastHeard.put(conn.id, System.nanoTime());
                    } else {
                        fastHeard.remove(conn.id);
                    }
                } else if (type == Protocol.PAUSE_REQUEST && msg.remaining() >= 1) {
                    boolean p = msg.get() != 0;
                    boolean waited = clock.isHeld();
                    if (clock.setPaused(p, conn.id, System.nanoTime())) {
                        notices.add(conn.name + (p ? " paused the game." : waited
                                ? " resumed the game, so it stopped waiting for joining players." : " resumed the game."));
                    }
                } else if (type == Protocol.PLAYER_MSG && msg.remaining() >= 9) {
                    int to = msg.getInt();
                    msg.getInt(); // who they say they are: we know who they are
                    byte channel = msg.get();
                    String json = Protocol.getLongString(msg);
                    if (channel == CHANNEL_OBJECTS) {
                        objectMessage(conn, json); // the host keeps the claims, whoever it's addressed to
                    } else if (to == TO_ALL) {
                        // To everyone: ours, and on to every other player.
                        received(channel, new PlayerMessage(conn.id, conn.name, json));
                        for (Conn c : conns) {
                            if (c == conn) continue;
                            try {
                                c.send(playerMessage(TO_ALL, conn.id, channel, json));
                            } catch (IOException e) {
                                closeQuietly(c.socket);
                            }
                        }
                    } else if (to == HOST_ID) {
                        received(channel, new PlayerMessage(conn.id, conn.name, json));
                    } else {
                        Conn target = null;
                        for (Conn c : conns) if (c.id == to) target = c;
                        if (target == null) {
                            conn.send(playerMessage(conn.id, to, channel, LEFT_JSON)); // they're gone
                        } else {
                            try {
                                target.send(playerMessage(to, conn.id, channel, json));
                            } catch (IOException e) {
                                closeQuietly(target.socket);
                            }
                        }
                    }
                } else if (type == Protocol.WORLD_EVENT) {
                    String json = Protocol.getLongString(msg);
                    worldEvents.add(json);
                    ByteBuffer ev = Protocol.begin(Protocol.WORLD_EVENT, Protocol.longStringSize(json));
                    Protocol.putLongString(ev, json);
                    ev.flip();
                    relay(conn, ev);
                } else if (type == Protocol.NPC_RESET) {
                    msg.getInt(); // publisher: it's them
                    String loc = Protocol.getString(msg);
                    feed(conn.id).reset(loc);
                    relay(conn, npcResetMessage(conn.id, loc));
                } else if (type == Protocol.NPC_INFO) {
                    msg.getInt();
                    String loc = Protocol.getString(msg);
                    NpcInfo info = NpcInfo.read(msg);
                    Feed f = feed(conn.id);
                    if (loc.equals(f.location)) f.infos.put(info.handle, info);
                    relay(conn, npcInfoMessage(conn.id, loc, info));
                } else if (type == Protocol.NPC_GONE) {
                    msg.getInt();
                    String loc = Protocol.getString(msg);
                    int handle = msg.getInt();
                    Feed f = feed(conn.id);
                    if (loc.equals(f.location)) {
                        f.infos.remove(handle);
                        f.states.remove(handle);
                    }
                    ByteBuffer gone = Protocol.begin(Protocol.NPC_GONE, 4 + Protocol.stringSize(loc) + 4);
                    gone.putInt(conn.id);
                    Protocol.putString(gone, loc);
                    gone.putInt(handle).flip();
                    relay(conn, gone);
                } else if (type == Protocol.MARKET_REQUEST) {
                    marketEvents.add(new Object[]{MARKET_EVENT_REQUEST, conn.id, conn.name, Protocol.getString(msg)});
                } else if (type == Protocol.MARKET_TRADE) {
                    marketEvents.add(new Object[]{MARKET_EVENT_TRADE, conn.id, conn.name, Protocol.getLongString(msg)});
                } else if (type == Protocol.FLEET_COMP) {
                    msg.getInt(); // the sender's id: we already know who they are
                    FleetComp c = FleetComp.read(msg);
                    comps.put(conn.id, c);
                    ByteBuffer relay = compMessage(conn.id, c);
                    for (Conn other : conns) {
                        if (other == conn) continue;
                        try {
                            other.send(relay.duplicate());
                        } catch (IOException e) {
                            closeQuietly(other.socket);
                        }
                    }
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
                objectLocks.releaseAll(conn.id); // what they were salvaging is free
                udpAddresses.remove(conn.id);
                comps.remove(conn.id);
                fastHeard.remove(conn.id);
                fastMults.remove(conn.id);
                // Anyone talking or trading with them, or holding fleets for or from them, is told.
                for (byte ch : new byte[]{CHANNEL_TALK, CHANNEL_LOCK, CHANNEL_BATTLE}) {
                    received(ch, new PlayerMessage(conn.id, conn.name, LEFT_JSON));
                    outbox.add(playerMessage(-1, conn.id, ch, LEFT_JSON));
                }
                // Their NPC fleets go with them.
                feeds.remove(conn.id);
                outbox.add(npcResetMessage(conn.id, ""));
            }
            if (conn != null && conns.remove(conn) && alive(gen)) {
                if (!conn.replaced) notices.add(conn.name + " left");
                refreshHostPlayers();
                broadcastPlayers();
            }
        }
    }

    /**
     * The host's own launcher: a friend is about to download the save. The
     * game thread saves a fresh copy (and may hold the clock until they
     * join); we answer when it's done so the launcher shares the new file.
     */
    private void localSave(Socket s, ByteBuffer msg) throws IOException {
        if (!s.getInetAddress().isLoopbackAddress()) return; // only this PC's launcher
        String friend = Protocol.cleanName(msg.remaining() >= 2 ? Protocol.getString(msg) : "A friend");
        SaveRequest r = new SaveRequest(friend, System.nanoTime() + SAVE_WAIT_MS * 1000000L);
        saveRequests.add(r);
        boolean answered = r.await(SAVE_WAIT_MS);
        boolean ok = answered && r.ok;
        String text = answered ? r.message : "the game didn't get to it in time (is it in a battle?)";
        ByteBuffer bb = Protocol.begin(Protocol.LOCAL_SAVE_DONE, 1 + Protocol.stringSize(text));
        bb.put((byte) (ok ? 1 : 0));
        Protocol.putString(bb, text);
        bb.flip();
        Frames.write(s.getOutputStream(), bb);
    }

    /** Host: passes a message from one client on to all the others. */
    private void relay(Conn from, ByteBuffer msg) {
        for (Conn other : conns) {
            if (other == from) continue;
            try {
                other.send(msg.duplicate());
            } catch (IOException e) {
                closeQuietly(other.socket);
            }
        }
    }

    private static ByteBuffer compMessage(int playerId, FleetComp c) {
        ByteBuffer bb = Protocol.begin(Protocol.FLEET_COMP, 4 + c.size());
        bb.putInt(playerId);
        c.write(bb);
        bb.flip();
        return bb;
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
                } else if (type == Protocol.NPC_STATE) {
                    relayNpcStates(u, bb, p);
                } else if (type == Protocol.COMBAT_UDP) {
                    relayCombat(u, bb, p);
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

    /**
     * A client's NPC positions: kept if we follow their world, and passed on
     * to the other players who follow it and are in that location.
     */
    private void relayNpcStates(DatagramSocket u, ByteBuffer bb, DatagramPacket p) {
        int publisher = bb.getInt();
        Conn owner = null;
        for (Conn c : conns) if (c.id == publisher) owner = c;
        if (owner == null || !owner.socket.getInetAddress().equals(p.getAddress())) return;
        String loc = Protocol.getString(bb);
        Feed f = feeds.get(publisher);
        if (f == null || !loc.equals(f.location)) return; // from before they moved
        readNpcStates(bb, f, System.nanoTime());
        Map<Integer, Integer> fol = follows;
        for (Map.Entry<Integer, SocketAddress> e : udpAddresses.entrySet()) {
            int to = e.getKey();
            Integer o = fol.get(to);
            if (to == publisher || o == null || o != publisher) continue;
            FleetState.Received r = remoteFleets.get(to);
            if (r == null || !loc.equals(r.state.location)) continue;
            try {
                u.send(new DatagramPacket(p.getData(), p.getOffset(), p.getLength(), e.getValue()));
            } catch (IOException ignored) {
                // the next tick sends them again
            }
        }
    }

    /** A joint battle datagram from a client: ours, or forwarded as is to the player it's for. */
    private void relayCombat(DatagramSocket u, ByteBuffer bb, DatagramPacket p) {
        int to = bb.getInt();
        int from = bb.getInt();
        Conn owner = null;
        for (Conn c : conns) if (c.id == from) owner = c;
        if (owner == null || !owner.socket.getInetAddress().equals(p.getAddress())) return;
        if (to == HOST_ID) {
            receivedCombat(from, bb);
            return;
        }
        SocketAddress a = udpAddresses.get(to);
        if (a == null) return;
        try {
            u.send(new DatagramPacket(p.getData(), p.getOffset(), p.getLength(), a));
        } catch (IOException ignored) {
            // the next one replaces it
        }
    }

    /** Host: who follows whom, from everyone's latest position; told to everyone when it changes. */
    private void planZones() {
        long now = System.nanoTime();
        List<FleetState> all = new ArrayList<FleetState>();
        FleetState mine = localFleet;
        if (mine != null) {
            FleetState m = mine.copy();
            m.playerId = HOST_ID;
            all.add(m);
        }
        for (FleetState.Received r : remoteFleets.values()) {
            if (now - r.arrivedNanos < STALE_NANOS) all.add(r.state);
        }
        Map<Integer, Integer> next = planner.plan(all, now);
        if (next.equals(follows)) return;
        follows = next;
        ByteBuffer z = zonesMessage(next);
        for (Conn c : conns) {
            try {
                c.send(z.duplicate());
            } catch (IOException e) {
                closeQuietly(c.socket);
            }
        }
    }

    /** Every tick, sends each client every other player's fleet (including the host's). */
    private void hostSyncLoop(int gen) {
        while (alive(gen)) {
            sleep(SYNC_INTERVAL_MS);
            if (!alive(gen)) return;
            ByteBuffer out;
            while ((out = outbox.poll()) != null) {
                for (Conn c : conns) {
                    try {
                        c.send(out.duplicate());
                    } catch (IOException e) {
                        closeQuietly(c.socket); // its reader thread cleans up
                    }
                }
            }
            Object[] d;
            while ((d = directed.poll()) != null) {
                for (Conn c : conns) {
                    if (c.id != (Integer) d[0]) continue;
                    try {
                        c.send((ByteBuffer) d[1]);
                    } catch (IOException e) {
                        closeQuietly(c.socket);
                    }
                }
            }
            DatagramSocket cu = hostUdp;
            long tick = System.nanoTime();
            // Nobody on the map (everyone in a battle, dialog or menu): the world waits. Checked here, not in the
            // game, because the campaign doesn't run during battles. A host whose world has closed doesn't count.
            clock.setIdle(holdWhileBusy && clock.isStarted() && !anyoneOnMap(tick), tick);
            if (gameAliveAt != 0 && tick - gameAliveAt > HOST_GONE_NANOS) {
                // Our world closed (back at the main menu, say): stop hosting, so friends are told the session is
                // over and nobody can join a session with no world behind it. Loading the world again hosts again.
                notices.add("Your world closed, so you stopped hosting. Load it again to host.");
                stopWith(gen, null, "Not connected");
                return;
            }
            if (cu != null && clock.isStarted()) {
                ClockState cs = clock.snapshot(tick);
                ByteBuffer cb = Protocol.begin(Protocol.CLOCK, cs.size());
                cs.write(cb);
                for (SocketAddress to : udpAddresses.values()) {
                    try {
                        cu.send(new DatagramPacket(cb.array(), cb.position(), to));
                    } catch (IOException ignored) {
                        // the next tick sends it again
                    }
                }
            }
            planZones();
            if (cu != null) sendNpcStates(cu);
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

    /** Our NPC fleet positions, to the players who follow the host's world and are in that location. */
    private void sendNpcStates(DatagramSocket u) {
        String loc = pubLocation;
        List<NpcState> states = pubStates;
        if (loc.isEmpty() || states.isEmpty()) return;
        Map<Integer, Integer> fol = follows;
        List<SocketAddress> targets = new ArrayList<SocketAddress>();
        for (Map.Entry<Integer, SocketAddress> e : udpAddresses.entrySet()) {
            Integer o = fol.get(e.getKey());
            if (o == null || o != HOST_ID) continue;
            FleetState.Received r = remoteFleets.get(e.getKey());
            if (r != null && loc.equals(r.state.location)) targets.add(e.getValue());
        }
        if (targets.isEmpty()) return;
        for (ByteBuffer bb : npcStatePackets(HOST_ID, loc, states)) {
            for (SocketAddress to : targets) {
                try {
                    u.send(new DatagramPacket(bb.array(), bb.position(), to));
                } catch (IOException ignored) {
                    // the next tick sends them again
                }
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
            ByteBuffer z = zonesMessage(follows); // again now and then, in case one crossed a join
            for (Conn c : conns) {
                try {
                    c.send(z.duplicate());
                } catch (IOException e) {
                    closeQuietly(c.socket);
                }
            }
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
            ByteBuffer hello = Protocol.begin(Protocol.HELLO,
                    Protocol.stringSize(localName) + Protocol.stringSize(BuildInfo.VERSION));
            Protocol.putString(hello, localName);
            Protocol.putString(hello, BuildInfo.VERSION);
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
            FleetComp mine = localComp;
            if (mine != null) outbox.add(compMessage(id, mine));
            final InetSocketAddress udpTarget = target;
            udpToHost = target;
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
                } else if (type == Protocol.FLEET_COMP) {
                    int pid = msg.getInt();
                    comps.put(pid, FleetComp.read(msg));
                } else if (type == Protocol.PLAYER_MSG && msg.remaining() >= 9) {
                    msg.getInt(); // to: us
                    int from = msg.getInt();
                    byte channel = msg.get();
                    received(channel, new PlayerMessage(from, playerName(from), Protocol.getLongString(msg)));
                } else if (type == Protocol.ZONES) {
                    follows = readZones(msg);
                } else if (type == Protocol.WORLD_EVENT) {
                    worldEvents.add(Protocol.getLongString(msg));
                } else if (type == Protocol.MARKET_SNAPSHOT) {
                    String marketId = Protocol.getString(msg);
                    marketSnapshots.put(marketId, Protocol.getLongString(msg));
                } else if (type == Protocol.HOST_ORDER) {
                    String order = Protocol.getLongString(msg);
                    hostOrders.add(order);
                    if (order.contains("\"end\"") || order.contains("\"gone\"")) hostEnded = true;
                } else if (type == Protocol.NPC_RESET) {
                    int pub = msg.getInt();
                    String loc = Protocol.getString(msg);
                    if (pub != myId) feed(pub).reset(loc);
                } else if (type == Protocol.NPC_INFO) {
                    int pub = msg.getInt();
                    String loc = Protocol.getString(msg);
                    NpcInfo info = NpcInfo.read(msg);
                    Feed f = feeds.get(pub);
                    if (pub != myId && f != null && loc.equals(f.location)) f.infos.put(info.handle, info);
                } else if (type == Protocol.NPC_GONE) {
                    int pub = msg.getInt();
                    String loc = Protocol.getString(msg);
                    int handle = msg.getInt();
                    Feed f = feeds.get(pub);
                    if (f != null && loc.equals(f.location)) {
                        f.infos.remove(handle);
                        f.states.remove(handle);
                    }
                } else if (type == Protocol.BYE) {
                    String why = Protocol.getString(msg);
                    endReason = "Host: " + why;
                    if (why.startsWith("The host stopped")) hostEnded = true;
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
                Conn host = toHost;
                ByteBuffer queued;
                while (host != null && (queued = outbox.poll()) != null) host.send(queued);
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
                String loc = pubLocation;
                List<NpcState> states = pubStates;
                if (myId >= 0 && !loc.isEmpty() && !states.isEmpty() && hasFollowers(myId)) {
                    for (ByteBuffer bb : npcStatePackets(myId, loc, states)) {
                        udp.send(new DatagramPacket(bb.array(), bb.position(), target));
                    }
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
                } else if (type == Protocol.CLOCK) {
                    ClockState cs = ClockState.read(r);
                    ClockState.Received old = remoteClock;
                    if (old == null || cs.seq > old.state.seq || old.state.seq - cs.seq > 1000) {
                        remoteClock = new ClockState.Received(cs, System.nanoTime());
                    }
                } else if (type == Protocol.COMBAT_UDP) {
                    r.getInt(); // to: us
                    receivedCombat(r.getInt(), r);
                } else if (type == Protocol.NPC_STATE) {
                    int pub = r.getInt();
                    String loc = Protocol.getString(r);
                    Feed f = feeds.get(pub);
                    if (f == null || !loc.equals(f.location)) continue; // from before they moved
                    readNpcStates(r, f, System.nanoTime());
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
        comps.clear();
        outbox.clear();
        remoteClock = null;
        clock.reset();
        clearNpc();
        SaveRequest pending;
        while ((pending = saveRequests.poll()) != null) pending.complete(false, "hosting stopped");
        pingMs = -1;
        myId = -1;
        state = State.IDLE;
        lastError = error;
        status = newStatus;
    }

    private void clearNpc() {
        playerMessages.clear();
        lockMessages.clear();
        battleMessages.clear();
        socialMessages.clear();
        objectReplies.clear();
        objectLocks.clear();
        combatPackets.clear();
        worldEvents.clear();
        marketEvents.clear();
        marketSnapshots.clear();
        directed.clear();
        follows = Collections.emptyMap();
        planner.reset();
        pubLocation = "";
        pubInfos.clear();
        pubStates = Collections.emptyList();
        feeds.clear();
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
        /** Closed because the same player reconnected: its cleanup doesn't announce a leave. */
        volatile boolean replaced;

        Conn(Socket socket, int id, String name) {
            this.socket = socket;
            this.id = id;
            this.name = name;
        }

        synchronized void send(ByteBuffer msg) throws IOException {
            Frames.write(socket.getOutputStream(), msg);
        }
    }

    /**
     * The launcher asking the host's game to save before a friend downloads.
     * The game thread calls {@link #complete} once it has saved (or can't).
     */
    public static final class SaveRequest {
        public final String friend;
        private final long deadlineNanos;
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile boolean ok;
        private volatile String message = "";

        SaveRequest(String friend, long deadlineNanos) {
            this.friend = friend;
            this.deadlineNanos = deadlineNanos;
        }

        /** The launcher stopped waiting: too late to be useful. */
        public boolean isExpired() {
            return System.nanoTime() - deadlineNanos > 0;
        }

        public void complete(boolean saved, String text) {
            ok = saved;
            message = text == null ? "" : text;
            done.countDown();
        }

        boolean await(long ms) {
            try {
                return done.await(ms, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static final class JoinFailure extends Exception {
        private static final long serialVersionUID = 1L;

        JoinFailure(String message) {
            super(message);
        }
    }
}
