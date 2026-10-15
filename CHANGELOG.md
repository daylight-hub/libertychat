# Changelog

All notable changes to Liberty Chat, relative to the upstream Columba release it
forked from.

Liberty Chat is a downstream distribution of [Columba](https://github.com/torlando-tech/columba)
by Liberty Communication Systems. Everything below is an LCS change on top of
upstream; upstream's own history is not repeated here.

---

## 2.3.0 — 2026-10-15

Catch-up with upstream Columba's NomadNet work: 51 commits spanning
`torlando-tech/columba` between the fork point and `main`. LCS had never made a
functional change anywhere in the NomadNet or Micron subsystem — every shared
file was byte-identical to upstream once the package namespace was normalised —
so these files are upstream's, carried across rather than hand-merged. The
fourteen files LCS *had* diverged on (MainActivity, SettingsRepository and
friends) were merged three-way against upstream-at-fork, keeping LCS's own
changes including 2.1.1's transport-node default and 2.2.0's interface reload.

### Added

- **Inline page images.** Micron image tags are parsed and rendered in the
  NomadNet browser, with a per-image loading policy, an image cache, and a
  `/media` request on the `RnsNomadnet` seam. Images can be tapped to reload
  individually, respect percent widths as parent-relative fractions, and honour
  left/right alignment. Saved images land in `Pictures/Liberty Chat`.
  - New image-loading mode setting, so images can be off, on-demand or automatic.
- **Identify.** Saved nodes are identified to at link establishment, auto-identify
  is remembered per destination, and the link is torn down when a node is
  unflagged. Includes upstream's hardening for concurrent requests and for a
  stale identify completing after navigation.
- **User-configurable bottom navigation.** The bar's tabs and their order are
  chosen in Settings, with Settings pinned last and a five-tab ceiling.
  - **LCS ships NomadNet in the default bar**, where upstream leaves it opt-in.
    Upstream's default is Chats, Contacts, Map, Settings, which on a fresh
    install leaves no way into NomadNet at all: the per-announce "Browse Site"
    button only appears for the `nomadnetwork.node` aspect, so with no such
    announce received the feature is invisible. The default is now Chats,
    Contacts, Map, NomadNet, Settings — the three existing tabs keep their
    positions, and it is still within the five-tab cap.
- **NomadNet keeps its session across tab switches.** Moving to another tab and
  back leaves the page you left on screen with no re-fetch, because the
  navigation entry is saved and restored rather than popped. Re-tapping the
  NomadNet tab while browsing is a no-op; a site session ends via Close Site or
  Back.
- **Ping destination** in the chat's three-dot menu, above Request path. Reports
  whether the peer answers right now, the round-trip time and the hop count, and
  says so immediately rather than after the probe finishes, since link
  establishment can run to its timeout.
  - Placed above Request path deliberately: ping is the diagnosis, Request path
    the remedy. A `no_path` result names Request path as the next step.
  - Nothing is delivered to the peer. The probe establishes or reuses an
    `RNS.Link` and reads its metrics; it does not send an LXMF message, so a ping
    cannot leave anything in the other side's chat. (`RnsCore.probeLinkSpeed`'s
    own documentation claimed otherwise and was wrong — corrected.)
  - Built on the existing link probe that the pre-dial codec picker already uses,
    so no new backend surface was needed. That probe degrades to path-table
    heuristics when a peer refuses a link, so a reachable result does not always
    carry a round-trip figure.
- Two new AIDL parcelables (`NomadnetMediaResult`, `NomadnetLinkStats`), three
  new seam methods, and two new `RnsError` variants.

### Changed

- **Transport node is off by default again**, as upstream has it, and the toggle
  is back to meaning only what it says: whether this device carries other peers'
  traffic.
  2.1.1 turned it on for a real reason in the wrong place. Upstream RNS gates the
  *startup load* of its saved path table on `transport_enabled()`, while
  `save_path_table` is ungated — so with transport off the `destination_table`
  file was written and never read back, and every launch began with no routes.
  Turning transport on fixed that, but it also made every handset relay traffic,
  answer path requests and pass announces along: battery and LoRa airtime spent
  on something the user never asked for.
  `event_bridge.restore_path_table()` now performs that load itself, ungated, so
  learned routes survive a restart either way. It mirrors upstream's own loader
  field for field — re-resolving each entry's interface by hash, bumping the
  cached announce's hop count, and skipping blackholed identities — and will not
  overwrite an entry already learned since the process started. A no-op when
  transport is on (upstream has already loaded it) or when another RNS app owns
  the stack.

