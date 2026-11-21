package multisector.character;

import com.fs.starfarer.api.FactoryAPI;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
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

        String name = player.getNameString();
        log.info("[MultiSector] imported character " + name + ": " + shipCount + " ships, " + officers.size()
                + " officers; skipped " + skipped);
        String msg = "you're now " + name + " with your imported fleet (" + shipCount + " ships).";
        if (!skipped.isEmpty()) {
            msg += " Left behind because this game doesn't have them (missing mods?): " + summarize(skipped) + ".";
        }
        return msg;
    }

    // ---------------------------------------------------------------- people

    private void applyPerson(PersonAPI p, MutableCharacterStatsAPI stats, JSONObject o, String nameOverride) {
        Gender gender = "FEMALE".equalsIgnoreCase(o.optString("gender")) ? Gender.FEMALE : Gender.MALE;
        String first = o.optString("first", "");
        String last = o.optString("last", "");
        if (nameOverride != null && !nameOverride.trim().isEmpty()) {
            String n = nameOverride.trim();
            int space = n.indexOf(' ');
            first = space > 0 ? n.substring(0, space) : n;
            last = space > 0 ? n.substring(space + 1).trim() : "";
        }
        p.setName(new FullName(first, last, gender));
        p.setGender(gender);
        String portrait = o.optString("portrait", "");
        if (!portrait.isEmpty()) p.setPortraitSprite(portrait);
        if (!o.optString("personality", "").isEmpty()) p.setPersonality(o.optString("personality"));
        if (!o.optString("rank", "").isEmpty()) p.setRankId(o.optString("rank"));
        if (!o.optString("post", "").isEmpty()) p.setPostId(o.optString("post"));

        stats.resetSkills();
        stats.setLevel(o.optInt("level", 1));
        stats.setXP(o.optLong("xp", 0));
        stats.setBonusXp(o.optLong("bonusXp", 0));
        stats.setPoints(o.optInt("points", 0));
        stats.setStoryPoints(o.optInt("storyPoints", 0));
        JSONObject skills = o.optJSONObject("skills");
        if (skills != null) {
            Iterator<?> it = skills.keys();
            while (it.hasNext()) {
                String id = (String) it.next();
                if (!exists("skill", id)) continue;
                stats.setSkillLevel(id, (float) skills.optDouble(id, 0));
            }
        }
        stats.refreshCharacterStatsEffects();
    }

    // ----------------------------------------------------------------- ships

    private FleetMemberAPI buildShip(JSONObject s) throws Exception {
        String label = s.optString("shipName", s.optString("hull"));
        if (s.optBoolean("hasModules")) {
            // Multi-module ships: rebuilding each module's loadout isn't supported yet; use the stock layout.
            String stock = s.optString("stockVariant", "");
            if (exists("variant", stock)) {
                FleetMemberAPI m = Global.getFactory().createFleetMember(FleetMemberType.SHIP, stock);
                m.setShipName(s.optString("shipName", m.getShipName()));
                return m;
            }
            skipped.add("ship " + label);
            return null;
        }

        ShipHullSpecAPI hull = hullSpec(s.optString("hull"));
        if (hull == null) hull = hullSpec(s.optString("baseHull"));
        if (hull == null) {
            skipped.add("ship " + label + " (" + s.optString("hull") + ")");
            return null;
        }
        ShipVariantAPI v = Global.getSettings().createEmptyVariant(hull.getHullId() + "_multisector", hull);
        v.setSource(VariantSource.REFIT);
        String vName = s.optString("variantName", "");
        if (!vName.isEmpty()) v.setVariantDisplayName(vName);

        JSONObject weapons = s.optJSONObject("weapons");
        if (weapons != null) {
            Iterator<?> it = weapons.keys();
            while (it.hasNext()) {
                String slot = (String) it.next();
                String w = weapons.getString(slot);
                if (v.getWeaponId(slot) != null) continue; // built-in, already there
                if (exists("weapon", w)) v.addWeapon(slot, w);
                else skipped.add("weapon " + w);
            }
        }
        JSONObject wings = s.optJSONObject("wings");
        if (wings != null) {
            Iterator<?> it = wings.keys();
            while (it.hasNext()) {
                String idx = (String) it.next();
                String w = wings.getString(idx);
                int i = Integer.parseInt(idx);
                if (v.getWingId(i) != null) continue; // built-in
                if (exists("wing", w)) v.setWingId(i, w);
                else skipped.add("fighter wing " + w);
            }
        }
        Set<String> sMods = set(s.optJSONArray("sMods"));
        for (String id : list(s.optJSONArray("permaMods"))) {
            if (exists("hullmod", id)) v.addPermaMod(id, sMods.contains(id));
            else skipped.add("hullmod " + id);
        }
        for (String id : list(s.optJSONArray("hullmods"))) {
            if (exists("hullmod", id)) v.addMod(id);
            else skipped.add("hullmod " + id);
        }
        for (String id : list(s.optJSONArray("sModdedBuiltIns"))) {
            if (exists("hullmod", id)) v.getSModdedBuiltIns().add(id);
        }
        v.setNumFluxVents(s.optInt("vents", 0));
        v.setNumFluxCapacitors(s.optInt("caps", 0));

        FactoryAPI f = Global.getFactory();
        FleetMemberAPI m = f.createFleetMember(FleetMemberType.SHIP, v);
        String shipName = s.optString("shipName", "");
        if (!shipName.isEmpty()) m.setShipName(shipName);
        return m;
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

    /** True if this game knows the thing; the spec lookups throw or return null otherwise. */
    private static boolean exists(String type, String id) {
        if (id == null || id.isEmpty()) return false;
        try {
            if (type.equals("weapon")) return Global.getSettings().getWeaponSpec(id) != null;
            if (type.equals("wing")) return Global.getSettings().getFighterWingSpec(id) != null;
            if (type.equals("hullmod")) return Global.getSettings().getHullModSpec(id) != null;
            if (type.equals("skill")) return Global.getSettings().getSkillSpec(id) != null;
            if (type.equals("special")) return Global.getSettings().getSpecialItemSpec(id) != null;
            if (type.equals("commodity")) return Global.getSettings().getCommoditySpec(id) != null;
            if (type.equals("variant")) return Global.getSettings().getVariant(id) != null;
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    private static ShipHullSpecAPI hullSpec(String id) {
        if (id == null || id.isEmpty()) return null;
        try {
            return Global.getSettings().getHullSpec(id);
        } catch (Throwable t) {
            return null;
        }
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
