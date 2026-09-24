# Liberty Chat

*Powered by Torlando-Tech's Columba.*

**A Liberty Communication Systems, Inc. (LCS) distribution of Columba** — native
Android messaging & voice over LXMF / Reticulum (Bluetooth LE, TCP, or RNode LoRa).

Built on [Columba](https://github.com/torlando-tech/columba) by torlando-tech
(MPL 2.0). Original design and code are theirs; LCS branding and the changes
below are Liberty Communication Systems, Inc.

**Current release: v1.2.1** (forked from Columba 2.0.9). The release workflow
derives the version from the tag, so tagging the build commit `v1.2.1` is what
makes the in-app version and APK filenames read `1.2.1`.

Full history is in [CHANGELOG.md](CHANGELOG.md).

## What LCS changes

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
- **Long Range / Turbo** (SF11 / 500 kHz / CR 4:8, ~1.34 kbps) for more
  throughput than Long Fast on a wider channel.
- **Codec2 3200** badged *Voice/PTT* in the call quality picker.
- **Mid-call codec switching.** When the pre-dial link probe says the link is
  too slow for the codec in use, the call screen offers a picker covering both
  Codec2 and Opus, plus a push-to-talk suggestion. Switching signals the peer,
  so both ends move — including Sideband and MeshChat peers.
- *Display Logo on RNode* option removed from the wizard.
- Built-in RNode flasher entry removed from Settings — LCS ships pre-flashed
  hardware.

### Network

- No TCP bootstrap interface is seeded. A fresh install comes up on
  AutoInterface and Bluetooth LE only — nothing reaches the internet until the
  user adds a server or attaches an RNode. Upstream's Beleth RNS Hub seed is
  removed and deleted from existing installs on upgrade.
- When adding a TCP server, the list offers LCS infrastructure:
  **public.lcs.network:4245** and **iprnode.local:4545**, plus Custom.

### Identity

- Renamed to **Liberty Chat** throughout, with the LCS logo across launcher
  densities and a "Liberty Chat — powered by Columba" splash wordmark that
  renders correctly on every supported Android version.
- Liberty theme: navy / gold / silver.
- **Buy RNode Radios** call-to-action in Settings → About, linking to
  `www.lcs.network`.
- Share-APK serves `liberty-chat-<version>.apk`.
- Update checks target `daylight-hub/columba`, so "Check for Updates" and
  "View Release" track LCS builds.
- Removed: *Report Bug*, the GitHub / Report an Issue / About Reticulum links,
  and the crash-reporting opt-in popup.
- About credits torlando-tech (original) and LCS (distribution); MPL 2.0
  retained.

### Build

- **Build Liberty Chat APKs** Actions workflow — one-click build of the four
  no-Sentry `official-rns-py` APKs.

## Upstream stack

| Component | Version |
|---|---|
| Columba | 2.0.9 (fork base) |
| RNS (Python) | 1.1.9 — `torlando-tech/Reticulum`, pinned commit |
| LXMF (Python) | 0.9.2 — `torlando-tech/LXMF`, pinned commit |
| LXST | LXST-kt `v0.0.4` (Kotlin; no Python LXST) |

The `kotlinBackend` flavor uses reticulum-kt `v0.0.21` and LXMF-kt `v0.0.13`
instead. LCS ships the `pythonBackend` flavor.

See `APPLY.md` for how this branch was produced and the items that need your input.
