package com.hydra.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydra.app.ble.BottleConfig
import com.hydra.app.data.ActiveFilter
import com.hydra.app.data.AppPreferencesRepository
import com.hydra.app.data.FilterRepository
import com.hydra.app.data.HydraDatabase
import com.hydra.app.data.SipRepository
import com.hydra.app.filter.FilterHealth
import com.hydra.app.filter.FilterMath
import com.hydra.app.filter.FilterReminder
import com.hydra.app.filter.FilterStatus
import com.hydra.app.filter.FilterType
import com.hydra.app.ui.components.GlassCard
import com.hydra.app.ui.components.HydraIcon
import com.hydra.app.ui.components.HydraIconName
import com.hydra.app.ui.components.InsetCard
import com.hydra.app.ui.theme.Cyan
import com.hydra.app.ui.theme.InkOnAccent
import com.hydra.app.ui.theme.LocalHydraColors
import com.hydra.app.ui.theme.Ok
import com.hydra.app.ui.theme.Violet
import com.hydra.app.ui.theme.Warn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Red used for the depleted state. Theme-invariant, like the other accents. */
private val Danger = Color(0xFFFF6B6B)

internal fun FilterStatus.tint(): Color = when (this) {
    FilterStatus.Good -> Ok
    FilterStatus.ReplaceSoon -> Warn
    FilterStatus.ReplaceNow -> Danger
}

internal fun FilterStatus.shortLabel(): String = when (this) {
    FilterStatus.Good -> "Good"
    FilterStatus.ReplaceSoon -> "Replace soon"
    FilterStatus.ReplaceNow -> "Replace now"
}

/** Live filter health for the active filter, or null when no filter is being tracked. */
@Composable
internal fun rememberFilterHealth(active: ActiveFilter?): FilterHealth? {
    val context = LocalContext.current
    val sipRepo = remember(context) { SipRepository(HydraDatabase.get(context).sipDao()) }
    val sips by sipRepo.observeAll().collectAsState(initial = emptyList())
    // Age-based health changes with the clock, not just with data — re-evaluate each minute.
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowSec = System.currentTimeMillis() / 1000L
        }
    }
    return remember(active, sips, nowSec) {
        active?.let { FilterMath.health(it, sips, BottleConfig.BOTTLE_SIZE_ML, nowSec = maxOf(nowSec, System.currentTimeMillis() / 1000L)) }
    }
}

