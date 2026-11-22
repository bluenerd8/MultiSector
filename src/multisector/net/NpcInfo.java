package multisector.net;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One NPC fleet in the host's star system: who it is and which ships it
 * has. Sent over TCP when the fleet first appears or changes; its position
 * comes separately in {@link NpcState} packets, keyed by the same handle.
 */
public final class NpcInfo {

    public static final int MAX_SHIPS = 60;

    /** The host's short number for this fleet, for as long as it stays in that system. */
    public final int handle;
    /** The fleet's own id. Fleets that were in the shared save have the same id in every copy. */
    public final String fleetId;
    public final String faction;
    public final String name;
    /** Per ship: the variant id, and the hull id to fall back on if this game lacks that variant. */
    public final List<String> variants;
    public final List<String> hulls;
    /**
     * What the fleet is doing, as its tooltip says it on the host ("travelling
     * to Jangala", "interacting with Bob"), or "". Not part of {@link #hash},
     * which is about who the fleet is and which ships it has.
     */
    public final String status;
    /**
     * Details that change: each ship's hull, readiness and mothballing (in
     * the same order as the ships), and the commander's name, portrait and
     * rank, as JSON, or "". Not part of {@link #hash} either.
     */
    public final String extra;
    public final int hash;

    public NpcInfo(int handle, String fleetId, String faction, String name, List<String> variants, List<String> hulls) {
        this(handle, fleetId, faction, name, variants, hulls, "");
    }

    public NpcInfo(int handle, String fleetId, String faction, String name, List<String> variants, List<String> hulls,
                   String status) {
        this(handle, fleetId, faction, name, variants, hulls, status, "");
    }

    public NpcInfo(int handle, String fleetId, String faction, String name, List<String> variants, List<String> hulls,
                   String status, String extra) {
        this.handle = handle;
        this.extra = extra == null || extra.length() > 8000 ? "" : extra;
        String st = status == null ? "" : status;
        this.status = st.length() > 80 ? st.substring(0, 80) : st;
        this.fleetId = fleetId == null ? "" : fleetId;
        this.faction = faction == null ? "" : faction;
        this.name = name == null ? "" : name;
        int n = Math.min(Math.min(variants.size(), hulls.size()), MAX_SHIPS);
        this.variants = Collections.unmodifiableList(new ArrayList<String>(variants.subList(0, n)));
        this.hulls = Collections.unmodifiableList(new ArrayList<String>(hulls.subList(0, n)));
        int h = this.fleetId.hashCode();
        h = 31 * h + this.faction.hashCode();
        h = 31 * h + this.name.hashCode();
        h = 31 * h + this.variants.hashCode();
        this.hash = 31 * h + this.hulls.hashCode();
    }

    public int size() {
        int s = 4 + Protocol.stringSize(fleetId) + Protocol.stringSize(faction) + Protocol.stringSize(name) + 2;
        for (int i = 0; i < variants.size(); i++) {
            s += Protocol.stringSize(variants.get(i)) + Protocol.stringSize(hulls.get(i));
        }
        return s + Protocol.stringSize(status) + Protocol.stringSize(extra);
    }

    public void write(ByteBuffer bb) {
        bb.putInt(handle);
        Protocol.putString(bb, fleetId);
        Protocol.putString(bb, faction);
        Protocol.putString(bb, name);
        bb.putShort((short) variants.size());
        for (int i = 0; i < variants.size(); i++) {
            Protocol.putString(bb, variants.get(i));
            Protocol.putString(bb, hulls.get(i));
        }
        Protocol.putString(bb, status);
        Protocol.putString(bb, extra);
    }

    public static NpcInfo read(ByteBuffer bb) {
        int handle = bb.getInt();
        String id = Protocol.getString(bb);
        String faction = Protocol.getString(bb);
        String name = Protocol.getString(bb);
        int n = bb.getShort();
        if (n < 0 || n > MAX_SHIPS) throw new IllegalArgumentException("bad ship count " + n);
        List<String> v = new ArrayList<String>(n);
        List<String> h = new ArrayList<String>(n);
        for (int i = 0; i < n; i++) {
            v.add(Protocol.getString(bb));
            h.add(Protocol.getString(bb));
        }
        String status = bb.remaining() >= 2 ? Protocol.getString(bb) : "";
        String extra = bb.remaining() >= 2 ? Protocol.getString(bb) : "";
        return new NpcInfo(handle, id, faction, name, v, h, status, extra);
    }
}
