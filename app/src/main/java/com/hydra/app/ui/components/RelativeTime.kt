package com.hydra.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Returns a relative-time string ("just now", "X min ago", "X h ago", "X d ago") that
 * recomposes every 30 seconds so the displayed value stays fresh without manual nudging.
 *
 * @param epochMs the timestamp to render — pass 0 for "never"
 */
@Composable
fun rememberRelativeTime(epochMs: Long, neverLabel: String = "never"): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(epochMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    if (epochMs == 0L) return neverLabel
    val elapsedMs = (now - epochMs).coerceAtLeast(0L)
    return when {
        elapsedMs < 45_000L -> "just now"
        elapsedMs < 90_000L -> "1 min ago"
        elapsedMs < 60 * 60 * 1000L -> "${elapsedMs / 60_000L} min ago"
        elapsedMs < 24 * 60 * 60 * 1000L -> {
            val h = elapsedMs / (60 * 60 * 1000L)
            "$h h ago"
        }
        else -> {
            val d = elapsedMs / (24 * 60 * 60 * 1000L)
            "$d d ago"
        }
    }
}
