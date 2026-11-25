package multisector.sync;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;
import multisector.net.FleetState;
import multisector.net.Session;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Enemies go after every player, not just the one whose world the fleets
 * are from.
 *
 * Other players' fleets (puppets) are kept out of the game's own fleet AI
 * on the owner's game: an NPC fleet must never start a battle with a
 * stand-in. So without this, in any place only the owner was ever chased.
 * Now the owner's game decides for its fleets about each other player
 * there, the way the game decides about you:
 * <ul>
 * <li>a fleet that's hostile to that player (by their own standing, which
 *     their game sends), would fight (not a trader, not told to stay
 *     non-aggressive, not much weaker than them unless told to be
 *     aggressive) and sees them (their real sensor profile, transponder
 *     included) goes after them, unless you're closer and it's after you;</li>
 * <li>it gives up at half again that range, or after a while;</li>
 * <li>catching them hands the fight to their game: the encounter opens there
 *     with that fleet, as if they'd flown into it, and the owner's fleet is
 *     held while they deal with it (FleetLocks).</li>
 * </ul>
 * Everyone sees the chase: the owner's fleet moves, the others follow it.
 */
final class PlayerAggro {

    private static final Logger log = Global.getLogger(PlayerAggro.class);
    private static final long DECIDE_NANOS = 1_000_000_000L;
    private static final long GIVE_UP_NANOS = 45_000_000_000L;
    private static final float GIVE_UP_RANGE = 1.5f;
    private static final float CATCH_GAP = 40f;
    private static final long REPORT_NANOS = 20_000_000_000L;

    /** What each other player told us about themselves: hostile factions, sensor profile, transponder. */
    private static final class Standing {
        final Set<String> hostile = new HashSet<String>();
        float profile = 2000f;
        boolean transponder = true;
    }

    private static final Map<Integer, Standing> standings = new HashMap<Integer, Standing>();

    /** One of our fleets going after another player. */
    private static final class Chase {
        final int player;
        final long since;
        final boolean oldAi;
        final String oldText;
        Vector2f lastPos;
        long lastMoved;

        Chase(int player, CampaignFleetAPI f) {
            this.player = player;
            this.since = System.nanoTime();
            this.oldAi = f.isDoNotAdvanceAI();
            this.oldText = f.getAI() == null ? null : f.getAI().getActionTextOverride();
            this.lastPos = new Vector2f(f.getLocation());
            this.lastMoved = since;
        }
    }

    private static final Map<CampaignFleetAPI, Chase> chases = new IdentityHashMap<CampaignFleetAPI, Chase>();
    /** A fleet that just caught (or lost) a player leaves them alone a while, as the game's own fleets do. */
    private static final long GRACE_NANOS = 30_000_000_000L;
    private static final Map<CampaignFleetAPI, long[]> grace = new IdentityHashMap<CampaignFleetAPI, long[]>();
    private static long nextDecide, nextReport;
    /** Caught by another player's fleet: their handle, and whose. Opened once we're on the map. */
    private static int caughtHandle = -1, caughtOwner = -1;
    private static long caughtAt;

    private PlayerAggro() {
    }

    // ================================================================ every player: what others need to know about us

    /** Our standing and how visible we are, to everyone now and then. */
    static void report(Session s) {
        long now = System.nanoTime();
        if (now - nextReport < 0 || !s.isActive()) return;
        nextReport = now + REPORT_NANOS;
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (me == null) return;
        try {
            JSONArray hostile = new JSONArray();
            FactionAPI player = Global.getSector().getPlayerFaction();
            for (FactionAPI f : Global.getSector().getAllFactions()) {
                if (f != player && !f.isPlayerFaction() && f.isHostileTo(player)) hostile.put(f.getId());
            }
            s.sendSocial(new JSONObject().put("k", "rep").put("hostile", hostile).put("profile", me.getSensorProfile())
                    .put("tx", me.isTransponderOn()).toString());
        } catch (Exception e) {
            // next time
        }
    }

    /** From Social: another player's standing. */
    static void standing(int player, JSONObject j) {
        Standing st = new Standing();
        JSONArray h = j.optJSONArray("hostile");
        for (int i = 0; h != null && i < h.length(); i++) st.hostile.add(h.optString(i));
        st.profile = (float) j.optDouble("profile", 2000);
        st.transponder = j.optBoolean("tx", true);
        standings.put(player, st);
    }

    // ================================================================ the owner: our fleets and the other players here

