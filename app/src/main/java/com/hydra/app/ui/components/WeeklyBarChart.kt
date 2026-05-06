package com.hydra.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.hydra.app.ui.theme.Cyan
import com.hydra.app.ui.theme.LocalHydraColors
import com.hydra.app.ui.theme.Warn
import java.time.LocalDate

/**
 * 7-day intake chart. Bars hitting goal use the cyan gradient + glow; today's bar uses
 * the warn-orange gradient instead so it pops, even when it hasn't yet cleared goal. A
 * dashed cyan goal line floats across the chart at the goal value.
 *
 * Renders the day-of-week labels in a row of clickable cells beneath the canvas so a
 * caller can hook day-detail navigation onto a tap.
 */
@Composable
fun WeeklyBarChart(
    daily: List<Pair<LocalDate, Double>>,
    goalMl: Int,
    today: LocalDate = LocalDate.now(),
    onDayClick: ((LocalDate) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val colors = LocalHydraColors.current
    val rawMax = remember(daily, goalMl) {
        (daily.maxOfOrNull { it.second } ?: 0.0).coerceAtLeast(goalMl.toDouble().coerceAtLeast(1.0))
    }
    val niceMax = remember(rawMax) { niceCeil(rawMax) }

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp),
        ) {
            val gap = with(density) { 6.dp.toPx() }
            val chartTop = 0f
            val chartBottom = size.height - 4f
            val chartHeight = chartBottom - chartTop
            val barCount = daily.size.coerceAtLeast(1)
            val totalGap = gap * (barCount - 1)
            val barWidth = (size.width - totalGap) / barCount

            // Goal line — dashed cyan
            if (goalMl > 0) {
                val frac = (goalMl.toFloat() / niceMax).coerceIn(0f, 1f)
                val goalY = chartBottom - frac * chartHeight
                drawLine(
                    color = Cyan.copy(alpha = 0.6f),
                    start = Offset(0f, goalY),
                    end = Offset(size.width, goalY),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                )
            }

            daily.forEachIndexed { index, (date, ml) ->
                val frac = (ml / niceMax).coerceIn(0.0, 1.0)
                val barHeight = (frac * chartHeight).toFloat().coerceAtLeast(if (ml > 0) 4f else 0f)
                val x = index * (barWidth + gap)
                val y = chartBottom - barHeight
                val isToday = date == today
                val hitGoal = goalMl > 0 && ml >= goalMl
                val brush = when {
                    isToday -> Brush.verticalGradient(listOf(Warn, Warn.copy(alpha = 0.3f)))
                    hitGoal -> Brush.verticalGradient(listOf(Cyan, Cyan.copy(alpha = 0.3f)))
                    ml > 0 -> Brush.verticalGradient(listOf(Cyan.copy(alpha = 0.45f), Cyan.copy(alpha = 0.15f)))
                    else -> Brush.verticalGradient(listOf(colors.hair, colors.hair))
                }
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(x + barWidth * 0.11f, y),
                    size = Size(barWidth * 0.78f, barHeight),
                    cornerRadius = CornerRadius(4f, 4f),
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            daily.forEach { (date, _) ->
                val isToday = date == today
                val label = date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
                Text(
                    text = label,
                    color = if (isToday) Warn else colors.inkDim,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .weight(1f)
                        .let {
                            if (onDayClick != null) it.clickable { onDayClick(date) } else it
                        }
                        .padding(vertical = 2.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
