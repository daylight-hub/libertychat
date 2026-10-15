package network.libertychat.app.service.manager

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import network.libertychat.app.repository.InterfaceRepository
import network.libertychat.app.rns.api.RnsCore
import network.libertychat.app.rns.api.RnsTransportAdmin
import network.libertychat.app.rns.api.model.InterfaceConfig
import network.libertychat.app.rns.host.manager.CurrentTransport
import network.libertychat.app.service.InterfaceConfigManager
import network.libertychat.app.startup.ConfigApplyFlagManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LCS: tests for [AutoInterfaceRecovery]'s choice between a live reload and a full
 * restart.
 *
 * The interesting behaviour is the preference order and its rate limits. A reload
 * touches one interface and leaves BLE, RNode and TCP links up; the restart drops
 * all of them. So the reload has to be tried first, the restart has to happen when
 * the reload is declined — a `kotlinBackend` build, a pre-1.5.5 pin, an
 * AutoInterface that is not attached — and neither may loop on a flapping NIC.
 *
 * The real [AutoInterfaceWatcher] is used rather than a mock, because the baseline
 * bookkeeping after a successful reload is part of what is being tested: if the
 * snapshot is not moved, the same interface is reported as new forever.
 */
class AutoInterfaceRecoveryTest {
    private lateinit var watcher: AutoInterfaceWatcher
    private lateinit var transportAdmin: RnsTransportAdmin
    private lateinit var rnsCore: RnsCore
    private lateinit var interfaceRepository: InterfaceRepository
    private lateinit var interfaceConfigManager: InterfaceConfigManager
    private lateinit var configApplyFlagManager: ConfigApplyFlagManager
    private lateinit var recovery: AutoInterfaceRecovery

    private var present: Set<String> = emptySet()
    private var now = 1_000L

    @Before
    fun setup() {
        watcher = AutoInterfaceWatcher()
        watcher.adoptableProvider = { present }

        transportAdmin = mockk()
        rnsCore = mockk()
        interfaceRepository = mockk()
        interfaceConfigManager = mockk()
        configApplyFlagManager = mockk()

        // Default world: one enabled AutoInterface, nothing else in the way.
        // Deliberately *not* named "AutoInterface" — the name is user-editable, so a
        // hardcoded one would pass a test and fail on any renamed interface.
        givenEnabledInterfaces(InterfaceConfig.AutoInterface(name = "Mesh Discovery"))
        every { configApplyFlagManager.isApplyingConfig() } returns false
        coEvery { rnsCore.persistTransportData() } returns Unit

        recovery =
            AutoInterfaceRecovery(
                watcher = watcher,
                transportAdmin = transportAdmin,
                rnsCore = rnsCore,
                interfaceRepository = interfaceRepository,
                interfaceConfigManager = interfaceConfigManager,
                configApplyFlagManager = configApplyFlagManager,
            )
        recovery.clock = { now }
    }

    private fun givenEnabledInterfaces(vararg configs: InterfaceConfig) {
        every { interfaceRepository.enabledInterfaces } returns flowOf(configs.toList())
    }

    /** Baseline taken with nothing present, then [name] appears — the case this exists for. */
    private fun givenInterfaceAppeared(name: String = "wlan0") {
        present = emptySet()
        watcher.snapshot()
        present = setOf(name)
    }

    @Test
    fun `ignores transports where AutoInterface is not in the stack at all`() =
        runTest {
            // AutoInterface is WIFI_ONLY, so the transport filter drops it on
            // cellular. Recovering there would rebuild a stack that still has no
            // AutoInterface in it, at the cost of every other link.
            givenInterfaceAppeared()

            assertEquals(
                AutoInterfaceRecovery.Outcome.NOT_WIFI,
                recovery.recoverIfNeeded(CurrentTransport.CELLULAR),
            )
            assertEquals(
                AutoInterfaceRecovery.Outcome.NOT_WIFI,
                recovery.recoverIfNeeded(CurrentTransport.NONE),
            )
        }

