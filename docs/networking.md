# Connecting players

MultiSector uses a **direct connection**: the host's game opens a port, and clients connect to the host's IP address. There is no central server or matchmaking. One player hosts and everyone else connects to them.

The default port is planned as **7777**, used for both TCP (reliable messages) and UDP (fast position updates). It will be configurable.

## Ways to connect

| Situation | What to do |
|---|---|
| Testing alone | Run two copies of the game on one PC and connect to `127.0.0.1` (localhost). Each copy uses about 2 GB of RAM with the current `vmparams`. |
| Same house or LAN | Connect to the host's local IP, e.g. `192.168.1.20`. Nothing else needed. |
| Over the internet, easy | Everyone joins the same **virtual LAN** and connects to the host's virtual IP. |
| Over the internet, no extra software | The host **port-forwards** 7777 (TCP + UDP) on their router, and clients connect to the host's public IP. |

## Virtual LAN options

These all make the friends' PCs look like they're on the same LAN. Any of them works, and the mod doesn't need to know which one you use.

| Tool | Notes |
|---|---|
| **Tailscale** | Recommended. Free for small groups, quick to set up, usually connects peer-to-peer so ping stays low. |
| **ZeroTier** | Similar to Tailscale, free tier, slightly more setup. |
| **Radmin VPN** | Free, Windows-only, very simple, popular for LAN games. |
| **Hamachi** | Works, but the free tier caps a network at 5 people and it tends to have higher latency. |

Hamachi is fine if your group already has it set up. You don't need it.

## Windows Firewall

The first time the host starts hosting, Windows may ask whether to allow Java through the firewall. Allow it, at least for private networks. Virtual LAN adapters are sometimes classed as "public", in which case allow public too, or change that adapter's network profile to private.

## Ping

The design assumes a ~100 ms input buffer (see [design.md](design.md), section 5). Friends in the same region over Tailscale or a direct connection should be well under that. Players on different continents will feel more delay.
