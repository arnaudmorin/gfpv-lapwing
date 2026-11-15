package com.gfpv.lapwing.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * GFPV red, the colour of the logo and launcher icon. Wallpaper based dynamic colour
 * (Android 12+) is not used so the app keeps its brand colour on every phone.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFFC00100),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD4),
    onPrimaryContainer = Color(0xFF410000),
    secondary = Color(0xFF775651),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD4),
    onSecondaryContainer = Color(0xFF2C1512),
    tertiary = Color(0xFF705C2E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBDFA6),
    onTertiaryContainer = Color(0xFF251A00),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF231918),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF231918),
    surfaceVariant = Color(0xFFF5DDDA),
    onSurfaceVariant = Color(0xFF534341),
    outline = Color(0xFF857370),
    outlineVariant = Color(0xFFD8C2BE),
    surfaceDim = Color(0xFFE8D6D3),
    surfaceBright = Color(0xFFFFF8F6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0EE),
    surfaceContainer = Color(0xFFFCEAE7),
    surfaceContainerHigh = Color(0xFFF6E4E1),
    surfaceContainerHighest = Color(0xFFF1DEDB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF5A4D),
    onPrimary = Color(0xFF3B0000),
    primaryContainer = Color(0xFF930100),
    onPrimaryContainer = Color(0xFFFFDAD4),
    secondary = Color(0xFFE7BDB6),
    onSecondary = Color(0xFF442925),
    secondaryContainer = Color(0xFF5D3F3B),
    onSecondaryContainer = Color(0xFFFFDAD4),
    tertiary = Color(0xFFDEC48C),
    onTertiary = Color(0xFF3E2E04),
    tertiaryContainer = Color(0xFF564419),
    onTertiaryContainer = Color(0xFFFBDFA6),
    background = Color(0xFF1A1110),
    onBackground = Color(0xFFF1DEDB),
    surface = Color(0xFF1A1110),
    onSurface = Color(0xFFF1DEDB),
    surfaceVariant = Color(0xFF534341),
    onSurfaceVariant = Color(0xFFD8C2BE),
    outline = Color(0xFFA08C89),
    outlineVariant = Color(0xFF534341),
    surfaceDim = Color(0xFF1A1110),
    surfaceBright = Color(0xFF423735),
    surfaceContainerLowest = Color(0xFF140C0B),
    surfaceContainerLow = Color(0xFF231918),
    surfaceContainer = Color(0xFF271D1C),
    surfaceContainerHigh = Color(0xFF322826),
    surfaceContainerHighest = Color(0xFF3D3231),
)

@Composable
fun LapwingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

/** Fixed chart colours, matching the web UI calibration graph. */
object ChartColors {
    val rssi = Color(0xFF5B8DD6)
    val crossing = Color(0x5550D27A)
    val enter = Color(0xFFE8402F)
    val exit = Color(0xFFE5822E)

    /** Best lap / best 3 consecutive, same colours as fpv-android-lap-timer. */
    val bestLap = Color(0xFF4ADE80)
    val bestThree = Color(0xFFFBBF24)
}
