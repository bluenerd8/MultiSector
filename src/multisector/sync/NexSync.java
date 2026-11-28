package multisector.sync;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import multisector.net.Session;
import multisector.nex.Nex;
import multisector.ui.MultiSectorConfig;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nexerelin's world is the host's (phase 1 of sharing it).
 *
 * <p><b>Friends' Nexerelin stands down while connected</b>
 * ({@code nexerelinFromHost}): no invasions, raids, diplomacy, alliances,
 * NPC agents, colony expeditions, strategic AI or NPC colony growth of its
 * own (see {@code NexBridge}). What's personal stays on.
 *
 * <p><b>Who owns which market is the host's</b>: the host sends every NPC
 * market's owner and size (on change, and once a minute), and friends follow:
 * a market that changed hands there changes hands here, through Nexerelin's
 * own transfer; a market the host has that a friend doesn't (a Nexerelin
 * colony expedition's) is founded there; one gone from the host's world (ruined)
 * goes. Players' colonies are {@link ColonySync}'s and left out. A market a
 * friend has captured themselves is theirs: it goes to the host as their colony.
 *
 * <p><b>The host's Nexerelin news reaches everyone</b>: invasions, raids,
 * base strikes, saturation bombardments, rebellions, diplomacy, alliances and
 * colony expeditions show in friends' intel, on the map where they happen,
 * and end when they end on the host's game. One aimed at a player's colony
 * warns its owner.
 */
public final class NexSync implements EveryFrameScript {

    private static final Logger log = Global.getLogger(NexSync.class);
    private static final long OWNERS_NANOS = 10_000_000_000L;
    private static final long RESEND_NANOS = 60_000_000_000L;
    private static final long INTEL_NANOS = 5_000_000_000L;
    /** The host's market lists a market must be missing from before a friend removes it. */
    private static final int MISSES = 2;
    /** Set on a market a friend took themselves, for its first report as their colony. */
    static final String CAPTURED = "$multisector_captured";
    /** Nexerelin's intel worth everyone's knowing, by class name. */
    private static final Set<String> NEWS = new HashSet<String>(java.util.Arrays.asList(
            "InvasionIntel", "CounterInvasionIntel", "RespawnInvasionIntel", "NexRaidIntel", "RemnantRaidIntel",
            "BaseStrikeIntel", "SatBombIntel", "RebellionIntel", "DiplomacyIntel", "AllianceIntel",
            "ColonyExpeditionIntel", "RespawnBaseIntel"));

    private long nextOwners, nextIntel;
    private static boolean following;
    // host
    private static String ownersSent;
    private static long ownersAt;
    private static final Map<IntelInfoPlugin, String> newsIds = new IdentityHashMap<IntelInfoPlugin, String>();
    private static final Map<String, String> newsSent = new HashMap<String, String>();
    private static long newsAt;
    private static int newsCount;
    // friend
    private static final Map<String, Integer> misses = new HashMap<String, Integer>();
    private static final Set<String> asked = new HashSet<String>();
    private static final Map<String, Mirror> mirrors = new HashMap<String, Mirror>();

    public boolean isDone() {
        return false;
    }

    public boolean runWhilePaused() {
        return true;
    }

    public static void forget() {
        ownersSent = null;
        newsIds.clear();
        newsSent.clear();
        misses.clear();
        asked.clear();
        mirrors.clear();
    }

    public void advance(float amount) {
        Session s = Session.get();
        boolean hosting = s.getState() == Session.State.HOSTING;
        boolean friend = s.getState() == Session.State.CONNECTED && s.getMyId() >= 0;
        boolean follow = friend && MultiSectorConfig.isNexerelinFromHost();
        if (follow != following) {
            following = follow;
            Nex.followHost(follow);
            if (!follow) endMirrors();
        }
        if (!hosting) return;
        long now = System.nanoTime();
        try {
            if (now - nextOwners >= 0) {
                nextOwners = now + OWNERS_NANOS;
                sendOwners(s, now);
            }
            if (now - nextIntel >= 0) {
                nextIntel = now + INTEL_NANOS;
                sendNews(s, now);
            }
        } catch (Exception e) {
            log.warn("[MultiSector] sharing the host's Nexerelin world failed this time", e);
        }
    }

