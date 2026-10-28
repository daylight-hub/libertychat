package network.libertychat.app.gateway

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * hosts a hand-made interface may never point at.
 *
 * The LCS machines carry the paid LCS Global gateway (and the entitlement API). A custom
 * TCP Client aimed at them — typed, imported from a config file, restored from a backup or
 * picked from discovered interfaces — is refused when saved and switched off when the
 * Reticulum config is built. The server's own lock (per-session firewall plus the rotating
 * passphrase) is what actually keeps non-subscribers out; this keeps the app from being
 * the tool that probes for it, and keeps these names out of the app's interface list.
 *
 * Covers the names themselves, any sub-name of them, and the IP addresses they resolve
 * to (looked up in the background and remembered), so typing the raw IP doesn't help.
 */
object ReservedHosts {
    private const val TAG = "ReservedHosts"
    private const val PREF_IPS = "reserved_ips"

    /** Hostnames (and everything ending in ".<name>") that custom interfaces can't use. */
    val NAMES = listOf("public.lcs.network", "server.lcs.network", "start.lcs.network", "update.lcs.network")

    const val MESSAGE =
        "This server is reserved for ${GatewayConfig.PRODUCT_NAME}. Add ${GatewayConfig.PRODUCT_NAME} " +
            "from the Add Interface menu instead."

    @Volatile
    private var knownIps: Set<String> = emptySet()

    /** Lower-case host with any scheme, path, port, brackets and trailing dot removed. */
    fun normalise(host: String?): String {
        var h = host.orEmpty().trim().lowercase()
        h = h.removePrefix("http://").removePrefix("https://").substringBefore('/')
        if (h.startsWith("[")) {
            h = h.substringAfter('[').substringBefore(']') // [v6]:port
        } else if (h.count { it == ':' } == 1) {
            h = h.substringBefore(':') // name:port
        }
        return h.trimEnd('.')
    }

    /** True if [host] is (or is under) a reserved name, or is one of their known addresses. */
    fun isReserved(host: String?): Boolean {
        val h = normalise(host)
        if (h.isEmpty()) return false
        if (NAMES.any { h == it || h.endsWith(".$it") }) return true
        return h in knownIps || canonicalIp(h)?.let { it in knownIps } == true
    }

    /** Also checks what [host] resolves to now. Call off the main thread (it may do DNS). */
    suspend fun isReservedResolved(host: String?): Boolean {
        if (isReserved(host)) return true
        val h = normalise(host)
        if (h.isEmpty() || h.endsWith(".onion") || knownIps.isEmpty()) return false
        return withContext(Dispatchers.IO) {
            runCatching { InetAddress.getAllByName(h).any { it.hostAddress?.let(::canonicalIp) in knownIps } }
                .getOrDefault(false)
        }
    }

    /** Load remembered addresses, then refresh them in the background. */
    suspend fun refresh(context: Context) {
        val prefs = context.getSharedPreferences(GatewayConfig.PREFS, Context.MODE_PRIVATE)
        knownIps = prefs.getStringSet(PREF_IPS, emptySet()).orEmpty()
        val fresh =
            withContext(Dispatchers.IO) {
                NAMES.flatMap { name ->
                    runCatching { InetAddress.getAllByName(name).mapNotNull { it.hostAddress?.let(::canonicalIp) } }
                        .getOrDefault(emptyList())
                }.toSet()
            }
        if (fresh.isNotEmpty()) {
            // Keep earlier addresses too, so a DNS change doesn't reopen an old one.
            knownIps = knownIps + fresh
            prefs.edit().putStringSet(PREF_IPS, knownIps).apply()
            Log.d(TAG, "Reserved addresses: ${knownIps.size}")
        }
    }

    private fun canonicalIp(text: String): String? {
        val t = text.substringBefore('%')
        val looksLikeIp = t.matches(Regex("^[0-9.]+$")) || (t.contains(':') && t.matches(Regex("^[0-9a-f:.]+$")))
        if (!looksLikeIp) return null
        // Literal parse only (no DNS for a literal address).
        return runCatching { InetAddress.getByName(t).hostAddress?.substringBefore('%') }.getOrNull()
    }
}