### Fixed

- The NomadNet link is retried after waiting for route discovery on a cold
  start, instead of failing because no path existed yet.
- Refreshing or retrying a page that carries variables rebuilds its request data
  from the displayed page's own tokens, rather than losing it.
- The backtick field block is preserved and displayed.
- A flagged node's pages are re-fetched rather than served from cache, including
  on Back navigation.
- Deep page position and link reuse survive leaving and re-entering the tab.

---

## 2.2.0 — 2026-10-15

### Changed

- **Python RNS bumped 1.4.2 → 1.5.5**, with ble-reticulum co-bumped 0.2.2 →
  0.2.3. Brings live interface attach/detach/reload, a prioritised inbound
  queue, in-flight path-request batching, roughly 90x faster IFAC and
  announce-signature caching (which matters under announce storms), plus RNode
  BLE reconnect reliability fixes.
  - **The announce-callback signature fixes are part of the bump, not optional.**
    RNS 1.5.0 added a leading `size=0` to four `RNS.Interface` callbacks, and
    1.5.x `Transport` calls the announce pair by keyword —
    `sent_announce(size=len(raw))`. Our two RNode interfaces override that pair,
    so on the old signatures *every announce sent or received over an RNode*
    would raise `TypeError` and be dropped: peer discovery would die on real
    hardware, presenting as a radio fault rather than a signature mismatch.
    `columba_rnode_interface.py` now delegates to `RNS.Interface`, which also
    restores the announce byte counters the interface-stats RPC reads and the
    spawned-interface propagation that the hand-rolled override had silently
    dropped; `rnode_interface.py` is duck-typed rather than a subclass, so it
    accepts the parameter and ignores it.
  - **ble-reticulum had to move in lockstep.** RNS ≥ 1.5.2 reads
    `interface.ifac_size` on every inbound packet, and 0.2.2's spawned BLE peer
    interface never inherited it — every inbound packet would have raised
    `AttributeError` and no BLE announce would ever have been ingested.
  - `AutoInterfaceWatcher`'s assumptions were re-verified against the new pin:
    the ignore lists, the init-only `adopted_interfaces` write and the
    `peer_jobs` link-local repair are all unchanged between 1.4.2 and 1.5.5.
  - The announce *handler* contract in `event_bridge.py` is unchanged; the two
    are easy to confuse and only the `Interface` callbacks moved.
- **Picking up a new network interface no longer restarts the stack.** The
  recovery added in 2.1.1 reloads just AutoInterface now, using RNS 1.5.5's
  `Reticulum.reload_interface`. That detaches the one interface and
  re-synthesizes it from its config entry, which for AutoInterface re-runs the
  constructor — the only thing that makes it re-enumerate NICs. BLE, RNode and
  TCP links stay up throughout, where previously enabling WiFi after launch tore
  down every link to rebuild them.
  - Falls back to the old full `applyInterfaceChanges()` restart when the reload
    is declined: a `kotlinBackend` build, where reticulum-kt has no live
    interface management at all; or an AutoInterface that is not attached.
  - The interface is reloaded under the name it carries in the database, not a
    hardcoded one, because the user can rename it in Settings. RNS keys its live
    interfaces by the config section name and `AutoInterface.__init__` stores
    that as `self.name`, so the two agree.
  - A reload is used rather than a detach followed by an attach, and that is a
    safety property rather than a shortcut. `_attach_interface` re-synthesizes
    with `force_attach=True`, which ignores the entry's `enabled` flag — so an
    attach could force up an AutoInterface the backend deliberately wrote as
    `enabled = no` because another RNS app on the device holds the multicast
    data port, and binding it would take the `:reticulum` process down with
    `EADDRINUSE`. `_reload_interface` looks the name up in
    `Transport.interfaces` first and returns early when it is not there, so an
    interface that was never attached stays down.
  - Two rate limits instead of one, since the paths no longer cost the same: 10s
    between reload attempts, enough to stop a flapping NIC driving a
    detach/attach loop, and the original 60s floor kept for the restart
    fallback. Skipping a restart on its cooldown deliberately leaves the
    detection baseline alone, so the next network change retries rather than
    treating the interface as already adopted.
  - Declined outright when another RNS app owns the stack as a shared instance:
    `reload_interface` would forward the request over RPC to *their* instance,
    where our interface names mean nothing.

