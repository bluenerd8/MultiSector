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
    /** Toggle counters in an input packet: shield, system, vent, fighters, autofire. */
    private static final int TOGGLES = 5;

    private static final long STATE_NANOS = 50_000_000L;
    private static final long INPUT_NANOS = 33_000_000L;
    private static final long INPUT_STALE_NANOS = 1_500_000_000L;
    private static final long STATUS_NANOS = 1_000_000_000L;
    private static final String IMMUNE = "multisector_view";
    private static final String TIME_ID = "multisector_leader_speed";

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
        private long nextGuard;
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
            int n = deploy(jn);
            message(jn.name + " joins your battle with " + n + (n == 1 ? " ship" : " ships")
                    + (jn.pilotPuppetId != null ? ", flying " + jn.names.get(jn.pilotPuppetId) + " themselves" : ""));
            try {
                Global.getSoundPlayer().playUISound("ui_noise_static_message", 1f, 1f);
            } catch (RuntimeException ignored) {
                // no sound
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
                track(s);
                JointBattle.tellFight(s, !ended, ready);
                for (JointBattle.Joiner jn : JointBattle.joiners()) {
                    if (jn.retreat) retreat(jn);
                    pilot(jn);
                }
                long now = System.nanoTime();
                if (now - nextState >= 0) {
                    nextState = now + STATE_NANOS;
                    sendStates(s);
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
                    .put("mine", owner != null).put("pilot", m.getId().equals(jn.pilotPuppetId))
                    .put("x", ship.getLocation().x).put("y", ship.getLocation().y).put("f", ship.getFacing());
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
                byte[] entry = encode(e.getValue(), e.getKey());
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

        private static byte[] encode(int id, ShipAPI ship) {
            List<WeaponAPI> weapons = ship.getAllWeapons();
            List<int[]> firing = new ArrayList<int[]>();
            for (int i = 0; i < weapons.size() && firing.size() < 40; i++) {
                WeaponAPI w = weapons.get(i);
                if (w.isFiring()) firing.add(new int[]{i, Math.round(w.getCurrAngle() * 10f)});
            }
            ByteBuffer bb = ByteBuffer.allocate(41 + firing.size() * 3);
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
            bb.put((byte) firing.size());
            for (int[] f : firing) {
                bb.put((byte) f[0]);
                bb.putShort((short) f[1]);
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
                try {
                    ShipAPI ship = fm.spawnFleetMember(m, new Vector2f(x, y), 90f, 2f);
                    fm.removeFromReserves(m); // in the fight, not waiting to be deployed again
                    if (ship != null) {
                        ship.setAlly(true);
                        n++;
                    }
                } catch (RuntimeException e) {
                    log.warn("[MultiSector] couldn't deploy " + jn.name + "'s " + m.getShipName(), e);
                }
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
            if (jn.pilotPuppetId == null) return;
            Input in = inputs.get(jn.id);
            boolean live = in != null && System.nanoTime() - in.arrived < INPUT_STALE_NANOS;
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
                    banner(jn.name + "'s controls stopped coming: " + m.getShipName() + " is on its own AI");
                    log.info("[MultiSector] " + jn.name + "'s controls stopped; " + m.getShipName() + " back on its AI");
                }
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
        // latest state
        float x, y, vx, vy, facing, angVel, hull, flux, hard, cr, shieldFacing;
        int flags, eng;
        int[] fireIdx = new int[0];
        float[] fireAngle = new float[0];
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

        Mirror(int leader) {
            this.leader = leader;
        }

        @Override
        public void init(CombatEngineAPI engine) {
            this.engine = engine;
            engine.setDoNotEndCombat(true);
            view = this;
            log.info("[MultiSector] watching " + Session.get().playerName(leader) + "'s battle");
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
                for (Shown sh : shown.values()) apply(sh, now);
                if (pilot != null && pilot.ship != null) controls(s, now);
            } catch (Exception e) {
                log.warn("[MultiSector] battle view frame failed", e);
            }
        }

        private void announce(JSONObject j) throws Exception {
            String k = j.optString("k");
            int id = j.optInt("id");
            if ("cgone".equals(k)) {
                Shown sh = shown.remove(id);
                if (sh != null && sh.ship != null && !sh.ship.isHulk()) engine.removeEntity(sh.ship);
                if (sh == pilot) pilot = null;
                return;
            }
            if (shown.containsKey(id)) return;
            int side = j.optInt("owner");
            if (side != 0 && side != 1) return; // a wreck (owner 100): neither side's, and nothing to show moving
            List<String> missing = new ArrayList<String>();
            FleetMemberAPI m = ShipJson.buildShip(j.getJSONObject("ship"), missing);
            if (m == null) {
                log.warn("[MultiSector] battle view can't make " + j.optString("name") + " (missing here: " + missing + ")");
                return;
            }
            if (j.has("name")) m.setShipName(j.optString("name"));
            // A new ship starts at no readiness: everything malfunctions and it looks scuttled. The stream sets the real value.
            m.getRepairTracker().setCR(m.getRepairTracker().getMaxCR());
            int owner = j.optInt("owner") == 1 ? 1 : 0;
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
            if (owner == 0) ship.setAlly(!j.optBoolean("mine"));
            Shown sh = new Shown();
            sh.ship = ship;
            sh.pilot = j.optBoolean("pilot");
            shown.put(id, sh);
            if (sh.pilot) {
                pilot = sh;
                engine.setPlayerShipExternal(ship);
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

        /** Only the leader's battle on this field: nothing deployed here by the game itself. */
        private void clean(long now) {
            if (now - nextClean < 0) return;
            nextClean = now + 500_000_000L;
            for (ShipAPI ship : new ArrayList<ShipAPI>(engine.getShips())) {
                if (ship.isFighter() || ship.isHulk() || ship.getParentStation() != null || ours.contains(ship)) continue;
                log.info("[MultiSector] removing a ship the battle view didn't make: "
                        + (ship.getFleetMember() != null ? ship.getFleetMember().getShipName() : ship.getHullSpec().getHullId()));
                engine.removeEntity(ship);
            }
            for (int side = 0; side <= 1; side++) {
                CombatFleetManagerAPI fm = engine.getFleetManager(side);
                if (fm == null) continue;
                for (FleetMemberAPI m : fm.getReservesCopy()) fm.removeFromReserves(m);
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
                    int nf = bb.get() & 0xff;
                    sh.fireIdx = new int[nf];
                    sh.fireAngle = new float[nf];
                    for (int f = 0; f < nf; f++) {
                        sh.fireIdx[f] = bb.get() & 0xff;
                        sh.fireAngle[f] = bb.getShort() / 10f;
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
            if (leaderPaused != engine.isPaused()) engine.setPaused(leaderPaused);
        }

        private void apply(Shown sh, long now) {
            ShipAPI ship = sh.ship;
            if (ship == null || !sh.hasState) return;
            if ((sh.flags & F_HULK) != 0) {
                if (!ship.isHulk() && ship.isAlive()) {
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
            if (!sh.pilot) {
                ShipSystemAPI sys = ship.getSystem();
                if ((sh.flags & F_SYSTEM) != 0 && sys != null && !sys.isActive()) ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0);
                if ((sh.flags & F_VENT) != 0 && !ft.isVenting()) ship.giveCommand(ShipCommand.VENT_FLUX, null, 0);
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
            for (int i = 0; i < sh.fireIdx.length; i++) {
                if (sh.fireIdx[i] >= weapons.size()) continue;
                WeaponAPI w = weapons.get(sh.fireIdx[i]);
                w.setCurrAngle(sh.fireAngle[i]);
                w.setForceFireOneFrame(true); // shots fly and hit here, but nothing here takes damage
            }
        }

        /** Our piloted ship: its controls go to the leader, not to it. */
        private void controls(Session s, long now) {
            ShipAPI ship = pilot.ship;
            for (ShipCommand c : ShipCommand.values()) ship.blockCommandForOneFrame(c);
            if (engine.isUIShowingDialog()) return; // the game's own menus have the keyboard
            boolean rmb = Mouse.isButtonDown(1);
            if (rmb && !wasRmb) toggles[0]++;
            wasRmb = rmb;
            wasF = edge(Keyboard.KEY_F, wasF, 1);
            wasV = edge(Keyboard.KEY_V, wasV, 2);
            wasZ = edge(Keyboard.KEY_Z, wasZ, 3);
            wasT = edge(Keyboard.KEY_T, wasT, 4);
            for (int g = 0; g < 7; g++) if (Keyboard.isKeyDown(Keyboard.KEY_1 + g)) group = g;
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
