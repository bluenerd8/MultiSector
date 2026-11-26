package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.impl.campaign.RuleBasedInteractionDialogPluginImpl;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.terrain.DebrisFieldTerrainPlugin;
import multisector.net.Session;
import org.apache.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Salvaging something in space (a derelict, a debris field, cargo pods, a
 * cache or probe) is one player's at a time: the host keeps who has what
 * ({@link multisector.net.ObjectLockTable}). Opening one asks first; if
 * another player is on it, you're told and can come back once they're done.
 * The claim ends when your dialog closes. If the host doesn't answer within
 * a few seconds, you go ahead as before.
 */
public final class ObjectLocks implements EveryFrameScript {

    private static final Logger log = Global.getLogger(ObjectLocks.class);
    private static final long ANSWER_NANOS = 3_000_000_000L;

    /** What we hold now: key, and the object. */
    private static String heldKey;
    private static SectorEntityToken held;
    /** Answers that came in: key to "grant" or the holder's name. */
    private static final Map<String, String> answers = new HashMap<String, String>();

    public boolean isDone() {
        return false;
    }

    public boolean runWhilePaused() {
        return true;
    }

    public void advance(float amount) {
        Session s = Session.get();
        if (!s.isActive()) {
            heldKey = null;
            held = null;
            answers.clear();
            return;
        }
        readAnswers(s);
        if (heldKey == null) return;
        // Done once no dialog with it is open any more (or it's gone).
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        InteractionDialogAPI d = ui == null ? null : ui.getCurrentInteractionDialog();
        boolean still = d != null && d.getInteractionTarget() == held && !held.isExpired();
        if (!still) {
            s.releaseObject(heldKey);
            log.info("[MultiSector] done salvaging " + heldKey);
            heldKey = null;
            held = null;
        }
    }

    private static void readAnswers(Session s) {
        Session.PlayerMessage m;
        while ((m = s.pollObjectReply()) != null) {
            String key = field(m.json, "key");
            if (key == null) continue;
            answers.put(key, m.json.contains("\"k\":\"grant\"") ? "grant" : field(m.json, "by") == null ? "someone"
                    : field(m.json, "by"));
        }
    }

    private static String field(String json, String name) {
        try {
            return new org.json.JSONObject(json).optString(name, null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Something only one player should salvage at a time. */
    static boolean isSalvage(SectorEntityToken t) {
        if (t == null || t.getId() == null || t.getContainingLocation() == null) return false;
        if (t instanceof CampaignTerrainAPI) return ((CampaignTerrainAPI) t).getPlugin() instanceof DebrisFieldTerrainPlugin;
        if (!(t instanceof CustomCampaignEntityAPI)) return false;
        if (Wreckage.is(t)) return true;
        if (Entities.CARGO_PODS.equals(((CustomCampaignEntityAPI) t).getCustomEntityType())) return true;
        return t.hasTag(Tags.SALVAGEABLE);
    }

    /** We hold it already (the dialog that asked is carrying on). */
    static boolean holds(SectorEntityToken t) {
        return t != null && t == held;
    }

    static String keyOf(SectorEntityToken t) {
        return FleetSync.locationId(t.getContainingLocation()) + "|" + t.getId();
    }

    /** A save is loading: nothing held. */
    public static void forget() {
        heldKey = null;
        held = null;
        answers.clear();
    }

    /**
     * Asks the host while showing a short line, then hands the dialog to
     * the game's own salvage dialog, or says who's on it.
     */
    static final class Claim implements InteractionDialogPlugin {
        private final SectorEntityToken target;
        private final String key;
        private InteractionDialogAPI dialog;
        private long asked;
        private boolean decided;

        Claim(SectorEntityToken target) {
            this.target = target;
            this.key = keyOf(target);
        }

        public void init(InteractionDialogAPI dialog) {
            this.dialog = dialog;
            answers.remove(key);
            asked = System.nanoTime();
            Session.get().claimObject(key);
            readAnswers(Session.get());
            if (!tryDecide()) {
                dialog.getTextPanel().addPara("Checking that nobody else is salvaging this...");
                dialog.getOptionPanel().clearOptions();
                dialog.getOptionPanel().addOption("Leave", "leave");
                dialog.setOptionOnEscape("Leave", "leave");
            }
        }

        /** True once it's settled: handed on, or refused. */
        private boolean tryDecide() {
            if (decided) return true;
            String a = answers.remove(key);
            boolean late = System.nanoTime() - asked > ANSWER_NANOS;
            if (a == null && !late) return false;
            decided = true;
            if (a == null || "grant".equals(a)) {
                if (a == null) log.info("[MultiSector] no answer about " + key + " in time; salvaging anyway");
                // Ours either way: a late yes is let go of when this dialog closes, like any other.
                heldKey = key;
                held = target;
                InteractionDialogPlugin real = Wreckage.is(target) ? new Wreckage.Dialog()
                        : new RuleBasedInteractionDialogPluginImpl();
                dialog.setPlugin(real);
                real.init(dialog);
            } else {
                log.info("[MultiSector] " + a + " is salvaging " + key);
                dialog.getTextPanel().addPara(a + " is salvaging this right now. Come back once they're done.");
                dialog.getOptionPanel().clearOptions();
                dialog.getOptionPanel().addOption("Leave", "leave");
                dialog.setOptionOnEscape("Leave", "leave");
            }
            return true;
        }

        public void optionSelected(String optionText, Object optionData) {
            // Left before the answer came: if it's a yes, it mustn't keep the object ours.
            if (!decided) Session.get().releaseObject(key);
            dialog.dismiss();
        }

        public void optionMousedOver(String optionText, Object optionData) {
        }

        public void advance(float amount) {
            readAnswers(Session.get());
            tryDecide();
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
}
