package network.libertychat.app.data.model

/**
 * Represents a community TCP server for Reticulum networking.
 *
 * @param name User-friendly name for the server
 * @param host Hostname or IP address
 * @param port TCP port number
 * @param isBootstrap When true, this server is recommended as a bootstrap interface.
 *                    Bootstrap interfaces auto-detach once sufficient discovered
 *                    interfaces are connected (RNS 1.1.0+ feature).
 * @param note LCS: optional one-line hint shown under the host:port in the server
 *             picker, for entries whose purpose isn't obvious from the name.
 */
data class TcpCommunityServer(
    val name: String,
    val host: String,
    val port: Int,
    val isBootstrap: Boolean = false,
    val note: String? = null,
)

/**
 * List of known community TCP servers for Reticulum.
 *
 * Selected servers are marked as bootstrap candidates based on:
 * - Reputation in the community
 * - Long-term reliability
 * - Geographic distribution
 */
object TcpCommunityServers {
    val servers: List<TcpCommunityServer> =
        listOf(
            // LCS: official Liberty Communication Systems public node (bootstrap interface)
            TcpCommunityServer("LCS Public Node", "public.lcs.network", 4245, isBootstrap = true),
            // LCS: local-network RNode — bootstrap interface OFF so it stays connected
            TcpCommunityServer("Local IP RNode", "iprnode.local", 4545, isBootstrap = false),
            // LCS: direct TCP path to a Command Center on the local network, for when
            // AutoInterface can't find it (some routers drop the multicast discovery
            // traffic AutoInterface relies on, and a few carrier/guest networks block
            // peer-to-peer traffic outright). Bootstrap OFF so it stays connected.
            TcpCommunityServer(
                "Command Center PRO Client",
                "liberty.local",
                4246,
                isBootstrap = false,
                note = "Use if Local WiFi (AutoInterface) isn't finding your Command Center",
            ),
        )

    /**
     * LCS: the local-network Command Center entry, offered during onboarding as an
     * alternative to AutoInterface. Kept as a lookup rather than an index so the
     * onboarding default (`servers.first()`, the LCS public node) is unaffected by
     * the order of this list.
     */
    val commandCenter: TcpCommunityServer?
        get() = servers.firstOrNull { it.host == "liberty.local" && it.port == 4246 }

    /**
     * Get only servers marked as bootstrap candidates.
     */
    val bootstrapServers: List<TcpCommunityServer>
        get() = servers.filter { it.isBootstrap }
}
