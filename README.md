# MultiSector

Experimental multiplayer mod for [Starsector](https://fractalsoftworks.com/), aimed at small friend groups (2–8 players). One player hosts and runs the real game; everyone else connects to them.

> **Status: early prototype.** Players share the host's world. Everyone sees the other players' fleets (with all their ships) on the map. Everyone stays on the host's date, pause is shared, and the host's fast-forward is everyone's. Planets line up. Where players are together, everyone sees the same NPC fleets: in a star system those of whoever got there first, and in hyperspace those of the nearby player who has been there longest. You can fight them, and the results reach that player's world. Battle wreckage, cargo pods and objectives (comm relays, sensor arrays and the like) are shared everywhere. Markets are shared everywhere: stock, purchases and price changes go through the host. Players can hail each other's fleets to talk and trade credits, cargo and ships. Players far apart in hyperspace each have their own hyperspace fleets.

## Time and pause

- **Pause** on the map pauses everyone, and anyone can resume. Your own menus, markets and dialogs only pause your game. The shared clock keeps going, and your date catches up when you close them.
- **Ctrl+Space** pauses or resumes everyone from inside a dialog, market or menu, where the normal pause key doesn't reach.
- **Fast-forward:** the host's. When the host fast-forwards, the world speeds up for everyone, and every game says so (the players panel, and a message). Hold fast-forward too to move at the host's speed; if you don't, your date and the planets around you still follow the host's smoothly. In a menu, the host's fast-forward goes on only while they keep holding the key. `friendsCanFastForward` lets friends speed the world up as well, but that looks jumpy on the host's game.
- **Nobody on the map:** while everyone is in a battle, dialog or menu, the world waits instead of running on (`holdWhileEveryoneBusy`).
- **Menus:** while you're in the cargo, refit, character or map screen, the world keeps going for you at its own speed, fast-forward included: your date keeps up, and your fleet keeps flying, following a course you plot on the map screen or the player you escort. Switch it to stay put in the Multiplayer window (F8), or with `keepMovingInMenus`.
- **Joining:** when you click Download, the host's game saves a fresh copy for you. By default everyone waits, paused, until you're in the game, so you start in exactly the world you downloaded. The host (or anyone) can unpause to stop waiting. Turn this off with `holdWhileJoining` in `data/config/multisector.json`.
- If your save is older or newer than the host's world, your date is set to the host's when you connect.

## One world, kept together

- **The host comes first.** A friend's world that isn't connected to the host stays paused and keeps trying to join, so loading before the host is fine. F8 lets you stop waiting.
- **When the host ends the session** (stops hosting or quits), friends' characters and worlds are saved and they go back to the main menu. The launcher then waits for the host and downloads their world as soon as they're back: just click Continue.
- **Coming back as yourself:** your character in each shared world is saved separately (your own export is never overwritten): skills, portrait, officers and AI cores commanding your ships, fleet, cargo, your standing with the factions, and where you were. Next time you join that world, you're back as that character, where you left off, without choosing.
- **Always the host's world:** if your copy doesn't match the host's (they loaded a save since), the game offers a fresh copy. One click, and the launcher fetches it; F8 has the same button.
- **Saving:** when the host saves, everyone saves. Friends don't autosave while connected, and a friend's own save asks the host to save the world. The host can also have the world wait after loading until everyone from the last session is back (`waitForAllPlayers`).
- **Shared:** raids and bombardments on planets, planet surveys and explored ruins, wars and peace between factions, and faction bounties on star systems. Your reputation, missions and bar events stay your own.
- **Losing your whole fleet:** get picked up next to another player, or at the nearest friendly port.

## Seeing the other players

- A **players panel** in the top-right of the campaign map lists everyone else: where they are, what they're doing (travelling, at a market, in the refit screen, fighting a fleet), and ping. **F7** hides or shows it.
- The **Multiplayer window (F8)** shows more for each player: distance from you, fleet size and flagship, level and credits.
- On the map, every other player in your system is **ringed in their colour and marked with their own shape** (double ring, diamond, square, triangle, hexagon or star; their pings carry it too) with their name and distance, and an **arrow at the screen's edge** points at anyone off screen.
- **Choose your own mark** in the players window (F6): ten colours and six shapes. Everyone sees you, your pings and your ships in battles with it.
- The **players window (F6)** lists everyone wherever they are ("Corvus, near Jangala"), with **Go to** (lays in a course, through jump points too), **Escort**, and **Ping their spot**.
- **On the sector, system and hyperspace maps** (and in the intel screen under "Players"), every other player shows on their fleet with their name, colour and shape, and pings show on their spot.
- **Map pings:** Alt+click on the map marks a spot for everyone for 30 seconds (one every 2 seconds, 3 at a time per player). Players elsewhere are told where, and F6 has a course there.
- **Enemies go after every player**, not only the one whose world the system's fleets are from: a hostile fleet that sees you comes after you, and catching you opens the encounter on your game.
- **Chat:** press **Enter** on the map, type, Enter sends to everyone. Lines show in the game's message list, in battles too.
- Move or turn off the panel with `showPlayersHud`, `hudHotkey`, `hudCorner`, `hudOffsetX` and `hudOffsetY` in `data/config/multisector.json`; `playersHotkey` and `chatHotkey` change the other two keys.

## Talking and trading with other players

- **Click another player's fleet** to hail or escort them. A hail opens a dialog on their game to answer or decline, at once if they're on the map, or as soon as they finish what they're doing. Either of you can cancel or leave at any time.
- **Escort** follows their fleet closely, through jump points too, with no dialog on their side. Click anywhere on the map to stop.
- While you talk, both of you are in a dialog, so both fleets hold still. The world keeps going for everyone else; press Ctrl+Space if you want to pause everyone.
- **Salvage is one player's at a time:** opening a derelict, debris field, cargo pods or cache that another player is salvaging tells you they're on it, so nothing gets taken twice.
- **Offer credits, cargo or ships.** What you offer leaves your fleet straight away and comes back if they turn it down, leave the conversation or leave the game. Officers stay with you, and you can't give away your flagship.

## Fighting together

- Nobody is pulled into a battle. To help a friend who's fighting, fly to the fight and click the enemy fleet (or your friend's fleet). You can join once the fighting has begun; until then the dialog tells you to wait.
- With three or more players, clicking **anyone** fighting in a battle (not only the one who started it) offers to join it.
- Battles between NPC fleets (a patrol fighting pirates, say) can be joined too, even in a world you follow: click one of the fleets and pick a side.
- Your ships arrive as reinforcements at your friend's side of the battlefield, and they get a message in combat. A live view of the battle opens on your game: you **fly one of your own ships** (your flagship unless you pick another), and the rest of your combat-ready ships fight under the battle AI. Your friend can't pilot, recover or keep your ships.
- Controls in the view are your own game's, with your own key bindings: moving, strafing, the strafe key that turns your ship to the mouse, decelerating, autopilot, autofire for each weapon group (Ctrl+1-7 by default), hold fire, selecting a group, firing, shields, system, venting, fighters and targeting.
- **Leave the battle** at any point, even while waiting for it to start: your ships pull out, and what happened to them still comes back when it ends.
- When the battle is over, their losses, hull damage and readiness are applied to their real fleet. Nobody is left without a ship. Your own lost ships can be recovered right after, on the game's usual recovery screen (the usual chances, d-mods and story-point recoveries). Once the battle's leader has taken their pick, the enemy ships they left can be recovered by everyone who fought with them.
- The view follows the battle's combat speed, so a speed-up mod (2x combat speed and the like) on the battle's game shows at the same speed. Only the battle's leader sets it, and both sides are told when it changes.
- Not yet: loot and experience go to the battle's leader. `jointBattleView` and `jointBattleDeployAll` in `data/config/multisector.json` turn the view and full deployment off.

