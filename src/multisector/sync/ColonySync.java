package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.campaign.listeners.PlayerColonizationListener;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker;
import com.fs.starfarer.api.impl.campaign.submarkets.StoragePlugin;
import multisector.character.ShipJson;
import multisector.net.Session;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Colonies belong to the player who founded them, and each player's
 * colonies show on everyone else's game as that player's own faction.
 *
 * <p><b>Your game:</b> your colonies are normal player colonies, yours to
 * manage, your income. Your game runs them and sends them as they are
 * (name, size, stability, free port, what's built, your storage) whenever
 * they change and once a minute anyway. It also sends your faction's look
 * (the name and flag you gave it) and your standing with every NPC faction.
 *
 * <p><b>Everyone else's game:</b> your colonies belong to "your" faction
 * there: one of ten factions kept for players ({@code multisector_captain_0}
 * to {@code _9}, one per mark colour), named and flagged as you named and
 * flagged yours. It's always friendly with every player, and stands with NPC
 * factions as you do; Nexerelin's diplomacy leaves it alone. Visitors can
 * dock and trade (it gets an open market), not manage it, and its income
 * isn't theirs. It doesn't run itself there: size, stability and buildings
 * are what your game says (stability is held at yours), and your storage is
 * kept out of reach in its memory.
 *
 * <p><b>The host decides what happens to it:</b> the host gives each player
 * their faction (kept in its save, sent to everyone), keeps your colony in
 * its world (so it's there for you next session, storage included), and is
 * where NPC factions' moves count: if your colony is taken on the host's
 * game (a Nexerelin invasion, say), it's taken on every game, yours too. On
 * anyone else's copy, a takeover is undone.
 *
 * <p>Colonies already in the world when a friend first looks belong to the
 * host (they came with the host's save); a colony founded during a session
 * is its founder's. Abandoning one removes it everywhere.
 */
public final class ColonySync implements EveryFrameScript, PlayerColonizationListener {

    private static final Logger log = Global.getLogger(ColonySync.class);
    /** Who founded it: in the market's memory. */
    public static final String OWNER = "$multisector_owner";
    /** The factions kept for players: this plus 0 to 9. */
    public static final String SLOT_PREFIX = "multisector_captain_";
    public static final int SLOTS = 10;
    /** Which player has which faction (host's word), and how each looks: in the sector's persistent data. */
    private static final String SLOTS_KEY = "multisector_captainSlots";
    private static final String LOOKS_KEY = "multisector_captainLooks";
    /** The owner's storage, on other games: kept here, out of reach, for when the colony is theirs again. */
    private static final String STORAGE = "$multisector_ownerStorage";
    /** The owner's stability, held on other games. */
    private static final String STABILITY = "$multisector_ownerStability";
    private static final String STABILITY_MOD = "multisector_owner";
    /** We gave it an open market (a player colony has none), to take off again when it's ours. */
    private static final String ADDED_OPEN = "$multisector_addedOpenMarket";
    /** Marks the stand-in administrator we put on other players' colonies. */
    private static final String STAND_IN = "$multisector_standInAdmin";
    /** The game's population conditions: population_1 to population_10. */
    private static final String POPULATION = "population_";

    private static final long SCAN_NANOS = 2_000_000_000L;
    private static final long SEND_NANOS = 5_000_000_000L;
    private static final long RESEND_NANOS = 60_000_000_000L;
    private static final long LOOK_NANOS = 15_000_000_000L;
    private static final long CAPTAINS_NANOS = 10_000_000_000L;

    private long nextScan, nextSend, nextLook, nextCaptains;
    /** The first look this session: colonies with no owner yet came with the host's world. */
    private static boolean tookStock;
    /** What we last sent of each of our colonies, and when; our look, and the host's list. */
    private static final Map<String, String> sent = new HashMap<String, String>();
    private static final Map<String, Long> sentAt = new HashMap<String, Long>();
    private static String lookSent;
    private static long lookSentAt;
    private static String captainsSent;
    /** Changes to a colony someone's docked at, or of a player whose faction we don't know yet: tried again later. */
    private static final List<JSONObject> deferred = new ArrayList<JSONObject>();

    public boolean isDone() {
        return false;
    }

    public boolean runWhilePaused() {
        return true;
    }

    public void advance(float amount) {
        Session s = Session.get();
        boolean on = s.getState() == Session.State.HOSTING
                || (s.getState() == Session.State.CONNECTED && s.getMyId() >= 0);
        if (!on) {
            tookStock = false; // offline: every colony is as the save has it
            return;
        }
        long now = System.nanoTime();
        try {
            if (now - nextScan >= 0) {
                nextScan = now + SCAN_NANOS;
                if (!deferred.isEmpty()) {
                    List<JSONObject> again = new ArrayList<JSONObject>(deferred);
                    deferred.clear();
                    for (JSONObject j : again) apply(j);
                }
                scan(s);
            }
            if (now - nextSend >= 0) {
                nextSend = now + SEND_NANOS;
                sendOurs(s, now);
            }
            if (now - nextLook >= 0) {
                nextLook = now + 2_000_000_000L;
                sendLook(s, now);
            }
            if (isHost(s) && now - nextCaptains >= 0) {
                nextCaptains = now + CAPTAINS_NANOS;
                sendCaptains(s, false);
            }
        } catch (RuntimeException e) {
            log.warn("[MultiSector] colony sync failed this time", e);
        }
    }

    private static boolean isHost(Session s) {
        return s.getState() == Session.State.HOSTING;
    }

    private static String me(Session s) {
        return s.playerName(s.getMyId());
    }

    private static String ownerOf(MarketAPI m) {
        String o = m.getMemoryWithoutUpdate().getString(OWNER);
        return o == null || o.isEmpty() ? null : o;
    }

    static boolean isPlayersFaction(String factionId) {
        return factionId != null && factionId.startsWith(SLOT_PREFIX);
    }

    /** A colony a player runs (or ran, before it came to us): not an NPC faction's, not just a planet's conditions. */
    private static boolean isColony(MarketAPI m) {
        if (m == null || m.isPlanetConditionMarketOnly() || !m.isInEconomy()) return false;
        return m.isPlayerOwned() || Factions.PLAYER.equals(m.getFactionId()) || ownerOf(m) != null;
    }

    // ================================================================ which faction is whose (the host's word)

    private static JSONObject slots() {
        return json(SLOTS_KEY);
    }

    private static JSONObject looks() {
        return json(LOOKS_KEY);
    }

    private static JSONObject json(String key) {
        try {
            Object o = Global.getSector().getPersistentData().get(key);
            return o instanceof String ? new JSONObject((String) o) : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void keep(String key, JSONObject o) {
        Global.getSector().getPersistentData().put(key, o.toString());
    }

    /** That player's faction here, or null if the host hasn't said yet. */
    private static String factionOf(String owner) {
        int slot = slots().optInt(owner, -1);
        return slot < 0 || slot >= SLOTS ? null : SLOT_PREFIX + slot;
    }

    /** Host: a faction for a player who has none yet, the one in their mark's colour if it's free. */
    private static String assign(Session s, String owner) {
        JSONObject slots = slots();
        int slot = slots.optInt(owner, -1);
        if (slot >= 0) return SLOT_PREFIX + slot;
        Set<Integer> used = new HashSet<Integer>();
        Iterator<?> it = slots.keys();
        while (it.hasNext()) used.add(slots.optInt((String) it.next(), -1));
        int want = -1;
        for (Session.Player p : s.getPlayers()) if (owner.equals(p.name)) want = Marks.colorIndex(p.id) % SLOTS;
        if (want < 0 || used.contains(want)) {
            want = -1;
            for (int i = 0; i < SLOTS && want < 0; i++) if (!used.contains(i)) want = i;
        }
        if (want < 0) want = Math.abs(owner.hashCode()) % SLOTS; // more than ten players: share
        try {
            slots.put(owner, want);
        } catch (Exception ignored) {
            // never with these values
        }
        keep(SLOTS_KEY, slots);
        log.info("[MultiSector] " + owner + "'s colonies are faction " + SLOT_PREFIX + want + " on other games");
        captainsSent = null; // tell everyone now
        return SLOT_PREFIX + want;
    }

    /** Host: who has which faction, and how each looks, to everyone (when it changes, and now and then). */
    private static void sendCaptains(Session s, boolean force) {
        try {
            JSONObject j = new JSONObject().put("e", "captains").put("slots", slots()).put("looks", looks());
            // The host's own look and standing go in too (its colonies are its own faction on every other game).
            JSONObject mine = myLook(s);
            j.getJSONObject("looks").put(me(s), mine);
            String text = j.toString();
            if (!force && text.equals(captainsSent)) return;
            captainsSent = text;
            s.sendWorldEvent(text);
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't send the players' factions", e);
        }
    }

    /** Our faction as we've made it: its name and flag, and how we stand with every NPC faction. */
    private static JSONObject myLook(Session s) throws Exception {
        FactionAPI pf = Global.getSector().getPlayerFaction();
        String name = pf.getDisplayNameOverride();
        if (name == null || name.isEmpty()) name = me(s);
        JSONObject o = new JSONObject().put("name", name);
        if (pf.getFactionLogoOverride() != null) o.put("logo", pf.getFactionLogoOverride());
        if (pf.getFactionCrestOverride() != null) o.put("crest", pf.getFactionCrestOverride());
        JSONObject rel = new JSONObject();
        for (FactionAPI f : Global.getSector().getAllFactions()) {
            if (f == pf || isPlayersFaction(f.getId())) continue;
            rel.put(f.getId(), Math.round(pf.getRelationship(f.getId()) * 100f) / 100f);
        }
        o.put("rel", rel);
        return o;
    }

    /** Friend: our look to the host, which passes it on. */
    private static void sendLook(Session s, long now) {
        if (isHost(s)) return;
        try {
            String text = new JSONObject().put("e", "captainlook").put("owner", me(s)).put("look", myLook(s)).toString();
            if (text.equals(lookSent) && now - lookSentAt < LOOK_NANOS * 4) return;
            lookSent = text;
            lookSentAt = now;
            s.sendWorldEvent(text);
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't send our faction's look", e);
        }
    }

    /** Each player's faction as they've made theirs: name, flag, friends with every player, their standing with NPCs. */
    private static void dress() {
        JSONObject slots = slots();
        JSONObject looks = looks();
        String me = me(Session.get());
        Iterator<?> it = slots.keys();
        Set<FactionAPI> players = new HashSet<FactionAPI>();
        for (int i = 0; i < SLOTS; i++) {
            FactionAPI f = Global.getSector().getFaction(SLOT_PREFIX + i);
            if (f != null) players.add(f);
        }
        while (it.hasNext()) {
            String owner = (String) it.next();
            FactionAPI f = Global.getSector().getFaction(SLOT_PREFIX + slots.optInt(owner, -1));
            if (f == null) continue;
            JSONObject look = looks.optJSONObject(owner);
            String name = look == null ? owner : look.optString("name", owner);
            if (!name.equals(f.getDisplayNameOverride())) {
                f.setDisplayNameOverride(name);
                f.setDisplayNameWithArticleOverride(name);
            }
            if (look != null) {
                String logo = look.optString("logo", "");
                String crest = look.optString("crest", "");
                if (!logo.isEmpty() && !logo.equals(f.getFactionLogoOverride())) f.setFactionLogoOverride(logo);
                if (!crest.isEmpty() && !crest.equals(f.getFactionCrestOverride())) f.setFactionCrestOverride(crest);
                JSONObject rel = look.optJSONObject("rel");
                Iterator<?> ri = rel == null ? null : rel.keys();
                while (ri != null && ri.hasNext()) {
                    String other = (String) ri.next();
                    if (Global.getSector().getFaction(other) == null || owner.equals(me)) continue;
                    float r = (float) rel.optDouble(other, 0);
                    if (Math.abs(f.getRelationship(other) - r) > 0.005f) f.setRelationship(other, r);
                }
            }
            // Players are always friends: with us, and with each other's factions.
            f.setRelationship(Factions.PLAYER, 1f);
            for (FactionAPI g : players) if (g != f) f.setRelationship(g.getId(), 1f);
        }
    }

    // ================================================================ every colony as its owner says

    private static void scan(Session s) {
        String me = me(s);
        String host = s.playerName(Session.hostId());
        boolean hosting = isHost(s);
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (!isColony(m)) continue;
            String owner = ownerOf(m);
            if (owner == null) {
                // Colonies in the world when we first look came with the host's save; a new one is tagged by whoever
                // founded it as it happens (below), so an untagged one later is ours.
                owner = tookStock ? me : host;
                m.getMemoryWithoutUpdate().set(OWNER, owner);
                log.info("[MultiSector] colony " + m.getName() + " is " + owner + "'s");
            }
            if (hosting) assign(s, owner); // every owner (the host too) has a faction for the other games
            String fid = m.getFactionId();
            boolean taken = !Factions.PLAYER.equals(fid) && !isPlayersFaction(fid) && !m.isPlayerOwned();
            if (taken && hosting) {
                // An NPC faction took it on the host's game (an invasion): that counts, everywhere.
                taken(s, m, owner, fid);
                continue;
            }
            if (owner.equals(me)) {
                ours(m);
            } else {
                String faction = factionOf(owner);
                if (faction != null) theirs(m, owner, faction);
            }
        }
        tookStock = true;
        dress();
    }

    /** Host: an NPC faction took a player's colony here. It's theirs now on every game. */
    private static void taken(Session s, MarketAPI m, String owner, String byFaction) {
        try {
            SectorEntityToken p = m.getPrimaryEntity();
            s.sendWorldEvent(new JSONObject().put("e", "colonytaken").put("loc", FleetSync.locationId(p.getContainingLocation()))
                    .put("planet", p.getId()).put("owner", owner).put("faction", byFaction).toString());
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't tell the others " + m.getName() + " was taken", e);
        }
        lose(m, owner, byFaction);
    }

    /** It's an NPC faction's colony now: no player's any more. */
    private static void lose(MarketAPI m, String owner, String byFaction) {
        FactionAPI by = Global.getSector().getFaction(byFaction);
        if (by == null) return;
        m.getMemoryWithoutUpdate().unset(OWNER);
        m.getMemoryWithoutUpdate().unset(STORAGE);
        m.getMemoryWithoutUpdate().unset(STABILITY);
        m.getStability().unmodifyFlat(STABILITY_MOD);
        if (!byFaction.equals(m.getFactionId())) m.setFactionId(byFaction);
        for (SectorEntityToken e : m.getConnectedEntities()) e.setFaction(byFaction);
        m.setPlayerOwned(false);
        if (!m.hasSubmarket(Submarkets.SUBMARKET_OPEN)) m.addSubmarket(Submarkets.SUBMARKET_OPEN);
        m.getMemoryWithoutUpdate().unset(ADDED_OPEN);
        log.info("[MultiSector] " + owner + "'s colony " + m.getName() + " was taken by " + by.getDisplayName());
        Session s = Session.get();
        s.notice((owner.equals(me(s)) ? "Your" : owner + "'s") + " colony " + m.getName() + " was taken by "
                + by.getDisplayNameWithArticle() + ".");
    }

    /** Ours: a normal player colony (back from being another player's copy, if it was). */
    private static void ours(MarketAPI m) {
        MemoryAPI mem = m.getMemoryWithoutUpdate();
        if (mem.contains(STABILITY)) {
            mem.unset(STABILITY);
            m.getStability().unmodifyFlat(STABILITY_MOD);
        }
        if (Factions.PLAYER.equals(m.getFactionId()) && m.isPlayerOwned()) return;
        m.setFactionId(Factions.PLAYER);
        for (SectorEntityToken e : m.getConnectedEntities()) e.setFaction(Factions.PLAYER);
        m.setPlayerOwned(true);
        if (mem.getBoolean(ADDED_OPEN)) {
            m.removeSubmarket(Submarkets.SUBMARKET_OPEN);
            mem.unset(ADDED_OPEN);
        }
        if (!m.hasSubmarket(Submarkets.LOCAL_RESOURCES)) m.addSubmarket(Submarkets.LOCAL_RESOURCES);
        if (!m.hasSubmarket(Submarkets.SUBMARKET_STORAGE)) m.addSubmarket(Submarkets.SUBMARKET_STORAGE);
        SubmarketAPI storage = m.getSubmarket(Submarkets.SUBMARKET_STORAGE);
        if (storage != null && storage.getPlugin() instanceof StoragePlugin) {
            ((StoragePlugin) storage.getPlugin()).setPlayerPaidToUnlock(true);
        }
        String kept = mem.getString(STORAGE);
        if (kept != null && storage != null) {
            try {
                fillStorage(storage.getCargo(), new JSONObject(kept));
            } catch (Exception e) {
                log.warn("[MultiSector] couldn't put back the storage of " + m.getName(), e);
            }
            mem.unset(STORAGE);
        }
        PersonAPI admin = m.getAdmin();
        if (admin == null || admin.getMemoryWithoutUpdate().getBoolean(STAND_IN)) {
            m.setAdmin(Global.getSector().getPlayerPerson());
        }
        log.info("[MultiSector] colony " + m.getName() + " is ours to run");
        Session.get().notice("Your colony " + m.getName() + " is yours to run.");
    }

    /** Another player's: their faction's, theirs to run; ours to visit and trade at. Held as they have it. */
    private static void theirs(MarketAPI m, String owner, String faction) {
        MemoryAPI mem = m.getMemoryWithoutUpdate();
        holdStability(m);
        if (faction.equals(m.getFactionId()) && !m.isPlayerOwned()) return;
        // Their storage: out of reach, kept for when they're running it here again.
        SubmarketAPI storage = m.getSubmarket(Submarkets.SUBMARKET_STORAGE);
        if (storage != null) {
            try {
                if (!mem.contains(STORAGE)) mem.set(STORAGE, storageOf(storage.getCargo()).toString());
            } catch (Exception e) {
                log.warn("[MultiSector] couldn't keep the storage of " + m.getName(), e);
            }
            m.removeSubmarket(Submarkets.SUBMARKET_STORAGE);
        }
        if (m.hasSubmarket(Submarkets.LOCAL_RESOURCES)) m.removeSubmarket(Submarkets.LOCAL_RESOURCES);
        if (!m.hasSubmarket(Submarkets.SUBMARKET_OPEN)) {
            m.addSubmarket(Submarkets.SUBMARKET_OPEN);
            mem.set(ADDED_OPEN, true);
        }
        m.setPlayerOwned(false);
        m.setFactionId(faction);
        for (SectorEntityToken e : m.getConnectedEntities()) e.setFaction(faction);
        PersonAPI admin = m.getAdmin();
        if (admin == null || !admin.getMemoryWithoutUpdate().getBoolean(STAND_IN) || !faction.equals(admin.getFaction().getId())) {
            admin = Global.getFactory().createPerson();
            admin.setFaction(faction);
            admin.getName().setFirst(owner);
            admin.getName().setLast("");
            admin.setRankId(Ranks.SPACE_CAPTAIN);
            admin.setPostId(Ranks.POST_ADMINISTRATOR);
            admin.getMemoryWithoutUpdate().set(STAND_IN, true);
            m.setAdmin(admin);
        }
        log.info("[MultiSector] colony " + m.getName() + " is " + owner + "'s (" + faction + "): visit and trade only");
    }

    /** Their stability as their game has it, whatever ours would make of it. */
    private static void holdStability(MarketAPI m) {
        MemoryAPI mem = m.getMemoryWithoutUpdate();
        if (!mem.contains(STABILITY)) return;
        float want = mem.getFloat(STABILITY);
        MutableStat st = m.getStability();
        st.unmodifyFlat(STABILITY_MOD);
        float diff = want - st.getModifiedValue();
        if (Math.abs(diff) > 0.01f) st.modifyFlat(STABILITY_MOD, diff, "As its owner's colony is");
    }

    // ================================================================ telling the others

    /** Our colonies, to everyone: when they change, and once a minute for anyone who's missing them. */
    private static void sendOurs(Session s, long now) {
        String me = me(s);
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (!isColony(m) || !me.equals(ownerOf(m)) || !m.isPlayerOwned()) continue;
            try {
                JSONObject j = describe(m, me);
                if (j == null) continue;
                String text = j.toString();
                String key = m.getId();
                Long at = sentAt.get(key);
                if (text.equals(sent.get(key)) && at != null && now - at < RESEND_NANOS) continue;
                boolean changed = !text.equals(sent.get(key));
                sent.put(key, text);
                sentAt.put(key, now);
                s.sendWorldEvent(text);
                if (changed) log.info("[MultiSector] sent our colony " + m.getName() + " (size " + m.getSize() + ", "
                        + m.getIndustries().size() + " industries)");
            } catch (Exception e) {
                log.warn("[MultiSector] couldn't send our colony " + m.getName(), e);
            }
        }
    }

    private static JSONObject describe(MarketAPI m, String owner) throws Exception {
        SectorEntityToken p = m.getPrimaryEntity();
        if (p == null || p.getContainingLocation() == null || p.getId() == null) return null;
        JSONObject j = new JSONObject();
        j.put("e", "colony").put("loc", FleetSync.locationId(p.getContainingLocation())).put("planet", p.getId());
        j.put("owner", owner).put("name", m.getName()).put("size", m.getSize()).put("freePort", m.isFreePort());
        j.put("stability", Math.round(m.getStabilityValue()));
        JSONArray inds = new JSONArray();
        for (Industry ind : m.getIndustries()) {
            JSONObject o = new JSONObject().put("id", ind.getId());
            if (ind.isBuilding() && !ind.isUpgrading()) o.put("building", true);
            if (ind.getAICoreId() != null) o.put("core", ind.getAICoreId());
            if (ind.isImproved()) o.put("improved", true);
            SpecialItemData item = ind.getSpecialItem();
            if (item != null) o.put("item", item.getId()).put("itemData", item.getData() == null ? "" : item.getData());
            inds.put(o);
        }
        j.put("industries", inds);
        SubmarketAPI storage = m.getSubmarket(Submarkets.SUBMARKET_STORAGE);
        if (storage != null) j.put("storage", storageOf(storage.getCargo()));
        return j;
    }

    private static JSONObject storageOf(CargoAPI c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("cargo", MarketSync.stacks(c));
        JSONArray ships = new JSONArray();
        if (c.getMothballedShips() != null) {
            for (FleetMemberAPI m : c.getMothballedShips().getMembersListCopy()) ships.put(ShipJson.ship(m));
        }
        o.put("ships", ships);
        return o;
    }

    private static void fillStorage(CargoAPI c, JSONObject o) throws Exception {
        c.clear();
        if (c.getMothballedShips() != null) c.getMothballedShips().clear();
        JSONArray stacks = o.optJSONArray("cargo");
        for (int i = 0; stacks != null && i < stacks.length(); i++) MarketSync.add(c, stacks.getJSONObject(i));
        JSONArray ships = o.optJSONArray("ships");
        List<String> skipped = new ArrayList<String>();
        for (int i = 0; ships != null && i < ships.length(); i++) {
            FleetMemberAPI m = ShipJson.buildShip(ships.getJSONObject(i), skipped);
            if (m != null && c.getMothballedShips() != null) c.getMothballedShips().addFleetMember(m);
        }
        if (!skipped.isEmpty()) log.info("[MultiSector] storage: missing here: " + skipped);
    }

    // ================================================================ from the other games

    /** Colony and players' faction messages ride the world-object channel. */
    static boolean handles(JSONObject j) {
        String e = j.optString("e");
        return "colony".equals(e) || "colonygone".equals(e) || "colonytaken".equals(e) || "captains".equals(e)
                || "captainlook".equals(e);
    }

    static void apply(JSONObject j) {
        try {
            String e = j.optString("e");
            Session s = Session.get();
            if ("captains".equals(e)) {
                if (isHost(s)) return; // ours to say
                if (j.has("slots")) keep(SLOTS_KEY, j.getJSONObject("slots"));
                if (j.has("looks")) keep(LOOKS_KEY, j.getJSONObject("looks"));
                return;
            }
            if ("captainlook".equals(e)) {
                if (!isHost(s)) return; // the host passes it on in its list
                String owner = j.optString("owner", "");
                JSONObject look = j.optJSONObject("look");
                if (owner.isEmpty() || look == null) return;
                JSONObject looks = looks();
                looks.put(owner, look);
                keep(LOOKS_KEY, looks);
                assign(s, owner);
                return;
            }
            LocationAPI loc = FleetSync.findLocation(j.optString("loc"));
            SectorEntityToken planet = loc == null ? null : loc.getEntityById(j.optString("planet"));
            MarketAPI m = planet == null ? null : planet.getMarket();
            if (m == null) return; // a place this copy of the world doesn't have
            String owner = j.optString("owner", "");
            SectorEntityToken talking = NpcSync.dialogTarget();
            if (talking != null && talking.getMarket() == m) {
                deferred.add(j); // never change a colony under someone docked there
                return;
            }
            if ("colonytaken".equals(e)) {
                if (owner.equals(ownerOf(m))) lose(m, owner, j.optString("faction"));
                return;
            }
            if (owner.equals(me(s))) return; // ours: our game runs it
            if ("colonygone".equals(e)) {
                if (!m.isPlanetConditionMarketOnly() && !m.isPlayerOwned() && owner.equals(ownerOf(m))) {
                    DecivTracker.removeColony(m, false);
                    m.getMemoryWithoutUpdate().unset(OWNER);
                    m.getMemoryWithoutUpdate().unset(STORAGE);
                    m.getMemoryWithoutUpdate().unset(STABILITY);
                    m.getStability().unmodifyFlat(STABILITY_MOD);
                    log.info("[MultiSector] " + owner + " abandoned " + m.getName());
                }
                return;
            }
            String faction = isHost(s) ? assign(s, owner) : factionOf(owner);
            if (faction == null) {
                deferred.add(j); // the host hasn't told us their faction yet
                return;
            }
            boolean fresh = m.isPlanetConditionMarketOnly() || !m.isInEconomy();
            if (fresh) {
                found(m, planet, j, faction);
            } else if (!isColony(m)) {
                log.info("[MultiSector] " + owner + "'s colony at " + planet.getName() + " is an NPC faction's market here;"
                        + " leaving it");
                return;
            }
            m.getMemoryWithoutUpdate().set(OWNER, owner);
            update(m, j);
            theirs(m, owner, faction);
        } catch (Exception ex) {
            log.warn("[MultiSector] couldn't apply another player's colony", ex);
        }
    }

    /** Another player founded a colony: the same on our copy, as the game makes a new one. */
    private static void found(MarketAPI m, SectorEntityToken planet, JSONObject j, String faction) {
        int size = Math.max(1, j.optInt("size", 3));
        m.setSize(size);
        m.addCondition(POPULATION + size);
        m.setFactionId(faction);
        m.setPlanetConditionMarketOnly(false);
        if (m.hasCondition(Conditions.DECIVILIZED)) {
            m.removeCondition(Conditions.DECIVILIZED);
            m.addCondition(Conditions.DECIVILIZED_SUBPOP);
        }
        if (!m.hasIndustry(Industries.POPULATION)) m.addIndustry(Industries.POPULATION);
        m.addSubmarket(Submarkets.SUBMARKET_OPEN);
        m.getMemoryWithoutUpdate().set(ADDED_OPEN, true);
        m.setSurveyLevel(MarketAPI.SurveyLevel.FULL);
        for (MarketConditionAPI c : m.getConditions()) c.setSurveyed(true);
        Global.getSector().getEconomy().addMarket(m, true);
        planet.setFaction(faction);
        m.setPlayerOwned(false);
        log.info("[MultiSector] " + j.optString("owner") + " founded " + j.optString("name") + " at " + planet.getName());
        Session.get().notice(j.optString("owner") + " founded a colony: " + j.optString("name") + ".");
    }

    /** As its owner has it: name, size, stability, what's built, free port, and their storage (kept out of reach). */
    private static void update(MarketAPI m, JSONObject j) throws Exception {
        String name = j.optString("name", "");
        if (!name.isEmpty() && !name.equals(m.getName())) m.setName(name);
        int size = j.optInt("size", m.getSize());
        if (size != m.getSize() && size > 0) {
            for (MarketConditionAPI c : new ArrayList<MarketConditionAPI>(m.getConditions())) {
                if (c.getId().startsWith(POPULATION)) m.removeSpecificCondition(c.getIdForPluginModifications());
            }
            m.setSize(size);
            m.addCondition(POPULATION + size);
        }
        if (j.has("stability")) m.getMemoryWithoutUpdate().set(STABILITY, (float) j.optDouble("stability"));
        if (j.has("freePort") && m.isFreePort() != j.optBoolean("freePort")) m.setFreePort(j.optBoolean("freePort"));
        JSONArray inds = j.optJSONArray("industries");
        if (inds != null) {
            Set<String> want = new HashSet<String>();
            for (int i = 0; i < inds.length(); i++) want.add(inds.getJSONObject(i).optString("id"));
            for (Industry ind : new ArrayList<Industry>(m.getIndustries())) {
                if (!want.contains(ind.getId())) m.removeIndustry(ind.getId(), null, false);
            }
            for (int i = 0; i < inds.length(); i++) {
                JSONObject o = inds.getJSONObject(i);
                String id = o.optString("id");
                if (Global.getSettings().getIndustrySpec(id) == null) continue; // from a mod this game doesn't have
                if (!m.hasIndustry(id)) {
                    m.addIndustry(id);
                    Industry added = m.getIndustry(id);
                    if (added != null && o.optBoolean("building") && !added.isBuilding()) added.startBuilding();
                }
                Industry ind = m.getIndustry(id);
                if (ind == null) continue;
                if (!o.optBoolean("building") && ind.isBuilding() && !ind.isUpgrading()) ind.finishBuildingOrUpgrading();
                String core = o.optString("core", "");
                String had = ind.getAICoreId() == null ? "" : ind.getAICoreId();
                if (!core.equals(had)) ind.setAICoreId(core.isEmpty() ? null : core);
                if (ind.isImproved() != o.optBoolean("improved")) ind.setImproved(o.optBoolean("improved"));
                String item = o.optString("item", "");
                SpecialItemData now = ind.getSpecialItem();
                if (item.isEmpty()) {
                    if (now != null) ind.setSpecialItem(null);
                } else if (now == null || !item.equals(now.getId())) {
                    String data = o.optString("itemData", "");
                    ind.setSpecialItem(new SpecialItemData(item, data.isEmpty() ? null : data));
                }
            }
        }
        JSONObject storage = j.optJSONObject("storage");
        if (storage != null) m.getMemoryWithoutUpdate().set(STORAGE, storage.toString());
    }

    // ================================================================ founding and abandoning, on the founder's game

    public void reportPlayerColonizedPlanet(PlanetAPI planet) {
        Session s = Session.get();
        MarketAPI m = planet == null ? null : planet.getMarket();
        if (m == null || !s.isActive()) return;
        m.getMemoryWithoutUpdate().set(OWNER, me(s));
        sent.remove(m.getId()); // sent at the next look
        log.info("[MultiSector] we founded " + m.getName());
    }

    public void reportPlayerAbandonedColony(MarketAPI m) {
        Session s = Session.get();
        if (m == null || !s.isActive()) return;
        String owner = ownerOf(m);
        if (owner != null && !owner.equals(me(s))) return;
        try {
            SectorEntityToken p = m.getPrimaryEntity();
            s.sendWorldEvent(new JSONObject().put("e", "colonygone").put("loc", FleetSync.locationId(p.getContainingLocation()))
                    .put("planet", p.getId()).put("owner", me(s)).toString());
            log.info("[MultiSector] we abandoned " + m.getName());
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't tell the others we abandoned " + m.getName(), e);
        }
        m.getMemoryWithoutUpdate().unset(OWNER);
        sent.remove(m.getId());
        sentAt.remove(m.getId());
    }

    /** A save is loading. */
    public static void forget() {
        tookStock = false;
        sent.clear();
        sentAt.clear();
        deferred.clear();
        lookSent = null;
        captainsSent = null;
    }
}
