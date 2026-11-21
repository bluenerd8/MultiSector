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
- **Connection model.** Direct connection to the host's IP and port. See [networking.md](networking.md).
