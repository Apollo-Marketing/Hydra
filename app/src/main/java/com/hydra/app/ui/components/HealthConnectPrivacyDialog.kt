package com.hydra.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.hydra.app.ui.theme.Cyan
import com.hydra.app.ui.theme.LocalHydraColors

/**
 * Shared privacy dialog for Health Connect. Two entry points render this:
 * - the "Privacy" row in Settings → Integrations, opened explicitly by the user;
 * - the rationale intent (`androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` or
 *   API 34+'s `VIEW_PERMISSION_USAGE` + `HEALTH_PERMISSIONS`), routed by MainActivity
 *   when Health Connect deep-links into us from its own permission UI.
 *
 * Styled to match the other AlertDialogs in the app (ScanSheet, theme picker, etc.).
 */
@Composable
fun HealthConnectPrivacyDialog(open: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    val colors = LocalHydraColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceSolid,
        titleContentColor = colors.ink,
        textContentColor = colors.inkSoft,
        title = { Text("Health Connect privacy", color = colors.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Hydra writes your sip data to Health Connect on this device so other " +
                        "health apps you've granted access can read it. Hydra does not send " +
                        "your hydration data over the internet, and Hydra does not read " +
                        "hydration data written by other apps.",
                    color = colors.inkSoft,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "You can revoke Hydra's access at any time inside the Health Connect " +
                        "app's permissions screen.",
                    color = colors.inkSoft,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Cyan) }
        },
    )
}
