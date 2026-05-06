package com.hydra.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydra.app.BuildConfig
import com.hydra.app.ble.BottleScanner
import com.hydra.app.ble.DiscoveredBottle
import com.hydra.app.ble.ScanState
import com.hydra.app.data.AppPreferencesRepository
import com.hydra.app.data.AppPreferencesRepository.ThemeMode
import com.hydra.app.data.HydraDatabase
import com.hydra.app.data.SavedBottleEntity
import com.hydra.app.data.SavedBottlesRepository
import com.hydra.app.ui.components.GlassCard
import com.hydra.app.ui.components.HydraIcon
import com.hydra.app.ui.components.HydraIconName
import com.hydra.app.ui.components.rememberRelativeTime
import com.hydra.app.ui.theme.Cyan
import com.hydra.app.ui.theme.InkOnAccent
import com.hydra.app.ui.theme.LocalHydraColors
import com.hydra.app.ui.theme.Violet
import com.hydra.app.update.UpdateController
import kotlinx.coroutines.launch

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

private const val GOAL_MIN_ML = 1500
private const val GOAL_MAX_ML = 4000
private val PRESETS_L = listOf(1.8f, 2.0f, 2.4f, 3.0f)

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val colors = LocalHydraColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedRepo = remember(context) {
        SavedBottlesRepository(HydraDatabase.get(context).savedBottleDao())
    }
    val prefs = remember(context) { AppPreferencesRepository.get(context) }
    val scanner = remember(context) { BottleScanner(context) }
    val updateController = remember(context) { UpdateController.get(context) }

    val savedBottles by savedRepo.observeAll().collectAsState(initial = emptyList())
    val discovered by scanner.discovered.collectAsState()
    val scanState by scanner.state.collectAsState()
    val dailyGoalMl by prefs.dailyGoalMl.collectAsState(initial = AppPreferencesRepository.DEFAULT_DAILY_GOAL_ML)
    val themeMode by prefs.themeMode.collectAsState(initial = ThemeMode.System)
    val showStreak by prefs.showStreak.collectAsState(initial = true)
    val autoUpdateEnabled by prefs.autoUpdateEnabled.collectAsState(initial = true)
    val lastUpdateCheckMs by prefs.lastUpdateCheckMs.collectAsState(initial = 0L)

    var pendingRemove: SavedBottleEntity? by remember { mutableStateOf(null) }
    var scanSheetOpen by rememberSaveable { mutableStateOf(false) }
    var themePickerOpen by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(scanner) {
        onDispose { scanner.stop() }
    }

    LaunchedEffect(scanSheetOpen, scanner) {
        if (scanSheetOpen) scanner.start()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            scanSheetOpen = true
            scanner.start()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(6.dp))

        Column(modifier = Modifier.padding(horizontal = 22.dp)) {
            Text("Settings", color = colors.ink, style = MaterialTheme.typography.headlineMedium)
            Text("Hydra · Personal account", color = colors.inkSoft, style = MaterialTheme.typography.bodyMedium)
        }

        DailyGoalCard(
            goalMl = dailyGoalMl,
            onGoalChange = { newGoal -> scope.launch { prefs.setDailyGoal(newGoal) } },
        )

        SettingsGroup(title = "Bottle") {
            if (savedBottles.isEmpty()) {
                SettingsRow(
                    label = "Pair a new bottle",
                    value = "Tap to scan",
                    end = RowEnd.Arrow,
                    onClick = { permissionLauncher.launch(REQUIRED_PERMISSIONS) },
                )
            } else {
                savedBottles.forEach { bottle ->
                    SettingsRow(
                        label = bottle.name,
                        value = "Saved",
                        end = RowEnd.Arrow,
                        onClick = { pendingRemove = bottle },
                    )
                }
                SettingsRow(
                    label = "Pair another bottle",
                    end = RowEnd.Arrow,
                    onClick = { permissionLauncher.launch(REQUIRED_PERMISSIONS) },
                )
                SettingsRow(
                    label = "Units",
                    value = "Milliliters",
                    end = RowEnd.None,
                    last = true,
                    onClick = {},
                )
            }
        }

        SettingsGroup(title = "Display") {
            SettingsRow(
                label = "Theme",
                value = themeMode.label(),
                end = RowEnd.Arrow,
                onClick = { themePickerOpen = true },
            )
            SettingsRow(
                label = "Show streak",
                end = if (showStreak) RowEnd.ToggleOn else RowEnd.ToggleOff,
                last = true,
                onClick = { scope.launch { prefs.setShowStreak(!showStreak) } },
            )
        }

        val lastCheckRelative = rememberRelativeTime(lastUpdateCheckMs, neverLabel = "Never checked")
        val checkLabel = if (lastUpdateCheckMs == 0L) {
            lastCheckRelative
        } else {
            "Last checked $lastCheckRelative"
        }
        SettingsGroup(title = "About") {
            SettingsRow(
                label = "Version",
                value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                end = RowEnd.None,
                onClick = {},
            )
            SettingsRow(
                label = "Check for updates",
                value = checkLabel,
                end = RowEnd.Arrow,
                onClick = { updateController.checkNow() },
            )
            SettingsRow(
                label = "Auto-check on launch",
                end = if (autoUpdateEnabled) RowEnd.ToggleOn else RowEnd.ToggleOff,
                last = true,
                onClick = { scope.launch { prefs.setAutoUpdateEnabled(!autoUpdateEnabled) } },
            )
        }

        Spacer(Modifier.height(80.dp))
    }

    if (scanSheetOpen) {
        ScanSheet(
            scanState = scanState,
            discovered = discovered,
            savedNames = savedBottles.map { it.name }.toSet(),
            onPick = { bottle ->
                scope.launch {
                    savedRepo.save(name = bottle.name, address = bottle.address)
                    scanner.stop()
                    scanSheetOpen = false
                }
            },
            onDismiss = {
                scanner.stop()
                scanSheetOpen = false
            },
        )
    }

    if (themePickerOpen) {
        ThemePickerDialog(
            current = themeMode,
            onPick = { mode ->
                scope.launch { prefs.setThemeMode(mode) }
                themePickerOpen = false
            },
            onDismiss = { themePickerOpen = false },
        )
    }

    pendingRemove?.let { bottle ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            containerColor = colors.surfaceSolid,
            titleContentColor = colors.ink,
            textContentColor = colors.inkSoft,
            title = { Text("Remove this bottle?") },
            text = { Text("\"${bottle.name}\" will be removed from your saved bottles. You can pair it again any time.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { savedRepo.remove(bottle.name) }
                    pendingRemove = null
                }) { Text("Remove", color = Cyan) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Cancel", color = colors.inkSoft) }
            },
        )
    }
}

