package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.util.Misc;
import multisector.character.CharacterExporter;
import multisector.net.Session;
import multisector.ui.MultiSectorConfig;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * One shared world, kept in step across games:
 *
 * <ul>
 * <li><b>Waiting for the host</b> (friends, {@code waitForHost}): a world we
 *     joined, or tried to, stays paused while we're not connected, and keeps
 *     trying to join every few seconds. So loading before the host, or the
 *     host's game restarting, never lets this copy run off on its own. F8
 *     and Leave stops waiting.</li>
 * <li><b>The host ends the session</b> (friends, {@code endWhenHostLeaves}):
 *     they stopped hosting, or their world closed. Our character is saved
 *     (exported, for the launcher next time) and the world saved, then we go
 *     back to the main menu.</li>
 * <li><b>Saving together</b> (friends, {@code saveWithHost}): whenever the
 *     host saves, we save too, and export our character.</li>
 * <li><b>Everyone's back</b> (host, {@code waitForAllPlayers}): the players
 *     at the host's last save are remembered in it, and after loading the
 *     world waits for them to join.</li>
 * </ul>
 *
 * Saves and the exit run from campaign input handling, outside the game's
 * own update, like {@link HostSaver}. Transient: added on each load.
 */
public final class SessionGuard implements EveryFrameScript, CampaignInputListener {

    private static final Logger log = Global.getLogger(SessionGuard.class);
    /** Saved in the host's save: who was playing, so a load can wait for them. */
    public static final String PLAYERS_KEY = "$multisector_lastPlayers";
    private static final long RETRY_NANOS = 6_000_000_000L;
    private static final long REMIND_NANOS = 20_000_000_000L;
    /** Longest the host's world waits for last session's players. */
    private static final long WAIT_ALL_NANOS = 10L * 60L * 1_000_000_000L;

    /** The world we joined this load (we keep it in step), and whether the player let it go. */
    private static boolean joinedHere;
    private static boolean letGo;
    private static boolean saveWanted;
    private static boolean exitWanted;
    private static boolean endShown;

    /** The world (seed) the running session belongs to, or null. Kept across loads, unlike the rest. */
    private static String sessionWorld;

    /**
     * This load of the world: new each time a save loads. The host writes it
     * into every save it makes, so a friend's copy says which load of the
     * host's world it came from, and the host tells friends its current one
     * as they join. A mismatch means the friend's copy is from another load
     * (the host loaded a save since): it's time for a fresh copy.
     */
    public static final String EPOCH_KEY = "$multisector_epoch";
    private static String epoch = newEpoch();
    /** Our copy isn't from the host's current load of the world. */
    private static boolean staleCopy;
    private static boolean staleShown;
    /** Leave for the main menu without saving (the launcher brings a fresh copy). */
    private static boolean exitNoSave;
    /** Asks the MultiSector launcher (if open) to download the host's world again. */
    public static final String REJOIN_FILE = multisector.net.NetConfig.REJOIN_FILE;

    private static String newEpoch() {
        return Long.toHexString(new java.util.Random().nextLong() & Long.MAX_VALUE);
    }

    /**
     * First thing on every load: a session from another world ends, and this
     * load gets its own id. A host who loaded its world again tells friends,
     * whose copies are now ahead of it: they're offered a fresh one.
     */
    public static void worldLoaded() {
        checkWorld();
        epoch = newEpoch();
        Session s = Session.get();
        s.setWorldEpoch(epoch);
        if (s.getState() == Session.State.HOSTING) s.sendHostOrder("{\"k\":\"epoch\",\"id\":\"" + epoch + "\"}");
    }

    private long nextTry;
    private long nextRemind;
    private boolean waitingShown;

    /** A save loaded: a new world, nothing joined yet. */
    public static void forget() {
        joinedHere = false;
        letGo = false;
        saveWanted = false;
        exitWanted = false;
        endShown = false;
        staleCopy = false;
        staleShown = false;
        exitNoSave = false;
    }

