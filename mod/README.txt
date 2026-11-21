MultiSector - experimental multiplayer for Starsector
====================================================

Requires Starsector 0.98.5a. Everyone needs the same MultiSector version.

INSTALL
  Unzip so the folder ends up at  Starsector\mods\MultiSector
  (this file should be at Starsector\mods\MultiSector\README.txt).
  Enable "MultiSector" in the launcher's Mods list.

PLAYING OVER HAMACHI
  1. Everyone joins the same Hamachi network.
  2. HOST ONLY, once: double-click  tools\firewall.cmd  and allow admin.
     Without it, Windows usually blocks friends from connecting, because
     it treats Hamachi as a "Public" network.
  3. Host: in the campaign press F8 (or click the Multiplayer button on
     the ability bar) and click "Host game". Copy the 25.x.x.x address
     it shows and send it to your friends.
  4. Friends: press F8, paste the host's address, click "Join".

  Right now players can connect and see each other with ping. The game
  itself isn't synced yet, so everyone still plays their own campaign.

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
