# Changelog

All notable changes to Liberty Chat, relative to the upstream Columba release it
forked from.

Liberty Chat is a downstream distribution of [Columba](https://github.com/torlando-tech/columba)
by Liberty Communication Systems. Everything below is an LCS change on top of
upstream; upstream's own history is not repeated here.

---

## 2.1.0 — 2026-10-07

### Added

- **Path request when a message fails to deliver.** A message that comes back
  failed now triggers `RNS.Transport.request_path` for that peer, so the route
  can be rediscovered instead of the message sitting dead until the user
  notices.
  - Hooked to the **delivery failure**, not the send call. A send that fails
    synchronously does so only when the backend couldn't resolve the recipient
    at all — and by then it has already called `request_path` itself and polled
    for ten seconds, so asking again there would just put a second broadcast on
    the air for nothing.
  - The case with no path request anywhere is the one this catches. Once a peer
    has been resolved its destination is cached, so a peer that was reachable an
    hour ago and isn't now skips resolution entirely, goes straight to
    `handle_outbound` over a path that no longer leads anywhere, and surfaces as
    a delivery failure later. Nothing in that sequence ever asks for a new
    route, and RNS won't drop the stale entry until it expires on its own.
  - The request is **forced** — it does not skip when RNS already holds a path.
    That guard is right for the startup sweep and for opening a chat, and wrong
    here, since a stale path is the whole problem.
  - **Rate-limited to one automatic request per peer per minute.** A path
    request is broadcast on every active interface, which costs real airtime on
    a LoRa link; a conversation failing repeatedly costs one request a minute,
    not one per message.
  - Nothing re-sends automatically. A route takes time to return and a retry
    racing the announce would fail again, so the message stays "failed" and
    **Retry** is there once the path is back.
- **Request path** on the message action menu (long-press a message), for asking
  on demand rather than waiting — useful when a repeater has just come back up.
  A deliberate tap bypasses the rate limit, since an explicit action that
  silently does nothing is worse than the airtime. A Toast confirms the request
  went out; a path request is fire-and-forget at the protocol level, so nothing
  can confirm more than that.

### Changed

- **Medium Fast is now the default modem preset**, in place of Long Fast. SF9 on
  a 250 kHz channel leaves a rooftop repeater enough airtime headroom to hear,
  decode and re-transmit a frame before the next one arrives, while staying
  faster than the Long presets — and LCS deployments lean on rooftop repeaters.
  The wizard's pre-filled spreading factor moves from 11 to 9 to match; bandwidth
  and coding rate are the same on both presets, so slot counts are unchanged.
  Existing interfaces keep whatever they were configured with.
- Modem preset badges reworked around that change: **Medium Fast** reads *Good
  range with rooftop repeaters*, **Long Fast** reads *Good for ground radios*
  (it no longer carries *Default*), and **Medium Slow** keeps *Works well with
  repeaters*. No preset is badged *Default* any more — the default is shown by
  being pre-selected.
- A "no route" send failure now reads *"No route to this contact. They may be
  offline or out of range — Retry once they're back."* instead of surfacing the
  backend's `IdentityNotFound`, which read as though the contact were wrong
  rather than unreachable.
- The message action row scrolls horizontally. It reaches seven buttons with the
  new action, which overflows a compact phone — the far end was previously
  unreachable.

---

## 2.0.9 — 2026-10-06

### Added

- **Command Center PRO over TCP** (`liberty.local:4246`), as an alternative way
  in when AutoInterface can't find the Command Center — its multicast discovery
  doesn't survive every router, and some guest networks block peer-to-peer
  traffic outright.
  - Offered in **Add Interface → TCP Client**, listed under the local IP RNode,
    with a note saying when to reach for it.
  - Offered during **onboarding** as its own choice on the "How will you
    connect?" screen, so a first run that can't see the Command Center over
    Local WiFi still has a route to it.
  - Never configured as a bootstrap interface. A bootstrap link auto-detaches
    once enough other interfaces are connected, which is the wrong behaviour
    for the user's own Command Center.
  - It is tracked by its host and port rather than by interface type, so it and
    the LCS public node coexist as two separate TCP interfaces instead of
    overwriting one another.
- **Medium Fast** and **Medium Slow** modem presets badged *Works well with
  repeaters*. At SF9–10 on a 250 kHz channel they leave a repeater enough
  airtime headroom to hear, decode and re-transmit a frame before the next one
  arrives — the Short presets are too fast for that and the Long presets too
  slow.

### Fixed

- **The Columba logo no longer appears on the RNode's screen.** The wizard's
  *Display Logo on RNode* toggle was removed earlier, but the framebuffer was
  still populated whenever an interface carried the old `enable_framebuffer`
  default, which left the logo latched on the panel — most visibly on an RNode
  reached over IP, where nothing cleared it when the app disconnected. The
  backend now disables the RNode's external framebuffer on every connect,
  regardless of what a saved config says, so interfaces set up by an earlier
  build are fixed on their next connect rather than needing to be re-added. The
  logo asset itself is gone from both backends.

---

## 1.2.1 — 2026-07-23

### Added

- **Change the call codec without hanging up.** An advisory on the call screen
  now appears when the link measured before dialling is too slow for the codec
  in use. It names what the link measured, what would fit, and offers a codec
  picker and a push-to-talk suggestion.
  - The picker lists **both families** — Codec2 (700C / 1600 / 3200) for LoRa
    links and Opus (Medium / High / Maximum) for WiFi and TCP — so the switch
    works in both directions. A call downgraded earlier, or one that has moved
    onto a faster interface, can go back up to Opus without redialling.
  - Switching moves **both ends**. LXST signals the peer with
    `Signalling.PREFERRED_PROFILE`, and the peer's LXST rebuilds its encoder and
    decoder to match. That is upstream protocol, so Sideband and MeshChat peers
    follow a switch, and this side follows theirs.
  - Half-duplex is offered alongside, since it roughly halves what the link has
    to carry.

### Fixed

- **The splash wordmark never appeared.** `setContent` composes the whole tree
  during `onCreate`, while the system splash is still covering the window — so
  the 1.2.0 overlay ran its dwell timer and removed itself *behind* the splash,
  leaving a logo and no words. The overlay now waits for
  `SplashScreen.setOnExitAnimationListener` before starting.
- **The splash now reads identically on every Android version.** The platform
  branding attribute has been dropped from the v31 and v33 themes; the app draws
  the wordmark itself on all API levels rather than relying on a slot that only
  exists on Android 12+.
- **Push-to-talk settings ran off the right edge of the card.** Both PTT rows
  put an unweighted `Row` next to a `Switch`, so the label consumed the full
  width and pushed the switch off screen. Both now use `weight(1f)`, and the
  bandwidth label has been shortened with the detail moved into the description
  below it.

### Changed

- Codec badge shortened to **"Voice/PTT"**; modem preset badge shortened to
  **"Best for voice over LoRa"**.

### Not included

Automatic codec reduction during a call. LXST exposes no mid-call quality
telemetry — no jitter, packet-loss or decoder-underrun counters — so the only
available measurement is an active probe, which would send traffic over the very
link that is already struggling. On a ~1 kbps Long Fast link that probe would
cause the degradation it was meant to detect. The measurement is therefore taken
once before dialling and the decision left with the user. A starvation counter
exposed upstream would be enough to revisit this.

---

## 1.2.0 — 2026-07-22

Second feature release. Voice messaging matures, the default radio
configuration changes, and two long-standing branding defects are fixed.

### Voice messages (PTT)

- **Replay.** Received voice messages now render as a tappable bubble showing
  the codec and playback state. Tap to replay, tap again to stop. Clips still
  autoplay once on receipt when the conversation is open.
  - Previously a received clip autoplayed and then had no visual representation
    at all — a PTT message carries no text beyond the single placeholder space
    Sideband sends for attachment-only messages, so the bubble appeared empty.
- **Unsupported-codec state.** A Codec2 clip that this device cannot decode now
  says so on the bubble instead of silently doing nothing. In practice this is
  the x86_64 emulator case: LXST-kt ships `libcodec2.so` for `arm64-v8a` and
  `armeabi-v7a` only.

### Radio defaults

- **RNode TX power default raised to 22 dBm**, both as the wizard's initial
  value (was 17) and as the per-region default.
  - 22 dBm is the ceiling on the RAK and LILYGO boards LCS ships.
  - Region defaults are clamped to each band's regulatory maximum rather than
    applied flat, so this reads 22 dBm in the US, Australia, Brazil and India,
    and the legal figure elsewhere (EU 868 stays 14, EU 433 stays 12, Korea 14,
    Japan 16).
  - The city-level presets under *Popular RNode Settings* still carry their own
    community-sourced TX values and are unchanged.
- **RNode interface mode default changed to Full** (was Boundary). LCS nodes are
  expected to participate fully in transport rather than sit at the network
  edge.
- **Short Fast is now badged "Best for LoRa voice/PTT"** in the modem preset
  picker, alongside the existing "LCS Recommended" badge on Long Fast.
  - The two are different recommendations for different jobs. Long Fast
    (SF11/BW250) is ~1.07 kbps raw and is a messaging preset — it cannot carry
    a live call. Short Fast (SF7/BW250) is ~10.9 kbps, which fits Codec2 3200
    with room for RNS framing.
- **Codec2 3200 is now badged "Best for LoRa voice/PTT"** in the call quality
  picker. This badge is editorial and static; it appears alongside the dynamic
  "Recommended" chip driven by the live link probe, which may disagree.

### Network

- **No TCP bootstrap interface is seeded any more.** Upstream Columba seeded its
  Beleth RNS Hub; LCS now seeds neither that nor a replacement.
  - A fresh install comes up on AutoInterface and Bluetooth LE only. Nothing
    reaches out over the internet unless the user chooses a TCP server or
    attaches an RNode.
  - The **LCS gateway remains available as a bootstrap interface** wherever one
    is chosen: pick TCP during onboarding, or add it from Settings → Interfaces
    → Add, and it is configured `bootstrap_only` so it auto-detaches once better
    connections are discovered. The difference from before is that it is opt-in
    rather than seeded.
  - Existing installs have the seeded bootstrap row deleted on first launch
    after upgrading. This is a data cleanup, not a schema migration — the
    interface table is unchanged. A server the user added deliberately is not
    touched.

### Fixed

- **Onboarding dropped the bootstrap flag on the TCP interface.** Choosing TCP
  during first-run created the LCS gateway as a permanent interface, while the
  same server added later through the TCP wizard was correctly configured
  `bootstrap_only`. Onboarding now carries the flag through.

### Interface and branding fixes

- **Fixed the splash screen on Android 13 and newer.** `values-v33/themes.xml`
  shadowed `values-v31` in full, and set a purple background with no branding
  image. Every Android 13+ device — most current phones — was showing upstream
  Columba's purple splash with no Liberty Chat wordmark. The v33 theme now
  matches v31 and keeps only its intentional difference
  (`windowSplashScreenBehavior`).
- **Added the splash wordmark on Android 10 and 11.**
  `android:windowSplashScreenBrandingImage` is API 31+ and platform-only;
  androidx `core-splashscreen` does not emulate the branding slot. On API < 31
  the app now draws "Liberty Chat / powered by Columba" itself — navy and
  silver, as live text rather than the PNG, so it stays crisp at any density.
  No-ops on API 31+.
- **Centred the "Buy RNode Radios" button and its label.** The gloss overlay
  inside the shiny red button used `fillMaxWidth`, which stretched the button
  edge to edge while leaving the label at the default top-start alignment. The
  overlay now uses `matchParentSize()`, so the button wraps its label and both
  the pill and the text sit centred.

### Removed

- **The crash-reporting opt-in popup is gone.** LCS ships the `noSentry`
  flavor, so the prompt could only ever be a dead end, and an unprompted dialog
  asking to send data off-device is not appropriate for Liberty Chat. The
  setting remains in Settings → Advanced for anyone deliberately building the
  `sentry` flavor.

---

## 1.1.2 — 2026-07-22

### Added

- **Voice messages over LXMF `FIELD_AUDIO` (0x07), interoperable with Sideband
  in both directions and both codecs.**
  - Push-to-talk clips are sent as `FIELD_AUDIO = [mode, bytes]` rather than as
    file attachments. A clip sent as a file attachment arrives in Sideband as
    an inert file: no voice bubble, no autoplay.
  - **Low bandwidth** sends Codec2 1200 (~150 B/s), using the `libcodec2.so`
    LXST-kt already ships for voice calls. Chosen over Sideband's own 2400
    default because Sideband decodes the whole 700C–3200 range on receive, so
    the lower rate costs nothing in interoperability and halves the airtime.
  - **High bandwidth** sends Opus in an Ogg container (~3 KB/s).
  - Inbound clips in either codec are decoded and autoplayed when the
    conversation is open, matching Sideband's own PTT behaviour.
  - The Advanced Settings toggle now selects the *codec*, not just the bitrate —
    the same choice Sideband's `hq_ptt` option makes. Both apps now mean the
    same thing by "low" and "high".

### Fixed

- **Attachment payloads crossing the IPC boundary as text.** The `extraFields`
  Bundle codec only marshals scalars, `String` and `ByteArray`, and silently
  called `toString()` on anything else — so a `[mode, bytes]` list crossed the
  binder as the literal text `"[16, [B@...]"`. Audio now travels on the
  file-descriptor-backed attachment blob alongside images and files.
- **`tests/interop/test_audio.py` was passing on a wrong constant.** It used
  `0x01`, which is `AM_CODEC2_450PWB` — a mode Sideband has no bindings for and
  refuses outright. The suite now pins real `AM_*` values and covers Codec2 at
  1200 and 2400 in both directions.

---

## 1.1.1 — 2026-07-22

### Fixed

- Push-to-talk settings toggles did not respond to taps. The block has been
  hoisted out of the scope that was swallowing the interactions.
- Splash branding image now ships at all five screen densities instead of a
  single `nodpi` asset.

---

## 1.1.0 — 2026-07-21

### Added

- Push-to-talk voice capture in conversations, with a bandwidth toggle in
  Advanced Settings.

---

## 1.0.0 — 2026-07-21

Initial Liberty Chat release, forked from **upstream Columba 2.0.9**.

### Network

- Community TCP server list replaced with LCS infrastructure: **LCS Public Node**
  (`public.lcs.network:4245`, bootstrap) and **Local IP RNode**
  (`iprnode.local:4545`).
- Update checks point at the `daylight-hub/columba` release feed rather than
  upstream, so "View Release" and the update banner track LCS builds.

### Removed

- Built-in RNode firmware flasher entry removed from Settings. LCS ships
  pre-flashed hardware; the flasher's failure modes are not something an end
  user should meet unprompted.

### Branding

- Liberty Chat identity throughout: app name, launcher icon, share-APK naming
  (`liberty-chat-<version>.apk`), and the "Liberty Chat — powered by Columba"
  splash wordmark.
- Liberty theme (navy / gold / silver) matching LCS identity.
- "Buy RNode Radios" call-to-action in Settings → About, linking to
  `www.lcs.network`.

### Licensing

Columba is MPL-2.0. Liberty Chat is a downstream distribution and remains
MPL-2.0; modified source files carry the same licence, and the full text ships
in `LICENSE.md`.
