package network.libertychat.app.ui.screens.onboarding.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import network.libertychat.app.R
import network.libertychat.app.gateway.GatewayConfig
import network.libertychat.app.gateway.ui.AlreadySubscribedDialog
import network.libertychat.app.gateway.ui.PlanButtons
import network.libertychat.app.gateway.ui.fromPrice
import network.libertychat.app.gateway.ui.rememberGatewayAccessManager

/**
 * onboarding page for LCS Global, the internet connection to the Liberty network.
 *
 * [selected] = LCS Global will be set up when onboarding completes (the interface is
 * created then, and the free trial starts by itself unless a plan or code was chosen).
 */
@Composable
fun LcsGlobalPage(
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val manager = rememberGatewayAccessManager()
    val fallbackPlans = remember { kotlinx.coroutines.flow.MutableStateFlow(GatewayConfig.FALLBACK_PLANS) }
    val plans by (manager?.plans ?: fallbackPlans).collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPlans by remember { mutableStateOf(false) }
    var showAlready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { manager?.refreshPlans() }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        // The LCS Global mark (full colour, so no tint).
        Image(
            painter = painterResource(id = R.drawable.ic_lcs_global),
            contentDescription = "${GatewayConfig.PRODUCT_NAME} logo",
            modifier = Modifier.size(112.dp),
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = GatewayConfig.PRODUCT_NAME,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Reach your people from anywhere",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text =
                "Local WiFi, Bluetooth and LoRa reach people near you. ${GatewayConfig.PRODUCT_NAME} connects " +
                    "Liberty Chat to the wider Liberty network over the internet, so your messages reach family " +
                    "and friends who are out of radio range.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Point("AES-256 CBC end-to-end encrypted voice calls, messaging, and picture/file transfer")
            Point("No phone number or email needed")
            Point("Works over WiFi and mobile data")
            Point("Free for ${GatewayConfig.TRIAL_DAYS} days, then ${fromPrice(plans)}; family plans available")
        }
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                manager?.setAutoTrial(true)
                onSelectedChange(true)
                onContinue()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) { Text("Start ${GatewayConfig.TRIAL_DAYS}-day free trial") }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = { showPlans = true }, modifier = Modifier.fillMaxWidth()) {
            Text("See plans and subscribe")
        }
        TextButton(onClick = { showAlready = true }, modifier = Modifier.fillMaxWidth()) {
            Text("I already subscribe / have a code")
        }
        TextButton(
            onClick = {
                onSelectedChange(false)
                onContinue()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Not now (add it later from Settings)") }
        if (selected) {
            Text(
                "${GatewayConfig.PRODUCT_NAME} will be set up when you finish.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                onClick = onBack,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Back") }
            Button(
                onClick = onContinue,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Continue") }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showPlans && manager != null) {
        AlertDialog(
            onDismissRequest = { showPlans = false },
            title = { Text("Choose a plan") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "Payment happens on lcs.network in your browser, not in this app. Come back here when you're done.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    PlanButtons(plans, enabled = true) { plan ->
                        scope.launch {
                            if (manager.openSubscribe(context, plan)) {
                                onSelectedChange(true)
                                showPlans = false
                            }
                        }
                    }
                    Text(
                        network.libertychat.app.gateway.ui.CANCEL_TERMS,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPlans = false }) { Text("CLOSE") } },
        )
    }
    if (showAlready) {
        AlreadySubscribedDialog(
            onDismiss = { showAlready = false },
            onDone = {
                showAlready = false
                onSelectedChange(true)
            },
        )
    }
}

@Composable
private fun Point(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
