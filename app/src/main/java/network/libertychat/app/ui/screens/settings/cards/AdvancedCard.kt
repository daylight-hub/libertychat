package network.libertychat.app.ui.screens.settings.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.libertychat.app.BuildConfig
import network.libertychat.app.ui.components.CollapsibleSettingsCard
import network.libertychat.app.ui.components.LocalCapabilities

/**
 * Advanced settings card — houses power-user toggles and options that most users should
 * leave alone. Sits between the RNode Flasher and About cards so it's findable but
 * out of the way.
 *
 * @param isExpanded Whether the card is currently expanded
 * @param onExpandedChange Callback when expansion state changes
 * @param transportNodeEnabled Whether transport node mode is enabled (forwards mesh traffic)
 * @param onTransportNodeToggle Callback when transport node toggle is changed
 * @param shareInstanceHostingEnabled Persisted "Share Instance" toggle state
 *   (python backend only; capability-gated). True means the daemon will publish itself
 *   as an RNS shared instance on TCP 37428 after the next restart.
 * @param onShareInstanceHostingToggle Callback when Share Instance toggle is changed.
 *   Persists the preference but does NOT trigger a service restart — the user must
 *   explicitly Apply & Restart from elsewhere to avoid surprise outages.
 * @param shareInstanceHostingPending True when the persisted preference differs from
 *   the value the running daemon was constructed with. Drives the "(pending restart)"
 *   hint and inline Restart button under the toggle.
 * @param onRestartReticulum Invoked when the user taps the inline Restart button next
 *   to the pending-changes hint. Plumbs through to `SettingsViewModel.restartService()`.
 * @param isRestarting True while a service restart is in flight; disables the
 *   inline Restart button and shows a small progress spinner instead of the icon.
 */
@Composable
fun AdvancedCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    transportNodeEnabled: Boolean = true,
    onTransportNodeToggle: (Boolean) -> Unit = {},
    shareInstanceHostingEnabled: Boolean = false,
    onShareInstanceHostingToggle: (Boolean) -> Unit = {},
    shareInstanceHostingPending: Boolean = false,
    onRestartReticulum: () -> Unit = {},
    isRestarting: Boolean = false,
    crashReportingEnabled: Boolean = false,
    onCrashReportingToggle: (Boolean) -> Unit = {},
    pttEnabled: Boolean = true,
    onPttToggle: (Boolean) -> Unit = {},
    pttHighBandwidth: Boolean = false,
    onPttHighBandwidthToggle: (Boolean) -> Unit = {},
) {
    val canHostShareInstance = LocalCapabilities.current.performance.shareInstanceHosting
    CollapsibleSettingsCard(
        title = "Advanced",
        icon = Icons.Default.Tune,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        // Transport Node toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Hub,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Transport Node",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
            Switch(
                checked = transportNodeEnabled,
                onCheckedChange = onTransportNodeToggle,
            )
        }
        Text(
            // LCS: off by default, as upstream has it. 2.1.1 turned it on because
            // Reticulum only reloads its saved path table when transport is
            // enabled; the app now does that load itself, so this toggle is back to
            // meaning just one thing — whether you relay for other peers.
            text =
                "Forward traffic for other peers on the mesh. Off by default: a phone " +
                    "that moves around makes a poor relay, and relaying costs battery and " +
                    "airtime. Turning it off does not lose your own routes — the app keeps " +
                    "those across restarts either way. In a BLE-only mesh this is required " +
                    "for multi-hop messaging.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Share Instance toggle (python backend only; capability-gated)
        if (canHostShareInstance) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Cast,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Share Instance",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Switch(
                    checked = shareInstanceHostingEnabled,
                    onCheckedChange = onShareInstanceHostingToggle,
                )
            }
            Text(
                text =
                    "Make Liberty Chat available as a shared RNS instance so other apps on this device " +
                        "(Sideband, rnsd, …) can route through Liberty Chat's transport. " +
                        "Requires a service restart to take effect. " +
                        "If another app is already hosting a shared instance on this device, " +
                        "Liberty Chat will join it as a client instead — see the Shared Instance banner.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (shareInstanceHostingPending) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Change pending — restart Reticulum to apply.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        onClick = onRestartReticulum,
                        enabled = !isRestarting,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) {
                        if (isRestarting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text("Restarting…")
                        } else {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text("Restart")
                        }
                    }
                }
            }
        }

        // Anonymous crash reporting toggle (sentry flavor only — the SDK is stripped from
        // the noSentry flavor, so the toggle would do nothing there).
        if (BuildConfig.CRASH_REPORTING_AVAILABLE) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Anonymous Crash Reports",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Switch(
                    checked = crashReportingEnabled,
                    onCheckedChange = onCrashReportingToggle,
                )
            }
            Text(
                text =
                    "Send anonymous crash and error reports to help the developer fix bugs. " +
                        "No message content, contacts, or identity information is ever included. " +
                        "Off by default; you can change this at any time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        }

        // LCS: Push-to-talk voice messages
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // weight(1f) is load-bearing. Without it this Row is measured
            // against the full incoming width, consumes all of it, and pushes
            // the Switch past the right edge of the card — which is exactly
            // what both PTT rows did before 1.2.1. The weight makes the label
            // yield whatever the Switch needs.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Push-to-Talk Voice Messages",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = pttEnabled,
                onCheckedChange = onPttToggle,
            )
        }
        Text(
            text =
                "Show a hold-to-talk microphone button in every conversation. Hold to record, " +
                    "release to send. Received voice messages play automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (pttEnabled) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    // The qualifier that used to live here ("for LoRa") moved
                    // into the description below: at bodyLarge it made this
                    // label wider than the row could hold on a 360 dp screen.
                    Text(
                        text = if (pttHighBandwidth) "High Bandwidth" else "Low Bandwidth",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = pttHighBandwidth,
                    onCheckedChange = onPttHighBandwidthToggle,
                )
            }
            Text(
                text =
                    if (pttHighBandwidth) {
                        "Opus at 24 kbps — about 3 KB per second. Much better audio, best " +
                            "over WiFi or TCP. Too heavy for most LoRa links."
                    } else {
                        "Codec2 1200, 8 kHz mono — about 150 bytes per second. Roughly 15 " +
                            "seconds of air time per 10-second clip on a standard LoRa preset."
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    "This selects the codec, not just the bitrate — the same choice Sideband " +
                        "makes. Sideband decodes both, and sends Codec2 unless its own " +
                        "high-quality PTT option is switched on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
