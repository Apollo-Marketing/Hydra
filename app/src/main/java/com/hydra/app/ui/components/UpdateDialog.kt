package com.hydra.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hydra.app.BuildConfig
import com.hydra.app.update.UpdateState
import com.hydra.app.ui.theme.Cyan
import com.hydra.app.ui.theme.LocalHydraColors

@Composable
fun UpdateDialog(
    state: UpdateState,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onInstall: () -> Unit,
) {
    val colors = LocalHydraColors.current
    when (state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = onLater,
            containerColor = colors.surfaceSolid,
            titleContentColor = colors.ink,
            textContentColor = colors.inkSoft,
            title = { Text("Update available · ${state.versionName}") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.releaseNotes.isNotBlank()) {
                        Text(
                            state.releaseNotes,
                            color = colors.inkSoft,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (state.sizeBytes > 0) {
                        Text(
                            "Download size · ${formatBytes(state.sizeBytes)}",
                            color = colors.inkDim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onUpdate) { Text("Update", color = Cyan) }
            },
            dismissButton = {
                TextButton(onClick = onLater) { Text("Later", color = colors.inkSoft) }
            },
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = onLater,
            containerColor = colors.surfaceSolid,
            titleContentColor = colors.ink,
            textContentColor = colors.inkSoft,
            title = { Text("Downloading update") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = Cyan,
                        trackColor = colors.hair,
                    )
                    Text(
                        "${(state.progress * 100).toInt()}%",
                        color = colors.inkSoft,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onLater) { Text("Hide", color = colors.inkSoft) }
            },
        )

        is UpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = onLater,
            containerColor = colors.surfaceSolid,
            titleContentColor = colors.ink,
            textContentColor = colors.inkSoft,
            title = { Text("Ready to install") },
            text = {
                Text(
                    "Tap Install to hand the APK to the system installer.",
                    color = colors.inkSoft,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = onInstall) { Text("Install", color = Cyan) }
            },
            dismissButton = {
                TextButton(onClick = onLater) { Text("Later", color = colors.inkSoft) }
            },
        )

        is UpdateState.PermissionRequired -> AlertDialog(
            onDismissRequest = onLater,
            containerColor = colors.surfaceSolid,
            titleContentColor = colors.ink,
            textContentColor = colors.inkSoft,
            title = { Text("Allow installs from Hydra") },
            text = {
                Text(
                    "Android needs permission to install updates from this app. " +
                        "Open settings and enable \"Allow from this source,\" then return and tap Update again.",
                    color = colors.inkSoft,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = onOpenSettings) { Text("Open settings", color = Cyan) }
            },
            dismissButton = {
                TextButton(onClick = onLater) { Text("Later", color = colors.inkSoft) }
            },
        )

        is UpdateState.Error -> AlertDialog(
            onDismissRequest = onLater,
            containerColor = colors.surfaceSolid,
            titleContentColor = colors.ink,
            textContentColor = colors.inkSoft,
            title = { Text("Update failed") },
            text = {
                Text(
                    state.message,
                    color = colors.inkSoft,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = onRetry) { Text("Retry", color = Cyan) }
            },
            dismissButton = {
                TextButton(onClick = onLater) { Text("Dismiss", color = colors.inkSoft) }
            },
        )

        UpdateState.Checking -> AlertDialog(
            onDismissRequest = {},
            containerColor = colors.surfaceSolid,
            titleContentColor = colors.ink,
            textContentColor = colors.inkSoft,
            title = { Text("Checking for updates") },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Cyan,
                    )
                    Text(
                        "Asking GitHub for the latest release…",
                        color = colors.inkSoft,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {},
        )

        UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = onLater,
            containerColor = colors.surfaceSolid,
            titleContentColor = colors.ink,
            textContentColor = colors.inkSoft,
            title = { Text("You're up to date") },
            text = {
                Text(
                    "Hydra v${BuildConfig.VERSION_NAME} is the latest version.",
                    color = colors.inkSoft,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = onLater) { Text("Done", color = Cyan) }
            },
        )

        UpdateState.Idle,
        UpdateState.InstallerLaunched -> Unit
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return "%.1f MB".format(mb)
}