    /**
     * A save just loaded. If a session is still running from another world
     * (the player loaded a different save while hosting or connected), it
     * ends: other players must never be attached to a world that isn't theirs.
     */
    public static void checkWorld() {
        Session s = Session.get();
        String seed = Global.getSector().getSeedString();
        if (s.isActive() && sessionWorld != null && !sessionWorld.equals(seed)) {
            log.info("[MultiSector] loaded another world (" + seed + ") during a session for " + sessionWorld + "; leaving it");
            s.stop();
            s.notice("You loaded another world, so you left the multiplayer session.");
        }
        if (!s.isActive()) sessionWorld = null;
    }

    /** The player left or stopped the session themselves (F8): don't hold the world for it. */
    public static void letGo() {
        letGo = true;
    }

    /** Holding this world paused until we're back with the host (for the Multiplayer window). */
    public static boolean isWaiting() {
        Session s = Session.get();
        return joinedHere && !letGo && MultiSectorConfig.isWaitForHost() && s.getState() != Session.State.CONNECTED
                && s.getState() != Session.State.HOSTING && !(s.hostEnded() && MultiSectorConfig.isEndWhenHostLeaves());
    }

    public boolean isDone() {
        return false;
    }

    public boolean runWhilePaused() {
        return true;
    }

    public void advance(float amount) {
        Session s = Session.get();
        Session.State st = s.getState();
        if (st == Session.State.IDLE && !joinedHere) sessionWorld = null;
        else if (sessionWorld == null && st != Session.State.IDLE) sessionWorld = Global.getSector().getSeedString();
        if (st == Session.State.CONNECTING || st == Session.State.CONNECTED) {
            joinedHere = true;
            letGo = false;
        }
        if (st == Session.State.CONNECTED) waitingShown = false; // a later wait says so again
        if (st == Session.State.HOSTING) {
            joinedHere = false;
            return;
        }
        if (st == Session.State.CONNECTED) {
            String order;
            while ((order = s.pollHostOrder()) != null) hostOrder(s, order);
            // The host's world closed while their hosting runs on (back at their main menu): we're done too.
            if (s.hostEnded() && MultiSectorConfig.isEndWhenHostLeaves()) s.stop();
            if (staleCopy && !staleShown) showStale();
            return;
        }
        String order;
        while ((order = s.pollHostOrder()) != null) hostOrder(s, order); // "end" can arrive just before the drop
        if (!joinedHere || letGo) return;

        if (s.hostEnded()) {
            if (MultiSectorConfig.isEndWhenHostLeaves() && !endShown) showEnded(s);
            if (MultiSectorConfig.isEndWhenHostLeaves()) return;
        }
        if (!MultiSectorConfig.isWaitForHost() || st != Session.State.IDLE) return;
        // Not connected to the world we joined: it waits, and we keep trying.
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (ClockSync.isMapView() && !Global.getSector().isPaused()) Global.getSector().setPaused(true);
        long now = System.nanoTime();
        String[] to = s.getLastJoin();
        if (to == null) return;
        if (!waitingShown || now - nextRemind > 0) {
            waitingShown = true;
            nextRemind = now + REMIND_NANOS;
            if (ui != null) {
                ui.addMessage("MultiSector: waiting for the host's world (" + to[0] + "), so this one stays paused. "
                        + MultiSectorConfig.getHotkeyName() + " to stop waiting.", Misc.getHighlightColor());
            }
        }
        if (now - nextTry < 0) return;
        nextTry = now + RETRY_NANOS;
        try {
            s.clearHostEnded();
            s.join(to[0], parse(to[1]), to[2]);
        } catch (RuntimeException e) {
            log.info("[MultiSector] couldn't retry joining: " + e);
        }
    }

    private static int parse(String port) {
        try {
            return Integer.parseInt(port);
        } catch (NumberFormatException e) {
            return MultiSectorConfig.getPort();
        }
    }

    private void hostOrder(Session s, String json) {
        try {
            JSONObject o = new JSONObject(json);
            String k = o.optString("k");
            if ("save".equals(k) && MultiSectorConfig.isSaveWithHost()) {
                saveWanted = true;
            } else if ("epoch".equals(k)) {
                String ours = Global.getSector().getMemoryWithoutUpdate().getString(EPOCH_KEY);
                String theirs = o.optString("id", "");
                // A copy from before world ids (an older save) can't tell: left as it is.
                boolean stale = ours != null && !ours.isEmpty() && !theirs.isEmpty() && !ours.equals(theirs);
                if (stale && !staleCopy) {
                    staleShown = false;
                    log.info("[MultiSector] our copy is from load " + ours + " of the host's world; theirs is now " + theirs);
                }
                staleCopy = stale;
            }
        } catch (Exception e) {
            log.info("[MultiSector] bad order from the host: " + e);
        }
    }

