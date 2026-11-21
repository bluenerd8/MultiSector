package multisector.character;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.FullName.Gender;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Skills;
import multisector.catalog.CatalogExporter;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Random;

/**
 * Turns the host's character into the joining player's own, on the joiner's
 * copy of the host's save. The world (map, factions, markets) stays exactly
 * as the host has it; only the player character and their fleet change.
 *
 * Applied once per downloaded save. The marker lives in the save, so loading
 * a copy you've saved yourself doesn't reset your character again.
 */
public final class ProfileApplier {

    public static final String PROFILE_FILE = "multisector_profile.json";
    private static final String APPLIED_KEY = "multisector_profileAppliedFor";

    private ProfileApplier() {
    }

    /**
     * Applies the launcher profile unless this download already got it.
     * Returns a message for the player, or null if nothing was done.
     */
    public static String applyIfNeeded(String downloadId) {
        Logger log = Global.getLogger(ProfileApplier.class);
        Object done = Global.getSector().getPersistentData().get(APPLIED_KEY);
        if (downloadId != null && downloadId.equals(done)) return null;

        JSONObject profile;
        try {
            String text = Global.getSettings().readTextFileFromCommon(PROFILE_FILE);
            if (text == null || text.trim().isEmpty()) return "no character saved in the launcher, so you're playing as the host's character.";
            profile = new JSONObject(text);
        } catch (Exception e) {
            return "couldn't read your launcher character (" + e.getMessage() + "), so you're playing as the host's character.";
        }

        try {
            applyCharacter(profile);
            String startName = applyFleet(profile.optString("start", ""));
            Global.getSector().getPersistentData().put(APPLIED_KEY, downloadId == null ? "unknown" : downloadId);
            log.info("[MultiSector] applied launcher character \"" + profile.optString("name") + "\" with start " + startName);
            return "you're now " + profile.optString("name") + ", flying the " + startName + ".";
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't apply the launcher character", e);
            return "couldn't set up your character: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------- character

    private static void applyCharacter(JSONObject profile) {
        PersonAPI person = Global.getSector().getPlayerPerson();
        Gender gender = "female".equals(profile.optString("gender")) ? Gender.FEMALE : Gender.MALE;
        String name = profile.optString("name", "Captain").trim();
        int space = name.indexOf(' ');
        String first = space > 0 ? name.substring(0, space) : name;
        String last = space > 0 ? name.substring(space + 1).trim() : "";
        person.setName(new FullName(first, last, gender));
        person.setGender(gender);
        String portrait = profile.optString("portrait", "");
        if (!portrait.isEmpty()) person.setPortraitSprite(portrait);

        // A fresh captain: level 1, no XP, no skills, nothing to spend.
        MutableCharacterStatsAPI stats = Global.getSector().getPlayerStats();
        stats.resetSkills();
        stats.setLevel(1);
        stats.setXP(0);
        stats.setPoints(0);
        stats.setStoryPoints(0);
        stats.refreshCharacterStatsEffects();
    }

    // ----------------------------------------------------------------- fleet

    /** Replaces the player fleet's ships, officers and cargo with the chosen start. Returns its name. */
    private static String applyFleet(String startId) throws Exception {
        JSONObject start = findStart(startId);
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        FleetDataAPI data = fleet.getFleetData();
        PersonAPI player = Global.getSector().getPlayerPerson();

        for (FleetMemberAPI m : data.getMembersListCopy()) data.removeFleetMember(m);
        for (OfficerDataAPI o : data.getOfficersCopy()) data.removeOfficer(o.getPerson());
        CargoAPI cargo = fleet.getCargo();
        cargo.clear();
        cargo.getCredits().set(start.optInt("credits", 0));

        JSONArray ships = start.optJSONArray("ships");
        FleetMemberAPI flagship = null;
        for (int i = 0; ships != null && i < ships.length(); i++) {
            String variant = ships.getString(i);
            FleetMemberAPI m = data.addFleetMember(variant);
            if (m == null) continue;
            m.setShipName(Global.getSector().getPlayerFaction().pickRandomShipName());
            if (flagship == null) {
                flagship = m;
                m.setCaptain(player);
                data.setFlagship(m);
            }
        }
        if (flagship == null) throw new IllegalStateException("the start \"" + startId + "\" has no ships this game knows");

        // Same top-up as the game's own new-game setup.
        int crew = 0;
        for (FleetMemberAPI m : data.getMembersListCopy()) {
            crew += Math.ceil(m.getMinCrew() + (m.getMaxCrew() - m.getMinCrew()) * 0.5f);
        }
        cargo.initPartialsIfNeeded();
        cargo.addCrew(crew);
        cargo.addSupplies(10);
        cargo.addCommodity(Commodities.HEAVY_MACHINERY, 10);
        cargo.addFuel(cargo.getMaxFuel() * 0.5f);
        for (String pair : start.optString("cargo", "").split(",")) {
            String[] kv = pair.trim().split(":");
            if (kv.length == 2) {
                try {
                    cargo.addCommodity(kv[0].trim(), Float.parseFloat(kv[1].trim()));
                } catch (Exception ignored) {
                }
            }
        }

        if (start.optBoolean("officer", false)) {
            for (FleetMemberAPI m : data.getMembersListCopy()) {
                if (m == flagship) continue;
                PersonAPI officer = Global.getSector().getPlayerFaction().createRandomPerson(new Random());
                officer.getStats().setSkillLevel(Skills.HELMSMANSHIP, 1);
                officer.setRankId(Ranks.SPACE_LIEUTENANT);
                officer.setPostId(Ranks.POST_OFFICER);
                officer.setPersonality(Personalities.STEADY);
                officer.getStats().refreshCharacterStatsEffects();
                m.setCaptain(officer);
                data.addOfficer(officer);
                break;
            }
        }

        for (FleetMemberAPI m : data.getMembersListCopy()) {
            m.getRepairTracker().setCR(m.getRepairTracker().getMaxCR());
        }
        data.setSyncNeeded();
        data.syncIfNeeded();
        return start.optString("name", startId);
    }

    /** The start row from the merged starts.csv, with ships as a list of variant ids. */
    private static JSONObject findStart(String startId) throws Exception {
        JSONArray rows = Global.getSettings().getMergedSpreadsheetDataForMod("id", CatalogExporter.STARTS_CSV, "multisector");
        JSONObject fallback = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String id = row.optString("id", "").trim();
            if (id.isEmpty() || id.startsWith("#")) continue;
            JSONObject s = new JSONObject();
            s.put("name", row.optString("name", id));
            s.put("credits", row.optInt("credits", 0));
            s.put("cargo", row.optString("cargo", ""));
            s.put("officer", row.optBoolean("officer", false));
            JSONArray ships = new JSONArray();
            for (String v : row.optString("ships", "").split(",")) {
                if (!v.trim().isEmpty()) ships.put(v.trim());
            }
            s.put("ships", ships);
            if (id.equals(startId)) return s;
            if (fallback == null) fallback = s;
        }
        if (fallback == null) throw new IllegalStateException("no starting fleets found in " + CatalogExporter.STARTS_CSV);
        return fallback;
    }
}