@Composable
private fun DailyGoalCard(goalMl: Int, onGoalChange: (Int) -> Unit) {
    val colors = LocalHydraColors.current
    var sliderValue by remember(goalMl) { mutableFloatStateOf(goalMl.toFloat()) }
    val display = sliderValue.toInt()

    GlassCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        contentPadding = 18.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Daily goal", color = colors.inkSoft, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "%,d".format(display),
                    style = TextStyle(
                        brush = Brush.linearGradient(listOf(Cyan, Violet)),
                        fontSize = 56.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    " ml / day",
                    color = colors.inkSoft,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onGoalChange(sliderValue.toInt()) },
                valueRange = GOAL_MIN_ML.toFloat()..GOAL_MAX_ML.toFloat(),
                steps = (GOAL_MAX_ML - GOAL_MIN_ML) / 100 - 1,
                colors = SliderDefaults.colors(
                    thumbColor = Cyan,
                    activeTrackColor = Cyan,
                    inactiveTrackColor = colors.hair,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${GOAL_MIN_ML / 1000.0} L", color = colors.inkSoft, style = MaterialTheme.typography.labelMedium)
                Text("${GOAL_MAX_ML / 1000.0} L", color = colors.inkSoft, style = MaterialTheme.typography.labelMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PRESETS_L.forEach { liters ->
                    val ml = (liters * 1000).toInt()
                    val active = ml == display
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (active) Brush.linearGradient(listOf(Cyan, Violet))
                                else Brush.linearGradient(listOf(colors.hair, colors.hair)),
                            )
                            .clickable {
                                sliderValue = ml.toFloat()
                                onGoalChange(ml)
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            "$liters L",
                            color = if (active) InkOnAccent else colors.inkSoft,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    val colors = LocalHydraColors.current
    Column(
        modifier = Modifier.padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title.uppercase(),
            color = colors.inkSoft,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 0.dp) {
            Column { content() }
        }
    }
}

private enum class RowEnd { Arrow, ToggleOn, ToggleOff, None }

@Composable
private fun SettingsRow(
    label: String,
    value: String? = null,
    end: RowEnd = RowEnd.None,
    last: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalHydraColors.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, color = colors.ink, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (value != null) {
                    Text(value, color = colors.inkSoft, style = MaterialTheme.typography.bodySmall)
                }
            }
            when (end) {
                RowEnd.Arrow -> HydraIcon(name = HydraIconName.Arrow, size = 14.dp, color = colors.inkDim)
                RowEnd.ToggleOn -> Toggle(on = true)
                RowEnd.ToggleOff -> Toggle(on = false)
                RowEnd.None -> {}
            }
        }
        if (!last) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(1.dp)
                    .background(colors.hair),
            )
        }
    }
}

