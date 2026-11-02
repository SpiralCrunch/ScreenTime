package com.usetracker.screentime.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun ScreenTimeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF9FA8DA))
        else -> lightColorScheme(primary = Color(0xFF3F51B5))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * Screen time (the old measure) is blue, active use (the new one) red, and where both count,
 * purple. Active use only counts while the screen is on, so in practice bars are purple where
 * the phone was really used and blue where it was only lit up.
 */
@Immutable
class UseColors(val screen: Color, val active: Color, val both: Color)

private val lightUse = UseColors(Color(0xFF2F6FE4), Color(0xFFE5484D), Color(0xFF8B45D1))
private val darkUse = UseColors(Color(0xFF6E9BFF), Color(0xFFFF6B6F), Color(0xFFB98AF0))

val useColors: UseColors
    @Composable get() = if (isSystemInDarkTheme()) darkUse else lightUse
