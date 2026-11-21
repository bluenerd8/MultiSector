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
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Skills;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Turns the host's character into the joining player's own, on the joiner's
 * copy of the host's save. The world (map, factions, markets) stays exactly
 * as the host has it; only the player character and their fleet change.
 *
 * Either a new character (name, gender, portrait, starting fleet) or one
 * exported from the player's own save. Chosen in the game, in
 * {@link multisector.ui.CharacterChooser}.
 */
public final class ProfileApplier {

    /** Starting fleets: other mods can add rows to their own copy of this file. */
    public static final String STARTS_CSV = "data/config/multisector/starts.csv";
    private static final String APPLIED_KEY = "multisector_profileAppliedFor";

    private ProfileApplier() {
    }

    /** True if this download already had a character chosen (the marker is saved in the save). */
    public static boolean isApplied(String downloadId) {
        Object done = Global.getSector().getPersistentData().get(APPLIED_KEY);
        return done != null && (downloadId == null || downloadId.isEmpty() || downloadId.equals(done));
    }

    private static void markApplied(String downloadId) {
        Global.getSector().getPersistentData().put(APPLIED_KEY,
                downloadId == null || downloadId.isEmpty() ? "unknown" : downloadId);
    }

    /** The exported character (F8, Export character), or null if there's none. */
    public static JSONObject readExport() {
        try {
            String text = Global.getSettings().readTextFileFromCommon(CharacterExporter.EXPORT_FILE);
            return text == null || text.trim().isEmpty() ? null : new JSONObject(text);
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================ import

    /** Brings in the exported character, optionally renamed. Returns a message for the player. */
    public static String applyImport(JSONObject export, String name, String downloadId) throws Exception {
        String msg = new CharacterImporter().apply(export, name);
        markApplied(downloadId);
        return msg;
    }

    // =========================================================== new captain

    /** Sets up a fresh captain with the chosen start. Returns a message for the player. */
    public static String applyNew(String name, Gender gender, String portrait, String startId, String downloadId)
            throws Exception {
        PersonAPI person = Global.getSector().getPlayerPerson();
        String n = name == null || name.trim().isEmpty() ? "Captain" : name.trim();
        int space = n.indexOf(' ');
        String first = space > 0 ? n.substring(0, space) : n;
        String last = space > 0 ? n.substring(space + 1).trim() : "";
        person.setName(new FullName(first, last, gender));
        person.setGender(gender);
        if (portrait != null && !portrait.isEmpty()) person.setPortraitSprite(portrait);

        // A fresh captain: level 1, no XP, no skills, nothing to spend.
        MutableCharacterStatsAPI stats = Global.getSector().getPlayerStats();
        stats.resetSkills();
        stats.setLevel(1);
        stats.setXP(0);
        stats.setPoints(0);
        stats.setStoryPoints(0);
        stats.refreshCharacterStatsEffects();

        String startName = applyFleet(startId);
        markApplied(downloadId);
        Global.getLogger(ProfileApplier.class).info("[MultiSector] new character \"" + n + "\" with start " + startName);
        return "you're now " + n + ", flying the " + startName + ".";
    }

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
            FleetMemberAPI m = data.addFleetMember(ships.getString(i));
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

    // ================================================================ starts

    /**
     * Starting fleets from the merged starts.csv, in display order. Starts using
     * a ship this game doesn't have are left out. Each has id, name,
     * description, credits, cargo, officer, and ships (variant ids).
     */
    public static List<JSONObject> listStarts() {
        Logger log = Global.getLogger(ProfileApplier.class);
        List<JSONObject> out = new ArrayList<JSONObject>();
        try {
            JSONArray rows = Global.getSettings().getMergedSpreadsheetDataForMod("id", STARTS_CSV, "multisector");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String id = row.optString("id", "").trim();
                if (id.isEmpty() || id.startsWith("#")) continue;
                JSONArray ships = new JSONArray();
                boolean ok = true;
                for (String v : row.optString("ships", "").split(",")) {
                    v = v.trim();
                    if (v.isEmpty()) continue;
                    ShipVariantAPI variant = null;
                    try {
                        variant = Global.getSettings().getVariant(v);
                    } catch (Exception ignored) {
                    }
                    if (variant == null) {
                        log.warn("[MultiSector] start \"" + id + "\" uses unknown ship variant \"" + v + "\"; hiding it");
                        ok = false;
                        break;
                    }
                    ships.put(v);
                }
                if (!ok || ships.length() == 0) continue;
                JSONObject s = new JSONObject();
                s.put("id", id);
                s.put("name", row.optString("name", id));
                s.put("description", row.optString("description", ""));
                s.put("credits", row.optInt("credits", 0));
                s.put("cargo", row.optString("cargo", ""));
                s.put("officer", row.optBoolean("officer", false));
                s.put("order", row.optDouble("order", 1000));
                s.put("ships", ships);
                out.add(s);
            }
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't read " + STARTS_CSV, e);
        }
        Collections.sort(out, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                return Double.compare(a.optDouble("order", 1000), b.optDouble("order", 1000));
            }
        });
        return out;
    }

    private static JSONObject findStart(String startId) {
        List<JSONObject> starts = listStarts();
        for (JSONObject s : starts) if (s.optString("id").equals(startId)) return s;
        if (starts.isEmpty()) throw new IllegalStateException("no starting fleets found in " + STARTS_CSV);
        return starts.get(0);
    }
}
