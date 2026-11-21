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
       the main menu. The game joins the host by itself.

  In the game, F8 (or the Multiplayer button on the ability bar) shows
  the connection and players, and lets you host or join by hand.

  Right now everyone loads the same world and sees each other connected
  with ping. Fleets and events aren't synced live yet.

IF JOINING FAILS
  Double-click  tools\connection-test.cmd  on both PCs. The host presses
  Enter to host a test. The friend types the host's address. It prints
  the most likely cause: firewall, Hamachi offline, wrong address, or a
  slow relayed Hamachi connection.

SETTINGS
  data\config\multisector.json : hotkey (default F8) and port (default 7777)

UNINSTALL
  Saves made with the mod contain its Multiplayer button. Back up saves
  before removing the mod. "tools\firewall.cmd remove" removes the
  firewall rules.

Source and issues: https://github.com/bluenerd8/MultiSector