### Fixed

- **Recovering an interface no longer throws away the routes learned on it.**
  `Transport.jobs()` culls every path table entry whose attached interface has
  left `Transport.interfaces` — *"removed since the attached interface no longer
  exists"* — so detaching an interface silently drops its routes within seconds.
  That bites both recovery paths:
  - A **live reload** had no recovery at all, because the path table is only read
    from storage inside `Transport.start`; a reload has no reload-from-disk behind
    it the way a restart does, and the next persistence flush would write the loss
    through to the saved table. Reloads now go through
    `event_bridge.reload_interface_preserving_paths`, which takes the affected
    entries first and re-inserts them against the new interface object — the same
    rebinding `Transport.start` performs via `find_interface_from_hash`. The
    interface slot is located by object identity rather than a hardcoded index, so
    a future change to the entry layout cannot make it write the wrong field.
  - A **restart** does reload the table, but only what was last written. RNS's own
    persistence fires on a 12-hour timer and an `atexit` handler Android never
    runs, and the app otherwise only flushes on backgrounding — so a recovery the
    user never asked for cost them everything learned since. The path table is now
    flushed before the restart, under a 5s deadline so a wedged IPC call cannot
    hang the recovery.
- **The stack no longer goes quiet after ten hours.** The doze-protection wake
  lock was taken with a 10-hour timeout, and `acquireAll()` runs only at service
  start and on a network change — and only takes a lock when one is not already
  held, so it could never renew a timed one. On a device sitting on a single
  network the lock simply lapsed, letting the CPU doze: the Python stack's timers
  stop, `AutoInterface` stops sending peer announces, its peers age out at the 22s
  peering timeout, and every interface looks stale until something unrelated
  rebuilt the locks. `LockManager.refreshAll()` now pushes the expiry back every
  20 minutes against a 2-hour backstop. Re-acquiring a non-reference-counted wake
  lock resets its timeout rather than stacking, so the keepalive is safe to run on
  a timer.

### Added

- `RnsTransportAdmin.reloadInterfaceByName(name)` across the backend seam — AIDL
  surface, both IPC adapters, and both backends. Real on `pythonBackend`;
  returns false on `kotlinBackend`, which is the fallback signal rather than an
  error. Note this is distinct from the pre-existing `reloadInterfaces(configs)`,
  which remains a documented no-op on the python backend: whole-set interface
  changes still go through the config-rewrite-and-restart path.

---

## 2.1.1 — 2026-10-14

### Fixed

- **Learned routes survive a restart.** Paths were lost whenever the app was
  closed and reopened, and the peer had to announce again before anything could
  be sent. Two independent causes, either of which was enough on its own:
  - **Nothing was written on a normal background-and-kill.** The backend's
    `persistTransportData()` was a no-op, on the reasoning that RNS persists on
    its own cadence. It does, but that cadence is `Reticulum.PERSIST_INTERVAL` —
    **12 hours** — plus an `atexit` handler that Android never runs, because it
    kills the process rather than letting the Python interpreter exit.
    `Application.onTerminate()` was no help either; Android does not call it on a
    real device. (An explicit Apply & Restart or Stop *did* write, which is why
    the loss looked intermittent.) It now calls `Transport.persist_data()`, which
    was a public method all along, and the app flushes whenever it goes to the
    background — the last moment it is reliably still alive.
  - **Nothing was ever read back.** Upstream RNS gates the startup load of its
    saved path table on `Transport.transport_enabled()`, so with transport off
    the file was written and then ignored. **Transport node is now on by
    default.** The trade is deliberate: the device also routes traffic for other
    peers, answers path requests and passes announces along, which costs battery
    and airtime on a LoRa link. It can still be turned off in Settings, at the
    price of losing routes on every restart.

  Two limits worth knowing. This covers the shipped `pythonBackend` flavor;
  `kotlinBackend` still does not persist its path table, because reticulum-kt
  exposes no flush for it. And neither half applies when the app runs as a
  shared-instance client — another RNS app on the device owning the stack — since
  RNS gates both the save and the load on the instance not being a shared client.

