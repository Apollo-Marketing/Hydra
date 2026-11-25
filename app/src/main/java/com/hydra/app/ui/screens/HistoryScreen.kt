package com.hydra.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydra.app.ble.BottleMath
import com.hydra.app.ble.BottleConfig
import com.hydra.app.data.AppPreferencesRepository
import com.hydra.app.data.HydraDatabase
import com.hydra.app.data.SipRepository
import com.hydra.app.ui.components.GlassCard
import com.hydra.app.ui.components.HourlyBarChart
import com.hydra.app.ui.components.HydraIcon
import com.hydra.app.ui.components.HydraIconName
import com.hydra.app.ui.components.HydrationHeatmap
import com.hydra.app.ui.components.WeeklyBarChart
import com.hydra.app.ui.components.quantizeIntensity
import com.hydra.app.ui.theme.Cyan
import com.hydra.app.ui.theme.InkOnAccent
import com.hydra.app.ui.theme.LocalHydraColors
import com.hydra.app.ui.theme.Violet
import com.hydra.app.ui.theme.Warn
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private enum class HistoryRange(val label: String) { Day("Day"), Week("Week"), Month("Month") }

/** Sentinel for "no date picked — follow today". */
private const val FOLLOW_TODAY = Long.MIN_VALUE

@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val colors = LocalHydraColors.current
    val context = LocalContext.current
    val sipRepo = remember(context) { SipRepository(HydraDatabase.get(context).sipDao()) }
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { AppPreferencesRepository.get(context) }

    val sips by sipRepo.observeAll().collectAsState(initial = emptyList())
    val dailyGoal by prefs.dailyGoalMl.collectAsState(initial = AppPreferencesRepository.DEFAULT_DAILY_GOAL_ML)

    val today = BottleMath.currentLogicalDate()
    val bottleSize = BottleConfig.BOTTLE_SIZE_ML
    val zone = ZoneId.systemDefault()

    var rangeName by rememberSaveable { mutableStateOf(HistoryRange.Week.name) }
    val range = HistoryRange.valueOf(rangeName)
    // The day the visible period ends on (Day: that day; Week: last of 7; Month: any day in it).
    var anchorDay by rememberSaveable { mutableLongStateOf(FOLLOW_TODAY) }
    val anchor = if (anchorDay == FOLLOW_TODAY) today else minOf(LocalDate.ofEpochDay(anchorDay), today)
    fun moveTo(date: LocalDate) {
        anchorDay = if (date >= today) FOLLOW_TODAY else date.toEpochDay()
    }

    // One pass over the history; every view below is a slice of these.
    val events = remember(sips) { BottleMath.levelEvents(sips, bottleSize) }
    val daily = remember(events) {
        val out = HashMap<LocalDate, Double>()
        events.filter { it.kind != BottleMath.LevelEventKind.Refill }.forEach {
            out.merge(BottleMath.logicalDateFor(Instant.ofEpochSecond(it.timestampSec), zone), it.volumeMl, Double::plus)
        }
        out
    }
    val hourly = remember(events) {
        val out = HashMap<LocalDate, DoubleArray>()
        events.filter { it.kind != BottleMath.LevelEventKind.Refill }.forEach {
            val instant = Instant.ofEpochSecond(it.timestampSec)
            val arr = out.getOrPut(BottleMath.logicalDateFor(instant, zone)) { DoubleArray(24) }
            arr[instant.atZone(zone).hour] += it.volumeMl
        }
        out
    }

    val month = YearMonth.from(anchor)
    val canGoForward = when (range) {
        HistoryRange.Month -> month < YearMonth.from(today)
        else -> anchor < today
    }
    val periodLabel = when (range) {
        HistoryRange.Day -> if (anchor == today) "Today" else anchor.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
        HistoryRange.Week -> weekRangeLabel(anchor)
        HistoryRange.Month -> month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
    }
    // +1 when the last move went forward in time, -1 when it went back; picks the slide direction.
    var slideDir by remember { mutableIntStateOf(1) }
    fun step(dir: Long) {
        if (dir > 0 && !canGoForward) return
        slideDir = if (dir > 0) 1 else -1
        moveTo(
            when (range) {
                HistoryRange.Day -> anchor.plusDays(dir)
                HistoryRange.Week -> anchor.plusDays(7 * dir)
                HistoryRange.Month -> month.plusMonths(dir).atEndOfMonth()
            },
        )
    }
    val currentStep by rememberUpdatedState<(Long) -> Unit> { step(it) }
    val swipeThresholdPx = with(LocalDensity.current) { 56.dp.toPx() }
    val openDay: (LocalDate) -> Unit = { date ->
        if (date <= today) {
            moveTo(date)
            rangeName = HistoryRange.Day.name
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Swipe sideways anywhere on the tab to move through time: like turning a page,
            // swiping left goes forward and swiping right goes back. One step per swipe.
            .pointerInput(Unit) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onHorizontalDrag = { _, amount -> dragged += amount },
                    onDragEnd = {
                        if (dragged <= -swipeThresholdPx) currentStep(1L)
                        else if (dragged >= swipeThresholdPx) currentStep(-1L)
                    },
                )
            }
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("History", color = colors.ink, style = MaterialTheme.typography.headlineMedium)
            RangePicker(selected = range, onSelect = { rangeName = it.name })
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton(icon = HydraIconName.Back, enabled = true, onClick = { step(-1) })
            Text(
                periodLabel,
                color = colors.ink,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            StepButton(icon = HydraIconName.Arrow, enabled = canGoForward, onClick = { step(1) })
        }

        AnimatedContent(
            targetState = range to anchor,
            transitionSpec = {
                if (initialState.first != targetState.first) {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                } else {
                    val dir = slideDir
                    (slideInHorizontally(tween(220)) { it * dir } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(220)) { -it * dir } + fadeOut(tween(160)))
                }
            },
            label = "history-period",
        ) { (shownRange, shownAnchor) ->
            // Shadow the outer names so each page keeps drawing its own period while it slides out.
            @Suppress("NAME_SHADOWING") val range = shownRange
            @Suppress("NAME_SHADOWING") val anchor = shownAnchor
            @Suppress("NAME_SHADOWING") val month = YearMonth.from(shownAnchor)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (range) {
                HistoryRange.Day -> {
                    val totalMl = daily[anchor] ?: 0.0
                    val goalPct = if (dailyGoal > 0) (totalMl / dailyGoal * 100).roundToInt() else 0
                    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), contentPadding = 16.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SummaryRow(
                                leftLabel = "Total",
                                leftValue = "%,d".format(totalMl.roundToInt()),
                                leftUnit = " ml",
                                rightLabel = "Of goal",
                                rightValue = goalPct.toString(),
                                rightUnit = " %",
                            )
                            HourlyBarChart(intakePerHour = hourly[anchor] ?: DoubleArray(24))
                        }
                    }

                    val rows = remember(events, anchor) { sipsForDay(events, anchor, zone) }
                    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), contentPadding = 14.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                if (anchor == today) "Today's sips" else "Sips",
                                color = colors.ink,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            if (rows.isEmpty()) {
                                Text("No sips logged this day.", color = colors.inkDim, style = MaterialTheme.typography.bodySmall)
                            } else {
                                rows.forEachIndexed { idx, entry ->
                                    SipTimelineRow(
                                        entry = entry,
                                        last = idx == rows.lastIndex,
                                        onDelete = if (entry.isManual) {
                                            { scope.launch { sipRepo.deleteManual(entry.timestampSec) } }
                                        } else null,
                                    )
                                }
                            }
                        }
                    }
                }

                HistoryRange.Week -> {
                    val week = (6 downTo 0).map { anchor.minusDays(it.toLong()) }.map { it to (daily[it] ?: 0.0) }
                    val avgMl = week.map { it.second }.average()
                    val goalsHit = week.count { dailyGoal > 0 && it.second >= dailyGoal }
                    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), contentPadding = 16.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SummaryRow(
                                leftLabel = "Daily average",
                                leftValue = "%,d".format(avgMl.roundToInt()),
                                leftUnit = " ml",
                                rightLabel = "Goals hit",
                                rightValue = goalsHit.toString(),
                                rightUnit = " / ${week.size}",
                            )
                            // Transparent tap targets over each bar + label column open that day.
                            Box {
                                WeeklyBarChart(daily = week, goalMl = dailyGoal, today = today)
                                Row(modifier = Modifier.matchParentSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    week.forEach { (date, _) ->
                                        Box(modifier = Modifier.weight(1f).fillMaxSize().clickable { openDay(date) })
                                    }
                                }
                            }
                        }
                    }

                    val heatmapMaxMl = week.maxOfOrNull { (date, _) -> hourly[date]?.slice(6 until 22)?.maxOrNull() ?: 0.0 } ?: 0.0
                    // Rows are labelled Monday-first, so order the 7 days by weekday.
                    val heatmap = week.sortedBy { it.first.dayOfWeek }.map { (date, _) ->
                        val arr = hourly[date]
                        (6 until 22).map { h -> quantizeIntensity(arr?.get(h) ?: 0.0, heatmapMaxMl) }
                    }
                    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), contentPadding = 14.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("When you drink", color = colors.ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                                HeatmapLegend()
                            }
                            HydrationHeatmap(intensities = heatmap, cols = 16)
                        }
                    }
                }

                HistoryRange.Month -> {
                    // Average and goal count only cover days that have happened.
                    val lastDay = if (month == YearMonth.from(today)) today.dayOfMonth else month.lengthOfMonth()
                    val days = (1..lastDay).map { month.atDay(it) }.map { it to (daily[it] ?: 0.0) }
                    val avgMl = days.map { it.second }.average()
                    val goalsHit = days.count { dailyGoal > 0 && it.second >= dailyGoal }
                    val best = days.maxByOrNull { it.second }?.takeIf { it.second > 0 }
                    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), contentPadding = 16.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            SummaryRow(
                                leftLabel = "Daily average",
                                leftValue = "%,d".format(avgMl.roundToInt()),
                                leftUnit = " ml",
                                rightLabel = "Goals hit",
                                rightValue = goalsHit.toString(),
                                rightUnit = " / ${days.size}",
                            )
                            MonthCalendar(
                                month = month,
                                daily = daily,
                                goalMl = dailyGoal,
                                today = today,
                                onDayClick = openDay,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    best?.let { (date, ml) ->
                                        "Best day: ${date.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))} · ${"%,d".format(ml.roundToInt())} ml"
                                    } ?: "No sips this month.",
                                    color = colors.inkSoft,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                HeatmapLegend()
                            }
                        }
                    }
                }
            }
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun StepButton(icon: HydraIconName, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalHydraColors.current
    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        HydraIcon(name = icon, size = 18.dp, color = if (enabled) colors.ink else colors.hair)
    }
}

