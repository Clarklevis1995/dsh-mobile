package com.clarklevis.dsh.android.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur

/** 标题后保留毛玻璃；下缘只渐隐底色，避免模糊副本与原始正文叠出重影。 */
@Composable
internal fun Modifier.conversationTopGlass(
    backdrop: LayerBackdrop,
    protectedHeight: Dp,
    fadeHeight: Dp
): Modifier {
    val tint = MaterialTheme.colorScheme.surface
    val surface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.drawPlainBackdrop(
            backdrop,
            shape = { RectangleShape },
            effects = { blur(24.dp.toPx()) },
            onDrawSurface = { drawRect(tint.copy(alpha = 0.95f)) }
        )
    } else {
        Modifier.background(tint.copy(alpha = 0.95f))
    }
    return drawWithContent {
        val edge = protectedHeight.toPx().coerceAtMost(size.height)
        clipRect(bottom = edge) { this@drawWithContent.drawContent() }
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(tint.copy(alpha = 0.95f), Color.Transparent),
                startY = edge,
                endY = edge + fadeHeight.toPx()
            ),
            topLeft = Offset(0f, edge),
            size = Size(size.width, size.height - edge)
        )
    }.then(surface)
}
