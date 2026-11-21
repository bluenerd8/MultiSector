package multisector.launcher;

import multisector.net.NetConfig;
import multisector.net.Protocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Downloads the zipped save over UDP in 1 KB pieces.
 *
 * Why: Hamachi's adapter allows 1500-byte packets but adds its own wrapping,
 * so full-size TCP packets can be silently dropped on the way (an "MTU black
 * hole"). TCP then crawls at a few KB/s and times out, while small packets
 * pass fine. 1 KB pieces always fit.
 *
 * How: the friend's side asks for specific pieces and keeps a window of
 * requests in flight, sized from what the link actually delivers (about twice
 * pieces-per-second times the best round trip), growing while delivery keeps
 * improving. Lost pieces are simply asked for again: relays drop packets at
 * random, so treating every loss as "slow down" (as TCP does) would crawl.
 * The host just answers requests, so nothing gets stuck.
 */
final class UdpTransfer {

    /** Nothing at all arrived within this long: UDP is blocked, use TCP instead. */
    static final class Blocked extends IOException {
        private static final long serialVersionUID = 1L;

        Blocked() {
            super("no UDP replies");
        }
    }

    private static final long FIRST_REPLY_TIMEOUT_MS = 4000;
    /** No new piece for this long after it started: give up. */
    private static final long STALL_TIMEOUT_MS = 20000;
    private static final double MIN_WINDOW = 8;
    private static final double MAX_WINDOW = 2048;

    private UdpTransfer() {
    }

    static void download(InetSocketAddress host, String sha256, byte[] into, LobbyClient.Progress progress)
            throws IOException {
        final int total = into.length;
        final int pieces = (total + Protocol.XFER_CHUNK - 1) / Protocol.XFER_CHUNK;
        final long key = Protocol.saveKey(sha256);

        // Per piece: 0 = not asked, 1 = asked, 2 = received.
        byte[] state = new byte[pieces];
        long[] askedAt = new long[pieces];
        boolean[] resent = new boolean[pieces];
        ArrayDeque<Integer> inFlight = new ArrayDeque<Integer>(); // roughly in ask order
        ArrayDeque<Integer> retry = new ArrayDeque<Integer>();
        int nextNew = 0;
        int received = 0;
        int flying = 0;
        long bytes = 0;

        // Pacing: measure what actually gets delivered (pieces per second) and the
        // best round trip seen, then keep about twice that much in flight. Random
        // loss on a relay just gets re-requested; only a falling delivery rate
        // or a growing queue (round trips well above the best) slows things down.
        double window = 64;
        double srttMs = 400;
        double minRttMs = Double.MAX_VALUE;
        double[] rates = new double[8]; // pieces/s over recent ticks
        int rateSlot = 0;
        int deliveredThisTick = 0;
        double lastRate = 0;
        long tickStart = System.nanoTime();
        long start = System.nanoTime();
        long lastProgress = start;
        boolean anyReply = false;

        DatagramSocket udp = new DatagramSocket();
        try {
            udp.setReceiveBufferSize(4 * 1024 * 1024);
            udp.setSoTimeout(15);
            byte[] in = new byte[2048];
            byte[] req = new byte[Protocol.HEADER + 12 + 4 * Protocol.XFER_MAX_REQUEST];

            while (received < pieces) {
                long now = System.nanoTime();
                double rtoMs = Math.max(250, Math.min(5000, srttMs * 2));

                // Every quarter second: re-aim the window at about 2x what the link delivers.
                double tickMs = (now - tickStart) / 1e6;
                if (tickMs >= 250 && minRttMs < Double.MAX_VALUE) {
                    double rate = deliveredThisTick / (tickMs / 1000.0);
                    rates[rateSlot++ % rates.length] = rate;
                    double best = 0;
                    for (double r : rates) best = Math.max(best, r);
                    double bdp = best * (minRttMs / 1000.0); // pieces the link holds per round trip
                    boolean queueing = srttMs > minRttMs * 1.5 + 50;
                    if (!queueing && rate >= lastRate * 0.95) {
                        window = Math.max(window * 1.5, bdp * 2); // still room: probe for more
                    } else {
                        window = bdp * 2; // settle at twice what's getting through
                    }
                    window = Math.max(MIN_WINDOW, Math.min(MAX_WINDOW, window));
                    lastRate = rate;
                    deliveredThisTick = 0;
                    tickStart = now;
                }

                // Pieces asked for too long ago count as lost and get asked for again.
                while (!inFlight.isEmpty()) {
                    int idx = inFlight.peekFirst();
                    if (state[idx] != 1) {
                        inFlight.pollFirst(); // already arrived
                        continue;
                    }
                    if ((now - askedAt[idx]) / 1e6 < rtoMs) break;
                    inFlight.pollFirst();
                    state[idx] = 0;
                    flying--;
                    retry.addLast(idx);
                }

                // Ask for more, up to the window.
                int canAsk = (int) window - flying;
                while (canAsk > 0 && (!retry.isEmpty() || nextNew < pieces)) {
                    ByteBuffer rb = ByteBuffer.wrap(req);
                    rb.putInt(NetConfig.MAGIC).putShort(NetConfig.PROTOCOL_VERSION).put(Protocol.XFER_GET);
                    rb.putLong(key);
                    int countPos = rb.position();
                    rb.putInt(0);
                    int count = 0;
                    while (count < Protocol.XFER_MAX_REQUEST && canAsk > 0) {
                        int idx;
                        if (!retry.isEmpty()) {
                            idx = retry.pollFirst();
                            if (state[idx] == 2) continue;
                            resent[idx] = true;
                        } else if (nextNew < pieces) {
                            idx = nextNew++;
                            if (state[idx] == 2) continue;
                        } else {
                            break;
                        }
                        rb.putInt(idx);
                        state[idx] = 1;
                        askedAt[idx] = now;
                        inFlight.addLast(idx);
                        flying++;
                        canAsk--;
                        count++;
                    }
                    if (count == 0) break;
                    rb.putInt(countPos, count);
                    udp.send(new DatagramPacket(req, rb.position(), host));
                }

                // Take whatever has arrived.
                while (true) {
                    DatagramPacket p = new DatagramPacket(in, in.length);
                    try {
                        udp.receive(p);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    ByteBuffer bb = ByteBuffer.wrap(in, 0, p.getLength());
                    if (Protocol.readHeader(bb, null) != Protocol.XFER_DATA) continue;
                    if (bb.getLong() != key) continue;
                    int idx = bb.getInt();
                    int len = bb.getInt();
                    if (idx < 0 || idx >= pieces || len < 0 || len > bb.remaining()) continue;
                    anyReply = true;
                    if (state[idx] == 2) continue; // duplicate
                    int off = idx * Protocol.XFER_CHUNK;
                    if (off + len > total) continue;
                    bb.get(into, off, len);
                    if (state[idx] == 1) flying--;
                    state[idx] = 2;
                    received++;
                    bytes += len;
                    long t = System.nanoTime();
                    lastProgress = t;
                    deliveredThisTick++;
                    if (!resent[idx] && askedAt[idx] > 0) { // RTT only from first tries
                        double sample = (t - askedAt[idx]) / 1e6;
                        srttMs = srttMs * 0.875 + sample * 0.125;
                        minRttMs = Math.min(minRttMs, sample);
                    }
                    progress.update(bytes, total);
                    if (received >= pieces) break;
                }

                long waited = (System.nanoTime() - (anyReply ? lastProgress : start)) / 1000000L;
                if (!anyReply && waited > FIRST_REPLY_TIMEOUT_MS) throw new Blocked();
                if (anyReply && waited > STALL_TIMEOUT_MS) {
                    throw new IOException("The download stalled (" + received + " of " + pieces
                            + " pieces). The connection to the host dropped; try again.");
                }
            }
        } finally {
            udp.close();
        }
    }
}
