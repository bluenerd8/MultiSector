package multisector.sync;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleCreationPlugin;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import multisector.character.ShipJson;
import multisector.net.NetConfig;
import multisector.net.Session;
import multisector.ui.MultiSectorConfig;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The live side of a joint battle ({@link JointBattle}): seeing it, and
 * piloting your own ship in it.
 *
 * <p>The battle only really happens on the <b>leader's</b> game. There, the
 * {@link Leader} plugin:
 * <ul>
 * <li>announces every ship as it enters the battle (its exact loadout),
 *     and streams every ship's state 20 times a second (position, speed,
 *     heading, hull, flux, shield, system, engines, which weapons are firing
 *     and where), to each player who joined;</li>
 * <li>brings joiners in as reinforcements once combat is under way: their
 *     combat-ready ships (config) and always the one they pilot arrive at the
 *     leader's edge of the map as allies, and the leader is told in combat;</li>
 * <li>hands that ship to {@link RemotePilot}, which flies it from the joiner's
 *     controls (falling back to the ship's own AI if they stop coming).</li>
 * </ul>
 *
 * <p>On a <b>joiner's</b> game, a battle of its own opens ({@link Mirror}):
 * the same ships, placed and posed from the leader's stream, firing where
 * the leader's do. They can't be hurt there (shots hit, nothing is
 * damaged), so everything that counts comes from the leader. The joiner's
 * piloted ship is their "own" ship there, camera and all, but its controls
 * are read and sent to the leader instead of moving it: WASD/QE to move,
 * mouse to aim, left button fires the selected group (1-7), right button
 * shields, F system, V vent, Z fighters, T autofire, R target.
 *
 * <p>The view follows the leader's combat speed and pause, so speed-up mods
 * (2x combat speed and the like) on the leader show at the same speed.
 */
final class JointCombat {

    private static final Logger log = Global.getLogger(JointCombat.class);

    static final byte STATE = 1;
    static final byte INPUT = 2;

    // ship state flags
    private static final int F_SHIELD = 1, F_PHASED = 2, F_SYSTEM = 4, F_OVERLOAD = 8, F_VENT = 16, F_HULK = 32,
            F_RETREAT = 64;
    // engine flags
    private static final int E_ACCEL = 1, E_BACK = 2, E_DECEL = 4, E_LEFT = 8, E_RIGHT = 16, E_SLEFT = 32, E_SRIGHT = 64;
    // input keys
    private static final int K_W = 1, K_S = 2, K_A = 4, K_D = 8, K_Q = 16, K_E = 32, K_FIRE = 64, K_DECEL = 128;
    /** The joiner switched their ship to autopilot: the leader gives it its AI. */
    private static final int K_AUTO = 256;
    /** Toggle counters in an input packet: shield, system, vent, fighters, autofire. */
    private static final int TOGGLES = 5;

    private static final long STATE_NANOS = 50_000_000L;
    private static final long INPUT_NANOS = 33_000_000L;
    private static final long INPUT_STALE_NANOS = 1_500_000_000L;
    private static final long STATUS_NANOS = 1_000_000_000L;
    private static final String IMMUNE = "multisector_view";
    private static final String TIME_ID = "multisector_leader_speed";

    /**
     * On every ship a player brought into someone else's battle: that
     * player's id. What's done with those ships follows from it: an ally on
     * the leader's game (so the leader can't command or take them over), with
     * the orders their player gives in their view enforced on them, and
     * ringed in that player's colour.
     */
    static final String PLAYER_TAG = "multisector_player";

    /** Each player's colour in battles, by id: the same on every game. */
    private static final java.awt.Color[] PLAYER_COLORS = {
            new java.awt.Color(60, 200, 255), new java.awt.Color(255, 90, 220), new java.awt.Color(255, 170, 40),
            new java.awt.Color(150, 110, 255), new java.awt.Color(255, 240, 80), new java.awt.Color(80, 255, 170)};

    static java.awt.Color colorOf(int player) {
        return PLAYER_COLORS[Math.abs(player) % PLAYER_COLORS.length];
    }

    /** The player a ship belongs to (tagged), or -1. */
    static int playerOf(ShipAPI ship) {
        Object o = ship.getCustomData().get(PLAYER_TAG);
        return o instanceof Integer ? (Integer) o : -1;
    }

    /** A glow in the player's colour, under the hull: whose ship it is, at a glance. */
    private static void mark(ShipAPI ship, int player) {
        if (ship.isHulk() || !ship.isAlive()) return;
        java.awt.Color c = colorOf(player);
        ship.setJitterUnder(PLAYER_TAG, new java.awt.Color(c.getRed(), c.getGreen(), c.getBlue(), 110), 1f, 3, 0f, 5f);
    }

    /**
     * A ring in each player's colour around their ships, in world space
     * (from a combat plugin's renderInWorldCoords). Skips one player's own.
     */
    static void rings(CombatEngineAPI engine, int skipPlayer) {
        if (engine == null) return;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(2.5f);
            for (ShipAPI ship : engine.getShips()) {
                int player = playerOf(ship);
                if (player < 0 || player == skipPlayer || ship.isFighter() || ship.isHulk() || !ship.isAlive()) continue;
                java.awt.Color c = colorOf(player);
                float r = ship.getCollisionRadius() + 35f;
                float cx = ship.getLocation().x, cy = ship.getLocation().y;
                GL11.glColor4ub((byte) c.getRed(), (byte) c.getGreen(), (byte) c.getBlue(), (byte) 190);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                for (int i = 0; i < 56; i++) {
                    double a = i * Math.PI * 2 / 56;
                    GL11.glVertex2f(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r);
                }
                GL11.glEnd();
            }
        } catch (RuntimeException ignored) {
            // no rings this frame
        } finally {
            GL11.glPopAttrib();
        }
    }

    /** Where in our code this is happening, for the log (every place a ship is spawned or deployed). */
    static String where() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder b = new StringBuilder();
        for (int i = 1; i < st.length && i < 9; i++) b.append("\n    at ").append(st[i]);
        return b.toString();
    }

    private static String aiName(ShipAPI ship) {
        ShipAIPlugin ai = ship.getShipAI();
        return ai == null ? "player" : ai.getClass().getSimpleName();
    }

    private JointCombat() {
    }

    // ================================================================ leader

    /** One joiner's latest controls. */
    static final class Input {
        int seq;
        int ship;
        int keys;
        float mx, my;
        int group;
        int target;
        final int[] toggles = new int[TOGGLES];
        long arrived;
    }

    /** On the leader's game, for the battle's whole length (from {@link JointCombatHook}). */
    static final class Leader extends BaseEveryFrameCombatPlugin {
        /** The one running now, if we're leading a battle's combat. */
        static volatile Leader active;

        private CombatEngineAPI engine;
        private final Map<ShipAPI, Integer> ids = new IdentityHashMap<ShipAPI, Integer>();
        private final Map<Integer, ShipAPI> byId = new HashMap<Integer, ShipAPI>();
        private int nextId = 1;
        private int seq;
        private long nextState, nextStatus;
        private boolean ended;
        private int slot;
        /** Our own ships are out: joiners' ships can go in. */
        private boolean ready;
        private long nextGuard, nextCheck, nextOrders;
        /** Shots each weapon fired since the last state we sent (every projectile and missile, by its weapon). */
        private final Map<WeaponAPI, int[]> shots = new IdentityHashMap<WeaponAPI, int[]>();
        private Set<Object> seenShots = java.util.Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        /** When we last gave each ship its player's order (so the ally AI's own isn't fought every frame). */
        private final Map<ShipAPI, Long> ordered = new IdentityHashMap<ShipAPI, Long>();
        /** The assignment we made for each ship's current order, to give again rather than make another. */
        private final Map<ShipAPI, Object[]> made = new IdentityHashMap<ShipAPI, Object[]>();
        private final Map<Integer, Input> inputs = new ConcurrentHashMap<Integer, Input>();
        /** Ships flown from a joiner's controls right now (the rest of the time they have their own AI). */
        private final Set<ShipAPI> piloted = java.util.Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());

        @Override
        public void init(CombatEngineAPI engine) {
            this.engine = engine;
            active = this;
            JointBattle.combatStarted();
            log.info("[MultiSector] combat started: other players can join it now");
        }

        /**
         * A player joins mid-fight: they see the battle as it is, and their
         * ships arrive as reinforcements.
         */
        void welcome(JointBattle.Joiner jn) throws Exception {
            Session s = Session.get();
            s.sendBattleMessage(jn.id, JointBattle.msg("cstart").put("w", engine.getMapWidth())
                    .put("h", engine.getMapHeight()).toString());
            for (Map.Entry<ShipAPI, Integer> e : ids.entrySet()) {
                s.sendBattleMessage(jn.id, cship(e.getKey(), e.getValue(), jn).toString());
            }
            jn.joinedAt = System.nanoTime();
            message(jn.name + " is joining: they choose what to deploy from their view of the battle");
        }

        /**
         * Without a view (config, or it couldn't open): their combat-ready
         * ships come in together, as before.
         */
        void bringIn(JointBattle.Joiner jn) {
            if (jn.deployed) return;
            int n = deploy(jn);
            announceArrival(jn, n);
        }

        /**
         * The joiner deployed these ships from their view's deployment panel:
         * exactly those come in, where they put them. Nothing else of theirs
         * ever deploys by itself.
         */
        void bringIn(JointBattle.Joiner jn, org.json.JSONArray ships) {
            CombatFleetManagerAPI fm = engine.getFleetManager(0);
            if (fm == null || ships == null) return;
            int n = 0;
            for (int i = 0; i < ships.length(); i++) {
                JSONObject o = ships.optJSONObject(i);
                if (o == null) continue;
                FleetMemberAPI m = jn.memberFor(o.optString("id"));
                if (m == null || m.getRepairTracker().isMothballed() || !jn.spawned.add(m.getId())) continue;
                Vector2f at = new Vector2f((float) o.optDouble("x", 0), (float) o.optDouble("y", -engine.getMapHeight() / 2f + 500f));
                if (spawn(jn, m, at, (float) o.optDouble("f", 90), "they deployed it from their view")) n++;
            }
            if (n > 0) announceArrival(jn, n);
        }

        private void announceArrival(JointBattle.Joiner jn, int n) {
            boolean first = !jn.deployed;
            jn.deployed = true;
            message(jn.name + (first ? " joins your battle with " : " brings in ") + n + (n == 1 ? " ship" : " ships"));
            if (!first) return;
            try {
                Global.getSoundPlayer().playUISound("ui_noise_static_message", 1f, 1f);
            } catch (RuntimeException ignored) {
                // no sound
            }
        }

        /** One joiner ship into the fight: tagged as theirs, an ally (we don't command it), never twice. */
        private boolean spawn(JointBattle.Joiner jn, FleetMemberAPI m, Vector2f at, float facing, String why) {
            CombatFleetManagerAPI fm = engine.getFleetManager(0);
            try {
                m.setAlly(true);
                ShipAPI ship = fm.spawnFleetMember(m, at, facing, 1.5f);
                fm.removeFromReserves(m); // in the fight, not waiting to be deployed again
                if (ship == null) return false;
                ship.setCustomData(PLAYER_TAG, jn.id);
                ship.setAlly(true);
                engine.addFloatingText(ship.getLocation(), jn.name, 28f, colorOf(jn.id), ship, 1f, 4f);
                log.info("[MultiSector] " + jn.name + "'s " + m.getShipName() + " is in (" + why + "): ally=" + ship.isAlly()
                        + ", fleet " + jn.fleet.getName() + " (outside the campaign battle), owner " + ship.getOwner() + where());
                return true;
            } catch (RuntimeException e) {
                log.warn("[MultiSector] couldn't deploy " + jn.name + "'s " + m.getShipName(), e);
                return false;
            }
        }

        /** The joiner flies another of their ships now (transferred command in their view). */
        void pilotShip(JointBattle.Joiner jn, int shipId) {
            ShipAPI ship = byId.get(shipId);
            if (ship == null || playerOf(ship) != jn.id || ship.getFleetMember() == null) return;
            jn.pilotPuppetId = ship.getFleetMember().getId();
        }

        /** Their orders, from their view: kept until they change them. */
        void orders(JointBattle.Joiner jn, org.json.JSONArray list) {
            jn.orders.clear();
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o != null && !o.optString("t").isEmpty()) jn.orders.put(o.optInt("id"), o);
            }
        }

        @Override
        public void advance(float amount, List<InputEventAPI> events) {
            if (engine == null) return;
            Session s = Session.get();
            try {
                JointBattle.handleMessages(s); // campaign scripts are stopped during combat
                readInputs(s);
                if (!ready && leaderDeployed()) {
                    ready = true;
                    JointBattle.tellFightSoon();
                    JointBattle.admitQueued(s);
                }
                guard();
                players();
                track(s);
                if (MultiSectorConfig.isJointBattleChecks() && !JointBattle.joiners().isEmpty()) check();
                JointBattle.tellFight(s, !ended, ready);
                boolean orderTick = System.nanoTime() - nextOrders >= 0;
                if (orderTick) nextOrders = System.nanoTime() + 500_000_000L;
                for (JointBattle.Joiner jn : JointBattle.joiners()) {
                    if (jn.retreat) retreat(jn);
                    pilot(jn);
                    if (orderTick) applyOrders(jn);
                }
                countShots();
                long now = System.nanoTime();
                if (now - nextState >= 0) {
                    nextState = now + STATE_NANOS;
                    sendStates(s);
                    shots.clear();
                }
                boolean over = engine.isCombatOver();
                if (now - nextStatus >= 0 || (over && !ended)) {
                    nextStatus = now + STATUS_NANOS;
                    for (JointBattle.Joiner jn : JointBattle.joiners()) {
                        s.sendBattleMessage(jn.id, JointBattle.msg("status").put("ships", JointBattle.status(engine, jn)).toString());
                    }
                }
                if (over && !ended) {
                    ended = true;
                    for (JointBattle.Joiner jn : JointBattle.joiners()) s.sendBattleMessage(jn.id, JointBattle.msg("cend").toString());
                    JointBattle.finishJoiners(s);
                    JointBattle.tellFightSoon();
                    JointBattle.tellFight(s, false, false);
                }
            } catch (Exception e) {
                log.warn("[MultiSector] joint battle frame failed", e);
            }
        }

        @Override
        public void renderInWorldCoords(ViewportAPI viewport) {
            rings(engine, -1);
        }

        /** New ships are announced, gone ones dropped. Fighters and station modules come with their ship. */
        private void track(Session s) throws Exception {
            Set<ShipAPI> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
            for (ShipAPI ship : engine.getShips()) {
                if (ship.isFighter() || ship.getParentStation() != null || ship.getFleetMember() == null) continue;
                seen.add(ship);
                if (ids.containsKey(ship)) continue;
                int id = nextId++;
                ids.put(ship, id);
                byId.put(id, ship);
                for (JointBattle.Joiner jn : JointBattle.joiners()) {
                    s.sendBattleMessage(jn.id, cship(ship, id, jn).toString());
                }
            }
            for (ShipAPI gone : new ArrayList<ShipAPI>(ids.keySet())) {
                if (seen.contains(gone)) continue;
                int id = ids.remove(gone);
                byId.remove(id);
                for (JointBattle.Joiner jn : JointBattle.joiners()) {
                    s.sendBattleMessage(jn.id, JointBattle.msg("cgone").put("id", id).toString());
                }
            }
        }

        boolean isReady() {
            return ready;
        }

        /**
         * Every 5 s while someone has joined: every ship as this game has it,
         * by the ids the joiners' views use. Compare with their "view check".
         */
        private void check() {
            long now = System.nanoTime();
            if (now - nextCheck < 0) return;
            nextCheck = now + 5_000_000_000L;
            StringBuilder b = new StringBuilder("[MultiSector] battle check, t=")
                    .append(Math.round(engine.getTotalElapsedTime(false))).append("s, ").append(ids.size())
                    .append(" ships (id hull owner ally player CR hull state ai):");
            for (Map.Entry<ShipAPI, Integer> e : ids.entrySet()) {
                ShipAPI ship = e.getKey();
                b.append("\n  ").append(e.getValue()).append(' ').append(ship.getHullSpec().getHullId())
                        .append(" o=").append(ship.getOwner()).append(" a=").append(ship.isAlly() ? 1 : 0)
                        .append(" p=").append(ownerPlayer(ship))
                        .append(" cr=").append(Math.round(ship.getCurrentCR() * 100))
                        .append(" h=").append(Math.round(ship.getHullLevel() * 100))
                        .append(' ').append(ship.isHulk() ? "hulk" : !ship.isAlive() ? "dead" : ship.isRetreating() ? "retreating" : "ok")
                        .append(' ').append(aiName(ship));
            }
            log.info(b.toString());
        }

        /** The ship we fly, last time it was one of ours. */
        private ShipAPI ownShip;

        /**
         * Every frame: other players' ships stay theirs. They're allies here
         * (we can't command them), marked in their colour, and we can't take
         * one over.
         */
        private void players() {
            for (ShipAPI ship : engine.getShips()) {
                int player = playerOf(ship);
                if (player < 0) continue;
                if (!ship.isAlly()) ship.setAlly(true);
                mark(ship, player);
            }
            ShipAPI ps = engine.getPlayerShip();
            if (ps == null) return;
            int player = playerOf(ps);
            if (player < 0) {
                ownShip = ps;
                return;
            }
            if (ownShip != null && ownShip != ps && ownShip.isAlive() && !ownShip.isHulk() && engine.isEntityInPlay(ownShip)) {
                engine.setPlayerShipExternal(ownShip);
                message("That ship is " + Session.get().playerName(player) + "'s: they command it");
            }
        }

        /**
         * Our ships are on the field and the deployment screen is behind us
         * (combat time only runs once it's closed). Joiners wait for this, so
         * their ships never go in while the battle is still being set up.
         */
        private boolean leaderDeployed() {
            if (engine.getTotalElapsedTime(false) < 1f) return false;
            for (ShipAPI ship : engine.getShips()) {
                if (ship.getOwner() != 0 || ship.isFighter() || ship.isHulk() || ship.getFleetMember() == null) continue;
                if (joinerOf(ship.getFleetMember()) == null) return true;
            }
            return engine.getTotalElapsedTime(false) > 30f; // nothing of ours deployed: don't keep friends waiting forever
        }

        private static JointBattle.Joiner joinerOf(FleetMemberAPI m) {
            for (JointBattle.Joiner jn : JointBattle.joiners()) if (jn.toOwner.containsKey(m.getId())) return jn;
            return null;
        }

        /**
         * Each joiner ship is in the fight once, ours: a second copy of one,
         * or a ship of another player's stand-in fleet (it must never be in a
         * battle), is taken out, and none of them wait in reserve where the
         * game could deploy them again.
         */
        private void guard() {
            long now = System.nanoTime();
            if (now - nextGuard < 0) return;
            nextGuard = now + 500_000_000L;
            Map<String, ShipAPI> seen = new HashMap<String, ShipAPI>();
            for (ShipAPI ship : new ArrayList<ShipAPI>(engine.getShips())) {
                FleetMemberAPI m = ship.getFleetMember();
                if (m == null || ship.isFighter() || ship.getParentStation() != null || ship.isHulk()) continue;
                if (isPuppet(m)) {
                    drop(ship, "another player's stand-in fleet");
                    continue;
                }
                if (joinerOf(m) == null) continue;
                ShipAPI had = seen.get(m.getId());
                if (had == null) {
                    seen.put(m.getId(), ship);
                    continue;
                }
                // Keep the one being flown (or the first), drop the other.
                ShipAPI extra = piloted.contains(ship) ? had : ship;
                if (extra == had) seen.put(m.getId(), ship);
                drop(extra, "a second copy");
            }
            for (int side = 0; side <= 1; side++) {
                CombatFleetManagerAPI fm = engine.getFleetManager(side);
                if (fm == null) continue;
                for (FleetMemberAPI m : fm.getReservesCopy()) {
                    if (isPuppet(m) || joinerOf(m) != null) fm.removeFromReserves(m);
                }
            }
        }

        private static boolean isPuppet(FleetMemberAPI m) {
            try {
                com.fs.starfarer.api.campaign.FleetDataAPI d = m.getFleetData();
                com.fs.starfarer.api.campaign.CampaignFleetAPI f = d == null ? null : d.getFleet();
                return f != null && f.getMemoryWithoutUpdate().getBoolean(PuppetManager.PUPPET_FLAG);
            } catch (RuntimeException e) {
                return false;
            }
        }

        private void drop(ShipAPI ship, String why) {
            log.info("[MultiSector] taking " + ship.getFleetMember().getShipName() + " out of the battle (" + why + ")");
            try {
                CombatFleetManagerAPI fm = engine.getFleetManager(ship.getOwner());
                if (fm != null) fm.removeDeployed(ship, false);
            } catch (RuntimeException ignored) {
                // removing it from the field is what matters
            }
            engine.removeEntity(ship);
        }

        /** A joiner's view is missing these ships: announce them again. */
        void reannounce(JointBattle.Joiner jn, org.json.JSONArray want) throws Exception {
            if (want == null) return;
            Session s = Session.get();
            for (int i = 0; i < want.length() && i < 60; i++) {
                ShipAPI ship = byId.get(want.optInt(i));
                if (ship != null) s.sendBattleMessage(jn.id, cship(ship, want.optInt(i), jn).toString());
            }
        }

        /** A ship, announced to a joiner: its exact loadout, side, and whether it's theirs. */
        private static JSONObject cship(ShipAPI ship, int id, JointBattle.Joiner jn) throws Exception {
            FleetMemberAPI m = ship.getFleetMember();
            String owner = jn.toOwner.get(m.getId());
            return JointBattle.msg("cship").put("id", id).put("owner", ship.getOwner())
                    .put("ally", ship.isAlly()).put("ship", ShipJson.ship(m)).put("name", m.getShipName())
                    .put("player", ownerPlayer(ship))
                    .put("mine", owner != null).put("pilot", m.getId().equals(jn.pilotPuppetId))
                    .put("real", owner != null ? owner : "").put("cr", ship.getCurrentCR())
                    .put("x", ship.getLocation().x).put("y", ship.getLocation().y).put("f", ship.getFacing());
        }

        /** Whose ship it is: a joiner's (tagged), ours (our side, not an AI ally), or nobody's (-1). */
        private static int ownerPlayer(ShipAPI ship) {
            int player = playerOf(ship);
            if (player >= 0) return player;
            return ship.getOwner() == 0 && !ship.isAlly() ? Session.get().getMyId() : -1;
        }

        /**
         * Every new projectile or missile this frame, counted against the
         * weapon that fired it. A weapon is only "firing" for a moment per
         * shot, so sampling that 20 times a second missed most of them.
         */
        private void countShots() {
            if (JointBattle.joiners().isEmpty()) return;
            Set<Object> now = java.util.Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            for (com.fs.starfarer.api.combat.DamagingProjectileAPI p : engine.getProjectiles()) {
                now.add(p);
                if (seenShots.contains(p)) continue;
                WeaponAPI w = p.getWeapon();
                if (w == null) continue;
                int[] c = shots.get(w);
                if (c == null) shots.put(w, c = new int[1]);
                c[0]++;
            }
            seenShots = now;
        }

        /** Every ship's state, packed into datagrams, to every joiner. */
        private void sendStates(Session s) {
            if (JointBattle.joiners().isEmpty()) return;
            float timeMult = engine.getTimeMult().getModifiedValue();
            List<ByteBuffer> packets = new ArrayList<ByteBuffer>();
            ByteBuffer bb = null;
            int countPos = 0;
            short count = 0;
            int room = NetConfig.MAX_DATAGRAM - 7 - 8;
            for (Map.Entry<ShipAPI, Integer> e : ids.entrySet()) {
                byte[] entry = encode(e.getValue(), e.getKey(), shots);
                if (bb == null || bb.remaining() < entry.length) {
                    if (bb != null) {
                        bb.putShort(countPos, count);
                        bb.flip();
                        packets.add(bb);
                    }
                    bb = ByteBuffer.allocate(room);
                    bb.put(STATE).putInt(seq).putFloat(timeMult).put((byte) (engine.isPaused() ? 1 : 0));
                    countPos = bb.position();
                    bb.putShort((short) 0);
                    count = 0;
                }
                if (bb.remaining() < entry.length) continue; // one huge ship; never in practice
                bb.put(entry);
                count++;
            }
            if (bb != null) {
                bb.putShort(countPos, count);
                bb.flip();
                packets.add(bb);
            }
            seq++;
            for (JointBattle.Joiner jn : JointBattle.joiners()) {
                for (ByteBuffer p : packets) s.sendCombatPacket(jn.id, p);
            }
        }

        private static byte[] encode(int id, ShipAPI ship, Map<WeaponAPI, int[]> shots) {
            List<WeaponAPI> weapons = ship.getAllWeapons();
            List<int[]> firing = new ArrayList<int[]>();
            for (int i = 0; i < weapons.size() && firing.size() < 40; i++) {
                WeaponAPI w = weapons.get(i);
                int[] c = shots.get(w);
                boolean beam = w.isBeam() && w.isFiring();
                // count 0: a beam, firing right now; otherwise how many shots since the last update
                if (beam || c != null) firing.add(new int[]{i, Math.round(w.getCurrAngle() * 10f), beam ? 0 : Math.min(255, c[0])});
            }
            ByteBuffer bb = ByteBuffer.allocate(43 + firing.size() * 4);
            bb.putShort((short) id);
            bb.putFloat(ship.getLocation().x).putFloat(ship.getLocation().y);
            bb.putFloat(ship.getVelocity().x).putFloat(ship.getVelocity().y);
            bb.putFloat(ship.getFacing()).putFloat(ship.getAngularVelocity());
            bb.put(unit(ship.getHullLevel()));
            FluxTrackerAPI ft = ship.getFluxTracker();
            float max = Math.max(1f, ft.getMaxFlux());
            bb.put(unit(ft.getCurrFlux() / max)).put(unit(ft.getHardFlux() / max));
            bb.put(unit(ship.getCurrentCR()));
            int flags = 0;
            ShieldAPI sh = ship.getShield();
            if (sh != null && sh.isOn()) flags |= F_SHIELD;
            if (ship.isPhased()) flags |= F_PHASED;
            ShipSystemAPI sys = ship.getSystem();
            if (sys != null && sys.isActive()) flags |= F_SYSTEM;
            if (ft.isOverloaded()) flags |= F_OVERLOAD;
            if (ft.isVenting()) flags |= F_VENT;
            if (ship.isHulk() || !ship.isAlive()) flags |= F_HULK;
            if (ship.isRetreating()) flags |= F_RETREAT;
            bb.put((byte) flags);
            int eng = 0;
            ShipEngineControllerAPI ec = ship.getEngineController();
            if (ec != null) {
                if (ec.isAccelerating()) eng |= E_ACCEL;
                if (ec.isAcceleratingBackwards()) eng |= E_BACK;
                if (ec.isDecelerating()) eng |= E_DECEL;
                if (ec.isTurningLeft()) eng |= E_LEFT;
                if (ec.isTurningRight()) eng |= E_RIGHT;
                if (ec.isStrafingLeft()) eng |= E_SLEFT;
                if (ec.isStrafingRight()) eng |= E_SRIGHT;
            }
            bb.put((byte) eng);
            bb.putShort((short) Math.round((sh == null ? 0f : sh.getFacing()) * 10f));
            bb.put(stateOf(ship.getSystem())).put(stateOf(ship.getPhaseCloak()));
            bb.put((byte) firing.size());
            for (int[] f : firing) {
                bb.put((byte) f[0]);
                bb.putShort((short) f[1]);
                bb.put((byte) f[2]);
            }
            byte[] out = new byte[bb.position()];
            bb.flip();
            bb.get(out);
            return out;
        }

        private void readInputs(Session s) {
            Session.CombatPacket p;
            for (int n = 0; n < 100 && (p = s.pollCombatPacket()) != null; n++) {
                ByteBuffer bb = p.data;
                if (bb.remaining() < 1 || bb.get() != INPUT) continue;
                Input in = new Input();
                in.seq = bb.getInt();
                in.ship = bb.getShort();
                in.keys = bb.getInt();
                in.mx = bb.getFloat();
                in.my = bb.getFloat();
                in.group = bb.get();
                in.target = bb.getShort();
                for (int i = 0; i < TOGGLES; i++) in.toggles[i] = bb.get() & 0xff;
                in.arrived = System.nanoTime();
                Input old = inputs.get(p.from);
                if (old == null || in.seq > old.seq || old.seq - in.seq > 1000) inputs.put(p.from, in);
            }
        }

        /**
         * A joiner's ships arrive as reinforcements at our edge of the map:
         * their combat-ready ships (config), and always the one they pilot.
         * Their fleet is no part of the campaign battle, so the game leaves
         * them out of its outcome; we report it to the joiner instead.
         */
        private int deploy(JointBattle.Joiner jn) {
            CombatFleetManagerAPI fm = engine.getFleetManager(0);
            if (fm == null) return 0;
            float y = -engine.getMapHeight() / 2f + 500f;
            int n = 0;
            for (FleetMemberAPI m : jn.fleet.getFleetData().getMembersListCopy()) {
                if (m.isFighterWing() || m.getRepairTracker().isMothballed() || !jn.spawned.add(m.getId())) continue;
                boolean pilot = m.getId().equals(jn.pilotPuppetId);
                boolean ready = !m.isCivilian() && m.getRepairTracker().getCR() >= 0.2f;
                if (!pilot && !(ready && MultiSectorConfig.isJointBattleDeployAll())) continue;
                float x = (slot % 2 == 0 ? 1 : -1) * (300f + 400f * (slot / 2));
                slot++;
                if (spawn(jn, m, new Vector2f(x, y), 90f, "they have no battle view: their whole fleet")) n++;
            }
            return n;
        }

        /**
         * The joiner's chosen ship flies on their controls while they're
         * coming in, and on its own AI while they aren't (before their view
         * opens, or if their connection stalls). The game won't let a ship's
         * old AI be called once replaced, so it gets a fresh default AI back.
         */
        private void pilot(JointBattle.Joiner jn) {
            for (ShipAPI ship : new ArrayList<ShipAPI>(piloted)) {
                // They took the helm of another ship: this one is back on its AI.
                if (playerOf(ship) != jn.id || ship.getFleetMember() == null) continue;
                if (ship.getFleetMember().getId().equals(jn.pilotPuppetId)) continue;
                piloted.remove(ship);
                if (ship.isAlive() && !ship.isHulk()) ship.resetDefaultAI();
            }
            if (jn.pilotPuppetId == null) return;
            Input in = inputs.get(jn.id);
            boolean fresh = in != null && System.nanoTime() - in.arrived < INPUT_STALE_NANOS;
            boolean autopilot = fresh && (in.keys & K_AUTO) != 0;
            boolean live = fresh && !autopilot;
            for (ShipAPI ship : engine.getShips()) {
                FleetMemberAPI m = ship.getFleetMember();
                if (m == null || !m.getId().equals(jn.pilotPuppetId)) continue;
                boolean flying = piloted.contains(ship);
                if (ship.isHulk() || !ship.isAlive()) {
                    piloted.remove(ship);
                    continue;
                }
                if (live && !flying) {
                    piloted.add(ship);
                    ship.setShipAI(new RemotePilot(ship, jn.id, inputs, byId));
                    banner(jn.name + " takes the helm of " + m.getShipName());
                    log.info("[MultiSector] " + jn.name + " is piloting " + m.getShipName());
                } else if (!live && flying) {
                    piloted.remove(ship);
                    ship.resetDefaultAI();
                    banner(autopilot ? jn.name + " puts " + m.getShipName() + " on autopilot"
                            : jn.name + "'s controls stopped coming: " + m.getShipName() + " is on its own AI");
                    log.info("[MultiSector] " + jn.name + "'s controls stopped; " + m.getShipName() + " back on its AI");
                }
            }
        }

        /**
         * The joiner's orders for their ships, enforced: whatever the ally AI
         * assigned instead is replaced (at most every 3 s per ship, so the two
         * don't fight every frame). Ships without an order of theirs are left
         * to the ally AI. Their piloted ship flies on their controls.
         */
        private void applyOrders(JointBattle.Joiner jn) {
            long now = System.nanoTime();
            for (Map.Entry<Integer, JSONObject> e : jn.orders.entrySet()) {
                ShipAPI ship = byId.get(e.getKey());
                if (ship == null || !ship.isAlive() || ship.isHulk() || piloted.contains(ship) || playerOf(ship) != jn.id) continue;
                Long last = ordered.get(ship);
                if (last != null && now - last < 3_000_000_000L) continue;
                try {
                    if (give(ship, e.getValue())) ordered.put(ship, now);
                } catch (RuntimeException ex) {
                    log.warn("[MultiSector] couldn't give " + jn.name + "'s order to " + ship.getFleetMember().getShipName(), ex);
                    ordered.put(ship, now);
                }
            }
        }

        /** One order on one ship, unless it already has it. True if it was given. */
        private boolean give(ShipAPI ship, JSONObject o) {
            com.fs.starfarer.api.combat.CombatAssignmentType type;
            try {
                type = com.fs.starfarer.api.combat.CombatAssignmentType.valueOf(o.optString("t"));
            } catch (IllegalArgumentException ex) {
                return false;
            }
            CombatFleetManagerAPI fm = engine.getFleetManager(ship.getOwner());
            com.fs.starfarer.api.combat.CombatTaskManagerAPI tm = fm.getTaskManager(ship.isAlly());
            com.fs.starfarer.api.combat.DeployedFleetMemberAPI dfm = fm.getDeployedFleetMember(ship);
            if (dfm == null) return false;
            Vector2f want = o.has("x") ? new Vector2f((float) o.optDouble("x"), (float) o.optDouble("y")) : null;
            ShipAPI targetShip = o.optInt("tgt", 0) > 0 ? byId.get(o.optInt("tgt")) : null;
            if (targetShip != null && targetShip.getLocation() != null) want = new Vector2f(targetShip.getLocation());
            CombatFleetManagerAPI.AssignmentInfo cur = tm.getAssignmentFor(ship);
            if (cur != null && cur.getType() == type) {
                if (want == null || cur.getTarget() == null) return false;
                Vector2f at = cur.getTarget().getLocation();
                if (at != null && Math.hypot(at.x - want.x, at.y - want.y) < 400) return false;
            }
            if (type == com.fs.starfarer.api.combat.CombatAssignmentType.RETREAT) {
                tm.orderRetreat(dfm, false, false);
                return true;
            }
            if (type == com.fs.starfarer.api.combat.CombatAssignmentType.SEARCH_AND_DESTROY && want == null) {
                tm.orderSearchAndDestroy(dfm, false);
                return true;
            }
            // Only targets that fit the order: a ship for ship orders, an objective for objective orders, else a spot.
            String kind = orderKind(type);
            if (kind == null) return false;
            String sig = type.name() + "|" + o.optInt("tgt", 0) + "|" + o.optInt("x", 0) / 200 + "|" + o.optInt("y", 0) / 200;
            Object[] had = made.get(ship);
            if (had != null && sig.equals(had[0]) && tm.getAllAssignments().contains(had[1])) {
                tm.giveAssignment(dfm, (CombatFleetManagerAPI.AssignmentInfo) had[1], false); // the same order, given again
                return true;
            }
            com.fs.starfarer.api.combat.AssignmentTargetAPI target = null;
            if ("ship".equals(kind)) {
                if (targetShip == null || !targetShip.isAlive() || targetShip.isHulk() || !engine.isEntityInPlay(targetShip)) return false;
                CombatFleetManagerAPI tfm = engine.getFleetManager(targetShip.getOwner());
                target = tfm == null ? null : tfm.getDeployedFleetMember(targetShip);
            } else if (want != null) {
                for (com.fs.starfarer.api.combat.BattleObjectiveAPI obj : engine.getObjectives()) {
                    if (obj.getLocation() != null && Math.hypot(obj.getLocation().x - want.x, obj.getLocation().y - want.y) < 400) {
                        target = obj;
                        break;
                    }
                }
                if (target == null && "spot".equals(kind)) target = fm.createWaypoint(want, ship.isAlly());
            }
            if (target == null || target.getLocation() == null) return false;
            CombatFleetManagerAPI.AssignmentInfo info = tm.createAssignment(type, target, false);
            if (info == null) return false;
            tm.giveAssignment(dfm, info, false);
            made.put(ship, new Object[]{sig, info});
            return true;
        }

        /** What an order needs as its target: "ship", "objective", "spot", or null for orders we don't pass on. */
        private static String orderKind(com.fs.starfarer.api.combat.CombatAssignmentType type) {
            switch (type) {
                case INTERCEPT:
                case ENGAGE:
                case HARASS:
                case STRIKE:
                case LIGHT_ESCORT:
                case MEDIUM_ESCORT:
                case HEAVY_ESCORT:
                    return "ship";
                case CAPTURE:
                case CONTROL:
                case ASSAULT:
                    return "objective";
                case DEFEND:
                case AVOID:
                case RECON:
                case RALLY_TASK_FORCE:
                case RALLY_CARRIER:
                case RALLY_CIVILIAN:
                case RALLY_STRIKE_FORCE:
                case RALLY_FIGHTERS:
                    return "spot";
                default:
                    return null;
            }
        }

        /** A line in the battle's message log, for the leader, from anywhere in combat. */
        static void message(String text) {
            Leader l = active;
            if (l != null && l.engine != null) l.banner(text);
        }

        /** A line in the battle's message log, for the leader. */
        private void banner(String text) {
            try {
                if (engine.getCombatUI() != null) engine.getCombatUI().addMessage(1, java.awt.Color.CYAN, text);
            } catch (RuntimeException ignored) {
                // just no message
            }
        }

        void retreat(JointBattle.Joiner jn) {
            for (ShipAPI ship : engine.getShips()) {
                FleetMemberAPI m = ship.getFleetMember();
                if (m == null || !jn.toOwner.containsKey(m.getId())) continue;
                if (ship.isAlive() && !ship.isHulk() && !ship.isRetreating()) ship.setRetreating(true, false);
            }
        }
    }

    /** A ship system's state (IDLE, IN, ACTIVE, OUT, COOLDOWN), or 255 for none. */
    private static byte stateOf(ShipSystemAPI sys) {
        return (byte) (sys == null || sys.getState() == null ? 255 : sys.getState().ordinal());
    }

    /**
     * A view ship's system (or phase cloak) in the same state as the
     * leader's, set directly: the effect and its visuals run as there. A
     * system that's already running its course isn't restarted.
     */
    private static void syncSystem(ShipSystemAPI sys, int state) {
        ShipSystemAPI.SystemState[] all = ShipSystemAPI.SystemState.values();
        if (sys == null || state < 0 || state >= all.length) return;
        ShipSystemAPI.SystemState want = all[state];
        ShipSystemAPI.SystemState have = sys.getState();
        if (have == want) return;
        boolean wantOn = want == ShipSystemAPI.SystemState.IN || want == ShipSystemAPI.SystemState.ACTIVE
                || want == ShipSystemAPI.SystemState.OUT;
        boolean haveOn = have == ShipSystemAPI.SystemState.IN || have == ShipSystemAPI.SystemState.ACTIVE
                || have == ShipSystemAPI.SystemState.OUT;
        if (wantOn == haveOn) return; // both running (or both not): let it run its course
        try {
            sys.forceState(want, 0f);
        } catch (RuntimeException ignored) {
            // a system that won't be forced: no effect shown
        }
    }

    private static byte unit(float f) {
        return (byte) Math.max(0, Math.min(255, Math.round(f * 255f)));
    }

    private static float unit(byte b) {
        return (b & 0xff) / 255f;
    }

    /** Flies a ship from a joiner's controls. The leader puts the ship's own AI back if they stop coming. */
    static final class RemotePilot implements ShipAIPlugin {
        private final ShipAPI ship;
        private final int joiner;
        private final Map<Integer, Input> inputs;
        private final Map<Integer, ShipAPI> byId;
        private final int[] seen = {-1, -1, -1, -1, -1};
        private final ShipwideAIFlags flags = new ShipwideAIFlags();

        RemotePilot(ShipAPI ship, int joiner, Map<Integer, Input> inputs, Map<Integer, ShipAPI> byId) {
            this.ship = ship;
            this.joiner = joiner;
            this.inputs = inputs;
            this.byId = byId;
        }

        public void advance(float amount) {
            Input in = inputs.get(joiner);
            if (in == null || System.nanoTime() - in.arrived > INPUT_STALE_NANOS) return; // the leader switches us out
            if ((in.keys & K_W) != 0) ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
            if ((in.keys & K_S) != 0) ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
            if ((in.keys & K_A) != 0) ship.giveCommand(ShipCommand.TURN_LEFT, null, 0);
            if ((in.keys & K_D) != 0) ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
            if ((in.keys & K_Q) != 0) ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
            if ((in.keys & K_E) != 0) ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
            if ((in.keys & K_DECEL) != 0) ship.giveCommand(ShipCommand.DECELERATE, null, 0);

            List<WeaponGroupAPI> groups = ship.getWeaponGroupsCopy();
            int group = Math.max(0, Math.min(in.group, Math.max(0, groups.size() - 1)));
            if (!groups.isEmpty() && groups.indexOf(ship.getSelectedGroupAPI()) != group) {
                ship.giveCommand(ShipCommand.SELECT_GROUP, null, group);
            }
            ShipCommand[] toggles = {ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, ShipCommand.USE_SYSTEM,
                    ShipCommand.VENT_FLUX, ShipCommand.PULL_BACK_FIGHTERS, ShipCommand.TOGGLE_AUTOFIRE};
            for (int i = 0; i < TOGGLES; i++) {
                if (seen[i] >= 0 && seen[i] != in.toggles[i]) ship.giveCommand(toggles[i], null, group);
                seen[i] = in.toggles[i];
            }
            ShipAPI target = in.target > 0 ? byId.get(in.target) : null;
            if (target != null && target.isAlive()) ship.setShipTarget(target);

            Vector2f mouse = new Vector2f(in.mx, in.my);
            if (!groups.isEmpty()) {
                for (WeaponAPI w : groups.get(group).getWeaponsCopy()) aim(w, mouse);
            }
            if ((in.keys & K_FIRE) != 0) ship.giveCommand(ShipCommand.FIRE, mouse, group);
        }

        /** Turns a weapon toward the mouse, as far as its arc allows. */
        private void aim(WeaponAPI w, Vector2f mouse) {
            Vector2f at = w.getLocation();
            float want = (float) Math.toDegrees(Math.atan2(mouse.y - at.y, mouse.x - at.x));
            float center = ship.getFacing() + w.getArcFacing();
            float half = w.getArc() / 2f;
            float diff = wrap(want - center);
            if (diff > half) diff = half;
            if (diff < -half) diff = -half;
            w.setCurrAngle(center + diff);
        }

        public void setDoNotFireDelay(float amount) {
        }

        public void forceCircumstanceEvaluation() {
        }

        public boolean needsRefit() {
            return false;
        }

        public ShipwideAIFlags getAIFlags() {
            return flags;
        }

        public void cancelCurrentManeuver() {
        }

        public ShipAIConfig getConfig() {
            return new ShipAIConfig();
        }
    }

    private static float wrap(float deg) {
        while (deg > 180f) deg -= 360f;
        while (deg < -180f) deg += 360f;
        return deg;
    }

    // ================================================================ joiner: the view

    /** Ships announced by the leader, waiting for the view to spawn them (or drop them). */
    static final ConcurrentLinkedQueue<JSONObject> pending = new ConcurrentLinkedQueue<JSONObject>();
    /** The view open right now, if any. */
    static volatile Mirror view;
    /** When we last started opening a view (nanoTime), or 0: that combat is a view, never one we lead. */
    static volatile long viewOpening;

    /** True while a view of a friend's battle is opening or open. */
    static boolean isViewing() {
        return view != null || (viewOpening != 0 && System.nanoTime() - viewOpening < 60_000_000_000L);
    }

    /** Set-up for the view: an empty battlefield of the leader's size, with the view plugin. */
    static final class MirrorCreation implements BattleCreationPlugin {
        private final int leader;
        private final float w, h;

        MirrorCreation(int leader, float w, float h) {
            this.leader = leader;
            this.w = w;
            this.h = h;
        }

        public void initBattle(BattleCreationContext context, MissionDefinitionAPI api) {
            api.initFleet(FleetSide.PLAYER, "", FleetGoal.ATTACK, false);
            api.initFleet(FleetSide.ENEMY, "", FleetGoal.ATTACK, false);
            api.initMap(-w / 2f, w / 2f, -h / 2f, h / 2f);
            // The same backdrop as the battle's: we're in the same place.
            try {
                com.fs.starfarer.api.campaign.LocationAPI loc = context.getPlayerFleet() == null ? null
                        : context.getPlayerFleet().getContainingLocation();
                if (loc != null) {
                    if (loc.getBackgroundTextureFilename() != null) api.setBackgroundSpriteName(loc.getBackgroundTextureFilename());
                    if (loc.isHyperspace()) api.setHyperspaceMode(true);
                }
            } catch (RuntimeException ignored) {
                // plain backdrop
            }
            api.addPlugin(new Mirror(leader));
        }

        public void afterDefinitionLoad(CombatEngineAPI engine) {
        }
    }

    /** A view ship and the leader's latest state for it. */
    private static final class Shown {
        ShipAPI ship;
        boolean pilot;
        /** The player it belongs to, or -1. */
        int player = -1;
        /** What the leader announced: side, ally. */
        int owner;
        boolean ally;
        long spawnedAt;
        /** The leader has streamed it alive at least once. */
        boolean seenAlive;
        /** Frames traced to the log since it was placed (checks on). */
        int traced;
        // latest state
        float x, y, vx, vy, facing, angVel, hull, flux, hard, cr, shieldFacing;
        int flags, eng;
        int[] fireIdx = new int[0];
        float[] fireAngle = new float[0];
        /** Beams firing right now (from the latest update). */
        boolean[] fireBeam = new boolean[0];
        /** Shots to show, from every update since the last frame: weapon index, angle, count. */
        final List<float[]> shotsDue = new ArrayList<float[]>();
        int sys = 255, cloak = 255;
        long arrived;
        boolean hasState;
    }

    /** The joiner's view of the leader's battle. */
    static final class Mirror extends BaseEveryFrameCombatPlugin {
        private final int leader;
        private CombatEngineAPI engine;
        private final Map<Integer, Shown> shown = new HashMap<Integer, Shown>();
        private Shown pilot;
        private float leaderTime = 1f;
        private boolean leaderPaused;
        private long lastPacket = System.nanoTime();
        private long nextInput;
        private int inputSeq;
        private int group;
        private int target;
        private final int[] toggles = new int[TOGGLES];
        private boolean wasRmb, wasF, wasV, wasZ, wasT, wasR;
        private volatile boolean ending;
        /** Ships in the stream we haven't been told about (or couldn't make), and how often we've asked. */
        private final Set<Integer> unknown = new HashSet<Integer>();
        private final Map<Integer, Integer> asked = new HashMap<Integer, Integer>();
        private long nextAsk, nextClean;
        /** Every ship this view made. Anything else on the field isn't the leader's battle. */
        private final Set<ShipAPI> ours = java.util.Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
        private long openedAt;
        private boolean synced;
        /**
         * The game shows our flown ship as on autopilot from the start (it has
         * the view's do-nothing AI). That isn't the player's choice: only once
         * autopilot has been off does turning it on count.
         */
        private boolean autopilotAtStart;
        private long nextCheck, nextReserves, nextOrders, ordersSentAt;
        private String ordersSent = "";
        private boolean retreatSent;
        /** Our real fleet's member ids: what our deployment panel deploys. */
        private final Set<String> realIds = new HashSet<String>();
        /** Ships we deployed here, waiting for the leader to bring them in: our member id -> ship. */
        private final Map<String, ShipAPI> adopting = new HashMap<String, ShipAPI>();
        private final Map<String, Long> adoptingSince = new HashMap<String, Long>();
        private final Set<String> requested = new HashSet<String>();
        /** Our member ids already in the leader's battle (announced to us as ours). */
        private final Set<String> inBattle = new HashSet<String>();
        /** Fleets the view's ships belong to (by side and whose), so the game sees proper ships, not strays. */
        private final Map<Integer, com.fs.starfarer.api.campaign.CampaignFleetAPI> fleets =
                new HashMap<Integer, com.fs.starfarer.api.campaign.CampaignFleetAPI>();

        Mirror(int leader) {
            this.leader = leader;
        }

        @Override
        public void init(CombatEngineAPI engine) {
            this.engine = engine;
            engine.setDoNotEndCombat(true);
            view = this;
            viewOpening = 0;
            openedAt = System.nanoTime();
            try {
                com.fs.starfarer.api.campaign.CampaignFleetAPI me = Global.getSector().getPlayerFleet();
                if (me != null) for (FleetMemberAPI m : me.getFleetData().getMembersListCopy()) realIds.add(m.getId());
            } catch (RuntimeException ignored) {
                // nothing to deploy from here; the leader brings everything in
            }
            try {
                com.fs.starfarer.api.campaign.CampaignFleetAPI ctx = engine.getContext() == null ? null
                        : engine.getContext().getPlayerFleet();
                log.info("[MultiSector] watching " + Session.get().playerName(leader) + "'s battle; player fleet here is "
                        + (ctx == null ? "none" : ctx == Global.getSector().getPlayerFleet() ? "our campaign fleet" : ctx.getName())
                        + ", " + realIds.size() + " of our ships can deploy");
            } catch (RuntimeException e) {
                log.info("[MultiSector] watching " + Session.get().playerName(leader) + "'s battle");
            }
        }

        /** The leader's battle is over: close the view. */
        void end() {
            ending = true;
        }

        @Override
        public void advance(float amount, List<InputEventAPI> events) {
            if (engine == null) return;
            Session s = Session.get();
            try {
                JointBattle.handleMessages(s);
                long now = System.nanoTime();
                if (ending || now - lastPacket > 20_000_000_000L || !s.isActive()) {
                    if (view == this) view = null;
                    viewOpening = 0;
                    engine.setDoNotEndCombat(false);
                    engine.endCombat(0.5f);
                    engine = null;
                    return;
                }
                JSONObject spec;
                while ((spec = pending.poll()) != null) announce(spec);
                readStates(s);
                askAgain(s, now);
                clean(now);
                followSpeed();
                followPilot(s);
                sendOrders(s, now);
                int me = s.getMyId();
                for (Map.Entry<Integer, Shown> te : shown.entrySet()) {
                    Shown sh = te.getValue();
                    apply(sh, now);
                    if (sh.traced < 10 && sh.ship != null && MultiSectorConfig.isJointBattleChecks()) trace(te.getKey(), sh);
                    if (sh.ship != null && sh.player >= 0) mark(sh.ship, sh.player); // every player's ships, ours too
                }
                if (!synced) checkSynced(s, now);
                if (MultiSectorConfig.isJointBattleChecks()) check(now);
                if (pilot != null && pilot.ship != null) controls(s, now);
                for (ShipAPI waiting : adopting.values()) {
                    for (ShipCommand c : ShipCommand.values()) waiting.blockCommandForOneFrame(c);
                }
            } catch (Exception e) {
                log.warn("[MultiSector] battle view frame failed", e);
            }
        }

        @Override
        public void renderInWorldCoords(ViewportAPI viewport) {
            rings(engine, -1); // every player's ships, ours too: the same colours on every game
        }

        /** The fleet a view ship belongs to: one per side and whose, never in any location. */
        private com.fs.starfarer.api.campaign.CampaignFleetAPI fleetFor(int owner, boolean mine) {
            int key = owner * 2 + (mine ? 1 : 0);
            com.fs.starfarer.api.campaign.CampaignFleetAPI f = fleets.get(key);
            if (f == null) {
                f = Global.getFactory().createEmptyFleet(owner == 1 ? "pirates" : "player",
                        mine ? "Your fleet" : owner == 1 ? "Enemy" : "Allies", true);
                fleets.put(key, f);
            }
            return f;
        }

        /**
         * We took the helm of another of our ships here (transfer command):
         * the leader puts our controls on that one.
         */
        private void followPilot(Session s) {
            ShipAPI ps = engine.getPlayerShip();
            if (ps == null || (pilot != null && pilot.ship == ps)) return;
            int me = s.getMyId();
            for (Map.Entry<Integer, Shown> e : shown.entrySet()) {
                Shown sh = e.getValue();
                if (sh.ship != ps || sh.player != me) continue;
                if (pilot != null) {
                    pilot.pilot = false;
                    if (pilot.ship != null && pilot.ship.isAlive()) pilot.ship.setShipAI(new Idle()); // the stream moves it again
                }
                sh.pilot = true;
                pilot = sh;
                autopilotAtStart = engine.isUIAutopilotOn();
                try {
                    s.sendBattleMessage(leader, JointBattle.msg("cpilot", 0).put("id", e.getKey()).toString());
                } catch (Exception ignored) {
                    // next change
                }
                note("You take the helm of " + (ps.getFleetMember() != null ? ps.getFleetMember().getShipName() : "this ship"));
                return;
            }
        }

        /**
         * The orders we give our ships here (command map) go to the leader,
         * who enforces them on the real ones. Sent when they change, and every
         * 3 s regardless. A full retreat here orders ours out there.
         */
        private void sendOrders(Session s, long now) {
            if (now - nextOrders < 0) return;
            nextOrders = now + 500_000_000L;
            int me = s.getMyId();
            CombatFleetManagerAPI fm = engine.getFleetManager(0);
            if (fm == null) return;
            com.fs.starfarer.api.combat.CombatTaskManagerAPI tm = fm.getTaskManager(false);
            if (tm == null) return;
            if (tm.isInFullRetreat() && !retreatSent) {
                retreatSent = true;
                s.sendBattleMessage(leader, JointBattle.msg("retreat", 0).toString());
                note("Your ships pull out of the battle");
            }
            Map<ShipAPI, Integer> idOf = new IdentityHashMap<ShipAPI, Integer>();
            for (Map.Entry<Integer, Shown> e : shown.entrySet()) if (e.getValue().ship != null) idOf.put(e.getValue().ship, e.getKey());
            org.json.JSONArray list = new org.json.JSONArray();
            try {
                for (Map.Entry<Integer, Shown> e : shown.entrySet()) {
                    Shown sh = e.getValue();
                    if (sh.ship == null || sh.player != me || sh.ship.isHulk()) continue;
                    CombatFleetManagerAPI.AssignmentInfo info = tm.getAssignmentFor(sh.ship);
                    JSONObject o = new JSONObject().put("id", e.getKey());
                    if (info != null && info.getType() != null) {
                        o.put("t", info.getType().name());
                        com.fs.starfarer.api.combat.AssignmentTargetAPI t = info.getTarget();
                        if (t instanceof com.fs.starfarer.api.combat.DeployedFleetMemberAPI) {
                            ShipAPI ts = ((com.fs.starfarer.api.combat.DeployedFleetMemberAPI) t).getShip();
                            Integer tid = ts == null ? null : idOf.get(ts);
                            if (tid != null) o.put("tgt", tid);
                        }
                        if (t != null && t.getLocation() != null && !o.has("tgt")) {
                            o.put("x", Math.round(t.getLocation().x)).put("y", Math.round(t.getLocation().y));
                        }
                    } else {
                        o.put("t", "");
                    }
                    list.put(o);
                }
                String sig = list.toString();
                if (sig.equals(ordersSent) && now - ordersSentAt < 3_000_000_000L) return;
                ordersSent = sig;
                ordersSentAt = now;
                s.sendBattleMessage(leader, JointBattle.msg("corders", 0).put("list", list).toString());
            } catch (Exception ignored) {
                // next tick
            }
        }

        private void announce(JSONObject j) throws Exception {
            String k = j.optString("k");
            int id = j.optInt("id");
            if ("cgone".equals(k)) {
                Shown sh = shown.remove(id);
                if (sh != null && sh.ship != null && !sh.ship.isHulk()) removeCleanly(sh.ship);
                if (sh == pilot) pilot = null;
                return;
            }
            if (shown.containsKey(id)) return;
            int side = j.optInt("owner");
            if (side != 0 && side != 1) return; // a wreck (owner 100): neither side's, and nothing to show moving
            String real = j.optString("real", "");
            ShipAPI ours0 = real.isEmpty() ? null : adopting.remove(real);
            if (ours0 != null) {
                adoptingSince.remove(real);
                adopt(id, ours0, j);
                return;
            }
            List<String> missing = new ArrayList<String>();
            FleetMemberAPI m = ShipJson.buildShip(j.getJSONObject("ship"), missing);
            if (m == null) {
                log.warn("[MultiSector] battle view can't make " + j.optString("name") + " (missing here: " + missing + ")");
                return;
            }
            if (j.has("name")) m.setShipName(j.optString("name"));
            int owner = side;
            boolean mine = j.optBoolean("mine");
            // In a fleet, with real readiness: a member in no fleet shows as mothballed with no readiness on the
            // command map, and can't be commanded or flown.
            fleetFor(owner, mine).getFleetData().addFleetMember(m);
            m.getRepairTracker().setCR((float) Math.max(0.05, j.optDouble("cr", 0.7)));
            // A new member is on the player's side, not an ally, until told otherwise: enemies came out as allies.
            m.setOwner(owner);
            m.setAlly(owner == 0 && !mine);
            CombatFleetManagerAPI fm = engine.getFleetManager(owner);
            ShipAPI ship = fm.spawnFleetMember(m, new Vector2f((float) j.optDouble("x"), (float) j.optDouble("y")),
                    (float) j.optDouble("f", 90), 0f);
            if (ship == null) {
                log.warn("[MultiSector] battle view couldn't place " + j.optString("name") + " (side " + owner + ")");
                return;
            }
            ours.add(ship);
            fm.removeFromReserves(m);
            ship.setShipAI(new Idle());
            immune(ship.getMutableStats(), true);
            if (ship.getOwner() != owner) ship.setOwner(owner);
            ship.setAlly(owner == 0 && !mine);
            ship.setCurrentCR(m.getRepairTracker().getCR());
            register(id, ship, j, owner, mine);
        }

        /** A ship we deployed here, now in the leader's battle: it's that one. */
        private void adopt(int id, ShipAPI ship, JSONObject j) {
            ship.setShipAI(new Idle());
            immune(ship.getMutableStats(), true);
            if (ship.getOwner() != 0) ship.setOwner(0);
            ship.setAlly(false);
            register(id, ship, j, 0, true);
            log.info("[MultiSector] " + ship.getFleetMember().getShipName() + " is in " + Session.get().playerName(leader)
                    + "'s battle");
        }

        private void register(int id, ShipAPI ship, JSONObject j, int owner, boolean mine) {
            if (mine && !j.optString("real", "").isEmpty()) inBattle.add(j.optString("real"));
            if (ship.getOriginalOwner() != owner) ship.setOriginalOwner(owner);
            int player = j.optInt("player", -1);
            if (player >= 0) ship.setCustomData(PLAYER_TAG, player);
            Shown sh = new Shown();
            sh.ship = ship;
            sh.pilot = j.optBoolean("pilot");
            sh.player = player;
            sh.owner = owner;
            sh.ally = owner == 0 && !mine;
            sh.spawnedAt = System.nanoTime();
            shown.put(id, sh);
            if (sh.pilot && (pilot == null || pilot.ship == null || !pilot.ship.isAlive())) {
                pilot = sh;
                if (engine.getPlayerShip() != ship) engine.setPlayerShipExternal(ship);
                autopilotAtStart = engine.isUIAutopilotOn();
            }
        }

        /**
         * Once every ship of the battle is here and placed from the stream,
         * our own ships can come in: tell the leader. Until then they're
         * not in the battle at all, so nothing of ours is ever out of step.
         */
        private void checkSynced(Session s, long now) {
            if (now - openedAt < 1_500_000_000L) return;
            boolean placed = !shown.isEmpty();
            for (Shown sh : shown.values()) if (!sh.hasState) placed = false;
            if (!placed && now - openedAt < 8_000_000_000L) return;
            synced = true;
            CombatFleetManagerAPI fm = engine.getFleetManager(0);
            boolean canDeploy = !requested.isEmpty();
            if (fm != null) for (FleetMemberAPI m : fm.getReservesCopy()) if (realIdOf(m) != null) canDeploy = true;
            if (!canDeploy && fm != null) {
                // Nothing in our deployment list yet: put our ships there, rather than bring them all in for us.
                try {
                    com.fs.starfarer.api.campaign.CampaignFleetAPI me = Global.getSector().getPlayerFleet();
                    for (FleetMemberAPI m : me.getFleetData().getMembersListCopy()) {
                        if (m.isFighterWing() || m.getRepairTracker().isMothballed() || inBattle.contains(m.getId())) continue;
                        fm.addToReserves(m);
                        canDeploy = true;
                    }
                } catch (RuntimeException e) {
                    log.warn("[MultiSector] couldn't list our ships for deployment in the battle view", e);
                }
                if (canDeploy) log.info("[MultiSector] put our ships in the battle view's deployment list");
            }
            if (!canDeploy) {
                // Never bring our fleet in for us: only what we deploy goes in.
                note("Caught up with " + Session.get().playerName(leader) + "'s battle, but none of your ships can deploy");
                log.info("[MultiSector] battle view caught up (" + shown.size() + " ships); none of our ships can deploy");
                return;
            }
            note("Caught up with " + Session.get().playerName(leader) + "'s battle. Deploy your ships"
                    + " (deployment panel, or Deploy on the command map): only what you deploy goes in");
            log.info("[MultiSector] battle view caught up (" + shown.size() + " ships); waiting for our deployment");
        }

        /**
         * A ship of ours that our deployment panel just put on the field:
         * it holds still here and the leader brings exactly it in, where we
         * put it. When the leader announces it, it becomes that ship.
         */
        private void deployed(Session s, ShipAPI ship, String id) {
            FleetMemberAPI m = ship.getFleetMember();
            if (!requested.add(id)) return;
            ours.add(ship);
            ship.setShipAI(new Idle());
            immune(ship.getMutableStats(), true);
            adopting.put(id, ship);
            adoptingSince.put(id, System.nanoTime());
            try {
                org.json.JSONArray list = new org.json.JSONArray().put(new JSONObject().put("id", id)
                        .put("x", ship.getLocation().x).put("y", ship.getLocation().y).put("f", ship.getFacing()));
                s.sendBattleMessage(leader, JointBattle.msg("cdeploy", 0).put("ships", list).toString());
            } catch (Exception e) {
                log.warn("[MultiSector] couldn't ask for " + m.getShipName(), e);
            }
            log.info("[MultiSector] deploying " + m.getShipName() + " into " + Session.get().playerName(leader) + "'s battle (it appeared"
                    + " on our side here at t=" + Math.round(engine.getTotalElapsedTime(false)) + "s, dialog open: "
                    + engine.isUIShowingDialog() + ")" + where());
        }

        /**
         * A new view ship's first frames (1, 2, 5, 10), for the log: whether it
         * turned into a wreck right after being placed (how it was placed), on
         * its first update (a bad update), or later (the view deciding it).
         */
        private void trace(int id, Shown sh) {
            sh.traced++;
            if (sh.traced != 1 && sh.traced != 2 && sh.traced != 5 && sh.traced != 10) return;
            ShipAPI ship = sh.ship;
            FleetMemberAPI m = ship.getFleetMember();
            boolean real = m != null && realIds.contains(m.getId());
            log.info("[MultiSector] view ship " + id + " " + ship.getHullSpec().getHullId() + " frame " + sh.traced
                    + ": hulk=" + ship.isHulk() + " hp=" + Math.round(ship.getHitpoints()) + "/" + Math.round(ship.getMaxHitpoints())
                    + " owner=" + ship.getOwner() + " original=" + ship.getOriginalOwner() + " ally=" + ship.isAlly()
                    + " member=" + (m == null ? "none" : real ? "our campaign ship" : "view copy, fleet "
                    + (m.getFleetData() != null && m.getFleetData().getFleet() != null ? m.getFleetData().getFleet().getName() : "none"))
                    + " memberCR=" + (m == null ? "-" : String.valueOf(Math.round(m.getRepairTracker().getCR() * 100)))
                    + " cr=" + Math.round(ship.getCurrentCR() * 100) + " updates=" + sh.hasState
                    + (sh.hasState ? " leaderSaysWreck=" + ((sh.flags & F_HULK) != 0) : ""));
        }

        /**
         * Every 5 s: every ship as the view has it, by the leader's ids
         * (compare with their "battle check"), and any that differ from what
         * the leader says: side, ally, wrecked, readiness, hull.
         */
        private void check(long now) {
            if (now - nextCheck < 0) return;
            nextCheck = now + 5_000_000_000L;
            int noState = 0;
            List<String> wrong = new ArrayList<String>();
            StringBuilder b = new StringBuilder();
            for (Map.Entry<Integer, Shown> e : shown.entrySet()) {
                Shown sh = e.getValue();
                ShipAPI ship = sh.ship;
                if (ship == null) continue;
                if (!sh.hasState) noState++;
                b.append("\n  ").append(e.getKey()).append(' ').append(ship.getHullSpec().getHullId())
                        .append(" o=").append(ship.getOwner()).append(" a=").append(ship.isAlly() ? 1 : 0)
                        .append(" p=").append(sh.player)
                        .append(" cr=").append(Math.round(ship.getCurrentCR() * 100))
                        .append(" h=").append(Math.round(ship.getHullLevel() * 100))
                        .append(' ').append(ship.isHulk() ? "hulk" : !ship.isAlive() ? "dead" : "ok")
                        .append(' ').append(aiName(ship)).append(sh.hasState ? "" : " NO-STATE");
                if (!sh.hasState) continue;
                String id = e.getKey() + " " + ship.getHullSpec().getHullId() + ": ";
                if (ship.getOwner() != sh.owner) wrong.add(id + "side " + ship.getOwner() + ", leader says " + sh.owner);
                if (ship.isAlly() != sh.ally) wrong.add(id + "ally " + ship.isAlly() + ", leader says " + sh.ally);
                boolean hulk = (sh.flags & F_HULK) != 0;
                if (ship.isHulk() != hulk) wrong.add(id + (ship.isHulk() ? "wrecked here, alive on the leader's game"
                        : "alive here, wrecked on the leader's game"));
                if (!hulk && Math.abs(ship.getCurrentCR() - sh.cr) > 0.1f) {
                    wrong.add(id + "CR " + Math.round(ship.getCurrentCR() * 100) + "%, leader says " + Math.round(sh.cr * 100) + "%");
                }
                if (!hulk && Math.abs(ship.getHullLevel() - sh.hull) > 0.15f) {
                    wrong.add(id + "hull " + Math.round(ship.getHullLevel() * 100) + "%, leader says " + Math.round(sh.hull * 100) + "%");
                }
            }
            log.info("[MultiSector] view check, " + shown.size() + " ships (" + noState + " without updates, "
                    + unknown.size() + " streamed but unknown) (id hull owner ally player CR hull state ai):" + b);
            if (!wrong.isEmpty()) {
                StringBuilder w = new StringBuilder("[MultiSector] view differs from the leader's battle (" + wrong.size() + "):");
                for (int i = 0; i < wrong.size() && i < 30; i++) w.append("\n  ").append(wrong.get(i));
                log.warn(w.toString());
            }
        }

        private void note(String text) {
            try {
                if (engine.getCombatUI() != null) engine.getCombatUI().addMessage(1, java.awt.Color.CYAN, text);
            } catch (RuntimeException ignored) {
                // just no message
            }
        }

        /** Ships the leader streams but we never got (or couldn't make): ask for them again, a few times. */
        private void askAgain(Session s, long now) {
            if (unknown.isEmpty() || now - nextAsk < 0) return;
            nextAsk = now + 1_000_000_000L;
            org.json.JSONArray want = new org.json.JSONArray();
            for (Integer id : unknown) {
                int n = asked.containsKey(id) ? asked.get(id) : 0;
                if (n >= 3) continue;
                asked.put(id, n + 1);
                want.put(id);
                if (want.length() >= 40) break;
            }
            unknown.clear();
            if (want.length() == 0) return;
            try {
                s.sendBattleMessage(leader, JointBattle.msg("cwho", 0).put("ids", want).toString());
            } catch (Exception ignored) {
                // next second
            }
        }

        /** Off the field and out of its side's deployed list, so nothing (orders included) still points at it. */
        private void removeCleanly(ShipAPI ship) {
            try {
                CombatFleetManagerAPI fm = engine.getFleetManager(ship.getOwner());
                if (fm != null && fm.getDeployedFleetMember(ship) != null) fm.removeDeployed(ship, false);
            } catch (RuntimeException ignored) {
                // removing it from the field is what matters
            }
            engine.removeEntity(ship);
        }

        /**
         * Our campaign fleet's member id for a ship record here: the record
         * itself, or, if the battle made a copy of it, the ship it copies
         * (same hull and name). Null if it isn't ours.
         */
        private String realIdOf(FleetMemberAPI m) {
            if (m == null) return null;
            if (realIds.contains(m.getId())) return m.getId();
            try {
                for (FleetMemberAPI r : Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy()) {
                    if (r.getHullId().equals(m.getHullId()) && r.getShipName() != null && r.getShipName().equals(m.getShipName())) {
                        return r.getId();
                    }
                }
            } catch (RuntimeException ignored) {
                // not ours
            }
            return null;
        }

        /** Only the leader's battle on this field: nothing deployed here by the game itself. */
        private void clean(long now) {
            Session s = Session.get();
            for (ShipAPI ship : new ArrayList<ShipAPI>(engine.getShips())) {
                if (ship.isFighter() || ship.isHulk() || ship.getParentStation() != null || ours.contains(ship)) continue;
                FleetMemberAPI m = ship.getFleetMember();
                String real = ship.getOwner() == 0 ? realIdOf(m) : null;
                if (real != null) {
                    if (inBattle.contains(real) || requested.contains(real)) {
                        // Already in the leader's battle (or on its way): a second one here would be a duplicate.
                        log.info("[MultiSector] " + m.getShipName() + " is already in the battle; not deploying it again");
                        removeCleanly(ship);
                        continue;
                    }
                    deployed(s, ship, real); // our deployment panel: the leader brings exactly this one in
                    continue;
                }
                log.info("[MultiSector] removing a ship the battle view didn't make: "
                        + (m != null ? m.getShipName() : ship.getHullSpec().getHullId()));
                removeCleanly(ship);
            }
            for (Map.Entry<String, Long> e : new ArrayList<Map.Entry<String, Long>>(adoptingSince.entrySet())) {
                if (now - e.getValue() < 15_000_000_000L) continue;
                ShipAPI ship = adopting.remove(e.getKey());
                adoptingSince.remove(e.getKey());
                if (ship != null) {
                    note((ship.getFleetMember() != null ? ship.getFleetMember().getShipName() : "A ship")
                            + " couldn't join the battle");
                    engine.removeEntity(ship);
                }
            }
            if (now - nextClean < 0) return;
            nextClean = now + 500_000_000L;
            for (int side = 0; side <= 1; side++) {
                CombatFleetManagerAPI fm = engine.getFleetManager(side);
                if (fm == null) continue;
                // Only our own ships wait in reserve here (for the deployment panel), and only until deployed.
                for (FleetMemberAPI m : fm.getReservesCopy()) {
                    String real = side == 0 ? realIdOf(m) : null;
                    if (real == null || requested.contains(real) || inBattle.contains(real)) fm.removeFromReserves(m);
                }
            }
            for (Shown sh : shown.values()) {
                // The command map reads readiness from the ship's record: keep it with the leader's.
                if (sh.ship == null || !sh.hasState || sh.ship.getFleetMember() == null || requested.contains(sh.ship.getFleetMember().getId())) continue;
                FleetMemberAPI m = sh.ship.getFleetMember();
                if (Math.abs(m.getRepairTracker().getCR() - sh.cr) > 0.05f) m.getRepairTracker().setCR(Math.max(0.01f, sh.cr));
            }
        }

        private void readStates(Session s) {
            Session.CombatPacket p;
            for (int n = 0; n < 200 && (p = s.pollCombatPacket()) != null; n++) {
                if (p.from != leader) continue;
                ByteBuffer bb = p.data;
                if (bb.remaining() < 1 || bb.get() != STATE) continue;
                bb.getInt(); // seq: datagrams are independent snapshots of their ships
                leaderTime = bb.getFloat();
                leaderPaused = bb.get() != 0;
                int count = bb.getShort();
                long now = System.nanoTime();
                lastPacket = now;
                for (int i = 0; i < count; i++) {
                    int id = bb.getShort();
                    Shown sh = shown.get(id);
                    if (sh == null) {
                        unknown.add(id); // not announced (or lost): ask again
                        sh = new Shown(); // read and drop
                    }
                    sh.x = bb.getFloat();
                    sh.y = bb.getFloat();
                    sh.vx = bb.getFloat();
                    sh.vy = bb.getFloat();
                    sh.facing = bb.getFloat();
                    sh.angVel = bb.getFloat();
                    sh.hull = unit(bb.get());
                    sh.flux = unit(bb.get());
                    sh.hard = unit(bb.get());
                    sh.cr = unit(bb.get());
                    sh.flags = bb.get() & 0xff;
                    sh.eng = bb.get() & 0xff;
                    sh.shieldFacing = bb.getShort() / 10f;
                    sh.sys = bb.get() & 0xff;
                    sh.cloak = bb.get() & 0xff;
                    int nf = bb.get() & 0xff;
                    sh.fireIdx = new int[nf];
                    sh.fireAngle = new float[nf];
                    sh.fireBeam = new boolean[nf];
                    for (int f = 0; f < nf; f++) {
                        sh.fireIdx[f] = bb.get() & 0xff;
                        sh.fireAngle[f] = bb.getShort() / 10f;
                        int fired = bb.get() & 0xff;
                        sh.fireBeam[f] = fired == 0;
                        if (fired > 0 && sh.shotsDue.size() < 200) {
                            sh.shotsDue.add(new float[]{sh.fireIdx[f], sh.fireAngle[f], fired});
                        }
                    }
                    sh.arrived = now;
                    sh.hasState = true;
                }
            }
        }

        /** Same combat speed and pause as the leader, whatever speed-up mod either of us has. */
        private void followSpeed() {
            engine.getTimeMult().unmodify(TIME_ID);
            float local = engine.getTimeMult().getModifiedValue();
            if (local > 0.01f && Math.abs(leaderTime - local) > 0.01f) {
                engine.getTimeMult().modifyMult(TIME_ID, leaderTime / local);
            }
            // Follow the leader's pause, but don't unpause under our own dialog (the deployment panel).
            if (leaderPaused != engine.isPaused() && (leaderPaused || !engine.isUIShowingDialog())) engine.setPaused(leaderPaused);
        }

        private void apply(Shown sh, long now) {
            ShipAPI ship = sh.ship;
            if (ship == null || !sh.hasState) return;
            if ((sh.flags & F_HULK) == 0) sh.seenAlive = true;
            if ((sh.flags & F_HULK) != 0) {
                // Only a ship the leader had alive, and not in its first second here: never wreck a ship on a
                // stale or early update (that left the view full of "scuttled" ships).
                boolean settled = sh.seenAlive && now - sh.spawnedAt > 1_000_000_000L;
                if (settled && !ship.isHulk() && ship.isAlive()) {
                    immune(ship.getMutableStats(), false);
                    engine.applyDamage(ship, ship.getLocation(), ship.getMaxHitpoints() * 20f + 100000f,
                            DamageType.HIGH_EXPLOSIVE, 0f, true, false, null);
                }
                return;
            }
            if (ship.isHulk()) return;
            // Where it is now, predicted from the last update (in real seconds, at the leader's speed).
            float ahead = Math.min((now - sh.arrived) / 1e9f, 0.25f) * (leaderPaused ? 0f : leaderTime);
            float tx = sh.x + sh.vx * ahead;
            float ty = sh.y + sh.vy * ahead;
            Vector2f pos = ship.getLocation();
            float dx = tx - pos.x, dy = ty - pos.y;
            if (dx * dx + dy * dy > 300f * 300f) pos.set(tx, ty);
            else pos.set(pos.x + dx * 0.5f, pos.y + dy * 0.5f);
            ship.getVelocity().set(sh.vx, sh.vy);
            ship.setFacing(sh.facing + sh.angVel * ahead);
            ship.setAngularVelocity(sh.angVel);
            ship.setHitpoints(Math.max(1f, sh.hull * ship.getMaxHitpoints()));
            if (Math.abs(ship.getCurrentCR() - sh.cr) > 0.01f) ship.setCurrentCR(sh.cr);
            FluxTrackerAPI ft = ship.getFluxTracker();
            ft.setCurrFlux(sh.flux * ft.getMaxFlux());
            ft.setHardFlux(sh.hard * ft.getMaxFlux());
            ShieldAPI shield = ship.getShield();
            if (shield != null) {
                boolean on = (sh.flags & F_SHIELD) != 0;
                if (on && !shield.isOn()) shield.toggleOn();
                if (!on && shield.isOn()) shield.toggleOff();
                if (on) shield.forceFacing(sh.shieldFacing);
            }
            if ((sh.flags & F_OVERLOAD) != 0 && !ft.isOverloaded()) ft.forceOverload(0f);
            // Systems, phase cloak and venting are set directly, as on the leader's game (commands could be refused here).
            syncSystem(ship.getSystem(), sh.sys);
            syncSystem(ship.getPhaseCloak(), sh.cloak);
            boolean phased = (sh.flags & F_PHASED) != 0;
            if (ship.isPhased() != phased) ship.setPhased(phased);
            if ((sh.flags & F_VENT) != 0 && !ft.isVenting() && !ft.isOverloaded()) ft.ventFlux();
            if (!sh.pilot) {
                // Engine flames: same commands as the leader's ship (the move itself is overwritten above).
                if ((sh.eng & E_ACCEL) != 0) ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
                if ((sh.eng & E_BACK) != 0) ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
                if ((sh.eng & E_DECEL) != 0) ship.giveCommand(ShipCommand.DECELERATE, null, 0);
                if ((sh.eng & E_LEFT) != 0) ship.giveCommand(ShipCommand.TURN_LEFT, null, 0);
                if ((sh.eng & E_RIGHT) != 0) ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
                if ((sh.eng & E_SLEFT) != 0) ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
                if ((sh.eng & E_SRIGHT) != 0) ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
            } else if ((sh.eng & E_ACCEL) != 0 && ship.getEngineController() != null) {
                ship.getEngineController().forceShowAccelerating();
            }
            List<WeaponAPI> weapons = ship.getAllWeapons();
            boolean beams = false;
            boolean fresh = now - sh.arrived < 250_000_000L;
            for (int i = 0; i < sh.fireIdx.length; i++) {
                if (sh.fireIdx[i] >= weapons.size()) continue;
                WeaponAPI w = weapons.get(sh.fireIdx[i]);
                w.setCurrAngle(sh.fireAngle[i]);
                if (sh.fireBeam[i] && fresh) {
                    w.setForceFireOneFrame(true); // beams fire as long as the leader's do
                    beams = true;
                }
            }
            // Exactly the shots the leader's ships fired: projectiles and missiles fly and hit here, nothing takes damage.
            for (float[] shot : sh.shotsDue) {
                int idx = (int) shot[0];
                if (idx >= weapons.size()) continue;
                WeaponAPI w = weapons.get(idx);
                if (w.isBeam()) continue;
                w.setCurrAngle(shot[1]);
                for (int k = 0; k < Math.min((int) shot[2], 8); k++) {
                    try {
                        Vector2f from = w.getFirePoint(0) != null ? w.getFirePoint(0) : w.getLocation();
                        if (from == null) break;
                        engine.spawnProjectile(ship, w, w.getId(), new Vector2f(from), shot[1], new Vector2f(ship.getVelocity()));
                    } catch (RuntimeException e) {
                        break; // a weapon that can't be spawned this way: no shot shown
                    }
                }
            }
            sh.shotsDue.clear();
            // Nothing fires here on its own (autofire): only what the leader's ships fired.
            if (!beams) ship.setHoldFireOneFrame(true);
        }

        /** Our piloted ship: its controls go to the leader, not to it. */
        private void controls(Session s, long now) {
            ShipAPI ship = pilot.ship;
            // Everything goes to the leader, except picking a weapon group: that happens here too, so it shows.
            for (ShipCommand c : ShipCommand.values()) if (c != ShipCommand.SELECT_GROUP) ship.blockCommandForOneFrame(c);
            if (engine.isUIShowingDialog()) return; // the game's own menus have the keyboard
            boolean rmb = Mouse.isButtonDown(1);
            if (rmb && !wasRmb) toggles[0]++;
            wasRmb = rmb;
            wasF = edge(Keyboard.KEY_F, wasF, 1);
            wasV = edge(Keyboard.KEY_V, wasV, 2);
            wasZ = edge(Keyboard.KEY_Z, wasZ, 3);
            wasT = edge(Keyboard.KEY_T, wasT, 4);
            for (int g = 0; g < 7; g++) if (Keyboard.isKeyDown(Keyboard.KEY_1 + g)) group = g;
            int selected = ship.getWeaponGroupsCopy().indexOf(ship.getSelectedGroupAPI());
            if (selected >= 0) group = selected;
            ViewportAPI vp = engine.getViewport();
            // The game's mouse position is in UI pixels (scaled), which is what the viewport expects.
            float mx = vp.convertScreenXToWorldX(Global.getSettings().getMouseX());
            float my = vp.convertScreenYToWorldY(Global.getSettings().getMouseY());
            boolean r = Keyboard.isKeyDown(Keyboard.KEY_R);
            if (r && !wasR) target = nearestEnemy(mx, my);
            wasR = r;
            if (now - nextInput < 0) return;
            nextInput = now + INPUT_NANOS;
            int keys = 0;
            if (Keyboard.isKeyDown(Keyboard.KEY_W)) keys |= K_W;
            if (Keyboard.isKeyDown(Keyboard.KEY_S)) keys |= K_S;
            if (Keyboard.isKeyDown(Keyboard.KEY_A)) keys |= K_A;
            if (Keyboard.isKeyDown(Keyboard.KEY_D)) keys |= K_D;
            if (Keyboard.isKeyDown(Keyboard.KEY_Q)) keys |= K_Q;
            if (Keyboard.isKeyDown(Keyboard.KEY_E)) keys |= K_E;
            if (Keyboard.isKeyDown(Keyboard.KEY_X)) keys |= K_DECEL;
            if (Mouse.isButtonDown(0)) keys |= K_FIRE;
            boolean autopilotUi = engine.isUIAutopilotOn();
            if (!autopilotUi) autopilotAtStart = false;
            if (autopilotUi && !autopilotAtStart) keys = K_AUTO; // the player's autopilot: its AI flies it on the leader's game
            ByteBuffer bb = ByteBuffer.allocate(64);
            bb.put(INPUT).putInt(inputSeq++).putShort((short) idOf(pilot)).putInt(keys).putFloat(mx).putFloat(my)
                    .put((byte) group).putShort((short) target);
            for (int t : toggles) bb.put((byte) t);
            bb.flip();
            s.sendCombatPacket(leader, bb);
        }

        private boolean edge(int key, boolean was, int toggle) {
            boolean down = Keyboard.isKeyDown(key);
            if (down && !was) toggles[toggle]++;
            return down;
        }

        private int idOf(Shown sh) {
            for (Map.Entry<Integer, Shown> e : shown.entrySet()) if (e.getValue() == sh) return e.getKey();
            return 0;
        }

        private int nearestEnemy(float x, float y) {
            int best = 0;
            float bestD = 600f * 600f;
            for (Map.Entry<Integer, Shown> e : shown.entrySet()) {
                ShipAPI ship = e.getValue().ship;
                if (ship == null || ship.getOwner() != 1 || ship.isHulk()) continue;
                float dx = ship.getLocation().x - x, dy = ship.getLocation().y - y;
                float d = dx * dx + dy * dy;
                if (d < bestD) {
                    bestD = d;
                    best = e.getKey();
                }
            }
            return best;
        }
    }

    /** Shots land on view ships, but only the leader's game decides what they did. */
    private static void immune(MutableShipStatsAPI stats, boolean on) {
        if (on) {
            stats.getHullDamageTakenMult().modifyMult(IMMUNE, 0f);
            stats.getArmorDamageTakenMult().modifyMult(IMMUNE, 0f);
            stats.getShieldDamageTakenMult().modifyMult(IMMUNE, 0f);
            stats.getEmpDamageTakenMult().modifyMult(IMMUNE, 0f);
        } else {
            stats.getHullDamageTakenMult().unmodify(IMMUNE);
            stats.getArmorDamageTakenMult().unmodify(IMMUNE);
            stats.getShieldDamageTakenMult().unmodify(IMMUNE);
            stats.getEmpDamageTakenMult().unmodify(IMMUNE);
        }
    }

    /** View ships do nothing by themselves; the leader's stream moves them. */
    static final class Idle implements ShipAIPlugin {
        private final ShipwideAIFlags flags = new ShipwideAIFlags();

        public void setDoNotFireDelay(float amount) {
        }

        public void forceCircumstanceEvaluation() {
        }

        public void advance(float amount) {
        }

        public boolean needsRefit() {
            return false;
        }

        public ShipwideAIFlags getAIFlags() {
            return flags;
        }

        public void cancelCurrentManeuver() {
        }

        public ShipAIConfig getConfig() {
            return new ShipAIConfig();
        }
    }
}
