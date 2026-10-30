package network.libertychat.app.gateway

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import network.libertychat.app.data.database.entity.InterfaceEntity
import network.libertychat.app.data.repository.IdentityRepository
import network.libertychat.app.repository.InterfaceRepository
import network.libertychat.app.rns.api.model.InterfaceConfig
import network.libertychat.app.rns.api.model.toJsonString
import network.libertychat.app.service.InterfaceConfigManager
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * keeps LCS Global interface in step with the user's trial/subscription.
 *
 * Every cycle (every ~4 h, on network change, when the app comes to the front, and
 * when the gateway interface is added): ask the server for this identity's status,
 * start the free trial if the user chose it, then either open a gateway session
 * (the server opens its firewall for our IP and hands back host, port and the
 * current passphrase) or switch the interface off when access has ended.
 */
@Singleton
@Suppress("TooManyFunctions")
class GatewayAccessManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val signer: GatewaySigner,
        private val interfaceRepository: InterfaceRepository,
        private val configManager: InterfaceConfigManager,
        private val identityRepository: IdentityRepository,
    ) {
        companion object {
            private const val TAG = "GatewayAccess"
            private const val PREF_AUTO_TRIAL = "auto_trial"
            private const val PREF_PLANS = "plans_json"
            private const val PREF_USE_TOR = "use_tor"
        }

        /** Gateway access found on the identity the user just switched away from. */
        data class TransferOffer(
            val fromIdentityHash: String,
            val fromName: String,
        )

        data class UiState(
            val identityHash: String? = null,
            val status: GatewayStatus? = null,
            val hasGatewayInterface: Boolean = false,
            val busy: Boolean = false,
            val message: String? = null,
            val transferOffer: TransferOffer? = null,
        )

        private val api = GatewayApi(signer)
        private val prefs = context.getSharedPreferences(GatewayConfig.PREFS, Context.MODE_PRIVATE)
        private val cycleLock = Mutex()
        private val kicks = Channel<Unit>(Channel.CONFLATED)
        private val _state = MutableStateFlow(UiState())
        val state: StateFlow<UiState> = _state.asStateFlow()

        private val _useTor = MutableStateFlow(prefs.getBoolean(PREF_USE_TOR, false))

        /** "Connect through Tor (Orbot)" for LCS Global. */
        val useTor: StateFlow<Boolean> = _useTor.asStateFlow()

        @Volatile
        private var started = false

        @Volatile
        private var lastCycleAt = 0L

        // ------------------------------------------------------------------ lifecycle

        /** Called once from ColumbaApplication in the main process. */
        fun start(appScope: CoroutineScope) {
            if (started) return
            started = true

            appScope.launch { ReservedHosts.refresh(context) }
            appScope.launch {
                adoptLegacyPublicNode()
                interfaceRepository.allInterfaceEntities
                    .map { list -> list.any { isGatewayEntity(it) } }
                    .distinctUntilChanged()
                    .collect { present ->
                        _state.update { it.copy(hasGatewayInterface = present) }
                        if (present) kick(force = true)
                    }
            }
            appScope.launch {
                identityRepository.activeIdentity
                    .map { it?.identityHash }
                    .distinctUntilChanged()
                    .collect { hash ->
                        val previous = _state.value
                        val hadAccess = previous.status?.state?.let { it in setOf("trial", "active", "grace") } == true
                        val offer =
                            if (previous.identityHash != null && hash != null && previous.identityHash != hash && hadAccess) {
                                val name = identityRepository.getIdentity(previous.identityHash)?.displayName ?: "your previous identity"
                                TransferOffer(previous.identityHash, name)
                            } else {
                                previous.transferOffer?.takeIf { it.fromIdentityHash != hash }
                            }
                        _state.update { it.copy(identityHash = hash, status = null, transferOffer = offer) }
                        kick(force = true)
                    }
            }
            registerNetworkCallback()
            appScope.launch {
                while (isActive) {
                    val waitMs = runCatching { cycle() }.getOrElse {
                        Log.e(TAG, "Gateway cycle failed", it)
                        GatewayConfig.RETRY_INTERVAL_MS
                    }
                    withTimeoutOrNull(waitMs) { kicks.receive() }
                }
            }
        }

        /** Ask for a cycle soon (e.g. app resumed after paying in the browser). */
        fun kick(force: Boolean = false) {
            if (force || SystemClock.elapsedRealtime() - lastCycleAt > GatewayConfig.MIN_KICK_GAP_MS) {
                kicks.trySend(Unit)
            }
        }

        private fun registerNetworkCallback() {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            try {
                cm.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        // A new network usually means a new public IP: the firewall must be reopened for it.
                        override fun onAvailable(network: Network) = kick(force = true)

                        // Same network, new addresses (carrier re-assignment, VPN reconnect).
                        override fun onLinkPropertiesChanged(
                            network: Network,
                            linkProperties: android.net.LinkProperties,
                        ) = kick()
                    },
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "No network callback: ${e.message}")
            }
        }

        // ------------------------------------------------------------------ the cycle

        /**
         * One round. The common case (already in trial or paid) is a single request: the
         * session call both checks access and reopens the firewall for our current IP, so a
         * switch between LTE and WiFi costs one round trip. Returns how long to wait next.
         */
        private suspend fun cycle(): Long {
            cycleLock.withLock {
                lastCycleAt = SystemClock.elapsedRealtime()
                val entity = gatewayEntity() ?: return GatewayConfig.STATUS_INTERVAL_MS

                var result = openSession(entity)
                if (result is GatewayApi.Result.Refused && result.code == 402) {
                    val status = GatewayStatus.from(result.json)
                    if (status.state == "none" && status.trialAvailable && prefs.getBoolean(PREF_AUTO_TRIAL, true)) {
                        if (requestTrial()?.gatewayOpen == true) result = openSession(entity)
                    }
                }
                return when (result) {
                    is GatewayApi.Result.Ok -> {
                        val session = GatewaySession.from(result.json)
                        applySession(entity, session)
                        (session.refreshInSeconds * 1000).coerceIn(
                            GatewayConfig.MIN_INTERVAL_MS,
                            GatewayConfig.STATUS_INTERVAL_MS,
                        )
                    }
                    is GatewayApi.Result.Refused -> {
                        if (result.code == 402) {
                            GatewaySecrets.put(null) // access ended: forget the passphrase too
                            setGatewayEnabled(entity, false)
                        }
                        GatewayConfig.STATUS_INTERVAL_MS
                    }
                    is GatewayApi.Result.Failed -> {
                        Log.w(TAG, "Session failed: ${result.message}")
                        GatewayConfig.RETRY_INTERVAL_MS
                    }
                }
            }
        }

        private suspend fun openSession(entity: InterfaceEntity): GatewayApi.Result =
            api.call("/v1/gateway/session", "session", socksFor(entity)) { _, _, _ ->
                // Over Tor the server answers with its onion address (if it has one) instead
                // of opening its firewall for a Tor exit's address.
                if (_useTor.value) mapOf("via" to "tor") else emptyMap()
            }.also { r ->
                val json = (r as? GatewayApi.Result.Ok)?.json ?: (r as? GatewayApi.Result.Refused)?.json
                if (json != null && json.has("state")) _state.update { it.copy(status = GatewayStatus.from(json)) }
            }

        private suspend fun fetchStatus(): GatewayStatus? =
            when (val r = api.call("/v1/status", "status", socksFor(gatewayEntity()))) {
                is GatewayApi.Result.Ok -> GatewayStatus.from(r.json).also { s -> _state.update { it.copy(status = s) } }
                is GatewayApi.Result.Refused -> {
                    _state.update { it.copy(message = r.json.optString("message").ifEmpty { null }) }
                    null
                }
                is GatewayApi.Result.Failed -> null
            }

        private suspend fun requestTrial(): GatewayStatus? =
            when (val r = api.call("/v1/trial/start", "trial_start", socksFor(gatewayEntity()))) {
                is GatewayApi.Result.Ok -> GatewayStatus.from(r.json)
                is GatewayApi.Result.Refused -> {
                    _state.update { it.copy(message = r.json.optString("message").ifEmpty { null }) }
                    if (r.code == 409) {
                        // e.g. "a free trial was already used on this device": don't keep asking.
                        prefs.edit().putBoolean(PREF_AUTO_TRIAL, false).apply()
                        GatewayStatus.from(r.json)
                    } else {
                        null
                    }
                }
                is GatewayApi.Result.Failed -> null
            }.also { s -> if (s != null) _state.update { it.copy(status = s) } }

        // ------------------------------------------------------------------ interface upkeep

        private fun isGatewayEntity(e: InterfaceEntity) = e.type == "TCPClient" && GatewayConfig.isGateway(e.name)

        private suspend fun gatewayEntity(): InterfaceEntity? =
            interfaceRepository.allInterfaceEntities.first().firstOrNull { isGatewayEntity(it) }

        /** Requests go the same way as the gateway connection: through Orbot when Tor is on. */
        @Suppress("UNUSED_PARAMETER")
        private fun socksFor(entity: InterfaceEntity?): InetSocketAddress? =
            if (_useTor.value) {
                InetSocketAddress.createUnresolved(GatewayConfig.TOR_SOCKS_HOST, GatewayConfig.TOR_SOCKS_PORT)
            } else {
                null
            }

        /**
         * Turn Tor on or off for LCS Global. The stored session is dropped so the next one
         * matches (over Tor the server may hand out its onion address instead of the host).
         */
        suspend fun setUseTor(enabled: Boolean) {
            if (_useTor.value == enabled) return
            _useTor.value = enabled
            prefs.edit().putBoolean(PREF_USE_TOR, enabled).apply()
            val entity = gatewayEntity()
            if (entity != null) {
                val current = runCatching { interfaceRepository.entityToConfig(entity) }.getOrNull() as? InterfaceConfig.TCPClient
                val stored =
                    (current ?: GatewayConfig.placeholderInterface()).copy(
                        targetHost = GatewayConfig.PLACEHOLDER_HOST,
                        targetPort = GatewayConfig.PLACEHOLDER_PORT,
                        networkName = null,
                        passphrase = null,
                        bootstrapOnly = false,
                        socksProxyEnabled = enabled,
                        socksProxyHost = GatewayConfig.TOR_SOCKS_HOST,
                        socksProxyPort = GatewayConfig.TOR_SOCKS_PORT,
                    )
                interfaceRepository.updateInterfaceEntity(entity.copy(configJson = stored.toJsonString()))
            }
            GatewaySecrets.put(null)
            kick(force = true)
        }

        /** Is Orbot installed on this phone? */
        fun isOrbotInstalled(): Boolean =
            runCatching { context.packageManager.getPackageInfo(GatewayConfig.ORBOT_PACKAGE, 0) }.isSuccess

        private suspend fun applySession(
            entity: InterfaceEntity,
            session: GatewaySession,
        ) {
            val live = GatewaySecrets.Session(session.host, session.port, session.networkName, session.passphrase)
            val current = runCatching { interfaceRepository.entityToConfig(entity) }.getOrNull() as? InterfaceConfig.TCPClient
            // The database row keeps only a placeholder: it's included in backups and exports.
            val stored =
                (current ?: GatewayConfig.placeholderInterface()).copy(
                    name = GatewayConfig.INTERFACE_NAME,
                    enabled = true,
                    targetHost = GatewayConfig.PLACEHOLDER_HOST,
                    targetPort = GatewayConfig.PLACEHOLDER_PORT,
                    networkName = null,
                    passphrase = null,
                    bootstrapOnly = false,
                    socksProxyEnabled = _useTor.value,
                    socksProxyHost = GatewayConfig.TOR_SOCKS_HOST,
                    socksProxyPort = GatewayConfig.TOR_SOCKS_PORT,
                ).toJsonString()
            val rowChanged = !entity.enabled || entity.configJson != stored || entity.name != GatewayConfig.INTERFACE_NAME
            if (GatewaySecrets.get() == live && !rowChanged) return // firewall renewed; nothing to reconfigure
            Log.i(TAG, "Gateway settings changed; reloading interfaces")
            GatewaySecrets.put(live)
            if (rowChanged) {
                interfaceRepository.updateInterfaceEntity(
                    entity.copy(name = GatewayConfig.INTERFACE_NAME, enabled = true, configJson = stored),
                )
            }
            configManager.applyInterfaceChanges()
        }

        private suspend fun setGatewayEnabled(
            entity: InterfaceEntity,
            enabled: Boolean,
        ) {
            if (entity.enabled == enabled) return
            interfaceRepository.toggleInterfaceEnabled(entity.id, enabled)
            configManager.applyInterfaceChanges()
        }

        /**
         * Installs from before the subscription added the free node by hand ("LCS Public
         * Node", public.lcs.network). Turn that row into the managed gateway so those users
         * move onto the trial automatically instead of silently losing the connection at
         * cut-over. Runs once.
         */
        private suspend fun adoptLegacyPublicNode() {
            // A row saved under a former product name takes the current one.
            interfaceRepository.allInterfaceEntities.first()
                .filter { isGatewayEntity(it) && it.name != GatewayConfig.INTERFACE_NAME }
                .forEach { interfaceRepository.updateInterfaceEntity(it.copy(name = GatewayConfig.INTERFACE_NAME)) }
            if (prefs.getBoolean("legacy_adopted", false)) return
            val entities = interfaceRepository.allInterfaceEntities.first()
            if (entities.none { isGatewayEntity(it) }) {
                val legacy =
                    entities.firstOrNull { e ->
                        e.type == "TCPClient" &&
                            runCatching { org.json.JSONObject(e.configJson).optString("target_host") }
                                .getOrDefault("") == "public.lcs.network"
                    }
                if (legacy != null) {
                    // Only the name changes, so it keeps working exactly as before until the
                    // first session response supplies the passphrase-protected settings.
                    Log.i(TAG, "Adopting the legacy public node row as the managed gateway")
                    val config = runCatching { interfaceRepository.entityToConfig(legacy) }.getOrNull() as? InterfaceConfig.TCPClient
                    val renamed = (config ?: GatewayConfig.placeholderInterface()).copy(
                        name = GatewayConfig.INTERFACE_NAME,
                        enabled = legacy.enabled,
                        bootstrapOnly = false,
                    )
                    interfaceRepository.updateInterfaceEntity(
                        legacy.copy(name = GatewayConfig.INTERFACE_NAME, configJson = renamed.toJsonString()),
                    )
                }
            }
            prefs.edit().putBoolean("legacy_adopted", true).apply()
        }

        /** Create the gateway interface if it doesn't exist yet. */
        suspend fun ensureGatewayInterface() {
            if (gatewayEntity() == null) {
                interfaceRepository.insertInterface(GatewayConfig.placeholderInterface(useTor = _useTor.value))
            }
        }

        // ------------------------------------------------------------------ user actions

        suspend fun refreshStatus(): GatewayStatus? = cycleLock.withLock { fetchStatus() }

        /** "Start 7-day free trial". */
        suspend fun startTrial(): Boolean {
            _state.update { it.copy(busy = true, message = null) }
            prefs.edit().putBoolean(PREF_AUTO_TRIAL, true).apply()
            ensureGatewayInterface()
            val status = cycleLock.withLock { requestTrial() }
            _state.update { it.copy(busy = false) }
            kick(force = true)
            return status?.gatewayOpen == true
        }

        /** Subscribe in the browser on lcs.network. The card form never lives in this app. */
        /** Checkout for [plan] (a key from the plan list: monthly, yearly, family2, ...) on lcs.network. */
        suspend fun openSubscribe(
            activityContext: Context,
            plan: String,
        ): Boolean {
            prefs.edit().putBoolean(PREF_AUTO_TRIAL, false).apply()
            ensureGatewayInterface()
            val link = linkCode() ?: return false
            val url =
                link.subscribeUrlPrefix?.let { it + plan }
                    ?: if (plan == "monthly") link.monthlyUrl else link.yearlyUrl
            return openBrowser(activityContext, url)
        }

        /** Onboarding's "Start free trial": the trial starts as soon as the interface exists. */
        fun setAutoTrial(enabled: Boolean) = prefs.edit().putBoolean(PREF_AUTO_TRIAL, enabled).apply()

        // ------------------------------------------------------------------ plans

        private val _plans = MutableStateFlow(loadCachedPlans() ?: GatewayConfig.FALLBACK_PLANS)

        /** The plan picker's entries: live from lcs.network, else last known, else built-in. */
        val plans: StateFlow<List<GatewayPlan>> = _plans.asStateFlow()

        suspend fun refreshPlans() {
            val fresh = api.fetchPlans() ?: return
            _plans.value = fresh
            val array = org.json.JSONArray()
            fresh.forEach {
                array.put(
                    org.json.JSONObject()
                        .put("plan", it.plan).put("name", it.name).put("lines", it.lines)
                        .put("label", it.label).put("per_line", it.perLine),
                )
            }
            prefs.edit().putString(PREF_PLANS, array.toString()).apply()
        }

        private fun loadCachedPlans(): List<GatewayPlan>? =
            runCatching {
                val array = org.json.JSONArray(prefs.getString(PREF_PLANS, null) ?: return null)
                (0 until array.length()).map { GatewayPlan.from(array.getJSONObject(it)) }.takeIf { it.isNotEmpty() }
            }.getOrNull()

        /** Website-first purchase: enter the redemption code from the receipt. */
        suspend fun redeem(code: String): Boolean {
            _state.update { it.copy(busy = true, message = null) }
            prefs.edit().putBoolean(PREF_AUTO_TRIAL, false).apply()
            ensureGatewayInterface()
            val r = api.call("/v1/redeem", "redeem", socksFor(gatewayEntity())) { _, _, _ -> mapOf("code" to code.trim()) }
            val ok = afterAction(r)
            if (ok) kick(force = true)
            return ok
        }

        /** Move trial/subscription from another identity on this phone to the active one. */
        suspend fun transferFrom(fromIdentityHash: String): Boolean {
            _state.update { it.copy(busy = true, message = null) }
            val fromEntity = identityRepository.getIdentity(fromIdentityHash)
            val fromKeys = fromEntity?.let { signer.keysFor(it) }
            if (fromKeys == null) {
                _state.update {
                    it.copy(busy = false, message = "Couldn't unlock that identity's key. Unlock it in Identity Manager and try again.")
                }
                return false
            }
            ensureGatewayInterface()
            val r =
                api.call("/v1/transfer", "transfer", socksFor(gatewayEntity())) { keys, ts, nonce ->
                    signer.transferConsent(fromKeys, keys.identityHash, ts, nonce)
                }
            fromKeys.signingSeed.fill(0)
            val ok = afterAction(r)
            if (ok) {
                _state.update { it.copy(transferOffer = null) }
                kick(force = true)
            }
            return ok
        }

        fun dismissTransferOffer() = _state.update { it.copy(transferOffer = null) }

        /** Other identities on this phone, for "Move from another identity". */
        suspend fun otherIdentities(): List<Pair<String, String>> {
            val active = _state.value.identityHash
            return identityRepository.allIdentities.first()
                .filter { it.identityHash != active }
                .map { it.identityHash to it.displayName }
        }

        private fun afterAction(r: GatewayApi.Result): Boolean {
            val (ok, json, fallback) =
                when (r) {
                    is GatewayApi.Result.Ok -> Triple(true, r.json, "Done.")
                    is GatewayApi.Result.Refused -> Triple(false, r.json, "Request refused.")
                    is GatewayApi.Result.Failed -> Triple(false, null, "Couldn't reach LCS. Check your connection and try again.")
                }
            _state.update {
                it.copy(
                    busy = false,
                    message = json?.optString("message")?.ifEmpty { null } ?: fallback,
                    status = json?.takeIf { j -> j.has("state") }?.let { j -> GatewayStatus.from(j) } ?: it.status,
                )
            }
            return ok
        }

        /** New phone / reinstalled: attach this identity to an existing subscription. */
        suspend fun openLinkDevice(activityContext: Context): Boolean {
            prefs.edit().putBoolean(PREF_AUTO_TRIAL, false).apply()
            ensureGatewayInterface()
            val link = linkCode() ?: return false
            _state.update { it.copy(message = "Your device code is ${link.code}") }
            return openBrowser(activityContext, link.linkDeviceUrl)
        }

        /** Auto-renew, card, cancel, renew: all on lcs.network My Account. */
        /**
         * "Manage plan": My Account on lcs.network. If this phone bought the plan, the server
         * returns a one-time link (2 minutes) that opens it already signed in; otherwise it's
         * the normal page, where the customer signs in.
         */
        suspend fun openManage(activityContext: Context): Boolean {
            val fallback = _state.value.status?.accountUrl ?: "https://lcs.network/my-account/lcs-global/"
            val url =
                when (val r = api.call("/v1/account-link", "account_link", socksFor(gatewayEntity()))) {
                    is GatewayApi.Result.Ok -> r.json.optString("url").ifEmpty { fallback }
                    else -> fallback
                }
            return openBrowser(activityContext, url)
        }

        private suspend fun linkCode(): GatewayLinkCode? {
            _state.update { it.copy(busy = true, message = null) }
            val r = api.call("/v1/link-code", "link_code", socksFor(gatewayEntity()))
            _state.update { it.copy(busy = false) }
            return when (r) {
                is GatewayApi.Result.Ok ->
                    GatewayLinkCode(
                        code = r.json.getString("code"),
                        monthlyUrl = r.json.getString("subscribe_monthly_url"),
                        yearlyUrl = r.json.getString("subscribe_yearly_url"),
                        linkDeviceUrl = r.json.getString("link_device_url"),
                        subscribeUrlPrefix = r.json.optStringOrNull("subscribe_url_prefix"),
                    )
                is GatewayApi.Result.Refused -> {
                    _state.update { it.copy(message = r.json.optString("message").ifEmpty { "Request refused." }) }
                    null
                }
                is GatewayApi.Result.Failed -> {
                    _state.update { it.copy(message = "Couldn't reach LCS. Check your connection and try again.") }
                    null
                }
            }
        }

        private fun openBrowser(
            activityContext: Context,
            url: String,
        ): Boolean {
            val uri = Uri.parse(url)
            val host = uri.host.orEmpty()
            if (uri.scheme != "https" || (host != "lcs.network" && !host.endsWith(".lcs.network"))) {
                Log.w(TAG, "Refusing to open unexpected URL")
                return false
            }
            return try {
                activityContext.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: ActivityNotFoundException) {
                _state.update { it.copy(message = "No web browser found to open lcs.network.") }
                false
            }
        }

        fun clearMessage() = _state.update { it.copy(message = null) }

        /** For the identity-delete warning: does [identityHash] hold a trial or subscription? */
        fun isLinked(identityHash: String): Boolean {
            val s = _state.value
            return s.identityHash == identityHash && s.status != null && s.status.state != "none"
        }
    }
