# MultiSector

Experimental multiplayer mod for [Starsector](https://fractalsoftworks.com/), aimed at small friend groups (2–8 players). One player hosts and runs the real game; everyone else connects to them.

> **Status: early prototype.** The mod currently only runs a sandbox probe that checks which Java APIs Starsector lets mods use. There's no multiplayer yet.

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
2. Host: `dev firewall` (once), then `dev ip` to get their `25.x.x.x` address.
3. Check the connection before playing. The host runs `dev nettest host`, and the friend runs `dev nettest join 25.x.x.x`.

Full guide and troubleshooting: [docs/networking.md](docs/networking.md).

## Repo layout

```
dev.cmd                 task runner entry point
tools/dev.ps1           task implementations
mod/                    the mod folder exactly as it ships (mod_info.json, data, jars/)
src/multisector/        Java sources
  MultiSectorModPlugin.java   mod entry point
  probe/                      sandbox probe
  net/                        networking: config, address detection, framing, connection tester
docs/
  design.md             architecture and design notes
  networking.md         how players connect
```

## Docs

- [Design notes](docs/design.md): architecture, sync model, timing, roadmap
- [Networking](docs/networking.md): how players connect
