package multisector.sync;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import multisector.character.ShipJson;
import multisector.net.Session;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Locks on NPC fleets that a player is interacting with, so the fleet stays
 * put on every game and only one player deals with it at a time.
 *
 * <p>The fleet belongs to the owner's world (see {@link NpcSync}): the host,
 * or whichever friend's world the others follow there. Messages travel on
 * the lock channel of PLAYER_MSG, through the host.
 *
 * <p><b>Owner:</b> a follower asks for a fleet ({@code claim}). If nobody else
 * has it, the owner freezes it (AI off, held in place) and answers with its
 * exact copy: every ship's loadout, officer, readiness and hull damage. When
 * the follower is done they send which ships were lost ({@code result}), and
 * the owner removes those from its real fleet, or the whole fleet if none
 * are left. A fleet the owner itself is talking to, or one already in a
 * battle, is refused.
 *
 * <p><b>Follower:</b> on a granted lock, the stand-in fleet is rebuilt as the
 * exact copy and the game's normal fleet dialog opens, so talking and
 * fighting work as usual. When that dialog closes, the losses go to the
 * owner. Until the owner's update arrives, the fleet isn't re-created as a
 * stand-in (which would bring a destroyed fleet back for a moment).
 *
 * <p>Nothing here is saved: the frozen state is lifted before every save and
 * put back on the next frame.
 */
public final class FleetLocks {

    /** Owner: memory key on a held fleet, set to the name of the player holding it. */
    public static final String CLAIMED_BY = "$multisector_claimedBy";
    /** A hold is dropped after this long, in case a follower's game never reports back. */
    private static final long MAX_HOLD_NANOS = 20L * 60L * 1_000_000_000L;
    /** Follower: wait this long for the owner's update before showing a stand-in again. */
    private static final long AWAIT_NANOS = 5_000_000_000L;

    private static final Logger log = Global.getLogger(FleetLocks.class);

    private FleetLocks() {
    }

    // ================================================================ messages

    /** Every frame: requests to us as an owner, and answers to our own requests. */
    static void handleMessages(Session s, Map<Integer, CampaignFleetAPI> byHandle, String myName) {
        Session.PlayerMessage m;
        for (int n = 0; n < 50 && (m = s.pollLockMessage()) != null; n++) {
            try {
                JSONObject j = new JSONObject(m.json);
                String k = j.optString("k");
                if ("claim".equals(k) || "release".equals(k) || "result".equals(k)) {
                    ownerEvent(s, m, k, j, byHandle, myName);
                } else if ("reply".equals(k)) {
                    int h = j.optInt("h");
                    Integer asked = pending.get(h);
                    if (asked != null && asked == m.from) {
                        pending.remove(h);
                        replies.put(h, new Reply(m.from, j.optBoolean("ok"), j.optString("holder", ""),
                                j.optString("fleet", "")));
                    }
                } else if ("left".equals(k)) {
                    playerLeft(m.from);
                }
            } catch (Exception e) {
                log.warn("[MultiSector] bad fleet lock message from " + m.fromName + ": " + e);
            }
        }
    }

    private static void playerLeft(int id) {
        // As owner: what they held is free again.
        for (Iterator<Map.Entry<Integer, Hold>> it = holds.entrySet().iterator(); it.hasNext(); ) {
            Hold h = it.next().getValue();
            if (h.playerId != id) continue;
            unfreeze(h);
            it.remove();
        }
        // As follower: an owner who left won't answer.
        for (Iterator<Map.Entry<Integer, Integer>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Integer> e = it.next();
            if (e.getValue() != id) continue;
            replies.put(e.getKey(), new Reply(id, false, "", ""));
            it.remove();
        }
        if (active != null && active.owner == id) active = null;
    }

