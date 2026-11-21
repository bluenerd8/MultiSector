package multisector.catalog;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import com.fs.starfarer.api.campaign.FactionSpecAPI;
import com.fs.starfarer.api.characters.FullName.Gender;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Writes what this game install can offer a new MultiSector character to
 * saves/common/multisector_catalog.json.data, for the launcher's Character tab.
 *
 * It runs at startup with all enabled mods loaded, so modded portraits
 * (added to the player faction) and modded ships in starts.csv show up in the
 * launcher without the launcher knowing anything about mods.
 */
public final class CatalogExporter {

    public static final String CATALOG_FILE = "multisector_catalog.json";
    public static final String STARTS_CSV = "data/config/multisector/starts.csv";
    private static final String MOD_ID = "multisector";

    private CatalogExporter() {
    }

    public static void export() {
        Logger log = Global.getLogger(CatalogExporter.class);
        try {
            JSONObject root = new JSONObject();
            root.put("gameVersion", Global.getSettings().getVersionString());
            root.put("createdAt", System.currentTimeMillis());

            JSONArray mods = new JSONArray();
            for (ModSpecAPI m : Global.getSettings().getModManager().getEnabledModsCopy()) {
                JSONObject o = new JSONObject();
                o.put("id", m.getId());
                o.put("name", m.getName());
                o.put("path", m.getPath());
                mods.put(o);
            }
            root.put("mods", mods);

            // The same portrait pool the game's own character creator uses.
            FactionSpecAPI player = Global.getSettings().getFactionSpec("player");
            JSONObject portraits = new JSONObject();
            portraits.put("male", toArray(player.getAllPortraits(Gender.MALE)));
            portraits.put("female", toArray(player.getAllPortraits(Gender.FEMALE)));
            root.put("portraits", portraits);

            root.put("starts", starts(log));

            String text = root.toString();
            if (text.length() > 1000000) {
                log.warn("[MultiSector] catalog is " + text.length() + " chars, over the 1 MB limit; trimming portraits");
                portraits.put("male", new JSONArray());
                portraits.put("female", new JSONArray());
                text = root.toString();
            }
            Global.getSettings().writeTextFileToCommon(CATALOG_FILE, text);
            log.info("[MultiSector] wrote character catalog: " + root.getJSONArray("starts").length() + " starts, "
                    + player.getAllPortraits(Gender.MALE).size() + "+" + player.getAllPortraits(Gender.FEMALE).size()
                    + " portraits");
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't write the character catalog", e);
        }
    }

    private static JSONArray starts(Logger log) throws Exception {
        JSONArray out = new JSONArray();
        JSONArray rows = Global.getSettings().getMergedSpreadsheetDataForMod("id", STARTS_CSV, MOD_ID);
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
                    log.warn("[MultiSector] start \"" + id + "\" uses unknown ship variant \"" + v + "\"; skipping it");
                    ok = false;
                    break;
                }
                JSONObject s = new JSONObject();
                s.put("variant", v);
                s.put("hull", variant.getHullSpec().getHullName());
                s.put("size", variant.getHullSpec().getHullSize().name());
                s.put("designation", variant.getHullSpec().getDesignation());
                s.put("display", variant.getDisplayName());
                ships.put(s);
            }
            if (!ok || ships.length() == 0) continue;

            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", row.optString("name", id));
            o.put("description", row.optString("description", ""));
            o.put("credits", row.optInt("credits", 0));
            o.put("cargo", row.optString("cargo", ""));
            o.put("officer", row.optBoolean("officer", false));
            o.put("order", row.optDouble("order", 1000));
            o.put("ships", ships);
            out.put(o);
        }
        return out;
    }

    private static JSONArray toArray(List<String> list) {
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        return a;
    }
}
