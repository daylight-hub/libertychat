package network.libertychat.app.service.manager

import android.util.Log
import androidx.annotation.VisibleForTesting
import java.net.Inet6Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LCS: notices when a network interface appears that RNS's AutoInterface cannot
 * see, so the backend can be restarted to pick it up.
 *
 * ## Why this is needed
 *
 * Upstream `AutoInterface` decides which NICs to use exactly once, in its
 * constructor: it enumerates the interfaces, picks each one's `fe80:` link-local
 * address and stores them in `adopted_interfaces`. Nothing ever adds to that map
 * afterwards — neither `final_init` nor the `peer_jobs` loop, both of which only
 * iterate the keys the constructor put there.
 *
 * What `peer_jobs` *does* handle, every four seconds, is a **changed** link-local
 * address on an interface it already adopted: it swaps the address, closes the old
 * UDP listener and binds a new one. So ordinary address churn — a reconnect, an AP
 * roam, a new `fe80:` on the same `wlan0` — recovers on its own and must NOT
 * trigger a restart here.
 *
 * The case upstream cannot recover from is a NIC that did not exist when RNS
 * started: launch with WiFi off and enable it later, or have `wlan0` disappear and
 * return under a different name. AutoInterface never adopts it, and local peers
 * stay invisible until the stack is rebuilt. `ReticulumService`'s network-change
 * callback was written to fix precisely this — its comment says so — but the
 * `binder.restartAutoInterface()` it called has always been `= Unit`.
 *
 * ## What this does
 *
 * Takes a snapshot of the adoptable interfaces whenever the backend starts, and
 * reports any that have appeared since. Detection is deliberately all on the Java
 * side: it needs no new IPC surface and no reaching into RNS internals, and the
 * recovery it triggers is the same `applyInterfaceChanges()` restart that the
 * Settings "Apply & Restart" button already performs in production.
 *
 * The filter deliberately errs on the strict side of AutoInterface's own rules —
 * see [isAdoptable] — because a false positive costs a full stack restart while a
 * false negative only costs a recovery on an interface that carries no local peers.
 */
@Singleton
class AutoInterfaceWatcher
    @Inject
    constructor() {
        companion object {
            private const val TAG = "AutoInterfaceWatcher"

            /**
             * Interface names RNS's `AutoInterface` skips on Android: its
             * `ANDROID_IGNORE_IFS` plus `ALL_IGNORE_IFS`, verbatim.
             */
            private val IGNORED_INTERFACES =
                setOf(
                    "dummy0", "lo", "lo0", "tun0",
                    "rmnet0", "rmnet1", "rmnet2", "rmnet3",
                    "rmnet4", "rmnet5", "rmnet6", "rmnet7",
                )

            /**
             * Prefixes we skip that upstream does not, because its list is years out
             * of date and matching it exactly would make us restart the stack for
             * nothing.
             *
             * `ANDROID_IGNORE_IFS` names cellular as literal `rmnet0`..`rmnet7`.
             * Modern Qualcomm devices call it `rmnet_data0`..`rmnet_dataN` (and
             * `r_rmnet_data*`), MediaTek calls it `ccmni0`..`ccmniN`, and extra PDNs
             * for IMS, MMS, SUPL and tethering bring more of them up at runtime —
             * all up, all carrying an `fe80:` address, none on upstream's list. VPNs
             * are the same story beyond `tun0`: WireGuard uses `wg0`, others `tun1`
             * upwards. `clat*` is the 464XLAT translation interface.
             *
             * None of these ever carry AutoInterface peers, so being stricter than
             * upstream costs nothing — the only thing we lose is a recovery we would
             * not have wanted.
             */
            private val IGNORED_PREFIXES =
                listOf("rmnet", "r_rmnet", "ccmni", "clat", "wg", "tun")
        }

        /**
         * Adoptable interfaces as of the last [snapshot]. Null until the first one,
         * which means "we do not know yet" and suppresses any restart — important,
         * because an empty set would otherwise make the first network change look
         * like every interface was brand new.
         */
        @Volatile
        private var lastSnapshot: Set<String>? = null

        /**
         * Seam for tests. Enumerating real NICs needs a device, so the snapshot and
         * diff logic — which is where the behaviour actually lives — is exercised by
         * substituting this.
         */
        @VisibleForTesting
        internal var adoptableProvider: () -> Set<String> = { enumerateAdoptable() }

        /**
         * Record the interfaces RNS can be assumed to have adopted. Call this right
         * after the backend finishes initializing, and again after any restart.
         */
        fun snapshot() {
            val current = adoptableProvider()
            lastSnapshot = current
            Log.d(TAG, "Snapshot of adoptable interfaces: $current")
        }

        /**
         * Interfaces that have appeared since [snapshot] and that AutoInterface
         * therefore cannot know about. Empty when nothing new appeared, when the
         * snapshot has not been taken yet, or when interfaces only went away —
         * losing one needs no rebuild, since RNS will simply stop hearing peers on it.
         */
        fun newlyAppearedInterfaces(): Set<String> {
            val previous = lastSnapshot ?: return emptySet()
            val current = adoptableProvider()
            return current - previous
        }

        /**
         * Whether an interface with these properties is one AutoInterface would use.
         *
         * The only hard requirement upstream imposes is an `fe80:` address — it
         * enumerates with `include_unconfigured=True` and never checks whether the
         * link is up. We additionally require up and non-loopback, and skip the
         * cellular/VPN families in [IGNORED_PREFIXES]. Both make us *stricter* than
         * upstream, which is the safe direction: we can miss a recovery, but we
         * cannot restart the stack for an interface it would never have adopted.
         */
        @VisibleForTesting
        internal fun isAdoptable(
            name: String,
            isUp: Boolean,
            isLoopback: Boolean,
            hasLinkLocalIpv6: Boolean,
        ): Boolean {
            if (!isUp || isLoopback || !hasLinkLocalIpv6) return false
            if (name in IGNORED_INTERFACES) return false
            return IGNORED_PREFIXES.none { name.startsWith(it) }
        }

        private fun enumerateAdoptable(): Set<String> =
            try {
                NetworkInterface
                    .getNetworkInterfaces()
                    ?.asSequence()
                    ?.filter { nic ->
                        val flags = runCatching { nic.isUp to nic.isLoopback }.getOrNull()
                        flags != null &&
                            isAdoptable(
                                name = nic.name,
                                isUp = flags.first,
                                isLoopback = flags.second,
                                hasLinkLocalIpv6 = nic.hasLinkLocalIpv6(),
                            )
                    }?.map { it.name }
                    ?.toSet()
                    ?: emptySet()
            } catch (e: Exception) {
                // getNetworkInterfaces throws SocketException on some devices, and
                // reading a NIC that disappears mid-enumeration can throw too. An
                // empty result is the safe answer: it reports nothing new, so the
                // worst case is that we miss one change rather than restart in a loop.
                Log.w(TAG, "Could not enumerate network interfaces", e)
                emptySet()
            }

        private fun NetworkInterface.hasLinkLocalIpv6(): Boolean =
            runCatching {
                // Inet6Address rather than a 16-byte length check: IPv4 link-local
                // (169.254/16) also reports isLinkLocalAddress, and this avoids
                // copying the address bytes for every address on every interface.
                inetAddresses.asSequence().any { it is Inet6Address && it.isLinkLocalAddress }
            }.getOrDefault(false)
    }