@Composable
private fun Toggle(on: Boolean) {
    val colors = LocalHydraColors.current
    val shape = RoundedCornerShape(11.dp)
    Row(
        modifier = Modifier
            .size(width = 38.dp, height = 22.dp)
            .clip(shape)
            .background(
                if (on) Brush.linearGradient(listOf(Cyan, Violet))
                else Brush.linearGradient(listOf(colors.hair, colors.hair)),
            )
            .padding(2.dp),
        horizontalArrangement = if (on) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun ScanSheet(
    scanState: ScanState,
    discovered: List<DiscoveredBottle>,
    savedNames: Set<String>,
    onPick: (DiscoveredBottle) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalHydraColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceSolid,
        titleContentColor = colors.ink,
        textContentColor = colors.inkSoft,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Nearby bottles", color = colors.ink)
                if (scanState is ScanState.Scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Cyan,
                    )
                }
            }
        },
        text = {
            Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                when {
                    scanState is ScanState.Error -> {
                        Text(scanState.message, color = colors.inkSoft, modifier = Modifier.align(Alignment.Center))
                    }
                    discovered.isEmpty() && scanState is ScanState.Scanning -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Looking for bottles…", color = colors.inkSoft)
                            Text(
                                "Make sure your bottle is awake — pick it up or give it a shake.",
                                color = colors.inkDim,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                    discovered.isEmpty() -> {
                        Text("No bottles found nearby.", color = colors.inkSoft, modifier = Modifier.align(Alignment.Center))
                    }
                    else -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(discovered, key = { it.name }) { b ->
                                val alreadySaved = b.name in savedNames
                                DiscoveredRow(
                                    bottle = b,
                                    alreadySaved = alreadySaved,
                                    onPick = { if (!alreadySaved) onPick(b) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = Cyan) }
        },
    )
}

@Composable
private fun DiscoveredRow(
    bottle: DiscoveredBottle,
    alreadySaved: Boolean,
    onPick: () -> Unit,
) {
    val colors = LocalHydraColors.current
    val border = if (alreadySaved) colors.hair else Cyan
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(BorderStroke(1.dp, border), RoundedCornerShape(14.dp))
            .clickable(enabled = !alreadySaved, onClick = onPick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                bottle.name,
                color = colors.ink,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "${bottle.rssi} dBm  ·  ${bottle.address}",
                color = colors.inkDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (alreadySaved) {
            Text("Saved", color = colors.inkDim, style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Pair", color = Cyan, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ThemePickerDialog(
    current: ThemeMode,
    onPick: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalHydraColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceSolid,
        titleContentColor = colors.ink,
        textContentColor = colors.inkSoft,
        title = { Text("Theme") },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(
                            selected = mode == current,
                            onClick = { onPick(mode) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Cyan,
                                unselectedColor = colors.inkDim,
                            ),
                        )
                        Text(
                            mode.label(),
                            color = colors.ink,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = Cyan) }
        },
    )
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.System -> "System"
    ThemeMode.Dark -> "Dark"
    ThemeMode.Light -> "Light"
}

