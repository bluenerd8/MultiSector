# Joint battle protocol

How a player joins another player's battle: who owns which state, and which messages go when. The design notes behind it are in [design.md](design.md), sections 19 and 20. The code is in `src/multisector/sync/`: `JointBattle` (campaign side), `JointCombat` (combat side), `JointCombatHook` (starts the leader side in combat).

## Roles

- **Leader:** the player whose fleet is in the campaign battle. **The battle runs only on the leader's game.**
- **Joiner:** a player who joins it. Their game shows a **view** of the leader's battle (`JointCombat.Mirror`). Nothing there counts: view ships can't take damage, and every change comes from the leader.
- **Host:** relays the messages between the two games, like all player-to-player traffic. It plays no other part unless it's the leader or a joiner.

## Who owns what

| State | Owner | Everyone else |
|---|---|---|
| The campaign battle (sides, fleets, outcome, loot) | Leader's game, untouched by the mod | Never changed. Joiners' ships are never part of it. |
| Every ship in the combat: position, hull, flux, CR, shields, systems, weapons, wrecked or not | Leader's game | The view copies it from the stream and never decides any of it itself. |
| Side (`owner` 0/1) and ally flag of each ship | Leader's game, sent once per ship in `cship` | The view sets both before spawning and must match. A mismatch shows in the view check. |
| Which ships a joiner brings, and their loadouts | Joiner's real fleet, sent once in `join` | The leader builds an exact copy in a separate fleet that's never in any location or battle. |
| Whose ship it is (`multisector_player` tag) | Leader sets it on each joiner ship it spawns | Sent as `player` in `cship`. The view tags its ships the same way. |
| Controls of the piloted ship | Joiner (input datagrams) | The leader's `RemotePilot` applies them. Without fresh input for 1.5 s, the ship's default AI flies it. |
| Orders for joiner ships | Leader's side: they aren't AI allies, so the leader's tactical orders apply | None yet from the joiner. |
| What happened to the joiner's ships | Leader's game, sent in `result` | The joiner applies it to their real fleet. |
| Combat speed and pause | Leader's game, in every state datagram | The view follows both. |

Invariants:
- Each joiner ship exists once in the leader's combat. A second copy, or a ship of a player's stand-in fleet, is removed within 0.5 s.
- A player's stand-in fleet is never in a campaign battle. If it ever is, it's taken out immediately.
- The view shows only ships the leader announced. Anything else is removed.
- Only the leader's combat runs the leader side: never a simulation, a mission or a view (`JointCombat.isViewing`).

## Transport

- **Battle messages:** JSON over TCP, sent player to player on the battle channel (`CHANNEL_BATTLE`) and relayed by the host. Each message carries `k` (its kind) and `b` (the leader's battle id). `b` = 0 on a join request means "whatever you're fighting".
- **Combat datagrams:** UDP, relayed by the host (`COMBAT_UDP`). The first byte says which kind:
  - `STATE` (1), leader to joiner, 20 per second: every ship's state, plus combat speed and pause.
  - `INPUT` (2), joiner to leader, about 30 per second: the piloted ship's controls.
- **Two independent channels:** the two channels aren't ordered with respect to each other. TCP messages arrive in order. Datagrams may be lost or reordered, and each one is a complete snapshot of the ships in it.

## Sequence

```
Leader                                       Joiner
------                                       ------
opens battle dialog
  fight {live:false}  every 2 s ───────────► "not fighting yet" (no Join option)
starts combat (JointCombatHook → Leader)
  fight {live:true, ready:false} every 2 s ─► "deploying" (Join available)
                                         ◄── join {fleet, pilot}   (clicked Join)
  wait ────────────────────────────────────► "your ships go in once theirs are out"
leader's ships out, combat clock running
  fight {live:true, ready:true}  every 2 s ─►
  joined ──────────────────────────────────►
  cstart {w, h} ───────────────────────────► opens the view (MirrorCreation)
  cship × every ship already in the fight ─► spawns each one on its side, damage-immune, idle AI
  STATE datagrams, 20/s ═══════════════════► places every ship from the stream
                                         ◄── cwho {ids}  (stream mentions ships it doesn't have; ≤ 3 times each)
  cship × those ───────────────────────────►
                                         ◄── cdeploy  (every ship placed from the stream: 1.5 s ≤ t ≤ 8 s)
spawns the joiner's ships, tagged, not allies
("X joins your battle with N ships")
  cship × the joiner's ships ──────────────► spawns them as its own; the piloted one becomes its player ship
                                         ◄══ INPUT datagrams, ~30/s
  status {ships} every 1 s ────────────────► (report, used without the view)
                                         ◄── retreat  (optional)
combat ends
  cend ────────────────────────────────────► closes the view
  result {lost, ships} ────────────────────► applies losses, hull and CR to the real fleet
  fight {live:false} / fightover ──────────►
```

The other messages:

| Message | Direction | When |
|---|---|---|
| `seen` | joiner → leader | The join dialog opened. Shown to the leader only in combat. |
| `notyet` | leader → joiner | A join arrived before combat started, or combat ended before the joiner got in. |
| `nobattle` | leader → joiner | A join for a battle the leader isn't in any more. |
| `failed` | leader → joiner | The joiner's fleet couldn't be rebuilt on the leader's game (missing mod ships). |
| `cgone` | leader → joiner | A ship left the combat. |
| `left` | relay → everyone | A player disconnected. Their battles end for the others. If it was a joiner, their ships retreat. |

Fallbacks:
- If `cdeploy` never comes, the leader brings the joiner's ships in after 25 s.
- If the leader has deployed nothing, joiners are let in after 30 s of combat time.
- The view closes after 20 s with no datagram.

## Checking a battle

With `"jointBattleChecks": true` (the default, in `data/config/multisector.json`), both games write a table of every ship to `starsector.log` every 5 seconds. Ship ids are the leader's, so lines with the same id are the same ship:

```
[MultiSector] battle check, t=42s, 18 ships (id hull owner ally player CR hull state ai):     ← leader
  7 hound o=1 a=0 p=-1 cr=70 h=100 ok BasicShipAI
[MultiSector] view check, 18 ships (0 without updates, 0 streamed but unknown) (...):         ← joiner
  7 hound o=1 a=0 p=-1 cr=70 h=100 ok Idle
```

On the joiner's game, `view differs from the leader's battle` lists each ship whose side, ally flag, wreck state, CR (more than 10% apart) or hull (more than 15% apart) doesn't match the leader. Other lines to look for:
- `view can't make` or `couldn't place`: a ship the view couldn't build or spawn.
- `removing a ship the view didn't make`: a ship that appeared in the view without being announced.
- `taking … out of the battle`: the leader's duplicate or stand-in guard removed a ship.