    static boolean handles(JSONObject j) {
        String e = j.optString("e");
        return "nexowners".equals(e) || "nexask".equals(e) || "nexmarket".equals(e) || "nexintel".equals(e);
    }

    static void apply(JSONObject j) {
        Session s = Session.get();
        boolean hosting = s.getState() == Session.State.HOSTING;
        String e = j.optString("e");
        try {
            if ("nexask".equals(e)) {
                if (hosting) answer(s, j);
                return;
            }
            if (hosting || !MultiSectorConfig.isNexerelinFromHost()) return; // the host's to say; and only if we follow it
            if ("nexowners".equals(e)) owners(s, j);
            else if ("nexmarket".equals(e)) found(j);
            else if ("nexintel".equals(e)) news(s, j);
        } catch (Exception ex) {
            log.warn("[MultiSector] couldn't follow the host's Nexerelin world (" + e + ")", ex);
        }
    }

    // ================================================================ which markets, whose

    /** An NPC faction's market everyone has: not a player's colony, not hidden (bases are each game's own for now). */
    private static boolean shared(MarketAPI m) {
        if (m == null || m.isPlanetConditionMarketOnly() || !m.isInEconomy() || m.isHidden()) return false;
        if (m.isPlayerOwned() || Factions.PLAYER.equals(m.getFactionId()) || ColonySync.isPlayersFaction(m.getFactionId())) return false;
        if (m.getMemoryWithoutUpdate().contains(ColonySync.OWNER)) return false;
        SectorEntityToken p = m.getPrimaryEntity();
        return p != null && p.getContainingLocation() != null && p.getId() != null;
    }

    private static String key(MarketAPI m) {
        SectorEntityToken p = m.getPrimaryEntity();
        return FleetSync.locationId(p.getContainingLocation()) + "|" + p.getId();
    }

    private static SectorEntityToken entityOf(String key) {
        int bar = key.lastIndexOf('|');
        if (bar < 0) return null;
        LocationAPI loc = FleetSync.findLocation(key.substring(0, bar));
        return loc == null ? null : loc.getEntityById(key.substring(bar + 1));
    }

