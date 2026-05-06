package com.hydra.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import com.hydra.app.ui.theme.BgRadialBottom
import com.hydra.app.ui.theme.BgRadialMid
import com.hydra.app.ui.theme.BgRadialTop
import com.hydra.app.ui.theme.LocalHydraColors

/**
 * Hydra ground. Dark theme paints a deep navy radial wash that lifts at the top; light
 * theme paints a flat off-white per the design spec — the radial bloom is dark-only since
 * the cyan/violet aurora needs darkness to register.
 */
@Composable
fun GradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalHydraColors.current
    val bg = if (colors.isDark) {
        Modifier.background(
            Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to BgRadialTop,
                    0.6f to BgRadialMid,
                    1.0f to BgRadialBottom,
                ),
                center = Offset(500f, -200f),
                radius = 2200f,
            ),
        )
    } else {
        Modifier.background(colors.bg)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(bg),
    ) {
        content()
    }
}