    @Test
    fun `does nothing on ordinary churn where no interface appeared`() =
        runTest {
            // A reconnect or an AP roam changes the link-local address on an
            // interface RNS already adopted, and its peer_jobs loop re-binds that
            // itself every four seconds. Nothing for us to do.
            present = setOf("wlan0")
            watcher.snapshot()

            assertEquals(
                AutoInterfaceRecovery.Outcome.NOTHING_NEW,
                recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE),
            )
        }

    @Test
    fun `reloads the interface under its configured name rather than restarting`() =
        runTest {
            val reloadedName = slot<String>()
            coEvery { transportAdmin.reloadInterfaceByName(capture(reloadedName)) } returns true
            givenInterfaceAppeared()

            assertEquals(
                AutoInterfaceRecovery.Outcome.RELOADED,
                recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE),
            )
            assertEquals("Mesh Discovery", reloadedName.captured)
        }

    @Test
    fun `reloads every enabled AutoInterface entry`() =
        runTest {
            val names = mutableListOf<String>()
            coEvery { transportAdmin.reloadInterfaceByName(capture(names)) } returns true
            givenEnabledInterfaces(
                InterfaceConfig.AutoInterface(name = "Mesh Discovery"),
                InterfaceConfig.AutoInterface(name = "Lab Discovery"),
            )
            givenInterfaceAppeared()

            assertEquals(
                AutoInterfaceRecovery.Outcome.RELOADED,
                recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE),
            )
            assertEquals(listOf("Mesh Discovery", "Lab Discovery"), names)
        }

    @Test
    fun `does not report the same interface again after a successful reload`() =
        runTest {
            // The reload re-ran AutoInterface's constructor, so wlan0 is adopted now
            // and the baseline has to move with it. Without this the interface looks
            // new on every subsequent transport change.
            coEvery { transportAdmin.reloadInterfaceByName(any()) } returns true
            givenInterfaceAppeared()

            recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE)
            now += AutoInterfaceRecovery.ATTEMPT_COOLDOWN_MS

            assertEquals(
                AutoInterfaceRecovery.Outcome.NOTHING_NEW,
                recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE),
            )
        }

    @Test
    fun `restarts when the backend declines the reload`() =
        runTest {
            // What a kotlinBackend build or a pre-1.5.5 pin looks like: false rather
            // than an exception, so the caller can fall back.
            coEvery { transportAdmin.reloadInterfaceByName(any()) } returns false
            coEvery { interfaceConfigManager.applyInterfaceChanges(any()) } returns Result.success(Unit)
            givenInterfaceAppeared()

            assertEquals(
                AutoInterfaceRecovery.Outcome.RESTARTED,
                recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE),
            )
        }

    @Test
    fun `restarts when the reload throws`() =
        runTest {
            // A dead binder to the :reticulum process throws rather than returning.
            // It must not escape to the collector, which would kill recovery for the
            // rest of the process's life.
            coEvery { transportAdmin.reloadInterfaceByName(any()) } throws IllegalStateException("binder died")
            coEvery { interfaceConfigManager.applyInterfaceChanges(any()) } returns Result.success(Unit)
            givenInterfaceAppeared()

            assertEquals(
                AutoInterfaceRecovery.Outcome.RESTARTED,
                recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE),
            )
        }

    @Test
    fun `reports a failed restart rather than claiming success`() =
        runTest {
            coEvery { transportAdmin.reloadInterfaceByName(any()) } returns false
            coEvery {
                interfaceConfigManager.applyInterfaceChanges(any())
            } returns Result.failure(IllegalStateException("service unavailable"))
            givenInterfaceAppeared()

            assertEquals(
                AutoInterfaceRecovery.Outcome.RESTART_FAILED,
                recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE),
            )
        }

    @Test
    fun `does not touch the stack when no AutoInterface is enabled`() =
        runTest {
            // Neither a reload nor a restart would add one, so a restart here would
            // drop every link for nothing. transportAdmin and interfaceConfigManager
            // are left unstubbed on purpose: reaching either throws.
            givenEnabledInterfaces(
                InterfaceConfig.TCPClient(
                    name = "LCS Gateway",
                    targetHost = "gateway.example",
                    targetPort = 4242,
                ),
            )
            givenInterfaceAppeared()

            assertEquals(
                AutoInterfaceRecovery.Outcome.NO_AUTO_INTERFACE,
                recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE),
            )
        }

    @Test
    fun `stands aside while a config apply is already running`() =
        runTest {
            // The user's own "Apply & Restart", onboarding, or the RNode wizard. Two
            // concurrent applies race to clear the shared is_applying_config flag.
            every { configApplyFlagManager.isApplyingConfig() } returns true
            givenInterfaceAppeared()

            assertEquals(
                AutoInterfaceRecovery.Outcome.APPLY_IN_PROGRESS,
                recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE),
            )
        }

    @Test
    fun `flushes the path table before restarting, not after`() =
        runTest {
            // A restart reloads the path table from storage, but only what was last
            // written there — so the flush has to happen before the stack goes down
            // or the restart restores a stale table and the routes learned since the
            // last flush are gone. Order is the whole point, hence the recorder.
            val order = mutableListOf<String>()
            coEvery { transportAdmin.reloadInterfaceByName(any()) } returns false
            coEvery { rnsCore.persistTransportData() } coAnswers { order.add("flush") }
            coEvery { interfaceConfigManager.applyInterfaceChanges(any()) } coAnswers {
                order.add("restart")
                Result.success(Unit)
            }
            givenInterfaceAppeared()

            assertEquals(
                AutoInterfaceRecovery.Outcome.RESTARTED,
                recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE),
            )
            assertEquals(listOf("flush", "restart"), order)
        }

    @Test
    fun `restarts even when the pre-restart flush fails`() =
        runTest {
            // Losing a few recent routes is better than never rebuilding the
            // interface, so a failed flush must not abort the recovery.
            coEvery { transportAdmin.reloadInterfaceByName(any()) } returns false
            coEvery { rnsCore.persistTransportData() } throws IllegalStateException("binder died")
            coEvery { interfaceConfigManager.applyInterfaceChanges(any()) } returns Result.success(Unit)
            givenInterfaceAppeared()

            assertEquals(
                AutoInterfaceRecovery.Outcome.RESTARTED,
                recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE),
            )
        }

    @Test
    fun `does not flush when the live reload succeeds`() =
        runTest {
            // The reload keeps the stack up and carries the paths across in the
            // backend, so there is nothing to protect against here — and a flush
            // walks the whole table under the GIL, which is not free.
            var flushes = 0
            coEvery { transportAdmin.reloadInterfaceByName(any()) } returns true
            coEvery { rnsCore.persistTransportData() } coAnswers { flushes++ }
            givenInterfaceAppeared()

            assertEquals(
                AutoInterfaceRecovery.Outcome.RELOADED,
                recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE),
            )
            assertEquals(0, flushes)
        }

    @Test
    fun `holds off a second attempt inside the attempt cooldown`() =
        runTest {
            coEvery { transportAdmin.reloadInterfaceByName(any()) } returns true
            givenInterfaceAppeared()
            assertEquals(
                AutoInterfaceRecovery.Outcome.RELOADED,
                recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE),
            )

            // A different interface appears immediately — a flapping NIC must not be
            // able to drive a detach/attach loop.
            present = present + "wlan1"
            now += AutoInterfaceRecovery.ATTEMPT_COOLDOWN_MS - 1

            assertEquals(
                AutoInterfaceRecovery.Outcome.COOLDOWN,
                recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE),
            )
        }

    @Test
    fun `attempts again once the attempt cooldown expires`() =
        runTest {
            coEvery { transportAdmin.reloadInterfaceByName(any()) } returns true
            givenInterfaceAppeared()
            recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE)

            present = present + "wlan1"
            now += AutoInterfaceRecovery.ATTEMPT_COOLDOWN_MS

            assertEquals(
                AutoInterfaceRecovery.Outcome.RELOADED,
                recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE),
            )
        }

    @Test
    fun `keeps the long floor between restarts and retries after it`() =
        runTest {
            coEvery { transportAdmin.reloadInterfaceByName(any()) } returns false
            coEvery { interfaceConfigManager.applyInterfaceChanges(any()) } returns Result.success(Unit)
            givenInterfaceAppeared()

            assertEquals(
                AutoInterfaceRecovery.Outcome.RESTARTED,
                recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE),
            )

            // Past the attempt floor but well inside the restart floor. A restart
            // drops BLE, RNode and TCP, so it keeps the longer window.
            now += AutoInterfaceRecovery.ATTEMPT_COOLDOWN_MS
            assertTrue(AutoInterfaceRecovery.ATTEMPT_COOLDOWN_MS < AutoInterfaceRecovery.RESTART_COOLDOWN_MS)
            assertEquals(
                AutoInterfaceRecovery.Outcome.COOLDOWN,
                recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE),
            )

            // The baseline was deliberately left alone while the restart was
            // skipped, so the interface is still reported and the retry happens
            // rather than being lost.
            now += AutoInterfaceRecovery.RESTART_COOLDOWN_MS
            assertEquals(
                AutoInterfaceRecovery.Outcome.RESTARTED,
                recovery.recoverIfNeeded(CurrentTransport.WIFI_LIKE),
            )
        }
}
