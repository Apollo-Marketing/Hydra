package com.hydra.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hydra.app.ble.ConnectionState
import com.hydra.app.ui.theme.Cyan
import com.hydra.app.ui.theme.Warn

/**
 * Connection-state pill — a small chip in the screen header showing live BLE link state.
 * Connected: cyan glow + dot. Searching/Connecting: cyan, no glow.
 * Error: warm orange "Tap to retry" — clickable when [onRetry] is supplied.
 */
@Composable
fun ConnectionBadge(
    state: ConnectionState,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    data class Spec(val label: String, val tint: Color, val pulse: Boolean)
    val spec = when (state) {
        ConnectionState.Disconnected -> Spec("Offline", Warn, false)
        ConnectionState.Searching -> Spec("Searching", Cyan, true)
        ConnectionState.Connecting -> Spec("Connecting", Cyan, true)
        ConnectionState.Discovering -> Spec("Pairing", Cyan, true)
        ConnectionState.Ready -> Spec("Connected", Cyan, false)
        ConnectionState.Error -> Spec("Tap to retry", Warn, false)
    }
    val canRetry = onRetry != null && state == ConnectionState.Error
    val shape = RoundedCornerShape(50)
    val tintBg = spec.tint.copy(alpha = 0.12f)
    val tintBorder = spec.tint.copy(alpha = 0.4f)

    Row(
        modifier = modifier
            .clip(shape)
            .background(tintBg)
            .border(BorderStroke(1.dp, tintBorder), shape)
            .let { if (canRetry) it.clickable { onRetry?.invoke() } else it }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(spec.tint),
        )
        Text(
            spec.label,
            color = spec.tint,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
