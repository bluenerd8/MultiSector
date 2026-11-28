package multisector.sync;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.impl.campaign.intel.PersonBountyIntel;
import multisector.net.Session;
import org.apache.log4j.Logger;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Person bounties that came with the host's world are the same bounty on
 * every game (same person, same fleet). When one player claims one, it's
 * over for everyone: the others' games remove their copy of the target's
 * fleet, so the bounty ends there too, and say who claimed it.
 *
 * <p>Bounties a game posted on its own after the save are that player's
 * alone and aren't touched.
 */
final class BountyClaims {

    private static final Logger log = Global.getLogger(BountyClaims.class);
    /** Bounties claimed (here or anywhere), by key: said once, and never echoed back. */
    private static final Set<String> done = new HashSet<String>();

    private BountyClaims() {
    }

    static void forget() {
        done.clear();
    }

    private static String key(PersonBountyIntel b) {
        String person = b.getPerson() == null ? "" : b.getPerson().getId();
        String fleet = b.getFleet() == null ? "" : b.getFleet().getId();
        return person + "|" + fleet;
    }

    private static boolean claimedHere(PersonBountyIntel b) {
        PersonBountyIntel.BountyResult r = b.getResult();
        if (r == null) return false;
        return r.type == PersonBountyIntel.BountyResultType.END_PLAYER_BOUNTY
                || r.type == PersonBountyIntel.BountyResultType.END_PLAYER_NO_BOUNTY
                || r.type == PersonBountyIntel.BountyResultType.END_PLAYER_NO_REWARD;
    }

    /** Every couple of seconds: a bounty we just claimed goes to everyone. */
    static void scan(Session s) {
        for (IntelInfoPlugin i : Global.getSector().getIntelManager().getIntel(PersonBountyIntel.class)) {
            PersonBountyIntel b = (PersonBountyIntel) i;
            if (!claimedHere(b)) continue;
            String key = key(b);
            if (!done.add(key)) continue;
            try {
                String name = b.getPerson() == null ? "a wanted captain" : b.getPerson().getNameString();
                s.sendWorldEvent(new JSONObject().put("e", "world").put("w", "claimed")
                        .put("person", b.getPerson() == null ? "" : b.getPerson().getId())
                        .put("fleet", b.getFleet() == null ? "" : b.getFleet().getId())
                        .put("name", name).put("by", s.playerName(s.getMyId())).toString());
                log.info("[MultiSector] we claimed the bounty on " + name + "; telling everyone");
            } catch (Exception e) {
                log.warn("[MultiSector] couldn't tell the others we claimed a bounty", e);
            }
        }
    }

    /** Another player claimed a bounty: if it's one we have too, it's over here. */
    static void apply(JSONObject j) {
        String person = j.optString("person", "");
        String fleet = j.optString("fleet", "");
        String by = j.optString("by", "another player");
        for (IntelInfoPlugin i : Global.getSector().getIntelManager().getIntel(PersonBountyIntel.class)) {
            PersonBountyIntel b = (PersonBountyIntel) i;
            if (b.getResult() != null || b.isEnding() || b.isEnded()) continue;
            boolean samePerson = !person.isEmpty() && b.getPerson() != null && person.equals(b.getPerson().getId());
            boolean sameFleet = !fleet.isEmpty() && b.getFleet() != null && fleet.equals(b.getFleet().getId());
            if (!samePerson && !sameFleet) continue;
            done.add(key(b));
            CampaignFleetAPI f = b.getFleet();
            if (f != null && !f.isExpired() && f.getContainingLocation() != null) {
                // The bounty listens to its fleet: gone some other way than a battle of ours, it ends as taken by someone else.
                f.despawn(FleetDespawnReason.OTHER, null);
                if (f.getContainingLocation() != null) f.getContainingLocation().removeEntity(f);
            }
            if (b.getResult() == null && !b.isEnding()) b.endAfterDelay();
            String name = j.optString("name", "the wanted captain");
            log.info("[MultiSector] " + by + " claimed the bounty on " + name + "; ended here");
            Session.get().notice("Bounty claimed by " + by + ": " + name + ".");
        }
    }
}
