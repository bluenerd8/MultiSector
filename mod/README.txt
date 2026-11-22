MultiSector - experimental multiplayer for Starsector
====================================================

Requires Starsector 0.98.5a. Everyone needs the same MultiSector version.

INSTALL
  Unzip so the folder ends up at  Starsector\mods\MultiSector
  (this file should be at Starsector\mods\MultiSector\README.txt).
  Enable "MultiSector" in the launcher's Mods list.

PLAYING OVER HAMACHI
  Everyone joins the same Hamachi network first.

  HOST ONLY, once: double-click  tools\firewall.cmd  and allow admin.
  Without it, Windows usually blocks friends from connecting, because it
  treats Hamachi as a "Public" network.

  Then use the launcher: double-click  "MultiSector Launcher.cmd"
  (keep a shortcut to it on your desktop).

  Host:
    1. In the game, save. Then in the launcher, "Host a game" tab:
       pick that save and click "Start hosting".
    2. Send friends the address the launcher shows (25.x.x.x).
    3. Click "Launch Starsector" and load that save. The game starts
       hosting by itself. Keep the launcher open while friends download.

  Friends:
    1. "Join a game" tab: type the host's address, click "Check host".
       It warns you if your mods don't match the host's.
    2. Click "Download save", then "Launch Starsector", then Continue on
       the main menu.
    3. The game asks you to choose your character: your exported one (see
       below) or a new one (name, portrait, starting fleet). Then it joins
       the host by itself. The host's world stays as it is; only the
       character and fleet in your copy are yours.

  BRINGING YOUR OWN CHARACTER
    Load one of your own saves, press F8 and click "Export character".
    Your character, ships (with their loadouts), officers and cargo are
    saved, ready to pick when you join a world. You can rename it there.
    Colonies, reputation and story progress stay in your own save.

  In the game, F8 (or the Multiplayer button on the ability bar) shows
  the connection and players, and lets you host or join by hand.

  Everyone plays in the host's world, sees the other players' fleets on
  the map, and stays on the host's date:
  - Pause on the map pauses everyone. Anyone can unpause.
  - Your own menus and markets only pause your game; your date catches
    up when you close them.
  - Fast-forward follows the host. Turn yours on when they do.
  - When you download, the host's game saves a fresh copy, and everyone
    waits (paused) until you're in. Unpause to stop waiting.
  NPC fleets, markets and battles aren't synced yet.

  The players panel (top right of the map, F7 hides it) shows where the
  others are and what they're doing. F8 shows their fleet, level and
  credits too.

IF JOINING FAILS
  Double-click  tools\connection-test.cmd  on both PCs. The host presses
  Enter to host a test. The friend types the host's address. It prints
  the most likely cause: firewall, Hamachi offline, wrong address, or a
  slow relayed Hamachi connection.

SETTINGS
  data\config\multisector.json : hotkey (default F8) and port (default 7777)
    holdWhileJoining: pause everyone while a friend joins (default on)
    showPlayersHud, hudHotkey, hudCorner, hudOffsetX/Y: the players panel

UNINSTALL
  Saves made with the mod contain its Multiplayer button. Back up saves
  before removing the mod. "tools\firewall.cmd remove" removes the
  firewall rules.

Source and issues: https://github.com/bluenerd8/MultiSector
