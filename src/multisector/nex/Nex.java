package multisector.nex;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import org.apache.log4j.Logger;

/**
 * Nexerelin, if it's there. Safe to call without it: {@link NexBridge}
 * (the only class that touches Nexerelin's own) is only loaded when
 * Nexerelin is enabled.
 */
public final class Nex {

    private static final Logger log = Global.getLogger(Nex.class);
    private static Boolean enabled;

    private Nex() {
    }

    public static boolean enabled() {
        if (enabled == null) {
            try {
                enabled = Global.getSettings().getModManager().isModEnabled("nexerelin");
            } catch (RuntimeException e) {
                enabled = false;
            }
        }
        return enabled;
    }

    /** A friend's game: Nexerelin's world decisions are the host's while connected (or its own again). */
    public static void followHost(boolean on) {
        if (!enabled()) return;
        try {
            NexBridge.followHost(on);
        } catch (Throwable t) { // a Nexerelin version without these settings
            log.warn("[MultiSector] couldn't switch Nexerelin's planners " + (on ? "off" : "back on"), t);
        }
    }

    /** A market changes hands: Nexerelin's way if it's there, else plainly. */
    public static void transfer(MarketAPI m, FactionAPI to, boolean capture) {
        FactionAPI from = m.getFaction();
        if (enabled()) {
            try {
                NexBridge.transfer(m, to, from, capture);
                return;
            } catch (Throwable t) {
                log.warn("[MultiSector] Nexerelin couldn't transfer " + m.getName() + "; doing it plainly", t);
            }
        }
        m.setFactionId(to.getId());
        for (SectorEntityToken e : m.getConnectedEntities()) e.setFaction(to.getId());
        if (m.getPrimaryEntity() != null) m.getPrimaryEntity().setFaction(to.getId());
    }
}