    /** Every frame while we publish this place's fleets (NpcSync). */
    static void ownerFrame(Session s, LocationAPI here, Map<Integer, CampaignFleetAPI> byHandle) {
        long now = System.nanoTime();
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        steer(s, here, byHandle, now);
        if (now - nextDecide < 0 || me == null) return;
        nextDecide = now + DECIDE_NANOS;
        Map<Integer, CampaignFleetAPI> players = playersHere(s, here);
        if (players.isEmpty()) return;
        for (CampaignFleetAPI f : byHandle.values()) {
            if (chases.containsKey(f) || !mayChase(f)) continue;
            int best = -1;
            float bestD = Float.MAX_VALUE;
            for (Map.Entry<Integer, CampaignFleetAPI> e : players.entrySet()) {
                CampaignFleetAPI p = e.getValue();
                float d = Misc.getDistance(f.getLocation(), p.getLocation());
                if (d >= bestD || d > seeRange(f, e.getKey()) || !wouldFight(f, p, e.getKey())) continue;
                long[] g = grace.get(f);
                if (g != null && g[0] == e.getKey() && now - g[1] < GRACE_NANOS) continue;
                best = e.getKey();
                bestD = d;
            }
            if (best < 0) continue;
            // We're closer, and it's after us (or would be): the game's own pursuit carries on.
            float myD = Misc.getDistance(f.getLocation(), me.getLocation());
            if (f.isHostileTo(me) && myD < bestD && me.isVisibleToSensorsOf(f)) continue;
            Chase c = new Chase(best, f);
            chases.put(f, c);
            f.setDoNotAdvanceAI(true);
            if (f.getAI() != null) f.getAI().setActionTextOverride("pursuing " + s.playerName(best) + "'s fleet");
            log.info("[MultiSector] " + f.getName() + " goes after " + s.playerName(best) + " ("
                    + Math.round(bestD) + " away)");
        }
    }

    /** Our chasing fleets: after their target, until caught or given up. */
    private static void steer(Session s, LocationAPI here, Map<Integer, CampaignFleetAPI> byHandle, long now) {
        if (chases.isEmpty()) return;
        Map<CampaignFleetAPI, Integer> handleOf = new IdentityHashMap<CampaignFleetAPI, Integer>();
        for (Map.Entry<Integer, CampaignFleetAPI> e : byHandle.entrySet()) handleOf.put(e.getValue(), e.getKey());
        for (Iterator<Map.Entry<CampaignFleetAPI, Chase>> it = chases.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<CampaignFleetAPI, Chase> e = it.next();
            CampaignFleetAPI f = e.getKey();
            Chase c = e.getValue();
            CampaignFleetAPI target = PuppetManager.fleetOf(c.player);
            String why = null;
            if (f.isExpired() || f.getContainingLocation() != here || !handleOf.containsKey(f)) why = "gone";
            else if (f.getBattle() != null || FleetLocks.isHeld(f)) why = "busy";
            else if (target == null || target.getContainingLocation() != here || !onMap(s, c.player)) why = "target away";
            else if (now - c.since > GIVE_UP_NANOS) why = "too long";
            else if (Misc.getDistance(f.getLocation(), target.getLocation()) > seeRange(f, c.player) * GIVE_UP_RANGE) why = "lost them";
            if (why == null) {
                float gap = Misc.getDistance(f.getLocation(), target.getLocation()) - f.getRadius() - target.getRadius();
                if (gap < CATCH_GAP) {
                    catchThem(s, f, c, handleOf.get(f));
                    grace.put(f, new long[]{c.player, now});
                    restore(f, c, false);
                    it.remove();
                    continue;
                }
                move(f, c, target.getLocation(), now);
                continue;
            }
            restore(f, c, true);
            it.remove();
            grace.put(f, new long[]{c.player, now});
            if (!"gone".equals(why)) log.info("[MultiSector] " + f.getName() + " stops chasing " + s.playerName(c.player) + ": " + why);
        }
    }

