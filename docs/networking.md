# Connecting players

MultiSector uses a **direct connection**: the host's game listens on a port, and clients connect to the host's IP address. There's no central server or matchmaking. One player hosts and everyone else connects to them.

- **Port:** 7777, used for both TCP (reliable messages) and UDP (fast position updates).
- The host listens on **every network adapter**, so LAN, Hamachi, Tailscale and port-forwarded connections all reach the same game.
- UDP packets are capped at 1200 bytes so they fit through Hamachi and other virtual LANs without being split up.

## Playing over Hamachi

Hamachi is the main supported setup.

### One-time setup

1. **Everyone:** install Hamachi, then join the same Hamachi network. One person creates it with *Network → Create a new network*, and the others use *Join an existing network*.
2. **Host only:** open the firewall. From the MultiSector repo folder, run:
   ```
   dev firewall
   ```
   Windows will ask for admin rights. This opens TCP and UDP port 7777 on all network types. Without it, friends usually can't connect, because Windows treats the Hamachi adapter as a **Public** network and blocks incoming connections on it.

   It also removes any "block" rules Windows created for the game's Java if someone clicked *Cancel* on Windows' "allow access?" prompt. Those block rules override allow rules, and they're a common hidden cause of failed connections.

   To undo it later: `dev firewall remove`.

### Find the host's address

On the host's PC:

```
dev ip
```

```
This PC's addresses:
  25.12.34.56  (Hamachi, adapter "LogMeIn Hamachi Virtual Ethernet Adapter")
  192.168.1.20  (LAN, adapter "Wi-Fi")
  127.0.0.1  (this PC only, adapter "Software Loopback Interface 1")

Hamachi address: 25.12.34.56   <- give this to friends on your Hamachi network
```

The same address also appears in the Hamachi window, and in `starsector.log` as `[MultiSector net] Hamachi address: ...` when the game starts with the mod on.

### Test the connection before playing

This tests the exact port, protocol and packet size the mod uses, without starting the game.

Host:
```
dev nettest host
```

Friend:
```
dev nettest join 25.12.34.56
```

A working connection looks like this:

```
[ OK ] TCP handshake in 41 ms
[ OK ] UDP: 20/20 pings of 1200 bytes, round trip min 38 / avg 44 / max 61 ms

Connection works. MultiSector should be able to connect over this address.
```

If something fails, the tester prints the most likely cause. The common ones:

| Result | Usual cause | Fix |
|---|---|---|
| TCP: no answer | Host firewall, or not both online in Hamachi | Host runs `dev firewall`. Check both players show a **green** dot in Hamachi. |
| TCP: connection refused | Reached the PC, but nothing is listening | Host isn't running `dev nettest host`, or you're using different ports |
| UDP 0/20, TCP OK | Firewall allows TCP but not UDP | Host runs `dev firewall` (opens both) |
| UDP loss above 10%, or high ping | Hamachi is **relaying** the connection through its servers instead of connecting directly | Restart Hamachi on both ends. If it stays relayed, try Tailscale. |

### Hamachi limits worth knowing

- The free plan allows **5 people per network**. Larger groups need a paid plan or a second network.
- Hamachi tries to connect peers directly. When it can't, usually because of strict router NAT, it relays through LogMeIn's servers, which adds delay. The tester's ping numbers show which you got.

## Other ways to connect

| Situation | What to do |
|---|---|
| Testing alone | Run `dev nettest host`, then `dev nettest join 127.0.0.1` in a second window. For the real game, run two copies and connect to `127.0.0.1`. Each copy uses about 2 GB of RAM with the current `vmparams`. |
| Same house or LAN | Connect to the host's LAN address (`192.168.x.x`) from `dev ip`. |
| Tailscale / ZeroTier / Radmin VPN | Work the same way as Hamachi: connect to the host's address on that network. `dev ip` labels them. Tailscale is free for larger groups and usually has lower ping. |
| Port forwarding | Host forwards TCP and UDP 7777 on their router to their PC's LAN address. Clients connect to the host's public IP. |

## Ping

The design assumes a ~100 ms input buffer (see [design.md](design.md), section 5). Players in the same region on a direct Hamachi connection should be well under that.
