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
- **Long Fast** badged *Default* for general use; **Short Fast** badged
  *Best for voice over LoRa* — at ~10.9 kbps it is the slowest preset that can
  actually carry a live call, against Long Fast's ~1.07 kbps.
- **Medium Fast** and **Medium Slow** badged *Works well with repeaters* — at
  SF9–10 on a 250 kHz channel they leave a repeater enough airtime headroom to
  hear, decode and re-transmit a frame before the next one arrives.
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

### Network

- No TCP bootstrap interface is seeded. A fresh install comes up on
  AutoInterface and Bluetooth LE only — nothing reaches the internet until the
  user adds a server or attaches an RNode. Upstream's Beleth RNS Hub seed is
  removed and deleted from existing installs on upgrade.
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
| RNS (Python) | 1.1.9 — `torlando-tech/Reticulum`, pinned commit |
| LXMF (Python) | 0.9.2 — `torlando-tech/LXMF`, pinned commit |
| LXST | LXST-kt `v0.0.4` (Kotlin; no Python LXST) |
