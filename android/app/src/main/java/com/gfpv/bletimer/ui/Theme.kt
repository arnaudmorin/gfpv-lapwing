package com.gfpv.bletimer.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Brand = Color(0xFF5B8DD6)

@Composable
fun PhobosTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Brand)
        else -> lightColorScheme(primary = Brand)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Fixed chart colours, matching the web UI calibration graph. */
object ChartColors {
    val rssi = Color(0xFF5B8DD6)
    val crossing = Color(0x5550D27A)
    val enter = Color(0xFFE8402F)
    val exit = Color(0xFFE5822E)
}