    // ================================================================ host

    /** Host, before every save: tell friends to save too, and remember who's playing. */
    public static void beforeSave() {
        Session s = Session.get();
        // Only a hosting world's saves carry its load id: a friend's copy keeps the host's, whatever it saves.
        if (s.getState() == Session.State.HOSTING) Global.getSector().getMemoryWithoutUpdate().set(EPOCH_KEY, epoch);
        if (s.getState() == Session.State.HOSTING) {
            JSONArray names = new JSONArray();
            for (Session.Player p : s.getPlayers()) if (p.id != s.getMyId()) names.put(p.name);
            Global.getSector().getMemoryWithoutUpdate().set(PLAYERS_KEY, names.toString());
            s.sendHostOrder("{\"k\":\"save\"}");
        } else if (s.getState() == Session.State.CONNECTED) {
            exportCharacter("saving");
        }
    }

    /** Host, just after starting to host this world: optionally wait for last session's players. */
    public static void waitForLastPlayers() {
        if (!MultiSectorConfig.isWaitForAllPlayers()) return;
        Session s = Session.get();
        if (s.getState() != Session.State.HOSTING) return;
        String text = Global.getSector().getMemoryWithoutUpdate().getString(PLAYERS_KEY);
        if (text == null || text.isEmpty()) return;
        StringBuilder who = new StringBuilder();
        try {
            JSONArray names = new JSONArray(text);
            for (int i = 0; i < names.length(); i++) {
                String n = names.optString(i, "");
                if (n.isEmpty()) continue;
                s.getSharedClock().hold(n, WAIT_ALL_NANOS, System.nanoTime());
                if (who.length() > 0) who.append(", ");
                who.append(n);
            }
        } catch (Exception e) {
            return;
        }
        if (who.length() > 0) {
            s.notice("Waiting for " + who + " (playing at your last save) to join. Unpause to start without them.");
        }
    }

    // ================================================================ friends: saving and leaving