    /** Toward them at its own speed. (Its AI is off meanwhile, or it would turn back to its own business.) */
    private static void move(CampaignFleetAPI f, Chase c, Vector2f to, long now) {
        f.setMoveDestination(to.x, to.y);
        if (Misc.getDistance(f.getLocation(), c.lastPos) > 5f) {
            c.lastPos.set(f.getLocation());
            c.lastMoved = now;
        } else if (now - c.lastMoved > 1_500_000_000L) {
            // Not moving by itself without its AI: move it directly, at its travel speed.
            float dx = to.x - f.getLocation().x, dy = to.y - f.getLocation().y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 1f) {
                float speed = Math.max(20f, f.getTravelSpeed());
                f.getVelocity().set(dx / len * speed, dy / len * speed);
                float step = speed / 60f;
                f.setLocation(f.getLocation().x + dx / len * step, f.getLocation().y + dy / len * step);
            }
        }
    }

    private static void restore(CampaignFleetAPI f, Chase c, boolean ai) {
        f.setDoNotAdvanceAI(c.oldAi);
        if (f.getAI() != null) f.getAI().setActionTextOverride(c.oldText);
    }

    /** Caught: the fight is theirs. Their game opens the encounter with its copy of this fleet. */
    private static void catchThem(Session s, CampaignFleetAPI f, Chase c, Integer handle) {
        if (handle == null) return;
        try {
            s.sendLockMessage(c.player, new JSONObject().put("k", "caught").put("h", handle).put("fleet", f.getName()).toString());
            log.info("[MultiSector] " + f.getName() + " caught " + s.playerName(c.player) + ": their game opens the encounter");
        } catch (Exception e) {
            // they'll be caught again next time
        }
    }

    /** Other players in this place, on the map (not docked, talking, in a menu or a battle). */
    private static Map<Integer, CampaignFleetAPI> playersHere(Session s, LocationAPI here) {
        Map<Integer, CampaignFleetAPI> out = new HashMap<Integer, CampaignFleetAPI>();
        for (Session.Player p : s.getPlayers()) {
            if (p.id == s.getMyId() || !onMap(s, p.id)) continue;
            CampaignFleetAPI f = PuppetManager.fleetOf(p.id);
            if (f != null && f.getContainingLocation() == here) out.put(p.id, f);
        }
        return out;
    }

    private static boolean onMap(Session s, int player) {
        FleetState.Received r = s.getRemoteFleets().get(player);
        return r != null && r.state != null && r.state.activity == FleetState.ON_MAP;
    }

    /** A fleet that would go after anyone at all: armed, not a trader, not told to keep to itself. */
    private static boolean mayChase(CampaignFleetAPI f) {
        if (f.getBattle() != null || FleetLocks.isHeld(f) || f.isStationMode() || f.getAI() == null) return false;
        MemoryAPI m = f.getMemoryWithoutUpdate();
        if (m.getBoolean(NpcSync.DRIVEN_FLAG) || m.getBoolean(NpcSync.HIDDEN_FLAG) || m.getBoolean(NpcSync.MIRROR_FLAG)) return false;
        if (m.getBoolean(MemFlags.MEMORY_KEY_MAKE_NON_AGGRESSIVE) || m.getBoolean(MemFlags.FLEET_IGNORES_OTHER_FLEETS)) return false;
        if (m.getBoolean(MemFlags.MEMORY_KEY_TRADE_FLEET) && !m.getBoolean(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE)) return false;
        if (f.getAI().isFleeing()) return false;
        return f.getFleetPoints() > 0;
    }

    /** Hostile to that player (by their own standing), and strong enough to want the fight. */
    private static boolean wouldFight(CampaignFleetAPI f, CampaignFleetAPI target, int player) {
        MemoryAPI m = f.getMemoryWithoutUpdate();
        if (m.getBoolean(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE) && !m.getBoolean(MemFlags.MEMORY_KEY_MAKE_HOSTILE)) return false;
        Standing st = standings.get(player);
        boolean hostile = m.getBoolean(MemFlags.MEMORY_KEY_MAKE_HOSTILE)
                || (st != null ? st.hostile.contains(f.getFaction().getId()) : f.getFaction().isHostileTo(Global.getSector().getPlayerFaction()))
                || (st != null && !st.transponder && m.getBoolean(MemFlags.MEMORY_KEY_MAKE_HOSTILE_WHILE_TOFF));
        if (!hostile) return false;
        boolean bold = m.getBoolean(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE) || m.getBoolean(MemFlags.MEMORY_KEY_MAKE_ALWAYS_PURSUE);
        return bold || f.getFleetPoints() >= target.getFleetPoints() * 0.6f;
    }

    /** How far this fleet sees that player: the game's own sensor rule, with their real profile (not the puppet's). */
    private static float seeRange(CampaignFleetAPI f, int player) {
        Standing st = standings.get(player);
        float profile = st == null ? 2000f : st.profile;
        try {
            return f.getBaseSensorRangeToDetect(profile);
        } catch (RuntimeException e) {
            return Math.min(f.getSensorStrength(), profile);
        }
    }

    /** We stopped publishing here (or left): every chase ends, the fleets back on their own business. */
    static void stopAll() {
        for (Map.Entry<CampaignFleetAPI, Chase> e : chases.entrySet()) restore(e.getKey(), e.getValue(), true);
        chases.clear();
    }

    // ================================================================ the one being chased

    /** From FleetLocks: the owner's fleet caught us. */
    static void caught(int owner, JSONObject j) {
        caughtOwner = owner;
        caughtHandle = j.optInt("h", -1);
        caughtAt = System.nanoTime();
    }

    /** Every frame (NpcSync): open the encounter once we're on the map. */
    static void playerFrame() {
        if (caughtHandle < 0) return;
        if (System.nanoTime() - caughtAt > 10_000_000_000L) {
            caughtHandle = -1; // we'd moved on (a dialog, a jump): they'll catch us again if they can
            return;
        }
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (ui == null || ui.isShowingDialog() || ui.isShowingMenu() || !ClockSync.isMapView()) return;
        CampaignFleetAPI f = NpcSync.fleetOfHandle(caughtHandle);
        int owner = caughtOwner;
        int handle = caughtHandle;
        caughtHandle = -1;
        if (f == null || owner != NpcSync.followedOwner()) return;
        log.info("[MultiSector] " + f.getName() + " caught us; opening the encounter");
        ui.showInteractionDialog(NpcInteractionGuard.engagedBy(owner, handle, f), f);
    }

    /** A save is loading. */
    static void forget() {
        chases.clear();
        grace.clear();
        standings.clear();
        caughtHandle = -1;
    }
}
