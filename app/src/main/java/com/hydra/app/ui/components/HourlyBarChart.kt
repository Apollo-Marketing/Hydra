package com.hydra.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydra.app.ui.theme.Cyan
import com.hydra.app.ui.theme.LocalHydraColors

/**
 * Compressed hourly chart — bins 24 raw hours into 8 contiguous three-hour buckets
 * covering the full day (12a, 3a, 6a, 9a, 12p, 3p, 6p, 9p) so early-morning and late-
 * night sips still show up. Empty buckets render as a hairline so the cadence stays visible.
 */
@Composable
fun HourlyBarChart(
    intakePerHour: DoubleArray,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val colors = LocalHydraColors.current

    val buckets = remember(intakePerHour) {
        val starts = intArrayOf(0, 3, 6, 9, 12, 15, 18, 21)
        starts.map { s -> (0 until 3).sumOf { intakePerHour.getOrNull(s + it) ?: 0.0 } }
    }
    val labels = listOf("12a", "3a", "6a", "9a", "12p", "3p", "6p", "9p")
    val maxVal = remember(buckets) { (buckets.maxOrNull() ?: 0.0).coerceAtLeast(1.0) }

    val labelStyle = remember(colors.inkDim) {
        TextStyle(color = colors.inkDim, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp),
    ) {
        val xLabelHeight = with(density) { 14.dp.toPx() }
        val gap = with(density) { 6.dp.toPx() }
        val chartTop = 0f
        val chartBottom = size.height - xLabelHeight
        val chartHeight = chartBottom - chartTop
        val barCount = 8
        val totalGap = gap * (barCount - 1)
        val barWidth = (size.width - totalGap) / barCount
        val gradient = Brush.verticalGradient(
            colors = listOf(Cyan, Cyan.copy(alpha = 0.35f)),
        )

        buckets.forEachIndexed { i, ml ->
            val x = i * (barWidth + gap)
            val cx = x + barWidth / 2f
            if (ml > 0) {
                val barHeight = (ml / maxVal * (chartHeight - 4f)).toFloat().coerceAtLeast(4f)
                val y = chartBottom - barHeight
                drawRoundRect(
                    brush = gradient,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(3f, 3f),
                )
            } else {
                drawLine(
                    color = colors.hair,
                    start = Offset(x, chartBottom - 1f),
                    end = Offset(x + barWidth, chartBottom - 1f),
                    strokeWidth = 2f,
                )
            }
            val layout = measurer.measure(labels[i], labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(cx - layout.size.width / 2f, chartBottom + 4f),
            )
        }
    }
}
