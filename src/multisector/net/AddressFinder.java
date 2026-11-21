package multisector.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Lists this machine's IPv4 addresses and labels each one (Hamachi, Tailscale,
 * LAN, ...), so the host can see which address to give friends.
 *
 * Uses only java.net, so it works inside the mod and in standalone tools.
 */
public final class AddressFinder {

    /** Kinds in preference order: the first one found is what the host should share. */
    public enum Kind {
        HAMACHI("Hamachi"),
        RADMIN("Radmin VPN"),
        TAILSCALE("Tailscale"),
        ZEROTIER("ZeroTier"),
        LAN("LAN"),
        PUBLIC("public"),
        LOOPBACK("this PC only");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public static final class Candidate {
        public final InetAddress address;
        public final String adapter;
        public final Kind kind;

        Candidate(InetAddress address, String adapter, Kind kind) {
            this.address = address;
            this.adapter = adapter;
            this.kind = kind;
        }

        public String ip() {
            return address.getHostAddress();
        }

        @Override
        public String toString() {
            return ip() + "  (" + kind.label + ", adapter \"" + adapter + "\")";
        }
    }

    private AddressFinder() {
    }

    /** All usable IPv4 addresses on up adapters, best first. Never null. */
    public static List<Candidate> find() {
        List<Candidate> out = new ArrayList<Candidate>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!isUp(ni)) continue;
                String adapter = ni.getDisplayName() != null ? ni.getDisplayName() : ni.getName();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLinkLocalAddress()) continue; // 169.254.x.x: adapter has no real address
                    out.add(new Candidate(a, adapter, classify(a, adapter)));
                }
            }
        } catch (Exception e) {
            // Leave the list empty; callers report "no addresses found".
        }
        Collections.sort(out, new Comparator<Candidate>() {
            public int compare(Candidate x, Candidate y) {
                return x.kind.compareTo(y.kind);
            }
        });
        return out;
    }

    /** First address of the given kind, or null. */
    public static Candidate first(List<Candidate> list, Kind kind) {
        for (Candidate c : list) {
            if (c.kind == kind) return c;
        }
        return null;
    }

    /** The address a host should most likely share, or null if there is none besides loopback. */
    public static Candidate preferred(List<Candidate> list) {
        for (Candidate c : list) {
            if (c.kind != Kind.LOOPBACK) return c;
        }
        return null;
    }

    static Kind classify(InetAddress a, String adapter) {
        String name = adapter.toLowerCase(Locale.ROOT);
        byte[] b = a.getAddress();
        int o1 = b[0] & 0xFF;
        int o2 = b[1] & 0xFF;

        if (a.isLoopbackAddress()) return Kind.LOOPBACK;
        // Hamachi hands out 25.x.x.x (older installs: 5.x.x.x). Match the adapter
        // name first; fall back to the range for renamed adapters.
        if (name.contains("hamachi") || o1 == 25) return Kind.HAMACHI;
        if (name.contains("radmin") || o1 == 26) return Kind.RADMIN;
        if (name.contains("tailscale") || (o1 == 100 && o2 >= 64 && o2 <= 127)) return Kind.TAILSCALE;
        if (name.contains("zerotier")) return Kind.ZEROTIER;
        if (a.isSiteLocalAddress()) return Kind.LAN;
        return Kind.PUBLIC;
    }

    private static boolean isUp(NetworkInterface ni) {
        try {
            return ni.isUp();
        } catch (Exception e) {
            return false;
        }
    }
}
