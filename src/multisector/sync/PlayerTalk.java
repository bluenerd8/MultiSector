package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoAPI.CargoItemType;
import com.fs.starfarer.api.campaign.CargoPickerListener;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.FleetMemberPickerListener;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import multisector.character.ShipJson;
import multisector.net.Session;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Players talking to each other.
 *
 * <p>Clicking another player's fleet hails them. Their game opens a dialog
 * at once (like an NPC fleet pulling them over), or as soon as they're out
 * of whatever dialog, menu or battle they're in, and they can answer or
 * decline. Either side can back out at any point. While they talk, both
 * players sit in a dialog, so both fleets hold still. The world keeps going
 * for everyone else, as it does for any dialog.
 *
 * <p>Trading is by offers. A player puts up credits, cargo or ships, which
 * leave their fleet straight away and are held aside. The other player
 * accepts (they arrive) or turns the offer down (they go back). Only the
 * receiver decides, so nothing can end up in both fleets or in neither. If
 * the receiver leaves the game before deciding, the offer goes back.
 *
 * <p>Transient: added on each load, never saved.
 */
public final class PlayerTalk implements EveryFrameScript {

    private static final Logger log = Global.getLogger(PlayerTalk.class);

    /** The caller gives up after this long without an answer (restarted when the other side says they're busy). */
    private static final long HAIL_NANOS = 60_000_000_000L;
    /** A hail that waits this long for the player to be free is turned down for them. */
    private static final long RING_NANOS = 55_000_000_000L;
    /** A player missing from the list this long counts as gone, even without a "left" message. */
    private static final long MISSING_NANOS = 15_000_000_000L;
    private static final int[] CREDIT_STEPS = {1_000, 5_000, 10_000, 50_000, 100_000, 500_000, 1_000_000};

    private enum Phase { NONE, HAILING, RINGING, TALKING, ENDED }

    // One conversation at a time.
    private static Phase phase = Phase.NONE;
    private static int peer = -1;
    private static String peerName = "";
    private static int talkId;
    private static long phaseSince;
    private static boolean waitSent;
    private static boolean calleeShown;
    /** The dialog showing this conversation, or null. */
    private static TalkDialog open;

    /** Offers we made that the other player hasn't decided on yet, by offer id. */
    private static final Map<Integer, Escrow> outgoing = new LinkedHashMap<Integer, Escrow>();
    /** Offers to us, waiting for our answer, by offer id. */
    private static final LinkedHashMap<Integer, JSONObject> incoming = new LinkedHashMap<Integer, JSONObject>();
    private static final Random random = new Random();
    private static int nextOfferId = 1 + random.nextInt(1_000_000);

    /** What we put up in one offer, held aside until the other player decides. */
    private static final class Escrow {
        int peer;
        String peerName;
        float credits;
        JSONArray cargo = new JSONArray();
        final List<FleetMemberAPI> ships = new ArrayList<FleetMemberAPI>();
        JSONArray shipsJson = new JSONArray();
        long missingSince;
    }

    public boolean isDone() {
        return false;
    }

    /** Dialogs pause the game; this has to keep running in them. */
    public boolean runWhilePaused() {
        return true;
    }

