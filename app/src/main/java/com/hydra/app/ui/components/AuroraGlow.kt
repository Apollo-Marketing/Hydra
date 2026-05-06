package com.hydra.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.hydra.app.ui.theme.Cyan
import com.hydra.app.ui.theme.LocalHydraColors
import com.hydra.app.ui.theme.Violet

/**
 * Two soft radial blooms — one cyan in the top-right, one violet on the left mid-screen —
 * that sit behind a screen's content and give the dark ground its "liquid" depth. In light
 * mode this renders nothing; the bright bg can't carry the bloom and the spec calls for it
 * to be dark-only.
 */
@Composable
fun AuroraGlow(modifier: Modifier = Modifier) {
    if (!LocalHydraColors.current.isDark) return
    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Cyan.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width * 0.95f, -40f),
                    radius = 460f,
                ),
                radius = 460f,
                center = Offset(size.width * 0.95f, -40f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Violet.copy(alpha = 0.18f), Color.Transparent),
                    center = Offset(-60f, size.height * 0.18f),
                    radius = 420f,
                ),
                radius = 420f,
                center = Offset(-60f, size.height * 0.18f),
            )
        }
    }
}
