package network.libertychat.app.gateway

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** what the entitlement server says about this identity. */
data class GatewayStatus(
    /** none | trial | trial_ended | active | grace | expired */
    val state: String,
    val gatewayOpen: Boolean,
    val trialAvailable: Boolean,
    val trialEnds: Long?,
    val paidThrough: Long?,
    val graceEnds: Long?,
    val autoRenew: Boolean?,
    val accountUrl: String,
    /** This identity's LXMF address, as the server computed it from the signed public key. */
    val lxmfAddress: String?,
    /** e.g. "Family (5 lines)"; null when not on a paid plan. */
    val plan: String? = null,
    val lines: Int? = null,
    /** Which line of the plan this identity holds (family plans). */
    val line: Int? = null,
    /** Cancelled on lcs.network: no more charges; access runs to [paidThrough], then ends. */
    val cancelled: Boolean = false,
) {
    companion object {
        fun from(json: JSONObject) =
            GatewayStatus(
                state = json.optString("state", "none"),
                gatewayOpen = json.optBoolean("gateway_open", false),
                trialAvailable = json.optBoolean("trial_available", false),
                trialEnds = json.optLongOrNull("trial_ends"),
                paidThrough = json.optLongOrNull("paid_through"),
                graceEnds = json.optLongOrNull("grace_ends"),
                autoRenew = if (json.isNull("auto_renew") || !json.has("auto_renew")) null else json.optBoolean("auto_renew"),
                accountUrl = json.optString("account_url", "https://lcs.network/my-account/lcs-global/"),
                lxmfAddress = json.optStringOrNull("lxmf_address"),
                plan = json.optStringOrNull("plan"),
                lines = if (json.has("lines") && !json.isNull("lines")) json.optInt("lines") else null,
                line = if (json.has("line") && !json.isNull("line")) json.optInt("line") else null,
                cancelled = json.optBoolean("cancelled", false),
            )
    }
}

data class GatewaySession(
    val host: String,
    val port: Int,
    val networkName: String?,
    val passphrase: String?,
    val refreshInSeconds: Long,
) {
    companion object {
        fun from(json: JSONObject) =
            GatewaySession(
                host = json.getString("host"),
                port = json.getInt("port"),
                networkName = json.optStringOrNull("network_name"),
                passphrase = json.optStringOrNull("passphrase"),
                refreshInSeconds = json.optLong("refresh_in", 14400),
            )
    }
}

data class GatewayLinkCode(
    val code: String,
    val monthlyUrl: String,
    val yearlyUrl: String,
    val linkDeviceUrl: String,
    /** Append a plan key (monthly, yearly, family2, ...) to get its checkout link. */
    val subscribeUrlPrefix: String?,
)

/** One entry of the plan picker. Names and prices come from lcs.network via the server. */
data class GatewayPlan(
    val plan: String,
    val name: String,
    val lines: Int,
    val label: String,
    val perLine: String,
) {
    companion object {
        fun from(json: JSONObject) =
            GatewayPlan(
                plan = json.getString("plan"),
                name = json.optString("name", json.getString("plan")),
                lines = json.optInt("lines", 1),
                label = json.optString("label"),
                perLine = json.optString("per_line"),
            )
    }
}

internal fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null

internal fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null

/** minimal HTTPS client for the entitlement API (no extra dependencies). */
class GatewayApi(
    private val signer: GatewaySigner,
) {
    sealed class Result {
        data class Ok(
            val json: JSONObject,
        ) : Result()

        /** The server answered with a 4xx; [json] says why (e.g. trial ended -> 402). */
        data class Refused(
            val code: Int,
            val json: JSONObject,
        ) : Result()

        data class Failed(
            val message: String,
        ) : Result()
    }

    companion object {
        private const val TAG = "GatewayApi"
        private const val TIMEOUT_MS = 15_000
    }

    /**
     * Sign [action] with the active identity and POST it to [path].
     * [socks] routes the request the same way the gateway connection goes (Tor/Orbot),
     * so the server opens the firewall for the address the connection will come from.
     */
    suspend fun call(
        path: String,
        action: String,
        socks: InetSocketAddress? = null,
        extra: ((keys: GatewaySigner.Keys, ts: Long, nonce: String) -> Map<String, String>)? = null,
    ): Result =
        withContext(Dispatchers.IO) {
            val keys = signer.activeKeys() ?: return@withContext Result.Failed("No identity is loaded yet.")
            val fields = extra?.let { e -> { ts: Long, nonce: String -> e(keys, ts, nonce) } }
            var result = post(path, signer.sign(keys, action, fields), socks)
            // Phone clock is off: learn the server's time and retry once.
            if (result is Result.Refused && result.json.optString("error") == "clock_skew" && result.json.has("server_time")) {
                signer.clockOffsetSeconds = result.json.getLong("server_time") - System.currentTimeMillis() / 1000
                result = post(path, signer.sign(keys, action, fields), socks)
            }
            keys.signingSeed.fill(0)
            result
        }

    /** The current plans and prices (public, unsigned). Null if the server can't be reached. */
    suspend fun fetchPlans(): List<GatewayPlan>? =
        withContext(Dispatchers.IO) {
            val connection =
                try {
                    URL(GatewayConfig.API_BASE + "/v1/plans").openConnection() as HttpURLConnection
                } catch (e: IOException) {
                    return@withContext null
                }
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                if (connection.responseCode != 200) return@withContext null
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val array = json.getJSONArray("plans")
                (0 until array.length()).map { GatewayPlan.from(array.getJSONObject(it)) }.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                Log.w(TAG, "Plans unavailable: ${e.message}")
                null
            } finally {
                connection.disconnect()
            }
        }

    private fun post(
        path: String,
        signed: GatewaySigner.Signed,
        socks: InetSocketAddress?,
    ): Result {
        val url = URL(GatewayConfig.API_BASE + path)
        if (url.protocol != "https") return Result.Failed("Refusing to send over plain http.")
        val connection =
            try {
                (if (socks != null) url.openConnection(Proxy(Proxy.Type.SOCKS, socks)) else url.openConnection())
                    as HttpURLConnection
            } catch (e: IOException) {
                return Result.Failed(e.message ?: "Could not connect.")
            }
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-LCS-Signature", signed.signature)
            connection.outputStream.use { it.write(signed.body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            when (code) {
                in 200..299 -> Result.Ok(json)
                in 400..499 -> Result.Refused(code, json)
                else -> Result.Failed(json.optString("message").ifEmpty { "Server error $code." })
            }
        } catch (e: IOException) {
            Log.w(TAG, "POST $path failed: ${e.message}")
            Result.Failed(e.message ?: "Network error.")
        } finally {
            connection.disconnect()
        }
    }
}