@Composable
private fun SummaryRow(
    leftLabel: String,
    leftValue: String,
    leftUnit: String,
    rightLabel: String,
    rightValue: String,
    rightUnit: String,
) {
    val colors = LocalHydraColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(leftLabel, color = colors.inkSoft, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(leftValue, color = colors.ink, fontWeight = FontWeight.SemiBold, fontSize = 36.sp)
                Text(leftUnit, color = colors.inkSoft, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(rightLabel, color = colors.inkSoft, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(rightValue, color = Cyan, fontWeight = FontWeight.SemiBold, fontSize = 24.sp)
                Text(rightUnit, color = colors.inkSoft, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

/**
 * Month grid: one cell per day, shaded by intake relative to the goal (or the month's best
 * day if that is higher). Days that hit the goal get a cyan ring; today's number is orange.
 */
@Composable
private fun MonthCalendar(
    month: YearMonth,
    daily: Map<LocalDate, Double>,
    goalMl: Int,
    today: LocalDate,
    onDayClick: (LocalDate) -> Unit,
) {
    val colors = LocalHydraColors.current
    val firstDow = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val weekdays = (0L until 7L).map { firstDow.plus(it) }
    val lead = (month.atDay(1).dayOfWeek.value - firstDow.value + 7) % 7
    val cells: List<LocalDate?> = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    val scaleMax = maxOf(
        goalMl.toDouble(),
        (1..month.lengthOfMonth()).maxOf { daily[month.atDay(it)] ?: 0.0 },
    )
    val cellShape = RoundedCornerShape(8.dp)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            weekdays.forEach { dow ->
                Text(
                    dow.name.take(1),
                    color = colors.inkDim,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        cells.chunked(7).forEach { weekCells ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 7) {
                    val date = weekCells.getOrNull(i)
                    if (date == null) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                        continue
                    }
                    val ml = daily[date] ?: 0.0
                    val future = date > today
                    val level = quantizeIntensity(ml, scaleMax)
                    val hitGoal = goalMl > 0 && ml >= goalMl
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(cellShape)
                            .background(
                                when {
                                    future -> Color.Transparent
                                    level == 0 -> colors.hair
                                    else -> Cyan.copy(alpha = (0.10f + level * 0.20f).coerceAtMost(1f))
                                },
                            )
                            .let { if (hitGoal) it.border(BorderStroke(1.5.dp, Cyan), cellShape) else it }
                            .clickable(enabled = !future) { onDayClick(date) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            date.dayOfMonth.toString(),
                            color = when {
                                future -> colors.inkDim
                                date == today -> Warn
                                else -> colors.ink
                            },
                            fontWeight = if (date == today) FontWeight.Bold else FontWeight.Medium,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RangePicker(selected: HistoryRange, onSelect: (HistoryRange) -> Unit) {
    val colors = LocalHydraColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .border(BorderStroke(1.dp, colors.hair), shape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HistoryRange.entries.forEach { option ->
            val active = option == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (active) Brush.linearGradient(listOf(Cyan, Violet))
                        else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Text(
                    option.label,
                    color = if (active) InkOnAccent else colors.inkSoft,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun HeatmapLegend() {
    val colors = LocalHydraColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Less", color = colors.inkDim, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            (1..4).forEach { v ->
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Cyan.copy(alpha = (0.10f + v * 0.20f).coerceAtMost(1f))),
                )
            }
        }
        Text("More", color = colors.inkDim, style = MaterialTheme.typography.labelSmall)
    }
}

private data class SipRow(
    val timestampSec: Long,
    val timeLabel: String,
    val ml: Int,
    val refill: Boolean,
    val isManual: Boolean = false,
)

@Composable
private fun SipTimelineRow(entry: SipRow, last: Boolean, onDelete: (() -> Unit)? = null) {
    val colors = LocalHydraColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            entry.timeLabel,
            color = colors.inkSoft,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(64.dp),
        )
        Text(
            if (entry.refill) "Refill" else "Sip",
            color = if (entry.refill) Violet else colors.ink,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(
            buildString {
                if (entry.refill) append("+")
                append(entry.ml)
                append(" ml")
            },
            color = if (entry.refill) Violet else colors.ink,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
        if (onDelete != null) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "×",
                    color = colors.inkDim,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }
        }
    }
    if (!last) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.hair),
        )
    }
}

private fun weekRangeLabel(end: LocalDate): String {
    val start = end.minusDays(6)
    val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    return "${start.format(fmt)} — ${end.format(fmt)}"
}

/** Drinks, refills and manual sips on one logical date, newest first. */
private fun sipsForDay(events: List<BottleMath.LevelEvent>, date: LocalDate, zone: ZoneId): List<SipRow> {
    val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    return events.mapNotNull { ev ->
        val instant = Instant.ofEpochSecond(ev.timestampSec)
        if (BottleMath.logicalDateFor(instant, zone) != date) return@mapNotNull null
        SipRow(
            timestampSec = ev.timestampSec,
            timeLabel = instant.atZone(zone).format(timeFmt).lowercase(),
            ml = ev.volumeMl.roundToInt(),
            refill = ev.kind == BottleMath.LevelEventKind.Refill,
            isManual = ev.kind == BottleMath.LevelEventKind.Manual,
        )
    }.sortedByDescending { it.timestampSec }
}