@Composable
fun FilterScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalHydraColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filterRepo = remember(context) { FilterRepository(HydraDatabase.get(context)) }
    val prefs = remember(context) { AppPreferencesRepository.get(context) }

    // `loaded` distinguishes "still reading the database" from "no filter" so the empty state
    // doesn't flash for a frame when a filter exists.
    var loaded by remember { mutableStateOf(false) }
    val active by remember(filterRepo) { filterRepo.observeActive() }.collectAsState(initial = null)
    LaunchedEffect(filterRepo) { filterRepo.observeActive().collect { loaded = true } }
    val health = rememberFilterHealth(active)
    val reminderOn by prefs.filterReminderEnabled.collectAsState(initial = false)

    var installDialog by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scope.launch { prefs.setFilterReminderEnabled(true) }
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                HydraIcon(name = HydraIconName.Back, size = 20.dp, color = colors.ink)
            }
            Text(
                active?.let { "${FilterType.fromKey(it.filter.type).label} filter" } ?: "Filter",
                color = colors.ink,
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        val current = active
        val currentHealth = health
        when {
            !loaded -> Unit
            current == null || currentHealth == null -> NoFilterCard(onInstall = { installDialog = true })
            else -> {
                FilterHealthCard(active = current, health = currentHealth)

                Text(
                    "The filter has a finite capacity and needs to be replaced to keep working well. " +
                        "Health is the lower of water filtered against its " +
                        "${FilterType.fromKey(current.filter.type).capacityL.toInt()} L capacity and its age against a 60-day life.",
                    color = colors.inkSoft,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )

                ActionGroup {
                    ActionRow(label = "Replace filter now", onClick = { installDialog = true })
                    ActionRow(label = "Remove filter", last = true, onClick = { confirmRemove = true })
                }

                ActionGroup {
                    if (current.isPaused) {
                        ActionRow(
                            label = "Put filter back",
                            sub = "Resumes counting. Filter health is kept.",
                            last = true,
                            onClick = { scope.launch { filterRepo.resume() } },
                        )
                    } else {
                        ActionRow(
                            label = "Temporarily remove filter",
                            sub = "Water you drink without it won't count against it.",
                            last = true,
                            onClick = { scope.launch { filterRepo.pause() } },
                        )
                    }
                }

                ActionGroup {
                    ActionRow(
                        label = "Replacement reminder",
                        sub = "Notify me when the filter needs replacing.",
                        toggle = reminderOn,
                        last = true,
                        onClick = {
                            when {
                                reminderOn -> scope.launch { prefs.setFilterReminderEnabled(false) }
                                FilterReminder.hasNotificationPermission(context) ->
                                    scope.launch { prefs.setFilterReminderEnabled(true) }
                                Build.VERSION.SDK_INT >= 33 ->
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(80.dp))
    }

    if (installDialog) {
        InstallFilterDialog(
            replacing = active != null,
            initialType = active?.let { FilterType.fromKey(it.filter.type) } ?: FilterType.Essential,
            onConfirm = { type, atSec ->
                scope.launch { filterRepo.install(type, atSec) }
                installDialog = false
            },
            onDismiss = { installDialog = false },
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = colors.surfaceSolid,
            titleContentColor = colors.ink,
            textContentColor = colors.inkSoft,
            title = { Text("Remove this filter?") },
            text = {
                Text(
                    "Hydra will stop tracking this filter and its health can't be restored. " +
                        "If you're only taking it out for a while, use \"Temporarily remove filter\" instead.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { filterRepo.remove() }
                    confirmRemove = false
                }) { Text("Remove", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel", color = colors.inkSoft) }
            },
        )
    }
}

@Composable
private fun NoFilterCard(onInstall: () -> Unit) {
    val colors = LocalHydraColors.current
    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), contentPadding = 18.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No filter being tracked", color = colors.ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(
                "Add the filter in your bottle to track its health, how much water it has filtered, and when to replace it. " +
                    "If it's already been in for a while, enter the day you installed it and Hydra will count the water since then.",
                color = colors.inkSoft,
                style = MaterialTheme.typography.bodyMedium,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(Cyan, Violet)))
                    .clickable(onClick = onInstall)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Add a filter", color = InkOnAccent, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun FilterHealthCard(active: ActiveFilter, health: FilterHealth) {
    val colors = LocalHydraColors.current
    val zone = ZoneId.systemDefault()
    val installedOn = Instant.ofEpochSecond(active.filter.installedAtSec).atZone(zone).toLocalDate()
        .format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault()))

    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), contentPadding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when {
                active.isPaused -> Banner(
                    text = "Filter is temporarily removed. Water you drink isn't counted against it.",
                    tint = Violet,
                )
                health.status == FilterStatus.ReplaceNow -> Banner(
                    text = "Your filter is depleted. Replace it as soon as possible.",
                    tint = Danger,
                )
                health.status == FilterStatus.ReplaceSoon -> Banner(
                    text = "Your filter is running low. Plan to replace it soon.",
                    tint = Warn,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(modifier = Modifier.weight(0.8f), contentAlignment = Alignment.Center) {
                    FilterCartridge(percent = health.percent, tint = health.status.tint(), width = 78.dp)
                }
                Column(modifier = Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(label = "Volume filtered", value = "%.1f".format(health.volumeFilteredL), unit = "liters")
                    StatTile(
                        label = "Filter age",
                        value = health.ageDays.toString(),
                        unit = if (health.ageDays == 1) "day" else "days",
                    )
                }
            }

            Text(
                "Filter installed on $installedOn.",
                color = colors.inkSoft,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Banner(text: String, tint: Color) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tint.copy(alpha = 0.16f))
            .border(BorderStroke(1.dp, tint.copy(alpha = 0.5f)), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            color = tint,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String) {
    val colors = LocalHydraColors.current
    InsetCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label.uppercase(), color = colors.inkSoft, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, color = colors.ink, fontWeight = FontWeight.SemiBold, fontSize = 24.sp)
                Text(
                    " $unit",
                    color = colors.inkSoft,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

/**
 * Filter cartridge drawing: two end caps and a body that fills from the bottom with
 * [percent] of remaining health, with the percentage centered on top.
 */
@Composable
internal fun FilterCartridge(percent: Int, tint: Color, width: Dp, modifier: Modifier = Modifier) {
    val colors = LocalHydraColors.current
    val outline = colors.inkSoft
    val empty = colors.hair
    Box(modifier = modifier.width(width).height(width * 2.3f), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val sw = w * 0.035f
            val capH = h * 0.085f
            val nubH = h * 0.045f
            val bodyInset = w * 0.07f
            val bodyTop = nubH + capH
            val bodyBottom = h - nubH - capH
            val bodyH = bodyBottom - bodyTop
            val r = CornerRadius(w * 0.06f, w * 0.06f)

            // Body: empty track, then the remaining-health fill rising from the bottom.
            drawRect(color = empty, topLeft = Offset(bodyInset, bodyTop), size = Size(w - 2 * bodyInset, bodyH))
            val fillH = bodyH * (percent.coerceIn(0, 100) / 100f)
            if (fillH > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.9f)),
                        startY = bodyBottom - fillH,
                        endY = bodyBottom,
                    ),
                    topLeft = Offset(bodyInset, bodyBottom - fillH),
                    size = Size(w - 2 * bodyInset, fillH),
                )
            }
            drawRect(color = outline, topLeft = Offset(bodyInset, bodyTop), size = Size(w - 2 * bodyInset, bodyH), style = Stroke(sw))

            // End caps and their nubs.
            val nubW = w * 0.5f
            val nubX = (w - nubW) / 2f
            drawRoundRect(color = outline, topLeft = Offset(nubX, 0f), size = Size(nubW, nubH + sw), cornerRadius = r, style = Stroke(sw))
            drawRoundRect(color = outline, topLeft = Offset(nubX, h - nubH - sw), size = Size(nubW, nubH + sw), cornerRadius = r, style = Stroke(sw))
            drawRoundRect(color = outline, topLeft = Offset(sw / 2, nubH), size = Size(w - sw, capH), cornerRadius = r, style = Stroke(sw))
            drawRoundRect(color = outline, topLeft = Offset(sw / 2, bodyBottom), size = Size(w - sw, capH), cornerRadius = r, style = Stroke(sw))
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceSolid)
                .border(BorderStroke(1.dp, colors.hair), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text("$percent%", color = colors.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun ActionGroup(content: @Composable () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), contentPadding = 0.dp) {
        Column { content() }
    }
}

