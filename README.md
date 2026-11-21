# MultiSector

Experimental multiplayer mod for [Starsector](https://fractalsoftworks.com/), aimed at small friend groups (2–8 players). One player hosts and runs the real game; everyone else connects to them.

> **Status: early prototype.** Players can host and join from inside the game and see each other in a player list with ping. Game state isn't synced yet, so everyone still plays their own campaign.

## Download

Get the latest zip from [Releases](https://github.com/bluenerd8/MultiSector/releases) and unzip it into `Starsector\mods`, so you end up with `Starsector\mods\MultiSector`. The zip includes `README.txt` with setup steps, plus two double-click tools in `tools\`:

- `firewall.cmd`: the host runs it once so friends can connect
- `connection-test.cmd`: checks the connection between two players

## The launcher

`MultiSector Launcher.cmd` in the mod folder opens a window outside the game that handles everything before playing:

- **Host:** pick a save and share it, and see your Hamachi address with a copy button
- **Join:** type the host's address, check your mods match theirs, download their save
- **Launch Starsector.** When the save loads, the mod hosts or joins by itself.

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