    public int getListenerInputPriority() {
        return 0;
    }

    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        if (!ClockSync.isMapView()) return;
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (exitWanted) {
            exitWanted = false;
            exportCharacter("leaving");
            boolean saved = false;
            try {
                if (!exitNoSave) saved = ui.cmdSave();
            } catch (RuntimeException e) {
                log.warn("[MultiSector] save before leaving failed", e);
            }
            log.info("[MultiSector] leaving for the main menu (" + (exitNoSave ? "for a fresh copy" : "the host ended the session")
                    + "); saved: " + saved);
            ui.cmdExitWithoutSaving();
            return;
        }
        if (saveWanted && Session.get().getState() == Session.State.CONNECTED) {
            saveWanted = false;
            boolean saved = false;
            try {
                saved = ui.cmdSave(); // our beforeGameSave exports the character too
            } catch (RuntimeException e) {
                log.warn("[MultiSector] save with the host failed", e);
            }
            log.info("[MultiSector] saved along with the host: " + saved);
            if (saved) Session.get().notice("Saved along with the host (your character too).");
        }
    }

    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {
    }

    public void processCampaignInputPostCore(List<InputEventAPI> events) {
    }

    /** Our character as it is now in this world, for the character chooser next time we join it. */
    static void exportCharacter(String why) {
        try {
            String summary = CharacterExporter.export(CharacterExporter.worldFile(Global.getSector().getSeedString()));
            log.info("[MultiSector] exported our character (" + why + "): " + summary);
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't export our character (" + why + ")", e);
        }
    }

    /** "The host ended the session": back to the main menu (or, if asked, stay offline). */
    private static void showEnded(Session s) {
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (ui == null || ui.isShowingDialog() || ui.isShowingMenu() || !ClockSync.isMapView()) return;
        endShown = true;
        Global.getSector().setPaused(true);
        ui.showInteractionDialog(new Ended(), Global.getSector().getPlayerFleet());
    }

    /** Tells the MultiSector launcher to download the host's world again (waiting for them first if asked). */
    static void askLauncher(boolean waitForHost) {
        String[] to = Session.get().getLastJoin();
        if (to == null) return;
        try {
            String address = to[0] + (parse(to[1]) != multisector.net.NetConfig.DEFAULT_PORT ? ":" + to[1] : "");
            JSONObject j = new JSONObject().put("address", address).put("name", to[2])
                    .put("wait", waitForHost).put("at", System.currentTimeMillis());
            Global.getSettings().writeTextFileToCommon(REJOIN_FILE, j.toString());
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't ask the launcher for a fresh copy", e);
        }
    }

    /** "The host's world has moved on": get a fresh copy, or play on in this one. */
    private static void showStale() {
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (ui == null || ui.isShowingDialog() || ui.isShowingMenu() || !ClockSync.isMapView()) return;
        staleShown = true;
        ui.showInteractionDialog(new Stale(), Global.getSector().getPlayerFleet());
    }

    private static final class Stale implements InteractionDialogPlugin {
        private InteractionDialogAPI dialog;

        public void init(InteractionDialogAPI dialog) {
            this.dialog = dialog;
            dialog.getTextPanel().addPara("This copy of the world isn't the host's any more: they've loaded their save "
                    + "since it was made (or it's from an earlier session). Fleets, markets and events won't match.");
            dialog.getTextPanel().addPara("A fresh copy takes a moment: your character is kept, you go back to the "
                    + "main menu, and the MultiSector launcher downloads the host's world. Then click Continue.");
            dialog.getTextPanel().addPara("The launcher needs to be open. If it isn't, open it and click Join and launch.",
                    Misc.getGrayColor());
            dialog.getOptionPanel().addOption("Get a fresh copy", "fresh");
            dialog.getOptionPanel().addOption("Play on in this copy", "stay");
            dialog.setOptionOnEscape("Play on in this copy", "stay");
        }

        public void optionSelected(String optionText, Object optionData) {
            if ("fresh".equals(optionData)) {
                askLauncher(false);
                exitNoSave = true; // this copy is the old one: nothing in it to keep but the character
                exitWanted = true;
                Session.get().stop();
                letGo(); // leaving on purpose: don't hold this world for the host
            } else {
                Session.get().notice("Playing on in an old copy of the host's world. F8 has Get a fresh copy.");
            }
            dialog.dismiss();
        }

        public void optionMousedOver(String optionText, Object optionData) {
        }

        public void advance(float amount) {
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

    /** F8: leave and get a fresh copy of the host's world, any time. */
    public static void freshCopyNow() {
        askLauncher(false);
        exitNoSave = true;
        exitWanted = true;
        Session.get().stop();
        letGo();
    }

    /** The host's world has moved on from our copy (for the Multiplayer window). */
    public static boolean isStaleCopy() {
        return staleCopy;
    }

    private static final class Ended implements InteractionDialogPlugin {
        private InteractionDialogAPI dialog;

        public void init(InteractionDialogAPI dialog) {
            this.dialog = dialog;
            dialog.getTextPanel().addPara("The host ended the session, so this world has nobody to keep it in step.");
            dialog.getTextPanel().addPara("Your character and this world are saved now. Your character comes along "
                    + "when you next join.");
            dialog.getTextPanel().addPara("If the MultiSector launcher is open, it waits for the host to be back and "
                    + "downloads their world again. Then click Continue.", Misc.getGrayColor());
            dialog.getOptionPanel().addOption("Return to the main menu", "exit");
            dialog.getOptionPanel().addOption("Stay here, offline", "stay");
            dialog.setOptionOnEscape("Return to the main menu", "exit");
        }

        public void optionSelected(String optionText, Object optionData) {
            if ("stay".equals(optionData)) {
                letGo();
                Session.get().notice("Playing offline. This world no longer follows the host's.");
            } else {
                askLauncher(true); // it waits for the host to be back, then fetches a fresh copy
                exitWanted = true; // carried out from the map, outside this dialog
            }
            dialog.dismiss();
        }

        public void optionMousedOver(String optionText, Object optionData) {
        }

        public void advance(float amount) {
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
