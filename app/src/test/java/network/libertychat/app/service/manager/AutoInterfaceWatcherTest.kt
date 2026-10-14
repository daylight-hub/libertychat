package network.libertychat.app.service.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LCS: tests for [AutoInterfaceWatcher]'s snapshot/diff logic.
 *
 * The behaviour that matters is distinguishing "a NIC appeared that RNS cannot
 * see" — the only case upstream AutoInterface genuinely cannot recover from, and
 * the one worth a full stack restart — from ordinary network churn, which RNS's
 * own four-second `peer_jobs` loop repairs on its own. Getting that wrong in
 * either direction is costly: too eager and every WiFi/cellular flip drops all
 * links, too lax and local peers stay invisible until a manual restart.
 */
class AutoInterfaceWatcherTest {
    private lateinit var watcher: AutoInterfaceWatcher
    private var present: Set<String> = emptySet()

    @Before
    fun setup() {
        watcher = AutoInterfaceWatcher()
        watcher.adoptableProvider = { present }
    }

    @Test
    fun `reports nothing before a snapshot is taken`() {
        // Null snapshot means "we don't know what RNS adopted yet". Treating that
        // as an empty set would make every interface look brand new and restart
        // the stack on the first network change after launch.
        present = setOf("wlan0")

        assertTrue(watcher.newlyAppearedInterfaces().isEmpty())
    }

    @Test
    fun `reports an interface that appeared after the snapshot`() {
        // The case this exists for: launched with WiFi off, WiFi enabled later.
        present = emptySet()
        watcher.snapshot()

        present = setOf("wlan0")

        assertEquals(setOf("wlan0"), watcher.newlyAppearedInterfaces())
    }

    @Test
    fun `reports nothing when the interface set is unchanged`() {
        present = setOf("wlan0")
        watcher.snapshot()

        assertTrue(watcher.newlyAppearedInterfaces().isEmpty())
    }

    @Test
    fun `an interface going away is not a reason to rebuild`() {
        // RNS simply stops hearing peers on a NIC that vanished; there is nothing
        // to re-adopt, so a restart would cost every other link for no gain.
        present = setOf("wlan0", "wlan1")
        watcher.snapshot()

        present = setOf("wlan0")

        assertTrue(watcher.newlyAppearedInterfaces().isEmpty())
    }

    @Test
    fun `an interface swapped for a different one counts as new`() {
        // wlan0 disappearing and returning as wlan1 is the rename case — the new
        // name was never adopted, so it does need a rebuild.
        present = setOf("wlan0")
        watcher.snapshot()

        present = setOf("wlan1")

        assertEquals(setOf("wlan1"), watcher.newlyAppearedInterfaces())
    }

    @Test
    fun `re-snapshotting clears a previously reported interface`() {
        // What the caller does after a successful restart: the NIC we rebuilt for
        // must not count as new again on the next transition.
        present = emptySet()
        watcher.snapshot()
        present = setOf("wlan0")
        assertEquals(setOf("wlan0"), watcher.newlyAppearedInterfaces())

        watcher.snapshot()

        assertTrue(watcher.newlyAppearedInterfaces().isEmpty())
    }

    @Test
    fun `reports only the interfaces that are actually new`() {
        present = setOf("wlan0")
        watcher.snapshot()

        present = setOf("wlan0", "p2p0", "eth0")

        assertEquals(setOf("p2p0", "eth0"), watcher.newlyAppearedInterfaces())
    }

    // ========== isAdoptable: the filter that decides whether we restart ==========

    @Test
    fun `a normal wifi interface is adoptable`() {
        assertTrue(adoptable("wlan0"))
    }

    @Test
    fun `an interface without an IPv6 link-local address is not adoptable`() {
        // AutoInterface peers over fe80: multicast and skips anything without one,
        // so an interface lacking it can never carry local peers.
        assertFalse(adoptable("wlan0", hasLinkLocalIpv6 = false))
    }

    @Test
    fun `a down or loopback interface is not adoptable`() {
        assertFalse(adoptable("wlan0", isUp = false))
        assertFalse(adoptable("lo", isLoopback = true))
    }

    @Test
    fun `upstream's android ignore list is honoured`() {
        // Verbatim from AutoInterface.ANDROID_IGNORE_IFS + ALL_IGNORE_IFS.
        listOf("dummy0", "lo", "lo0", "tun0", "rmnet0", "rmnet7").forEach { name ->
            assertFalse("$name should be ignored", adoptable(name))
        }
    }

    @Test
    fun `modern cellular interface names are ignored even though upstream misses them`() {
        // The regression this guards: upstream's list stops at literal rmnet0..7,
        // but Qualcomm devices name cellular rmnet_dataN and MediaTek ccmniN, and
        // extra PDNs bring more up at runtime. Matching upstream exactly would make
        // every cellular transition look like a new interface and restart the stack.
        listOf("rmnet_data0", "rmnet_data7", "r_rmnet_data0", "ccmni0", "ccmni3", "clat4")
            .forEach { name -> assertFalse("$name should be ignored", adoptable(name)) }
    }

    @Test
    fun `vpn interfaces beyond tun0 are ignored`() {
        // One VPN connect would otherwise cost a full stack restart.
        listOf("wg0", "tun1", "tun2").forEach { name ->
            assertFalse("$name should be ignored", adoptable(name))
        }
    }

    @Test
    fun `interfaces that can carry local peers are still adoptable`() {
        // The exclusions must not be so broad that they swallow the real cases:
        // ethernet over a dock, WiFi Direct, and a tethering hotspot.
        listOf("eth0", "p2p0", "ap0", "swlan0", "wlan1").forEach { name ->
            assertTrue("$name should be adoptable", adoptable(name))
        }
    }

    private fun adoptable(
        name: String,
        isUp: Boolean = true,
        isLoopback: Boolean = false,
        hasLinkLocalIpv6: Boolean = true,
    ): Boolean = watcher.isAdoptable(name, isUp, isLoopback, hasLinkLocalIpv6)
}
