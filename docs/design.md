# Starsector Multiplayer Mod: Design Notes

Working notes from the design discussion. Target: small friend-group co-op (roughly 2-8 players), host-authoritative, built as a Java mod.

---

## 1. Goals and scope

- Shared campaign world, host runs the real simulation.
- Clients are a presentation + input layer, not a second simulation.
- Realistic player ceiling: about 4-8 comfortable, maybe 12-16 stretched. The bottleneck is host CPU (Starsector sim + networking + the host also playing), not bandwidth.
- Shared-battle participation (multiple players in one fight) is the hard, unproven part. Treat it as a stretch goal.

### Suggested v1 vertical slice
1. One host, one client, syncing one ship's movement in a test scene.
2. Prove the tick / buffer / reconcile loop in isolation.
3. Then wire into the campaign layer (fleet positions, faction relations).
4. Economy, then diplomacy, then combat hit-registration.

---

## 2. Core architecture

**Clients propose, host disposes.**

| Direction | What flows |
|---|---|
| Client -> Host | Intents (move, fire, trade, fleet order), never "here is my new state" |
| Host -> Client | Resolved outcomes plus ambient world deltas |

- Host runs the normal Starsector game loop, fed by remote intents instead of one local keyboard.
- Client predicts only its **own** ship for responsiveness, then reconciles with the host's result.
- Everything else (other fleets, factions, markets) is **received state, not simulated state**.

### Trust model
For a trusted friend group, relaying real client positions is acceptable. Add cheap sanity checks (reject impossible speed or teleports) instead of full intent simulation.

---

## 3. Data model: save vs runtime slate

- **One master save**, owned by the host. No per-player auxiliary save files to merge.
- A **custom runtime slate** holds only sync-relevant mutable state (fleet positions, faction relations, market ownership, player slots).
- A **converter** bridges slate and the real Starsector save format.
  - Build save -> slate first. Slate -> save is only needed when the host persists a session, and it is easier to get subtly wrong.
- Client "player context" is runtime only (which fleet it controls, UI state, prediction buffers). Not persisted as its own save.
- Join flow: full snapshot once, then deltas only. Reconnect: host retains the player slot.
- Per-player settings (keybinds, cosmetics) can live in a small local config file.

---

## 4. What to sync and how often

| Tier | Frequency | Examples | Transport |
|---|---|---|---|
| Fast | every tick | ship position/velocity in active view | unreliable UDP |
| Event | on action | fleet orders, weapon fire, trade resolved | reliable channel |
| Rare | on change | faction relation shift, colony ownership, spawns | reliable channel |
| Once | on join | full snapshot | reliable (TCP is fine) |

- Add low-frequency state hashes/checksums to catch drift, resync only on mismatch.
- Battles a client is not in: send only the auto-resolve **outcome** (winner, losses), not blow-by-blow.

### Economy
- Daily-tick cadence, so it lives in the slow tier.
- Host computes final prices; client displays already-parsed results (buy/sell price, stock).
- Prefer **pull on market open**, with a light refresh if older than one in-game day.
- Concurrent trades in one tick resolve **sequentially**, with the market updating between each intent. The second seller gets the post-first-sale price. Use a deterministic tie-break (arrival time, then player ID).
- UI cue: show the price as "pending" after the player submits, so a stale display does not feel like a scam.

---

## 5. Timing model

### Logical ticks, not synced wall clocks
- Everyone counts ticks from a shared starting reference.
- Clients tag intents with "my tick T".
- Host periodically sends "you should be at tick N" to correct drift.

### Buffer window (starting point ~100ms, configurable)
- It is a **catch window on a fixed timeline**, not a delay added on top of ping.
- Inputs arriving before the window closes are folded into that tick. Late ones go to the next tick.
- Add a small grace margin (5-10ms) before locking in a tick.
- Treat the value as host-configurable. Start static, consider adaptive sizing later.
- Same-region friends can likely go tighter (50-80ms). Mixed-region groups may need wider.
- Route the **host's own inputs through the same queue** to remove host advantage.
- The simulation never freezes waiting on a client. Broadcast and move on; acks are asynchronous bookkeeping.

### End-to-end barter latency (rough)
A -> host (one-way) + remaining buffer + host -> B (one-way) is roughly 150-200ms, which is fine for trading.

---

## 6. Prediction, interpolation, correction

- **Prediction**: own ship only, purely local, never sent as truth.
- **Interpolation**: render other entities ~100ms in the past, smoothed between received snapshots.
- **Dead reckoning**: send position + velocity + heading + optional target, so extrapolation between packets is better.
- **Lite rollback** (optional, narrow): only for combat hit registration.
  - Host keeps ~200-300ms of position history for ships in that fight.
  - Rewind the target to the shot's timestamp, check the hit, resolve.
  - Not for fleet AI, economy, or campaign state. Blast radius is too large.
- **Full rollback and dual parallel saves**: rejected. Require deterministic re-simulation and roughly double the CPU cost, and Starsector's engine was not built for either.

---

## 7. Fleet AI and aggro

- AI decisions are committed, not retroactively rewritten.
- Re-evaluate on a fixed decision cadence (about 1-2s). Too slow feels sluggish; too fast causes flip-flopping.
- Use **hysteresis**: different thresholds to enter and leave aggro (e.g. aggro at 500 units, disengage below 300).
- The host may feed player trajectory into the decision as better input, but the output is still one clean committed decision.
- To other players this reads as a patrol noticing you after a beat, not a glitch. Correction is forward-only.

---

## 8. Networking stack

| Option | Verdict |
|---|---|
| `java.net` raw sockets | Works, but you hand-roll reliability and channels |
| **KryoNet** | Recommended starting point: TCP + UDP in one library (`sendTCP` / `sendUDP`) |
| Netty | Powerful, probably overkill for a first version |
| KCP (Java port) | Worth knowing if KryoNet's latency profile disappoints later |
| ENet / Photon etc. | Overkill |

- Dual channels are normal: unreliable for positions, reliable for intents and events.
- An optional separate HTTP endpoint (`com.sun.net.httpserver.HttpServer`) only makes sense for external tooling.

> **Update:** see section 12. The game's mod sandbox probably rules out KryoNet.

---

## 9. Existing mods and references

