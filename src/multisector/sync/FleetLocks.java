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
 * <b>Host:</b> a friend asks for a fleet (CLAIM_REQUEST). If nobody else
 * has it, the host freezes it (AI off, held in place) and answers with its
 * exact copy: every ship's loadout, officer, readiness and hull damage. When
 * the friend is done, they send which ships were lost (BATTLE_RESULT) and
 * the host removes those from its real fleet, or the whole fleet if none
 * are left. A fleet the host itself is talking to, or one already in a
 * battle, is refused.
 *
 * <b>Friend:</b> on a granted lock, the stand-in fleet is rebuilt as the
 * exact copy and the game's normal fleet dialog opens, so talking and
 * fighting work as usual on the friend's PC. When that dialog closes, the
 * losses go to the host. Until the host's update arrives, the fleet isn't
 * re-created as a stand-in (which would bring a destroyed fleet back for a
 * moment).
 *
 * Nothing here is saved: the frozen state is lifted before every save and
 * put back on the next frame.
 */
public final class FleetLocks {

    /** Host: memory key on a held fleet, set to the name of the player holding it. */
    public static final String CLAIMED_BY = "$multisector_claimedBy";
    /** A hold is dropped after this long, in case a friend's game never reports back. */
    private static final long MAX_HOLD_NANOS = 20L * 60L * 1_000_000_000L;
    /** Friend: wait this long for the host's update before showing a stand-in again. */
    private static final long AWAIT_NANOS = 5_000_000_000L;

    private static final Logger log = Global.getLogger(FleetLocks.class);

    private FleetLocks() {
    }

    // ================================================================ host

    private static final class Hold {
        final int playerId;
        final String name;
        final CampaignFleetAPI fleet;
        final long since;
        Boolean oldAi;
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

