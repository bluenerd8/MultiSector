package multisector.character;

import com.fs.starfarer.api.FactoryAPI;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.FullName.Gender;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.loading.VariantSource;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Rebuilds an exported character (see {@link CharacterExporter}) as the player
 * in the current world: character, ships with their exact loadouts, officers
 * and cargo.
 *
 * Anything this game doesn't know (a ship, weapon or hullmod from a mod that
 * isn't enabled here) is skipped and listed, instead of failing the import.
 */
final class CharacterImporter {

    private final Logger log = Global.getLogger(CharacterImporter.class);
    private final List<String> skipped = new ArrayList<String>();
    private int shipCount;

    /**
     * Applies the export. nameOverride replaces the exported name when set
     * (players can rename an imported character in the launcher).
     * Returns a summary for the player.
     */
    String apply(JSONObject export, String nameOverride) throws Exception {
        PersonAPI player = Global.getSector().getPlayerPerson();
        JSONObject ch = export.getJSONObject("character");
        applyPerson(player, Global.getSector().getPlayerStats(), ch, nameOverride);

        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        FleetDataAPI data = fleet.getFleetData();
        for (FleetMemberAPI m : data.getMembersListCopy()) data.removeFleetMember(m);
        for (OfficerDataAPI o : data.getOfficersCopy()) data.removeOfficer(o.getPerson());

        // Officers
        List<PersonAPI> officers = new ArrayList<PersonAPI>();
        JSONArray officerArr = export.optJSONArray("officers");
        for (int i = 0; officerArr != null && i < officerArr.length(); i++) {
            PersonAPI o = Global.getFactory().createPerson();
            applyPerson(o, o.getStats(), officerArr.getJSONObject(i), null);
            data.addOfficer(o);
            officers.add(o);
        }

        // Ships
        FleetMemberAPI flagship = null;
        FleetMemberAPI first = null;
        JSONArray ships = export.optJSONArray("fleet");
        for (int i = 0; ships != null && i < ships.length(); i++) {
            JSONObject s = ships.getJSONObject(i);
            FleetMemberAPI m = buildShip(s);
            if (m == null) continue;
            data.addFleetMember(m);
            shipCount++;
            if (first == null) first = m;
            if (s.optBoolean("flagship")) flagship = m;
            int oi = s.optInt("officer", -1);
            if (oi >= 0 && oi < officers.size() && !s.optBoolean("flagship")) m.setCaptain(officers.get(oi));
            JSONObject ai = s.optJSONObject("aiCaptain");
            if (ai != null && oi < 0 && !s.optBoolean("flagship")) {
                PersonAPI core = aiCaptain(ai);
                if (core != null) m.setCaptain(core);
            }
            m.getRepairTracker().setCR((float) s.optDouble("cr", m.getRepairTracker().getMaxCR()));
            m.getStatus().setHullFraction((float) s.optDouble("hullFraction", 1));
            if (s.optBoolean("mothballed")) m.getRepairTracker().setMothballed(true);
        }
        if (flagship == null) flagship = first;
        if (flagship == null) throw new IllegalStateException("none of the exported ships exist in this game (missing mods?)");
        flagship.setCaptain(player);
        data.setFlagship(flagship);

        // Officers whose ship didn't make it stay in the fleet, unassigned.
        applyCargo(fleet.getCargo(), export);
        data.setSyncNeeded();
        data.syncIfNeeded();

        // Back in the same world: your standing with the factions, and where you were.
        boolean sameWorld = Global.getSector().getSeedString().equals(export.optString("sourceWorld", ""));
        String where = "";
        if (sameWorld) {
            relations(export.optJSONObject("relations"));
            where = place(fleet, export.optJSONObject("where"));
        }

        String name = player.getNameString();
        log.info("[MultiSector] imported character " + name + ": " + shipCount + " ships, " + officers.size()
                + " officers; skipped " + skipped);
        String msg = "you're now " + name + " with your imported fleet (" + shipCount + " ships)" + where + ".";
        if (!skipped.isEmpty()) {
            msg += " Left behind because this game doesn't have them (missing mods?): " + summarize(skipped) + ".";
        }
        return msg;
    }

    // ---------------------------------------------------------------- people