- **moi75ts/multiplayer** (GitHub): experimental campaign POC, client-server, server holds world state. Listed limitations include no AI fleets, no missions, and matching seeds required. Latest release seen: 0.3.6.
- **AyoKeito/starsector-coop** (GitHub): two-player campaign co-op, host runs the world and the guest mirrors it. Each player fights their own battles; two players piloting in the same battle is not supported in the release described.
- **Cooperative Multiplayer Combat** (forum thread topic 11598): an old archived description (0.7.2a) describes a local shared-screen setup (a second pilot's ship is tagged "player2" and uses separate keybinds). Later versions may differ, so I did not verify how the current version works. The forum page was not fetchable (HTTP 402).
- Licensing: check each project's license before copying code. Learning from approaches is fine.

---

## 10. Suggested project layout

```
src/multisector/
  MultiSectorModPlugin.java   entry point (BaseModPlugin hooks)
  net/
    NetworkManager.java       host/client roles, connections
    PacketSerializer.java
    SyncSession.java          tick loop, send/receive queues
  intents/
    Intent.java
    FireWeaponIntent.java
    FleetOrderIntent.java
    TradeIntent.java
    IntentResolver.java       host-side validate + apply
  state/
    FleetState.java
    FactionState.java
    MarketState.java
    StateDiffer.java
  sync/
    DeltaEvent.java
    FleetMovedEvent.java
    FactionRelationChangedEvent.java
    EventBroadcaster.java
  client/
    PredictionEngine.java
    PuppetStateManager.java
  combat/                     optional lite rollback
    HitPredictor.java
    HitHistoryBuffer.java
    HitResolver.java
    HitCorrection.java
```

Start with 4-5 files (ModPlugin, basic NetworkManager, one Intent, one DeltaEvent, a naive StateDiffer) and get one round trip working before growing the rest.

---

## 11. Open questions

- Scope of sync per client: whole sector, or only nearby systems?
- Fixed vs adaptive buffer window.
- How a second player joins an in-progress battle (control handoff, camera, ship assignment). No proven reference exists yet.
- ~~Which Starsector API version to target.~~ Decided: 0.98.5a (section 12).
- Exact save XML structure for fleets and factions. Inspect a real save before writing the converter.

---

## 12. Decisions since

- **Target: Starsector 0.98.5a-RC8, Java 17.** The game ships a full Azul Zulu 17 JDK in `jre/`, which the build uses.
- **The mod sandbox.** Starsector loads mod jars through `com.fs.starfarer.loading.scripts.ScriptClassLoader`, which refuses classes from some JDK packages. Strings in the engine jar list `java.lang.reflect` (except `Type`), most of `java.io` (with an allowlist of basic streams), `java.nio.file`, `javax.script`, `java.util.prefs` and `sun.reflect.misc`. `java.net` is not listed. Bundled libraries are loaded the same way, so the rules apply to them too.
- **Consequence for KryoNet.** Kryo's serializers rely on reflection, so KryoNet probably will not load from a mod jar. The plan is a small hand-written network layer on `java.net` sockets with packets packed into `ByteBuffer`. Fallback: put KryoNet on the game's classpath through `vmparams`, which bypasses the sandbox but means every player edits that file.
- **Sandbox probe.** `multisector.probe` tests each candidate API at startup and logs PASS/BLOCKED to `starsector.log`. Its results replace the guesses above.
- **Connection model.** Direct connection to the host's IP on port 7777 (TCP + UDP). Hamachi is the primary supported setup. Hamachi-driven constraints:
  - the host binds the wildcard address so the Hamachi adapter is included
  - UDP payloads are capped at 1200 bytes (Hamachi's adapter MTU is 1404)
  - the firewall rules are port-based on all profiles, because Hamachi is usually classed as a Public network

  See [networking.md](networking.md).
- **The shared world is the host's save (replaces "save -> slate converter" for joining).** The map isn't synced piece by piece. Everyone loads the same save, so the game's own loader rebuilds the world. A friend's game then shows the host's live state on top of it.
  - Mod code can't touch files (sandbox) and the API can't load a specific save. So an external **launcher** (Swing, run on the game's own Java, `multisector.launcher`) shares the host's save on port 7778 and downloads it into the friend's `saves/save_MultiSector_<host>`. It sets the save date to now, so the main menu's Continue loads it.
  - The launcher tells the mod what to do through `saves/common/multisector_session.json.data`, which the mod reads with the sandbox-safe `readTextFileFromCommon`. On load, the mod hosts or joins by itself if the loaded save's character name matches and the file is under 12 hours old.
  - Next: give each player their own fleet. The API has `SectorAPI.setPlayerFleet`, and the host's fleet becomes a puppet on clients.
- **Wire basics (`multisector.net`).** Every packet starts with magic `MSEC` plus a protocol version. TCP messages are length-prefixed frames. `NetTest` is a standalone tester using the same format.

---

## 13. Shared time, pause and joining

Every player runs the real Starsector simulation on their own copy of the world. Nothing stops two copies from drifting apart in time: each pauses for its own menus, fast-forwards on its own, and a friend's copy starts at whatever moment the host last saved. So the first thing synced is **time**.

### One clock, owned by the host

- The host keeps a **shared clock** (`net.SharedClock`). While the host is on the campaign map, the host's own game is the truth, and the shared clock is re-anchored to it every frame.
- The shared clock runs on **real time**, not on the host's game loop. Starsector freezes the campaign while a market, dialog or core screen is open. If the shared clock were simply the host's clock, every friend would freeze whenever the host went shopping. Instead it keeps going, and the host catches up when the menu closes, just like everyone else.
- The host sends the clock (`CLOCK`: timestamp, rate, paused/fast/held flags, who changed it) over UDP 10 times a second. A lost packet doesn't matter, because the next one carries the full state.
- Friends extrapolate it by `rate x (time since arrival + ping/2)`. If their own date is more than 3 game hours off (1 minute while stopped), it is set to the host's. Ping jitter is far below that, so normal play sees no corrections.
- The date is set exactly through the clock's calendar (`CampaignClockAPI.getCal()`) plus `CampaignClock.advance(0)`, which copies the calendar into the timestamp. `CampaignClock` is one of the game's unobfuscated classes.

### Pause and fast-forward

| Action | Effect |
|---|---|
| Anyone presses pause on the map | Everyone pauses (`PAUSE_REQUEST` -> host -> clock) |
| Anyone unpauses | Everyone resumes |
| Anyone presses Ctrl+Space inside a dialog, market or menu | Everyone pauses or resumes. The keyboard is read directly, because dialogs take the normal input. Plain Space would fire while typing. |
| Someone opens a menu, market or dialog | Only their game pauses. The world clock keeps going, and they catch up after. |
| Host fast-forwards | Shared clock runs at `campaignSpeedupMult`. Friends are told to fast-forward too; anyone who doesn't gets their date stepped forward. |
| Friend fast-forwards alone | Disabled: their `campaignSpeedupMult` is set to 1 unless the host is fast-forwarding, so they can't run ahead. (If the engine caches that setting, the 3-hour correction still pulls them back.) |

A pause counts as "pressed on the map" when it changes while no dialog, menu or core tab is open. Pauses the game makes for its own UI are never shared.

### Joining: why a downloaded save was a problem, and the fix

The old flow: the friend downloads the host's save file, which is the world as of the host's **last save**, possibly hours old. It then takes minutes to start the game and load. The friend's copy then ran its own clock from that point. Two friends joining at different times ended up at different dates, and whoever loaded the newer save was "ahead".

The fix has three layers. Each one alone helps; together they make a join exact.

1. **Fresh save on download.** The friend's launcher says it's starting a download (`LOBBY_HELLO` purpose byte). The host's launcher asks the host's own game over loopback (`LOCAL_SAVE` on the game port) to save now, waits for the files to settle, then shares that. The friend gets the world as it is, not as it was.
2. **Hold while joining** (`holdWhileJoining`, on by default). The same request also holds the shared clock: everyone pauses until that friend connects in-game, so they arrive in exactly the world they downloaded. Anyone can unpause to stop waiting, and the hold gives up after 10 minutes.
3. **Catch-up on connect.** However the friend got their save, the first clock packet sets their date to the shared one ("Moved the date forward 3.2 days to match the host's world"). Nobody can be ahead of or behind the host. This also covers reconnecting with an older or newer save of the same world.

What layer 3 does *not* fix: if the save was behind, the friend's NPC fleets, markets and events are where they were at that older moment, and the jump only moves the date. Layers 1 and 2 avoid that gap for joins. Ongoing drift needs entity sync (below).

### Player fleets: positions and ships

- Positions: unchanged (`FLEET_STATE` / `WORLD_STATE`, 10 Hz UDP, dead-reckoned puppets).
- Ships: each fleet's hull list (flagship first) goes over TCP as `FLEET_COMP`, only when it changes. The host relays it and sends everyone's to a new joiner. Position packets carry its hash, so a puppet rebuilds its ships when they buy, sell or lose one. Hulls a game doesn't have are skipped, and an unknown flagship gets a stand-in.

### Next: NPC fleets and the rest of the world (first part done: section 14)

Each copy still simulates its own NPC fleets, with its own randomness, so after a join they drift: different patrols spawn, and fleets wander differently. The plan, in order:

1. **Matched fleets, nearby only.** Fleets that existed in the shared save have the same `getId()` in every copy. For the star system (or hyperspace area) each friend is in, the host sends those fleets' position, velocity and destination a few times a second. The friend's copy steers its matching fleet there and turns off that fleet's own AI movement.
2. **Host-only and friend-only fleets.** A fleet the host spawned after the save doesn't exist on friends, so it shows as a puppet (like player fleets). A fleet a friend's copy spawned that the host doesn't have is hidden, or the friend's spawners are turned off.
3. **Composition and state**: ship list, faction, and the assignment text ("patrolling", "returning to ..."), sent on change, like `FLEET_COMP`.
4. Later tiers from section 4: markets on open, faction relations on change, battle outcomes.

Interacting with an NPC fleet on a friend's copy (talking to it, fighting it) is where this gets hard, because the host must resolve it. That comes after the position sync works.

---

## 14. Planets and NPC fleets

### Planets

Planets, stations, moons and asteroids exist identically in every copy, since they come from the save. Their orbits don't follow the date, though: each frame the game turns every orbit by that frame's time (`CircularOrbit.advance`). So whenever the clock sync jumps a game's date (catching up after a menu, or on joining), `ClockAccess` also turns every non-fleet orbit by the same game time. Every copy starts from the same angles in the save, so planets then sit in the same place on every player's game. Jumps under one game hour skip this pass.

### How the game makes NPC fleets (why this is narrow)

- `RouteManager` keeps most NPC traffic (patrols, traders, raiders) as abstract routes. It turns a route into a real fleet only within **1.6 ly of the local player's fleet** and removes it beyond 3-4 ly. Other managers (for example `DisposableFleetManager`) also spawn near the local player only.
- So the host's game has no fleets at all around a friend who is somewhere else, and each copy rolls its own fleets around its own player.
- A fleet spawned after the save gets a new random id on each copy. Only fleets that were already in the save share an id.

### What syncs now: star systems shared with the host

While a friend is in the **same star system as the host** (`NpcSync`):

| | Host | Friend |
|---|---|---|
| Fleet details | `NPC_INFO` over TCP when a fleet appears or changes (id, faction, name, variant and hull per ship), `NPC_GONE` when it leaves, `NPC_RESET` when the host changes system. All re-sent every 15 s as a safety net. | Kept per handle |
| Positions | `NPC_STATE` over UDP, 10 Hz, only to friends in that system | Glide toward them, predicted with the shared clock's speed |
| Fleet in the save (same id) | - | The friend's own copy is steered: AI off, orbit detached, moved to the host's position. Restored afterwards. |
| Host fleet the friend doesn't have | - | A stand-in fleet ("mirror") with the same faction, name and ships |
| Friend's own extra fleets | - | Hidden: detected range x0 (the stat "Go Dark" lowers), AI off, held in place. Given back untouched afterwards, so routes and managers never notice. |

Safety rules:
- Nothing is saved. `beforeGameSave` releases everything; the next frame sets it up again.
- Stations, player fleets and puppets are never touched, and a fleet in a battle is never hidden or taken over.
- Host-driven fleets open a short note instead of the talk/fight dialog (`NpcInteractionGuard`), because the host can't resolve those interactions yet. In shared systems NPCs also leave friends alone: on the host, friends are puppets that NPCs ignore.
- `syncNpcFleets: false` turns all of it off.

### Not covered yet

- **Hyperspace.** The host's fleets there only exist within a few ly of the host, so the shared area needs a distance rule rather than "same system". Next after this is tested.
- **Systems the host isn't in.** Each friend's game still runs its own fleets there.
- **Interacting** with host fleets (fights, trades, hails). Step 2: the friend fights on their own PC and the host applies the outcome.

### Dialogs: the world keeps running

Decision: a dialog (market, planet, hail, cargo or refit screen) pauses only the player who opened it. The shared clock keeps going, and their game catches up when they close it. Pausing everyone would stall the group most of the time, because Starsector opens a dialog for nearly everything.

- **Docked at something that orbits** (planet, station): the player's position packets carry `dockedAt` (the entity id, the same in every copy) plus their offset from it. Other games draw that player's fleet riding along with the planet. When the dialog closes and the date catches up, `Docking.reattach` puts their own fleet back at the same offset after the orbit catch-up.
- **Talking to a fleet:** the fleet being talked to stays frozen, and so does the player's own fleet. Friends can't open host-driven fleets yet. Letting them would need the host to apply battle results; otherwise a fleet a friend destroyed would reappear. A per-fleet claim (freeze only that fleet, and tell a second player someone is already dealing with it) comes with battle results.
- **The host in a dialog:** Starsector stops the host's whole campaign, which would also freeze every NPC fleet that friends in the host's system are watching. So while the host is in a dialog or menu, the game is unpaused, and a friend is in the host's system, `NpcSync` moves those fleets itself in real time (`fleet.advance`, at most 0.1 s per step). It skips the fleet the host is talking to and fleets in a battle. Meanwhile the host's fleet is flagged as ignored by other fleets, so nobody tries to start a second encounter; the flag is removed when the dialog closes and before any save. Only fleets move: spawning, NPC battles and the economy wait for the host to close the dialog. `moveFleetsDuringHostDialogs: false` turns just this off.

---

## 15. Friends' actions reach the host: fleet locks and battles

### Locks

When a player opens a dialog with an NPC fleet in a shared star system, that fleet is locked: held in place on every game, and nobody else can open it.

1. **Friend clicks a host fleet:** `NpcInteractionGuard` opens a short "reaching the host" step and sends `CLAIM_REQUEST(handle)`.
2. **Host** (`FleetLocks.hostFrame`) refuses if another player holds it, if the host is talking to it, or if it's in a battle, and says who has it. Otherwise it freezes the fleet (AI off, held at its spot, `$multisector_claimedBy` set to the player's name) and answers `CLAIM_REPLY` with the fleet's **exact copy**: every ship's loadout (`ShipJson`, shared with the character export), officer, readiness and hull damage, plus each ship's host member id. This can exceed 32K chars, so it uses `Protocol.putLongString`.
3. **Friend:** the stand-in is rebuilt as that exact copy, then the game's own `FleetInteractionDialogPluginImpl` takes over. Talking, fighting, pursuing and looting all work as normal on the friend's PC.
4. **When that dialog closes**, `FleetLocks.friendFrame` compares the ships left with the copy. No losses: `CLAIM_RELEASE`. Otherwise: `BATTLE_RESULT` with the host member ids of the lost ships.
5. **Host** removes those ships from its real fleet. If none are left, it despawns the fleet with reason `OTHER`, not `DESTROYED_BY_BATTLE`: bounty and mission listeners expect real battle data with that reason. Then it unfreezes and tells everyone. The normal NPC sync then updates or removes the fleet on every game.
6. Until the host's update arrives (or 5 s pass), the friend doesn't re-create a stand-in for that fleet, so a destroyed fleet can't flash back.

Safety: a player leaving frees everything they held. Holds end after 20 minutes regardless. Freezes are lifted before every save and re-applied next frame. If the request times out, it's released in case the host's yes was still on its way. The host clicking a fleet a friend holds is told who has it, and fleets the host keeps moving during its own dialogs skip held ones.

### What fleets say they're doing

A held fleet's tooltip used to keep its old order ("travelling to Jangala") while it sat still. Now the host sets the fleet's action text to "interacting with <player>" while it's held, and while the host itself is talking to it, then puts its own text back on release. `NpcInfo` also carries each fleet's status, so every friend's copy shows the host's text, whether it's a held fleet or just its real order. Re-sent within a second when it changes; not part of the info hash, so ships aren't rebuilt for it.

### Hidden fleets

The friend's own extra fleets in a shared system were only made undetectable. Next to a planet the game could still show them, which looked like fleets spawning around a docked friend. They're now also parked 150,000 units out (orbit detached), and put back exactly where they were afterwards. The first 30 hidden per shared stretch are named in the log ("hiding this game's own ...").

### Still to do

- Markets: show the host's stock on open, and send purchases and sales back.
- What a friend gains from battles (loot, XP, reputation) stays on their own game. Reputation is per player anyway: each friend has their own standing.
- Hyperspace sharing.

---

## 16. Markets, exact player fleets, visibility

### Markets (`MarketSync`)

The host's markets are the real ones, in every star system (the economy runs everywhere, not only near players).

- **A friend opens a market:** `MARKET_REQUEST(marketId)`. The host first calls each shared submarket's `updateCargoPrePlayerInteraction()`, so a friend's visit restocks it if due, just like the host docking. It then answers `MARKET_SNAPSHOT`: every stack (commodities, weapons, fighter LPCs, special items), every ship for sale (exact, with the host's member id), and each commodity's combined trade-mod quantity, which is what shifts prices after player trades.
- **The friend's game** replaces its shared submarkets' stock and ships with that. It marks them as just restocked (`setSinceLastCargoUpdate(0)`, `setSinceSWUpdate(0)`), so opening the trade screen doesn't re-roll the stock locally. It also lines up prices with one temporary trade mod per commodity (`multisector_sync`). If the snapshot arrives after the trade screen is already open, it's skipped, so stock never changes under an open screen.
- **Each trade a friend makes** is reported by the game (`reportPlayerMarketTransaction`, via a transient `TradeReporter`) and sent as `MARKET_TRADE`: items sold and bought, ships sold (exact) and bought (host ids). The host adds and removes the stock, then applies price impact with the same rules as `BaseSubmarketPlugin.reportPlayerMarketTransaction`, minus its reputation and smuggling bookkeeping. That bookkeeping belongs to the friend and stays on their game.
- Storage and other free-transfer submarkets are personal, so they're never shared.

Both games start each trade from the same stock and trade pressure and apply the same rules, so they end up with the same stock.

### Exact player fleets

`FLEET_COMP` now also carries each ship's exact loadout (`ShipJson`, minus readiness and hull damage, which change constantly). It's rebuilt at most every 2 s, and immediately when ships come or go. Puppets are built from it, so other players' fleets have the right ships, weapons and strength in tooltips, instead of bare hulls.

### Visibility

Puppets used `setSensorProfile`, which the game recalculates every frame, so a distant player turned into an unidentified sensor contact. Puppets now get a large flat bonus on the detected-range and sensor-profile stats, plus transponder on: always visible with full details. A puppet now hides after 10 s without updates (was 5), since jumps can take a few seconds.

### Next: wreckage and other spawned objects

Battles leave debris fields, derelict ships and cargo pods, but only on the game where the battle was fought. A friend's battle wreckage exists only for them, and the host's only for the host. The plan is a "shared objects" sync shaped like the NPC fleet one:

1. Objects from the save have the same id everywhere and need nothing.
2. For objects created later in a shared system (`Tags.SALVAGEABLE`; `Entities.WRECK`, debris fields, cargo pods), the host publishes their type and contents (derelict ship data, debris parameters, pod cargo). Friends show matching copies and hide their own extras, the same way NPC fleets work.
3. A friend's battle sends its wreckage to the host along with the losses. The host creates it, so it becomes shared.
4. Salvaging a shared object takes a lock, like fleets, and the host removes it when done.

## 17. Players talking and trading (`PlayerTalk`)

### Hailing

Clicking another player's fleet (a puppet) never opens the game's fleet encounter, which could start a fight with a stand-in. `NpcInteractionGuard` opens a hail instead:

1. The caller's dialog says it's hailing, and sends `hail` to that player (`PLAYER_MSG`, relayed by the host, which stamps the real sender).
2. The callee's game opens a dialog straight away if they're on the map, like an NPC fleet pulling them over. If they're in a dialog, menu or battle, it answers `wait` and opens as soon as they're free. It gives up after 55 s; the caller gives up after 60 s without an answer (restarted by `wait`).
3. The callee answers or declines. Either side can cancel or leave at any point, and the other side is told (`cancel`, `decline`, `end`). A dialog closed any other way counts as leaving.
4. If two players hail each other at the same moment, the one with the lower id answers the other's hail.

One conversation at a time: a hail to someone already talking is declined as busy.

### Time while talking

No shared pause. Both players sit in a dialog, so both fleets hold still: their games are paused locally, as for any dialog. Their puppets stay put on everyone else's map, and other fleets ignore puppets. The world keeps going for everyone else, which matters with 3+ players. Anyone who wants the whole world stopped can press Ctrl+Space in the dialog.

### Offers

Trading is by offers: credits (fixed steps), cargo (the game's cargo picker, on a copy of the cargo), or ships (any but the flagship; officers stay with the sender, unassigned, and AI cores go back to the sender's cargo).

- What's offered leaves the sender's fleet at once and is held aside (escrow, in memory).
- The receiver alone decides: `answer ok`, and it arrives, or `answer no`, and it goes back. So nothing ends up in both fleets or in neither. Ships and items the receiver's game doesn't have (a missing mod) are listed in the answer and go back.
- When a conversation ends, the receiver turns down anything still open. If the receiver leaves the game (`left`, sent by the host for anyone who disconnects), or the session ends, the sender gets everything back.
- Known gap: what's held aside isn't in a save made during the offer. Saves don't normally happen in dialogs.

### Not yet: fighting together

Battles run in the combat engine, outside the campaign, and syncing real-time combat is a separate, much larger project. A smaller step that fits the current design: when a player fights near a friend, add the friend's exact ships (already sent for puppets) as an allied AI fleet in that battle, then send the friend their losses, the same way NPC battle results reach the host.

## 18. Whose world: first come, hyperspace groups, and shared objects

### The problem

Every game makes up its own NPC fleets around its own player. Until 0.0.19 the host's world always won, and only inside a star system. That broke in two ways in play:

- **Hyperspace wasn't shared.** A friend was attacked by pirates that existed only on their game.
- **Arriving second swapped the world.** A friend explored a system alone, then the host arrived. The friend's whole local world (the fleets around them, mid-chase) was swapped for the host's. Save fleets that both games had kept the friend's ships and status, so the two views never fully matched.

### Owners (`ZonePlanner`, on the host)

The host decides, 10 times a second, whose world each player follows, and sends the plan (`ZONES`) when it changes and every 2 s:

- **Star system:** the owner is whoever has been in the system longest, and stays the owner until they leave. Ties go to the lower id (the host is 0). So whoever arrives second sees the first player's world, and the first player's world doesn't change under them. When the owner leaves, the next player in line takes over, and their own fleets come back.
- **Hyperspace:** players within 4,000 units (2 ly) of an owner follow the one who has been in hyperspace longest. They stop following beyond 5,500 units (2.75 ly), so the edge doesn't flip back and forth. Players further apart run their own hyperspace, which is unavoidable: a game only creates fleets around its own player, so nobody else's game has those fleets.

### Publishing (`NpcSync`), now any player

Any player with followers publishes their fleets: every NPC fleet in a system, or those within 8,000 units in hyperspace. That includes a friend whose world the host follows. `NPC_RESET`/`INFO`/`GONE` carry the publisher's id; the host relays a client's to all other clients, and keeps them in case it follows that client itself. `NPC_STATE` datagrams go client → host → the players following that publisher in that location.

A follower:

- steers its own copy of a save fleet (same id) to the owner's position, **and now gives it the owner's ships, name and faction**. Before, it kept its own ships, so a fleet that fought on one game but not the other looked different;
- shows a stand-in for any other owner fleet;
- hides its own extras: all of them in a system; in hyperspace, only those within 7,500 units of the owner.

### Locks (`FleetLocks`)

Lock requests go to the fleet's owner, not always the host. They travel on a new lock channel of `PLAYER_MSG`, relayed by the host: `claim`, `reply` (with the exact copy), `release`, `result` (ships lost), and `left` when a player disconnects.

### Shared objects (`WorldObjects`)

Each game checks the place its player is in once a second. When an object of these kinds appears, changes or goes, it sends a `WORLD_EVENT` to the host. The host applies it to its own world and relays it, so every world gets it, wherever its player is:

| Object | Created elsewhere | Changes passed on | Removal |
|---|---|---|---|
| Debris field (battle or other) | yes: size, density, salvage value and drops, recoverable ships, salvage seed | density, salvaged | yes |
| Derelict ship ("wreck") | yes: ship (stock variant or exact), condition, lifetime | | yes |
| Cargo pods | yes, with cargo | cargo | yes |
| Comm relay, nav buoy, sensor array (incl. makeshift) | yes: type, owner, orbit | owner, hacked | yes (deconstructed) |
| Stable location | yes, with orbit | | yes (built on) |
| Anything else salvageable from the save | no | | yes |

Objects created elsewhere get the same id, so later changes reach them. Arriving somewhere only takes stock, so nothing already there is reported as new. Changes we apply update what we've seen, so they don't echo back. Changes to the object the player is in a dialog with wait until the dialog closes. Mission-critical objects (`$missionImportant`) are left alone.

Known gaps: objects aren't locked while salvaged, so two players salvaging the same thing at the same moment can both get the loot. Objects owned by "the player" belong to each player's own player faction on their game.

### Player fleets are enforced

A player's fleet is only ever what that player's own game says. On everyone else's game it's a puppet, and puppets are now guarded:

- The ship list carries each ship's readiness and hull damage, to the nearest 10%, along with the exact loadouts.
- While a newer ship list is on its way (the position update with its new hash can arrive before the list over TCP), the puppet keeps the last list. Before, it fell back to the flagship alone for that moment.
- Once a second, each puppet is checked against what it was built from: the same ship objects, flagship, and a loadout signature (hull mods, s-mods, weapons, wings, vents, capacitors, mothballed). Anything this game changed (a hyperspace storm, a script, a battle it was somehow pulled into) is restored: the fleet is rebuilt exactly, and drifting readiness or hull damage is put back. A puppet the game despawned is put back too. Restores are logged (5 per puppet at most).
- Nothing in MultiSector writes to your own real fleet except a trade you accept or offer.

## 19. Fighting together (`JointBattle`)

The message-by-message protocol, and who owns which state, is in [joint-battle-protocol.md](joint-battle-protocol.md).

Like the Co-op mod's battles, but each player keeps their own ships. The battle runs on one game, the **leader's**: whoever's fleet is in it.

1. While the leader is in a battle dialog, every other player whose fleet is within 2,000 units is asked to join (`ask`, on a new battle channel of `PLAYER_MSG`). A player who is busy (in a dialog, a menu or their own battle) declines automatically.
2. The other player's game opens a dialog: **Join the battle** or **Stay out**. Joining sends their fleet exactly: loadouts, officers, their own character on the flagship, readiness, damage, member ids (`join`).
3. The leader's game rebuilds that player's puppet as the exact copy, stops guarding it (`PuppetManager.holdForBattle`), and adds it to the battle on the leader's side (`BattleAPI.join`). It's an allied fleet: the battle AI deploys and fights it, and the leader can't pilot it. The game already keeps allied ships out of the leader's post-battle recovery. A join that arrives once combat has started is refused (`late`). The leader's dialog says who's coming, so they can wait before engaging.
4. While someone has joined, battle set-up (`pickBattleCreationPlugin`, MOD_GENERAL) is the game's own `BattleCreationPluginImpl` plus a combat plugin. Once a second it sends each joiner the state of every one of their ships (reserve, fighting with hull %, retreating, retreated, disabled, destroyed). It carries out a joiner's **retreat** order on all their ships. Campaign scripts don't run during combat, so the plugin also reads battle messages.
5. When the battle is done (`isDone`, or the leader's fleet is out of it), the leader compares the puppet with the copy it was given and sends a `result`: ships lost, and hull and readiness for those that came back. The joiner's game applies that to their real fleet. If every ship was lost, the flagship survives at 10% hull and no readiness, because a player can't be left with no fleet. If only the flagship was lost, the player moves to another ship. The puppet then goes back to showing their ships as they are.
6. A leader who leaves the game mid-battle leaves the joiner's fleet untouched. A joiner can stop waiting after two minutes of silence. A result that arrives later is still applied.

Both players sit in dialogs meanwhile, so neither fleet moves.

### Not yet

- **Piloting your own ship** in someone else's battle. That's what the Co-op mod gets from screen sharing. Here it would need the battle running (or at least shown) on both games, with controls sent across: a project of its own.
- **Loot and experience** go to the leader, as with any allied fleet. Reputation effects with the enemy's faction apply to the leader only.

## 20. Battle view, piloting, and more of the world shared

### Seeing and piloting a joint battle (`JointCombat`)

Like the Co-op mod, but across two games instead of one shared screen. The battle still only happens on the leader's game; the joiner gets a live view of it and flies their own ship.

**Leader's game** (`JointCombat.Leader`, a combat plugin added while someone has joined):

- Announces each ship as it enters the battle (`cship`: exact loadout, side, whether it's the joiner's, whether it's the one they pilot) and drops ships that leave (`cgone`). Fighters and station modules come with their ship.
- Streams every ship's state 20 times a second over UDP (`COMBAT_UDP`, relayed by the host): position, velocity, heading, turn rate, hull, flux, hard flux, shield on and facing, phased, system active, overload, venting, hulk, retreating, which engines are firing, and which weapons are firing at what angle. Also its combat speed (time multiplier) and whether it's paused.
- About 1.5 s into the battle, deploys every combat-ready ship of each joiner (not mothballed, not civilian, readiness 20%+), even beyond the battle's deployment limit (`jointBattleDeployAll`). The ship a joiner pilots always goes in. Before, allied ships only deployed if there was room, which is likely why a host who joined a friend's battle didn't show up.
- Gives the piloted ship to `RemotePilot`, a ship AI that flies it from the joiner's controls (`COMBAT_UDP` input packets, about 30 a second). Held keys: accelerate, back, turn, strafe, decelerate, fire. Counted toggles: shield, system, vent, fighters, autofire. Plus weapon group, target, and the mouse position (the selected group's weapons aim at it within their arcs). Without input for 1.5 s it falls back to the ship's own AI.

**Joiner's game:** when the leader's combat starts (`cstart`, with the map size), the join dialog starts a battle of its own (`MirrorCreation`, picked only for that one start): an empty field of the same size, with `JointCombat.Mirror`. That plugin:

- spawns each announced ship (its own ships on the player side, the leader's as allies, the enemy as enemies), with an AI that does nothing, and makes them unable to take damage;
- puts each ship where the stream says, predicted forward by its velocity and the leader's speed, with hull, flux, shields, overloads and hulks. Engines and systems fire with the same commands as on the leader's game, and weapons fire where the leader's fire. Shots fly and hit visually, but nothing is damaged; only the leader's game decides what happened;
- makes the piloted ship the player's ship (camera, HUD), blocks its local controls, and sends them to the leader instead: WASD to move, Q/E to strafe, X to decelerate, mouse to aim, left button to fire the selected group (1-7), right button for shields, F system, V vent, Z fighters, T autofire, R to target the enemy nearest the mouse;
- follows the leader's combat speed and pause. A speed-up mod on the leader shows at the same speed on the joiner's game, and one on the joiner's game is cancelled out;
- closes when the leader's combat ends (`cend`), when the result arrives, or after 20 s without a packet.

Without the view (`jointBattleView: false`, or if it can't open) the joiner gets the report and retreat order from 0.0.25.

Known limits of the view: fighters and missiles on the joiner's game are its own (launched and fired locally, cosmetic); weapons that aren't firing keep their own aim; phase cloaks aren't shown; piloting has the network delay of a round trip; keys are Starsector's defaults, not your own bindings.

### Both players on one fleet

When you reach a fleet another player is already dealing with, you're no longer only told to wait: **Offer to join their battle** sends a join request (`join` with battle 0, "whatever you're fighting"). That covers both a fleet from the world you follow (the owner's refusal now carries the holder's id) and one from your own world that a follower holds. The leader takes it if they're still in the battle dialog and combat hasn't started.

### More of the world shared

- **NPC fleet details:** `NpcInfo.extra` carries each ship's hull, readiness (both to 5%) and mothballing, and the commander's name, portrait, rank and post. Followers apply them to stand-ins and their own copies; it's re-sent when it changes.
- **Objects already there:** an owner with followers sends everything rebuildable around it (debris, wrecks, pods, objectives, stable locations; within 8,000 units in hyperspace) when followers arrive and every 20 s. Followers add what they're missing. Before, only changes were shared, so a derelict that existed before a friend arrived never reached them.

### First test fixes (0.0.32)

- **Crash on the leader** ("Ship ... is being controlled by an AI that is assigned to something else"). The remote pilot called the ship's previous AI whenever the joiner's controls weren't arriving, which the game forbids once an AI has been replaced. Now the leader swaps the ship between the remote pilot (while controls arrive) and a fresh default AI (`resetDefaultAI`, while they don't), and says so in the combat log.
- **Joiners' ships never reaching combat.** The fleet dialog builds each side's combined fleet when it opens (`genCombined`), and the battle is fought with those. A fleet joined later was in the battle but not in the combined fleet. Now a join rebuilds them (and the battle's snapshots, before the first engagement).
- **"Scuttled" ships in the view.** New fleet members start at no readiness, so the view's ships malfunctioned. They now start at full readiness, and the stream carries each ship's readiness.
- **Blank battlefield in the view.** It now uses the backdrop of the place you're in (and hyperspace mode there).
- **Live updates for the leader.** The dialog says when each nearby player is asked, when they've seen the call and are deciding, when they're bringing their fleet in, when they've joined (also as a message on the map), when they decline or are busy, and when there's been no answer for 30 s. In combat, the message log shows each joiner arriving, taking the helm of their ship, and losing their connection.

### Joining once the fighting has begun (0.0.33)

Testing 0.0.32 showed that changing the campaign battle while it's being set up (adding the joiner's fleet with `battle.join`, then rebuilding the sides with `genCombined`) confuses it: duplicate mothballed ships, enemies that never deployed, enemies shown as the joiner's allies. So the campaign battle is no longer touched at all.

- **Nobody is pulled in.** The leader no longer asks nearby players. While the leader is in a battle, every other player is told about it every 2 s (`fight`: battle id, enemy name, and `live` once combat has begun; `fightover` when it ends). A battle not heard about for 7 s counts as over.
- **Joining is from the map.** Click the fleet being fought (it's locked: "Join their battle"), or the leader's own fleet, which opens the join dialog instead of a hail while they're in a battle. The dialog only offers **Join** while combat is running; before that it says to wait, and it updates by itself when combat starts or ends. Opening it tells the leader (`seen`): in the dialog, "X wants to join your battle", and in combat, "X is getting ready to join your battle".
- **Joiners arrive as reinforcements.** `JointCombatHook`, registered in `data/config/settings.json` (`plugins`), runs in every combat and starts `JointCombat.Leader` when it's our campaign battle during a session (not simulations, missions or our own view of a friend's battle). A `join` that arrives before combat gets `notyet`. During combat, the joiner's exact fleet is rebuilt in a fleet of its own that's outside any location and any battle, and its combat-ready ships (always including the piloted one) are spawned with `spawnFleetMember` at the leader's edge of the map as allies. The game leaves members with no source fleet out of the battle's outcome (`FleetEncounterContext.clearNoSourceMembers`), so the leader's battle and loot are unaffected.
- **The leader is told in combat.** The message log says "X joins your battle with N ships, flying Y themselves", with the radio sound.
- **The joiner sees the battle as it is.** On join, the leader sends `cstart` and every ship already in the fight; the view opens with them. Wrecks (owner 100) are no longer shown, which put them on the player side.
- **Results per engagement.** Each joiner ship's state, hull and readiness are kept from the status updates (every second, and when combat ends). When combat ends, or at the next campaign frame if it ended without the plugin seeing it, each joiner gets the result: destroyed and disabled ships are lost; the rest take their last hull and readiness; ships that never deployed are unchanged. A joiner who leaves the game mid-fight has their ships ordered to retreat.
- `PROTOCOL_VERSION` 14: builds with the old join messages won't connect.

### Waiting for the leader to deploy, and no duplicates (0.0.34)

From testing 0.0.33: joining while the leader was still on the deployment screen gave duplicate AI copies of the joiner's ships, on the leader's game and in the joiner's view, and enemies sometimes never appeared in the view.

- **Joiners wait for the leader's ships.** The leader's combat is `ready` once combat time has passed 1 s (it doesn't run while the deployment screen is open) and a ship of the leader's side that isn't a joiner's is on the field (or after 30 s regardless). `fight` carries `ready`. A `join` before then is queued and answered `wait` ("still deploying, your ships go in as soon as theirs are out"), and let in when the leader is ready. The join dialog shows the three stages: not fighting yet, deploying, fighting.
- **Nothing in the leader's battle dialog.** The "X wants to join" line before combat is gone; the leader only hears about joiners in combat ("getting ready to join", "waiting to join", "joins your battle").
- **Each joiner ship once.** Every joiner member is spawned at most once and taken out of reserves straight away. Twice a second the leader's guard removes a second live copy of any joiner ship (keeping the piloted one), any ship of a player's stand-in fleet (which must never be in a battle), and any of those waiting in reserve where the game could deploy them again.
- **The view only shows the leader's battle.** It removes any ship it didn't make itself (wrecks' pieces aside) and keeps both reserves empty.
- **Missing ships are asked for again.** Ships the stream mentions that the view doesn't have (an announcement lost, or a ship that couldn't be made) are asked for (`cwho`, up to 3 times each), and the leader announces them again. Ships the view can't build or place are logged with what's missing.
- `PROTOCOL_VERSION` 15.

### The joiner catches up first, then their ships come in (0.0.35)

From testing 0.0.34: the joiner's ships were already in the battle as AI copies before the joiner was in it, the view showed the enemy as allies, and most ships in it looked scuttled.

- **Enemies as allies in the view:** a newly built fleet member is on the player's side (owner 0), and the view only chose the fleet manager. The view now sets each member's side and ally flag before spawning it, and the ship's afterwards.
- **"Scuttled" ships:** the joiner's own game could start the leader's combat logic inside the view (`JointCombatHook` only checked for an open view, which may not be set yet on its first frame). That logic reads the same combat datagrams as the view, so most of the leader's updates never reached the view's ships. Now `viewOpening` is set before the view's battle starts, and the hook never leads a combat while a view is opening or open.
- **Ships in only after the joiner has caught up.** Joining no longer puts anything into the battle. The leader sends the view everything (`cstart`, every ship) and waits. The view says it's ready (`cdeploy`) once every ship it was told about is placed from the stream (at least 1.5 s after it opens, at most 8 s). Only then does the leader spawn the joiner's ships ("X joins your battle with N ships"). Without a view, `cdeploy` is sent on joining. If it never comes, the leader brings them in after 25 s.
- **No stand-in fleet in a campaign battle.** If a player's stand-in fleet is ever in a battle on another game, it's taken out straight away (`BattleAPI.leave`), instead of waiting for the battle to end.
- `PROTOCOL_VERSION` 16.

### Players' ships are tagged, not AI allies (0.0.35)

- Every ship a joiner brings in carries their player id (`JointCombat.PLAYER_TAG` in the ship's custom data). Everything about those ships follows from the tag, every frame on the leader's game:
  - **Not an AI ally.** They're on the leader's side with `isAlly` false, so the ally admiral AI never drives them, and the leader's tactical orders (assignments, waypoints, full retreat) reach them like the leader's own ships. They still use the game's ship AI, except the one the joiner pilots.
  - **Marked in the player's colour**: a soft glow under the hull, from a fixed palette by player id, so it's the same colour on every game. Their name floats over each ship as it arrives.
  - **Never taken over.** If the leader switches to one of them, they're put back on their own ship ("That ship is X's: they command it").
- `cship` carries whose ship each one is (`player`: a joiner's id, the leader's id for the leader's own ships, or -1). The joiner's view tags and marks other players' ships the same way, so the leader's ships glow in the leader's colour there.
- Still to come: commands from the joiner to their own ships. For now their ships take the leader's side's orders.

### The joiner deploys and commands their own ships (0.0.36)

From testing 0.0.35:
- The joiner had a deployment panel, but their ships came in by themselves anyway.
- The leader could command the joiner's ships as their own, and the colour marking didn't show.
- In the view every ship was unflyable, and the command map showed them all as mothballed, while combat showed readiness.

What changed:
- **The view's ships have a proper fleet record.** A member in no fleet has no readiness on its record (the command map reads it there, not the ship's combat readiness), so it shows as mothballed and can't be commanded or flown. Each view ship is now added to a view fleet (one per side and whose), its record's readiness is set from the leader's announcement (`cship` carries `cr`), and kept in step with the stream.
- **The joiner chooses what deploys.** The view keeps the joiner's real fleet in its reserves (and nothing else), so the deployment panel and the command map's Deploy list their own ships. A ship they deploy holds still in the view (damage-immune, controls blocked). The view sends `cdeploy` with its id and position. The leader spawns exactly that ship there, and announces it with `real` (the joiner's member id); the view adopts its held ship as that one instead of making another. Nothing else of the joiner's deploys. A view with nothing in reserve (no panel) falls back to bringing everything in.
- **Independent command.** On the leader's game the joiner's ships are allies again, so the leader can neither command nor fly them. The joiner commands them from the view's command map: every 0.5 s the view reads its task manager's assignment for each of their ships (type, and target ship or location) and sends any change (`corders`, and the full set every 3 s). The leader gives each order through the ally task manager (`createAssignment` on the target ship, an objective within 400 units, or a waypoint, then `giveAssignment`). It re-gives an order the ally AI replaced, at most every 3 s per ship. Ships the joiner hasn't ordered follow the ally AI. A full retreat in the view orders their ships out. The view has no battle objectives, so orders to capture them can't be given from it yet.
- **Transfer command.** Taking the helm of another of their ships in the view sends `cpilot`. The leader moves `RemotePilot` to that ship and gives the previous one its AI back.
- **Rings.** Each player's ships get a ring in their colour, drawn in world space (`renderInWorldCoords`), as well as the glow. The view rings the leader's ships and other joiners' ships, but not your own.
- `PROTOCOL_VERSION` 17.

### What the joiner sees, deploying, and stuck joins (0.0.37)

From testing 0.0.36: the leader's side worked well. In the joiner's view, ship systems, venting and most shots were missing, the joiner's fleet still deployed by itself, deploying from the panel made duplicates, a join could leave the joiner only able to hail the leader, and the joiner's own ships had no colour.

- **Every shot, not a sample.** A weapon is only "firing" for a moment per shot, so sampling that 20 times a second missed most shots. The leader now counts each new projectile and missile per weapon (`DamagingProjectileAPI.getWeapon`) and sends the count since the last update. The view spawns exactly that many from the weapon (`spawnProjectile`), at the leader's angle. Beams still fire for as long as the leader's do. The view's ships hold fire otherwise, so their autofire adds nothing of its own.
- **Systems, phase cloaks, venting** are set directly from the leader's state (`forceState` to the same system state, `setPhased`, `ventFlux`). Commands could be refused on the view's ships. The state now carries each ship's system and cloak state (one byte each).
- **No whole-fleet fallback in the view.** If the view's deployment list doesn't have the joiner's ships when it has caught up, they're added to it (`addToReserves`), instead of everything being brought in for them. That fallback was the "AI deploy". A ship deployed from the panel that's already in the leader's battle is removed at once. That was the duplicate. Ships already in the battle are kept out of the deployment list.
- **The view doesn't unpause under its own dialog** (the deployment panel), only with the leader.
- **Stuck joins.** A join that hears nothing from its leader for 30 s (and has no view open) is over. Before, a battle that ended without a result reaching the joiner (for example, if another fleet pulled them away) left them marked as in it, and clicking the leader only offered a hail.
- **Colours on the joiner's game too:** the view rings and marks every player's ships, its own included, so both games show the same colours.
- The leader's log says how each joiner's ships came in (`deploys N ship(s) from their view`, or `nothing to deploy from`).
- **Only the joiner deploys their ships (rechecked).** There was still a whole-fleet deploy in two places while the view was on: when the view caught up with nothing in its deployment list, and when the view couldn't open because the join dialog was gone. Both are gone; the whole-fleet deploy is only for `jointBattleView: false`. The view also recognises its own ships when the battle lists copies of them (same hull and name), instead of removing them as strays, so deploying from the panel works either way. On the leader's game, the joiner's ships spawn in one place, from either a deploy from the view or the no-view whole fleet. Every spawn is logged with why and where (a short stack), as is every deploy the view sees (combat time, and whether a dialog was open).
- **Autopilot:** with autopilot on in the view, input packets say so (`K_AUTO`), and the leader gives that ship its own AI until it's off.
- **Selected weapon group:** the piloted ship in the view picks its weapon group itself (that one command isn't blocked), so the selection shows, and that group is what's sent.
- `PROTOCOL_VERSION` 18 (the state datagram changed).

### Piloting and orders (0.0.38)

From testing 0.0.37: deploying, markings and duplicates were fixed, but the joiner couldn't fly their ship at all, and a game crashed ("Cannot invoke ReadableVector2f.getX() because src is null") a while after the joiner started giving orders.

- **Piloting:** the game shows the view's flown ship as on autopilot from the start, because it has the view's do-nothing AI. 0.0.37 sent that as the player turning autopilot on, so the leader kept the ship on its AI. Now autopilot only counts once it has been off: turning it off and on again is the player's choice. When the flown ship changes, the old one gets the do-nothing AI back.
- **Orders, hardened** (the crash's stack trace wasn't available yet):
  - Each order is only passed on with a target that fits it: a live ship in play for ship orders (intercept, engage, harass, strike, escorts), an objective within 400 units for objective orders (capture, control, assault), and an objective or a spot for the rest (defend, avoid, recon, rallies). Other orders aren't passed on.
  - An order is made once per ship and given again when the ally AI replaces it, instead of a new assignment and waypoint every 3 s.
  - Target positions are copied, never shared.
- **The view removes ships cleanly** (out of its side's deployed list too), so no order or AI in it is left pointing at a ship that's gone. Shots are never spawned from a missing fire point.
- `PROTOCOL_VERSION` 19.

### Orders on arrival, autopilot, and the view's pause (0.0.39)

From testing 0.0.38: commanding works, but the crash came back when ordering ships that hadn't finished arriving. Autopilot was unreliable: an order to the flown ship put it on autopilot for good. The joiner couldn't transfer command, because the view kept itself paused while the command map was open.

- **No orders mid-arrival.** A ship flying in from the edge after deploying has its travel drive on (`ShipAPI.getTravelDrive`). The leader gives no order to such a ship, nor one that targets it, until it has arrived. The view sends orders only for ships that are settled (updated from the stream, at least 2 s old). In the view, a ship the joiner deploys has its travel drive switched off straight away (`turnOffTravelDrive`): the leader's game is what moves it in.
- **Autopilot:** flying the ship yourself (any move or fire key) takes it off autopilot, as in the game (`shipControlsTurnOffAutopilot`). The game's own switch can't see our controls, since they're blocked locally and sent to the leader. It stays off until autopilot is switched off and on again. Giving your own ship an order still puts it on autopilot to carry the order out, and pressing a control takes it back.
- **The view runs unpaused.** It's only paused under the deployment panel (`CombatUIAPI.isShowingDeploymentDialog`). It's unpaused under the command map (so transferring command works) and otherwise, and left alone under other menus. When the leader pauses, the view runs at 2% speed with every ship held where the leader's paused battle has it, instead of pausing.
- `PROTOCOL_VERSION` 20.

## 21. One shared world (0.1.1)

From testing 0.0.39: autopilot behaves. Transfer command turned the joiner into a stuck command shuttle, and a crash still happened with nobody doing anything. Both are fixed, along with a batch of world-state work.

### Battle view fixes

- **The crash** ("Cannot invoke ReadableVector2f.getX() because src is null") was in the battle view, inside a ship system: `Ship.advance` → `systems.<displacer>.systemActivated` → `Vector2f.set(null)`. The view switches systems on directly (`forceState`) to mirror the leader. A teleport system (Phase Skimmer, displacer, phase teleporter: types `DISPLACER` and `TELEPORTER`) jumps to a spot that only the game's own "use system" sets, so it was still null. The view now only switches on systems that change the ship's own stats or engines (`STAT_MOD`, `ENGINE_MOD`, `PHASE_CLOAK`, `SHIELD_MOD`, `AMMO_RELOAD`, `FAST_RELOAD`, from the system's spec JSON). Teleports, drones, weapon and EMP systems aren't switched on; the stream moves those ships anyway. It's logged once per system. Any Wolf or Afflictor in the fight could set it off, which fits "crashed while doing nothing".
- **Transfer command:** the game flies you to the new ship in a command shuttle, a ship of its own. The view removed it as "a ship the battle view didn't make" (log: `removing ... shuttlepod`), leaving camera and controls on a ship that no longer existed. Shuttles are now left alone and can't be hurt. While you're in one, your old ship flies on its AI on the leader's game; when the shuttle lands, the controls move to the new ship as before. The leader doesn't announce its own command shuttle to views.
- **Which systems the view switches on, audited.** Beyond the type check, a system's script must be the game's own (`com.fs.starfarer.api.impl.combat`, or the self-contained ones in `data/shipsystems/scripts`). It also must not be one that acts on a target, the mouse or the AI's chosen spot, or that spawns things: acausal disruptor, drone strike, entropy amplifier, interdictor array, mine strike (and its station version), mote control, and the Dweller and Threat systems (convulsive lunge, darkened gaze, tenebrous expulsion, energy lash, extraction protocol, fragment volley, incursion mode, construction swarm). Convulsive lunge does `new Vector2f(ship.getMouseTarget())` with no check: the displacer crash again. Mods' system scripts can't be checked, so they're shown without being switched on; their ships still move with the stream.
- **Thrusters on the ship you fly.** The view blocked every command on it, including movement, and only hinted "accelerating", so its turning, strafing and braking jets never showed. Movement commands now go through on the view (they only light the engines; the stream still decides where the ship is), along with picking a weapon group. Weapons, shields, systems and venting stay blocked. The leader's engine state is also given to it, for autopilot and the moment you're in the command shuttle.
- **The leader follows the ship the controls are for.** Control datagrams always carried the flown ship's id. The leader now switches the joiner's helm to that ship too, not only on the `cpilot` message (TCP, through the host), so a transfer of command takes effect with the first control packet. Autopilot packets don't move the helm.
- **Spots for flown ships' systems.** On the leader, the joiner's flown ship has its mouse target and the AI's `SYSTEM_TARGET_COORDS` kept at the joiner's mouse. Teleports, mine strikes and the like then go where the joiner aims, and never read an empty spot.
- **Transfer command, checked against the game.** `CombatState.setPlayerShip` gives the old ship back the AI it had before you flew it (the view's do-nothing AI) and clears the new ship's while you fly it. The command shuttle keeps no AI record. When you have no ship at all, the engine shows a stand-in "Command Shuttle" (`shuttlepod_xfer`, `getFakePlayerShip`): that's the shuttle interface seen when the view deleted the real transfer shuttle.
- **Comparison checks:** a wreck is side 100 on every game, so wrecks' sides aren't reported as differences any more (0.0.38's "side 100, leader says 1" lines were that).
- **The view's do-nothing AI stays on.** The game gives the old flagship a real AI when command is transferred. A real AI in the view fights and uses systems by itself. Four times a second, any view ship other than the one you fly whose AI isn't ours gets it back (logged).

### Time

- **The world waits while nobody is on the map** (`holdWhileEveryoneBusy`). After each joint battle the log said "caught up 13-24 days": the shared clock ran on through the whole battle, then everyone's date jumped, with nothing simulated meanwhile. The host's network thread now holds the clock (`SharedClock.setIdle`, flag `IDLE` in `CLOCK`) while no player is on the campaign map. A player's map, or map while paused, counts as on it; dialogs, menus and battles don't. The check runs on the network thread because the campaign doesn't run during battles; a position more than 3 s old counts as not on the map. The host's own map frames clear it at once.
- **Anyone can fast-forward** (`friendsCanFastForward`). A friend holding fast-forward sends `FAST_REQUEST` (on, their `campaignSpeedupMult`), repeated every second. The host's shared clock then runs fast (`fastBy` in `CLOCK`), so every friend's speed-up cap lifts. The host's own fast-forward flag can only be set by its key: `CampaignState.fastForward` is private and re-read from the keyboard every frame. So while a friend fast-forwards and the host doesn't, the host's game gives its own fleet and the NPC fleets around it the extra time itself (`NpcSync.boostLocalFleets`, in 0.1 s steps, at most 1 s per frame). The date and the planets step forward in 6-hour pieces. Everyone is told who is fast-forwarding.

### Session rules (`SessionGuard`)

- **Waiting for the host** (`waitForHost`): a friend's world that has joined (or tried to) stays paused while not connected, and retries every 6 s. Loading before the host, or the host's game restarting, never lets that copy run on alone. F8 has "Stop waiting".
- **The host ends the session** (`endWhenHostLeaves`): a BYE "The host stopped hosting". A host whose world hasn't run a frame (campaign, battle or battle view) for 90 s (back at the main menu, say) stops hosting by itself, so its port closes and nobody can join a session with no world behind it; loading the world again hosts again. Friends' characters are saved and their world saved, then a dialog takes them to the main menu (or lets them stay offline). A lost connection without a goodbye is waited out instead.
- **Saving together** (`saveWithHost`): the host's `beforeGameSave` sends `HOST_ORDER {"k":"save"}`; friends save on their next map frame (from input handling, like `HostSaver`). A friend's every save also saves their character for that world (section 22).
- **Waiting for everyone** (`waitForAllPlayers`, off by default): the host's save remembers who was playing (`$multisector_lastPlayers`). After loading, the clock holds for each of them, using the joining hold, so it ends when they connect or when someone unpauses.
- **Another world loaded:** a session started in one world ends when a different world (seed) loads. Reloading the same world while hosting tells friends their copy is now ahead and to download again.

### World state (`WorldState`, on the world-event channel)

| What | Who sends | How it's applied |
|---|---|---|
| Raids, disruptions, bombardments (`ColonyPlayerHostileActListener`) | The player who did it, once the dialog closes | Disrupted industries (longer only), unrest (`RecentUnrest`, more only), raid timestamp, raid-defender bonus, pollution, size reduction, or decivilization. Deferred while the receiver is docked there. |
| Surveys, `$ruinsExplored`, `$explored`, `$siteExplored`, `$defenderFleetDefeated`, `$hasDefenders` false | Whoever's there (scanned every 2 s, arriving only takes stock) | Raised to match (`Misc.setFullySurveyed`/`setPreliminarySurveyed`), never lowered |
| Relations between NPC factions | Host, every 5 s on change, every 60 s regardless | Pairs involving the player faction are skipped: reputation stays per player |
| System bounties (`SystemBountyIntel`, not commerce-mode) | Host, every 10 s on change | Missing ones are posted, extras of the friend's own game ended (usually before they're shown), elapsed time and duration matched. Payments stay per player. |

Not shared yet: NPC factions' own raids, expeditions and invasions; pirate and Pathers bases; market ownership; player colonies.

### Markets

Ship and item stock already came from the host on docking. Now, after any trade at a shared submarket (the host's own, or a friend's), the host sends the new stock to every player docked at that market (`FleetState.dockedAt`), at most twice a second. Stock that arrives while the trade screen is open is applied when it closes, instead of being dropped. A ship someone just bought can no longer still be for sale on another player's screen.

### Players together

- **Escort:** clicking another player's fleet now offers Hail or Escort. Escorting flies a little behind their fleet with no dialog on either side. It clears any plotted course, follows through the jump point nearest where they were last seen, and ends when you set another course or they leave.
- **Fleets in menus** (`keepMovingInMenus`, F8 switch): in the cargo, refit, character or map screen (not dialogs), your fleet keeps flying its course at the shared clock's speed, instead of freezing while the world goes on. Off: it stays put.
- **Respawn:** losing every ship opens MultiSector's respawn (`CampaignPlugin.pickRespawnPlugin`) when someone else is playing. It's the game's new start (a Wayfarer and a Kite, crew, supplies, fuel; credits, officers and mission items kept, other cargo lost). You're placed next to the player you pick, or at the nearest friendly port.

### NPC battles

A battle between NPC fleets in the world you follow can now be joined (`joinNpcBattles`). The owner's claim answers with the whole battle: every fleet in it is held and described (handle, side, exact ships). The follower rebuilds each, puts them into a local battle (`FactoryAPI.createBattle`, then `join`), and the normal fleet dialog's "join the battle" takes over. When the dialog closes, each fleet's losses go back separately, and the local battle is left. The owner's battle goes on with what's left. Battles with a player or stand-in in them, or with a fleet the owner doesn't publish, are refused as before.

### Nexerelin

- Taking over a held NPC fleet opened the game's own `FleetInteractionDialogPluginImpl`, but Nexerelin replaces that dialog for every fleet (`NexFleetInteractionDialogPluginImpl`, `MOD_GENERAL`). With Nexerelin enabled, its dialog is now loaded by name, so there's no compile dependency.
- Faction relations (its diplomacy) follow the host. Its invasions, market transfers and alliances still run separately on each game.
- The Nexerelin in this install is 0.10.4c for 0.95.1a and won't load on 0.98.5a; it needs a 0.98 build.

### Launcher

- **Host and launch:** starts sharing the save, makes it the newest (so the main menu's Continue loads it), and starts the game. **Join and launch:** check, download, start.
- **Update MultiSector:** fetches a release zip from this repo's GitHub releases (the newest, or the host's build when the check finds a mismatch, `LobbyClient.VersionMismatch`). It's unpacked to `mods/MultiSector.update`. A script waits for the launcher to exit, mirrors it over the mod (keeping `multisector.json`) and reopens the launcher. It refuses while Starsector runs or while hosting.
- The last hosted save is remembered.

`PROTOCOL_VERSION` 21 (`CLOCK` gained `fastBy`; new `FAST_REQUEST` and `HOST_ORDER`).

## 22. Joining without friction (0.1.1)

The aim: a friend opens the launcher, clicks one button, clicks Continue, and plays. Every step that used to need knowing something (which mods, which character, whether the host is ready, whether the copy is current) is now handled or explained where it happens.

### Before the game starts (launcher)

- **Mods follow the host.** Join and launch compares the friend's enabled mods with the host's. If they differ and every one of the host's is installed, it switches Starsector's `mods/enabled_mods.json` to exactly the host's list (plus MultiSector). The friend's own list is kept the first time in `enabled_mods.json.multisector-backup`, and **Put my mods back** restores it. If a mod isn't installed, it stops and names it rather than starting a game whose save won't load. It won't change the list while Starsector runs, since the game only reads it at start. **Use the host's mods** does the switch alone.
- **MultiSector is always ticked.** Launching from the launcher ticks MultiSector in the game's mod list if it isn't.
- **Is the host ready?** `LOBBY_INFO` ends with a byte saying whether the host's game is in the world and hosting (its launcher tries the game port on loopback). The friend's launcher then also tries that port across the network. It says "in the world and hosting", "not hosting yet (download now; your game waits for theirs)", or "hosting, but this PC can't reach it" with the firewall fix to ask for.
- **The host's firewall.** The Host tab reads Windows Firewall through PowerShell's firewall commands, which need no admin rights and don't depend on the Windows language. It accepts MultiSector's port rules, or allow rules for the game's `java.exe` and `javaw.exe` on public networks (what clicking Allow on Windows' prompt makes; Hamachi is usually public), and flags any block rule for them, since a block beats an allow. If friends would be blocked, **Open it for friends** runs `tools/firewall.cmd`, which asks for admin, and checks again.
- **Mismatched builds** show on the host's launcher too ("Febl has 0.0.39 and you have 0.1.1").
- **Any save, any build.** A save doesn't need making again for a new MultiSector build. The game doesn't check a mod's version when loading: `descriptor.xml` only records it, and rewrites it on the next save. MultiSector keeps almost nothing in a save, just its ability-bar button (`MultiplayerAbility`, unchanged since it was added) and a few string flags. A save never played with MultiSector gets it when it loads. The Host tab shows which version last saved each one.
- **Updating mid-session.** Update MultiSector works while Starsector runs: it downloads and stages first, asks you to save and quit, and swaps the mod once the game has closed. It then carries on with what you were doing: hosting the same save (Host and launch), or joining the same host (Join and launch). It's kept in the launcher's prefs (`resume`, ignored after 20 minutes). A friend's launcher that's waiting for the host notices when they come back on another build, and offers that build; once updated, it joins by itself.

### Coming back as yourself (character)

- Your character in a shared world is saved by MultiSector to its own file per world (`multisector_world_<seed>.json` in `saves/common`): at every save while connected, and when you leave. Your hand-made export (F8, Export character) is never overwritten by play.
- When a fresh download of a world you've played in loads, you come straight back as that character ("Welcome back"), with no chooser (`GamePrefs.autoCharacter`, on by default; the chooser has a checkbox and F8 a switch). A new world, or no saved character, opens the chooser, which now offers "Continue as <name>" first, then your export, then a new character.

### Always the host's current world (fresh copies)

- **World ids.** Each load of a world gets a random id. A hosting game writes it into every save it makes (`$multisector_epoch`), so the copy a friend downloads says which load of the host's world it came from. Friends' saves never change it. As a friend joins, the host sends its current id (`HOST_ORDER {"k":"epoch"}`), and again if it loads its world again while hosting.
- **A copy that doesn't match** (the host loaded a save since, or it's a copy from an earlier session) opens "This copy of the world isn't the host's any more": **Get a fresh copy** or **Play on in this copy**. Copies from before world ids can't tell, and are left alone.
- **Getting a fresh copy:** your character is saved, you leave the session and go to the main menu without saving the old copy, and the game asks the launcher for the world (`saves/common/multisector_rejoin.json`). The launcher polls for that every 2 s, downloads the host's world (a fresh save, with the usual hold), and says "click Continue". On load you come back as yourself (above) and join. F8 has **Get a fresh copy** at any time while connected.
- **When the host ends the session,** "Return to the main menu" also asks the launcher to wait for them. Every 10 s it checks whether their game is in the world and hosting again, then downloads it. It gives up after an hour or on **Stop waiting for the host**. A request more than 30 minutes old is ignored.

### Host side

- **A world that closes stops hosting.** A host with no world frames for 90 s stops hosting (BYE to friends) instead of keeping a session open with no world, which a waiting friend's launcher would mistake for "hosting".
- **Reconnects replace the old connection.** A player connecting again under the same name from the same address (after a drop) replaces their old connection at once. That connection's cleanup still frees their locks and battles, but doesn't announce a leave. Before, the old one lingered as a ghost player until it timed out (15 s).

## 23. First 0.1 test round (0.1.2)

From testing 0.1.1: the joiner can transfer command normally now. Problems found:

- **The joiner's weapons stopped answering** (no manual fire, autofire toggle doing nothing) after autopilot or a transfer of command. While the AI flew the ship on the leader's game (autopilot, or as an ally before the transfer), it could leave the ship holding fire and its groups on autofire. Our pilot never undid either. `TOGGLE_AUTOFIRE` is also only carried out for the game's own player ship. Now the leader's pilot:
  - keeps, per ship, which groups the joiner wants on autofire: the loadout's own settings at first (`WeaponGroupSpec.isAutofireOnByDefault`), kept across autopilot and AI swaps;
  - toggles the group itself on T;
  - takes the selected group off autofire while the joiner holds fire, so it fires where they aim;
  - clears hold-fire every frame.

  The stream carries each ship's autofire groups (a byte, first 8 groups), and the joiner's own flown ship shows them in its HUD.
- **Moving in menus** only flew toward the spot the fleet was heading for when the menu opened. A course plotted on the map screen is followed by the game's autopilot, which only runs on the map. It's now followed step by step (`CampaignUIAPI.getNextStepForCourse` on the course target); jump points wait for the map. Escorting also goes on in menus, without taking jumps. It's still on by default (`keepMovingInMenus`).
- **Saves come from the host.** A friend's game doesn't autosave while connected: the game's `autosaveFeatureEnabled` setting, which it reads at every autosave check, is turned off and put back afterwards. Manual saves can't be blocked, so a friend's own save sends `SAVE_REQUEST`. The host saves the world on its next map frame, which, as always, tells every friend to save; the one who just saved skips it (within 90 s). Saves the host asks for never ask back.
- **Fast-forward for everyone.** A friend who isn't fast-forwarding while someone else is now gets what the host gets: their own fleet and their own world's fleets around them move the extra time (`NpcSync.boostLocalFleets`), and the date steps along quietly. The players panel's title shows "<name> is fast-forwarding (xN)" on every game but theirs, the host's included. The pop-up when someone starts no longer tells friends to fast-forward too.
- **Loading an autosave mid-session:** a friend loading one of their own saves keeps the host's world id, since their copy came from the host's current load. So nobody is prompted, and the date, fleets and objects catch up from the host. Only the host loading a save gives friends a new world id and the fresh-copy prompt.
- `PROTOCOL_VERSION` 22.

## 24. Second 0.1 test round (0.1.3)

From testing 0.1.2. Weapons now keep working through autopilot and transfers. Found:

- **The joiner's controls were hard-wired, and some were wrong.** The view read W/A/S/D/Q/E, X for decelerate, T for autofire and nothing for the strafe key. The game's own bindings (read from its settings) are: decelerate C (X is hold fire), autofire Ctrl+1-7 for each group (T is something else), and Shift held turns the ship to the mouse with A/D strafing. Now:
  - movement is read off the flown ship's own engines (`ShipEngineControllerAPI`), which show what the game made of the joiner's keys with their bindings, strafe mode included. The stream's engine commands are no longer given to that ship. If the engines never answer while keys are held (some mod in the way), the default keys are read instead, and the log says so;
  - the strafe key (`SHIP_STRAFE_KEY`, toggle or hold per `isStrafeKeyAToggle`) sends `K_FACE`, and the leader turns the real ship to the mouse itself, with its real facing, braking in time to stop on it;
  - autofire and hold fire are the game's own on the joiner's ship (`TOGGLE_AUTOFIRE`, `HOLD_FIRE` allowed locally). Each input carries the groups on autofire (a byte) and hold fire; the leader sets them on the real ship. A ship taken over starts with its loadout's autofire groups;
  - system, vent, fighters and target keys come from the joiner's bindings (`getControlStringForEnumName`, parsed; defaults if it names something else). The log lists them.
- **Autopilot was backwards.** `CombatEngineAPI.isUIAutopilotOn()` returns the opposite of the game's autopilot setting (it's `!CombatState.isAutopilotOn()`). With autopilot off, the view asked the leader for autopilot; with it on, it sent manual controls. The 0.0.x workaround ("autopilot at start doesn't count") only hid it. Now autopilot is exactly the game's switch, read the right way round; the game itself turns it off when you steer (`shipControlsTurnOffAutopilot`).
- **Missile ammo never went down in the view.** View shots are spawned projectiles, which use no ammo. The stream carries each ammo weapon's count when it changes, and all of them every tenth update for each ship (a datagram can be lost): a count byte, then index and ammo for each.
- **NPC battles:** the second player's controls were the same hard-wired keys; covered above.
- **Fast-forward is the host's** (`friendsCanFastForward` now off by default). A friend who wasn't holding fast-forward got the boost on their own fleets (in chunks) and a date that snapped forward 3 hours at a time, moving every orbit in the sector (27,000 with Nexerelin, about 8 ms each time): jumpy fleets, jumping planets and a date skipping ahead. Now a friend's date follows the host's in a small step every frame. Only the orbits where they are turn with it; the rest of the sector is owed that time and settled once a game day has built up, when fast-forward ends, or for one place as you arrive there (`ClockAccess.follow`, `payUp`, `settle`). Fleets copied from the host already predict at the host's speed. A friend holding fast-forward too runs at the host's speed natively. Everyone is told: the players panel shows "You're fast-forwarding" or "Febs is fast-forwarding (x3)" on a line of its own, the host gets a message (at most once a minute), friends a message saying to hold fast-forward too. A friend who presses fast-forward while the host isn't is told only the host can.
- **Campaign speed-up mods** (SpeedUp) write `campaignSpeedupMult`, the same setting a friend's cap uses; the cap is now kept every frame, so a friend can't run ahead with one.
- **Battle speed** stays the leader's: the joiner's view cancels any speed-up of its own (as before) and now says so when the joiner presses theirs. Both sides get a message when the leader's speed settles on a new one (slow motion from ship systems doesn't count). Battle speed never touches the campaign.
- **Recovering ships after a joint battle** (`JointSpoils`):
  - the joiner's own lost ships are held aside, prepared as the game prepares yours (d-mods, weapons and wings kept by the usual chances, `Misc.isShipRecoverable` for which need a story point) and offered on the game's own recovery screen (`showFleetMemberRecoveryDialog`) from the battle's dialog, or on the map if that's closed. Recovering uses `FleetEncounterContext.recoverShips`; officers and AI cores go back on their ships, and an AI core on a ship left behind goes back into the cargo;
  - the leader notes the enemy's disabled and destroyed ships as combat ends (exact copies, before its own recovery screen changes them). When the leader's dialog closes, the ones not in its fleet go to everyone who fought (`spoils`). Each joiner can recover them like any enemy ship;
  - held in memory only: a save before they're recovered or left doesn't keep them. Vanilla may also leave some unrecovered ships in space as derelicts, so one could be claimed twice.
- **Your character in each world** now also keeps AI cores commanding ships (`aiCore`, `unremovable` on a person; `aiCaptain` on a ship), your standing with every faction, and where your fleet was. Standing and position come back only into the same world (`sourceWorld`). Before, every fresh copy put you at the host's position with the host's reputation.
- **The Multiplayer window and the character chooser drew over themselves.** Each rebuild removed its scrolling section, but the game wraps that section in a scroll panel, and the wrapper stayed. Every rebuild (a player joining, a status change) left another copy underneath. Now the wrapper is removed (`Panels.remove`).
- **Found in the log:**
  - in hyperspace, hidden NPC fleets were parked far away, so the game took each route fleet for gone and spawned a new one near the player, which was hidden in turn (1,120 fleets in 14 seconds). In hyperspace they're now hidden where they are (nobody can detect them, they don't move), despawned ones are dropped, and at most 400 are hidden;
  - cargo pods with no cargo hold at all (from an earlier session) failed every scan, and the game's own pods dialog failed on them. They're faded away, and pods are never made without a hold.
- `PROTOCOL_VERSION` 23.

## Still to do (roadmap)

Kept here so they aren't lost between test rounds:

- **NPC factions' own activity** is still rolled separately on every game: their raids, expeditions, invasions, pirate and Pathers bases, inspections, and commodity events. Fixing it means switching those managers off on friends' games and copying the host's results, one kind at a time.
- **Market ownership** (Nexerelin invasions, and any mod that moves markets) and **player colonies** aren't shared. A safe ownership change needs Nexerelin's own transfer code, to be tested once a 0.98 build of Nexerelin is installed. Colonies are larger still.
- **NPC battles a friend joins** keep fighting on the owner's game meanwhile, so both games can wear down the same fleets. Pausing the owner's copy of the battle needs a way to hold its autoresolve.
- **Joint battle view:** no objectives (so no capture orders), the joiner's ship answers one ping late, and muzzle flashes, armour damage marks and the systems the view can't switch on (teleports, drones, EMP, targeted systems, and all of mods' own system scripts) aren't shown. Showing mods' systems safely would need the leader to stream each system's effect, not just its state.
- **Friends' characters in the host's save** (agreed design, not built): every player's character stored inside the host's save at each shared save, dormant unless hosting, so a rewind rewinds everyone and a friend can recover theirs from the host.
- **Fast-forward from a friend** (`friendsCanFastForward`, off by default) moves the host's fleets and date, not the host's whole simulation; the host's own fast-forward key can't be pressed for them. A friend not holding fast-forward while the host does moves their own fleet at normal speed.
- **The updater:** not yet confirmed by a test report (0.1.2 is installed here; it isn't known whether by the button or by hand).
- **Recovery offers** after a joint battle aren't saved: a save before they're recovered or left loses them.
- **Mod updates beyond MultiSector:** the launcher can match which mods are enabled, but can't install a mod the host has and the friend doesn't.

### Pitched for 0.1.3 (agreed to note, not built)

Suggested first: 1, 2, 3, 4 and 7; then 8 (above, friends' characters in the host's save) and 5.

Protecting the campaign and catching bugs:

1. **Rolling backups of the host's save:** the launcher copies the save folder each time hosting starts, keeps the last 5, and offers a restore button.
2. **"Send my log to the host":** a launcher button sends a friend's `starsector.log` over the lobby link, saved next to the host's, so crashes on a friend's PC can be read.
3. **Mod versions, not just mod ids:** the join check compares only which mods are enabled (`GameInstall.installedMods` reads ids and names). Compare each mod's `version` from `mod_info.json` and warn on a mismatch (Nexerelin especially).
4. **Salvage locks:** two players salvaging the same derelict, cache or debris at the same moment both get the loot. Claim world objects through the same lock channel as fleets (`FleetLocks`) while their dialog is open. (Also covers a derelict left by vanilla after a joint battle being claimed as well as recovered from the leader's leftovers.)
5. **Desync check:** every minute or so each friend compares a small fingerprint with the host's (date, fleets in the system, market stock hashes, world objects). On drift, say so and offer the fresh copy. Today only a new world id (the host loading a save) prompts it.
6. **Dropout and soak tests (no game needed):** leader or joiner dropping mid-battle, a lock holder disconnecting, and an hour-long three-session run for leaks and stuck states.

Flow and fun:

7. **Rewards for helping:** loot, XP and bounty payouts from a joint battle go to the leader only. Either split credits, bounty and XP by deployed strength, or offer a "share the spoils" screen after the battle on top of `PlayerTalk`'s escrowed offers. (Ships are done in 0.1.3: joiners recover their own, and the enemy ships the leader leaves.)
8. **Map pings:** Alt+click on the map marks a spot everyone sees for 30 s, with the player's name.
9. **In-game chat:** none yet. A text line in the Multiplayer window; messages shown as notices.
10. **"While you were away":** on joining, a short summary since your last session (days passed, raids, big battles), from the world events.
11. **Story and gate state:** the main questline, gate scans and activations happen separately on each game. Needs a design pass on which become world events.

Nice to have:

12. **Network overlay** on a hotkey: ping, packet loss, messages per second, puppets and mirrored fleets.
13. **Friends' abilities on their puppets:** emergency burn, sensor burst, go dark, transponder.
14. **Friendly sparring** between players, with the joint battle on opposite sides (large).
