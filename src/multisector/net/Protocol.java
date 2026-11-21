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

    // TCP, launcher lobby (port + 1)
    /** client -> host: name */
    public static final byte LOBBY_HELLO = 20;
    /** host -> client: hostName, characterName, saveDate, gameVersion, saveBytes(long), gamePort(int), modCount(int), modIds... */
    public static final byte LOBBY_INFO = 21;
    /** client -> host: send me the save */
    public static final byte LOBBY_GET_SAVE = 22;
    /** host -> client: totalBytes(long), fileCount(int); a raw zip stream of the save folder follows */
    public static final byte LOBBY_SAVE_BEGIN = 23;

    // UDP
    /** client -> host: seq, sentNanos. Host echoes it back as PONG. */
    public static final byte PING = 10;
    public static final byte PONG = 11;
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
