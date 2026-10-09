package com.example.syncdrop.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = SyncDropPrimary,
    onPrimary = TextPrimaryDark,
    primaryContainer = SyncDropDarkSurfaceVariant,
    onPrimaryContainer = SyncDropCyan,
    secondary = SyncDropCyan,
    onSecondary = SyncDropDarkBg,
    secondaryContainer = SyncDropDarkCard,
    onSecondaryContainer = SyncDropCyan,
    tertiary = SyncDropViolet,
    background = SyncDropDarkBg,
    onBackground = TextPrimaryDark,
    surface = SyncDropDarkSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = SyncDropDarkSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    outline = SyncDropDarkCardStroke
)

private val LightColorScheme = lightColorScheme(
    primary = SyncDropPrimaryVariant,
    onPrimary = SyncDropLightSurface,
    primaryContainer = SyncDropLightSurfaceVariant,
    onPrimaryContainer = SyncDropPrimaryVariant,
    secondary = SyncDropCyan,
    onSecondary = SyncDropLightSurface,
    secondaryContainer = SyncDropLightCard,
    onSecondaryContainer = SyncDropPrimaryVariant,
    tertiary = SyncDropViolet,
    background = SyncDropLightBg,
    onBackground = TextPrimaryLight,
    surface = SyncDropLightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = SyncDropLightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    outline = SyncDropLightCardStroke
)

@Composable
fun SyncdropTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Use our handcrafted rich palette by default for brand consistency
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}