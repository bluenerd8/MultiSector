package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.BattleCreationPlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.FleetMemberPickerListener;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import multisector.character.ShipJson;
import multisector.net.Session;
import multisector.ui.MultiSectorConfig;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Fighting together: another player's real ships join your battle.
 *
 * <p>The battle runs on one game, the <b>leader's</b>: whoever's fleet is in
 * it. Nobody is pulled in. While the leader is in a battle, every other
 * player is told about it ({@code fight}, every 2 s: what they're fighting,
 * and whether combat has begun). A player who wants to help clicks the fight
 * (the enemy fleet, or the leader's fleet) and gets the join dialog.
 *
 * <p><b>Joining only opens once combat has begun.</b> The battle's sides,
 * deployment and enemies are settled on the leader's game first, exactly as
 * in single player; nothing about the campaign battle is changed. A joiner
 * then comes in as reinforcements: they pick the ship they'll pilot (their
 * flagship by default) and send their fleet exactly (loadouts, officers,
 * their own character on the flagship, readiness, damage). The leader's
 * combat ({@link JointCombat.Leader}) spawns their combat-ready ships at the
 * leader's edge of the map as allies, in a fleet of their own that's never
 * part of the campaign battle (the game leaves ships like that out of the
 * battle's outcome). The leader gets a message in combat when someone joins.
 *
 * <p>During combat the joiner's game opens a live view of the battle, where
 * they fly their chosen ship; their other ships fight under the battle AI.
 * Without the view (config, or if it can't open) they get a report of each
 * ship and can order a retreat.
 *
 * <p><b>The ships stay the joiner's.</b> The leader can't pilot, recover or
 * keep them. When the engagement is over, what happened to each ship (lost,
 * hull damage, readiness) goes back to the joiner's game and is applied to
 * their real fleet. A joiner is never left without a ship: if every ship was
 * lost, the flagship limps away instead. Loot and experience go to the leader.
 *
 * Transient: added on each load.
 */
public final class JointBattle implements EveryFrameScript {

    /** A joiner can stop waiting if they hear nothing for this long. */
    private static final long QUIET_NANOS = 120_000_000_000L;
    /** How often the leader tells everyone about their battle. */
    private static final long FIGHT_NANOS = 2_000_000_000L;
    /** A battle we haven't heard about for this long is over (or its leader is gone). */
    private static final long FIGHT_STALE_NANOS = 7_000_000_000L;

    private static final Logger log = Global.getLogger(JointBattle.class);
    private static final Random random = new Random();

    public boolean isDone() {
        return false;
    }

    /** Battle dialogs pause the game; this must keep running in them. */
    public boolean runWhilePaused() {
        return true;
    }

    public void advance(float amount) {
        Session s = Session.get();
        boolean on = s.getState() == Session.State.HOSTING
                || (s.getState() == Session.State.CONNECTED && s.getMyId() >= 0);
        if (!on) {
            reset();
            return;
        }
        inCombat = false; // campaign frames don't run during combat
        handleMessages(s);
        try {
            leaderFrame(s);
        } catch (RuntimeException e) {
            log.warn("[MultiSector] joint battle (leader) failed this frame", e);
        }
        joinerFrame(s);
    }

    // ================================================================ messages

    /** From the campaign script, and from the combat plugins while a battle is being fought. */
    static void handleMessages(Session s) {
        Session.PlayerMessage m;
        for (int n = 0; n < 50 && (m = s.pollBattleMessage()) != null; n++) {
            try {
                JSONObject j = new JSONObject(m.json);
                String k = j.optString("k");
                if ("left".equals(k)) {
                    leaderLost(m.from);
                    joinerLost(m.from);
                } else if ("join".equals(k) || "retreat".equals(k) || "seen".equals(k)) {
                    toLeader(s, m, k, j);
                } else {
                    toJoiner(s, m, k, j);
                }
            } catch (Exception e) {
                log.warn("[MultiSector] bad joint battle message from " + m.fromName + ": " + e);
            }
        }
    }

    static JSONObject msg(String kind, int battle) {
        try {
            return new JSONObject().put("k", kind).put("b", battle);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A message about the battle we lead. */
    static JSONObject msg(String kind) {
        return msg(kind, battleId);
    }

    // ================================================================ leader

    /** A player whose ships are in our combat. */
    static final class Joiner {
        final int id;
        final String name;
        /** Their ships, in a fleet of their own: never part of the campaign battle. */
        final CampaignFleetAPI fleet;
        /** Our member id -> the joiner's real member id. */
        final Map<String, String> toOwner = new HashMap<String, String>();
        final Map<String, String> names = new HashMap<String, String>();
        /** The member they pilot (our id), or null. */
        String pilotPuppetId;
        volatile boolean retreat;
        /** Their member id -> how it was doing when we last looked (state; hull and readiness). */
        final Map<String, String> lastState = new HashMap<String, String>();
        final Map<String, float[]> lastHullCr = new HashMap<String, float[]>();

        Joiner(int id, String name, CampaignFleetAPI fleet) {
            this.id = id;
            this.name = name;
            this.fleet = fleet;
        }
    }

    private static BattleAPI battle;
    private static int battleId;
    private static final Map<Integer, Joiner> joiners = new LinkedHashMap<Integer, Joiner>();
    /** True while a battle is being fought (set by our combat plugin, cleared by any campaign frame). */
    static volatile boolean inCombat;
    private static long nextFight;
    /** Players we've told about our battle, to tell when it's over. */
    private static final Set<Integer> told = new HashSet<Integer>();

    static Collection<Joiner> joiners() {
        return joiners.values();
    }

    private static void leaderFrame(Session s) {
        if (!joiners.isEmpty()) finishJoiners(s); // combat ended without our combat plugin seeing it end
        JointCombat.Leader.active = null;
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        BattleAPI b = me == null ? null : me.getBattle();
        if (battle != null && (b != battle || battle.isDone())) {
            battle = null;
            fightOver(s);
        }
        if (b == null || b.isDone()) return;
        if (b != battle) {
            battle = b;
            battleId = 1 + random.nextInt(Integer.MAX_VALUE - 1);
            nextFight = 0;
        }
        tellFight(s, false);
    }

    /** Combat began: make sure there's a battle id even if no campaign frame saw the battle. */
    static void combatStarted() {
        inCombat = true;
        if (battleId == 0) battleId = 1 + random.nextInt(Integer.MAX_VALUE - 1);
        nextFight = 0;
    }

    /** Every other player hears about our battle, and whether they can join yet. */
    static void tellFight(Session s, boolean live) {
        long now = System.nanoTime();
        if (now - nextFight < 0) return;
        nextFight = now + FIGHT_NANOS;
        String text;
        try {
            text = msg("fight").put("live", live).put("enemy", enemyName()).toString();
        } catch (Exception e) {
            return;
        }
        for (Integer id : s.getRemoteFleets().keySet()) {
            if (id == s.getMyId() || !s.isPlayerHere(id)) continue;
            s.sendBattleMessage(id, text);
            told.add(id);
        }
    }

    /** The next {@link #tellFight} goes out straight away. */
    static void tellFightSoon() {
        nextFight = 0;
    }

    private static void fightOver(Session s) {
        String text = msg("fightover").toString();
        for (Integer id : told) if (s.isPlayerHere(id)) s.sendBattleMessage(id, text);
        told.clear();
    }

    private static String enemyName() {
        try {
            BattleAPI b = battle;
            if (b == null) {
                CampaignFleetAPI me = Global.getSector().getPlayerFleet();
                b = me == null ? null : me.getBattle();
            }
            List<CampaignFleetAPI> enemies = b == null ? null : b.getNonPlayerSide();
            if (enemies != null && !enemies.isEmpty()) return enemies.get(0).getName();
        } catch (RuntimeException ignored) {
            // a generic name
        }
        return "a hostile fleet";
    }

    private static void toLeader(Session s, Session.PlayerMessage m, String k, JSONObject j) throws Exception {
        int b = j.optInt("b");
        boolean live = battleId != 0 && (inCombat || (battle != null && !battle.isDone()));
        boolean ours = live && (b == battleId || b == 0); // 0: "whatever you're fighting"
        if ("seen".equals(k)) {
            if (!ours || joiners.containsKey(m.from)) return;
            if (inCombat) {
                JointCombat.Leader.message(m.fromName + " is getting ready to join your battle");
            } else {
                say(m.fromName + " wants to join your battle. They can come in once the fighting starts.");
            }
            return;
        }
        if ("retreat".equals(k)) {
            Joiner jn = joiners.get(m.from);
            if (ours && jn != null) {
                jn.retreat = true;
                JointCombat.Leader.message(m.fromName + " orders their ships to retreat");
            }
            return;
        }
        // join
        if (!ours) {
            s.sendBattleMessage(m.from, msg("nobattle", b).toString());
            return;
        }
        JointCombat.Leader lead = JointCombat.Leader.active;
        if (!inCombat || lead == null) {
            s.sendBattleMessage(m.from, msg("notyet").toString());
            return;
        }
        if (joiners.containsKey(m.from)) return;
        Joiner jn = new Joiner(m.from, m.fromName, ownFleet(m.from, m.fromName));
        if (!buildExact(jn, j.optString("fleet", ""), j.optString("pilot", ""))) {
            s.sendBattleMessage(m.from, msg("failed").toString());
            log.warn("[MultiSector] couldn't bring " + m.fromName + "'s fleet into the battle");
            return;
        }
        joiners.put(m.from, jn);
        s.sendBattleMessage(m.from, msg("joined").toString());
        lead.welcome(jn);
        log.info("[MultiSector] " + m.fromName + " joined the battle with " + jn.toOwner.size() + " ships");
    }

    /** A fleet for a joiner's ships, outside any location and any battle. */
    private static CampaignFleetAPI ownFleet(int id, String name) {
        CampaignFleetAPI puppet = PuppetManager.fleetOf(id);
        String faction = puppet != null && puppet.getFaction() != null ? puppet.getFaction().getId() : Factions.PLAYER;
        CampaignFleetAPI f = Global.getFactory().createEmptyFleet(faction, name + "'s fleet", true);
        f.setNoFactionInName(true);
        return f;
    }

    /** The joiner's exact fleet: ships, loadouts, officers, readiness, damage. */
    private static boolean buildExact(Joiner jn, String json, String pilot) {
        List<String> skipped = new ArrayList<String>();
        List<FleetMemberAPI> built = new ArrayList<FleetMemberAPI>();
        List<JSONObject> source = new ArrayList<JSONObject>();
        FleetMemberAPI flagship = null;
        try {
            JSONArray ships = new JSONObject(json).getJSONArray("ships");
            for (int i = 0; i < ships.length(); i++) {
                JSONObject sj = ships.getJSONObject(i);
                FleetMemberAPI m = ShipJson.buildShip(sj, skipped);
                if (m == null) continue;
                JSONObject captain = sj.optJSONObject("captain");
                if (captain != null) {
                    PersonAPI p = Global.getFactory().createPerson();
                    p.setFaction(jn.fleet.getFaction().getId());
                    ShipJson.applyPerson(p, p.getStats(), captain, null);
                    m.setCaptain(p);
                }
                built.add(m);
                source.add(sj);
                if (sj.optBoolean("flagship")) flagship = m;
            }
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't rebuild " + jn.name + "'s fleet for the battle", e);
            return false;
        }
        if (built.isEmpty()) return false;
        FleetDataAPI data = jn.fleet.getFleetData();
        for (int i = 0; i < built.size(); i++) {
            FleetMemberAPI m = built.get(i);
            JSONObject sj = source.get(i);
            data.addFleetMember(m);
            m.getRepairTracker().setCR((float) sj.optDouble("cr", m.getRepairTracker().getMaxCR()));
            m.getStatus().setHullFraction((float) sj.optDouble("hullFraction", 1));
            if (sj.optBoolean("mothballed")) m.getRepairTracker().setMothballed(true);
            m.setAlly(true);
            String owner = sj.optString("id", "");
            jn.toOwner.put(m.getId(), owner);
            jn.names.put(m.getId(), m.getShipName());
            if (!owner.isEmpty() && owner.equals(pilot)) jn.pilotPuppetId = m.getId();
        }
        if (flagship == null) flagship = built.get(0);
        data.setFlagship(flagship);
        if (flagship.getCaptain() != null && !flagship.getCaptain().isDefault()) jn.fleet.setCommander(flagship.getCaptain());
        data.setSyncNeeded();
        if (!skipped.isEmpty()) log.info("[MultiSector] " + jn.name + "'s fleet is missing " + skipped + " here (mods?)");
        return true;
    }

    /** The engagement is over: each joiner gets what happened to their ships. */
    static void finishJoiners(Session s) {
        for (Joiner jn : joiners.values()) {
            try {
                JSONArray lost = new JSONArray();
                JSONArray ships = new JSONArray();
                int back = 0;
                for (Map.Entry<String, String> e : jn.lastState.entrySet()) {
                    String owner = e.getKey();
                    String state = e.getValue();
                    if ("destroyed".equals(state) || "disabled".equals(state)) {
                        lost.put(owner);
                        continue;
                    }
                    float[] hc = jn.lastHullCr.get(owner);
                    if (hc == null) continue; // never deployed: as it was
                    ships.put(new JSONObject().put("id", owner).put("hull", hc[0]).put("cr", hc[1]));
                    back++;
                }
                s.sendBattleMessage(jn.id, msg("result").put("lost", lost).put("ships", ships).toString());
                log.info("[MultiSector] " + jn.name + "'s part in the battle: " + lost.length() + " ships lost, "
                        + back + " came back");
            } catch (Exception e) {
                log.warn("[MultiSector] couldn't report the battle to " + jn.name, e);
            }
        }
        joiners.clear();
    }

    private static void leaderLost(int id) {
        Joiner jn = joiners.remove(id);
        told.remove(id);
        JointCombat.Leader lead = JointCombat.Leader.active;
        if (jn != null && lead != null) {
            lead.retreat(jn); // nobody to report back to: their ships pull out
            JointCombat.Leader.message(jn.name + " left the game; their ships pull out");
        }
    }

    /** Into the leader's battle dialog. */
    private static void say(String text) {
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        InteractionDialogAPI d = ui == null ? null : ui.getCurrentInteractionDialog();
        if (d != null && d.getTextPanel() != null) d.getTextPanel().addPara(text);
        else if (ui != null) ui.addMessage(text);
    }

    /** How each of a joiner's ships is doing right now; also kept for the result. */
    static JSONArray status(CombatEngineAPI engine, Joiner jn) throws Exception {
        Map<String, ShipAPI> byId = new HashMap<String, ShipAPI>();
        for (ShipAPI ship : engine.getShips()) {
            FleetMemberAPI m = ship.getFleetMember();
            if (m == null || ship.getParentStation() != null) continue;
            ShipAPI had = byId.get(m.getId());
            if (had == null || (had.isHulk() && !ship.isHulk())) byId.put(m.getId(), ship);
        }
        Set<String> destroyed = new HashSet<String>();
        Set<String> disabled = new HashSet<String>();
        Set<String> retreated = new HashSet<String>();
        for (int owner = 0; owner <= 1; owner++) {
            CombatFleetManagerAPI fm = engine.getFleetManager(owner);
            if (fm == null) continue;
            for (FleetMemberAPI m : fm.getDestroyedCopy()) destroyed.add(m.getId());
            for (FleetMemberAPI m : fm.getDisabledCopy()) disabled.add(m.getId());
            for (FleetMemberAPI m : fm.getRetreatedCopy()) retreated.add(m.getId());
        }
        JSONArray a = new JSONArray();
        for (Map.Entry<String, String> e : jn.toOwner.entrySet()) {
            String id = e.getKey();
            String owner = e.getValue();
            ShipAPI ship = byId.get(id);
            String state;
            if (destroyed.contains(id)) state = "destroyed";
            else if (disabled.contains(id)) state = "disabled";
            else if (retreated.contains(id)) state = "retreated";
            else if (ship == null) state = "reserve";
            else if (ship.isHulk() || !ship.isAlive()) state = "disabled";
            else if (ship.isRetreating()) state = "retreating";
            else state = "fighting";
            if (!owner.isEmpty()) {
                jn.lastState.put(owner, state);
                if (ship != null && ship.isAlive() && !ship.isHulk()) {
                    jn.lastHullCr.put(owner, new float[]{ship.getHullLevel(), ship.getCurrentCR()});
                }
            }
            JSONObject o = new JSONObject().put("id", owner).put("name", jn.names.get(id)).put("state", state);
            if (ship != null && ship.isAlive()) o.put("hull", ship.getHullLevel()).put("cr", ship.getCurrentCR());
            a.put(o);
        }
        return a;
    }

    // ================================================================ joiner

    private enum Phase { NONE, ASKED, JOINING, IN, DONE }

    /** A battle another player is in, as they last told us. */
    static final class Fight {
        boolean live;
        String enemy = "a hostile fleet";
        int b;
        long heard;
    }

    /** Battles other players are in right now, by player. */
    private static final Map<Integer, Fight> fights = new HashMap<Integer, Fight>();

    private static Phase phase = Phase.NONE;
    private static int leader = -1;
    private static String leaderName = "";
    private static int joinBattle;
    private static String enemy = "";
    private static long lastHeard;
    private static JoinDialog open;
    /** The ship we'll pilot (our member id), or null for none. */
    private static String pilotId;
    /** Our member id -> what we last told the player about it. */
    private static final Map<String, String> shown = new HashMap<String, String>();
    /** A view about to open: the fleet standing in as "the enemy" for the game's battle start, and its set-up. */
    private static CampaignFleetAPI viewDummy;
    private static JointCombat.MirrorCreation viewCreation;

    /** The battle this player is in, if we've heard about it lately. */
    static Fight fightOf(int player) {
        Fight f = fights.get(player);
        if (f == null || System.nanoTime() - f.heard > FIGHT_STALE_NANOS) return null;
        return f;
    }

    /** Sets up joining this player's battle. False if we're already in one. */
    private static boolean prepare(int leaderId, String name, String enemyName) {
        if (phase == Phase.JOINING || phase == Phase.IN) return false;
        leader = leaderId;
        leaderName = name;
        Fight f = fightOf(leaderId);
        joinBattle = f != null ? f.b : 0; // 0: whatever they're fighting
        enemy = enemyName != null ? enemyName : f != null ? f.enemy : "a hostile fleet";
        lastHeard = System.nanoTime();
        shown.clear();
        return true;
    }

    /**
     * We ran into a fleet someone is already dealing with: offer to join
     * their battle. Switches the dialog over to the join dialog.
     */
    static void offerToJoin(InteractionDialogAPI dialog, int leaderId, String name, String enemyName) {
        if (!prepare(leaderId, name, enemyName)) {
            dialog.getTextPanel().addPara("You're already part of a battle.");
            return;
        }
        JoinDialog jd = new JoinDialog();
        dialog.setPlugin(jd);
        jd.init(dialog);
    }

    /** Clicking a player who's in a battle: the join dialog instead of a hail. Null if they aren't. */
    static InteractionDialogPlugin joinDialogFor(int leaderId) {
        Fight f = fightOf(leaderId);
        if (f == null || !prepare(leaderId, Session.get().playerName(leaderId), f.enemy)) return null;
        return new JoinDialog();
    }

    private static void toJoiner(Session s, Session.PlayerMessage m, String k, JSONObject j) throws Exception {
        int b = j.optInt("b");
        if ("fight".equals(k)) {
            Fight f = fights.get(m.from);
            if (f == null) fights.put(m.from, f = new Fight());
            f.live = j.optBoolean("live");
            f.enemy = j.optString("enemy", f.enemy);
            f.b = b;
            f.heard = System.nanoTime();
            if (m.from == leader && (phase == Phase.ASKED || phase == Phase.JOINING)) joinBattle = b;
            return;
        }
        if ("fightover".equals(k)) {
            fights.remove(m.from);
            return;
        }
        if (m.from != leader) return; // not ours
        if (joinBattle == 0 && "joined".equals(k)) joinBattle = b; // we asked to join "whatever they're fighting"
        if (b != joinBattle && joinBattle != 0) return;
        lastHeard = System.nanoTime();
        if ("joined".equals(k) && phase == Phase.JOINING) {
            phase = Phase.IN;
            say2("You've joined " + leaderName + "'s battle. Your ships arrive at their side of the battlefield."
                    + (MultiSectorConfig.isJointBattleView() ? " A view of it opens here and you fly your chosen ship;"
                    + " your other ships fight under the battle AI." : " Your ships fight under the battle AI."));
        } else if ("notyet".equals(k) && phase == Phase.JOINING) {
            phase = Phase.ASKED;
            say2(leaderName + " hasn't started the fighting yet. You can join as soon as it begins.");
        } else if (("nobattle".equals(k) || "failed".equals(k)) && (phase == Phase.JOINING || phase == Phase.ASKED)) {
            phase = Phase.DONE;
            say2("nobattle".equals(k) ? leaderName + " isn't in a battle (any more). Your fleet is as it was."
                    : "Your fleet couldn't be brought into " + leaderName + "'s battle. Your fleet is as it was.");
        } else if ("status".equals(k) && (phase == Phase.IN || phase == Phase.JOINING)) {
            phase = Phase.IN;
            showStatus(j.optJSONArray("ships"));
        } else if ("cstart".equals(k) && phase == Phase.IN) {
            JointCombat.pending.clear();
            openView((float) j.optDouble("w", 20000), (float) j.optDouble("h", 20000));
        } else if ("cship".equals(k) || "cgone".equals(k)) {
            if (JointCombat.view != null || viewCreation != null) JointCombat.pending.add(j);
        } else if ("cend".equals(k)) {
            JointCombat.Mirror v = JointCombat.view;
            if (v != null) v.end();
        } else if ("result".equals(k) && phase != Phase.NONE && phase != Phase.DONE) {
            phase = Phase.DONE;
            JointCombat.Mirror v = JointCombat.view;
            if (v != null) v.end();
            applyResult(j);
        }
        refresh();
    }

    /** The leader's combat is under way and we're in it: open our view of it, from the join dialog. */
    private static void openView(float w, float h) {
        if (!MultiSectorConfig.isJointBattleView() || open == null || open.dialog == null) return;
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (me == null) return;
        try {
            viewCreation = new JointCombat.MirrorCreation(leader, w, h);
            viewDummy = Global.getFactory().createEmptyFleet(Factions.NEUTRAL, leaderName + "'s battle", true);
            open.dialog.startBattle(new BattleCreationContext(me, FleetGoal.ATTACK, viewDummy, FleetGoal.ATTACK));
            say2("Opening the battle view...");
        } catch (RuntimeException e) {
            viewCreation = null;
            viewDummy = null;
            log.warn("[MultiSector] couldn't open the battle view", e);
            say2("Couldn't open a view of the battle; you'll get reports here instead.");
        }
    }

    /** The set-up for our battle view, once, if that's the battle starting. */
    static BattleCreationPlugin viewFor(SectorEntityToken opponent) {
        if (viewCreation == null || opponent != viewDummy) return null;
        BattleCreationPlugin c = viewCreation;
        viewCreation = null;
        viewDummy = null;
        return c;
    }

    private static void joinerLost(int id) {
        fights.remove(id);
        if (id != leader || phase == Phase.NONE || phase == Phase.DONE) return;
        boolean wasIn = phase == Phase.JOINING || phase == Phase.IN;
        phase = Phase.DONE;
        JointCombat.Mirror v = JointCombat.view;
        if (v != null) v.end();
        say2(leaderName + " left the game" + (wasIn ? " before the battle ended" : "") + ". Your fleet is as it was.");
        refresh();
    }

    private static void joinerFrame(Session s) {
        if (open == null) {
            if (phase == Phase.DONE || phase == Phase.ASKED) phase = Phase.NONE;
            return;
        }
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        InteractionDialogAPI d = ui == null ? null : ui.getCurrentInteractionDialog();
        if (d == null || d.getPlugin() != open) {
            // Closed some other way. A battle we joined still reports back; we apply it whenever it comes.
            open = null;
            if (phase == Phase.ASKED || phase == Phase.DONE) phase = Phase.NONE;
        }
    }

    /** Only changes: a ship deploying, retreating or going down, or its hull dropping by a fifth. */
    private static void showStatus(JSONArray ships) {
        for (int i = 0; ships != null && i < ships.length(); i++) {
            JSONObject o = ships.optJSONObject(i);
            if (o == null) continue;
            String state = o.optString("state");
            int hull = (int) Math.round(o.optDouble("hull", -1) * 100);
            String key = state + (hull >= 0 ? "|" + (hull / 20) : "");
            String old = shown.put(o.optString("id"), key);
            if (key.equals(old) || (old == null && "reserve".equals(state))) continue;
            String name = o.optString("name", "a ship");
            String text;
            if ("fighting".equals(state)) text = name + ": fighting, " + hull + "% hull";
            else if ("retreating".equals(state)) text = name + ": retreating, " + hull + "% hull";
            else if ("retreated".equals(state)) text = name + ": retreated safely";
            else if ("disabled".equals(state)) text = name + ": disabled";
            else if ("destroyed".equals(state)) text = name + ": destroyed";
            else text = name + ": held in reserve";
            say2(text);
        }
    }

    /** What the battle did to our ships, onto our real fleet. */
    private static void applyResult(JSONObject j) {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (me == null) return;
        FleetDataAPI data = me.getFleetData();
        Set<String> lost = new HashSet<String>();
        JSONArray la = j.optJSONArray("lost");
        for (int i = 0; la != null && i < la.length(); i++) lost.add(la.optString(i));
        Map<String, JSONObject> after = new HashMap<String, JSONObject>();
        JSONArray sa = j.optJSONArray("ships");
        for (int i = 0; sa != null && i < sa.length(); i++) {
            JSONObject o = sa.optJSONObject(i);
            if (o != null) after.put(o.optString("id"), o);
        }
        List<FleetMemberAPI> members = data.getMembersListCopy();
        int left = 0;
        for (FleetMemberAPI m : members) if (!m.isFighterWing() && !lost.contains(m.getId())) left++;
        FleetMemberAPI flag = me.getFlagship();
        boolean saved = false;
        if (left == 0 && flag != null) {
            lost.remove(flag.getId()); // never leave a player with no fleet
            saved = true;
        }
        List<String> lostNames = new ArrayList<String>();
        boolean flagLost = false;
        for (FleetMemberAPI m : members) {
            if (lost.contains(m.getId())) {
                lostNames.add(m.getShipName() + " (" + m.getHullSpec().getHullName() + ")");
                if (m == flag) flagLost = true;
                data.removeFleetMember(m);
                continue;
            }
            JSONObject o = after.get(m.getId());
            if (o != null) {
                m.getStatus().setHullFraction((float) Math.max(0.01, o.optDouble("hull", m.getStatus().getHullFraction())));
                m.getRepairTracker().setCR((float) o.optDouble("cr", m.getRepairTracker().getCR()));
            }
        }
        if (saved && flag != null) {
            flag.getStatus().setHullFraction(0.1f);
            flag.getRepairTracker().setCR(0f);
        }
        if (flagLost) {
            data.ensureHasFlagship();
            FleetMemberAPI nf = me.getFlagship();
            if (nf != null) nf.setCaptain(Global.getSector().getPlayerPerson());
        }
        data.setSyncNeeded();
        me.forceSync();
        say2(lostNames.isEmpty()
                ? "The battle is over. All your ships came back."
                : "The battle is over. Lost: " + join(lostNames) + ".");
        if (saved) say2("Every ship went down, but your flagship limped away, barely holding together.");
        if (flagLost && !saved && me.getFlagship() != null) {
            say2("Your flagship was lost; you've moved to " + me.getFlagship().getShipName() + ".");
        }
        log.info("[MultiSector] " + leaderName + "'s battle: lost " + lostNames.size() + " ships");
    }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(parts.get(i));
        }
        return b.toString();
    }

    private static void say2(String text) {
        if (open != null && open.dialog != null) {
            open.dialog.getTextPanel().addPara(text);
        } else {
            CampaignUIAPI ui = Global.getSector().getCampaignUI();
            if (ui != null) ui.addMessage(text);
        }
    }

    private static void refresh() {
        if (open != null) open.menu();
    }

    private static void reset() {
        forget();
    }

    /** A save is loading (or the session ended): nothing carries over. */
    public static void forget() {
        joiners.clear();
        told.clear();
        fights.clear();
        battle = null;
        battleId = 0;
        inCombat = false;
        JointCombat.Leader.active = null;
        phase = Phase.NONE;
        open = null;
        leader = -1;
        shown.clear();
        viewCreation = null;
        viewDummy = null;
        JointCombat.pending.clear();
    }

    /** The joiner's dialog: waiting for the fight to start, joining, watching, and the outcome. */
    private static final class JoinDialog implements InteractionDialogPlugin {
        InteractionDialogAPI dialog;
        private boolean quietOffered;
        /** What we last showed about the leader's battle: 0 over/unknown, 1 not fighting yet, 2 fighting. */
        private int shownState = -1;

        public void init(InteractionDialogAPI dialog) {
            this.dialog = dialog;
            open = this;
            phase = Phase.ASKED;
            CampaignFleetAPI me = Global.getSector().getPlayerFleet();
            pilotId = me != null && me.getFlagship() != null ? me.getFlagship().getId() : null;
            dialog.getTextPanel().addPara(leaderName + " is in a battle with " + enemy + ".");
            dialog.getTextPanel().addPara("You can bring your fleet in once the fighting has begun: your ships "
                    + "arrive as reinforcements on " + leaderName + "'s side, on their game. What happens to them is real: "
                    + "ships lost are lost, and damage stays."
                    + (MultiSectorConfig.isJointBattleView()
                    ? " You fly one ship yourself in a live view of the battle; the rest fight under the battle AI."
                    : " They fight under the battle AI, and you can order them to retreat at any time."));
            Session.get().sendBattleMessage(leader, msg("seen", joinBattle).toString());
            shownState = fightState();
            dialog.getTextPanel().addPara(shownState == 2 ? "The fighting has begun. You can join now."
                    : shownState == 1 ? "They haven't started fighting yet. Wait here: you can join as soon as they do."
                    : "You haven't heard from " + leaderName + " about it yet. Wait a moment.");
            menu();
        }

        /** 0 over/unknown, 1 not fighting yet, 2 fighting. */
        private int fightState() {
            Fight f = fightOf(leader);
            return f == null ? 0 : f.live ? 2 : 1;
        }

        void menu() {
            OptionPanelAPI op = dialog.getOptionPanel();
            op.clearOptions();
            if (phase == Phase.ASKED) {
                if (fightState() == 2) {
                    op.addOption("Join " + leaderName + "'s battle", "join");
                    if (MultiSectorConfig.isJointBattleView()) op.addOption("Ship you'll pilot: " + pilotName(), "pick");
                }
                op.addOption("Leave", "out");
                dialog.setOptionOnEscape("Leave", "out");
            } else if (phase == Phase.JOINING || phase == Phase.IN) {
                op.addOption("Order your ships to retreat", "retreat");
                if (quietOffered) op.addOption("Stop waiting (anything still to come is applied when it arrives)", "leave");
            } else {
                op.addOption("Leave", "leave");
                dialog.setOptionOnEscape("Leave", "leave");
            }
        }

        private String pilotName() {
            CampaignFleetAPI me = Global.getSector().getPlayerFleet();
            if (me == null || pilotId == null) return "none";
            for (FleetMemberAPI m : me.getFleetData().getMembersListCopy()) {
                if (m.getId().equals(pilotId)) return m.getShipName() + " (" + m.getHullSpec().getHullName() + ")";
            }
            return "none";
        }

        public void optionSelected(String optionText, Object optionData) {
            Session s = Session.get();
            String id = String.valueOf(optionData);
            if ("join".equals(id) && phase == Phase.ASKED) {
                CampaignFleetAPI me = Global.getSector().getPlayerFleet();
                try {
                    s.sendBattleMessage(leader, msg("join", joinBattle)
                            .put("fleet", FleetLocks.exactCopy(me).toString())
                            .put("pilot", MultiSectorConfig.isJointBattleView() && pilotId != null ? pilotId : "")
                            .toString());
                    phase = Phase.JOINING;
                    dialog.getTextPanel().addPara("Your fleet moves in to support " + leaderName + "...");
                } catch (Exception e) {
                    dialog.getTextPanel().addPara("Couldn't send your fleet's details.");
                    phase = Phase.DONE;
                }
            } else if ("pick".equals(id) && phase == Phase.ASKED) {
                pickPilot();
                return;
            } else if ("out".equals(id)) {
                phase = Phase.NONE;
                open = null;
                dialog.dismiss();
                return;
            } else if ("retreat".equals(id)) {
                s.sendBattleMessage(leader, msg("retreat", joinBattle).toString());
                dialog.getTextPanel().addPara("You order your ships to pull out.");
            } else if ("leave".equals(id)) {
                if (phase == Phase.DONE) phase = Phase.NONE;
                open = null;
                dialog.dismiss();
                return;
            }
            menu();
        }

        private void pickPilot() {
            CampaignFleetAPI me = Global.getSector().getPlayerFleet();
            if (me == null) return;
            List<FleetMemberAPI> pool = new ArrayList<FleetMemberAPI>();
            for (FleetMemberAPI m : me.getFleetData().getMembersListCopy()) {
                if (!m.isFighterWing() && !m.getRepairTracker().isMothballed()) pool.add(m);
            }
            dialog.showFleetMemberPickerDialog("The ship you'll pilot in the battle", "Pilot this one", "Cancel",
                    3, 7, 58f, true, false, pool, new FleetMemberPickerListener() {
                        public void pickedFleetMembers(List<FleetMemberAPI> members) {
                            if (members != null && !members.isEmpty()) pilotId = members.get(0).getId();
                            menu();
                        }

                        public void cancelledFleetMemberPicking() {
                            menu();
                        }
                    });
        }

        public void optionMousedOver(String optionText, Object optionData) {
        }

        public void advance(float amount) {
            if (phase == Phase.ASKED) {
                int st = fightState();
                if (st != shownState) {
                    if (st == 2) dialog.getTextPanel().addPara(leaderName + " has started fighting. You can join now.");
                    else if (st == 1 && shownState == 2) dialog.getTextPanel().addPara("That engagement is over; "
                            + leaderName + " is deciding what to do next. You can join if they fight again.");
                    else if (st == 1) dialog.getTextPanel().addPara(leaderName + " hasn't started fighting yet. Wait here.");
                    else dialog.getTextPanel().addPara(leaderName + "'s battle is over.");
                    shownState = st;
                    menu();
                }
            }
            if (!quietOffered && (phase == Phase.JOINING || phase == Phase.IN)
                    && System.nanoTime() - lastHeard > QUIET_NANOS) {
                quietOffered = true;
                menu();
            }
        }

        /** Back from the battle view. The result comes from the leader, not from this. */
        public void backFromEngagement(EngagementResultAPI battleResult) {
            dialog.getTextPanel().addPara(phase == Phase.DONE
                    ? "You're back from the battle."
                    : "You're back from the battle view; waiting for " + leaderName + "'s battle to wrap up...");
            menu();
        }

        public Object getContext() {
            return null;
        }

        public Map<String, MemoryAPI> getMemoryMap() {
            return new HashMap<String, MemoryAPI>();
        }
    }
}
