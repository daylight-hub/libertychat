package network.libertychat.app.service.manager

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import network.libertychat.app.repository.InterfaceRepository
import network.libertychat.app.rns.api.RnsCore
import network.libertychat.app.rns.api.RnsTransportAdmin
import network.libertychat.app.rns.api.model.InterfaceConfig
import network.libertychat.app.rns.host.manager.CurrentTransport
import network.libertychat.app.service.InterfaceConfigManager
import network.libertychat.app.startup.ConfigApplyFlagManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LCS: brings AutoInterface back in line with the device's NICs when one appears
 * that it cannot see.
 *
 * [AutoInterfaceWatcher] decides *whether* that has happened. This decides what to
 * do about it, and prefers the cheapest thing that works:
 *
 *  1. **Reload the one interface.** RNS >= 1.5.5 can detach and re-attach a single
 *     interface by name, and for `AutoInterface` that re-runs the constructor —
 *     the only thing that makes it re-enumerate NICs. Every other link (BLE,
 *     RNode, TCP) stays up.
 *  2. **Fall back to a full apply.** The same `applyInterfaceChanges()` that
 *     Settings' "Apply & Restart" runs, for when the reload is declined: a
 *     `kotlinBackend` build, where reticulum-kt has no live reload; an
 *     AutoInterface that is not attached at all because the backend disabled it;
 *     or a detach/re-attach that failed.
 *
 * Both paths are rate-limited, and the floors differ by what they cost. A reload
 * touches one interface, so it only needs enough of a floor to stop a flapping NIC
 * from looping it. A restart drops every link for a moment, so it keeps the minute
 * it had before this class existed.
 *
 * Deliberately *not* hooked to ordinary address churn. RNS's `peer_jobs` loop
 * re-binds an adopted interface whose link-local address changed every four
 * seconds, so reconnects and AP roams heal themselves — see [AutoInterfaceWatcher]
 * for why that distinction is the whole point.
 */
