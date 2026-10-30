package network.libertychat.app.gateway.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import network.libertychat.app.gateway.GatewayAccessManager
import network.libertychat.app.gateway.GatewayConfig
import network.libertychat.app.gateway.GatewayStatus
import network.libertychat.app.ui.components.CollapsibleSettingsCard

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GatewayEntryPoint {
    fun gatewayAccessManager(): GatewayAccessManager
}

/** The app-wide manager, or null in previews/screenshot tests where Hilt isn't running. */
@Composable
fun rememberGatewayAccessManager(): GatewayAccessManager? {
    if (LocalInspectionMode.current) return null
    val appContext = LocalContext.current.applicationContext
    return remember {
        runCatching {
            EntryPointAccessors.fromApplication(appContext, GatewayEntryPoint::class.java).gatewayAccessManager()
        }.getOrNull()
    }
}

private fun formatDate(epochSeconds: Long?): String =
    epochSeconds?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * 1000)) } ?: ""

private fun daysLeft(epochSeconds: Long?): Long =
    epochSeconds?.let { ((it * 1000 - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0) } ?: 0

/** One line describing where the user stands. */
fun describe(status: GatewayStatus?): String {
    if (status == null) return "Checking…"
    return when (status.state) {
        "none" ->
            if (status.trialAvailable) {
                "Not started. Try it free for ${GatewayConfig.TRIAL_DAYS} days."
            } else {
                "Not subscribed. (The free trial was already used on this device.)"
            }
        "trial" -> "Free trial: ${daysLeft(status.trialEnds)} day(s) left, until ${formatDate(status.trialEnds)}."
        "trial_ended" -> "Your free trial has ended."
        "active" -> {
            if (status.unlimited) {
                return status.plan?.let { "Active ($it). No time limit." } ?: "Active. No time limit."
            }
            val plan =
                status.plan?.let { p -> if ((status.lines ?: 1) > 1 && status.line != null) "$p, line ${status.line}" else p }
            val prefix = if (plan != null) "Active ($plan)." else "Active."
            if (status.cancelled) {
                "$prefix Cancelled: no more charges. Service continues until ${formatDate(status.paidThrough)}, then ends."
            } else if (status.autoRenew == true) {
                "$prefix Renews automatically on ${formatDate(status.paidThrough)}."
            } else {
                "$prefix Paid through ${formatDate(status.paidThrough)}."
            }
        }
        "grace" -> "Payment needed: the gateway stays on until ${formatDate(status.graceEnds)}, then stops."
        "expired" -> "Your subscription has ended."
        else -> status.state
    }
}

/** The warning shown wherever an identity could be deleted or replaced. */
/** Cancellation terms, word for word as on lcs.network. */
const val CANCEL_TERMS =
    "Cancel any time on lcs.network. Billing stops when you cancel and your service continues until the end " +
        "of the period you already paid for (the rest of the month on monthly plans, the rest of the year on " +
        "yearly plans), then ends. No refunds."

val IDENTITY_WARNING =
    "Your ${GatewayConfig.PRODUCT_NAME} trial and subscription belong to this identity. Before deleting it, switch to " +
        "the identity you'll keep and choose \"Move from another identity\" in Settings > ${GatewayConfig.PRODUCT_NAME} " +
        "(or change it later in My Account at lcs.network, 3 times a year). A free trial can't be restarted."

// ---------------------------------------------------------------------------- navigation

/**
 * "Edit" on the LCS Global interface opens Settings > LCS Global: the Interfaces screen asks
 * here, and the Settings card opens itself and scrolls into view.
 */
object GatewaySettingsRequest {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() {
        _pending.value = true
    }

    fun consume() {
        _pending.value = false
    }
}

/**
 * The LCS Global logo (navy disc, gold globe) for settings and onboarding. Full colour: show it
 * with tint = Color.Unspecified ([LCS_GLOBAL_ICON_TINT]) so it isn't flattened to one colour.
 */
@Composable
fun lcsGlobalGlobeIcon(): ImageVector = ImageVector.vectorResource(network.libertychat.app.R.drawable.ic_lcs_global)

val LCS_GLOBAL_ICON_TINT = androidx.compose.ui.graphics.Color.Unspecified

// ---------------------------------------------------------------------------- Tor

private fun openExternal(
    context: Context,
    url: String,
) {
    try {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "No web browser found.", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Short steps for setting up Tor, shown under the switch. */
val TOR_STEPS =
    "1. Install Orbot (Get Orbot below, or from Google Play or F-Droid).\n" +
        "2. Open Orbot and tap Connect. Wait until it says Connected.\n" +
        "3. Come back here and turn on Connect through Tor.\n" +
        "4. Leave Orbot running. If ${GatewayConfig.PRODUCT_NAME} stops connecting, open Orbot and make sure it's still connected.\n" +
        "Leave Orbot's settings as they are (it listens on port 9050). Messages and calls can be a little slower over Tor."

/**
 * "Connect through Tor (Orbot)" for LCS Global. The address, port and passphrase still come
 * from the server and are never shown; this only says which way the connection goes.
 */
@Composable
fun TorOption(modifier: Modifier = Modifier) {
    val manager = rememberGatewayAccessManager() ?: return
    val useTor by manager.useTor.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var orbotInstalled by remember { mutableStateOf(manager.isOrbotInstalled()) }
    var showSteps by rememberSaveable { mutableStateOf(false) }

    // Back from installing Orbot: check again.
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) orbotInstalled = manager.isOrbotInstalled()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Connect through Tor (Orbot)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Hides your internet address from LCS and from your network. Needs the free Orbot app " +
                        "installed and connected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = useTor, onCheckedChange = { on -> scope.launch { manager.setUseTor(on) } })
        }
        if (useTor && !orbotInstalled) {
            Text(
                "Orbot isn't installed yet. ${GatewayConfig.PRODUCT_NAME} won't connect until it is installed and connected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (orbotInstalled) {
                TextButton(onClick = {
                    context.packageManager.getLaunchIntentForPackage(GatewayConfig.ORBOT_PACKAGE)?.let {
                        context.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("Open Orbot") }
            } else {
                TextButton(onClick = { openExternal(context, GatewayConfig.ORBOT_DOWNLOAD_URL) }) { Text("Get Orbot") }
            }
            TextButton(onClick = { openExternal(context, GatewayConfig.ORBOT_RELEASES_URL) }) { Text("Orbot on GitHub") }
        }
        TextButton(onClick = { showSteps = !showSteps }) {
            Text(if (showSteps) "Hide Tor setup steps" else "How to set up Tor")
        }
        if (showSteps) {
            Text(TOR_STEPS, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------- encryption, data

/** Said on every plan, in the app and on lcs.network. */
const val UNLIMITED_DATA = "Unlimited data on every plan."

/** How the encryption works, in the same words as LCS MeshChat (Reticulum's own scheme). */
val ENCRYPTION_DETAILS =
    listOf(
        "Liberty Chat runs on Reticulum. Every message, call and file is encrypted on this phone for the one " +
            "person it's going to, before it leaves: X25519 elliptic-curve key exchange, Ed25519 signatures, and " +
            "AES-256 encryption (CBC mode) authenticated with HMAC-SHA256.",
        "Calls and file transfers run over Reticulum links, which make fresh keys for every session, so a key " +
            "taken later can't unlock what was sent before (forward secrecy). Your private key never leaves this phone.",
        "${GatewayConfig.PRODUCT_NAME} only carries the encrypted packets between you and the rest of the network. " +
            "It can't read them, and it doesn't need your name, phone number or email.",
    )

/** "How the encryption works", folded until tapped. */
@Composable
fun EncryptionDetails(modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        TextButton(onClick = { open = !open }) {
            Text(if (open) "Hide how the encryption works" else "How the encryption works")
        }
        if (open) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ENCRYPTION_DETAILS.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- shared pieces

/** "Your LXMF address" with a copy button: what to paste on lcs.network when buying there. */
@Composable
fun LxmfAddressRow(address: String?) {
    if (address == null) return
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Your LXMF address", style = MaterialTheme.typography.labelMedium)
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                address,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(address))
                android.widget.Toast.makeText(context, "LXMF address copied", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("COPY") }
        }
    }
}

/**
 * "I already subscribe": redeem the code from a website purchase, or (for a subscription
 * bought from another phone/identity) link it on lcs.network.
 */
@Composable
fun AlreadySubscribedDialog(
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val manager = rememberGatewayAccessManager() ?: return
    val ui by manager.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var showMove by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { LcsGlobalMark() },
        title = { Text("I already subscribe") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Bought on lcs.network? Enter the redemption code from your receipt or My Account. " +
                        "Your time starts when you enter it. Codes expire 12 months after they are issued.",
                )
                androidx.compose.material3.OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase() },
                    label = { Text("Redemption code") },
                    placeholder = { Text("XXXX-XXXX-XXXX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { scope.launch { if (manager.redeem(code)) onDone() } },
                    enabled = !ui.busy && code.count { it.isLetterOrDigit() } >= 12,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Redeem") }
                if (ui.busy) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                ui.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    "Typed your LXMF address at checkout? Nothing to do: it activates on its own.",
                    style = MaterialTheme.typography.bodySmall,
                )
                LxmfAddressRow(ui.status?.lxmfAddress)
                TextButton(onClick = { showMove = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("It's on another identity on this phone")
                }
                TextButton(
                    onClick = { scope.launch { if (manager.openLinkDevice(context)) onDone() } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("It's on another phone (link on lcs.network)") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } },
    )
    if (showMove) MoveFromIdentityDialog(onDismiss = { showMove = false }, onDone = { showMove = false; onDone() })
}

/** Pick another identity on this phone and move its trial/subscription to the active one. */
@Composable
fun MoveFromIdentityDialog(
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val manager = rememberGatewayAccessManager() ?: return
    val ui by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var others by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(Unit) { others = manager.otherIdentities() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move from another identity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Moves the ${GatewayConfig.PRODUCT_NAME} trial or subscription from the identity you pick to the one you're " +
                        "using now. Both identities sign the request, so only you can do this.",
                    style = MaterialTheme.typography.bodySmall,
                )
                when {
                    others == null -> CircularProgressIndicator()
                    others!!.isEmpty() -> Text("There are no other identities on this phone.")
                    else ->
                        others!!.forEach { (hash, name) ->
                            OutlinedButton(
                                onClick = { scope.launch { if (manager.transferFrom(hash)) onDone() } },
                                enabled = !ui.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("From \"$name\"") }
                        }
                }
                ui.message?.let { Text(it) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } },
    )
}

// ---------------------------------------------------------------------------- plans

/** "From $5/month" style summary of the cheapest single-line plan. */
fun fromPrice(plans: List<network.libertychat.app.gateway.GatewayPlan>): String =
    plans.filter { it.lines == 1 }.firstOrNull()?.let { "from ${it.label}" } ?: "with a paid plan"

/** One button per plan (live prices from lcs.network). [onPick] gets the plan key. */
@Composable
fun PlanButtons(
    plans: List<network.libertychat.app.gateway.GatewayPlan>,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    Text(UNLIMITED_DATA, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    plans.forEach { plan ->
        OutlinedButton(onClick = { onPick(plan.plan) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text("${plan.name}: ${plan.label}")
                if (plan.perLine.isNotEmpty()) {
                    Text(plan.perLine, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- offer dialog

/**
 * "Start free trial / pick a plan / I already subscribe". Shown from the Interfaces menu,
 * the Settings card and reminders. [onDone] fires after the user picked something.
 */
@Composable
fun GatewayOfferDialog(
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val manager = rememberGatewayAccessManager() ?: return
    val ui by manager.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trialAvailable = ui.status?.trialAvailable != false
    val plans by manager.plans.collectAsState()
    var showAlready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        manager.refreshPlans()
        manager.refreshStatus()
    }
    if (showAlready) {
        AlreadySubscribedDialog(onDismiss = { showAlready = false }, onDone = { showAlready = false; onDone() })
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { LcsGlobalMark() },
        title = { Text(GatewayConfig.PRODUCT_NAME) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                Text(
                    "Reach the Liberty network over the internet from anywhere, with end-to-end encrypted messages, " +
                        "calls, pictures and files. Try it free for ${GatewayConfig.TRIAL_DAYS} days, then ${fromPrice(plans)}. " +
                        "Family plans cover several people. $UNLIMITED_DATA",
                )
                if (ui.busy) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                if (trialAvailable) {
                    Button(
                        onClick = { scope.launch { manager.startTrial(); onDone() } },
                        enabled = !ui.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start ${GatewayConfig.TRIAL_DAYS}-day free trial") }
                }
                PlanButtons(plans, enabled = !ui.busy) { plan ->
                    scope.launch { if (manager.openSubscribe(context, plan)) onDone() }
                }
                TextButton(
                    onClick = { showAlready = true },
                    enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("I already subscribe / have a code") }
                EncryptionDetails()
                TorOption()
                Text(
                    "Payment happens securely on lcs.network, not in this app. " + CANCEL_TERMS + " " + IDENTITY_WARNING,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ui.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } },
    )
}

/**
 * Add Interface > LCS Global when the interface already exists. There is only ever one
 * (it is managed by the app); adding it again can't restart a trial — the server keeps one
 * trial per identity and per phone — so this shows where things stand and what to do next.
 */
@Composable
fun GatewayAlreadyAddedDialog(onDismiss: () -> Unit) {
    val manager = rememberGatewayAccessManager() ?: return
    val ui by manager.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showOffer by remember { mutableStateOf(false) }
    var showAlready by remember { mutableStateOf(false) }
    val state = ui.status?.state

    LaunchedEffect(Unit) { manager.refreshStatus() }
    if (showOffer) {
        GatewayOfferDialog(onDismiss = { showOffer = false }, onDone = { showOffer = false; onDismiss() })
        return
    }
    if (showAlready) {
        AlreadySubscribedDialog(onDismiss = { showAlready = false }, onDone = { showAlready = false; onDismiss() })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { LcsGlobalMark() },
        title = { Text("${GatewayConfig.PRODUCT_NAME} is already added") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(describe(ui.status))
                when (state) {
                    "active", "grace", "expired" ->
                        Button(onClick = { scope.launch { manager.openManage(context) } }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state == "active") "Manage plan" else if (state == "grace") "Fix payment on lcs.network" else "Renew on lcs.network")
                        }
                    "trial", "trial_ended", "none" ->
                        Button(onClick = { showOffer = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state == "none" && ui.status?.trialAvailable == true) "Start trial or subscribe" else "Subscribe")
                        }
                    else -> {}
                }
                OutlinedButton(onClick = { showAlready = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Redeem a code")
                }
                TorOption()
                Text(
                    "There is one ${GatewayConfig.PRODUCT_NAME} connection per phone, managed in " +
                        "Settings > ${GatewayConfig.PRODUCT_NAME}. The free trial is once per identity and per phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } },
    )
}

// ---------------------------------------------------------------------------- settings card

@Composable
fun GatewaySettingsCard() {
    val manager = rememberGatewayAccessManager() ?: return
    val ui by manager.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showOffer by remember { mutableStateOf(false) }
    var showAlready by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    val state = ui.status?.state
    val openRequested by GatewaySettingsRequest.pending.collectAsState()
    val bringIntoView = remember { BringIntoViewRequester() }

    LaunchedEffect(expanded) { if (expanded) manager.refreshStatus() }
    // Edit on the LCS Global interface: open this card and scroll to it.
    LaunchedEffect(openRequested) {
        if (openRequested) {
            GatewaySettingsRequest.consume()
            expanded = true
            delay(250)
            bringIntoView.bringIntoView()
        }
    }

    CollapsibleSettingsCard(
        title = GatewayConfig.INTERFACE_NAME,
        icon = lcsGlobalGlobeIcon(),
        iconTint = LCS_GLOBAL_ICON_TINT,
        isExpanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.bringIntoViewRequester(bringIntoView),
    ) {
        Text(describe(ui.status), style = MaterialTheme.typography.bodyMedium)
        LxmfAddressRow(ui.status?.lxmfAddress)
        when (state) {
            "active", "grace", "expired" ->
                Button(onClick = { scope.launch { manager.openManage(context) } }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state == "active") "Manage plan (card, cancel)" else if (state == "grace") "Fix payment on lcs.network" else "Renew on lcs.network")
                }
            else ->
                Button(onClick = { showOffer = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state == "trial") "Subscribe" else "Start trial or subscribe")
                }
        }
        OutlinedButton(onClick = { showAlready = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Redeem a code / I already subscribe")
        }
        OutlinedButton(onClick = { showMove = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Move from another identity on this phone")
        }
        TextButton(onClick = { scope.launch { manager.openLinkDevice(context) } }) {
            Text("Link this device on lcs.network")
        }
        TextButton(onClick = { manager.kick(force = true) }) { Text("Check again") }
        TorOption()
        EncryptionDetails()
        Text(UNLIMITED_DATA, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(CANCEL_TERMS, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(IDENTITY_WARNING, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showOffer) GatewayOfferDialog(onDismiss = { showOffer = false }, onDone = { showOffer = false })
    if (showAlready) AlreadySubscribedDialog(onDismiss = { showAlready = false }, onDone = { showAlready = false })
    if (showMove) MoveFromIdentityDialog(onDismiss = { showMove = false }, onDone = { showMove = false })
}

// ---------------------------------------------------------------------------- reminders

/**
 * Mounted once at the app root. Re-checks status whenever the app comes to the front
 * (e.g. right after paying in the browser) and shows at most one reminder at a time:
 * trial ending soon, trial ended, payment failed (grace), subscription ended.
 */
@Composable
fun GatewayReminderHost() {
    val manager = rememberGatewayAccessManager() ?: return
    val ui by manager.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("lcs_gateway_reminders", Context.MODE_PRIVATE) }
    var dismissedKey by remember { mutableStateOf<String?>(null) }
    var showOffer by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) manager.kick() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showOffer) {
        GatewayOfferDialog(onDismiss = { showOffer = false }, onDone = { showOffer = false })
        return
    }

    // Just switched identity, and the old one held the gateway: offer to bring it along.
    val offer = ui.transferOffer
    if (offer != null && ui.status != null && ui.status?.gatewayOpen == false) {
        AlertDialog(
            onDismissRequest = { manager.dismissTransferOffer() },
            icon = { LcsGlobalMark() },
            title = { Text("Move your ${GatewayConfig.PRODUCT_NAME}?") },
            text = {
                Text(
                    "\"${offer.fromName}\" has your ${GatewayConfig.PRODUCT_NAME} trial or subscription. Move it to the identity " +
                        "you're using now? \"${offer.fromName}\" will lose gateway access.",
                )
            },
            confirmButton = {
                TextButton(enabled = !ui.busy, onClick = { scope.launch { manager.transferFrom(offer.fromIdentityHash) } }) {
                    Text("MOVE IT")
                }
            },
            dismissButton = { TextButton(onClick = { manager.dismissTransferOffer() }) { Text("NOT NOW") } },
        )
        return
    }

    val status = ui.status ?: return
    if (!ui.hasGatewayInterface) return
    val reminder = reminderFor(status) ?: return
    val key = "${ui.identityHash}:${reminder.id}"
    val lastShown = prefs.getLong(key, 0)
    if (dismissedKey == key || System.currentTimeMillis() - lastShown < reminder.everyMs) return

    fun close() {
        prefs.edit().putLong(key, System.currentTimeMillis()).apply()
        dismissedKey = key
    }

    AlertDialog(
        onDismissRequest = { close() },
        icon = { LcsGlobalMark() },
        title = { Text(reminder.title) },
        text = { Text(reminder.body) },
        confirmButton = {
            TextButton(onClick = {
                if (reminder.goesToAccount) {
                    scope.launch { manager.openManage(context) }
                } else {
                    showOffer = true
                }
                close()
            }) { Text(reminder.action) }
        },
        dismissButton = { TextButton(onClick = { close() }) { Text("LATER") } },
    )
}

private data class Reminder(
    val id: String,
    val title: String,
    val body: String,
    val action: String,
    val goesToAccount: Boolean,
    val everyMs: Long,
)

private const val DAY_MS = 86_400_000L

private fun reminderFor(status: GatewayStatus): Reminder? =
    when (status.state) {
        "trial" ->
            if (daysLeft(status.trialEnds) <= 2) {
                Reminder(
                    "trial_ending", "Free trial ends soon",
                    "Your ${GatewayConfig.PRODUCT_NAME} trial ends ${formatDate(status.trialEnds)}. Subscribe to stay connected; family plans are available.",
                    "SUBSCRIBE", goesToAccount = false, everyMs = DAY_MS,
                )
            } else {
                null
            }
        "trial_ended" ->
            Reminder(
                "trial_ended", "Your free trial has ended",
                "The ${GatewayConfig.PRODUCT_NAME} is off for this phone. Local WiFi, Bluetooth and LoRa still work. " +
                    "Subscribe to reconnect over the internet.",
                "SUBSCRIBE", goesToAccount = false, everyMs = DAY_MS,
            )
        "grace" ->
            Reminder(
                "grace", "Renew your ${GatewayConfig.PRODUCT_NAME}",
                if (status.autoRenew == false) {
                    "Your paid period ended. The gateway stays on until ${formatDate(status.graceEnds)}. Renew on lcs.network to keep it."
                } else {
                    "Your renewal payment didn't go through. The gateway stays on until ${formatDate(status.graceEnds)}. " +
                        "Update your card or renew on lcs.network."
                },
                "RENEW", goesToAccount = true, everyMs = DAY_MS / 2,
            )
        "expired" ->
            Reminder(
                "expired", "Your subscription has ended",
                "The ${GatewayConfig.PRODUCT_NAME} is off for this phone. Renew on lcs.network to reconnect.",
                "RENEW", goesToAccount = true, everyMs = DAY_MS,
            )
        else -> null
    }

/** The LCS Global logo mark, in full colour. */
@Composable
fun LcsGlobalMark(size: androidx.compose.ui.unit.Dp = 48.dp) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(id = network.libertychat.app.R.drawable.ic_lcs_global),
        contentDescription = null,
        modifier = Modifier.size(size),
    )
}
