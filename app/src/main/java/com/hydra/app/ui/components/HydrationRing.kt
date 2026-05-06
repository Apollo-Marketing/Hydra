package com.hydra.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydra.app.ui.theme.Cyan
import com.hydra.app.ui.theme.LocalHydraColors
import com.hydra.app.ui.theme.Violet

/**
 * The signature Hydra hydration ring. Hairline track behind a cyan→violet progress arc
 * with a soft halo glow underlay. The centre is a slot for whatever caller wants —
 * eyebrow / % display / footer for the Hydration screen.
 */
@Composable
fun HydrationRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
    strokeWidth: Dp = 12.dp,
    centerContent: @Composable () -> Unit,
) {
    val colors = LocalHydraColors.current
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1.5f),
        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "hydration_ring",
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            val arcSize = Size(this.size.width - stroke.width, this.size.height - stroke.width)
            val topLeft = Offset(stroke.width / 2f, stroke.width / 2f)

            drawArc(
                color = colors.hair,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            if (animated > 0f) {
                val gradient = Brush.linearGradient(
                    colors = listOf(Cyan, Violet),
                    start = Offset(0f, 0f),
                    end = Offset(this.size.width, this.size.height),
                )
                drawArc(
                    brush = gradient,
                    startAngle = -90f,
                    sweepAngle = 360f * animated.coerceAtMost(1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth.toPx() * 1.6f, cap = StrokeCap.Round),
                    alpha = 0.35f,
                )
                drawArc(
                    brush = gradient,
                    startAngle = -90f,
                    sweepAngle = 360f * animated.coerceAtMost(1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            centerContent()
        }
    }
}

/**
 * Default centre stack used by the Hydration screen — eyebrow / big % / footer.
 */
@Composable
fun HydrationRingCenter(
    eyebrow: String,
    percent: Int,
    footer: String,
) {
    val colors = LocalHydraColors.current
    Text(eyebrow, color = colors.inkSoft, style = MaterialTheme.typography.bodyMedium)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            percent.toString(),
            color = colors.ink,
            fontSize = 64.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "%",
            color = colors.inkSoft,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
        )
    }
    Text(footer, color = colors.inkSoft, style = MaterialTheme.typography.bodyMedium)
}