How time sync works: [docs/design.md, section 13](docs/design.md#13-shared-time-pause-and-joining).

## Download

Get the latest zip from [Releases](https://github.com/bluenerd8/MultiSector/releases) and unzip it into `Starsector\mods`, so you end up with `Starsector\mods\MultiSector`. The zip includes `README.txt` with setup steps, plus two double-click tools in `tools\`:

- `firewall.cmd`: the host runs it once so friends can connect
- `connection-test.cmd`: checks the connection between two players

## The launcher

`MultiSector Launcher.cmd` in the mod folder opens a window outside the game that handles everything before playing:

- **Host:** pick a save and click **Host and launch**. It shares the save, makes it the one Continue loads, and starts Starsector. Your Hamachi address has a copy button.
- **Join:** type the host's address and click **Join and launch**. It switches your enabled mods to the host's if they differ (**Put my mods back** undoes it), downloads their world and starts Starsector. It says whether the host's game is ready, and what to fix if it can't be reached.
- The launcher ticks MultiSector in the game's mod list for you, and the Host tab checks Windows Firewall (with a button to open it).
- When the save loads, the mod hosts or joins by itself.
- It checks **mod versions**, not just which mods: a different build of the same mod (Nexerelin, say) is named before you join, and the game says so again when you connect.
- **Logs are shared:** while you play, a friend's launcher sends the useful part of their game log (MultiSector's lines, warnings, errors, crashes) to the host's launcher every few minutes, at once after a crash, and when the game closes; the host's comes back. Both end up in `Starsector/multisector_logs`.
- **Any save works** with any MultiSector build: there's no need to start a new game for each test build. The Host tab shows which build last saved each save.
- **Update MultiSector** installs the newest test build from GitHub. It works with Starsector open (it updates once you quit), then carries on hosting your save or joining your host. If the host has a different build, the launcher offers theirs. It keeps your `multisector.json` settings.

The host's save is the shared world, so the map never has to be synced piece by piece. The launcher shares it on port 7778, next to the game's 7777. Developers can open it with `dev launcher`.

## In the game

Press **F8** in the campaign, or click the **Multiplayer** button the mod adds to your ability bar. The window lets you:

- **Host game:** shows your Hamachi address, with a button to copy it
- **Join:** type or paste the host's address, then click Join
- see the **player list** with each player's ping

Change the hotkey or port in `mods/MultiSector/data/config/multisector.json`.

- **Game version:** Starsector 0.98.5a-RC8 (Java 17)
- **Dependencies:** none yet. LazyLib will be added later.

## Building

You only need Starsector installed. The build uses the Java 17 JDK bundled in the game's `jre` folder, so there's no separate Java, Gradle or npm to install.

```
dev build      compile src/ into mod/jars/MultiSector.jar
dev install    build, then copy the mod into <game>\mods\MultiSector
dev run        install, then start Starsector
dev log        show MultiSector lines from the game log
dev package    build, then zip the mod into dist/ for friends
dev clean      delete build/, dist/ and mod/jars/

dev ip                        show this PC's addresses (finds your Hamachi IP)
dev nettest host [port]       run a connection test host
dev nettest join <ip> [port]  test the connection to a host
dev firewall [port]           open port 7777 in Windows Firewall (asks for admin)
dev firewall remove           remove those firewall rules
```

Run these from the repo root in Command Prompt or PowerShell. In PowerShell, type `.\dev build`.

The game folder defaults to `D:\Starsector`. If yours is elsewhere, set the `STARSECTOR_DIR` environment variable:

```
setx STARSECTOR_DIR "E:\Games\Starsector"
```

## Playing together

The host's game listens on port 7777, and other players connect to the host's IP. **Hamachi** is the main supported setup. LAN, Tailscale and port forwarding also work.

Quick version for Hamachi:

1. Everyone joins the same Hamachi network.
2. Host: run `dev firewall` once, then press F8 in the game and click **Host game**. The window shows the `25.x.x.x` address to give friends.
3. Friends: press F8, paste the host's address, and click **Join**.

If joining fails, `dev nettest join 25.x.x.x` gives a more detailed diagnosis. It works against a host in the game too.

Full guide and troubleshooting: [docs/networking.md](docs/networking.md).

## Repo layout

```
dev.cmd                 task runner entry point
tools/dev.ps1           task implementations
tools/make_icon.py      draws the ability-bar icon
mod/                    the mod folder exactly as it ships
  data/config/multisector.json   hotkey and port settings
  data/campaign/abilities.csv    the Multiplayer ability-bar button
src/multisector/        Java sources
  MultiSectorModPlugin.java   mod entry point
  probe/                      sandbox probe
  net/                        networking: session (host/join), protocol, address detection, tester
  ui/                         Multiplayer window, hotkey, ability button, auto-connect, messages
  launcher/                   the external launcher window (runs outside the game)
docs/
  design.md             architecture and design notes
  networking.md         how players connect
```

## Docs

- [Design notes](docs/design.md): architecture, sync model, timing, roadmap
- [Networking](docs/networking.md): how players connect
