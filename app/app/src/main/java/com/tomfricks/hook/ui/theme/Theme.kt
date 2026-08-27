package com.tomfricks.hook.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnBackground,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnBackground,
    tertiary = Accent,
    onTertiary = OnPrimary,
    error = Error,
    onError = OnPrimary,
    errorContainer = Error,
    onErrorContainer = OnPrimary,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = Color.Black,
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
    inversePrimary = Primary
)

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnBackground,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnBackground,
    tertiary = Accent,
    onTertiary = OnPrimary,
    error = Error,
    onError = OnPrimary,
    errorContainer = Error,
    onErrorContainer = OnPrimary,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    outlineVariant = OutlineVariant,
    scrim = Color.Black,
    inverseSurface = Color.Black,
    inverseOnSurface = Color.White,
    inversePrimary = DarkPrimary
)

@Composable
fun HookTheme(
    themeMode: com.tomfricks.hook.data.ThemeMode = com.tomfricks.hook.data.ThemeMode.SYSTEM,
    // Dynamic color is off by default so the Hook palette stays consistent across devices
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemInDarkTheme = isSystemInDarkTheme()
    
    val darkTheme = when (themeMode) {
        com.tomfricks.hook.data.ThemeMode.LIGHT -> false
        com.tomfricks.hook.data.ThemeMode.DARK -> true
        com.tomfricks.hook.data.ThemeMode.SYSTEM -> systemInDarkTheme
    }
    
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
            // Only set window colors if we're in an Activity context (not keyboard service)
            val context = view.context
            if (context is Activity) {
                val insetsController = WindowCompat.getInsetsController(context.window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography
    ) {
        CompositionLocalProvider(LocalHookDarkTheme provides darkTheme, content = content)
    }
}

/**
 * Whether [HookTheme] resolved to its dark palette.
 *
 * Not the same question as `isSystemInDarkTheme()`: the Theme setting can force
 * Light or Dark against the system, and that override lives entirely in Compose
 * — it never reaches the resource configuration. Anything that has to pick a
 * light-vs-dark *asset* must read this, because `-night` resource qualifiers
 * follow the system and would show the wrong artwork whenever the two disagree.
 */
val LocalHookDarkTheme = staticCompositionLocalOf { false }