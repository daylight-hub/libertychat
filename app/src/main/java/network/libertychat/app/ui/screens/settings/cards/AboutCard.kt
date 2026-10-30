package network.libertychat.app.ui.screens.settings.cards

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import network.libertychat.app.R
import network.libertychat.app.service.AppUpdateResult
import network.libertychat.app.ui.components.CollapsibleSettingsCard
import network.libertychat.app.ui.theme.ShinyRedButton
import network.libertychat.app.util.SystemInfo
import network.libertychat.app.util.safeOpenUrl
import java.util.Locale
import android.content.Intent
import androidx.core.net.toUri

/**
 * LCS: the network guide PDF, hosted rather than bundled.
 *
 * Keeping it off-device avoids adding ~3 MB to every ABI split, and lets a
 * corrected guide reach existing installs without shipping an app update.
 */
private const val LCS_NETWORK_GUIDE_URL = "https://lcs.network/guide"

@Composable
fun AboutCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    systemInfo: SystemInfo,
    onCopySystemInfo: () -> Unit,
    onReportBug: () -> Unit,
    updateCheckResult: AppUpdateResult = AppUpdateResult.Idle,
    includePrereleaseUpdates: Boolean = false,
    onCheckForUpdates: () -> Unit = {},
    onSetIncludePrereleaseUpdates: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current

    CollapsibleSettingsCard(
        title = "About",
        icon = Icons.Default.Info,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Logo and Header
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "Liberty Chat Logo",
                modifier = Modifier.size(108.dp),
            )

            Text(
                text = "Liberty Chat",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = "Powered by Torlando-Tech's Columba",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "Liberty Chat — messaging over Bluetooth LE, TCP, or RNode (LoRa) using LXMF and Reticulum",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            // Buy RNode Radios — LCS shiny red CTA linking visibly to the LCS store
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ShinyRedButton(
                    text = "Buy RNode Radios",
                    leadingIcon = Icons.Default.ShoppingCart,
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, "https://www.lcs.network".toUri())
                        context.startActivity(intent)
                    },
                )
                Text(
                    text = "www.lcs.network",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                // LCS: the network guide. Opens in the device browser / PDF
                // viewer rather than shipping the file in the APK — the PDF is
                // ~3 MB, it would inflate every ABI split, and hosting it means
                // corrections reach existing installs without an app update.
                OutlinedButton(
                    onClick = {
                        openExternalUrl(context, LCS_NETWORK_GUIDE_URL)
                    },
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("LCS Network Guide", style = MaterialTheme.typography.bodyMedium)
                }
            }

            HorizontalDivider()

            // Version Information
            InfoSection(title = "App Information") {
                InfoRow("Version", systemInfo.appVersion)
                InfoRow("Build Number", systemInfo.appBuildCode.toString())
                InfoRow("Build Type", systemInfo.buildType.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() })
                InfoRow("Git Commit", systemInfo.gitCommitHash)
                InfoRow("Build Date", systemInfo.buildDate)
            }

            HorizontalDivider()

            // Device Information
            InfoSection(title = "Device Information") {
                InfoRow("Android Version", systemInfo.androidVersion)
                InfoRow("API Level", systemInfo.apiLevel.toString())
                InfoRow("Device Model", systemInfo.deviceModel)
                InfoRow("Manufacturer", systemInfo.manufacturer)
            }

            HorizontalDivider()

            // Protocol Versions
            InfoSection(title = "Protocol Versions") {
                if (systemInfo.reticulumVersion != null) {
                    InfoRow("Reticulum", systemInfo.reticulumVersion)
                }
                if (systemInfo.lxmfVersion != null) {
                    InfoRow("LXMF", systemInfo.lxmfVersion)
                }
                if (systemInfo.bleReticulumVersion != null) {
                    InfoRow("BLE-Reticulum", systemInfo.bleReticulumVersion)
                }
                if (systemInfo.lxstVersion != null) {
                    InfoRow("LXST", systemInfo.lxstVersion)
                }
            }

            HorizontalDivider()

            // Identity
            if (systemInfo.identityHash != null) {
                InfoSection(title = "Identity") {
                    InfoRow("Identity Hash", systemInfo.identityHash)
                }
                HorizontalDivider()
            }

            // Legal
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "MPL 2.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Liberty Chat — powered by Torlando-Tech's Columba — " +
                        "a Liberty Communication Systems, Inc. distribution",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Based on Columba © 2025–${network.libertychat.app.BuildConfig.COPYRIGHT_YEAR} " +
                        "Columba Contributors (torlando-tech) — original design & code",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "LCS branding & modifications © ${network.libertychat.app.BuildConfig.COPYRIGHT_YEAR} " +
                        "Liberty Communication Systems, Inc.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        openExternalUrl(context, "https://github.com/daylight-hub/libertychat/blob/liberty-chat/LICENSE.md")
                    },
                ) {
                    Text("View License", style = MaterialTheme.typography.bodySmall)
                }
            }

            HorizontalDivider()

            // Attribution
            Text(
                text = "Built With",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Reticulum by Mark Qvist", style = MaterialTheme.typography.bodySmall)
                Text("LXMF by Mark Qvist", style = MaterialTheme.typography.bodySmall)
                Text("Material Design 3", style = MaterialTheme.typography.bodySmall)
                Text("Jetpack Compose", style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            // Updates
            InfoSection(title = "Updates") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Include pre-releases",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = includePrereleaseUpdates,
                        onCheckedChange = onSetIncludePrereleaseUpdates,
                    )
                }

                val isChecking = updateCheckResult is AppUpdateResult.Checking
                OutlinedButton(
                    onClick = onCheckForUpdates,
                    enabled = !isChecking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Check for Updates")
                }

                when (val result = updateCheckResult) {
                    is AppUpdateResult.UpToDate ->
                        Text(
                            text = "Up to date (v${result.currentVersion})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    is AppUpdateResult.UpdateAvailable -> {
                        Text(
                            text = "Update available: ${result.tagName}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            // Downloads the same APK as the one installed (falls back to the release page).
                            Button(
                                onClick = {
                                    openExternalUrl(context, result.apkUrl ?: result.htmlUrl)
                                },
                            ) {
                                Text("Download update")
                            }
                            TextButton(
                                onClick = {
                                    openExternalUrl(context, network.libertychat.app.service.UpdateChecker.ALL_APPS_URL)
                                },
                            ) {
                                Text("All Apps")
                            }
                        }
                    }
                    is AppUpdateResult.Error ->
                        Text(
                            text = result.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    else -> {}
                }
            }

            HorizontalDivider()

            // Copy Button
            OutlinedButton(
                onClick = onCopySystemInfo,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy System Info")
            }

        }
    }
}

@Composable
private fun InfoSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        content()
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    // The label keeps its natural width; the value takes the remaining space and
    // is right-aligned. Without the weight, a long value (e.g. the Python
    // flavor's "Reticulum 1.4.2 (torlando-tech fork)") and the label both claim
    // their full intrinsic width and crowd/overlap under SpaceBetween — instead
    // a long value now wraps within its column.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LinkButton(
    label: String,
    url: String,
    context: Context,
) {
    TextButton(
        onClick = {
            openExternalUrl(context, url)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label)
    }
}

private fun openExternalUrl(
    context: Context,
    url: String,
) {
    if (!safeOpenUrl(context, url)) {
        Toast.makeText(context, R.string.error_no_app_for_link, Toast.LENGTH_SHORT).show()
    }
}
