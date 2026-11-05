package network.libertychat.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import network.libertychat.app.R
import network.libertychat.app.service.AppUpdateNotifier
import network.libertychat.app.service.AppUpdateResult
import network.libertychat.app.service.UpdateSelection
import network.libertychat.app.util.safeOpenUrl

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppUpdateEntryPoint {
    fun appUpdateNotifier(): AppUpdateNotifier
}

/**
 * LCS: shows the "Update available" popup when the startup check finds a newer Liberty Chat.
 * Placed once at the root of the UI. [ready] holds it back until onboarding is done, so a
 * new user's first minutes aren't interrupted.
 */
@Composable
fun AppUpdateHost(ready: Boolean) {
    if (LocalInspectionMode.current) return
    val appContext = LocalContext.current.applicationContext
    val notifier =
        remember {
            runCatching {
                EntryPointAccessors.fromApplication(appContext, AppUpdateEntryPoint::class.java).appUpdateNotifier()
            }.getOrNull()
        } ?: return
    val offer by notifier.offer.collectAsState()
    val update = offer ?: return
    if (!ready) return
    AppUpdateDialog(update = update, onDismiss = { notifier.dismiss() })
}

@Composable
fun AppUpdateDialog(
    update: AppUpdateResult.UpdateAvailable,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val target = update.apkUrl ?: update.htmlUrl

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
        },
        title = {
            Text(
                text = "Update available",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text =
                        "Liberty Chat ${update.latestVersion} is ready to download. " +
                            "You have version ${update.currentVersion}.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DetailRow("New version", update.latestVersion)
                        DetailRow("Build", UpdateSelection.describeVariant(update.variant))
                        DetailRow("From", update.source)
                        if (update.fileName != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(
                                text = update.fileName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (update.notes.isNotBlank()) {
                    Text(
                        text = "What's new",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = update.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text =
                        if (update.apkUrl != null) {
                            "This is the same build as the one on this phone. When the download " +
                                "finishes, open the file to install the update."
                        } else {
                            "The download page lists every build of this release."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "You can turn off this check in Settings › Advanced.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!safeOpenUrl(context, target)) {
                        Toast.makeText(context, R.string.error_no_app_for_link, Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                },
            ) {
                Icon(
                    imageVector = if (update.apkUrl != null) Icons.Default.Download else Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (update.apkUrl != null) "Download" else "Open download page")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later") }
        },
    )
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
