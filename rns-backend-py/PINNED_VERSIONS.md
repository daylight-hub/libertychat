# Pinned Python wheel versions — `:rns-backend-py`

The Python flavor ships **upstream RNS/LXMF as the protocol stack**. These are
the only dependencies that carry protocol-correctness weight, so they are
pinned and bumps require a deliberate PR.

**Pin to commit SHA, not branch tip.** Branch tips move; a build done today and
a build done next month must produce the same protocol behaviour. This mirrors
`release/v0.10.x`'s reproducibility discipline (its commit `63c4a2b` did the
same).

The pins live in `build.gradle.kts`'s `chaquopy { defaultConfig { pip { ... } } }`
block — there is no `requirements.txt` (per the dual-build plan, pip pinning
moved into the Gradle build script).

## Current pins

| Package | Ref | Pinned to | Notes |
|---|---|---|---|
| `rns` (Reticulum) | `git+https://github.com/torlando-tech/Reticulum` | **`3759488eba880489562268d9a1a993f443d1b8bd`** (SHA ✓) | RNS 1.5.5 with socket cleanup, narrow PHY-stats RPC backoff, ratchet file-handle fixes, and deterministic AutoInterface listener/peer teardown. Brings live `Reticulum.attach_interface`/`detach_interface`/`reload_interface`, a prioritised inbound queue, in-flight path-request batching, ~90x faster IFAC, and announce-signature caching. **Requires the announce-callback signature fixes — see below.** |
| `lxmf` (LXMF) | `git+https://github.com/torlando-tech/LXMF` | **`8912186e48b482a76bf04e2ac4b6c8940991aecc`** (SHA ✓) | LXMF 1.1.0 with validated external native stamping, cooperative cancellation and stale-result rejection, plus `receiving_interface` and `receiving_hops` on opportunistic delivery. |
| `ble-reticulum` | `git+https://github.com/torlando-tech/ble-reticulum.git` | **`63557acfe5c923dcdafeb7bc37508bf835f16514`** (SHA ✓) | Provides `BLEInterface` + `bluetooth_driver` that the bundled `ble_modules/` adapters subclass; builds as `ble-reticulum-0.2.3`. **Must move in lockstep with RNS ≥ 1.5.2** — see below. |
| `cryptography` | PyPI | `>=42.0.0` | Range, not pinned — Chaquopy resolves a native wheel for the target ABI. Acceptable: it's a well-tested transitive dep, not a protocol-correctness surface. |
| `u-msgpack-python` | PyPI | unpinned | Sideband-compatible telemetry + LXST signalling wire format. Pure-Python, stable API. |

## Decisions made during the Phase B restore

### `patches/` tree — intentionally NOT restored

`release/v0.10.x`'s `python/patches/RNS/{Destination.py,__init__.py}` carried
context-manager fixes for RNS file-handle leaks (ratchet I/O + the `log()`
function). They are **not restored** here because:

1. The pinned RNS fork commit `5b3a6ee4` **already includes** the ratchet I/O
   context-manager fixes, while upstream RNS 1.4.2 includes the equivalent
   `log()` context-manager fix. The plan's instruction is to skip the `patches/`
   tree when the pinned commit already has the fixes — it does.
2. The patch *deployment* mechanism — `reticulum_wrapper.py::_deploy_rns_patches()`,
   which copied the patched files over the pip-installed RNS at runtime — is
   **not** being restored (the slim-Python design deletes `reticulum_wrapper.py`).
   Restoring `patches/` without a deployer would be dead weight.

If a future RNS pin regresses on those fixes, the correct response is to bump
the pin to a fork commit that has them — **not** to re-introduce a runtime
file-patcher.

### `TorClientInterface.py` — not restored (Tor out of scope)

`release/v0.10.x` shipped `python/TorClientInterface.py` (a `TCPClientInterface`
subclass routing over a local SOCKS5/Tor proxy). It is small and self-contained
(`import RNS` + stdlib only), but Tor support is not in scope for the first
Python-flavor cut. It can be re-added as a single file later if wanted —
restore with `git show 66d983f^:python/TorClientInterface.py`.

## Restored interface adapters — known gap inherited from `66d983f^`

`ble_modules/android_ble_interface.py` does
`from drivers.android_ble_driver import AndroidBLEDriver`, but `66d983f^`'s
Python tree has only `drivers/__init__.py` (no `drivers/android_ble_driver.py`).
At v0.10.x runtime the BLE interface files were *deployed* into the RNS
interfaces directory (`~/.reticulum/interfaces/`) alongside `BLEInterface.py` /
`bluetooth_driver.py` from the `ble-reticulum` wheel, and `drivers/` was
populated there. That deployment step lived in the deleted `reticulum_wrapper.py`.

**This is restored verbatim and left as-is.** Wiring BLE-on-Python so
`Transport.find_interfaces()` discovers `AndroidBLEInterface` is an on-device
integration task (BLE is not on the Phase B verification checklist). When that
work happens, either `PythonRnsRuntime` grows an interface-deployment step or
the `from drivers.android_ble_driver import` line is repointed at
`ble_modules.android_ble_driver`.

## LCS: two couplings to respect when bumping RNS

### The announce-callback signatures are load-bearing

RNS 1.5.0 added a leading `size=0` to four `RNS.Interface` callbacks —
`sent_announce`, `received_announce`, `sent_path_request`,
`received_path_request` — and 1.5.x `Transport` calls the announce pair **by
keyword**:

```python
interface.sent_announce(size=len(raw))                          # Transport.py
packet.receiving_interface.received_announce(size=len(packet.raw))
```

Our two RNode interfaces override the announce pair, so the 1.4.x signatures
raise `TypeError` on **every announce sent or received over an RNode**. The
announce is dropped, peer discovery dies on real hardware, and it presents as a
radio fault rather than a signature mismatch. Both are fixed:

- `src/main/python/columba_rnode_interface.py` subclasses `RNS.Interface`, so it
  now delegates to `super()`. That also restores the `atxc`/`atxb`/`arxc`/`arxb`
  counters the interface-stats RPC reads and the `from_spawned=True` propagation
  to a parent interface, both of which the hand-rolled override silently dropped.
- `src/main/python/rnode_interface.py` is duck-typed rather than an
  `RNS.Interface` subclass, so it accepts `size` and ignores it; the frequency
  deque is all its stats path reads.

The announce **handler** contract (`received_announce(destination_hash,
announced_identity, app_data, …)` in `event_bridge.py`) is unchanged — do not
confuse the two.

### ble-reticulum must co-bump with RNS ≥ 1.5.2

1.5.2 reads `interface.ifac_size` on every inbound packet. ble-reticulum 0.2.2's
spawned BLE peer interface never inherited that attribute, so every inbound
packet raises `AttributeError` and no BLE announce is ever ingested. 0.2.3 fixes
it, and also carries `announce_rate_grace`/`announce_rate_penalty` for the stats
RPC plus a scanner-wedge health check.

### Before trusting `AutoInterfaceWatcher` on a future bump

`app/.../service/manager/AutoInterfaceWatcher.kt` mirrors
`AutoInterface.__init__`'s adoption rules and relies on adoption being
init-only, with `peer_jobs` repairing only a changed link-local address. Verified
true on both 1.4.2 and 1.5.5 — the ignore lists, the `adopted_interfaces` write
site and the `peer_jobs` repair are unchanged across them. Re-check
`RNS/Interfaces/AutoInterface.py` on any further bump.