    public void advance(float amount) {
        Session s = Session.get();
        Session.State st = s.getState();
        if (st != Session.State.HOSTING && st != Session.State.CONNECTED) {
            if (phase != Phase.NONE && phase != Phase.ENDED) finish("The connection closed.");
            incoming.clear();
            if (!outgoing.isEmpty()) {
                for (Escrow e : new ArrayList<Escrow>(outgoing.values())) restore(e, null, null, "the connection closed");
                outgoing.clear();
            }
            return;
        }
        Session.PlayerMessage m;
        for (int i = 0; i < 50 && (m = s.pollPlayerMessage()) != null; i++) {
            try {
                handle(s, m, new JSONObject(m.json));
            } catch (Exception e) {
                log.warn("[MultiSector] bad message from " + m.fromName + ": " + e);
            }
        }
        long now = System.nanoTime();

        // Offers to players who vanished without a "left" (shouldn't happen, but never lose things).
        for (Iterator<Escrow> it = outgoing.values().iterator(); it.hasNext(); ) {
            Escrow e = it.next();
            if (s.isPlayerHere(e.peer)) {
                e.missingSince = 0;
            } else if (e.missingSince == 0) {
                e.missingSince = now;
            } else if (now - e.missingSince > MISSING_NANOS) {
                it.remove();
                restore(e, null, null, e.peerName + " is gone");
            }
        }

        // The conversation's dialog closed some other way: same as leaving.
        if (open != null && !open.picking) { // a picker sits on top of the dialog
            CampaignUIAPI ui = Global.getSector().getCampaignUI();
            InteractionDialogAPI d = ui == null ? null : ui.getCurrentInteractionDialog();
            if (d == null || d.getPlugin() != open) leave(false);
        }

        if (phase == Phase.RINGING && open == null) {
            CampaignFleetAPI me = Global.getSector().getPlayerFleet();
            boolean free = ClockSync.isMapView() && me != null && me.getBattle() == null;
            if (free && !calleeShown) {
                SectorEntityToken target = PuppetManager.fleetOf(peer);
                if (target == null) target = me;
                calleeShown = Global.getSector().getCampaignUI().showInteractionDialog(new TalkDialog(), target);
            } else if (!free && !waitSent) {
                send(peer, msg("wait", talkId));
                waitSent = true;
            }
            if (!calleeShown && now - phaseSince > RING_NANOS) {
                send(peer, why(msg("decline", talkId), "busy"));
                tell(peerName + " hailed you, but you were busy.");
                phase = Phase.NONE;
            }
        }
        if (phase == Phase.HAILING && now - phaseSince > HAIL_NANOS) {
            send(peer, msg("cancel", talkId));
            finish("No answer from " + peerName + ".");
        }
    }

    // ================================================================ messages

    private static void handle(Session s, Session.PlayerMessage m, JSONObject j) throws Exception {
        String k = j.optString("k");
        int t = j.optInt("t");
        int from = m.from;

        if ("left".equals(k)) {
            for (Iterator<Escrow> it = outgoing.values().iterator(); it.hasNext(); ) {
                Escrow e = it.next();
                if (e.peer != from) continue;
                it.remove();
                restore(e, null, null, e.peerName + " left before deciding");
            }
            if (from == peer && phase != Phase.NONE && phase != Phase.ENDED) finish(peerName + " left the game.");
            return;
        }
        if ("answer".equals(k)) {
            Escrow e = outgoing.get(j.optInt("o"));
            if (e == null || e.peer != from) return;
            outgoing.remove(j.optInt("o"));
            settle(e, j);
            return;
        }
        if ("hail".equals(k)) {
            hailed(s, from, m.fromName, t);
            return;
        }
        if (from != peer || t != talkId) {
            // Not our conversation (an old one, say): never keep their things.
            if ("offer".equals(k)) send(from, answer(j.optInt("o"), false, null, null));
            return;
        }
        if ("cancel".equals(k) && phase == Phase.RINGING) {
            if (open != null) {
                finish(peerName + " stopped hailing.");
            } else {
                phase = Phase.NONE;
                tell(peerName + " hailed you, then gave up.");
            }
        } else if ("wait".equals(k) && phase == Phase.HAILING) {
            phaseSince = System.nanoTime(); // give them another minute
            say(peerName + " is busy right now (in a dialog, a menu or a battle). Your hail waits for them...");
        } else if ("accept".equals(k) && phase == Phase.HAILING) {
            phase = Phase.TALKING;
            say(peerName + " answers. Your fleets hold position while you talk.");
            refresh();
        } else if ("decline".equals(k) && phase == Phase.HAILING) {
            finish("busy".equals(j.optString("why"))
                    ? peerName + " was too busy to answer."
                    : peerName + " declined to talk.");
        } else if ("end".equals(k) && phase == Phase.TALKING) {
            finish(peerName + " ended the conversation.");
        } else if ("offer".equals(k)) {
            if (phase == Phase.TALKING && open != null) {
                incoming.put(j.optInt("o"), j);
                refresh();
            } else {
                send(from, answer(j.optInt("o"), false, null, null));
            }
        }
    }

