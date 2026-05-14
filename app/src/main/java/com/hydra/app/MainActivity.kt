package com.hydra.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.hydra.app.data.AppPreferencesRepository
import com.hydra.app.data.AppPreferencesRepository.ThemeMode
import com.hydra.app.ui.MainScreen
import com.hydra.app.ui.components.HealthConnectPrivacyDialog
import com.hydra.app.ui.theme.HydraTheme
import com.hydra.app.update.UpdateController

/** Health Connect deep-links into our app with this action when the user taps "Read privacy policy". */
private const val ACTION_HC_RATIONALE = "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
private const val CATEGORY_HEALTH_PERMISSIONS = "android.intent.category.HEALTH_PERMISSIONS"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = AppPreferencesRepository.get(this)
        val updates = UpdateController.get(this)
        val launchedForHealthConnectRationale = intent.isHealthConnectRationale()
        setContent {
            val mode by prefs.themeMode.collectAsState(initial = ThemeMode.System)
            val dark = when (mode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
            }
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
            LaunchedEffect(Unit) { updates.checkOnLaunch() }
            var hcPrivacyOpen by rememberSaveable { mutableStateOf(launchedForHealthConnectRationale) }
            HydraTheme(dark = dark) {
                MainScreen()
                HealthConnectPrivacyDialog(open = hcPrivacyOpen, onDismiss = { hcPrivacyOpen = false })
            }
        }
    }
}

private fun Intent?.isHealthConnectRationale(): Boolean {
    if (this == null) return false
    if (action == ACTION_HC_RATIONALE) return true
    return action == Intent.ACTION_VIEW_PERMISSION_USAGE &&
        categories?.contains(CATEGORY_HEALTH_PERMISSIONS) == true
}
