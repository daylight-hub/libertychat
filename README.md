# Liberty Chat

*Powered by Torlando-Tech's Columba.*

**A Liberty Communication Systems, Inc. (LCS) distribution of Columba** — A Reticulum messaging & voice app for Android (Bluetooth LE, TCP, or RNode LoRa).

Built on [Columba](https://github.com/torlando-tech/columba) by torlando-tech
(MPL 2.0). Original design and code are theirs; LCS branding and the changes
below are Liberty Communication Systems, Inc.

Full history is in [CHANGELOG.md](CHANGELOG.md).

## Features

### Messaging

- **Push-to-talk voice messages**, interoperable with
  [Sideband](https://github.com/markqvist/Sideband) in both directions.
  Clips are sent as LXMF `FIELD_AUDIO` (0x07) rather than as file attachments,
  which is what makes them arrive as voice messages rather than inert files.
  Received clips autoplay when the conversation is open, and the bubble can be
  tapped to replay.
  - **Low bandwidth** (default) — Codec2 1200, ~150 B/s. For LoRa/RNode links.
  - **High bandwidth** — Opus, ~3 KB/s. For WiFi or TCP.
  - The toggle selects the codec, not just the bitrate — the same choice
    Sideband's own high-quality PTT option makes.
- Announce button on the Chats screen, left of the search icon.

### Radio

- RNode default frequency: US **slot 51 = 914.875 MHz** (LCS standard).
- RNode default TX power is **board-aware**: **28 dBm** on a Heltec V4, **22
  dBm** on RAK, **20 dBm** on LILYGO, and 22 when the board is not identified.
  Default interface mode **Full**. Per-region defaults are still clamped to each
  band's regulatory maximum, so EU 868 stays at 14 dBm and EU 433 at 12 whatever
  the board can do. With no region selected — custom mode, or editing an
  existing interface — the ceiling is **30 dBm**.
- The board is identified from the USB descriptor or the Bluetooth name. A
  Heltec V4 drives its USB port from the ESP32-S3 directly (`303A:1001`), which
  is what the wizard looks for; a board that names itself something else — a
  LILYGO T3S3, say — keeps its own 20 dBm default. The hint under the power
  field names the board detected, and the figure is only a pre-filled default.
- **Medium Fast is the default preset** (SF9 / 250 kHz / CR 4:5), badged *Good
  range with rooftop repeaters*. LCS deployments lean on rooftop repeaters, and
  at SF9 there is enough airtime headroom for a repeater to hear, decode and
  re-transmit a frame before the next arrives — while staying faster than the
  Long presets. **Medium Slow** carries the general *Works well with repeaters*
  note for the same reason one notch slower.
- **Long Fast** badged *Good for ground radios* — radios working each other
  directly, where the extra reach is worth dropping to ~1.07 kbps.
- **Short Fast** badged *Best for voice over LoRa* — at ~10.9 kbps it is the
  slowest preset that can actually carry a live call. Neither Long Fast nor
  Medium Fast can; they are messaging presets.
- **Long Range / Turbo** (SF11 / 500 kHz / CR 4:8, ~1.34 kbps) for more
  throughput than Long Fast on a wider channel.
- **Codec2 3200** badged *Voice/PTT* in the call quality picker.
- **Mid-call codec switching.** When the pre-dial link probe says the link is
  too slow for the codec in use, the call screen offers a picker covering both
  Codec2 and Opus, plus a push-to-talk suggestion. Switching signals the peer,
  so both ends move — including Sideband and MeshChat peers.
- No vendor logo on the RNode's own screen. The *Display Logo on RNode* option
  is gone from the wizard, and the backend now disables the RNode's external
  framebuffer on every connect, so the panel keeps its own UI. Previously the
  logo could stay latched on the display — most visibly on an RNode reached over
  IP, where nothing cleared it when the app disconnected.
- Built-in RNode flasher entry removed from Settings — LCS ships pre-flashed
  hardware.

### NomadNet

- **Pages render inline images**, with a loading-mode setting (off, on demand, or
  automatic), a per-image tap to reload, and an image cache. Saved images go to
  `Pictures/Liberty Chat`.
- **Identify** to saved nodes happens at link establishment, and auto-identify is
  remembered per node rather than per session.
- **Browsing survives tab switches** — leave the NomadNet tab and come back and
  the page is still there, with no re-fetch.
- The link is retried after route discovery on a cold start, so a first visit
  doesn't fail just because no path existed yet.

### Navigation

- **The bottom bar is configurable** in Settings: choose which tabs appear and in
  what order, up to five, with Settings pinned last.
- The default bar is **Chats, Contacts, Map, NomadNet, Settings**. Upstream leaves
  NomadNet off its default bar, which hides the feature entirely on a fresh
  install — the per-announce *Browse Site* button only shows for announces from
  actual NomadNet nodes.

### Network

- No TCP bootstrap interface is seeded. A fresh install comes up on
  AutoInterface and Bluetooth LE only — nothing reaches the internet until the
  user adds a server or attaches an RNode. Upstream's Beleth RNS Hub seed is
  removed and deleted from existing installs on upgrade.
- **A failed message is retried on a rediscovered route before it is propagated.**
  The ladder is: send direct, and on failure expire the stale path and ask the
  network for a new one; if a route comes back the same message is re-sent direct;
  if none does, it goes to the propagation node; if that fails too it is reported
  failed. Direct delivery beats leaving a message on a third party's server, so
  propagation stays the fallback it is meant to be. Requests are batched per peer
  so one unreachable contact asks the network once rather than once per queued
  message, and throttled to three per peer at one a minute.
- **Path request when a message fails to deliver.** A failed delivery asks the
  network for a path to that peer instead of leaving the message dead in the
  thread. The request is forced rather than skipped when a path is already
  known — a stale path is exactly why a send to a previously-reachable peer
  fails. It hangs off the delivery failure rather than the send call, because a
  synchronous send failure means the backend never resolved the peer at all and
  has already requested a path itself; the gap is the cached-destination case,
  where nothing asks for a route. Rate-limited to one automatic request per peer
  per minute, since a path request goes out on every interface.
  The same applies to the propagation leg: a message that failed at a
  propagation node prompts a path request for the node rather than the peer. The
  ladder is send → on direct failure ask about the peer and fall back to the
  propagation node → on that failure ask about the node.
- **A network interface that appears after launch is picked up.** RNS's
  AutoInterface adopts its interfaces once at startup, so enabling WiFi after
  launch used to leave local peers invisible until a manual restart. The app now
  notices a NIC the stack cannot see and reloads just that interface, which
  re-runs its NIC enumeration while BLE, RNode and TCP links stay up; a full
  restart is the fallback for when the live reload isn't available. Ordinary
  churn — a reconnect, an AP roam, a new link-local address — is left alone,
  because RNS repairs that itself every four seconds. Either way the routes
  learned on that interface are kept: RNS drops every path whose interface has
  been detached, so a reload carries the entries over to the rebuilt interface and
  a restart flushes the path table before going down.
- **Learned routes survive a restart**, with transport node off. Upstream RNS
  gates the startup load of its saved path table on transport being enabled,
  while the save is ungated — so with transport off the table was written and
  never read back. The app performs that load itself instead, mirroring
  upstream's own loader, so transport node stays off by default and remains
  purely a choice about whether to relay other peers' traffic. The app also
  flushes the table when it goes to the background, since RNS's own persistence
  otherwise only fires on a 12-hour timer or an `atexit` handler that Android
  never runs. Does not apply when another RNS app on the device owns the stack
  as a shared instance.
- **Doze protection no longer lapses.** The wake lock that keeps the Reticulum
  service running is renewed on a timer rather than taken once with a long
  timeout. Previously it expired after ten hours and was only ever re-taken on a
  network change, so a phone left on one Wi-Fi network stopped announcing to its
  AutoInterface peers and lost them to the 22-second peering timeout.
- **Ping destination** in the chat's three-dot menu, above Request path: reports
  whether the peer answers, the round-trip time and how many hops away they are.
  Nothing is delivered to the peer — it establishes or reuses a Reticulum link
  and reads its metrics, so it never puts a message in their chat.
- **Request path** in the chat's three-dot menu, for asking on demand — when a
  repeater has just come back up, say, rather than waiting for the automatic
  attempt. A deliberate tap bypasses the rate limit.
- When adding a TCP server, the list offers the LCS Gateway, a local IP RNode,
  or a **Command Center PRO Client** (`liberty.local:4246`).
- **Command Center PRO over TCP** is offered as its own choice during onboarding
  and in Add Interface → TCP Client. It is the way to reach a Command Center
  when AutoInterface can't find it — its multicast discovery doesn't survive
  every router. Never configured as a bootstrap interface, so it stays attached
  rather than auto-detaching once other interfaces come up.

## Upstream stack

| Component | Version |
|---|---|
| Columba | 2.0.9 (fork base) |
| RNS (Python) | 1.5.5 — `torlando-tech/Reticulum`, pinned commit |
| LXMF (Python) | 1.1.0 — `torlando-tech/LXMF`, pinned commit |
| LXST | LXST-kt `v0.0.4` (Kotlin; no Python LXST) |