- **A network interface that appears after launch is now picked up.** Starting
  with WiFi off and enabling it later left local peers invisible until the stack
  was restarted by hand. `ReticulumService`'s network-change callback was written
  to fix exactly this — its comment said so — but the `binder.restartAutoInterface()`
  it called was `= Unit`, and had been since the native-mode port. Python RNS
  cannot hot-add an interface in any case: `AutoInterface` enumerates and adopts
  its NICs once, in its constructor, and nothing ever extends that set.
  - Recovery now lives in the main process, where the config and the restart
    machinery already are. `AutoInterfaceWatcher` snapshots the adoptable
    interfaces when the backend starts and reports any that appear later;
    `ColumbaApplication` then runs the same `applyInterfaceChanges()` restart that
    Settings' "Apply & Restart" performs.
  - **Ordinary network churn does not trigger it.** A reconnect, an AP roam or a
    new link-local address on an interface RNS already adopted is repaired by
    RNS's own `peer_jobs` loop every four seconds — it swaps the address, closes
    the old UDP listener and binds a new one. Only a genuinely unseen NIC causes a
    rebuild, and it is rate-limited to one a minute, because a restart briefly
    drops BLE, RNode and TCP links too. It also stands down while any other config
    apply is running, so it cannot race the user's own "Apply & Restart".
  - **Only on WiFi-like transports.** AutoInterface is a `WIFI_ONLY` interface, so
    an apply on cellular or a VPN-only default rebuilds the stack *without* it —
    a restart there would drop every link to achieve nothing. Recovery therefore
    runs only when the current transport is WiFi or Ethernet.
  - The watcher's filter is deliberately **stricter** than `AutoInterface`'s own
    rules. Upstream's Android ignore list stops at literal `rmnet0`..`rmnet7`,
    which misses how modern devices actually name cellular (`rmnet_dataN` on
    Qualcomm, `ccmniN` on MediaTek, plus extra PDNs appearing at runtime) and
    misses VPNs beyond `tun0` (`wg0`, `tun1`). Matching upstream exactly would
    have made ordinary cellular and VPN transitions look like new interfaces. Those
    families carry no local peers, so excluding them costs nothing.
  - Known limitation: detection is driven by the transport observer, which only
    reports changes of transport *class*. An interface appearing while the class is
    unchanged — a dock's Ethernet coming up while already on WiFi, or a hotspot or
    WiFi Direct interface — is not noticed until the next transport change.
- Removed the `restartAutoInterface()` no-op from `ReticulumServiceBinder` now
  that nothing calls it, rather than leaving a method whose name claims it does
  something.

### Changed

- Corrected the upstream stack table in the README: the Python stack is RNS
  **1.4.2** and LXMF **1.1.0**, matching the pinned commits in
  `rns-backend-py/build.gradle.kts`. It had read 1.1.9 / 0.9.2.
- Dropped the claim in `rns-backend-py/ARCHITECTURE.md` that AutoInterface
  hot-add is implemented in Kotlin in `:rns-host`. It never was: the
  `restartAutoInterface()` hook the network-change callback invoked was a no-op,
  and it has now been removed outright in favour of the recovery above.

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
  - **The propagation leg gets the same treatment.** A message that fell back to
    a propagation node and failed there, or that the user addressed to one
    directly, prompts a path request for **the node** rather than the peer — the
    peer was never the next hop, so asking about it could not help. The full
    ladder is now: send; if the direct route fails, ask for a path to the peer
    and hand the message to the propagation node; if that fails too, ask for a
    path to the node. Each rung is rate-limited on its own destination.
  - Nothing re-sends automatically. A route takes time to return and a retry
    racing the announce would fail again, so the message stays "failed" and
    **Retry** is there once the path is back.
- **Request path** in the chat's three-dot menu, for asking on demand rather
  than waiting — useful when a repeater has just come back up. A path belongs to
  the peer rather than to any one message, so it is scoped to the conversation. A
  deliberate tap bypasses the rate limit, since an explicit action that silently
  does nothing is worse than the airtime. A Toast confirms the request went out;
  a path request is fire-and-forget at the protocol level, so nothing can
  confirm more than that.

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