    private static void hailed(Session s, int from, String name, int t) throws Exception {
        if (phase == Phase.NONE) {
            phase = Phase.RINGING;
            peer = from;
            peerName = name;
            talkId = t;
            phaseSince = System.nanoTime();
            waitSent = false;
            calleeShown = false;
            incoming.clear();
            log.info("[MultiSector] " + name + " is hailing us");
            return;
        }
        if (phase == Phase.HAILING && peer == from) {
            // We hailed each other at the same moment: the lower id answers the other's hail.
            if (s.getMyId() < from) {
                send(peer, msg("cancel", talkId)); // they ignore it: it's not their conversation
                talkId = t;
                send(peer, msg("accept", talkId));
                phase = Phase.TALKING;
                say(peerName + " was hailing you too. You're connected.");
                refresh();
            }
            return; // otherwise they answer ours
        }
        send(from, why(msg("decline", t), "busy"));
    }

    /** The conversation is over: say so in the dialog (left open until the player leaves), or as a message. */
    private static void finish(String text) {
        declineIncoming();
        if (open != null) {
            phase = Phase.ENDED;
            say(text);
            refresh();
        } else {
            phase = Phase.NONE;
            tell(text);
        }
    }

    /** The player backs out (or their dialog closed): tell the other side. */
    private static void leave(boolean dismiss) {
        if (phase == Phase.HAILING) send(peer, msg("cancel", talkId));
        else if (phase == Phase.RINGING) send(peer, msg("decline", talkId));
        else if (phase == Phase.TALKING) send(peer, msg("end", talkId));
        declineIncoming();
        phase = Phase.NONE;
        TalkDialog d = open;
        open = null;
        if (dismiss && d != null && d.dialog != null) d.dialog.dismiss();
    }

    private static void declineIncoming() {
        for (Integer o : new ArrayList<Integer>(incoming.keySet())) send(peer, answer(o, false, null, null));
        incoming.clear();
    }

    // ================================================================ offers

    private static void offer(Escrow e) {
        int o = nextOfferId++;
        e.peer = peer;
        e.peerName = peerName;
        JSONObject j = msg("offer", talkId);
        try {
            j.put("o", o);
            j.put("credits", e.credits);
            j.put("cargo", e.cargo);
            j.put("ships", e.shipsJson);
        } catch (Exception ex) {
            restore(e, null, null, "the offer couldn't be sent");
            return;
        }
        outgoing.put(o, e);
        send(peer, j);
        say("You offer " + peerName + " " + summary(e.credits, e.cargo, e.shipsJson) + ". Waiting for them to decide...");
        log.info("[MultiSector] offered " + peerName + ": " + summary(e.credits, e.cargo, e.shipsJson));
    }

    private static void offerCredits(int amount) {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (me == null || amount <= 0 || me.getCargo().getCredits().get() < amount) return;
        me.getCargo().getCredits().subtract(amount);
        Escrow e = new Escrow();
        e.credits = amount;
        offer(e);
    }

    /** Takes what was picked (from a copy of the cargo) out of the real cargo, and offers it. */
    private static void offerCargo(CargoAPI picked) {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (me == null) return;
        CargoAPI mine = me.getCargo();
        Escrow e = new Escrow();
        for (CargoStackAPI st : picked.getStacksCopy()) {
            try {
                JSONObject o = MarketSync.stack(st);
                if (o == null) continue; // something this can't send: it stays
                float q = Math.min(st.getSize(), mine.getQuantity(st.getType(), st.getData()));
                if (q <= 0) continue;
                mine.removeItems(st.getType(), st.getData(), q);
                o.put("q", q);
                e.cargo.put(o);
            } catch (Exception ex) {
                log.warn("[MultiSector] couldn't offer a cargo stack: " + ex);
            }
        }
        if (e.cargo.length() > 0) offer(e);
    }

    private static void offerShips(List<FleetMemberAPI> picked) {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (me == null) return;
        Escrow e = new Escrow();
        for (FleetMemberAPI m : picked) {
            if (m == me.getFlagship() || !me.getFleetData().getMembersListCopy().contains(m)) continue;
            try {
                JSONObject js = ShipJson.ship(m);
                js.put("flagship", false);
                // Officers stay with you, unassigned. An AI core goes back in your cargo, as when refitting.
                PersonAPI c = m.getCaptain();
                if (c != null && !c.isDefault() && !c.isPlayer()) {
                    if (c.isAICore() && c.getAICoreId() != null) me.getCargo().addCommodity(c.getAICoreId(), 1);
                    m.setCaptain(null);
                }
                me.getFleetData().removeFleetMember(m);
                e.ships.add(m);
                e.shipsJson.put(js);
            } catch (Exception ex) {
                log.warn("[MultiSector] couldn't offer " + m.getShipName() + ": " + ex);
            }
        }
        me.forceSync();
        if (!e.ships.isEmpty()) offer(e);
    }