    /** Every frame on the host: answer requests, apply results, keep held fleets frozen. */
    static void hostFrame(Session s, Map<Integer, CampaignFleetAPI> byHandle, String hostName) {
        Session.ClaimEvent e;
        while ((e = s.pollClaimEvent()) != null) {
            try {
                hostEvent(s, e, byHandle, hostName);
            } catch (RuntimeException ex) {
                log.warn("[MultiSector] fleet lock request failed", ex);
                if (e.kind == Session.ClaimEvent.REQUEST) s.replyClaim(e.playerId, e.handle, false, "", "");
            }
        }
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

    private static void hostEvent(Session s, Session.ClaimEvent e, Map<Integer, CampaignFleetAPI> byHandle,
                                  String hostName) {
        Hold cur = holds.get(e.handle);
        switch (e.kind) {
            case Session.ClaimEvent.REQUEST: {
                CampaignFleetAPI f = byHandle.get(e.handle);
                if (f == null || f.getContainingLocation() == null) {
                    s.replyClaim(e.playerId, e.handle, false, "", ""); // gone
                } else if (cur != null && cur.playerId != e.playerId) {
                    s.replyClaim(e.playerId, e.handle, false, cur.name, "");
                } else if (f == NpcSync.dialogTarget()) {
                    s.replyClaim(e.playerId, e.handle, false, hostName, "");
                } else if (f.getBattle() != null) {
                    s.replyClaim(e.playerId, e.handle, false, "a battle", "");
                } else {
                    if (cur == null) {
                        cur = new Hold(e.playerId, e.playerName, f);
                        holds.put(e.handle, cur);
                        freeze(cur);
                        log.info("[MultiSector] " + e.playerName + " is dealing with " + f.getName());
                    }
                    s.replyClaim(e.playerId, e.handle, true, "", exactCopy(f).toString());
                }
                break;
            }
            case Session.ClaimEvent.RELEASE:
                if (cur != null && cur.playerId == e.playerId) {
                    unfreeze(cur);
                    holds.remove(e.handle);
                }
                break;
            case Session.ClaimEvent.RESULT:
                if (cur != null && cur.playerId == e.playerId) {
                    unfreeze(cur);
                    holds.remove(e.handle);
                    applyLosses(cur.fleet, e.lost, e.playerName);
                }
                break;
            case Session.ClaimEvent.LEFT:
                for (Iterator<Map.Entry<Integer, Hold>> it = holds.entrySet().iterator(); it.hasNext(); ) {
                    Hold h = it.next().getValue();
                    if (h.playerId != e.playerId) continue;
                    unfreeze(h);
                    it.remove();
                }
                break;
            default:
                break;
        }
    }

    private static void freeze(Hold h) {
        CampaignFleetAPI f = h.fleet;
        if (!h.applied) {
            h.oldAi = f.isDoNotAdvanceAI();
            f.setDoNotAdvanceAI(true);
            f.getMemoryWithoutUpdate().set(CLAIMED_BY, h.name);
            h.applied = true;
        }
        if (f.getOrbit() == null) {
            f.setLocation(h.x, h.y);
            f.getVelocity().set(0f, 0f);
        }
    }

    private static void unfreeze(Hold h) {
        if (!h.applied) return;
        h.fleet.setDoNotAdvanceAI(h.oldAi);
        h.fleet.getMemoryWithoutUpdate().unset(CLAIMED_BY);
        h.applied = false;
    }

    /** Before a save: lift every freeze (the holds stay, and come back next frame). */
    static void hostBeforeSave() {
        for (Hold h : holds.values()) unfreeze(h);
    }

    /** Hosting stopped or a save is loading: let go of everything. */
    static void hostReleaseAll() {
        for (Hold h : holds.values()) {
            if (h.fleet.getContainingLocation() != null) unfreeze(h);
        }
        holds.clear();
    }

    /** True while some player holds this fleet (host side). */
    static boolean isHeld(CampaignFleetAPI f) {
        for (Hold h : holds.values()) if (h.fleet == f) return true;
        return false;
    }

    /** The host's real fleet, exactly: ships with loadouts, officers, readiness, damage. */
    private static JSONObject exactCopy(CampaignFleetAPI f) {
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

    // ================================================================ friend

    private static final class Active {
        final int handle;
        final CampaignFleetAPI fleet;
        /** Our copy's member id -> the host's member id. */
        final Map<String, String> toHost = new HashMap<String, String>();

        Active(int handle, CampaignFleetAPI fleet) {
            this.handle = handle;
            this.fleet = fleet;
        }
    }

    private static Active active;
    /** Handle -> deadline: don't show a stand-in for it until the host has caught up. */
    private static final Map<Integer, Long> awaiting = new HashMap<Integer, Long>();

    /**
     * Friend: the host granted the lock. Rebuilds our fleet as the host's
     * exact copy and starts watching for the dialog to end. Returns false
     * if the copy couldn't be made (the dialog then just leaves).
     */
    static boolean takeOver(int handle, CampaignFleetAPI fleet, String json) {
        List<String> skipped = new ArrayList<String>();
        Active a = new Active(handle, fleet);
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
                a.toHost.put(m.getId(), s.optString("id"));
                built.add(m);
                if (s.optBoolean("flagship")) flagship = m;
            }
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't rebuild the host's fleet", e);
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
        if (!skipped.isEmpty()) log.info("[MultiSector] host fleet copy is missing " + skipped + " (mods?)");
        return true;
    }

    /** Every frame on a friend: when the dialog with the locked fleet ends, report what happened. */
    static void friendFrame(Session s) {
        Active a = active;
        if (a == null) return;
        if (NpcSync.dialogTarget() == a.fleet) return; // still talking or fighting
        active = null;
        Set<String> survivors = new HashSet<String>();
        if (a.fleet.getContainingLocation() != null) {
            for (FleetMemberAPI m : a.fleet.getFleetData().getMembersListCopy()) survivors.add(m.getId());
        }
        List<String> lost = new ArrayList<String>();
        for (Map.Entry<String, String> e : a.toHost.entrySet()) {
            if (!survivors.contains(e.getKey()) && !e.getValue().isEmpty()) lost.add(e.getValue());
        }
        if (lost.isEmpty()) {
            s.releaseClaim(a.handle);
        } else {
            s.sendBattleResult(a.handle, lost);
            awaiting.put(a.handle, System.nanoTime() + AWAIT_NANOS);
            log.info("[MultiSector] reported " + lost.size() + " ships lost by " + a.fleet.getName() + " to the host");
        }
    }

    /**
     * Friend: don't re-create a stand-in for this fleet yet: its battle
     * result is on its way to the host. Ends when the host's details for it
     * change (the result arrived), or after a few seconds.
     */
    static boolean isAwaiting(int handle, int infoHash) {
        Long until = awaiting.get(handle);
        if (until == null) return false;
        Integer seen = awaitingHash.get(handle);
        if (seen == null) {
            awaitingHash.put(handle, infoHash); // the details as they were when we reported
        } else if (seen != infoHash) {
            clearAwaiting(handle); // the host applied it
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

    private static final Map<Integer, Integer> awaitingHash = new HashMap<Integer, Integer>();

    /** Friend: the fleet currently locked for us, if any. */
    static CampaignFleetAPI activeFleet() {
        Active a = active;
        return a == null ? null : a.fleet;
    }

    /** A save is loading or the session ended: forget everything. */
    static void forget() {
        holds.clear();
        active = null;
        awaiting.clear();
        awaitingHash.clear();
    }
}
