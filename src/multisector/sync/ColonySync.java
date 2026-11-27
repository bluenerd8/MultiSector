package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CharacterDataAPI;
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
import com.fs.starfarer.api.characters.AdminData;
import com.fs.starfarer.api.characters.MarketConditionSpecAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.econ.RecentUnrest;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.econ.impl.ConstructionQueue;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker;
import com.fs.starfarer.api.impl.campaign.population.CoreImmigrationPluginImpl;
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
 * <p><b>Your game runs your colonies.</b> They're normal player colonies,
 * yours to manage, your income. Your game sends everything about them when it
 * changes (and once a minute anyway): name, size, stability, free port,
 * what's built and how far along construction is, disruption, unrest, hazard
 * pay, growth, the build queue, the planet's conditions, your administrator,
 * and your storage. It also sends your faction's look (the name and flag you
 * gave it), your standing with every NPC faction, and the administrators
 * you've hired but not placed.
 *
 * <p><b>The host keeps it all in its save.</b> A friend's world is the host's
 * save, downloaded fresh each session, so the host's copy of your colony is
 * what you get back next time: everything above comes back exactly as you
 * left it, along with your faction's name and flag and your hired admins.
 *
 * <p><b>Everyone else's game:</b> your colonies belong to "your" faction
 * there: one of ten factions kept for players ({@code multisector_captain_0}
 * to {@code _9}, one per mark colour), named and flagged as you named and
 * flagged yours, with your administrator. It's always friendly with every
 * player, and stands with NPC factions as you do; Nexerelin's diplomacy
 * leaves it alone. Visitors can dock and trade, not manage it, and its income
 * isn't theirs. It doesn't run itself there: size, stability, construction,
 * disruption and unrest are held at what your game says.
 *
 * <p><b>The host's game is where NPC factions' moves count.</b> On the host's
 * game, your colony can be raided, bombarded and invaded like any other. A
 * raid or bombardment there reaches your colony (the same unrest, the same
 * industries disrupted, the same size lost); an invasion there takes it on
 * every game, yours too; if it's ruined there, it's ruined for you. On every
 * other game (yours included) Nexerelin can't invade player colonies while
 * you're connected, so there's only one invasion to fear, the host's.
 * Raids your own game's NPC factions make on your colony still count too, and
 * reach the host like everything else your colony does. While you're away,
 * what happens to your colony on the host's game stays on it, so it's there
 * when you come back.
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
    /** Everything else the owner last said about it (admin, growth, construction...): for holding it, and for them. */
    private static final String STATE = "$multisector_ownerState";
    /** We gave it an open market (a player colony has none), to take off again when it's ours. */
    private static final String ADDED_OPEN = "$multisector_addedOpenMarket";
    /** Marks the administrator we put on other players' colonies (theirs, as they have it), and which one it is. */
    private static final String STAND_IN = "$multisector_standInAdmin";
    private static final String ADMIN_KEY = "$multisector_adminKey";
    /** Nexerelin won't invade a market with this; we set it only where an invasion wouldn't count. */
    private static final String UNINVADABLE = "$nex_uninvadable";
    private static final String PROTECTED = "$multisector_protected";
    /** The game's population conditions: population_1 to population_10. */
    private static final String POPULATION = "population_";

    private static final long SCAN_NANOS = 2_000_000_000L;
    private static final long SEND_NANOS = 5_000_000_000L;
    private static final long RESEND_NANOS = 60_000_000_000L;
    private static final long LOOK_NANOS = 15_000_000_000L;
    private static final long CAPTAINS_NANOS = 10_000_000_000L;
    /** More disruption than the owner said, by this many days, is a hit here (less is just time passing). */
    private static final float HIT_DAYS = 1f;

    private long nextScan, nextSend, nextLook, nextCaptains;
    /** The first look this session: colonies with no owner yet came with the host's world. */
    private static boolean tookStock;
    /** Friend: our faction's look and our admins put back from the host's save (once per load). */
    private static boolean restoredLook;
    /** We may have marked markets uninvadable: unmark them when we're offline. */
    private static boolean protecting = true;
    /** What we last sent of each of our colonies, its storage, and when; our look, and the host's list. */
    private static final Map<String, String> sent = new HashMap<String, String>();
    private static final Map<String, String> sentStorage = new HashMap<String, String>();
    private static final Map<String, Long> sentAt = new HashMap<String, Long>();
    private static String lookSent;
    private static long lookSentAt;
    private static String captainsSent;
    /** Changes to a colony someone's docked at, or of a player whose faction we don't know yet: tried again later. */
    private static final List<JSONObject> deferred = new ArrayList<JSONObject>();
    /** Host: other players' colonies here, to notice one being ruined. */
    private static final Map<String, JSONObject> copies = new HashMap<String, JSONObject>();
    /** Host: hits on other players' colonies here, sent, until their game says it has them. */
    private static final Map<String, List<Hit>> pending = new HashMap<String, List<Hit>>();
    private static long lastSeq;
    /** Owner: the hits from the host we've taken, per colony (said back in our reports). */
    private static final Map<String, List<Long>> applied = new HashMap<String, List<Long>>();
    /** Construction on other players' colonies, held where their game has it (checked every frame). */
    private static final List<Object[]> builds = new ArrayList<Object[]>();

    /** Something an NPC faction did to another player's colony on the host's game. */
    private static final class Hit {
        long seq;
        int size;
        int unrest;
        final Map<String, Float> disrupt = new HashMap<String, Float>();

        boolean isEmpty() {
            return size == 0 && unrest == 0 && disrupt.isEmpty();
        }
    }

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
            builds.clear();
            if (protecting) unprotectAll();
            return;
        }
        long now = System.nanoTime();
        try {
            holdBuilds();
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

    private static boolean online(Session s, String name) {
        for (Session.Player p : s.getPlayers()) if (name.equals(p.name)) return true;
        return false;
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

    private static JSONObject state(MarketAPI m) {
        String text = m.getMemoryWithoutUpdate().getString(STATE);
        if (text == null) return null;
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static PersonAPI person(JSONObject o, String faction) {
        PersonAPI p = Global.getFactory().createPerson();
        ShipJson.applyPerson(p, p.getStats(), o, null);
        p.setFaction(faction);
        p.setPostId(Ranks.POST_ADMINISTRATOR);
        return p;
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

    /** Our faction as we've made it (name, flag, standing with every NPC faction), and the admins we've hired but not placed. */
    private static JSONObject myLook(Session s) throws Exception {
        FactionAPI pf = Global.getSector().getPlayerFaction();
        String name = pf.getDisplayNameOverride();
        boolean named = name != null && !name.isEmpty();
        if (!named) name = me(s);
        JSONObject o = new JSONObject().put("name", name).put("named", named);
        if (pf.getFactionLogoOverride() != null) o.put("logo", pf.getFactionLogoOverride());
        if (pf.getFactionCrestOverride() != null) o.put("crest", pf.getFactionCrestOverride());
        JSONObject rel = new JSONObject();
        for (FactionAPI f : Global.getSector().getAllFactions()) {
            if (f == pf || isPlayersFaction(f.getId())) continue;
            rel.put(f.getId(), Math.round(pf.getRelationship(f.getId()) * 100f) / 100f);
        }
        o.put("rel", rel);
        JSONArray admins = new JSONArray();
        for (AdminData ad : Global.getSector().getCharacterData().getAdmins()) {
            PersonAPI p = ad.getPerson();
            if (ad.getMarket() != null || p == null || p.isAICore()) continue; // placed ones go with their colony
            admins.put(ShipJson.person(p, p.getStats()));
        }
        o.put("admins", admins);
        return o;
    }

    /** Friend: our look to the host, which keeps it and passes it on. */
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

    /**
     * Friend, once per load: our world is the host's save, so its player faction wears the host's name and flag and
     * has the host's admins. Ours, as the host kept them, go back on instead.
     */
    private static void restoreLook(String me) {
        JSONObject look = looks().optJSONObject(me);
        FactionAPI pf = Global.getSector().getPlayerFaction();
        boolean named = look != null && look.optBoolean("named");
        String name = named ? look.optString("name", "") : "";
        pf.setDisplayNameOverride(name.isEmpty() ? null : name);
        pf.setDisplayNameWithArticleOverride(name.isEmpty() ? null : name);
        String logo = look == null ? "" : look.optString("logo", "");
        String crest = look == null ? "" : look.optString("crest", "");
        pf.setFactionLogoOverride(logo.isEmpty() ? null : logo);
        pf.setFactionCrestOverride(crest.isEmpty() ? null : crest);
        CharacterDataAPI cd = Global.getSector().getCharacterData();
        for (AdminData ad : new ArrayList<AdminData>(cd.getAdmins())) cd.removeAdmin(ad.getPerson());
        JSONArray admins = look == null ? null : look.optJSONArray("admins");
        int n = 0;
        for (int i = 0; admins != null && i < admins.length(); i++) {
            JSONObject a = admins.optJSONObject(i);
            if (a == null) continue;
            cd.addAdmin(person(a, Factions.PLAYER));
            n++;
        }
        log.info("[MultiSector] our faction is " + (name.isEmpty() ? "unnamed" : name) + " again, with " + n
                + " hired admins waiting");
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
        if (!hosting && !restoredLook) {
            restoredLook = true;
            try {
                restoreLook(me);
            } catch (RuntimeException e) {
                log.warn("[MultiSector] couldn't put back our faction's look", e);
            }
        }
        builds.clear();
        Set<String> seen = new HashSet<String>();
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
            // Only the host's Nexerelin may invade a player's colony: everywhere else it wouldn't count.
            protect(m, !hosting);
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
                if (faction == null) continue;
                theirs(m, owner, faction);
                hold(s, m, owner, hosting);
                if (hosting) {
                    seen.add(m.getId());
                    remember(m, owner);
                }
            }
        }
        tookStock = true;
        if (hosting) ruined(s, seen);
        dress();
    }

    private static void protect(MarketAPI m, boolean on) {
        MemoryAPI mem = m.getMemoryWithoutUpdate();
        if (on) {
            protecting = true;
            if (!mem.getBoolean(UNINVADABLE)) {
                mem.set(UNINVADABLE, true);
                mem.set(PROTECTED, true);
            }
        } else if (mem.getBoolean(PROTECTED)) {
            mem.unset(UNINVADABLE);
            mem.unset(PROTECTED);
        }
    }

    private static void unprotectAll() {
        protecting = false;
        try {
            for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) protect(m, false);
        } catch (RuntimeException e) {
            log.warn("[MultiSector] couldn't unmark colonies", e);
        }
    }

    private static void remember(MarketAPI m, String owner) {
        if (copies.containsKey(m.getId())) return;
        SectorEntityToken p = m.getPrimaryEntity();
        if (p == null || p.getContainingLocation() == null) return;
        try {
            copies.put(m.getId(), new JSONObject().put("loc", FleetSync.locationId(p.getContainingLocation()))
                    .put("planet", p.getId()).put("owner", owner).put("name", m.getName()));
        } catch (Exception ignored) {
            // never with these values
        }
    }

    /** Host: another player's colony gone from the world here without them abandoning it (ruined): ruined for them too. */
    private static void ruined(Session s, Set<String> seen) {
        for (Iterator<Map.Entry<String, JSONObject>> it = copies.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, JSONObject> e = it.next();
            if (seen.contains(e.getKey())) continue;
            JSONObject c = e.getValue();
            LocationAPI loc = FleetSync.findLocation(c.optString("loc"));
            SectorEntityToken planet = loc == null ? null : loc.getEntityById(c.optString("planet"));
            MarketAPI m = planet == null ? null : planet.getMarket();
            if (m != null && m.isInEconomy() && !m.isPlanetConditionMarketOnly()) {
                if (isColony(m)) continue; // still theirs, just not looked at this time
                it.remove(); // someone else's now: that was said already
                continue;
            }
            it.remove();
            pending.remove(e.getKey());
            try {
                s.sendWorldEvent(new JSONObject().put("e", "colonydeciv").put("loc", c.optString("loc"))
                        .put("planet", c.optString("planet")).put("owner", c.optString("owner")).toString());
                log.info("[MultiSector] " + c.optString("owner") + "'s colony " + c.optString("name") + " was ruined here");
            } catch (Exception ex) {
                log.warn("[MultiSector] couldn't tell the others a colony was ruined", ex);
            }
        }
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

    private static void forgetColony(MarketAPI m) {
        MemoryAPI mem = m.getMemoryWithoutUpdate();
        mem.unset(OWNER);
        mem.unset(STORAGE);
        mem.unset(STABILITY);
        mem.unset(STATE);
        if (mem.getBoolean(PROTECTED)) {
            mem.unset(UNINVADABLE);
            mem.unset(PROTECTED);
        }
        m.getStability().unmodifyFlat(STABILITY_MOD);
        copies.remove(m.getId());
        pending.remove(m.getId());
    }

    /** It's an NPC faction's colony now: no player's any more. */
    private static void lose(MarketAPI m, String owner, String byFaction) {
        FactionAPI by = Global.getSector().getFaction(byFaction);
        if (by == null) return;
        forgetColony(m);
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

    /** Ours: a normal player colony (back from being another player's copy, if it was: as we left it). */
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
        try {
            restore(m);
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't put back everything about " + m.getName(), e);
        }
        PersonAPI admin = m.getAdmin();
        if (admin == null || admin.getMemoryWithoutUpdate().getBoolean(STAND_IN)) {
            m.setAdmin(Global.getSector().getPlayerPerson());
        }
        log.info("[MultiSector] colony " + m.getName() + " is ours to run");
        Session.get().notice("Your colony " + m.getName() + " is yours to run.");
    }

    /** Ours again, from the host's copy: the rest of it as we last sent it (size, buildings and conditions are already on it). */
    private static void restore(MarketAPI m) throws Exception {
        JSONObject st = state(m);
        m.getMemoryWithoutUpdate().unset(STATE);
        if (st == null) return;
        JSONObject a = st.optJSONObject("admin");
        if (!st.optBoolean("adminSelf") && a != null) {
            PersonAPI p = person(a, Factions.PLAYER);
            m.setAdmin(p);
            if (!p.isAICore()) {
                CharacterDataAPI cd = Global.getSector().getCharacterData();
                cd.addAdmin(p);
                for (AdminData ad : cd.getAdmins()) if (ad.getPerson() == p) ad.setMarket(m);
            }
        }
        if (st.has("incentives")) m.setImmigrationIncentivesOn(st.optBoolean("incentives"));
        if (st.has("incentiveCredits")) m.setIncentiveCredits((float) st.optDouble("incentiveCredits", 0));
        if (st.has("stockpiles")) m.setUseStockpilesForShortages(st.optBoolean("stockpiles"));
        JSONObject pop = st.optJSONObject("pop");
        Iterator<?> pi = pop == null ? null : pop.keys();
        while (pi != null && pi.hasNext()) {
            String id = (String) pi.next();
            m.getPopulation().set(id, (float) pop.optDouble(id, 0));
        }
        if (st.has("growth")) m.getPopulation().setWeight((float) st.optDouble("growth"));
        JSONArray q = st.optJSONArray("queue");
        if (q != null) {
            List<ConstructionQueue.ConstructionQueueItem> items = new ArrayList<ConstructionQueue.ConstructionQueueItem>();
            for (int i = 0; i < q.length(); i++) {
                JSONObject o = q.getJSONObject(i);
                if (Global.getSettings().getIndustrySpec(o.optString("id")) == null) continue;
                items.add(new ConstructionQueue.ConstructionQueueItem(o.optString("id"), o.optInt("cost")));
            }
            m.getConstructionQueue().setItems(items);
        }
        JSONArray inds = st.optJSONArray("industries");
        for (int i = 0; inds != null && i < inds.length(); i++) {
            JSONObject o = inds.getJSONObject(i);
            Industry ind = m.getIndustry(o.optString("id"));
            if (ind instanceof BaseIndustry && ind.isBuilding() && o.has("progress")) {
                ((BaseIndustry) ind).setBuildProgress((float) o.optDouble("progress"));
            }
        }
        log.info("[MultiSector] put back " + m.getName() + " as we left it");
    }

    /** Another player's: their faction's, theirs to run; ours to visit and trade at. */
    private static void theirs(MarketAPI m, String owner, String faction) {
        MemoryAPI mem = m.getMemoryWithoutUpdate();
        holdStability(m);
        if (!faction.equals(m.getFactionId()) || m.isPlayerOwned()) {
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
            log.info("[MultiSector] colony " + m.getName() + " is " + owner + "'s (" + faction + "): visit and trade only");
        }
        theirAdmin(m, owner, faction);
    }

    /** Their administrator as they have it (or one in their name, if they govern it themselves). */
    private static void theirAdmin(MarketAPI m, String owner, String faction) {
        JSONObject st = state(m);
        JSONObject a = st == null ? null : st.optJSONObject("admin");
        String key = faction + ":" + owner + ":" + (a == null ? "" : String.valueOf(a.toString().hashCode()));
        PersonAPI admin = m.getAdmin();
        if (admin != null && admin.getMemoryWithoutUpdate().getBoolean(STAND_IN)
                && key.equals(admin.getMemoryWithoutUpdate().getString(ADMIN_KEY))) return;
        if (a != null) {
            admin = person(a, faction);
        } else {
            admin = Global.getFactory().createPerson();
            admin.setFaction(faction);
            admin.getName().setFirst(owner);
            admin.getName().setLast("");
            admin.setRankId(Ranks.SPACE_CAPTAIN);
            admin.setPostId(Ranks.POST_ADMINISTRATOR);
        }
        admin.getMemoryWithoutUpdate().set(STAND_IN, true);
        admin.getMemoryWithoutUpdate().set(ADMIN_KEY, key);
        m.setAdmin(admin);
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

    /**
     * Another player's colony, held where their game has it: size, disruption, unrest, construction. On the host's
     * game, more than they have is an NPC faction's doing here, and that counts: it goes to their game (or, while
     * they're away, stays on the colony here, which is what they get back).
     */
    private static void hold(Session s, MarketAPI m, String owner, boolean hosting) {
        JSONObject st = state(m);
        if (st == null) return;
        boolean live = hosting && online(s, owner);
        boolean away = hosting && !live;
        List<Hit> pend = live ? pending.get(m.getId()) : null;
        if (hosting && !live) pending.remove(m.getId());
        int pendSize = 0, pendUnrest = 0;
        Map<String, Float> pendDisrupt = new HashMap<String, Float>();
        for (int i = 0; pend != null && i < pend.size(); i++) {
            Hit h = pend.get(i);
            pendSize += h.size;
            pendUnrest += h.unrest;
            for (Map.Entry<String, Float> e : h.disrupt.entrySet()) {
                Float had = pendDisrupt.get(e.getKey());
                pendDisrupt.put(e.getKey(), (had == null ? 0f : had) + e.getValue());
            }
        }
        Hit hit = new Hit();
        boolean rebased = false;
        try {
            int want = st.optInt("size", m.getSize()) + pendSize;
            if (want > 0 && m.getSize() != want) {
                if (m.getSize() < want && live) {
                    hit.size = m.getSize() - want;
                } else if (m.getSize() < want && away) {
                    st.put("size", m.getSize());
                    rebased = true;
                } else {
                    setSize(m, want);
                }
            }
            JSONArray inds = st.optJSONArray("industries");
            for (int i = 0; inds != null && i < inds.length(); i++) {
                JSONObject o = inds.getJSONObject(i);
                String id = o.optString("id");
                Industry ind = m.getIndustry(id);
                if (ind == null) continue;
                Float pd = pendDisrupt.get(id);
                float wantD = (float) o.optDouble("disrupted", 0) + (pd == null ? 0f : pd);
                float cur = ind.getDisruptedDays();
                if (cur > wantD + HIT_DAYS && live) {
                    hit.disrupt.put(id, cur - wantD);
                } else if (cur > wantD + HIT_DAYS && away) {
                    o.put("disrupted", Math.round(cur));
                    rebased = true;
                } else if (Math.abs(cur - wantD) > HIT_DAYS && ind.canBeDisrupted()) {
                    ind.setDisrupted(wantD, false);
                }
                if (ind instanceof BaseIndustry && ind.isBuilding() && o.has("progress")) {
                    BaseIndustry b = (BaseIndustry) ind;
                    float held = Math.max(0f, Math.min((float) o.optDouble("progress"), b.getBuildTime() - 0.05f));
                    if (Math.abs(b.getBuildProgress() - held) > 0.01f) b.setBuildProgress(held);
                    builds.add(new Object[]{b, held});
                }
            }
            int wantU = st.optInt("unrest", 0) + pendUnrest;
            int curU = RecentUnrest.getPenalty(m);
            if (curU > wantU && live) {
                hit.unrest = curU - wantU;
            } else if (curU > wantU && away) {
                st.put("unrest", curU);
                rebased = true;
            } else if (curU != wantU) {
                RecentUnrest ru = RecentUnrest.get(m, wantU > 0);
                if (ru != null) ru.setPenalty(wantU);
            }
            if (rebased) m.getMemoryWithoutUpdate().set(STATE, st.toString());
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't hold " + m.getName() + " as its owner has it", e);
        }
        if (!hit.isEmpty()) sendHit(s, m, owner, hit);
    }

    /** Their construction doesn't go on by itself here: checked every frame, so it never finishes on its own. */
    private static void holdBuilds() {
        for (Object[] o : builds) {
            BaseIndustry b = (BaseIndustry) o[0];
            float held = (Float) o[1];
            if (b.isBuilding() && b.getBuildProgress() > held + 0.001f) b.setBuildProgress(held);
        }
    }

    private static void setSize(MarketAPI m, int size) {
        for (MarketConditionAPI c : new ArrayList<MarketConditionAPI>(m.getConditions())) {
            if (c.getId().startsWith(POPULATION)) m.removeSpecificCondition(c.getIdForPluginModifications());
        }
        m.setSize(size);
        m.addCondition(POPULATION + size);
    }

    /** Host: an NPC faction hit another player's colony here. It's hit on their game too. */
    private static void sendHit(Session s, MarketAPI m, String owner, Hit hit) {
        SectorEntityToken p = m.getPrimaryEntity();
        if (p == null || p.getContainingLocation() == null) return;
        hit.seq = Math.max(System.currentTimeMillis(), lastSeq + 1);
        lastSeq = hit.seq;
        try {
            JSONObject d = new JSONObject();
            for (Map.Entry<String, Float> e : hit.disrupt.entrySet()) d.put(e.getKey(), Math.round(e.getValue()));
            s.sendWorldEvent(new JSONObject().put("e", "colonyhit").put("loc", FleetSync.locationId(p.getContainingLocation()))
                    .put("planet", p.getId()).put("owner", owner).put("seq", hit.seq).put("size", hit.size)
                    .put("unrest", hit.unrest).put("disrupt", d).toString());
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't tell " + owner + " their colony was hit", e);
            return;
        }
        List<Hit> pend = pending.get(m.getId());
        if (pend == null) pending.put(m.getId(), pend = new ArrayList<Hit>());
        pend.add(hit);
        log.info("[MultiSector] " + owner + "'s colony " + m.getName() + " was hit here: size " + hit.size + ", unrest +"
                + hit.unrest + ", disrupted " + hit.disrupt.keySet());
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
                SubmarketAPI storage = m.getSubmarket(Submarkets.SUBMARKET_STORAGE);
                String store = storage == null ? null : storageOf(storage.getCargo()).toString();
                String key = m.getId();
                Long at = sentAt.get(key);
                boolean changed = !text.equals(sent.get(key));
                boolean storeChanged = store != null && !store.equals(sentStorage.get(key));
                boolean due = at == null || now - at >= RESEND_NANOS;
                if (!changed && !storeChanged && !due) continue;
                // The storage only when it changed, or with the once-a-minute resend: it can be big.
                if (store != null && (storeChanged || due)) j.put("storage", new JSONObject(store));
                sent.put(key, text);
                if (store != null) sentStorage.put(key, store);
                sentAt.put(key, now);
                s.sendWorldEvent(j.toString());
                if (changed) log.info("[MultiSector] sent our colony " + m.getName() + " (size " + m.getSize() + ", "
                        + m.getIndustries().size() + " industries)");
            } catch (Exception e) {
                log.warn("[MultiSector] couldn't send our colony " + m.getName(), e);
            }
        }
    }

    /** All of our colony that isn't storage. Things that tick (days, growth) are rounded, so it's only sent as they move. */
    private static JSONObject describe(MarketAPI m, String owner) throws Exception {
        SectorEntityToken p = m.getPrimaryEntity();
        if (p == null || p.getContainingLocation() == null || p.getId() == null) return null;
        JSONObject j = new JSONObject();
        j.put("e", "colony").put("loc", FleetSync.locationId(p.getContainingLocation())).put("planet", p.getId());
        j.put("owner", owner).put("name", m.getName()).put("size", m.getSize()).put("freePort", m.isFreePort());
        j.put("stability", Math.round(m.getStabilityValue()));
        j.put("unrest", RecentUnrest.getPenalty(m));
        JSONArray inds = new JSONArray();
        for (Industry ind : m.getIndustries()) {
            JSONObject o = new JSONObject().put("id", ind.getId());
            if (ind.isBuilding() && !ind.isUpgrading()) o.put("building", true);
            if (ind.isUpgrading()) o.put("upgrading", true);
            if (ind.isBuilding() && ind instanceof BaseIndustry) {
                o.put("progress", Math.floor(((BaseIndustry) ind).getBuildProgress()));
            }
            if (ind.getDisruptedDays() > 0.5f) o.put("disrupted", Math.round(ind.getDisruptedDays()));
            if (ind.getAICoreId() != null) o.put("core", ind.getAICoreId());
            if (ind.isImproved()) o.put("improved", true);
            SpecialItemData item = ind.getSpecialItem();
            if (item != null) o.put("item", item.getId()).put("itemData", item.getData() == null ? "" : item.getData());
            inds.put(o);
        }
        j.put("industries", inds);
        JSONArray queue = new JSONArray();
        for (ConstructionQueue.ConstructionQueueItem q : m.getConstructionQueue().getItems()) {
            queue.put(new JSONObject().put("id", q.id).put("cost", q.cost));
        }
        j.put("queue", queue);
        JSONArray conds = new JSONArray();
        for (MarketConditionAPI c : m.getConditions()) {
            if (c.getId().startsWith(POPULATION)) continue;
            MarketConditionSpecAPI spec = Global.getSettings().getMarketConditionSpec(c.getId());
            if (spec != null && spec.isPlanetary()) conds.put(c.getId());
        }
        j.put("conds", conds);
        j.put("incentives", m.isImmigrationIncentivesOn()).put("incentiveCredits", Math.round(m.getIncentiveCredits()));
        j.put("stockpiles", m.isUseStockpilesForShortages());
        JSONObject pop = new JSONObject();
        for (Map.Entry<String, Float> e : m.getPopulation().getComp().entrySet()) {
            pop.put(e.getKey(), Math.round(e.getValue() * 10f) / 10f);
        }
        j.put("pop", pop).put("growth", Math.round(m.getPopulation().getWeightValue()));
        PersonAPI admin = m.getAdmin();
        PersonAPI me = Global.getSector().getPlayerPerson();
        if (admin == null || admin == me) {
            // We govern it ourselves: others see us as its administrator.
            JSONObject self = new JSONObject().put("first", me.getName().getFirst()).put("last", me.getName().getLast())
                    .put("gender", me.getGender() == null ? "MALE" : me.getGender().name()).put("portrait", me.getPortraitSprite());
            j.put("adminSelf", true).put("admin", self);
        } else {
            j.put("admin", ShipJson.person(admin, admin.getStats()));
        }
        List<Long> took = applied.get(m.getId());
        if (took != null) j.put("hits", new JSONArray(took));
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
        return "colony".equals(e) || "colonygone".equals(e) || "colonytaken".equals(e) || "colonyhit".equals(e)
                || "colonydeciv".equals(e) || "captains".equals(e) || "captainlook".equals(e);
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
                if (!isHost(s)) return; // the host keeps it and passes it on in its list
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
            if ("colonydeciv".equals(e)) {
                if (!owner.equals(ownerOf(m)) || m.isPlanetConditionMarketOnly()) return;
                boolean mine = owner.equals(me(s));
                forgetColony(m);
                DecivTracker.decivilize(m, false, mine);
                log.info("[MultiSector] " + owner + "'s colony " + m.getName() + " was ruined on the host's game");
                s.notice((mine ? "Your" : owner + "'s") + " colony " + m.getName() + " was ruined on the host's game.");
                return;
            }
            if ("colonyhit".equals(e)) {
                if (owner.equals(me(s)) && owner.equals(ownerOf(m)) && m.isPlayerOwned()) hit(m, j);
                return; // everyone else gets it in the owner's next report
            }
            if (owner.equals(me(s))) return; // ours: our game runs it
            if ("colonygone".equals(e)) {
                if (!m.isPlanetConditionMarketOnly() && !m.isPlayerOwned() && owner.equals(ownerOf(m))) {
                    forgetColony(m);
                    DecivTracker.removeColony(m, false);
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
            if (isHost(s)) acked(m, j.optJSONArray("hits"));
            update(m, j);
            theirs(m, owner, faction);
            hold(s, m, owner, isHost(s));
        } catch (Exception ex) {
            log.warn("[MultiSector] couldn't apply another player's colony", ex);
        }
    }

    /** Host: the hits their game says it has taken are in their numbers now. */
    private static void acked(MarketAPI m, JSONArray hits) {
        List<Hit> pend = pending.get(m.getId());
        if (pend == null || hits == null) return;
        Set<Long> took = new HashSet<Long>();
        for (int i = 0; i < hits.length(); i++) took.add(hits.optLong(i));
        for (Iterator<Hit> it = pend.iterator(); it.hasNext(); ) if (took.contains(it.next().seq)) it.remove();
        if (pend.isEmpty()) pending.remove(m.getId());
    }

    /** Owner: an NPC faction hit our colony on the host's game. The same here. */
    private static void hit(MarketAPI m, JSONObject j) throws Exception {
        long seq = j.optLong("seq");
        List<Long> took = applied.get(m.getId());
        if (took == null) applied.put(m.getId(), took = new ArrayList<Long>());
        if (took.contains(seq)) return;
        took.add(seq);
        while (took.size() > 20) took.remove(0);
        List<String> what = new ArrayList<String>();
        int unrest = j.optInt("unrest");
        if (unrest > 0) {
            RecentUnrest.get(m, true).add(unrest, "Hostile action");
            what.add("stability -" + unrest);
        }
        int size = j.optInt("size");
        for (int i = 0; i < -size; i++) CoreImmigrationPluginImpl.reduceMarketSize(m);
        if (size < 0) what.add("size " + size);
        JSONObject d = j.optJSONObject("disrupt");
        Iterator<?> it = d == null ? null : d.keys();
        int n = 0;
        while (it != null && it.hasNext()) {
            String id = (String) it.next();
            Industry ind = m.getIndustry(id);
            if (ind == null || !ind.canBeDisrupted()) continue;
            ind.setDisrupted(ind.getDisruptedDays() + (float) d.optDouble(id, 0), false);
            n++;
        }
        if (n > 0) what.add(n + (n == 1 ? " industry" : " industries") + " disrupted");
        sent.remove(m.getId()); // tell the host we have it, now
        log.info("[MultiSector] our colony " + m.getName() + " was hit on the host's game: " + what);
        Session.get().notice("Your colony " + m.getName() + " was attacked on the host's game: " + join(what) + ".");
    }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) b.append(b.length() == 0 ? "" : ", ").append(p);
        return b.length() == 0 ? "no lasting harm" : b.toString();
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

    /** As its owner has it: name, size, stability, what's built and how far along, conditions, free port, storage. */
    private static void update(MarketAPI m, JSONObject j) throws Exception {
        String name = j.optString("name", "");
        if (!name.isEmpty() && !name.equals(m.getName())) m.setName(name);
        int size = j.optInt("size", m.getSize());
        if (size != m.getSize() && size > 0) setSize(m, size);
        if (j.has("stability")) m.getMemoryWithoutUpdate().set(STABILITY, (float) j.optDouble("stability"));
        if (j.has("freePort") && m.isFreePort() != j.optBoolean("freePort")) m.setFreePort(j.optBoolean("freePort"));
        JSONArray conds = j.optJSONArray("conds");
        if (conds != null) {
            // The planet's own conditions (a terraforming mod changes them); not the ones events and buildings bring.
            Set<String> want = new HashSet<String>();
            for (int i = 0; i < conds.length(); i++) want.add(conds.optString(i));
            for (MarketConditionAPI c : new ArrayList<MarketConditionAPI>(m.getConditions())) {
                if (c.getId().startsWith(POPULATION) || want.contains(c.getId())) continue;
                MarketConditionSpecAPI spec = Global.getSettings().getMarketConditionSpec(c.getId());
                if (spec != null && spec.isPlanetary()) m.removeSpecificCondition(c.getIdForPluginModifications());
            }
            for (String id : want) {
                if (!m.hasCondition(id) && Global.getSettings().getMarketConditionSpec(id) != null) m.addCondition(id);
            }
        }
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
                boolean upgrading = o.optBoolean("upgrading");
                if (upgrading && !ind.isUpgrading() && ind.getSpec() != null && ind.getSpec().getUpgrade() != null) {
                    ind.startUpgrading();
                } else if (!upgrading && ind.isUpgrading()) {
                    ind.cancelUpgrade();
                }
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
        // The rest (admin, growth, hazard pay, queue, construction, disruption, unrest): held here, and theirs to take back.
        JSONObject st = new JSONObject(j.toString());
        st.remove("storage");
        st.remove("e");
        m.getMemoryWithoutUpdate().set(STATE, st.toString());
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
        forgetColony(m);
        sent.remove(m.getId());
        sentStorage.remove(m.getId());
        sentAt.remove(m.getId());
    }

    /** A save is loading. */
    public static void forget() {
        tookStock = false;
        restoredLook = false;
        protecting = true; // whatever the save has marked, unmarked if we're offline
        sent.clear();
        sentStorage.clear();
        sentAt.clear();
        deferred.clear();
        copies.clear();
        pending.clear();
        applied.clear();
        builds.clear();
        lookSent = null;
        captainsSent = null;
    }
}
