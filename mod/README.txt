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
    1. "Host a game" tab: pick the save and click "Host and launch".
    2. Click Continue on the game's main menu. The game starts hosting by
       itself. Send friends the address the launcher shows (25.x.x.x), and
       keep the launcher open while they download.

  Friends:
    1. "Join a game" tab: type the host's address, click "Join and launch".
       It switches your mods to the host's if they differ ("Put my mods
       back" undoes it), and offers the host's MultiSector build if yours
       differs. It also says whether the host's game is ready.
    2. Click Continue on the game's main menu.
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
  - Anyone can fast-forward: the world speeds up for everyone.
  - While everyone is in a battle, dialog or menu, the world waits.
  - In the cargo, refit or map screen your fleet keeps flying its course
    (F8 switches it to stay put).
  - Load before the host and your game waits, paused, until it can join.
    When the host ends the session, you're saved and sent to the menu,
    and the launcher fetches their world again once they're back.
  - Next time you join a world, you're back as your character from last
    time. If your copy doesn't match the host's, the game offers a fresh
    copy (one click; the launcher downloads it). F8 has it too.
    When the host saves, everyone saves.
  - When you download, the host's game saves a fresh copy, and everyone
    waits (paused) until you're in. Unpause to stop waiting.
  - Where players are together, everyone sees the same NPC fleets: in a
    star system those of whoever got there first, in hyperspace those of
    the nearby player who has been there longest. You can fight them.
  - Markets are shared through the host. Battle wreckage, cargo pods and
    objectives (comm relays, sensor arrays...) are shared everywhere.
  Players far apart in hyperspace each have their own hyperspace fleets.

  Click another player's fleet to hail or escort them (escort follows
  them closely, through jump points too). A hail can be answered or declined.
  While you talk, both fleets hold still. You can offer each other
  credits, cargo and ships; what you offer comes back if they say no.

  To help a friend in a battle, fly to the fight and click the enemy
  fleet (or theirs). You can join once the fighting has begun, and your
  ships arrive as reinforcements. A live view of the battle opens
  and you fly one of your own ships: WASD move, Q/E
  strafe, mouse aims, left button fires group 1-7, right button shields,
  F system, V vent. Your other ships fight beside theirs under the battle
  AI. Losses and damage are applied to your fleet afterwards. Battles
  between NPC fleets can be joined too: click one of the fleets.

  Shared by everyone: raids and bombardments, planet surveys and explored
  ruins, wars between factions, and faction bounties on star systems.
  Lose your whole fleet and you can be picked up next to another player.

  UPDATING: the launcher's "Update MultiSector" button installs the newest
  build. With the game open, it updates once you save and quit, then hosts
  your save (or joins your host) again. Your multisector.json is kept.
  Your saves work with every MultiSector build: no new game needed.

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
    syncNpcFleets: show the host's NPC fleets in shared systems (default on)
    The file explains every other setting (time, saving, what's shared).
    showPlayersHud, hudHotkey, hudCorner, hudOffsetX/Y: the players panel

UNINSTALL
  Saves made with the mod contain its Multiplayer button. Back up saves
  before removing the mod. "tools\firewall.cmd remove" removes the
  firewall rules.

Source and issues: https://github.com/bluenerd8/MultiSector
