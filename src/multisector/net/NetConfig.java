package multisector.net;

import java.net.InetSocketAddress;

/**
 * Network constants shared by the host, the client and the connection tester.
 */
public final class NetConfig {

    /** Default port for both TCP (reliable) and UDP (fast updates). */
    public static final int DEFAULT_PORT = 7777;

    /**
     * The launcher's lobby (save sharing) listens on the game port + 1, so a
     * host runs both on one PC: game 7777, launcher 7778.
     */
    public static int lobbyPort(int gamePort) {
        return gamePort + 1;
    }

    /** File in saves/common through which the launcher tells the mod to host or join. */
    public static final String SESSION_FILE = "multisector_session.json";
    /** The game asks the launcher for a fresh copy of the host's world (saves/common, see SessionGuard). */
    public static final String REJOIN_FILE = "multisector_rejoin.json";

    /**
     * Largest UDP payload we send. Hamachi's adapter MTU is 1404 bytes, and
     * other virtual LANs (Tailscale 1280, ZeroTier 2800) and some ISPs are
     * smaller than Ethernet's 1500. Staying at 1200 avoids IP fragmentation,
     * where losing any fragment loses the whole datagram.
     */
    public static final int MAX_DATAGRAM = 1200;

    /** Largest TCP frame we accept, to stop a bad length prefix allocating gigabytes. */
    public static final int MAX_FRAME = 8 * 1024 * 1024;

    /** "MSEC": first four bytes of every packet, so stray traffic on the port is ignored. */
    public static final int MAGIC = 0x4D534543;

    /** Bump when the wire format changes. Host and client must match. */
    public static final short PROTOCOL_VERSION = 24;

    public static final int CONNECT_TIMEOUT_MS = 5000;

    private NetConfig() {
    }

    /**
     * Address for the host to listen on: the wildcard address, so connections
     * arriving on any adapter (LAN, Hamachi, Tailscale, ...) are accepted.
     * Binding to one specific IP is the classic reason Hamachi joins fail.
     */
    public static InetSocketAddress listenAddress(int port) {
        return new InetSocketAddress(port);
    }
}
