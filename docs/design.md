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

### Hidden fleets

The friend's own extra fleets in a shared system were only made undetectable. Next to a planet the game could still show them, which looked like fleets spawning around a docked friend. They're now also parked 150,000 units out (orbit detached), and put back exactly where they were afterwards. The first 30 hidden per shared stretch are named in the log ("hiding this game's own ...").

### Still to do

- Markets: show the host's stock on open, and send purchases and sales back.
- What a friend gains from battles (loot, XP, reputation) stays on their own game. Reputation is per player anyway: each friend has their own standing.
- Hyperspace sharing.