    /** We take an offer: everything we can use arrives; the rest goes back to them. */
    private static void acceptOffer(int o) {
        JSONObject j = incoming.remove(o);
        if (j == null) return;
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        if (me == null) {
            send(peer, answer(o, false, null, null));
            return;
        }
        JSONArray backCargo = new JSONArray();
        JSONArray backShips = new JSONArray();
        float credits = (float) j.optDouble("credits", 0);
        if (credits > 0) me.getCargo().getCredits().add(credits);
        JSONArray cargo = j.optJSONArray("cargo");
        for (int i = 0; cargo != null && i < cargo.length(); i++) {
            JSONObject st = cargo.optJSONObject(i);
            CargoItemType t = st == null ? null : MarketSync.type(st);
            if (t == null || !MarketSync.known(st, t)) {
                backCargo.put(i);
                continue;
            }
            MarketSync.add(me.getCargo(), st);
        }
        JSONArray ships = j.optJSONArray("ships");
        List<String> skipped = new ArrayList<String>();
        for (int i = 0; ships != null && i < ships.length(); i++) {
            try {
                JSONObject js = ships.getJSONObject(i);
                FleetMemberAPI m = MarketSync.buildShip(js, skipped);
                if (m == null) {
                    backShips.put(i);
                    continue;
                }
                me.getFleetData().addFleetMember(m);
                m.getRepairTracker().setCR((float) js.optDouble("cr", m.getRepairTracker().getMaxCR()));
                m.getStatus().setHullFraction((float) js.optDouble("hullFraction", 1));
                if (js.optBoolean("mothballed")) m.getRepairTracker().setMothballed(true);
            } catch (Exception ex) {
                backShips.put(i);
            }
        }
        me.forceSync();
        send(peer, answer(o, true, backCargo, backShips));
        say("You take " + summary(credits, cargo, ships) + ".");
        if (backCargo.length() > 0 || backShips.length() > 0) {
            say("Some of it doesn't exist in your game (a mod you don't have?), so it goes back to " + peerName + ".");
        }
        if (!skipped.isEmpty()) say("Left off the ships (not in your game): " + join(skipped, 8) + ".");
        log.info("[MultiSector] took " + peerName + "'s offer: " + summary(credits, cargo, ships));
    }

    private static void refuseOffer(int o) {
        if (incoming.remove(o) == null) return;
        send(peer, answer(o, false, null, null));
        say("You turn it down.");
    }

    /** The other player decided on one of our offers. */
    private static void settle(Escrow e, JSONObject j) {
        if (!j.optBoolean("ok")) {
            restore(e, null, null, e.peerName + " turned down your offer");
            return;
        }
        JSONArray backCargo = j.optJSONArray("backCargo");
        JSONArray backShips = j.optJSONArray("backShips");
        if ((backCargo != null && backCargo.length() > 0) || (backShips != null && backShips.length() > 0)) {
            restore(e, backCargo, backShips, "their game doesn't have some of it");
        }
        tell(e.peerName + " accepted your offer of " + summary(e.credits, e.cargo, e.shipsJson) + ".");
    }

    /**
     * Puts an offer (or, with index lists, just those parts of it) back in our
     * fleet. Credits only come back when the whole offer does.
     */
    private static void restore(Escrow e, JSONArray cargoIdx, JSONArray shipIdx, String why) {
        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
        boolean all = cargoIdx == null && shipIdx == null;
        if (me == null) {
            log.warn("[MultiSector] no fleet to return an offer to (" + why + ")");
            return;
        }
        if (all && e.credits > 0) me.getCargo().getCredits().add(e.credits);
        for (int i = 0; i < e.cargo.length(); i++) {
            if (!all && !contains(cargoIdx, i)) continue;
            JSONObject st = e.cargo.optJSONObject(i);
            if (st != null) MarketSync.add(me.getCargo(), st);
        }
        for (int i = 0; i < e.ships.size(); i++) {
            if (!all && !contains(shipIdx, i)) continue;
            me.getFleetData().addFleetMember(e.ships.get(i));
        }
        me.forceSync();
        if (all) tell(why + ": " + summary(e.credits, e.cargo, e.shipsJson) + " came back to you.");
        else tell(why + ", so part of your offer came back to you.");
    }

