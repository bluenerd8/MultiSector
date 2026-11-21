package multisector.character;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collection;
import java.util.List;

/**
 * Exports the current player character, fleet, officers and cargo to
 * saves/common/multisector_character.json.data, so it can be brought into
 * another world (a host's MultiSector world).
 *
 * Everything goes through the game's API, so modded ships, weapons, hullmods
 * and skills export like vanilla ones. Things tied to this world (colonies,
 * reputation, story progress, storage) stay behind.
 */
public final class CharacterExporter {

    public static final String EXPORT_FILE = "multisector_character.json";
    public static final int FORMAT = 1;

    private CharacterExporter() {
    }

    /** Writes the export and returns a one-line summary for the player. */
    public static String export() throws Exception {
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        PersonAPI player = Global.getSector().getPlayerPerson();

        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("gameVersion", Global.getSettings().getVersionString());
        root.put("sourceWorld", Global.getSector().getSeedString());
        JSONArray mods = new JSONArray();
        for (ModSpecAPI m : Global.getSettings().getModManager().getEnabledModsCopy()) mods.put(m.getId());
        root.put("mods", mods);

        JSONObject ch = person(player, Global.getSector().getPlayerStats());
        root.put("character", ch);
        root.put("credits", (long) fleet.getCargo().getCredits().get());

        // Officers first, so ships can point at them by index.
        List<OfficerDataAPI> officers = fleet.getFleetData().getOfficersCopy();
        JSONArray officerArr = new JSONArray();
        for (OfficerDataAPI o : officers) officerArr.put(person(o.getPerson(), o.getPerson().getStats()));
        root.put("officers", officerArr);

        JSONArray ships = new JSONArray();
        for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
            if (m.isFighterWing()) continue;
            JSONObject s = ship(m);
            int officer = -1;
            for (int i = 0; i < officers.size(); i++) {
                if (officers.get(i).getPerson() == m.getCaptain()) officer = i;
            }
            s.put("officer", officer);
            ships.put(s);
        }
        root.put("fleet", ships);
        root.put("cargo", cargo(fleet));

        String text = root.toString();
        if (text.length() > 1000000) {
            throw new IllegalStateException("the export is too big (" + text.length() / 1024 + " KB, the limit is 1 MB)");
        }
        Global.getSettings().writeTextFileToCommon(EXPORT_FILE, text);
        return player.getNameString() + ", level " + ch.getInt("level") + ", " + ships.length() + " ships, "
                + officerArr.length() + " officers, " + root.getLong("credits") + " credits";
    }

    private static JSONObject person(PersonAPI p, MutableCharacterStatsAPI stats) throws Exception {
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

    private static JSONObject ship(FleetMemberAPI m) throws Exception {
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

    private static JSONArray cargo(CampaignFleetAPI fleet) throws Exception {
        JSONArray out = new JSONArray();
        for (CargoStackAPI st : fleet.getCargo().getStacksCopy()) {
            if (st.isNull() || st.getSize() <= 0) continue;
            JSONObject o = new JSONObject();
            if (st.isCommodityStack()) {
                o.put("type", "commodity");
                o.put("id", st.getCommodityId());
            } else if (st.isWeaponStack() && st.getWeaponSpecIfWeapon() != null) {
                o.put("type", "weapon");
                o.put("id", st.getWeaponSpecIfWeapon().getWeaponId());
            } else if (st.isFighterWingStack() && st.getFighterWingSpecIfWing() != null) {
                o.put("type", "wing");
                o.put("id", st.getFighterWingSpecIfWing().getId());
            } else if (st.getHullModSpecIfHullMod() != null) {
                o.put("type", "hullmod");
                o.put("id", st.getHullModSpecIfHullMod().getId());
            } else if (st.isSpecialStack() && st.getSpecialDataIfSpecial() != null) {
                SpecialItemData d = st.getSpecialDataIfSpecial();
                o.put("type", "special");
                o.put("id", d.getId());
                o.put("data", d.getData() == null ? JSONObject.NULL : d.getData());
            } else {
                continue;
            }
            o.put("qty", st.getSize());
            out.put(o);
        }
        return out;
    }

    private static JSONArray array(Collection<String> c) {
        JSONArray a = new JSONArray();
        if (c != null) for (String s : c) a.put(s);
        return a;
    }
}
