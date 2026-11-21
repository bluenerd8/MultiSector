package multisector.net;

import java.io.IOException;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Standalone connection tester, run outside the game (see "dev nettest").
 * Lets two players check that Hamachi, another virtual LAN, or port
 * forwarding works before the real multiplayer code exists. It uses the same
 * port, framing and packet size limits the mod will use.
 *
 *   ip                    list this PC's addresses
 *   host [port]           listen for testers
 *   join <ip> [port]      connect to a host: TCP handshake, then 20 UDP pings
 */
public final class NetTest {

    private static final byte HELLO = 1;
    private static final byte WELCOME = 2;
    private static final byte REJECT = 3;
    private static final byte PING = 10;
    private static final byte PONG = 11;

    private static final int PING_COUNT = 20;
    /** magic(4) + version(2) + type(1) + seq(4) + sentNanos(8) */
    private static final int PING_HEADER = 19;

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

    private static List<AddressFinder.Candidate> printAddresses() {
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
        return all;
    }

    // ---------------------------------------------------------------- host

    private static void host(int port) throws Exception {
        printAddresses();
        System.out.println();

        ServerSocket tcp = new ServerSocket();
        tcp.setReuseAddress(true);
        tcp.bind(NetConfig.listenAddress(port));
        final DatagramSocket udp = new DatagramSocket(NetConfig.listenAddress(port));

        System.out.println("Hosting on port " + port + " (TCP + UDP, all adapters). Press Ctrl+C to stop.");
        System.out.println("Friend runs:  dev nettest join <your address>" + (port != NetConfig.DEFAULT_PORT ? " " + port : ""));
        System.out.println("If Windows asks whether to allow Java, tick BOTH private and public networks.");
        System.out.println();

        Thread echo = new Thread(new Runnable() {
            public void run() {
                udpEcho(udp);
            }
        }, "nettest-udp");
        echo.setDaemon(true);
        echo.start();

        while (true) {
            final Socket s = tcp.accept();
            Thread t = new Thread(new Runnable() {
                public void run() {
                    handleTcpClient(s);
                }
            }, "nettest-tcp");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void udpEcho(DatagramSocket udp) {
        byte[] buf = new byte[2048];
        Set<String> seen = new HashSet<String>();
        while (true) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                udp.receive(p);
                if (p.getLength() < PING_HEADER) continue;
                ByteBuffer bb = ByteBuffer.wrap(buf, 0, p.getLength());
                if (bb.getInt() != NetConfig.MAGIC) continue;
                bb.getShort();
                if (bb.get() != PING) continue;
                buf[6] = PONG; // same packet back, type flipped
                udp.send(new DatagramPacket(buf, p.getLength(), p.getSocketAddress()));
                String from = p.getSocketAddress().toString();
                if (seen.add(from)) log("UDP pings arriving from " + from + " (" + p.getLength() + " bytes each)");
            } catch (IOException e) {
                log("UDP error: " + e);
            }
        }
    }

    private static void handleTcpClient(Socket s) {
        SocketAddress from = s.getRemoteSocketAddress();
        try {
            s.setSoTimeout(NetConfig.CONNECT_TIMEOUT_MS);
            ByteBuffer hello = Frames.read(s.getInputStream());
            if (hello == null || hello.remaining() < 7 || hello.getInt() != NetConfig.MAGIC) {
                log("TCP " + from + " sent something that isn't MultiSector, closed");
                return;
            }
            short version = hello.getShort();
            byte type = hello.get();
            if (type != HELLO) {
                log("TCP " + from + " sent unexpected message type " + type);
                return;
            }
            boolean ok = version == NetConfig.PROTOCOL_VERSION;
            ByteBuffer reply = ByteBuffer.allocate(7);
            reply.putInt(NetConfig.MAGIC).putShort(NetConfig.PROTOCOL_VERSION).put(ok ? WELCOME : REJECT).flip();
            Frames.write(s.getOutputStream(), reply);
            if (!ok) {
                log("TCP " + from + " rejected: protocol " + version + ", host has " + NetConfig.PROTOCOL_VERSION);
                return;
            }
            log("TCP " + from + " connected");
            s.setSoTimeout(0);
            while (Frames.read(s.getInputStream()) != null) {
                // Tester sends nothing else; wait for it to hang up.
            }
            log("TCP " + from + " disconnected");
        } catch (IOException e) {
            log("TCP " + from + " dropped: " + e.getMessage());
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- join

    private static boolean join(String host, int port) {
        InetSocketAddress target;
        try {
            target = new InetSocketAddress(java.net.InetAddress.getByName(host), port);
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
            // 1. TCP handshake
            long t0 = System.nanoTime();
            tcp.connect(target, NetConfig.CONNECT_TIMEOUT_MS);
            tcp.setSoTimeout(NetConfig.CONNECT_TIMEOUT_MS);
            ByteBuffer hello = ByteBuffer.allocate(7);
            hello.putInt(NetConfig.MAGIC).putShort(NetConfig.PROTOCOL_VERSION).put(HELLO).flip();
            Frames.write(tcp.getOutputStream(), hello);
            ByteBuffer reply = Frames.read(tcp.getInputStream());
            long tcpMs = (System.nanoTime() - t0) / 1000000L;
            if (reply == null || reply.remaining() < 7 || reply.getInt() != NetConfig.MAGIC) {
                System.out.println("[FAIL] TCP: something answered on port " + port + ", but it isn't a MultiSector host.");
                return false;
            }
            short hostVersion = reply.getShort();
            if (reply.get() != WELCOME) {
                System.out.println("[FAIL] TCP: host rejected us. Host protocol " + hostVersion
                        + ", ours " + NetConfig.PROTOCOL_VERSION + ". Both of you need the same MultiSector version.");
                return false;
            }
            System.out.println("[ OK ] TCP handshake in " + tcpMs + " ms");

            // 2. UDP pings at the full packet size the mod will use
            return udpPings(target);
        } catch (ConnectException e) {
            System.out.println("[FAIL] TCP: connection refused.");
            System.out.println("  Reached the host's PC, but nothing is listening on port " + port + ".");
            System.out.println("  - Is the host running \"dev nettest host\"? Same port number?");
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
        System.out.println("    incoming connections. The host runs \"dev firewall\" once (asks for admin).");
        System.out.println("  - Not on the same Hamachi network, or someone is offline. In the Hamachi window");
        System.out.println("    both of you need a green dot. Yellow means the peers can't reach each other.");
        System.out.println("  - Wrong address. Use the host's 25.x.x.x address from \"dev ip\" on their PC.");
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
                bb.putInt(NetConfig.MAGIC).putShort(NetConfig.PROTOCOL_VERSION).put(PING).putInt(seq).putLong(System.nanoTime());
                udp.send(new DatagramPacket(out, out.length, target));

                long deadline = System.currentTimeMillis() + 1000;
                while (System.currentTimeMillis() < deadline) {
                    DatagramPacket p = new DatagramPacket(in, in.length);
                    try {
                        udp.receive(p);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    if (p.getLength() < PING_HEADER) continue;
                    ByteBuffer r = ByteBuffer.wrap(in, 0, p.getLength());
                    if (r.getInt() != NetConfig.MAGIC) continue;
                    r.getShort();
                    if (r.get() != PONG || r.getInt() != seq) continue; // late reply to an earlier ping
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
                System.out.println("  The host should run \"dev firewall\", which opens both TCP and UDP.");
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
        long t = System.currentTimeMillis();
        System.out.println(String.format("[%tT] %s", t, msg));
    }
}
