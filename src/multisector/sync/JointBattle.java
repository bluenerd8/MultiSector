package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.BattleAPI.BattleSide;
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
import com.fs.starfarer.api.impl.combat.BattleCreationPluginImpl;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
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
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Fighting together: another player's real ships join your battle.
 *
 * <p>The battle runs on one game, the <b>leader's</b>: whoever's fleet is in
 * it. While the leader is in a battle dialog, every other player whose fleet
 * is within {@link #JOIN_RANGE} is asked to join; a player who runs into a
 * fleet someone is already fighting can ask to join too. A player who joins
 * (a <b>joiner</b>) picks the ship they'll pilot (their flagship by default)
 * and sends their fleet exactly: loadouts, officers, their own character on
 * the flagship, readiness, damage. The leader's game puts it into the battle
 * on the leader's side.
 *
 * <p>During combat ({@link JointCombat}) the joiner's game opens a live view
 * of the battle, where they fly their chosen ship; their other ships fight
 * under the battle AI. Without the view (config, or if it can't open) they
 * get a report of each ship and can order a retreat.
 *
 * <p><b>The ships stay the joiner's.</b> The leader can't pilot, recover or
 * keep them. When the battle is over, what happened to each ship (lost, hull
 * damage, readiness) goes back to the joiner's game and is applied to their
 * real fleet. A joiner is never left without a ship: if every ship was lost,
 * the flagship limps away instead.
 *
 * <p>Both sides wait in a dialog meanwhile, so neither fleet moves. Loot and
 * experience go to the leader, as with any allied fleet.
 *
 * Transient: added on each load.
 */
public final class JointBattle implements EveryFrameScript {

    /** Players this close to the battle are asked to join. */
    static final float JOIN_RANGE = 2000f;
    /** A joiner can stop waiting if they hear nothing for this long. */
    private static final long QUIET_NANOS = 120_000_000_000L;

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
                } else if ("join".equals(k) || "decline".equals(k) || "retreat".equals(k)) {
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

    /** A player whose ships are in our battle. */
    static final class Joiner {
        final int id;
        final String name;
        final CampaignFleetAPI fleet;
        /** The puppet's member id -> the joiner's real member id. */
        final Map<String, String> toOwner = new HashMap<String, String>();
        final Map<String, String> names = new HashMap<String, String>();
        /** The puppet member they pilot, or null. */
        String pilotPuppetId;
        volatile boolean retreat;

        Joiner(int id, String name, CampaignFleetAPI fleet) {
            this.id = id;
            this.name = name;
            this.fleet = fleet;
        }
    }

    private static BattleAPI battle;
    private static int battleId;
    private static final Set<Integer> asked = new HashSet<Integer>();
    private static final Map<Integer, Joiner> joiners = new HashMap<Integer, Joiner>();
    /** True while a battle is being fought: nobody can join a fight already under way. */
    static volatile boolean inCombat;

    static Collection<Joiner> joiners() {
        return joiners.values();
    }

    private static void leaderFrame(Session s) {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        BattleAPI b = me == null ? null : me.getBattle();
        if (battle != null && (b != battle || battle.isDone())) finish(s);
        if (b == null || b.isDone()) return;
        if (b != battle) {
            battle = b;
            battleId = 1 + random.nextInt(Integer.MAX_VALUE - 1);
            asked.clear();
            joiners.clear();
        }
        if (ClockSync.isMapView()) return; // asked from the battle's dialog
        List<CampaignFleetAPI> enemies = b.getNonPlayerSide();
        if (enemies == null || enemies.isEmpty()) return;
        for (Integer id : s.getRemoteFleets().keySet()) {
            if (asked.contains(id) || joiners.containsKey(id) || !s.isPlayerHere(id)) continue;
            CampaignFleetAPI f = PuppetManager.fleetOf(id);
            if (f == null || f.getContainingLocation() != me.getContainingLocation() || f.getBattle() != null) continue;
            if (distance(f, me) > JOIN_RANGE) continue;
            asked.add(id);
            try {
                s.sendBattleMessage(id, msg("ask").put("enemy", enemies.get(0).getName()).toString());
            } catch (Exception e) {
                continue;
            }
            say(s.playerName(id) + "'s fleet is close by: asking them to join you. Give them a moment before you engage.");
            log.info("[MultiSector] asking " + s.playerName(id) + " to join the battle with " + enemies.get(0).getName());
        }
    }

    private static void toLeader(Session s, Session.PlayerMessage m, String k, JSONObject j) throws Exception {
        int b = j.optInt("b");
        boolean live = battle != null && !battle.isDone();
        boolean ours = live && (b == battleId || (b == 0 && "join".equals(k))); // 0: "whatever you're fighting"
        if ("decline".equals(k)) {
            if (ours) say(m.fromName + (j.optBoolean("busy") ? " is busy and can't join." : " stays out of the battle."));
            return;
        }
        if ("retreat".equals(k)) {
            Joiner jn = joiners.get(m.from);
            if (ours && jn != null) {
                jn.retreat = true;
                say(m.fromName + " orders their ships to retreat.");
            }
            return;
        }
        // join
        if (!ours || inCombat || joiners.containsKey(m.from)) {
            s.sendBattleMessage(m.from, msg(live ? "late" : "nobattle", b).toString());
            return;
        }
        CampaignFleetAPI f = PuppetManager.holdForBattle(m.from);
        if (f == null) {
            s.sendBattleMessage(m.from, msg("late", b).toString());
            return;
        }
        Joiner jn = new Joiner(m.from, m.fromName, f);
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        BattleSide side = battle.getSideOne().contains(me) ? BattleSide.ONE : BattleSide.TWO;
        if (!buildExact(jn, j.optString("fleet", ""), j.optString("pilot", "")) || !battle.join(f, side)) {
            PuppetManager.releaseBattle(m.from);
            s.sendBattleMessage(m.from, msg("late", b).toString());
            log.warn("[MultiSector] couldn't add " + m.fromName + "'s fleet to the battle");
            return;
        }
        joiners.put(m.from, jn);
        asked.add(m.from);
        s.sendBattleMessage(m.from, msg("joined").toString());
        say(m.fromName + "'s fleet joins the battle on your side (" + jn.toOwner.size() + " ships)."
                + (jn.pilotPuppetId != null ? " They'll pilot " + jn.names.get(jn.pilotPuppetId) + " themselves." : ""));
        log.info("[MultiSector] " + m.fromName + " joined the battle with " + jn.toOwner.size() + " ships");
    }

    /** The joiner's exact fleet, into their puppet: ships, loadouts, officers, readiness, damage. */
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
        for (FleetMemberAPI old : data.getMembersListCopy()) data.removeFleetMember(old);
        for (int i = 0; i < built.size(); i++) {
            FleetMemberAPI m = built.get(i);
            JSONObject sj = source.get(i);
            data.addFleetMember(m);
            m.getRepairTracker().setCR((float) sj.optDouble("cr", m.getRepairTracker().getMaxCR()));
            m.getStatus().setHullFraction((float) sj.optDouble("hullFraction", 1));
            if (sj.optBoolean("mothballed")) m.getRepairTracker().setMothballed(true);
            String owner = sj.optString("id", "");
            jn.toOwner.put(m.getId(), owner);
            jn.names.put(m.getId(), m.getShipName());
            if (!owner.isEmpty() && owner.equals(pilot)) jn.pilotPuppetId = m.getId();
        }
        if (flagship == null) flagship = built.get(0);
        data.setFlagship(flagship);
        if (flagship.getCaptain() != null && !flagship.getCaptain().isDefault()) jn.fleet.setCommander(flagship.getCaptain());
        data.setSyncNeeded();
        jn.fleet.forceSync();
        if (!skipped.isEmpty()) log.info("[MultiSector] " + jn.name + "'s fleet is missing " + skipped + " here (mods?)");
        return true;
    }

    /** The battle is over: each joiner gets what happened to their ships. */
    private static void finish(Session s) {
        for (Joiner jn : joiners.values()) {
            try {
                Set<String> survivors = new HashSet<String>();
                JSONArray ships = new JSONArray();
                if (jn.fleet.getFleetData() != null) {
                    for (FleetMemberAPI m : jn.fleet.getFleetData().getMembersListCopy()) {
                        String owner = jn.toOwner.get(m.getId());
                        if (owner == null || owner.isEmpty()) continue;
                        survivors.add(owner);
                        ships.put(new JSONObject().put("id", owner).put("hull", m.getStatus().getHullFraction())
                                .put("cr", m.getRepairTracker().getCR()));
                    }
                }
                JSONArray lost = new JSONArray();
                for (String owner : jn.toOwner.values()) {
                    if (!owner.isEmpty() && !survivors.contains(owner)) lost.put(owner);
                }
                s.sendBattleMessage(jn.id, msg("result").put("lost", lost).put("ships", ships).toString());
                log.info("[MultiSector] " + jn.name + "'s part in the battle: " + lost.length() + " ships lost, "
                        + survivors.size() + " came back");
            } catch (Exception e) {
                log.warn("[MultiSector] couldn't report the battle to " + jn.name, e);
            }
            PuppetManager.releaseBattle(jn.id);
        }
        joiners.clear();
        asked.clear();
        battle = null;
    }

    private static void leaderLost(int id) {
        Joiner jn = joiners.remove(id);
        if (jn != null) PuppetManager.releaseBattle(id);
    }

    /** Only while a joint battle is set up: then our battle creation adds the live side. */
    static boolean hasJoiners() {
        return !joiners.isEmpty();
    }

    /** Into the leader's battle dialog. */
    private static void say(String text) {
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        InteractionDialogAPI d = ui == null ? null : ui.getCurrentInteractionDialog();
        if (d != null && d.getTextPanel() != null) d.getTextPanel().addPara(text);
    }

    private static float distance(SectorEntityToken a, SectorEntityToken b) {
        float dx = a.getLocation().x - b.getLocation().x;
        float dy = a.getLocation().y - b.getLocation().y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** The game's own battle set-up, plus the live side for joiners. Picked only while someone has joined. */
    static final class Creation implements BattleCreationPlugin {
        private final BattleCreationPluginImpl impl = new BattleCreationPluginImpl();

        public void initBattle(BattleCreationContext context, MissionDefinitionAPI api) {
            impl.initBattle(context, api);
            api.addPlugin(new JointCombat.Leader());
        }

        public void afterDefinitionLoad(CombatEngineAPI engine) {
            impl.afterDefinitionLoad(engine);
        }
    }

    /** How each of a joiner's ships is doing right now. */
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
            ShipAPI ship = byId.get(id);
            String state;
            if (destroyed.contains(id)) state = "destroyed";
            else if (disabled.contains(id)) state = "disabled";
            else if (retreated.contains(id)) state = "retreated";
            else if (ship == null) state = "reserve";
            else if (ship.isHulk() || !ship.isAlive()) state = "disabled";
            else if (ship.isRetreating()) state = "retreating";
            else state = "fighting";
            JSONObject o = new JSONObject().put("id", e.getValue()).put("name", jn.names.get(id)).put("state", state);
            if (ship != null && ship.isAlive()) o.put("hull", ship.getHullLevel()).put("cr", ship.getCurrentCR());
            a.put(o);
        }
        return a;
    }

    // ================================================================ joiner

    private enum Phase { NONE, ASKED, JOINING, IN, DONE }

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

    /**
     * We ran into a fleet someone is already dealing with: ask to join their
     * battle instead. Switches the dialog over to the join dialog.
     */
    static void offerToJoin(InteractionDialogAPI dialog, int leaderId, String name, String enemyName) {
        if (phase != Phase.NONE) return;
        phase = Phase.ASKED;
        leader = leaderId;
        leaderName = name;
        joinBattle = 0; // whatever they're fighting
        enemy = enemyName;
        lastHeard = System.nanoTime();
        shown.clear();
        JoinDialog jd = new JoinDialog(true);
        dialog.setPlugin(jd);
        jd.init(dialog);
    }

    private static void toJoiner(Session s, Session.PlayerMessage m, String k, JSONObject j) throws Exception {
        int b = j.optInt("b");
        if ("ask".equals(k)) {
            CampaignFleetAPI me = Global.getSector().getPlayerFleet();
            boolean free = phase == Phase.NONE && ClockSync.isMapView() && me != null && me.getBattle() == null;
            if (!free) {
                s.sendBattleMessage(m.from, msg("decline", b).put("busy", true).toString());
                return;
            }
            phase = Phase.ASKED;
            leader = m.from;
            leaderName = m.fromName;
            joinBattle = b;
            enemy = j.optString("enemy", "a hostile fleet");
            lastHeard = System.nanoTime();
            shown.clear();
            SectorEntityToken target = PuppetManager.fleetOf(m.from);
            if (target == null) target = me;
            if (!Global.getSector().getCampaignUI().showInteractionDialog(new JoinDialog(false), target)) {
                phase = Phase.NONE;
                s.sendBattleMessage(m.from, msg("decline", b).put("busy", true).toString());
            }
            return;
        }
        if (m.from != leader) return; // not ours
        if (joinBattle == 0 && "joined".equals(k)) joinBattle = b; // we asked to join "whatever they're fighting"
        if (b != joinBattle && joinBattle != 0) return;
        lastHeard = System.nanoTime();
        if ("joined".equals(k) && phase == Phase.JOINING) {
            phase = Phase.IN;
            say2("You've joined " + leaderName + "'s battle."
                    + (MultiSectorConfig.isJointBattleView() ? " When it begins, a view of it opens here and you fly "
                    + "your chosen ship; your other ships fight under the battle AI." : " Your ships fight under the battle AI."));
        } else if (("late".equals(k) || "nobattle".equals(k)) && (phase == Phase.JOINING || phase == Phase.ASKED)) {
            phase = Phase.DONE;
            say2("nobattle".equals(k) ? leaderName + " isn't in a battle (any more). Your fleet is as it was."
                    : "The fighting had already begun without you. Your fleet is as it was.");
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

    /** The leader's combat started: open our view of it, from the join dialog. */
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
        if (id != leader || phase == Phase.NONE || phase == Phase.DONE) return;
        phase = Phase.DONE;
        JointCombat.Mirror v = JointCombat.view;
        if (v != null) v.end();
        say2(leaderName + " left the game before the battle ended. Your fleet is as it was.");
        refresh();
    }

    private static void joinerFrame(Session s) {
        if (open == null) {
            if (phase == Phase.DONE) phase = Phase.NONE;
            return;
        }
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        InteractionDialogAPI d = ui == null ? null : ui.getCurrentInteractionDialog();
        if (d == null || d.getPlugin() != open) {
            // Closed some other way. A battle we joined still reports back; we apply it whenever it comes.
            if (phase == Phase.ASKED) s.sendBattleMessage(leader, msg("decline", joinBattle).toString());
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
        for (Integer id : new ArrayList<Integer>(joiners.keySet())) PuppetManager.releaseBattle(id);
        forget();
    }

    /** A save is loading (or the session ended): nothing carries over. */
    public static void forget() {
        joiners.clear();
        asked.clear();
        battle = null;
        inCombat = false;
        phase = Phase.NONE;
        open = null;
        leader = -1;
        shown.clear();
        viewCreation = null;
        viewDummy = null;
        JointCombat.pending.clear();
    }

    /** The joiner's dialog: asked (or asking), joining, watching, and the outcome. */
    private static final class JoinDialog implements InteractionDialogPlugin {
        private final boolean weAsked;
        InteractionDialogAPI dialog;
        private boolean quietOffered;

        JoinDialog(boolean weAsked) {
            this.weAsked = weAsked;
        }

        public void init(InteractionDialogAPI dialog) {
            this.dialog = dialog;
            open = this;
            CampaignFleetAPI me = Global.getSector().getPlayerFleet();
            pilotId = me != null && me.getFlagship() != null ? me.getFlagship().getId() : null;
            if (weAsked) {
                dialog.getTextPanel().addPara(leaderName + " is already dealing with " + enemy
                        + ". You can ask to join their battle.");
            } else {
                dialog.getTextPanel().addPara(leaderName + " is fighting " + enemy + " close by, and asks for your help.");
            }
            dialog.getTextPanel().addPara("If you join, your ships fight beside theirs on " + leaderName
                    + "'s game. What happens to them is real: ships lost are lost, and damage stays."
                    + (MultiSectorConfig.isJointBattleView()
                    ? " You fly one ship yourself in a live view of the battle; the rest fight under the battle AI."
                    : " They fight under the battle AI, and you can order them to retreat at any time."));
            menu();
        }

        void menu() {
            OptionPanelAPI op = dialog.getOptionPanel();
            op.clearOptions();
            if (phase == Phase.ASKED) {
                op.addOption(weAsked ? "Ask to join " + leaderName + "'s battle" : "Join the battle", "join");
                if (MultiSectorConfig.isJointBattleView()) op.addOption("Ship you'll pilot: " + pilotName(), "pick");
                op.addOption(weAsked ? "Leave" : "Stay out", "out");
                dialog.setOptionOnEscape("Stay out", "out");
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
                if (phase == Phase.ASKED && !weAsked) s.sendBattleMessage(leader, msg("decline", joinBattle).toString());
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