    private static JSONObject msg(String kind, int handle) {
        try {
            return new JSONObject().put("k", kind).put("h", handle);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ================================================================ owner

    private static final class Hold {
        final int playerId;
        final String name;
        final CampaignFleetAPI fleet;
        final long since;
        Boolean oldAi;
        String oldText;
        boolean applied;
        float x, y;

        Hold(int playerId, String name, CampaignFleetAPI fleet) {
            this.playerId = playerId;
            this.name = name;
            this.fleet = fleet;
            this.since = System.nanoTime();
            this.x = fleet.getLocation().x;
            this.y = fleet.getLocation().y;
        }
    }

    private static final Map<Integer, Hold> holds = new HashMap<Integer, Hold>();

    /** Every frame while we're an owner: keep held fleets frozen, let go of forgotten ones. */
    static void ownerFrame() {
        long now = System.nanoTime();
        for (Iterator<Map.Entry<Integer, Hold>> it = holds.entrySet().iterator(); it.hasNext(); ) {
            Hold h = it.next().getValue();
            if (h.fleet.getContainingLocation() == null) {
                it.remove(); // gone some other way
            } else if (now - h.since > MAX_HOLD_NANOS) {
                log.info("[MultiSector] " + h.name + " held " + h.fleet.getName() + " too long; letting go");
                unfreeze(h);
                it.remove();
            } else {
                freeze(h);
            }
        }
    }

    private static void ownerEvent(Session s, Session.PlayerMessage m, String k, JSONObject j,
                                   Map<Integer, CampaignFleetAPI> byHandle, String myName) throws Exception {
        int handle = j.optInt("h");
        Hold cur = holds.get(handle);
        if ("claim".equals(k)) {
            CampaignFleetAPI f = byHandle.get(handle);
            if (f == null || f.getContainingLocation() == null) {
                reply(s, m.from, handle, false, "", null); // gone, or we're not the owner any more
            } else if (cur != null && cur.playerId != m.from) {
                reply(s, m.from, handle, false, cur.name, null);
            } else if (f == NpcSync.dialogTarget()) {
                reply(s, m.from, handle, false, myName, null);
            } else if (f.getBattle() != null) {
                reply(s, m.from, handle, false, "a battle", null);
            } else {
                if (cur == null) {
                    cur = new Hold(m.from, m.fromName, f);
                    holds.put(handle, cur);
                    freeze(cur);
                    log.info("[MultiSector] " + m.fromName + " is dealing with " + f.getName());
                }
                reply(s, m.from, handle, true, "", exactCopy(f).toString());
            }
        } else if ("release".equals(k)) {
            if (cur != null && cur.playerId == m.from) {
                unfreeze(cur);
                holds.remove(handle);
            }
        } else if ("result".equals(k)) {
            if (cur != null && cur.playerId == m.from) {
                unfreeze(cur);
                holds.remove(handle);
                List<String> lost = new ArrayList<String>();
                JSONArray a = j.optJSONArray("lost");
                for (int i = 0; a != null && i < a.length(); i++) lost.add(a.getString(i));
                applyLosses(cur.fleet, lost, m.fromName);
            }
        }
    }

    private static void reply(Session s, int to, int handle, boolean ok, String holder, String fleet) throws Exception {
        JSONObject j = msg("reply", handle).put("ok", ok).put("holder", holder);
        if (fleet != null) j.put("fleet", fleet);
        s.sendLockMessage(to, j.toString());
    }

    private static void freeze(Hold h) {
        CampaignFleetAPI f = h.fleet;
        if (!h.applied) {
            h.oldAi = f.isDoNotAdvanceAI();
            f.setDoNotAdvanceAI(true);
            f.getMemoryWithoutUpdate().set(CLAIMED_BY, h.name);
            h.oldText = f.getAI() == null ? null : f.getAI().getActionTextOverride();
            h.applied = true;
        }
        // What its tooltip says, instead of the order it isn't following right now. Every frame,
        // since some fleets' own scripts set this text too.
        String text = statusFor(h.name);
        if (f.getAI() != null && !text.equals(f.getAI().getActionTextOverride())) f.getAI().setActionTextOverride(text);
        if (f.getOrbit() == null) {
            f.setLocation(h.x, h.y);
            f.getVelocity().set(0f, 0f);
        }
    }

    private static void unfreeze(Hold h) {
        if (!h.applied) return;
        h.fleet.setDoNotAdvanceAI(h.oldAi);
        h.fleet.getMemoryWithoutUpdate().unset(CLAIMED_BY);
        if (h.fleet.getAI() != null) h.fleet.getAI().setActionTextOverride(h.oldText);
        h.applied = false;
    }

    /** Before a save: lift every freeze (the holds stay, and come back next frame). */
    static void beforeSave() {
        for (Hold h : holds.values()) unfreeze(h);
    }

    /** No longer the owner there (or the session ended): let go of everything we hold. */
    static void releaseHolds() {
        if (holds.isEmpty()) return;
        for (Hold h : holds.values()) {
            if (h.fleet.getContainingLocation() != null) unfreeze(h);
        }
        holds.clear();
    }

    /** A held fleet's tooltip text. */
    static String statusFor(String playerName) {
        return "interacting with " + playerName;
    }

    /** Who holds this fleet (owner side), or null. */
    static String holderOf(CampaignFleetAPI f) {
        for (Hold h : holds.values()) if (h.fleet == f) return h.name;
        return null;
    }

    /** True while some player holds this fleet (owner side). */
    static boolean isHeld(CampaignFleetAPI f) {
        for (Hold h : holds.values()) if (h.fleet == f) return true;
        return false;
    }

    /** Our real fleet, exactly: ships with loadouts, officers, readiness, damage. */
    static JSONObject exactCopy(CampaignFleetAPI f) {
        JSONObject root = new JSONObject();
        JSONArray ships = new JSONArray();
        try {
            root.put("name", f.getName());
            FleetMemberAPI flag = f.getFlagship();
            for (FleetMemberAPI m : f.getFleetData().getMembersListCopy()) {
                if (m.isFighterWing()) continue;
                JSONObject s = ShipJson.ship(m);
                s.put("id", m.getId());
                s.put("flagship", m == flag);
                PersonAPI c = m.getCaptain();
                if (c != null && !c.isDefault()) s.put("captain", ShipJson.person(c, c.getStats()));
                ships.put(s);
            }
            root.put("ships", ships);
        } catch (Exception ex) {
            log.warn("[MultiSector] couldn't describe " + f.getName() + " exactly", ex);
        }
        return root;
    }

    private static void applyLosses(CampaignFleetAPI f, List<String> lost, String by) {
        if (f.getContainingLocation() == null) return;
        Set<String> ids = new HashSet<String>(lost);
        FleetDataAPI data = f.getFleetData();
        int removed = 0;
        for (FleetMemberAPI m : data.getMembersListCopy()) {
            if (!ids.contains(m.getId())) continue;
            data.removeFleetMember(m);
            removed++;
        }
        log.info("[MultiSector] " + by + "'s battle with " + f.getName() + ": " + removed + " ships lost");
        if (data.getNumMembers() == 0) {
            // OTHER, not DESTROYED_BY_BATTLE: listeners such as bounties expect real battle data with that one.
            f.despawn(FleetDespawnReason.OTHER, null);
            if (f.getContainingLocation() != null) f.getContainingLocation().removeEntity(f);
            Session.get().notice(by + " destroyed " + f.getName() + ".");
            return;
        }
        if (removed > 0) {
            data.ensureHasFlagship();
            data.setSyncNeeded();
            f.forceSync();
        }
    }

    // ================================================================ follower

    /** The owner's answer to one of our requests. */
    static final class Reply {
        final int owner;
        final boolean granted;
        /** Who holds it, when refused ("" if it's gone). */
        final String holder;
        /** The fleet's exact copy, when granted. */
        final String fleetJson;

        Reply(int owner, boolean granted, String holder, String fleetJson) {
            this.owner = owner;
            this.granted = granted;
            this.holder = holder;
            this.fleetJson = fleetJson;
        }
    }

    /** Handle -> the owner we asked. */
    private static final Map<Integer, Integer> pending = new HashMap<Integer, Integer>();
    private static final Map<Integer, Reply> replies = new HashMap<Integer, Reply>();

    /** Asks the owner to hold this fleet for us. The answer comes back through {@link #takeReply}. */
    static void request(int owner, int handle) {
        replies.remove(handle);
        pending.put(handle, owner);
        Session.get().sendLockMessage(owner, msg("claim", handle).toString());
    }

    /** The owner's answer, once (null until it arrives). */
    static Reply takeReply(int handle) {
        return replies.remove(handle);
    }

    /** Done with that fleet and nothing changed (or we gave up waiting). */
    static void release(int owner, int handle) {
        pending.remove(handle);
        Session.get().sendLockMessage(owner, msg("release", handle).toString());
    }

    private static final class Active {
        final int owner;
        final int handle;
        final CampaignFleetAPI fleet;
        /** Our copy's member id -> the owner's member id. */
        final Map<String, String> toOwner = new HashMap<String, String>();

        Active(int owner, int handle, CampaignFleetAPI fleet) {
            this.owner = owner;
            this.handle = handle;
            this.fleet = fleet;
        }
    }

    private static Active active;
    /** Handle -> deadline: don't show a stand-in for it until the owner has caught up. */
    private static final Map<Integer, Long> awaiting = new HashMap<Integer, Long>();
    private static final Map<Integer, Integer> awaitingHash = new HashMap<Integer, Integer>();

    /**
     * The owner granted the lock. Rebuilds our fleet as their exact copy and
     * starts watching for the dialog to end. Returns false if the copy
     * couldn't be made (the dialog then just leaves).
     */
    static boolean takeOver(int owner, int handle, CampaignFleetAPI fleet, String json) {
        List<String> skipped = new ArrayList<String>();
        Active a = new Active(owner, handle, fleet);
        List<FleetMemberAPI> built = new ArrayList<FleetMemberAPI>();
        FleetMemberAPI flagship = null;
        try {
            JSONArray ships = new JSONObject(json).getJSONArray("ships");
            for (int i = 0; i < ships.length(); i++) {
                JSONObject s = ships.getJSONObject(i);
                FleetMemberAPI m = ShipJson.buildShip(s, skipped);
                if (m == null) continue;
                m.getRepairTracker().setCR((float) s.optDouble("cr", m.getRepairTracker().getMaxCR()));
                m.getStatus().setHullFraction((float) s.optDouble("hullFraction", 1));
                JSONObject captain = s.optJSONObject("captain");
                if (captain != null) {
                    PersonAPI p = Global.getFactory().createPerson();
                    p.setFaction(fleet.getFaction().getId());
                    ShipJson.applyPerson(p, p.getStats(), captain, null);
                    m.setCaptain(p);
                }
                a.toOwner.put(m.getId(), s.optString("id"));
                built.add(m);
                if (s.optBoolean("flagship")) flagship = m;
            }
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't rebuild the owner's fleet", e);
            return false;
        }
        if (built.isEmpty()) return false;
        FleetDataAPI data = fleet.getFleetData();
        for (FleetMemberAPI old : data.getMembersListCopy()) data.removeFleetMember(old);
        for (FleetMemberAPI m : built) data.addFleetMember(m);
        if (flagship == null) flagship = built.get(0);
        data.setFlagship(flagship);
        if (flagship.getCaptain() != null && !flagship.getCaptain().isDefault()) fleet.setCommander(flagship.getCaptain());
        data.setSyncNeeded();
        fleet.forceSync();
        active = a;
        if (!skipped.isEmpty()) log.info("[MultiSector] fleet copy is missing " + skipped + " (mods?)");
        return true;
    }

    /** Every frame on a follower: when the dialog with the locked fleet ends, report what happened. */
    static void followerFrame(Session s) {
        Active a = active;
        if (a == null) return;
        if (NpcSync.dialogTarget() == a.fleet) return; // still talking or fighting
        active = null;
        Set<String> survivors = new HashSet<String>();
        if (a.fleet.getContainingLocation() != null) {
            for (FleetMemberAPI m : a.fleet.getFleetData().getMembersListCopy()) survivors.add(m.getId());
        }
        List<String> lost = new ArrayList<String>();
        for (Map.Entry<String, String> e : a.toOwner.entrySet()) {
            if (!survivors.contains(e.getKey()) && !e.getValue().isEmpty()) lost.add(e.getValue());
        }
        if (lost.isEmpty()) {
            release(a.owner, a.handle);
            return;
        }
        try {
            JSONArray arr = new JSONArray();
            for (String id : lost) arr.put(id);
            s.sendLockMessage(a.owner, msg("result", a.handle).put("lost", arr).toString());
        } catch (Exception e) {
            release(a.owner, a.handle);
            return;
        }
        awaiting.put(a.handle, System.nanoTime() + AWAIT_NANOS);
        log.info("[MultiSector] reported " + lost.size() + " ships lost by " + a.fleet.getName() + " to "
                + s.playerName(a.owner));
    }

    /**
     * Don't re-create a stand-in for this fleet yet: its battle result is on
     * its way to the owner. Ends when the owner's details for it change (the
     * result arrived), or after a few seconds.
     */
    static boolean isAwaiting(int handle, int infoHash) {
        Long until = awaiting.get(handle);
        if (until == null) return false;
        Integer seen = awaitingHash.get(handle);
        if (seen == null) {
            awaitingHash.put(handle, infoHash); // the details as they were when we reported
        } else if (seen != infoHash) {
            clearAwaiting(handle); // the owner applied it
            return false;
        }
        if (System.nanoTime() - until > 0) {
            clearAwaiting(handle);
            return false;
        }
        return true;
    }

    private static void clearAwaiting(int handle) {
        awaiting.remove(handle);
        awaitingHash.remove(handle);
    }

    /** Follower: the fleet currently locked for us, if any. */
    static CampaignFleetAPI activeFleet() {
        Active a = active;
        return a == null ? null : a.fleet;
    }

    /** A save is loading or the session ended: forget everything. */
    static void forget() {
        holds.clear();
        active = null;
        pending.clear();
        replies.clear();
        awaiting.clear();
        awaitingHash.clear();
    }
}