    /** An AI core commanding a ship, as it was (made by the core's own plugin where there is one). */
    private PersonAPI aiCaptain(JSONObject o) {
        String core = o.optString("aiCore", "");
        if (core.isEmpty() || !exists("commodity", core)) {
            if (!core.isEmpty()) skipped.add("AI core " + core);
            return null;
        }
        PersonAPI p = null;
        try {
            com.fs.starfarer.api.campaign.AICoreOfficerPlugin plugin = com.fs.starfarer.api.util.Misc.getAICoreOfficerPlugin(core);
            if (plugin != null) p = plugin.createPerson(core, com.fs.starfarer.api.impl.campaign.ids.Factions.PLAYER, new java.util.Random());
        } catch (RuntimeException e) {
            p = null;
        }
        if (p == null) p = Global.getFactory().createPerson();
        applyPerson(p, p.getStats(), o, null);
        p.setAICoreId(core);
        p.setFaction(com.fs.starfarer.api.impl.campaign.ids.Factions.PLAYER);
        return p;
    }

    /** Your standing with each faction, as you left it in this world. */
    private void relations(JSONObject o) {
        if (o == null) return;
        com.fs.starfarer.api.campaign.FactionAPI me = Global.getSector().getPlayerFaction();
        Iterator<?> it = o.keys();
        int n = 0;
        while (it.hasNext()) {
            String id = (String) it.next();
            com.fs.starfarer.api.campaign.FactionAPI f = Global.getSector().getFaction(id);
            if (f == null || f == me || f.isPlayerFaction()) continue;
            me.setRelationship(id, (float) o.optDouble(id, me.getRelationship(id)));
            n++;
        }
        log.info("[MultiSector] put back our standing with " + n + " factions");
    }

    /** Your fleet back where it was in this world. Returns " back where you left off" (or "" if it stays). */
    private String place(CampaignFleetAPI fleet, JSONObject w) {
        if (w == null) return "";
        LocationAPI loc = multisector.sync.FleetSync.findLocation(w.optString("loc", ""));
        if (loc == null) return "";
        float x = (float) w.optDouble("x", fleet.getLocation().x);
        float y = (float) w.optDouble("y", fleet.getLocation().y);
        LocationAPI was = fleet.getContainingLocation();
        try {
            if (was != loc) {
                if (was != null) was.removeEntity(fleet);
                loc.addEntity(fleet);
                Global.getSector().setCurrentLocation(loc);
            }
            fleet.setLocation(x, y);
            fleet.setMoveDestination(x, y);
            log.info("[MultiSector] back where we left off: " + loc.getName() + " (" + Math.round(x) + ", " + Math.round(y) + ")");
            return ", back where you left off in " + loc.getName();
        } catch (RuntimeException e) {
            log.warn("[MultiSector] couldn't put our fleet back where it was", e);
            if (fleet.getContainingLocation() == null && was != null) was.addEntity(fleet);
            return "";
        }
    }

    private void applyPerson(PersonAPI p, MutableCharacterStatsAPI stats, JSONObject o, String nameOverride) {
        ShipJson.applyPerson(p, stats, o, nameOverride);
    }

    // ----------------------------------------------------------------- ships

    private FleetMemberAPI buildShip(JSONObject s) throws Exception {
        return ShipJson.buildShip(s, skipped);
    }

    // ----------------------------------------------------------------- cargo

    private void applyCargo(CargoAPI cargo, JSONObject export) throws Exception {
        cargo.clear();
        cargo.initPartialsIfNeeded();
        cargo.getCredits().set(export.optLong("credits", 0));
        JSONArray items = export.optJSONArray("cargo");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            String type = o.optString("type");
            String id = o.optString("id");
            float qty = (float) o.optDouble("qty", 0);
            if (qty <= 0) continue;
            if (!exists(type, id)) {
                skipped.add(type + " " + id);
                continue;
            }
            if (type.equals("commodity")) {
                cargo.addCommodity(id, qty);
            } else if (type.equals("weapon")) {
                cargo.addWeapons(id, (int) qty);
            } else if (type.equals("wing")) {
                cargo.addFighters(id, (int) qty);
            } else if (type.equals("hullmod")) {
                cargo.addHullmods(id, (int) qty);
            } else if (type.equals("special")) {
                String d = o.isNull("data") ? null : o.optString("data", null);
                cargo.addSpecial(new SpecialItemData(id, d), qty);
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private static boolean exists(String type, String id) {
        return ShipJson.exists(type, id);
    }

    private static List<String> list(JSONArray a) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    private static Set<String> set(JSONArray a) {
        return new HashSet<String>(list(a));
    }

    private static String summarize(List<String> items) {
        Set<String> unique = new HashSet<String>(items);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String s : unique) {
            if (n++ == 6) {
                sb.append(", and ").append(unique.size() - 6).append(" more");
                break;
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }
}
