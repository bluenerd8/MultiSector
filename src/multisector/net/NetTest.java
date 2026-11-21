package multisector.net;

import java.io.IOException;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Standalone connection tester, run outside the game (see "dev nettest", or tools/connection-test.cmd in the mod folder).
 * Uses the same protocol as the mod, so "join" works against both
 * "nettest host" and a host running inside the game.
 *
 *   ip                    list this PC's addresses
 *   host [port]           host a session (the same code the mod runs)
 *   join <ip> [port]      TCP handshake, then 20 full-size UDP pings
 */
public final class NetTest {

    private static final int PING_COUNT = 20;

    private NetTest() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        if (mode.equals("ip")) {
            printAddresses();
        } else if (mode.equals("host")) {
            host(args.length > 1 ? parsePort(args[1]) : NetConfig.DEFAULT_PORT);
        } else if (mode.equals("join") && args.length > 1) {
            boolean ok = join(args[1], args.length > 2 ? parsePort(args[2]) : NetConfig.DEFAULT_PORT);
            System.exit(ok ? 0 : 1);
        } else {
            System.out.println("usage: nettest ip | host [port] | join <ip> [port]");
            System.exit(2);
        }
    }

    // ------------------------------------------------------------------ ip

    private static void printAddresses() {
        List<AddressFinder.Candidate> all = AddressFinder.find();
        System.out.println("This PC's addresses:");
        if (all.isEmpty()) System.out.println("  (none found)");
        for (AddressFinder.Candidate c : all) System.out.println("  " + c);

        AddressFinder.Candidate hamachi = AddressFinder.first(all, AddressFinder.Kind.HAMACHI);
        System.out.println();
        if (hamachi != null) {
            System.out.println("Hamachi address: " + hamachi.ip() + "   <- give this to friends on your Hamachi network");
        } else {
            System.out.println("No Hamachi address found. Is Hamachi running and switched on (power button in the Hamachi window)?");
            AddressFinder.Candidate best = AddressFinder.preferred(all);
            if (best != null) System.out.println("Best other address: " + best);
        }
    }

    // ---------------------------------------------------------------- host

    private static void host(int port) throws Exception {
        printAddresses();
        System.out.println();
        Session session = Session.get();
        try {
            session.host(port, "nettest host");
        } catch (IOException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }
        System.out.println("Hosting on port " + port + " (TCP + UDP, all adapters). Press Ctrl+C to stop.");
        System.out.println("Your friend runs tools\\connection-test.cmd and types your address"
                + (port != NetConfig.DEFAULT_PORT ? " (port " + port + ")" : "") + ".");
        System.out.println("If Windows asks whether to allow Java, tick BOTH private and public networks.");
        System.out.println();
        while (session.isActive()) {
            String n;
            while ((n = session.pollNotice()) != null) log(n);
            Thread.sleep(100);
        }
        System.out.println("Stopped: " + session.getLastError());
    }

    // ---------------------------------------------------------------- join

    private static boolean join(String host, int port) {
        InetSocketAddress target;
        try {
            target = new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (UnknownHostException e) {
            System.out.println("Can't understand address \"" + host + "\". Use the host's IP, e.g. 25.12.34.56.");
            return false;
        }
        System.out.println("Testing connection to " + target.getAddress().getHostAddress() + ":" + port);
        if (AddressFinder.classify(target.getAddress(), "") == AddressFinder.Kind.HAMACHI
                && AddressFinder.first(AddressFinder.find(), AddressFinder.Kind.HAMACHI) == null) {
            System.out.println("  Note: that's a Hamachi address, but this PC has no Hamachi address. Switch Hamachi on first.");
        }
        System.out.println();

        Socket tcp = new Socket();
        try {
            // 1. TCP handshake, exactly as the mod does it
            long t0 = System.nanoTime();
            tcp.connect(target, NetConfig.CONNECT_TIMEOUT_MS);
            tcp.setSoTimeout(NetConfig.CONNECT_TIMEOUT_MS);
            ByteBuffer hello = Protocol.begin(Protocol.HELLO, Protocol.stringSize("nettest"));
            Protocol.putString(hello, "nettest");
            hello.flip();
            Frames.write(tcp.getOutputStream(), hello);
            ByteBuffer reply = Frames.read(tcp.getInputStream());
            long tcpMs = (System.nanoTime() - t0) / 1000000L;
            int type = reply == null ? -1 : Protocol.readHeader(reply, null);
            if (type == Protocol.REJECT) {
                System.out.println("[FAIL] TCP: host refused: " + Protocol.getString(reply));
                return false;
            }
            if (type != Protocol.WELCOME) {
                System.out.println("[FAIL] TCP: something answered on port " + port + ", but it isn't a MultiSector host.");
                return false;
            }
            reply.getInt();
            System.out.println("[ OK ] TCP handshake with \"" + Protocol.getString(reply) + "\" in " + tcpMs + " ms");

            // 2. UDP pings at the full packet size the mod may use
            boolean ok = udpPings(target);

            ByteBuffer bye = Protocol.begin(Protocol.BYE, Protocol.stringSize("test done"));
            Protocol.putString(bye, "test done");
            bye.flip();
            Frames.write(tcp.getOutputStream(), bye);
            return ok;
        } catch (ConnectException e) {
            System.out.println("[FAIL] TCP: connection refused.");
            System.out.println("  Reached the host's PC, but nothing is listening on port " + port + ".");
            System.out.println("  - Is the host hosting (in game, or the connection test)? Same port number?");
            return false;
        } catch (SocketTimeoutException e) {
            System.out.println("[FAIL] TCP: no answer within " + NetConfig.CONNECT_TIMEOUT_MS / 1000 + " s.");
            printUnreachableHints();
            return false;
        } catch (NoRouteToHostException e) {
            System.out.println("[FAIL] TCP: no route to host.");
            printUnreachableHints();
            return false;
        } catch (IOException e) {
            System.out.println("[FAIL] TCP: " + e);
            printUnreachableHints();
            return false;
        } finally {
            try {
                tcp.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void printUnreachableHints() {
        System.out.println("  Most likely causes, in order:");
        System.out.println("  - Windows Firewall on the host. Hamachi counts as a Public network, which blocks");
        System.out.println("    incoming connections. The host double-clicks tools\\firewall.cmd once.");
        System.out.println("  - Not on the same Hamachi network, or someone is offline. In the Hamachi window");
        System.out.println("    both of you need a green dot. Yellow means the peers can't reach each other.");
        System.out.println("  - Wrong address. Use the host's 25.x.x.x address shown in their Multiplayer window (F8).");
    }

    private static boolean udpPings(InetSocketAddress target) throws IOException {
        DatagramSocket udp = new DatagramSocket();
        try {
            udp.setSoTimeout(1000);
            byte[] out = new byte[NetConfig.MAX_DATAGRAM];
            byte[] in = new byte[2048];
            int received = 0;
            long min = Long.MAX_VALUE, max = 0, sum = 0;

            for (int seq = 0; seq < PING_COUNT; seq++) {
                ByteBuffer bb = ByteBuffer.wrap(out);
                bb.putInt(NetConfig.MAGIC).putShort(NetConfig.PROTOCOL_VERSION).put(Protocol.PING)
                        .putInt(seq).putLong(System.nanoTime());
                udp.send(new DatagramPacket(out, out.length, target));

                long deadline = System.currentTimeMillis() + 1000;
                while (System.currentTimeMillis() < deadline) {
                    DatagramPacket p = new DatagramPacket(in, in.length);
                    try {
                        udp.receive(p);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    ByteBuffer r = ByteBuffer.wrap(in, 0, p.getLength());
                    if (p.getLength() < Protocol.PING_SIZE || Protocol.readHeader(r, null) != Protocol.PONG) continue;
                    if (r.getInt() != seq) continue; // late reply to an earlier ping
                    long rtt = (System.nanoTime() - r.getLong()) / 1000000L;
                    received++;
                    sum += rtt;
                    min = Math.min(min, rtt);
                    max = Math.max(max, rtt);
                    break;
                }
            }

            int lossPct = (PING_COUNT - received) * 100 / PING_COUNT;
            if (received == 0) {
                System.out.println("[FAIL] UDP: 0/" + PING_COUNT + " pings came back.");
                System.out.println("  TCP works but UDP doesn't, so a firewall is letting one through and not the other.");
                System.out.println("  The host should run tools\\firewall.cmd, which opens both TCP and UDP.");
                return false;
            }
            System.out.println((lossPct > 10 ? "[WARN]" : "[ OK ]") + " UDP: " + received + "/" + PING_COUNT
                    + " pings of " + NetConfig.MAX_DATAGRAM + " bytes, round trip min " + min
                    + " / avg " + (sum / received) + " / max " + max + " ms");
            if (lossPct > 10) {
                System.out.println("  " + lossPct + "% packet loss. On Hamachi this usually means the connection is relayed");
                System.out.println("  through Hamachi's servers instead of direct. Fast-moving ships will stutter.");
            }
            if (sum / received > 150) {
                System.out.println("  Average ping is high for real-time play. If you're both in the same region, check");
                System.out.println("  for a relayed Hamachi connection, or try Tailscale.");
            }
            System.out.println();
            System.out.println("Connection works. MultiSector should be able to connect over this address.");
            return true;
        } finally {
            udp.close();
        }
    }

    // ---------------------------------------------------------------- util

    private static int parsePort(String s) {
        try {
            int p = Integer.parseInt(s);
            if (p > 0 && p < 65536) return p;
        } catch (NumberFormatException ignored) {
        }
        System.out.println("Bad port \"" + s + "\", using " + NetConfig.DEFAULT_PORT);
        return NetConfig.DEFAULT_PORT;
    }

    private static void log(String msg) {
        System.out.println(String.format("[%tT] %s", System.currentTimeMillis(), msg));
    }
}