    /** Host: every NPC market's owner and size. */
    private static void sendOwners(Session s, long now) throws Exception {
        JSONObject m = new JSONObject();
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (!shared(market)) continue;
            m.put(key(market), market.getFactionId() + "|" + market.getSize());
        }
        String text = m.toString();
        if (text.equals(ownersSent) && now - ownersAt < RESEND_NANOS) return;
        ownersSent = text;
        ownersAt = now;
        s.sendWorldEvent(new JSONObject().put("e", "nexowners").put("m", m).toString());
    }

    /** Friend: markets as the host has them. */
    private static void owners(Session s, JSONObject j) throws Exception {
        JSONObject list = j.getJSONObject("m");
        SectorEntityToken talking = NpcSync.dialogTarget();
        List<String> want = new ArrayList<String>();
        Set<String> listed = new HashSet<String>();
        Iterator<?> it = list.keys();
        while (it.hasNext()) {
            String key = (String) it.next();
            listed.add(key);
            String[] v = list.optString(key).split("\\|");
            if (v.length < 2) continue;
            FactionAPI to = Global.getSector().getFaction(v[0]);
            int size = Integer.parseInt(v[1]);
            SectorEntityToken entity = entityOf(key);
            if (entity == null || to == null) continue; // a place or faction this game doesn't have
            MarketAPI m = entity.getMarket();
            if (m == null || m.isPlanetConditionMarketOnly() || !m.isInEconomy()) {
                if (entity instanceof PlanetAPI && !asked.contains(key)) want.add(key); // founded on the host's game
                continue;
            }
            if (talking != null && talking.getMarket() == m) continue; // never under someone docked there; next time
            if (m.isPlayerOwned() || Factions.PLAYER.equals(m.getFactionId())) {
                // We took it ourselves (our own invasion): it goes to the host as our colony.
                if (!m.getMemoryWithoutUpdate().getBoolean(CAPTURED)) {
                    m.getMemoryWithoutUpdate().set(CAPTURED, true);
                    log.info("[MultiSector] we took " + m.getName() + "; it goes to the host as our colony");
                }
                continue;
            }
            if (ColonySync.isPlayersFaction(m.getFactionId()) || m.getMemoryWithoutUpdate().contains(ColonySync.OWNER)) continue;
            if (!to.getId().equals(m.getFactionId())) {
                FactionAPI from = m.getFaction();
                Nex.transfer(m, to, true);
                log.info("[MultiSector] " + m.getName() + " is " + to.getDisplayName() + "'s now, as on the host's game (was "
                        + from.getDisplayName() + "'s)");
                s.notice(m.getName() + " changed hands on the host's world: " + to.getDisplayNameWithArticle() + " holds it now.");
            }
            if (size > 0 && size != m.getSize() && size <= 10) setSize(m, size);
        }
        // Gone from the host's world (ruined, or abandoned there): gone here too, once it's missing twice in a row.
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (!shared(m) || !(m.getPrimaryEntity() instanceof PlanetAPI)) continue;
            String key = key(m);
            if (listed.contains(key)) {
                misses.remove(key);
                continue;
            }
            int n = misses.containsKey(key) ? misses.get(key) + 1 : 1;
            misses.put(key, n);
            if (n < MISSES || (talking != null && talking.getMarket() == m)) continue;
            misses.remove(key);
            log.info("[MultiSector] " + m.getName() + " is gone from the host's world; ruined here too");
            s.notice(m.getName() + " fell on the host's world.");
            DecivTracker.decivilize(m, false, false);
        }
        if (!want.isEmpty()) {
            asked.addAll(want);
            s.sendWorldEvent(new JSONObject().put("e", "nexask").put("keys", new JSONArray(want)).toString());
        }
    }

    private static void setSize(MarketAPI m, int size) {
        for (MarketConditionAPI c : new ArrayList<MarketConditionAPI>(m.getConditions())) {
            if (c.getId().startsWith("population_")) m.removeSpecificCondition(c.getIdForPluginModifications());
        }
        m.setSize(size);
        m.addCondition("population_" + size);
    }

    /** Host: a friend is missing markets we have (new since their copy). What each is. */
    private static void answer(Session s, JSONObject j) throws Exception {
        JSONArray keys = j.optJSONArray("keys");
        for (int i = 0; keys != null && i < keys.length(); i++) {
            String key = keys.optString(i);
            SectorEntityToken entity = entityOf(key);
            MarketAPI m = entity == null ? null : entity.getMarket();
            if (!shared(m)) continue;
            JSONArray inds = new JSONArray();
            for (Industry ind : m.getIndustries()) inds.put(ind.getId());
            s.sendWorldEvent(new JSONObject().put("e", "nexmarket").put("key", key).put("name", m.getName())
                    .put("faction", m.getFactionId()).put("size", m.getSize()).put("freePort", m.isFreePort())
                    .put("industries", inds).toString());
        }
    }

    /** Friend: a market founded on the host's game (a Nexerelin colony expedition): founded here the same way. */
    private static void found(JSONObject j) {
        String key = j.optString("key");
        SectorEntityToken entity = entityOf(key);
        FactionAPI f = Global.getSector().getFaction(j.optString("faction"));
        if (entity == null || f == null) return;
        MarketAPI m = entity.getMarket();
        if (m == null || (!m.isPlanetConditionMarketOnly() && m.isInEconomy())) return; // none to found on, or there already
        int size = Math.max(1, j.optInt("size", 3));
        m.setSize(size);
        m.addCondition("population_" + size);
        m.setName(j.optString("name", m.getName()));
        m.setFactionId(f.getId());
        m.setPlanetConditionMarketOnly(false);
        if (!m.hasIndustry(Industries.POPULATION)) m.addIndustry(Industries.POPULATION);
        JSONArray inds = j.optJSONArray("industries");
        for (int i = 0; inds != null && i < inds.length(); i++) {
            String id = inds.optString(i);
            if (Global.getSettings().getIndustrySpec(id) != null && !m.hasIndustry(id)) m.addIndustry(id);
        }
        if (!m.hasSubmarket(Submarkets.SUBMARKET_OPEN)) m.addSubmarket(Submarkets.SUBMARKET_OPEN);
        if (!m.hasSubmarket(Submarkets.SUBMARKET_BLACK)) m.addSubmarket(Submarkets.SUBMARKET_BLACK);
        m.setFreePort(j.optBoolean("freePort"));
        Global.getSector().getEconomy().addMarket(m, true);
        entity.setFaction(f.getId());
        asked.remove(key);
        log.info("[MultiSector] " + f.getDisplayName() + " founded " + m.getName() + " on the host's world; here too");
        Session.get().notice(f.getDisplayNameWithArticle() + " founded a colony on the host's world: " + m.getName() + ".");
    }

    // ================================================================ the host's Nexerelin news

    private static boolean isNews(IntelInfoPlugin i) {
        String c = i.getClass().getName();
        return c.startsWith("exerelin.") && NEWS.contains(i.getClass().getSimpleName());
    }

    /** Host: Nexerelin's news, new, changed or ended, to everyone (and all of it again once a minute). */
    private static void sendNews(Session s, long now) throws Exception {
        boolean resend = now - newsAt >= RESEND_NANOS;
        if (resend) newsAt = now;
        Set<IntelInfoPlugin> live = java.util.Collections.newSetFromMap(new IdentityHashMap<IntelInfoPlugin, Boolean>());
        for (IntelInfoPlugin i : Global.getSector().getIntelManager().getIntel()) {
            if (!isNews(i)) continue;
            live.add(i);
            String id = newsIds.get(i);
            if (id == null) newsIds.put(i, id = "n" + (++newsCount));
            JSONObject j = describe(i, id);
            if (j == null) continue;
            String text = j.toString();
            if (!resend && text.equals(newsSent.get(id))) continue;
            newsSent.put(id, text);
            s.sendWorldEvent(text);
        }
        // Gone from the intel list altogether: over.
        for (Iterator<Map.Entry<IntelInfoPlugin, String>> it = newsIds.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<IntelInfoPlugin, String> e = it.next();
            if (live.contains(e.getKey())) continue;
            it.remove();
            newsSent.remove(e.getValue());
            s.sendWorldEvent(new JSONObject().put("e", "nexintel").put("id", e.getValue()).put("ended", true).toString());
        }
    }

    private static JSONObject describe(IntelInfoPlugin i, String id) throws Exception {
        String title;
        try {
            title = i.getSmallDescriptionTitle();
        } catch (RuntimeException e) {
            title = null;
        }
        if (title == null || title.isEmpty()) return null;
        JSONObject j = new JSONObject().put("e", "nexintel").put("id", id).put("title", title)
                .put("kind", i.getClass().getSimpleName()).put("ended", i.isEnded() || i.isEnding());
        try {
            if (i.getIcon() != null) j.put("icon", i.getIcon());
        } catch (RuntimeException ignored) {
            // no icon, then
        }
        try {
            FactionAPI f = i.getFactionForUIColors();
            if (f != null) j.put("faction", f.getId());
        } catch (RuntimeException ignored) {
            // no faction, then
        }
        SectorEntityToken at = null;
        try {
            at = i.getMapLocation(null);
        } catch (RuntimeException ignored) {
            // nowhere on the map
        }
        if (at != null && at.getContainingLocation() != null) {
            j.put("loc", FleetSync.locationId(at.getContainingLocation())).put("x", at.getLocation().x).put("y", at.getLocation().y);
            if (at.getId() != null) j.put("entity", at.getId());
            MarketAPI m = at.getMarket();
            String owner = m == null ? null : m.getMemoryWithoutUpdate().getString(ColonySync.OWNER);
            if (owner != null && !owner.isEmpty()) j.put("owner", owner).put("colony", m.getName());
        }
        return j;
    }

    /** Friend: the host's news in our intel. */
    private static void news(Session s, JSONObject j) {
        String id = j.optString("id");
        Mirror m = mirrors.get(id);
        if (j.optBoolean("ended")) {
            if (m != null && !m.isEnding() && !m.isEnded()) m.endAfterDelay();
            return;
        }
        String title = j.optString("title");
        if (m == null) {
            if (haveOwn(j.optString("kind"), title)) return; // our copy of the world already shows this one
            m = new Mirror();
            mirrors.put(id, m);
            m.fill(j);
            Global.getSector().getIntelManager().addIntel(m, false);
            String owner = j.optString("owner", "");
            if (owner.equals(s.playerName(s.getMyId()))) {
                m.setImportant(true);
                s.notice("Warning from the host's world: " + title + " (your colony " + j.optString("colony") + ").");
            }
            log.info("[MultiSector] the host's Nexerelin: " + title);
        } else {
            m.fill(j);
        }
    }

    /** Nexerelin's own intel here for the same thing (it came with the host's save). */
    private static boolean haveOwn(String kind, String title) {
        for (IntelInfoPlugin i : Global.getSector().getIntelManager().getIntel()) {
            if (!isNews(i) || i.isEnded() || i.isEnding() || !i.getClass().getSimpleName().equals(kind)) continue;
            try {
                if (title.equals(i.getSmallDescriptionTitle())) return true;
            } catch (RuntimeException ignored) {
                // can't tell
            }
        }
        return false;
    }

    private static void endMirrors() {
        try {
            for (Mirror m : mirrors.values()) Global.getSector().getIntelManager().removeIntel(m);
        } catch (RuntimeException ignored) {
            // the sector is going away
        }
        mirrors.clear();
        misses.clear();
        asked.clear();
    }

    /** One piece of the host's Nexerelin news, on a friend's game. */
    public static final class Mirror extends BaseIntelPlugin {
        private String title = "", icon, faction, kind = "", loc, entity, owner = "", colony = "";
        private float x, y;
        private transient SectorEntityToken spot;

        void fill(JSONObject j) {
            title = j.optString("title", title);
            icon = j.has("icon") ? j.optString("icon") : icon;
            faction = j.has("faction") ? j.optString("faction") : faction;
            kind = j.optString("kind", kind);
            loc = j.has("loc") ? j.optString("loc") : loc;
            entity = j.has("entity") ? j.optString("entity") : entity;
            x = (float) j.optDouble("x", x);
            y = (float) j.optDouble("y", y);
            owner = j.optString("owner", owner);
            colony = j.optString("colony", colony);
            spot = null;
        }

        @Override
        protected String getName() {
            return title;
        }

        @Override
        public String getIcon() {
            if (icon != null && !icon.isEmpty()) return icon;
            return getFactionForUIColors().getCrest();
        }

        @Override
        public FactionAPI getFactionForUIColors() {
            FactionAPI f = faction == null ? null : Global.getSector().getFaction(faction);
            return f != null ? f : super.getFactionForUIColors();
        }

        @Override
        public Set<String> getIntelTags(SectorMapAPI map) {
            Set<String> tags = super.getIntelTags(map);
            tags.add("Host's world");
            if (faction != null && Global.getSector().getFaction(faction) != null) tags.add(faction);
            return tags;
        }

        @Override
        public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
            info.addPara(title, getFactionForUIColors().getBaseUIColor(), 0f);
            info.addPara(owner.isEmpty() ? "On the host's world" : "Aimed at " + owner + "'s colony " + colony,
                    Misc.getGrayColor(), 3f);
        }

        @Override
        public boolean hasSmallDescription() {
            return true;
        }

        @Override
        public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
            info.addPara(title + ".", 0f);
            info.addPara("Reported from the host's game, where Nexerelin's invasions, raids, diplomacy and colony "
                    + "expeditions happen for everyone. What comes of it reaches your world too.", Misc.getGrayColor(), 10f);
            if (!owner.isEmpty()) {
                String mine = owner.equals(Session.get().playerName(Session.get().getMyId())) ? "your" : owner + "'s";
                info.addPara("It's aimed at " + mine + " colony " + colony + ". Its defences are as its owner built them;"
                        + " a fleet that's beaten before it arrives never gets there.", 10f);
            }
            if (isEnding() || isEnded()) info.addPara("It's over on the host's game.", Misc.getGrayColor(), 10f);
        }

        @Override
        public SectorEntityToken getMapLocation(SectorMapAPI map) {
            if (spot != null) return spot;
            LocationAPI l = loc == null ? null : FleetSync.findLocation(loc);
            if (l == null) return null;
            SectorEntityToken e = entity == null ? null : l.getEntityById(entity);
            spot = e != null ? e : l.createToken(x, y);
            return spot;
        }

        @Override
        public String getSortString() {
            return "Host " + kind + " " + title;
        }
    }
}
