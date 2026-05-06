package com.hydra.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun HydraTheme(
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (dark) HydraColors.Dark else HydraColors.Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = Cyan,
            onPrimary = InkOnAccent,
            secondary = Violet,
            onSecondary = InkOnAccent,
            tertiary = Warn,
            background = colors.bg,
            onBackground = colors.ink,
            surface = colors.surfaceSolid,
            onSurface = colors.ink,
            surfaceVariant = colors.surfaceSolid,
            onSurfaceVariant = colors.inkSoft,
            outline = colors.hair,
        )
    } else {
        lightColorScheme(
            primary = Cyan,
            onPrimary = InkOnAccent,
            secondary = Violet,
            onSecondary = InkOnAccent,
            tertiary = Warn,
            background = colors.bg,
            onBackground = colors.ink,
            surface = colors.surfaceSolid,
            onSurface = colors.ink,
            surfaceVariant = colors.surfaceSolid,
            onSurfaceVariant = colors.inkSoft,
            outline = colors.hair,
        )
    }

    CompositionLocalProvider(LocalHydraColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography,
            content = content,
        )
    }
}
