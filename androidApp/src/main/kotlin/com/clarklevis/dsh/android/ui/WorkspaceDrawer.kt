package com.clarklevis.dsh.android.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val drawerPageCornerRadius = 48.dp

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
        LaunchedEffect(drawerWidthPx, maxHeight) {
            animationJob?.cancel()
            offsetPx = 0f
            horizontalDragActive = false
        }
        // 连续位移只在图层阶段读取；组合阶段只观察跨阈值的交互状态。
        val progress = remember(drawerWidthPx) {
            derivedStateOf { (offsetPx / drawerWidthPx).coerceIn(0f, 1f) }
        }
        val drawerVisible by remember { derivedStateOf { offsetPx > 0f } }
        val interceptContent by remember(drawerWidthPx) {
            derivedStateOf { progress.value > 0.98f }
        }
        val canScrollVertically by remember {
            derivedStateOf { !horizontalDragActive && offsetPx == 0f }
        }
        val dark = isSystemInDarkTheme()
        val lightSystemBars by remember(drawerWidthPx, dark) {
            derivedStateOf { progress.value > 0.5f && !dark }
        }
        val view = LocalView.current
        val haptics = remember(view) { ClickHapticFeedback(view) }
        val dragFeedback = remember(haptics) {
            DrawerDragFeedback { haptics.perform() }
        }
        LaunchedEffect(view, lightSystemBars) {
            val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightSystemBars
                isAppearanceLightNavigationBars = lightSystemBars
            }
        }

        fun updateOffset(value: Float) {
            offsetPx = value.coerceIn(0f, drawerWidthPx)
        }

        fun settle(open: Boolean, velocity: Float = 0f, feedback: Boolean = true) {
            if (feedback) haptics.perform()
            animationJob?.cancel()
            animationJob = scope.launch {
                val targetOffset = if (open) drawerWidthPx else 0f
                animate(
                    initialValue = offsetPx,
                    targetValue = targetOffset,
                    initialVelocity = velocity,
                    animationSpec = spring(stiffness = 450f, dampingRatio = Spring.DampingRatioNoBouncy)
                ) { value, _ -> updateOffset(value) }
                updateOffset(targetOffset)
            }
        }
        val openDrawer = remember(drawerWidthPx, scope, haptics) { { settle(open = true) } }
        BackHandler(enabled = drawerVisible) { settle(open = false) }

        Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF242426) else Color.White)) {
            Column(
                modifier = Modifier.width(drawerWidth).fillMaxHeight()
                    .safeDrawingPadding()
                    .graphicsLayer {
                        val fraction = progress.value
                        alpha = 0.6f + 0.4f * fraction
                        scaleX = 0.9f + 0.1f * fraction
                        scaleY = 0.9f + 0.1f * fraction
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .padding(horizontal = 18.dp, vertical = 18.dp)
                    .testTag("workspace-drawer")
                    .then(if (!drawerVisible) Modifier.clearAndSetSemantics {} else Modifier),
                verticalArrangement = Arrangement.Top
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, bottom = 16.dp)
                        .size(width = minOf(216.dp, (drawerWidth - 48.dp).coerceAtLeast(0.dp)), height = 29.dp)
                        .semantics { contentDescription = "Whaleness Harness" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    WhalenessOrcaIcon(
                        modifier = Modifier.size(29.dp),
                        tint = if (dark) Color.White else Color.Black
                    )
                    Image(
                        painter = painterResource(if (dark) R.drawable.dsh_whaleness_wordmark_dark else R.drawable.dsh_whaleness_wordmark),
                        contentDescription = null,
                        modifier = Modifier.size(width = 183.dp, height = 29.dp)
                    )
                }
                DrawerItem(appText("插件"), R.drawable.ic_drawer_plugin, "drawer-plugins") {
                    onPlugins()
                }
                DrawerItem(appText("定时任务"), R.drawable.ic_drawer_schedule, "drawer-scheduled-tasks") {
                    onScheduledTasks()
                }
            }

            Box(
                Modifier.fillMaxSize()
                    // 单个硬件图层负责平移、圆角和阴影，避免逐帧生成全屏模糊遮罩。
                    .graphicsLayer {
                        val fraction = progress.value
                        val radius = drawerPageCornerRadius * fraction
                        translationX = offsetPx
                        shape = AbsoluteRoundedCornerShape(topLeft = radius, bottomLeft = radius)
                        shadowElevation = 18.dp.toPx() * fraction
                        clip = fraction > 0f
                    }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        interactionSource = dragFeedback,
                        state = rememberDraggableState { delta ->
                            animationJob?.cancel()
                            updateOffset(offsetPx + delta)
                        },
                        onDragStarted = {
                            animationJob?.cancel()
                            horizontalDragActive = true
                        },
                        onDragStopped = { velocity ->
                            horizontalDragActive = false
                            val open = when {
                                velocity > flingThresholdPx -> true
                                velocity < -flingThresholdPx -> false
                                else -> offsetPx >= drawerWidthPx * 0.5f
                            }
                            settle(open, velocity, feedback = false)
                        }
                    )
                    .background(MaterialTheme.colorScheme.background)
                    .testTag("workspace-drawer-main")
            ) {
                content(openDrawer, canScrollVertically)
                if (interceptContent) {
                    Box(
                        Modifier.fillMaxSize().testTag("workspace-drawer-close").clickable(
                            role = Role.Button,
                            onClickLabel = appText("关闭侧边栏")
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