    private static boolean contains(JSONArray a, int i) {
        for (int k = 0; a != null && k < a.length(); k++) if (a.optInt(k, -1) == i) return true;
        return false;
    }

    // ================================================================ helpers

    private static JSONObject msg(String kind, int t) {
        try {
            return new JSONObject().put("k", kind).put("t", t);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject why(JSONObject j, String why) {
        try {
            return j.put("why", why);
        } catch (Exception e) {
            return j;
        }
    }

    private static JSONObject answer(int o, boolean ok, JSONArray backCargo, JSONArray backShips) {
        try {
            JSONObject j = new JSONObject().put("k", "answer").put("o", o).put("ok", ok);
            if (backCargo != null) j.put("backCargo", backCargo);
            if (backShips != null) j.put("backShips", backShips);
            return j;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void send(int to, JSONObject j) {
        Session.get().sendPlayerMessage(to, j.toString());
    }

    /** Into the conversation's dialog if it's open, else as a message on the map. */
    private static void tell(String text) {
        if (open != null) {
            say(text);
            return;
        }
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (ui != null) ui.addMessage(text);
    }

    private static void say(String text) {
        if (open != null && open.dialog != null) open.dialog.getTextPanel().addPara(text);
    }

    private static void refresh() {
        if (open != null) open.menu();
    }

    /** "5,000 credits, 120 × Supplies, ISS Hammer (Eagle)". */
    static String summary(float credits, JSONArray cargo, JSONArray ships) {
        List<String> parts = new ArrayList<String>();
        if (credits > 0) parts.add(String.format(Locale.ROOT, "%,d credits", (long) credits));
        for (int i = 0; cargo != null && i < cargo.length(); i++) {
            JSONObject st = cargo.optJSONObject(i);
            if (st == null) continue;
            parts.add(String.format(Locale.ROOT, "%,d × %s", (long) st.optDouble("q", 0), itemName(st)));
        }
        for (int i = 0; ships != null && i < ships.length(); i++) {
            JSONObject js = ships.optJSONObject(i);
            if (js == null) continue;
            String hull = hullName(js.optString("hull"));
            String name = js.optString("shipName", "");
            parts.add(name.isEmpty() ? hull : name + " (" + hull + ")");
        }
        return parts.isEmpty() ? "nothing" : join(parts, 12);
    }

    private static String join(List<String> parts, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size() && i < max; i++) {
            if (i > 0) b.append(", ");
            b.append(parts.get(i));
        }
        if (parts.size() > max) b.append(" and ").append(parts.size() - max).append(" more");
        return b.toString();
    }

    private static String itemName(JSONObject st) {
        String id = st.optString("id", "?");
        try {
            CargoItemType t = MarketSync.type(st);
            if (t == CargoItemType.RESOURCES) return Global.getSettings().getCommoditySpec(id).getName();
            if (t == CargoItemType.WEAPONS) return Global.getSettings().getWeaponSpec(id).getWeaponName();
            if (t == CargoItemType.FIGHTER_CHIP) return Global.getSettings().getFighterWingSpec(id).getWingName();
            if (t == CargoItemType.SPECIAL) return Global.getSettings().getSpecialItemSpec(id).getName();
        } catch (Throwable ignored) {
            // not in this game
        }
        return id;
    }

    private static String hullName(String hullId) {
        try {
            return Global.getSettings().getHullSpec(hullId).getHullName();
        } catch (Throwable t) {
            return hullId;
        }
    }

    // ================================================================ entry points

    /** The dialog for clicking another player's fleet: hails them. */
    static InteractionDialogPlugin hail(int playerId) {
        return new TalkDialog(playerId);
    }

    /** A new save loaded: the old world's conversation is over. */
    public static void forget() {
        if (phase != Phase.NONE) {
            open = null;
            leave(false);
        }
        incoming.clear();
        outgoing.clear(); // belonged to the world that was unloaded
        open = null;
        phase = Phase.NONE;
    }

    // ================================================================ the dialog

    private static final String O_ANSWER = "answer";
    private static final String O_DECLINE = "decline";
    private static final String O_CANCEL = "cancel";
    private static final String O_CREDITS = "credits";
    private static final String O_CARGO = "cargo";
    private static final String O_SHIPS = "ships";
    private static final String O_END = "end";
    private static final String O_BACK = "back";
    private static final String O_LEAVE = "leave";

    /** Both ends of a conversation: the caller's (made by clicking a fleet) and the callee's (opened by a hail). */
    private static final class TalkDialog implements InteractionDialogPlugin {
        private final int callee; // the player we hail, or -1 when we're the one hailed
        InteractionDialogAPI dialog;
        /** False when this dialog was refused because another conversation is going. */
        private boolean ours = true;
        private boolean picking;
        private boolean creditsMenu;
        private int shownOffer = Integer.MIN_VALUE;

        /** The callee's dialog. */
        TalkDialog() {
            this.callee = -1;
        }

        /** The caller's dialog. */
        TalkDialog(int callee) {
            this.callee = callee;
        }

        public void init(InteractionDialogAPI dialog) {
            this.dialog = dialog;
            Session s = Session.get();
            if (callee >= 0) {
                String name = s.playerName(callee);
                if (phase != Phase.NONE) {
                    ours = false;
                    dialog.getTextPanel().addPara(phase == Phase.RINGING
                            ? peerName + " is hailing you. Leave this to answer them."
                            : "You're already talking with " + peerName + ".");
                    menu();
                    return;
                }
                phase = Phase.HAILING;
                peer = callee;
                peerName = name;
                talkId = 1 + random.nextInt(Integer.MAX_VALUE - 1);
                phaseSince = System.nanoTime();
                incoming.clear();
                open = this;
                showFleets();
                dialog.getTextPanel().addPara("You hail " + name + "'s fleet. Waiting for them to answer...");
                send(peer, msg("hail", talkId));
                log.info("[MultiSector] hailing " + name);
            } else {
                open = this;
                showFleets();
                dialog.getTextPanel().addPara(peerName + "'s fleet is hailing you.");
                log.info("[MultiSector] showing " + peerName + "'s hail");
            }
            menu();
        }

        private void showFleets() {
            try {
                SectorEntityToken t = dialog.getInteractionTarget();
                CampaignFleetAPI me = Global.getSector().getPlayerFleet();
                if (t instanceof CampaignFleetAPI && t != me) {
                    dialog.getVisualPanel().showFleetInfo("Your fleet", me, peerName + "'s fleet", (CampaignFleetAPI) t);
                }
            } catch (Throwable ignored) {
                // just no picture
            }
        }

        void menu() {
            if (picking || dialog == null) return; // the picker's callback comes back here
            OptionPanelAPI op = dialog.getOptionPanel();
            op.clearOptions();
            if (!ours || open != this) {
                op.addOption("Leave", O_LEAVE);
                dialog.setOptionOnEscape("Leave", O_LEAVE);
                return;
            }
            switch (phase) {
                case HAILING:
                    op.addOption("Cancel the hail", O_CANCEL);
                    dialog.setOptionOnEscape("Cancel the hail", O_CANCEL);
                    return;
                case RINGING:
                    op.addOption("Answer", O_ANSWER);
                    op.addOption("Decline", O_DECLINE);
                    dialog.setOptionOnEscape("Decline", O_DECLINE);
                    return;
                case TALKING:
                    if (!incoming.isEmpty()) {
                        Map.Entry<Integer, JSONObject> first = incoming.entrySet().iterator().next();
                        int o = first.getKey();
                        if (o != shownOffer) {
                            shownOffer = o;
                            JSONObject j = first.getValue();
                            dialog.getTextPanel().addPara(peerName + " offers you "
                                    + summary((float) j.optDouble("credits", 0), j.optJSONArray("cargo"),
                                    j.optJSONArray("ships")) + ".");
                        }
                        op.addOption("Accept the offer", "yes:" + o);
                        op.addOption("Turn it down", "no:" + o);
                        dialog.setOptionOnEscape("Turn it down", "no:" + o);
                        return;
                    }
                    if (creditsMenu) {
                        CampaignFleetAPI me = Global.getSector().getPlayerFleet();
                        float have = me == null ? 0 : me.getCargo().getCredits().get();
                        for (int c : CREDIT_STEPS) {
                            if (c <= have) op.addOption(String.format(Locale.ROOT, "%,d credits", c), "credits:" + c);
                        }
                        op.addOption("Back", O_BACK);
                        dialog.setOptionOnEscape("Back", O_BACK);
                        return;
                    }
                    op.addOption("Offer credits", O_CREDITS);
                    op.addOption("Offer cargo", O_CARGO);
                    op.addOption("Offer ships", O_SHIPS);
                    op.addOption("End the conversation", O_END);
                    dialog.setOptionOnEscape("End the conversation", O_END);
                    return;
                default:
                    op.addOption("Leave", O_LEAVE);
                    dialog.setOptionOnEscape("Leave", O_LEAVE);
            }
        }

        public void optionSelected(String optionText, Object optionData) {
            String id = String.valueOf(optionData);
            if (!ours || open != this) {
                dialog.dismiss();
                return;
            }
            if (O_LEAVE.equals(id) || O_CANCEL.equals(id) || O_DECLINE.equals(id) || O_END.equals(id)) {
                leave(true);
                return;
            }
            if (O_ANSWER.equals(id) && phase == Phase.RINGING) {
                send(peer, msg("accept", talkId));
                phase = Phase.TALKING;
                dialog.getTextPanel().addPara("You answer. Your fleets hold position while you talk.");
            } else if (id.startsWith("yes:")) {
                acceptOffer(Integer.parseInt(id.substring(4)));
            } else if (id.startsWith("no:")) {
                refuseOffer(Integer.parseInt(id.substring(3)));
            } else if (O_CREDITS.equals(id)) {
                creditsMenu = true;
            } else if (O_BACK.equals(id)) {
                creditsMenu = false;
            } else if (id.startsWith("credits:")) {
                creditsMenu = false;
                offerCredits(Integer.parseInt(id.substring(8)));
            } else if (O_CARGO.equals(id)) {
                pickCargo();
                return;
            } else if (O_SHIPS.equals(id)) {
                pickShips();
                return;
            }
            menu();
        }

        private void pickCargo() {
            CampaignFleetAPI me = Global.getSector().getPlayerFleet();
            if (me == null) return;
            picking = true;
            // A copy: the picker moves things out of the cargo it's given. Ours change only once picked.
            CargoAPI copy = me.getCargo().createCopy();
            dialog.showCargoPickerDialog("Cargo to offer " + peerName, "Offer", "Cancel", true, 310f, copy,
                    new CargoPickerListener() {
                        public void pickedCargo(CargoAPI cargo) {
                            picking = false;
                            if (phase == Phase.TALKING && !cargo.isEmpty()) offerCargo(cargo);
                            menu();
                        }

                        public void cancelledCargoSelection() {
                            picking = false;
                            menu();
                        }

                        public void recreateTextPanel(TooltipMakerAPI panel, CargoAPI cargo, CargoStackAPI pickedUp,
                                                      boolean pickedUpFromSource, CargoAPI combined) {
                            panel.addPara("Pick what to offer " + peerName + ". It leaves your fleet now and "
                                    + "comes back if they turn it down.", 10f);
                        }
                    });
        }

        private void pickShips() {
            CampaignFleetAPI me = Global.getSector().getPlayerFleet();
            if (me == null) return;
            List<FleetMemberAPI> pool = new ArrayList<FleetMemberAPI>();
            for (FleetMemberAPI m : me.getFleetData().getMembersListCopy()) {
                if (m != me.getFlagship() && !m.isFighterWing()) pool.add(m);
            }
            if (pool.isEmpty()) {
                dialog.getTextPanel().addPara("You have no ships to offer besides your flagship.");
                return;
            }
            picking = true;
            dialog.showFleetMemberPickerDialog("Ships to offer " + peerName + " (officers stay with you)",
                    "Offer", "Cancel", 3, 7, 58f, true, true, pool, new FleetMemberPickerListener() {
                        public void pickedFleetMembers(List<FleetMemberAPI> members) {
                            picking = false;
                            if (phase == Phase.TALKING && members != null && !members.isEmpty()) offerShips(members);
                            menu();
                        }

                        public void cancelledFleetMemberPicking() {
                            picking = false;
                            menu();
                        }
                    });
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
            return new HashMap<String, MemoryAPI>();
        }
    }
}
