# Liberty Chat

**Private messaging, voice messages and voice calls for Android, with or without the internet.**

Liberty Chat is made by Liberty Communication Systems, Inc. (LCS). It runs on
Reticulum, an encrypted self-configuring mesh networking stack, so messages travel over whatever
link is available: LCS Global, Bluetooth LE between nearby phones, local Wi-Fi, or long-range LoRa radios (RNode).

No phone number, email address or account is needed.

---

## How it works

1. **Your identity is a key pair made on your phone.** Liberty Chat creates it
   the first time you open the app. Your address is derived from it, and your
   private key never leaves the phone.
2. **Every message is encrypted for one person before it leaves.** Liberty Chat
   uses Reticulum's encryption: X25519 key exchange, Ed25519 signatures, and
   AES-256 authenticated with HMAC-SHA256. Calls and file transfers use fresh
   session keys (forward secrecy).
3. **Messages find their own way.** Liberty Chat sends over every interface you
   have turned on (Bluetooth LE, Wi-Fi, TCP, RNode LoRa) and repeaters relay
   them. If the other person can't be reached right away, the message is retried
   on a fresh route and, if needed, held by a propagation node until they come
   back online.
4. **LCS Global connects you to the wider Liberty network.** Local Wi-Fi,
   Bluetooth and LoRa reach people near you. LCS Global has a free 7-day trial with an optional paid
   service that links Liberty Chat to the Liberty network over Wi-Fi or mobile
   data, so you can reach people who are out of radio range. It stores messages for offline users until they return online. It only carries
   encrypted packets and can't read them.

## Works with LCS MeshChat

Liberty Chat and LCS MeshChat speak the same protocols (Reticulum for transport,
LXMF for messages, LXST for voice), so their users talk to each other directly,
with no bridge or conversion in between:

- Text messages, pictures and file attachments, both ways
- Replies and reactions
- Voice calls, including switching codecs mid-call
- Profile icons
- Reach each other over the same local network, the same LoRa repeaters, or
  anywhere through LCS Global

---

## Features

### Messaging
- End-to-end encrypted text messages, pictures and files
- Replies, reactions and delivery status
- Push-to-talk voice messages that also play in Sideband
  - Low bandwidth (Codec2, about 150 bytes/s) for LoRa
  - High bandwidth (Opus, about 3 KB/s) for Wi-Fi and TCP
- Store-and-forward delivery through propagation nodes
- Automatic retry: a failed message tries a direct link, then a fresh route,
  then a propagation node, before it is marked failed
- Block list and privacy controls

### Voice calls
- Encrypted voice calls over any link fast enough to carry them
- Codec2 and Opus quality settings
- Change the codec during a call without hanging up when the link is slow

### Contacts and identity
- Add contacts by QR code or from the network's announce list
- Multiple identities
- Backup and restore to a single file

### Interfaces
- **LCS Global** Connect with others on this gateway worldwide. 
- **Bluetooth LE**: phone-to-phone, no other hardware needed
- **Wi-Fi**: finds other Reticulum devices on the same network automatically
- **TCP**: Command Center PRO (`liberty.local:4246`), or the IPRNode
- **RNode (LoRa)**: connect over USB or Bluetooth
- Nothing reaches the internet until you add LCS Global

### RNode radio setup
- US default frequency 914.875 MHz (LCS slot 51)
- Transmit power set to suit the detected board (Heltec V4, RAK, LILYGO) and
  kept within each region's legal limit
- Presets labeled for what they're good at:
  - **Medium Fast** (default): good range with rooftop repeaters
  - **Long Fast**: radios talking to each other directly
  - **Short Fast**: fast enough for live voice over LoRa

### Map and location
- Share your location with chosen contacts
- Map view, with offline maps

### NomadNet browser
- Browse NomadNet pages, with inline images
- Remembers your place when you switch tabs

### App
- Choose which tabs are on the bottom bar, and their order
- Themes, including a custom theme editor
- Checks for updates when it opens (can be turned off)
- Share the app with someone nearby by QR code, without internet

---

## Install

Download the latest APK from this repository's Releases page. Most phones need
the **arm64-v8a** build; use **universal** if you're not sure. Android 12 or
newer is recommended.

## Build from source

```bash
./gradlew :app:assembleNoSentryPythonBackendRelease
```

Release signing reads its keystore from environment variables. See
`app/build.gradle.kts`.

---

## Credits

Liberty Chat is built on these open-source projects:

- **Reticulum** by Mark Qvist: the networking and encryption stack
- **LXMF** by Mark Qvist: the message format and delivery protocol
- **LXST** by Mark Qvist: the voice protocol. Liberty Chat uses the Kotlin
  implementation, LXST-kt, by torlando-tech.
- **Columba** by torlando-tech: the Android app Liberty Chat was originally
  forked from

Full license terms and notices for these and every other bundled component are
in [LICENSE.md](LICENSE.md) and [THIRD-PARTY-NOTICES.md](app/src/main/assets/licenses/THIRD-PARTY-NOTICES.md).

## License

Liberty Chat is free software under the Mozilla Public License 2.0.
Copyright © 2026 Liberty Communication Systems, Inc.
Release history is in [CHANGELOG.md](CHANGELOG.md).
