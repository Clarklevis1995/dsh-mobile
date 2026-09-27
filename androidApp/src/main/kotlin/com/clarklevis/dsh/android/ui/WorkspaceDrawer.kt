package com.clarklevis.dsh.android.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.clarklevis.dsh.android.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val drawerPageShape = RoundedCornerShape(48.dp)

@Composable
internal fun WorkspaceDrawer(
    onPlugins: () -> Unit,
    onScheduledTasks: () -> Unit,
    content: @Composable (openDrawer: () -> Unit, canScrollVertically: Boolean) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val drawerWidth = (maxWidth * 0.76f).coerceAtMost(360.dp)
        val drawerWidthPx = with(density) { drawerWidth.toPx() }
        val flingThresholdPx = with(density) { 400.dp.toPx() }
        var offsetPx by remember { mutableFloatStateOf(0f) }
        var horizontalDragActive by remember { mutableStateOf(false) }
        var animationJob by remember { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()
        val progress = (offsetPx / drawerWidthPx).coerceIn(0f, 1f)
        val dark = isSystemInDarkTheme()
        val view = LocalView.current
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = progress > 0.5f && !dark
                isAppearanceLightNavigationBars = progress > 0.5f && !dark
            }
        }

        fun settle(open: Boolean, velocity: Float = 0f) {
            animationJob?.cancel()
            animationJob = scope.launch {
                animate(
                    initialValue = offsetPx,
                    targetValue = if (open) drawerWidthPx else 0f,
                    initialVelocity = velocity,
                    animationSpec = spring(stiffness = 450f, dampingRatio = 0.86f)
                ) { value, _ -> offsetPx = value.coerceIn(0f, drawerWidthPx) }
            }
        }
        BackHandler(enabled = progress > 0f) { settle(open = false) }

        Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF242426) else Color.White)) {
            Column(
                modifier = Modifier.width(drawerWidth).fillMaxHeight()
                    .safeDrawingPadding()
                    .graphicsLayer {
                        alpha = 0.6f + 0.4f * progress
                        scaleX = 0.9f + 0.1f * progress
                        scaleY = 0.9f + 0.1f * progress
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .padding(horizontal = 18.dp, vertical = 18.dp)
                    .testTag("workspace-drawer")
                    .then(if (progress == 0f) Modifier.clearAndSetSemantics {} else Modifier),
                verticalArrangement = Arrangement.Top
            ) {
                Image(
                    painter = painterResource(R.drawable.dsh_brand_wordmark),
                    contentDescription = "DeepSeek Harness",
                    modifier = Modifier.padding(start = 12.dp, bottom = 16.dp)
                        .size(width = 216.dp, height = 28.5.dp)
                )
                DrawerItem("插件", R.drawable.ic_drawer_plugin, "drawer-plugins") {
                    onPlugins()
                }
                DrawerItem("定时任务", R.drawable.ic_drawer_schedule, "drawer-scheduled-tasks") {
                    onScheduledTasks()
                }
            }

            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { translationX = offsetPx }
                    .then(
                        if (progress > 0f) Modifier
                            .dropShadow(
                                shape = drawerPageShape,
                                shadow = Shadow(
                                    radius = 25.dp * progress,
                                    color = Color.Black.copy(alpha = 0.18f * progress),
                                    offset = DpOffset(x = -4.dp * progress, y = 0.dp)
                                )
                            )
                            .dropShadow(
                                shape = drawerPageShape,
                                shadow = Shadow(
                                    radius = 9.dp * progress,
                                    color = Color.Black.copy(alpha = 0.12f * progress),
                                    offset = DpOffset(x = -2.dp * progress, y = 0.dp)
                                )
                            )
                            .clip(drawerPageShape)
                        else Modifier
                    )
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            animationJob?.cancel()
                            offsetPx = (offsetPx + delta).coerceIn(0f, drawerWidthPx)
                        },
                        onDragStarted = { horizontalDragActive = true },
                        onDragStopped = { velocity ->
                            horizontalDragActive = false
                            val open = when {
                                velocity > flingThresholdPx -> true
                                velocity < -flingThresholdPx -> false
                                else -> offsetPx >= drawerWidthPx * 0.5f
                            }
                            settle(open, velocity)
                        }
                    )
                    .background(MaterialTheme.colorScheme.background)
                    .testTag("workspace-drawer-main")
            ) {
                content({ settle(open = true) }, !horizontalDragActive && offsetPx == 0f)
                if (progress > 0.98f) {
                    Box(
                        Modifier.fillMaxSize().clickable(
                            role = Role.Button,
                            onClickLabel = "关闭侧边栏"
                        ) { settle(open = false) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerItem(label: String, iconRes: Int, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 15.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
            modifier = Modifier.size(22.dp)
        )
        Text(label, color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp)
    }
}
