package multisector.sync;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoAPI.CargoItemType;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.SubmarketPlugin.PlayerEconomyImpactMode;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.submarkets.BaseSubmarketPlugin;
import com.fs.starfarer.api.util.Misc;
import multisector.character.ShipJson;
import multisector.net.Session;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The host's markets are the real ones.
 *
 * <ul>
 * <li><b>Friend opens a market:</b> their game asks the host for its stock
 *     (MARKET_REQUEST). The host refreshes it as if it had visited, then
 *     sends every buyable item and ship plus each commodity's trade pressure
 *     (what moves prices after players buy or sell). The friend's market is
 *     set to exactly that, and marked "just refreshed" so their own game
 *     doesn't re-roll it.</li>
 * <li><b>Friend trades:</b> the game reports each trade
 *     (reportPlayerMarketTransaction); we send it to the host (MARKET_TRADE),
 *     which applies it to its market with the game's own rules: stock moves,
 *     and prices shift like after a player trade.</li>
 * </ul>
 * Storage (your own items) isn't shared. Reputation and smuggling
 * suspicion from trading stay with whoever traded.
 */
public final class MarketSync {

    private static final String SYNC_MOD = "multisector_sync";
    private static final long SNAPSHOT_WAIT_NANOS = 10_000_000_000L;
    private static final Logger log = Global.getLogger(MarketSync.class);

    private MarketSync() {
    }

    // ================================================================ shared

    /** Markets everyone shares: not storage, and not other free-transfer (personal) ones. */
    static boolean syncable(SubmarketAPI sm) {
        if (sm == null || sm.getPlugin() == null) return false;
        if (Submarkets.SUBMARKET_STORAGE.equals(sm.getSpecId())) return false;
        return !sm.getPlugin().isFreeTransfer();
    }

    static JSONArray stacks(CargoAPI c) throws Exception {
        JSONArray a = new JSONArray();
        for (CargoStackAPI st : c.getStacksCopy()) {
            JSONObject o = stack(st);
            if (o != null) a.put(o);
        }
        return a;
    }

    static JSONObject stack(CargoStackAPI st) throws Exception {
        if (st.isNull() || st.getSize() <= 0 || st.getType() == CargoItemType.NULL) return null;
        JSONObject o = new JSONObject();
        o.put("t", st.getType().name());
        o.put("q", st.getSize());
        if (st.getType() == CargoItemType.SPECIAL) {
            SpecialItemData d = st.getSpecialDataIfSpecial();
            if (d == null) return null;
            o.put("id", d.getId());
            if (d.getData() != null) o.put("d", d.getData());
        } else {
            if (!(st.getData() instanceof String)) return null;
            o.put("id", st.getData());
        }
        return o;
    }

