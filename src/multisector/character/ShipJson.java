package multisector.character;

import com.fs.starfarer.api.FactoryAPI;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.FullName.Gender;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.loading.VariantSource;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Ships and people as JSON, with their exact loadouts and skills. Used by
 * the character export/import, and to send a fleet's exact copy over the
 * network so a friend fights the same ships the host has.
 *
 * Everything goes through the game's API, so modded ships, weapons, hullmods
 * and skills work like vanilla ones. Anything this game doesn't have is
 * skipped and named in the caller's list.
 */
public final class ShipJson {

    private ShipJson() {
    }

    // ---------------------------------------------------------------- people

    public static JSONObject person(PersonAPI p, MutableCharacterStatsAPI stats) throws Exception {
        JSONObject o = new JSONObject();
        FullName n = p.getName();
        o.put("first", n.getFirst() == null ? "" : n.getFirst());
        o.put("last", n.getLast() == null ? "" : n.getLast());
        o.put("gender", p.getGender() == null ? "MALE" : p.getGender().name());
        o.put("portrait", p.getPortraitSprite());
        o.put("personality", p.getPersonalityAPI() == null ? "" : p.getPersonalityAPI().getId());
        o.put("rank", p.getRankId() == null ? "" : p.getRankId());
        o.put("post", p.getPostId() == null ? "" : p.getPostId());
        o.put("level", stats.getLevel());
        o.put("xp", stats.getXP());
        o.put("bonusXp", stats.getBonusXp());
        o.put("points", stats.getPoints());
        o.put("storyPoints", stats.getStoryPoints());
        JSONObject skills = new JSONObject();
        for (MutableCharacterStatsAPI.SkillLevelAPI s : stats.getSkillsCopy()) {
            if (s.getLevel() > 0 && s.getSkill() != null) skills.put(s.getSkill().getId(), s.getLevel());
        }
        o.put("skills", skills);
        return o;
    }

    public static void applyPerson(PersonAPI p, MutableCharacterStatsAPI stats, JSONObject o, String nameOverride) {
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

    public static JSONObject ship(FleetMemberAPI m) throws Exception {
        ShipVariantAPI v = m.getVariant();
        JSONObject s = new JSONObject();
        s.put("hull", v.getHullSpec().getHullId());
        s.put("baseHull", v.getHullSpec().getBaseHullId());
        s.put("stockVariant", v.getHullVariantId());
        s.put("variantName", v.getDisplayName());
        s.put("shipName", m.getShipName());
        s.put("flagship", m.isFlagship());
        s.put("cr", m.getRepairTracker().getCR());
        s.put("hullFraction", m.getStatus().getHullFraction());
        s.put("mothballed", m.getRepairTracker().isMothballed());
        s.put("vents", v.getNumFluxVents());
        s.put("caps", v.getNumFluxCapacitors());
        s.put("hasModules", v.getStationModules() != null && !v.getStationModules().isEmpty());

        JSONObject weapons = new JSONObject();
        for (String slot : v.getFittedWeaponSlots()) {
            String w = v.getWeaponId(slot);
            if (w != null) weapons.put(slot, w);
        }
        s.put("weapons", weapons);

        JSONObject wings = new JSONObject();
        List<String> wingList = v.getWings();
        for (int i = 0; wingList != null && i < wingList.size(); i++) {
            if (wingList.get(i) != null) wings.put(String.valueOf(i), wingList.get(i));
        }
        s.put("wings", wings);

        JSONArray mods = new JSONArray();
        for (String id : v.getNonBuiltInHullmods()) {
            if (!v.getPermaMods().contains(id)) mods.put(id);
        }
        s.put("hullmods", mods);
        s.put("permaMods", array(v.getPermaMods()));
        s.put("sMods", array(v.getSMods()));
        s.put("sModdedBuiltIns", array(v.getSModdedBuiltIns()));
        return s;
    }

    /**
     * Rebuilds a ship with its exact loadout. Returns null if this game
     * doesn't have the hull. Missing weapons, wings and hullmods are left
     * off and added to skipped.
     */
    public static FleetMemberAPI buildShip(JSONObject s, List<String> skipped) throws Exception {
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

    // --------------------------------------------------------------- helpers

    /** True if this game knows the thing; the spec lookups throw or return null otherwise. */
    public static boolean exists(String type, String id) {
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

    private static JSONArray array(Collection<String> c) {
        JSONArray a = new JSONArray();
        if (c != null) for (String s : c) a.put(s);
        return a;
    }

    private static List<String> list(JSONArray a) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    private static Set<String> set(JSONArray a) {
        return new HashSet<String>(list(a));
    }
}