@Composable
private fun ActionRow(
    label: String,
    sub: String? = null,
    toggle: Boolean? = null,
    last: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalHydraColors.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, color = colors.ink, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (sub != null) Text(sub, color = colors.inkSoft, style = MaterialTheme.typography.bodySmall)
            }
            if (toggle != null) {
                Row(
                    modifier = Modifier
                        .size(width = 38.dp, height = 22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (toggle) Brush.linearGradient(listOf(Cyan, Violet))
                            else Brush.linearGradient(listOf(colors.hair, colors.hair)),
                        )
                        .padding(2.dp),
                    horizontalArrangement = if (toggle) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(Color.White))
                }
            } else {
                HydraIcon(name = HydraIconName.Arrow, size = 14.dp, color = colors.inkDim)
            }
        }
        if (!last) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(colors.hair))
        }
    }
}

/**
 * Picks the filter type and the day it went in. For past days the install time is the start
 * of that day, so all of that day's water counts; for today it is "now".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallFilterDialog(
    replacing: Boolean,
    initialType: FilterType,
    onConfirm: (FilterType, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalHydraColors.current
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    var typeName by rememberSaveable { mutableStateOf(initialType.name) }
    var dateEpochDay by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    val date = LocalDate.ofEpochDay(dateEpochDay)
    val dateLabel = if (date == today) "Today" else date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault()))

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceSolid,
        titleContentColor = colors.ink,
        textContentColor = colors.inkSoft,
        title = { Text(if (replacing) "Replace filter" else "Add a filter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (replacing) {
                    Text(
                        "The current filter will be retired and the new one starts at 100%.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                FilterType.entries.forEach { type ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { typeName = type.name }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(
                            selected = typeName == type.name,
                            onClick = { typeName = type.name },
                            colors = RadioButtonDefaults.colors(selectedColor = Cyan, unselectedColor = colors.inkDim),
                        )
                        Column {
                            Text(type.label, color = colors.ink, style = MaterialTheme.typography.bodyLarge)
                            Text("${type.capacityL.toInt()} L capacity", color = colors.inkSoft, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(BorderStroke(1.dp, colors.hair), RoundedCornerShape(12.dp))
                        .clickable { pickingDate = true }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Installed on", color = colors.inkSoft, style = MaterialTheme.typography.bodyMedium)
                    Text(dateLabel, color = Cyan, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val atSec = if (date == today) {
                    System.currentTimeMillis() / 1000L
                } else {
                    date.atStartOfDay(zone).toEpochSecond()
                }
                onConfirm(FilterType.valueOf(typeName), atSec)
            }) { Text(if (replacing) "Replace" else "Add", color = Cyan) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.inkSoft) }
        },
    )

    if (pickingDate) {
        // The picker works in UTC-midnight millis; convert to/from a plain calendar date.
        val todayUtcMs = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayUtcMs
                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        dateEpochDay = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