@Singleton
class AutoInterfaceRecovery
    @Inject
    constructor(
        private val watcher: AutoInterfaceWatcher,
        private val transportAdmin: RnsTransportAdmin,
        private val rnsCore: RnsCore,
        private val interfaceRepository: InterfaceRepository,
        private val interfaceConfigManager: InterfaceConfigManager,
        private val configApplyFlagManager: ConfigApplyFlagManager,
    ) {
        companion object {
            private const val TAG = "AutoInterfaceRecovery"

            /**
             * Minimum gap between recovery attempts of any kind. Short, because the
             * reload path is cheap — this is only here so a NIC that appears and
             * disappears repeatedly cannot drive a detach/attach loop.
             */
            @VisibleForTesting
            internal const val ATTEMPT_COOLDOWN_MS = 10_000L

            /**
             * Minimum gap between full restarts. A restart tears down BLE, RNode and
             * TCP to rebuild them, so it keeps a much longer floor than a reload.
             */
            @VisibleForTesting
            internal const val RESTART_COOLDOWN_MS = 60_000L

            /**
             * Deadline for the pre-restart path-table flush. Bounded because the
             * flush crosses the AIDL seam into the `:reticulum` process, and a
             * recovery must not hang on it — losing a few routes is better than
             * never rebuilding the interface.
             */
            @VisibleForTesting
            internal const val PERSIST_TIMEOUT_MS = 5_000L
        }

        /** What a call to [recoverIfNeeded] did, so callers and tests can tell the paths apart. */
        enum class Outcome {
            NOT_WIFI,
            NOTHING_NEW,
            NO_AUTO_INTERFACE,
            APPLY_IN_PROGRESS,
            COOLDOWN,
            RELOADED,
            RESTARTED,
            RESTART_FAILED,
        }

        /** Seam for tests, so the two cooldowns can be exercised without real time passing. */
        @VisibleForTesting
        internal var clock: () -> Long = { System.currentTimeMillis() }

        // Null means "never", which is not the same as "at time zero". A plain 0L
        // would put the first attempt inside the cooldown window for any clock whose
        // epoch starts near zero.
        private var lastAttemptAt: Long? = null
        private var lastRestartAt: Long? = null

        private fun withinCooldown(
            last: Long?,
            cooldownMs: Long,
        ): Boolean = last != null && clock() - last < cooldownMs

        /**
         * Recover if the current [transport] can benefit and an interface has in fact
         * appeared. Safe to call on every transport change; most calls do nothing.
         */
        suspend fun recoverIfNeeded(transport: CurrentTransport): Outcome {
            // AutoInterface defaults to WIFI_ONLY, and the transport filter drops it
            // from the stack entirely on cellular or a VPN-only default — so a
            // recovery there would rebuild a stack that still has no AutoInterface
            // in it. WIFI_LIKE is also the transport the interesting case arrives
            // on: WiFi enabled after launch.
            if (transport != CurrentTransport.WIFI_LIKE) return Outcome.NOT_WIFI

            val appeared = watcher.newlyAppearedInterfaces()
            if (appeared.isEmpty()) return Outcome.NOTHING_NEW

            return recoverFor(appeared)
        }

        private suspend fun recoverFor(appeared: Set<String>): Outcome {
            val names = enabledAutoInterfaceNames()
            val blocked =
                when {
                    // Nothing to recover: no AutoInterface is configured and enabled,
                    // so neither a reload nor a restart would add one.
                    names.isEmpty() -> Outcome.NO_AUTO_INTERFACE

                    // Skips when a config apply is already running — the user's own
                    // "Apply & Restart", onboarding, the RNode wizard. Two concurrent
                    // applies would race to clear the shared is_applying_config flag,
                    // and whichever finished first would drop the stale-ACTION_STOP
                    // gate out from under the other.
                    configApplyFlagManager.isApplyingConfig() -> Outcome.APPLY_IN_PROGRESS

                    withinCooldown(lastAttemptAt, ATTEMPT_COOLDOWN_MS) -> Outcome.COOLDOWN

                    else -> null
                }

            if (blocked != null) {
                Log.d(TAG, "New interfaces $appeared but not recovering: $blocked")
                return blocked
            }

            lastAttemptAt = clock()
            return reloadOrRestart(names, appeared)
        }

        private suspend fun reloadOrRestart(
            names: List<String>,
            appeared: Set<String>,
        ): Outcome {
            // `all` short-circuits, which is what we want: once one name is declined
            // we are restarting anyway, so there is no point reloading the rest.
            val reloaded =
                names.all { name ->
                    try {
                        transportAdmin.reloadInterfaceByName(name)
                    } catch (e: CancellationException) {
                        // Not a reload failure. runCatching would have swallowed
                        // this and sent us on to restart the stack while the scope
                        // was being torn down.
                        throw e
                    } catch (e: Exception) {
                        // A dead binder to the :reticulum process throws rather than
                        // returning false; the restart is the right answer either way.
                        Log.w(TAG, "Live reload of '$name' threw; falling back to a restart", e)
                        false
                    }
                }

            if (!reloaded) return restart(appeared)

            // The reload re-enumerated NICs, so the appeared set is now adopted and
            // the baseline has to move with it. The restart path does not need this:
            // InterfaceConfigManager already snapshots after every successful apply.
            watcher.snapshot()
            Log.i(TAG, "Reloaded $names for new interfaces $appeared; stack left running")
            return Outcome.RELOADED
        }

        private suspend fun restart(appeared: Set<String>): Outcome {
            if (withinCooldown(lastRestartAt, RESTART_COOLDOWN_MS)) {
                // Baseline deliberately left alone, so the next transport change
                // retries rather than treating these interfaces as already adopted.
                Log.d(TAG, "Live reload declined for $appeared but within restart cooldown; skipping")
                return Outcome.COOLDOWN
            }

            Log.i(TAG, "Restarting Reticulum: live reload declined and AutoInterface cannot see $appeared")

            // Flush before tearing the stack down. A restart does reload the path
            // table at init, but only what was last written to storage — RNS's own
            // persistence fires on a 12-hour timer and an `atexit` handler Android
            // never runs, and the app otherwise only flushes when it goes to the
            // background. Without this, a recovery the user never asked for costs
            // them every route learned since the last flush.
            val flushed =
                withTimeoutOrNull(PERSIST_TIMEOUT_MS) {
                    try {
                        rnsCore.persistTransportData()
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Path-table flush before restart failed", e)
                        false
                    }
                }
            if (flushed != true) {
                Log.w(TAG, "Restarting without a fresh path-table flush; recent routes may be lost")
            }

            val result =
                try {
                    interfaceConfigManager.applyInterfaceChanges()
                } finally {
                    // Stamped after the call, not before. An apply can run well past
                    // this cooldown — its own timeout is 60s for the main steps and
                    // identity restoration runs outside that — so stamping up front
                    // would leave the window already expired by the time a failed
                    // apply returned, and a persistently failing apply would retry
                    // back to back with no floor at all.
                    lastRestartAt = clock()
                }

            return result.fold(
                onSuccess = { Outcome.RESTARTED },
                onFailure = { e ->
                    Log.w(TAG, "AutoInterface recovery restart failed", e)
                    Outcome.RESTART_FAILED
                },
            )
        }

        /**
         * Names of the enabled AutoInterface entries, which is what RNS keys its live
         * interfaces by — `AutoInterface.__init__` stores the config section name as
         * `self.name`, and that section name is the interface's name in our database.
         * Read from the database rather than assumed, because the user can rename it.
         */
        private suspend fun enabledAutoInterfaceNames(): List<String> =
            interfaceRepository.enabledInterfaces
                .first()
                .filterIsInstance<InterfaceConfig.AutoInterface>()
                .map { it.name }
                .filter { it.isNotBlank() }
    }
