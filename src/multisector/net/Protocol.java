package multisector.net;

import java.nio.ByteBuffer;

/**
 * Message types and encoding helpers for the wire format.
 *
 * Every message starts with a 7-byte header: magic (int), protocol version
 * (short), message type (byte). TCP messages are wrapped in {@link Frames};
 * UDP messages are single datagrams of at most {@link NetConfig#MAX_DATAGRAM}.
 *
 * Strings are a short length followed by UTF-16 chars. That avoids
 * java.nio.charset, which the mod sandbox may block.
 */
public final class Protocol {

    public static final int HEADER = 7;

    // TCP
    /** client -> host: name */
    public static final byte HELLO = 1;
    /** host -> client: your player id, host name */
    public static final byte WELCOME = 2;
    /** host -> client: reason. Connection closes afterwards. */
    public static final byte REJECT = 3;
    /** host -> clients: count, then (id, name, pingMs) per player */
    public static final byte PLAYER_LIST = 4;
    /** either way: polite disconnect, reason */
    public static final byte BYE = 5;
    /** client -> host: my current round-trip ms */
    public static final byte PING_REPORT = 6;
    /** client -> host: paused(byte). Anyone can pause or resume the shared clock. */
    public static final byte PAUSE_REQUEST = 7;
    /** client -> host: FleetComp of my fleet. host -> clients: playerId(int), FleetComp. */
    public static final byte FLEET_COMP = 8;

    /** host -> clients: locationId. "The NPC fleets I'm sending are now for this star system" ("" = none). */
    public static final byte NPC_RESET = 9;
    /** host -> clients: locationId, NpcInfo. A fleet appeared or changed. */
    public static final byte NPC_INFO = 15;
    /** host -> clients: locationId, handle. That fleet left the system or is gone. */
    public static final byte NPC_GONE = 16;

    // TCP: locking an NPC fleet a player interacts with (see sync.NpcSync)
    /** client -> host: handle. "I'm opening this fleet's dialog: hold it for me." */
    public static final byte CLAIM_REQUEST = 26;
    /** host -> client: handle, granted(byte), holderName, fleetJson (long string; the exact fleet when granted) */
    public static final byte CLAIM_REPLY = 27;
    /** client -> host: handle. "Done with it, nothing changed." */
    public static final byte CLAIM_RELEASE = 28;
    /** client -> host: handle, count(int), lost member ids (strings). Applies the losses, then releases. */
    public static final byte BATTLE_RESULT = 29;

    // TCP: markets (see sync.MarketSync). The host's markets are the real ones.
    /** client -> host: marketId. "I'm at this market: send me its stock." */
    public static final byte MARKET_REQUEST = 32;
    /** host -> client: marketId, snapshot json (long string; "" if the host doesn't have it) */
    public static final byte MARKET_SNAPSHOT = 33;
    /** client -> host: one trade as json (long string): what was bought and sold, in which submarket */
    public static final byte MARKET_TRADE = 34;

    // TCP: player to player (see sync.PlayerTalk). Goes through the host, which fills in who sent it.
    /** either way: int to, int from, json (long string). Hails, answers, offers. */
    public static final byte PLAYER_MSG = 35;

    // TCP from the host's own launcher (loopback only) to the host's game
    /** launcher -> game: friendName. "A friend is about to download: save now (and hold the clock)." */
    public static final byte LOCAL_SAVE = 30;
    /** game -> launcher: saved(byte), message */
    public static final byte LOCAL_SAVE_DONE = 31;

    // TCP, launcher lobby (port + 1)
    /** client -> host: name, build version, purpose (one of the HELLO_FOR_ values) */
    public static final byte LOBBY_HELLO = 20;
    /** Just looking (the launcher's Check button). */
    public static final byte HELLO_FOR_CHECK = 0;
    /** Starting a download: the host saves a fresh copy first. */
    public static final byte HELLO_FOR_DOWNLOAD = 1;
    /** An extra connection of a download that already started. */
    public static final byte HELLO_FOR_EXTRA = 2;
    /**
     * host -> client: hostName, characterName, saveDate, gameVersion, saveBytes(long), gamePort(int),
     * modCount(int), modIds..., zipBytes(long), zipSha256
     */
    public static final byte LOBBY_INFO = 21;
    /** client -> host: zipSha256, offset(long), length(int). Repeatable on one connection. */
    public static final byte LOBBY_GET_RANGE = 22;
    /** host -> client: offset(long), length(int); then exactly length raw bytes of the zipped save */
    public static final byte LOBBY_RANGE = 23;

