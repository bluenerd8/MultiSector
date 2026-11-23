package multisector.sync;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.util.Misc;
import multisector.net.FleetState;
import multisector.net.Session;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Losing every ship in a multiplayer world. The game asks campaign plugins
 * for a respawn dialog (pickRespawnPlugin) before falling back to its own
 * "picked up by a salvage fleet". Ours offers the same new start (a Wayfarer
 * and a Kite, credits and mission items kept, the rest of the cargo lost),
 * either next to another player's fleet, or at the nearest friendly port.
 *
 * Nothing else needs syncing: other games see the new ships and position
 * through the normal fleet updates.
 */
final class Respawn implements InteractionDialogPlugin {

    private static final Logger log = Global.getLogger(Respawn.class);
    private static final String[] START_SHIPS = {"wayfarer_Starting", "kite_Starting"};
    private static final String PORT = "port";

    private InteractionDialogAPI dialog;

    /** Only in a session with someone else to be picked up by; otherwise the game's own respawn. */
    static boolean wanted() {
        Session s = Session.get();
        if (!s.isActive()) return false;
        for (Session.Player p : s.getPlayers()) if (p.id != s.getMyId()) return true;
        return false;
    }

    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        dialog.getTextPanel().addPara("Your fleet is scattered, and you yourself barely escape in the command pod.");
        dialog.getTextPanel().addPara("Your comms officer gets a signal out. Who picks you up?");
        Session s = Session.get();
        for (Map.Entry<Integer, FleetState.Received> e : s.getRemoteFleets().entrySet()) {
            FleetState st = e.getValue().state;
            if (st == null || FleetSync.findLocation(st.location) == null) continue;
            String where = st.place == null || st.place.isEmpty() ? "" : " (" + st.place + ")";
            dialog.getOptionPanel().addOption("Get picked up by " + s.playerName(e.getKey()) + where, e.getKey());
        }
        dialog.getOptionPanel().addOption("Get picked up by a passing salvage fleet (nearest friendly port)", PORT);
        dialog.setOptionOnEscape("Get picked up by a passing salvage fleet", PORT);
    }

    public void optionSelected(String optionText, Object optionData) {
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        try {
            newStart(fleet);
            SectorEntityToken to = null;
            String text;
            if (optionData instanceof Integer) {
                int id = (Integer) optionData;
                FleetState.Received r = Session.get().getRemoteFleets().get(id);
                LocationAPI loc = r == null ? null : FleetSync.findLocation(r.state.location);
                if (loc != null) to = loc.createToken(r.state.x + 250f, r.state.y + 250f);
                text = Session.get().playerName(id) + "'s fleet picks you up.";
            } else {
                to = nearestPort(fleet);
                text = "After a while, a passing salvage fleet takes you back to civilization.";
            }
            if (to != null && fleet != null) {
                if (to.getContainingLocation() == fleet.getContainingLocation()) {
                    fleet.setLocation(to.getLocation().x, to.getLocation().y);
                } else {
                    Global.getSector().doHyperspaceTransition(fleet, fleet, new JumpPointAPI.JumpDestination(to, null), 0f);
                }
            }
            Session.get().notice(text);
            log.info("[MultiSector] respawned: " + text);
        } catch (RuntimeException e) {
            log.warn("[MultiSector] respawn failed", e);
        }
        dialog.dismiss();
    }

    /** What the game gives you after losing everything: two starting ships, crew, supplies and fuel. */
    private static void newStart(CampaignFleetAPI fleet) {
        if (fleet == null) return;
        CargoAPI cargo = fleet.getCargo();
        // The fleet's cargo went down with it, except mission items.
        for (CargoStackAPI st : new ArrayList<CargoStackAPI>(cargo.getStacksCopy())) {
            boolean mission = (st.isCommodityStack() && st.getResourceIfResource() != null
                    && st.getResourceIfResource().hasTag("mission_item"))
                    || (st.isSpecialStack() && st.getSpecialItemSpecIfSpecial() != null
                    && st.getSpecialItemSpecIfSpecial().hasTag("mission_item"));
            if (!mission) cargo.removeStack(st);
        }
        List<FleetMemberAPI> added = new ArrayList<FleetMemberAPI>();
        for (String v : START_SHIPS) {
            if (Global.getSettings().getVariant(v) == null) continue;
            FleetMemberAPI m = Global.getFactory().createFleetMember(FleetMemberType.SHIP, v);
            fleet.getFleetData().addFleetMember(m);
            added.add(m);
        }
        if (added.isEmpty()) return;
        fleet.getFleetData().setFlagship(added.get(0));
        float crew = 0f, suppliesPerDay = 0f, fuelPerLy = 0f;
        for (FleetMemberAPI m : added) {
            m.getRepairTracker().setCR(m.getRepairTracker().getMaxCR());
            crew += m.getNeededCrew();
            suppliesPerDay += m.getStats().getSuppliesPerMonth().getModifiedValue() / 30f;
            fuelPerLy += m.getFuelUse();
        }
        cargo.addCommodity(Commodities.CREW, (float) Math.ceil(crew * 1.15f));
        cargo.addCommodity(Commodities.SUPPLIES, (float) Math.ceil(Math.max(20f, suppliesPerDay * 25f)));
        cargo.addCommodity(Commodities.FUEL, (float) Math.ceil(Math.max(10f, fuelPerLy * 12f)));
        fleet.getFleetData().setSyncNeeded();
        fleet.forceSync();
    }

    /** The closest market with a port that doesn't shoot at us, from where we went down. */
    private static SectorEntityToken nearestPort(CampaignFleetAPI fleet) {
        MarketAPI best = null;
        float bestD = Float.MAX_VALUE;
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m.isHidden() || !m.hasSpaceport() || m.getPrimaryEntity() == null) continue;
            if (m.getFaction() != null && m.getFaction().isHostileTo(fleet.getFaction())) continue;
            float d = Misc.getDistanceToPlayerLY(m.getPrimaryEntity());
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        if (best == null) return null;
        SectorEntityToken e = best.getPrimaryEntity();
        return e.getContainingLocation().createToken(e.getLocation().x + e.getRadius() + 150f, e.getLocation().y);
    }

    public void optionMousedOver(String optionText, Object optionData) {
    }

    public void advance(float amount) {
    }

    public void backFromEngagement(EngagementResultAPI battleResult) {
    }

    public Object getContext() {
        return null;
    }

    public Map<String, MemoryAPI> getMemoryMap() {
        return null;
    }
}
