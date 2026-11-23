package multisector.character;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.FullName.Gender;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.characters.SkillSpecAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Skills;
import org.json.JSONObject;

import java.util.Random;

/**
 * Turns the host's character into the joining player's own, on the joiner's
 * copy of the host's save. The world (map, factions, markets) stays exactly
 * as the host has it; only the player character and their fleet change.
 *
 * Either the result of the game's own starting-options dialog (run by
 * {@link multisector.ui.StartOptionsDialog}) or a character exported from the
 * player's own save.
 */
public final class ProfileApplier {

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

    /** Your character from your last session in this world (saved by MultiSector), or null. */
    public static JSONObject readWorldExport() {
        try {
            String text = Global.getSettings().readTextFileFromCommon(
                    CharacterExporter.worldFile(Global.getSector().getSeedString()));
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

    // ========================================================= new character

    /** Which kind of start the player picked, read from the dialog's memory flags. */
    public static final class StartFlags {
        public boolean explorer;
        public boolean merc;
        public boolean spacer;
        public boolean addOfficer;
    }

    /**
     * Builds a fresh captain and fleet from the game's starting-options dialog.
     * Mirrors what a real new game does for your fleet and cargo, but none of
     * the world changes (tutorial missions, contacts, reputation).
     */
    public static String applyCreation(JoinCreationData data, String name, Gender gender, String portrait,
                                       StartFlags flags, String downloadId) throws Exception {
        PersonAPI person = Global.getSector().getPlayerPerson();
        String n = name == null || name.trim().isEmpty() ? "Captain" : name.trim();
        int space = n.indexOf(' ');
        person.setName(new FullName(space > 0 ? n.substring(0, space) : n,
                space > 0 ? n.substring(space + 1).trim() : "", gender));
        person.setGender(gender);
        if (portrait != null && !portrait.isEmpty()) person.setPortraitSprite(portrait);

        // A fresh level-1 captain, plus whatever skills or points the chosen start grants.
        MutableCharacterStatsAPI stats = Global.getSector().getPlayerStats();
        stats.resetSkills();
        stats.setLevel(1);
        stats.setXP(0);
        stats.setPoints(0);
        stats.setStoryPoints(0);
        MutableCharacterStatsAPI chosen = data.getPerson().getStats();
        for (MutableCharacterStatsAPI.SkillLevelAPI s : chosen.getSkillsCopy()) {
            SkillSpecAPI spec = s.getSkill();
            if (spec == null || s.getLevel() <= 0) continue;
            if (spec.isAptitudeEffect()) stats.setAptitudeLevel(spec.getId(), s.getLevel());
            else stats.setSkillLevel(spec.getId(), s.getLevel());
        }
        if (chosen.getPoints() > 0) stats.addPoints(chosen.getPoints());
        stats.refreshCharacterStatsEffects();

        // Fleet
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        FleetDataAPI fd = fleet.getFleetData();
        for (FleetMemberAPI m : fd.getMembersListCopy()) fd.removeFleetMember(m);
        for (OfficerDataAPI o : fd.getOfficersCopy()) fd.removeOfficer(o.getPerson());
        FleetMemberAPI flagship = null;
        for (JoinCreationData.Ship s : data.ships()) {
            if (s.type != FleetMemberType.SHIP) continue; // fighter wings aren't fleet members anymore
            FleetMemberAPI m;
            try {
                m = fd.addFleetMember(s.specId);
            } catch (Exception e) {
                m = null;
            }
            if (m == null) continue;
            m.setShipName(Global.getSector().getPlayerFaction().pickRandomShipName());
            if (flagship == null) {
                flagship = m;
                m.setCaptain(Global.getSector().getPlayerPerson());
                fd.setFlagship(m);
            }
        }
        if (flagship == null) throw new IllegalStateException("the chosen start gave no ships");

        // Cargo: what the start granted (credits, items), then the game's standard top-up.
        CargoAPI cargo = fleet.getCargo();
        cargo.clear();
        cargo.initPartialsIfNeeded();
        CargoAPI granted = data.getStartingCargo();
        cargo.getCredits().set(granted.getCredits().get());
        for (CargoStackAPI st : granted.getStacksCopy()) {
            if (!st.isNull() && st.getSize() > 0) cargo.addItems(st.getType(), st.getData(), st.getSize());
        }
        topUp(fleet, cargo, flags);

        if (flags.addOfficer) {
            for (FleetMemberAPI m : fd.getMembersListCopy()) {
                if (m == flagship) continue;
                PersonAPI officer = Global.getSector().getPlayerFaction().createRandomPerson(new Random());
                officer.getStats().setSkillLevel(Skills.HELMSMANSHIP, 1);
                officer.setRankId(Ranks.SPACE_LIEUTENANT);
                officer.setPostId(Ranks.POST_OFFICER);
                officer.setPersonality(Personalities.STEADY);
                officer.getStats().refreshCharacterStatsEffects();
                m.setCaptain(officer);
                fd.addOfficer(officer);
                break;
            }
        }
        for (FleetMemberAPI m : fd.getMembersListCopy()) m.getRepairTracker().setCR(m.getRepairTracker().getMaxCR());
        fd.setSyncNeeded();
        fd.syncIfNeeded();

        markApplied(downloadId);
        int ships = fd.getMembersListCopy().size();
        Global.getLogger(ProfileApplier.class).info("[MultiSector] new character \"" + n + "\": " + ships + " ships, "
                + (int) cargo.getCredits().get() + " credits");
        return "you're now " + n + ", with " + ships + (ships == 1 ? " ship." : " ships.");
    }

    /**
     * The supplies, fuel, crew and machinery a real new game adds once you've
     * picked your ships (NGCAddStandardStartingScript, as if skipping the
     * tutorial), without its world changes.
     */
    private static void topUp(CampaignFleetAPI fleet, CargoAPI cargo, StartFlags f) {
        int crew = 0;
        for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
            crew += Math.ceil(m.getMinCrew() + (m.getMaxCrew() - m.getMinCrew()) * 0.5f);
        }
        cargo.addCrew(crew);
        cargo.addSupplies(10);
        cargo.addCommodity(Commodities.HEAVY_MACHINERY, 10);
        cargo.addFuel(cargo.getMaxFuel() * 0.5f);
        if (f.explorer) {
            cargo.addCommodity(Commodities.HEAVY_MACHINERY, 50);
            cargo.addSupplies(150);
            cargo.removeFuel(cargo.getMaxFuel() * 0.2f);
        } else if (f.merc) {
            cargo.addSupplies(100);
            cargo.removeFuel(cargo.getMaxFuel() * 0.2f);
        }

        if (f.spacer) {
            cargo.clear();
            cargo.addCrew(2);
            cargo.addSupplies(15);
            cargo.addFuel(cargo.getMaxFuel());
            return;
        }
        float free = cargo.getSpaceLeft();
        float machinery = Math.min(free, 15);
        if (machinery > 0) {
            cargo.addCommodity(Commodities.HEAVY_MACHINERY, machinery);
            free -= machinery;
        }
        float supplies = Math.min(free, 70);
        if (supplies > 0) cargo.addSupplies(supplies);
        float fuel = Math.min(cargo.getFreeFuelSpace() - 10f, 70);
        if (fuel > 0) cargo.addFuel(fuel);
        float addCrew = Math.min(cargo.getFreeCrewSpace() - 10, 100f);
        if (addCrew > 0) cargo.addCrew((int) addCrew);
    }
}