    static CargoItemType type(JSONObject o) {
        try {
            return CargoItemType.valueOf(o.optString("t"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Object data(JSONObject o, CargoItemType t) {
        String id = o.optString("id", "");
        if (t == CargoItemType.SPECIAL) return new SpecialItemData(id, o.isNull("d") ? null : o.optString("d", null));
        return id;
    }

    static boolean known(JSONObject o, CargoItemType t) {
        String id = o.optString("id", "");
        switch (t) {
            case RESOURCES: return ShipJson.exists("commodity", id);
            case WEAPONS: return ShipJson.exists("weapon", id);
            case FIGHTER_CHIP: return ShipJson.exists("wing", id);
            case SPECIAL: return ShipJson.exists("special", id);
            default: return false;
        }
    }

    static void add(CargoAPI c, JSONObject o) {
        CargoItemType t = type(o);
        float q = (float) o.optDouble("q", 0);
        if (t == null || q <= 0 || !known(o, t)) return;
        c.addItems(t, data(o, t), q);
    }

    private static void remove(CargoAPI c, JSONObject o) {
        CargoItemType t = type(o);
        if (t == null || !known(o, t)) return;
        Object d = data(o, t);
        float q = Math.min((float) o.optDouble("q", 0), c.getQuantity(t, d));
        if (q > 0) c.removeItems(t, d, q);
    }

    private static FleetDataAPI ships(MarketAPI m, SubmarketAPI sm) {
        CargoAPI c = sm.getCargo();
        if (c.getMothballedShips() == null) c.initMothballedShips(m.getFactionId());
        return c.getMothballedShips();
    }

    static FleetMemberAPI buildShip(JSONObject js, List<String> skipped) throws Exception {
        FleetMemberAPI m = ShipJson.buildShip(js, skipped);
        if (m == null) return null;
        m.getRepairTracker().setCR((float) js.optDouble("cr", m.getRepairTracker().getMaxCR()));
        m.getStatus().setHullFraction((float) js.optDouble("hullFraction", 1));
        return m;
    }

    // ================================================================ host

    /** Markets whose stock changed (a trade here or from a friend): players docked there get it again. */
    private static final java.util.Set<String> dirty = new java.util.HashSet<String>();
    private static long nextPush;

    /** Every frame on the host: answer stock requests and apply friends' trades. */
    static void hostFrame(Session s) {
        pushChanged(s);
        Object[] e;
        int budget = 20; // a burst of trades spreads over a few frames
        while (budget-- > 0 && (e = s.pollMarketEvent()) != null) {
            int kind = (Integer) e[0];
            int playerId = (Integer) e[1];
            String name = (String) e[2];
            String text = (String) e[3];
            try {
                if (kind == Session.MARKET_EVENT_REQUEST) {
                    s.replyMarket(playerId, text, snapshot(text));
                } else {
                    JSONObject t = new JSONObject(text);
                    applyTrade(t, name);
                    dirty.add(t.optString("market"));
                }
            } catch (Exception ex) {
                log.warn("[MultiSector] market " + (kind == Session.MARKET_EVENT_REQUEST ? "request" : "trade")
                        + " from " + name + " failed", ex);
                if (kind == Session.MARKET_EVENT_REQUEST) s.replyMarket(playerId, text, "");
            }
        }
    }

    /**
     * The host's stock changed: everyone docked at that market gets it again
     * (at most twice a second), so a ship someone just bought can't still be
     * for sale on another player's screen.
     */
    private static void pushChanged(Session s) {
        if (dirty.isEmpty()) return;
        long now = System.nanoTime();
        if (now - nextPush < 0) return;
        nextPush = now + 500_000_000L;
        for (String id : new ArrayList<String>(dirty)) {
            MarketAPI m = Global.getSector().getEconomy().getMarket(id);
            if (m == null) continue;
            java.util.Set<String> here = new java.util.HashSet<String>();
            if (m.getPrimaryEntity() != null) here.add(m.getPrimaryEntity().getId());
            for (SectorEntityToken e : m.getConnectedEntities()) here.add(e.getId());
            String json = null;
            for (Map.Entry<Integer, multisector.net.FleetState.Received> e : s.getRemoteFleets().entrySet()) {
                multisector.net.FleetState st = e.getValue().state;
                if (st == null || st.dockedAt == null || !here.contains(st.dockedAt)) continue;
                try {
                    if (json == null) json = snapshot(id);
                    s.replyMarket(e.getKey(), id, json);
                } catch (Exception ex) {
                    log.warn("[MultiSector] couldn't send " + m.getName() + "'s new stock", ex);
                    break;
                }
            }
        }
        dirty.clear();
    }

    private static String snapshot(String marketId) throws Exception {
        MarketAPI m = Global.getSector().getEconomy().getMarket(marketId);
        if (m == null) return "";
        JSONObject root = new JSONObject();
        root.put("market", marketId);
        JSONArray subs = new JSONArray();
        for (SubmarketAPI sm : m.getSubmarketsCopy()) {
            if (!syncable(sm)) continue;
            // A friend visiting counts as a visit: restock if it's due, exactly as when the host docks.
            sm.getPlugin().updateCargoPrePlayerInteraction();
            JSONObject so = new JSONObject();
            so.put("id", sm.getSpecId());
            so.put("cargo", stacks(sm.getCargo()));
            JSONArray ships = new JSONArray();
            for (FleetMemberAPI fm : ships(m, sm).getMembersListCopy()) {
                JSONObject js = ShipJson.ship(fm);
                js.put("id", fm.getId());
                ships.put(js);
            }
            so.put("ships", ships);
            subs.put(so);
        }
        root.put("subs", subs);
        JSONObject mods = new JSONObject();
        for (CommodityOnMarketAPI com : m.getAllCommodities()) {
            float q = com.getCombinedTradeModQuantity();
            if (Math.abs(q) >= 0.5f) mods.put(com.getId(), q);
        }
        root.put("tradeMods", mods);
        return root.toString();
    }

    private static void applyTrade(JSONObject t, String by) throws Exception {
        MarketAPI m = Global.getSector().getEconomy().getMarket(t.optString("market"));
        if (m == null) return;
        SubmarketAPI sm = m.getSubmarket(t.optString("sub"));
        if (!syncable(sm)) return;
        CargoAPI c = sm.getCargo();
        JSONArray sold = t.optJSONArray("sold");
        JSONArray bought = t.optJSONArray("bought");
        for (int i = 0; sold != null && i < sold.length(); i++) add(c, sold.getJSONObject(i));
        for (int i = 0; bought != null && i < bought.length(); i++) remove(c, bought.getJSONObject(i));

        List<String> skipped = new ArrayList<String>();
        JSONArray shipsSold = t.optJSONArray("shipsSold");
        for (int i = 0; shipsSold != null && i < shipsSold.length(); i++) {
            FleetMemberAPI fm = buildShip(shipsSold.getJSONObject(i), skipped);
            if (fm != null) ships(m, sm).addFleetMember(fm);
        }
        JSONArray shipsBought = t.optJSONArray("shipsBought");
        for (int i = 0; shipsBought != null && i < shipsBought.length(); i++) {
            String id = shipsBought.optString(i, "");
            for (FleetMemberAPI fm : ships(m, sm).getMembersListCopy()) {
                if (fm.getId().equals(id)) ships(m, sm).removeFleetMember(fm);
            }
        }
        tradeImpact(m, sm, sold, bought);
        log.info("[MultiSector] " + by + " traded at " + m.getName() + " (" + sm.getSpecId() + "): "
                + (sold == null ? 0 : sold.length()) + " kinds sold, " + (bought == null ? 0 : bought.length())
                + " bought, " + (shipsSold == null ? 0 : shipsSold.length()) + " ships sold, "
                + (shipsBought == null ? 0 : shipsBought.length()) + " ships bought"
                + (skipped.isEmpty() ? "" : "; skipped " + skipped));
    }

    /**
     * Prices react to the trade like after any player trade: the same rules
     * as the game's BaseSubmarketPlugin.reportPlayerMarketTransaction, minus
     * its reputation and smuggling bookkeeping, which belongs to the trader.
     */
    private static void tradeImpact(MarketAPI m, SubmarketAPI sm, JSONArray sold, JSONArray bought) throws Exception {
        SubmarketPlugin p = sm.getPlugin();
        if (!p.isParticipatesInEconomy()) return;
        PlayerEconomyImpactMode mode = p.getPlayerEconomyImpactMode();
        float mult = p.getPlayerTradeImpactMult();
        float removeMult = p instanceof BaseSubmarketPlugin ? ((BaseSubmarketPlugin) p).getTradeSellRemoveMult() : 0.5f;
        float days = BaseSubmarketPlugin.TRADE_IMPACT_DAYS;
        for (int i = 0; sold != null && i < sold.length(); i++) {
            JSONObject o = sold.getJSONObject(i);
            if (type(o) != CargoItemType.RESOURCES || !known(o, CargoItemType.RESOURCES)) continue;
            float size = (float) o.optDouble("q", 0);
            float qty = size * mult;
            if (qty <= 0) continue;
            CommodityOnMarketAPI com = m.getCommodityData(o.optString("id"));
            if (mode == PlayerEconomyImpactMode.BOTH) {
                com.addTradeMod("sell_" + Misc.genUID(), qty, days);
            } else if (mode == PlayerEconomyImpactMode.PLAYER_SELL_ONLY) {
                com.addTradeModPlus("sell_" + Misc.genUID(), qty, days);
            } else {
                com.addTradeModMinus("sell_" + Misc.genUID(), qty, days);
            }
            float currQty = com.getCombinedTradeModQuantity();
            float origQty = Math.max(0f, currQty - qty);
            int origMod = (int) com.getModValueForQuantity(origQty);
            int currMod = (int) com.getModValueForQuantity(Math.min(origQty + com.getDeficitQuantity(), currQty));
            int modDiff = currMod - origMod;
            float remove = size * removeMult;
            if (modDiff > 0) {
                float modVal = com.getQuantityForModValue(modDiff);
                remove = modVal + Math.max(0f, (size - modVal) * removeMult);
            }
            if (remove > 0) sm.getCargo().removeCommodity(com.getId(), remove);
        }
        for (int i = 0; bought != null && i < bought.length(); i++) {
            JSONObject o = bought.getJSONObject(i);
            if (type(o) != CargoItemType.RESOURCES || !known(o, CargoItemType.RESOURCES)) continue;
            float qty = (float) o.optDouble("q", 0) * mult;
            if (qty <= 0) continue;
            CommodityOnMarketAPI com = m.getCommodityData(o.optString("id"));
            if (mode == PlayerEconomyImpactMode.BOTH) {
                com.addTradeMod("buy_" + Misc.genUID(), -qty, days);
            } else if (mode == PlayerEconomyImpactMode.PLAYER_SELL_ONLY || mode == PlayerEconomyImpactMode.NONE) {
                com.addTradeModPlus("buy_" + Misc.genUID(), -qty, days);
            } else {
                com.addTradeModMinus("buy_" + Misc.genUID(), -qty, days);
            }
        }
    }

    // ================================================================ friend

    private static MarketAPI open;
    private static boolean settled;
    /** New stock from the host while our trade screen was open: applied when it closes. */
    private static String later;
    private static long askedAt;
    /** Ships in the market we're at: our member id -> the host's member id. */
    private static final Map<String, String> toHostShip = new HashMap<String, String>();

    /** Every frame on a friend: at a market, swap in the host's stock as soon as it arrives. */
    static void friendFrame(Session s) {
        SectorEntityToken t = Docking.current();
        MarketAPI m = t == null ? null : t.getMarket();
        if (m == null || m.isPlanetConditionMarketOnly() || m.getSubmarketsCopy().isEmpty() || m.isPlayerOwned()) {
            open = null; // our own colony: our game runs it, not the host's copy
            return;
        }
        long now = System.nanoTime();
        if (m != open) {
            open = m;
            settled = false;
            later = null;
            askedAt = now;
            toHostShip.clear();
            s.requestMarket(m.getId());
        }
        if (settled) {
            // The host's stock changed while we're here (someone traded): take it, once our trade screen is shut.
            String pushed = s.takeMarketSnapshot(m.getId());
            if (pushed != null && !pushed.isEmpty()) later = pushed;
            CampaignUIAPI ui = Global.getSector().getCampaignUI();
            if (later != null && ui != null && ui.getCurrentCoreTab() == null) {
                String json = later;
                later = null;
                try {
                    applySnapshot(m, new JSONObject(json));
                } catch (Exception e) {
                    log.warn("[MultiSector] couldn't apply the host's new stock for " + m.getName(), e);
                }
            }
            return;
        }
        String json = s.takeMarketSnapshot(m.getId());
        if (json == null) {
            if (now - askedAt > SNAPSHOT_WAIT_NANOS) {
                settled = true;
                log.info("[MultiSector] the host didn't send " + m.getName() + "'s stock in time; using this game's");
            }
            return;
        }
        settled = true;
        if (json.isEmpty()) return;
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (ui != null && ui.getCurrentCoreTab() != null) {
            // Never change stock under an open trade screen: it's applied as soon as the screen closes.
            log.info("[MultiSector] " + m.getName() + "'s stock arrived after the trade screen opened; applying it once it closes");
            later = json;
            return;
        }
        try {
            applySnapshot(m, new JSONObject(json));
        } catch (Exception e) {
            log.warn("[MultiSector] couldn't apply the host's stock for " + m.getName(), e);
        }
    }

    private static void applySnapshot(MarketAPI m, JSONObject root) throws Exception {
        List<String> skipped = new ArrayList<String>();
        JSONArray subs = root.optJSONArray("subs");
        int items = 0;
        int shipCount = 0;
        for (int i = 0; subs != null && i < subs.length(); i++) {
            JSONObject so = subs.getJSONObject(i);
            SubmarketAPI sm = m.getSubmarket(so.optString("id"));
            if (!syncable(sm)) continue;
            CargoAPI c = sm.getCargo();
            c.clear();
            JSONArray cargo = so.optJSONArray("cargo");
            for (int j = 0; cargo != null && j < cargo.length(); j++) {
                add(c, cargo.getJSONObject(j));
                items++;
            }
            FleetDataAPI ships = ships(m, sm);
            for (FleetMemberAPI old : ships.getMembersListCopy()) ships.removeFleetMember(old);
            JSONArray list = so.optJSONArray("ships");
            for (int j = 0; list != null && j < list.length(); j++) {
                JSONObject js = list.getJSONObject(j);
                FleetMemberAPI fm = buildShip(js, skipped);
                if (fm == null) continue;
                ships.addFleetMember(fm);
                toHostShip.put(fm.getId(), js.optString("id", ""));
                shipCount++;
            }
            // The game restocks when a player opens the trade screen if enough time has passed.
            // Mark it as just restocked, so it keeps the host's stock instead of rolling its own.
            if (sm.getPlugin() instanceof BaseSubmarketPlugin) {
                BaseSubmarketPlugin b = (BaseSubmarketPlugin) sm.getPlugin();
                b.setSinceLastCargoUpdate(0f);
                b.setSinceSWUpdate(0f);
            }
        }
        // Prices: line up each commodity's trade pressure with the host's.
        JSONObject mods = root.optJSONObject("tradeMods");
        for (CommodityOnMarketAPI com : m.getAllCommodities()) {
            com.getTradeMod().removeTemporaryMod(SYNC_MOD);
            float host = mods == null ? 0f : (float) mods.optDouble(com.getId(), 0);
            float diff = host - com.getCombinedTradeModQuantity();
            if (Math.abs(diff) >= 0.5f) com.getTradeMod().addTemporaryModFlat(30f, SYNC_MOD, diff);
        }
        log.info("[MultiSector] " + m.getName() + ": using the host's stock (" + items + " kinds of goods, "
                + shipCount + " ships)" + (skipped.isEmpty() ? "" : "; skipped " + skipped));
    }

    /** Sends each of this friend's market trades to the host. Transient: added on each load. */
    public static final class TradeReporter extends BaseCampaignEventListener {
        public TradeReporter() {
            super(false);
        }

        @Override
        public void reportPlayerMarketTransaction(PlayerMarketTransaction tx) {
            Session s = Session.get();
            if (s.getState() == Session.State.HOSTING) {
                // The host's own trade: friends docked at the same market get the new stock.
                if (tx.getMarket() != null && syncable(tx.getSubmarket())) dirty.add(tx.getMarket().getId());
                return;
            }
            if (s.getState() != Session.State.CONNECTED) return;
            try {
                SubmarketAPI sm = tx.getSubmarket();
                if (tx.getMarket() == null || !syncable(sm)) return;
                JSONObject j = new JSONObject();
                j.put("market", tx.getMarket().getId());
                j.put("sub", sm.getSpecId());
                j.put("sold", stacks(tx.getSold()));
                j.put("bought", stacks(tx.getBought()));
                JSONArray shipsSold = new JSONArray();
                for (PlayerMarketTransaction.ShipSaleInfo info : tx.getShipsSold()) {
                    shipsSold.put(ShipJson.ship(info.getMember()));
                }
                j.put("shipsSold", shipsSold);
                JSONArray shipsBought = new JSONArray();
                for (PlayerMarketTransaction.ShipSaleInfo info : tx.getShipsBought()) {
                    String hostId = toHostShip.get(info.getMember().getId());
                    if (hostId != null && !hostId.isEmpty()) shipsBought.put(hostId);
                }
                j.put("shipsBought", shipsBought);
                s.sendMarketTrade(j.toString());
            } catch (Exception e) {
                log.warn("[MultiSector] couldn't send a trade to the host", e);
            }
        }
    }

    /** A save is loading: forget the old world's market. */
    static void forget() {
        open = null;
        settled = false;
        later = null;
        dirty.clear();
        toHostShip.clear();
    }
}
