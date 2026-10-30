package network.libertychat.app.gateway

import network.libertychat.app.rns.api.model.InterfaceConfig

/**
 * constants for the paid LCS Global service (the internet gateway to the Liberty network).
 *
 * Deliberately absent: the gateway's address and its Reticulum passphrase. Both
 * arrive from the entitlement server only after this identity proves (by
 * signature) that it is in trial or paid up, and the passphrase rotates monthly,
 * so there is nothing in the APK worth extracting.
 */
object GatewayConfig {
    /** Entitlement API. HTTPS only: requests are refused over plain http. */
    const val API_BASE = "https://update.lcs.network"

    /**
     * What customers see everywhere in the app. Change it here (and LCS_PRODUCT_NAME on the
     * server, LCS_GW_PRODUCT_NAME on lcs.network) to rename the service.
     */
    const val PRODUCT_NAME = "LCS Global"

    /** Name of the managed interface row. The manager finds it by this name (or a former one). */
    const val INTERFACE_NAME = PRODUCT_NAME

    /** Earlier names of the managed interface, so a rename never orphans an existing row. */
    val FORMER_INTERFACE_NAMES = setOf("LCS Gateway")

    /** Interfaces menu entry type (not a real Reticulum interface type). */
    const val MENU_TYPE = "LCSGlobal"

    /**
     * Placeholder target until the first session response replaces it. `.invalid`
     * is reserved (RFC 2606), so it can never resolve to a real machine.
     */
    const val PLACEHOLDER_HOST = "gateway.lcs.invalid"
    const val PLACEHOLDER_PORT = 4245

    /**
     * Shown only if the app has never been able to fetch the live plan list. Real prices
     * come from the WooCommerce product on lcs.network (via the server's /v1/plans), so
     * changing a price there updates the app without a new release.
     */
    val FALLBACK_PLANS =
        listOf(
            GatewayPlan("monthly", "Monthly", 1, "$5/month", ""),
            GatewayPlan("yearly", "Yearly", 1, "$49/year", ""),
            GatewayPlan("family2-monthly", "Family (2 lines), monthly", 2, "$6/month", "$3/month per line"),
            GatewayPlan("family2", "Family (2 lines), yearly", 2, "$59/year", "$29.50/year per line"),
            GatewayPlan("family5-monthly", "Family (5 lines), monthly", 5, "$10/month", "$2/month per line"),
            GatewayPlan("family5", "Family (5 lines), yearly", 5, "$99/year", "$19.80/year per line"),
        )
    const val TRIAL_DAYS = 7

    internal const val PREFS = "lcs_gateway"
    internal const val DEVICE_SALT = "lcs-gw-v1:"

    /** How long to wait between cycles when nothing tells us otherwise. */
    internal const val STATUS_INTERVAL_MS = 6 * 60 * 60 * 1000L
    internal const val RETRY_INTERVAL_MS = 15 * 60 * 1000L
    internal const val MIN_INTERVAL_MS = 5 * 60 * 1000L
    internal const val MIN_KICK_GAP_MS = 30 * 1000L

    /**
     * Tor: LCS Global can be reached through Orbot's SOCKS proxy. When the server has an onion
     * address, a Tor session gets it (sent only to subscribers, like the host and passphrase), so
     * the connection never leaves the Tor network. Without one it goes out through a Tor exit.
     */
    const val ORBOT_PACKAGE = "org.torproject.android"
    const val ORBOT_DOWNLOAD_URL = "https://orbot.app/en/download/"
    const val ORBOT_RELEASES_URL = "https://github.com/guardianproject/orbot-android/releases"
    const val TOR_SOCKS_HOST = "127.0.0.1"
    const val TOR_SOCKS_PORT = 9050

    fun isGateway(interfaceName: String?): Boolean =
        interfaceName == INTERFACE_NAME || interfaceName in FORMER_INTERFACE_NAMES

    /** The interface onboarding / Add Interface create; disabled until a session fills it in. */
    fun placeholderInterface(useTor: Boolean = false): InterfaceConfig.TCPClient =
        InterfaceConfig.TCPClient(
            name = INTERFACE_NAME,
            enabled = false,
            targetHost = PLACEHOLDER_HOST,
            targetPort = PLACEHOLDER_PORT,
            mode = "full",
            // Never a bootstrap-only connection, and not offered as a choice.
            bootstrapOnly = false,
            socksProxyEnabled = useTor,
            socksProxyHost = TOR_SOCKS_HOST,
            socksProxyPort = TOR_SOCKS_PORT,
        )
}
