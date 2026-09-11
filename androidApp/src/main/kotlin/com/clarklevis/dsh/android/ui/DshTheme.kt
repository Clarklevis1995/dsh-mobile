package com.clarklevis.dsh.android.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

internal object DshColors {
    val Navy = Color(0xFF06172B)
    val NavyRaised = Color(0xFF0D2440)
    val Ocean = Color(0xFF2E6BE6)
    val Mist = Color(0xFFBFD6FF)
    val Ink = Color(0xFF0E131A)
    val Paper = Color(0xFFF9FAFC)
    val Purple = Color(0xFF7A54C7)
    val Orange = Color(0xFFF07D14)
    val Amber = Color(0xFFFFAD1F)
    val Success = Color(0xFF2EB85C)
}

@Composable
internal fun StatusIndicatorDot(
    color: Color,
    modifier: Modifier = Modifier,
    glowing: Boolean = false
) {
    val decoratedModifier = if (glowing) {
        modifier.dropShadow(
            shape = CircleShape,
            shadow = Shadow(
                radius = 5.dp,
                spread = 1.dp,
                color = color.copy(alpha = 0.28f),
                offset = DpOffset(0.dp, 0.dp)
            )
        )
    } else {
        modifier
    }
    Box(decoratedModifier.background(color, CircleShape))
}

private val LightColors = lightColorScheme(
    primary = DshColors.Ocean,
    secondary = DshColors.Purple,
    background = DshColors.Paper,
    surface = Color.White,
    onBackground = DshColors.Ink,
    onSurface = DshColors.Ink
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7EA8FF),
    secondary = Color(0xFFB69BFF),
    background = Color(0xFF090D13),
    surface = Color(0xFF111720),
    onBackground = Color(0xFFF1F4FA),
    onSurface = Color(0xFFF1F4FA)
)

@Composable
internal fun DshTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = !dark
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
