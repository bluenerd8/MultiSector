package multisector.character;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.Script;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CharacterDataAPI;
import com.fs.starfarer.api.characters.CharacterCreationData;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.procgen.StarAge;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stand-in for the game's new-game data while running the real starting-
 * options dialog inside an existing world. The game's NGC rule commands
 * (and any mod's) write their choices here: ships, credits, cargo, skills.
 * Nothing here generates or touches the world; MultiSector reads the result
 * and builds only the joining player's character and fleet from it.
 */
public final class JoinCreationData implements CharacterCreationData {

    /** A starting ship or wing the rules added, in order. */
    public static final class Ship {
        public final String specId;
        public final FleetMemberType type;

        Ship(String specId, FleetMemberType type) {
            this.specId = specId;
            this.type = type;
        }
    }

    private final PersonAPI person = Global.getFactory().createPerson();
    private final CargoAPI cargo = Global.getFactory().createCargo(true);
    private final List<Ship> ships = new ArrayList<Ship>();
    private final List<Script> scripts = new ArrayList<Script>();
    private final List<Script> scriptsBeforeTimePass = new ArrayList<Script>();
    private final Map<String, Object> customData = new HashMap<String, Object>();
    private final Vector2f coordinates = new Vector2f();
    private String startingLocationName = "";
    private String difficulty = "normal";
    private String seedString = "";
    private long seed;
    private StarAge sectorAge = StarAge.ANY;
    private String sectorSize = "";
    private boolean done;
    private boolean withTimePass;
    private boolean campaignHelp;
    private boolean extMode;

    public List<Ship> ships() {
        return ships;
    }

    // ------------------------------------------------- CharacterCreationData

    public String getStartingLocationName() { return startingLocationName; }
    public void setStartingLocationName(String name) { startingLocationName = name; }
    public Vector2f getStartingCoordinates() { return coordinates; }
    public PersonAPI getPerson() { return person; }
    public void clearAdditionalShips() { ships.clear(); }

    public void addStartingFleetMember(String specId, FleetMemberType type) {
        ships.add(new Ship(specId, type));
    }

    public void removeStartingFleetMember(String specId) {
        for (int i = ships.size() - 1; i >= 0; i--) {
            if (ships.get(i).specId.equals(specId)) {
                ships.remove(i);
                return;
            }
        }
    }

    public CargoAPI getStartingCargo() { return cargo; }

    /** The real player's character data: rule commands that touch it affect the joining player's own character. */
    public CharacterDataAPI getCharacterData() { return Global.getSector().getCharacterData(); }

    public void setDone(boolean done) { this.done = done; }
    public boolean isDone() { return done; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    /** Collected but never run: the game's own starting scripts change the world (tutorial, reputation). */
    public void addScript(Script script) { scripts.add(script); }
    public List<Script> getScripts() { return scripts; }

    public String getSeedString() { return seedString; }
    public void setSeedString(String seedString) { this.seedString = seedString; }
    public long getSeed() { return seed; }
    public void setSeed(long seed) { this.seed = seed; }
    public StarAge getSectorAge() { return sectorAge; }
    public void setSectorAge(StarAge sectorAge) { this.sectorAge = sectorAge; }
    public String getSectorSize() { return sectorSize; }
    public void setSectorSize(String sectorSize) { this.sectorSize = sectorSize; }
    public Map<String, Object> getCustomData() { return customData; }

    public List<String> getStartingShips() {
        List<String> out = new ArrayList<String>();
        for (Ship s : ships) out.add(s.specId);
        return out;
    }

    public boolean isWithTimePass() { return withTimePass; }
    public void setWithTimePass(boolean withTimePass) { this.withTimePass = withTimePass; }
    public void addScriptBeforeTimePass(Script script) { scriptsBeforeTimePass.add(script); }
    public List<Script> getScriptsBeforeTimePass() { return scriptsBeforeTimePass; }
    public boolean isCampaignHelpEnabled() { return campaignHelp; }
    public void setCampaignHelpEnabled(boolean campaignHelp) { this.campaignHelp = campaignHelp; }
    public boolean isExtMode() { return extMode; }
    public void setExtMode(boolean extMode) { this.extMode = extMode; }
}
