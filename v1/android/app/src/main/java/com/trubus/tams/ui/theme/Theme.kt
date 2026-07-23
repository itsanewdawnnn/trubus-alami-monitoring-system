package com.trubus.tams.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// No dedicated dark variant of the "Bento" design exists -- every custom
// color in Color.kt was tuned for the light treatment used throughout
// MainAppScreen. Resolving both branches to this same scheme (dynamic color
// is also disabled below) keeps the UI consistent regardless of system theme,
// instead of falling back to the stock template's unrelated purple/pink.
private val LightColorScheme = lightColorScheme(
    primary = BentoPrimary,
    onPrimary = Color.White,
    primaryContainer = BentoPrimaryContainer,
    onPrimaryContainer = BentoOnPrimaryContainer,
    secondary = BentoActiveMemberOnContainer,
    onSecondary = Color.White,
    secondaryContainer = BentoActiveMemberContainer,
    onSecondaryContainer = BentoActiveMemberOnContainer,
    tertiary = BentoSystemStatusOnContainer,
    onTertiary = Color.White,
    tertiaryContainer = BentoSystemStatusContainer,
    onTertiaryContainer = BentoSystemStatusOnContainer,
    background = BentoBackground,
    onBackground = BentoOnSurface,
    surface = BentoSurface,
    onSurface = BentoOnSurface,
    surfaceVariant = BentoNavBar,
    onSurfaceVariant = BentoActiveMemberOnContainer,
    outline = BentoBorder,
    outlineVariant = BentoBorder
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false, // Set to false to strictly enforce the Bento theme
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        else -> LightColorScheme
    }

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