    // UDP, launcher lobby (port + 1): the save in small pieces, so it fits through
    // links that silently drop full-size TCP packets (Hamachi "MTU black hole")
    /** client -> host: saveKey(long), count(int), chunk indices(int)... */
    public static final byte XFER_GET = 24;
    /** host -> client: saveKey(long), index(int), length(int), bytes */
    public static final byte XFER_DATA = 25;
    /** Payload per piece: with headers, well under every tunnel's packet limit. */
    public static final int XFER_CHUNK = 1024;
    /** Most indices one XFER_GET may carry (fits in one datagram). */
    public static final int XFER_MAX_REQUEST = 256;

    /** The save's short id for UDP packets: the first 16 hex digits of its SHA-256. */
    public static long saveKey(String sha256Hex) {
        return Long.parseUnsignedLong(sha256Hex.substring(0, 16), 16);
    }

    // UDP
    /** client -> host: seq, sentNanos. Host echoes it back as PONG. */
    public static final byte PING = 10;
    public static final byte PONG = 11;
    /** client -> host: one FleetState (the sender's own fleet) */
    public static final byte FLEET_STATE = 12;
    /** host -> client: count(short), then FleetStates of every other player */
    public static final byte WORLD_STATE = 13;
    /** host -> client: ClockState, the shared campaign clock */
    public static final byte CLOCK = 14;
    /** host -> client in the same star system: locationId, count(short), NpcStates */
    public static final byte NPC_STATE = 17;
    /** offset of the type byte inside a packet, so the host can flip PING to PONG in place */
    public static final int TYPE_OFFSET = 6;
    /** header + seq(4) + sentNanos(8) */
    public static final int PING_SIZE = HEADER + 12;

    public static final int MAX_NAME = 32;

    private Protocol() {
    }

    public static ByteBuffer begin(byte type, int capacity) {
        ByteBuffer bb = ByteBuffer.allocate(HEADER + capacity);
        bb.putInt(NetConfig.MAGIC).putShort(NetConfig.PROTOCOL_VERSION).put(type);
        return bb;
    }

    /**
     * Reads and checks the header. Returns the message type, or -1 if the
     * magic is wrong or the buffer is too short. If versionOut is given, the
     * sender's protocol version is written to versionOut[0].
     */
    public static int readHeader(ByteBuffer bb, short[] versionOut) {
        if (bb.remaining() < HEADER) return -1;
        if (bb.getInt() != NetConfig.MAGIC) return -1;
        short v = bb.getShort();
        if (versionOut != null) versionOut[0] = v;
        return bb.get();
    }

    public static int stringSize(String s) {
        return 2 + 2 * Math.min(s.length(), Short.MAX_VALUE);
    }

    public static void putString(ByteBuffer bb, String s) {
        int n = Math.min(s.length(), Short.MAX_VALUE);
        bb.putShort((short) n);
        for (int i = 0; i < n; i++) bb.putChar(s.charAt(i));
    }

    public static String getString(ByteBuffer bb) {
        int n = bb.getShort();
        if (n < 0 || n * 2 > bb.remaining()) throw new IllegalArgumentException("bad string length " + n);
        char[] c = new char[n];
        for (int i = 0; i < n; i++) c[i] = bb.getChar();
        return new String(c);
    }

    /** Size of a string written with {@link #putLongString}: an int length, then UTF-16 chars. */
    public static int longStringSize(String s) {
        return 4 + 2 * s.length();
    }

    /** For text that may pass 32K chars (a fleet's exact description). TCP only. */
    public static void putLongString(ByteBuffer bb, String s) {
        bb.putInt(s.length());
        for (int i = 0; i < s.length(); i++) bb.putChar(s.charAt(i));
    }

    public static String getLongString(ByteBuffer bb) {
        int n = bb.getInt();
        if (n < 0 || n * 2L > bb.remaining()) throw new IllegalArgumentException("bad long string length " + n);
        char[] c = new char[n];
        for (int i = 0; i < n; i++) c[i] = bb.getChar();
        return new String(c);
    }

    /** Trims a player name to something safe to show in the UI. */
    public static String cleanName(String name) {
        if (name == null) return "Player";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < MAX_NAME; i++) {
            char c = name.charAt(i);
            if (c >= 32 && c != 127) sb.append(c);
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? "Player" : s;
    }
}
