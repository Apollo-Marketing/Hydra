package com.hydra.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import com.hydra.app.data.AppPreferencesRepository
import com.hydra.app.data.AppPreferencesRepository.ThemeMode
import com.hydra.app.ui.MainScreen
import com.hydra.app.ui.theme.HydraTheme
import com.hydra.app.update.UpdateController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = AppPreferencesRepository.get(this)
        val updates = UpdateController.get(this)
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
            HydraTheme(dark = dark) {
                MainScreen()
            }
        }
    }
}
