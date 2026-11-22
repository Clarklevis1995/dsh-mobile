package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.localization.appText
import kotlin.math.roundToInt

private enum class RoleRowSwipePosition { Closed, Revealed, Confirm }

@Composable
internal fun BotRoleListRow(
    modifier: Modifier = Modifier,
    first: Boolean,
    last: Boolean,
    canDelete: Boolean,
    revealed: Boolean,
    onReveal: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val top = if (first) 26.dp else 0.dp
    val bottom = if (last) 26.dp else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    val actionWidth = 68.dp
    val density = LocalDensity.current
    val actionPx = with(density) { actionWidth.toPx() }
    var widthPx by remember { mutableIntStateOf(0) }
    val anchors = remember(actionPx, widthPx) {
        DraggableAnchors {
            RoleRowSwipePosition.Closed at 0f
            RoleRowSwipePosition.Revealed at -actionPx
            if (widthPx > actionPx * 2) RoleRowSwipePosition.Confirm at -widthPx.toFloat()
        }
    }
    val swipe = remember { AnchoredDraggableState(RoleRowSwipePosition.Closed, anchors) }
    SideEffect { swipe.updateAnchors(anchors) }
    val settle = AnchoredDraggableDefaults.flingBehavior(swipe, positionalThreshold = { distance ->
        if (distance > actionPx) distance * 0.8f else distance * 0.5f
    })
    val fling = remember(settle, actionPx) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                // 快速短滑可以露出按钮，只有滑过大部分行宽才触发确认。
                val velocity = if (initialVelocity < 0 && swipe.offset < -actionPx) 0f else initialVelocity
                return with(settle) { performFling(velocity) }
            }
        }
    }
    val deleteLabel = appText("删除")
    val deleteAction = {
        onClose()
        onDelete()
    }
    LaunchedEffect(swipe.settledValue, canDelete) {
        if (canDelete) when (swipe.settledValue) {
            RoleRowSwipePosition.Revealed -> onReveal()
            RoleRowSwipePosition.Confirm -> {
                deleteAction()
                swipe.snapTo(RoleRowSwipePosition.Closed)
            }
            RoleRowSwipePosition.Closed -> Unit
        }
    }
    LaunchedEffect(revealed, canDelete) {
        if (!canDelete || !revealed) swipe.animateTo(RoleRowSwipePosition.Closed)
    }
    val offset = swipe.offset.takeUnless(Float::isNaN) ?: 0f
    val actionVisible = canDelete && revealed && swipe.settledValue == RoleRowSwipePosition.Revealed
    Box(modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }.clip(shape)
        .anchoredDraggable(swipe, Orientation.Horizontal, enabled = canDelete, flingBehavior = fling)
        .semantics {
            if (canDelete) customActions = listOf(CustomAccessibilityAction(deleteLabel) { deleteAction(); true })
        }) {
        if (offset < 0f) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                val revealedWidth = with(density) { maxOf(-offset, actionPx).toDp() }
                Box(Modifier.width(revealedWidth).fillMaxHeight().padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp)).background(colors.onSurface)
                    .then(if (actionVisible) Modifier.testTag("bot.role-row-delete").clickable(role = Role.Button, onClick = deleteAction)
                        else Modifier.semantics { hideFromAccessibility() }),
                    contentAlignment = Alignment.Center) {
                    Text(deleteLabel, fontSize = 13.sp, color = colors.surface)
                }
            }
        }
        val foreground = lerp(colors.surface, colors.onSurface, (-offset / actionPx).coerceIn(0f, 1f) * .12f)
        Column(Modifier.fillMaxWidth().offset { IntOffset(offset.roundToInt(), 0) }
            .clip(if (offset < 0f) RoundedCornerShape(26.dp) else shape).background(foreground)
            .then(when {
                actionVisible -> Modifier.clickable(onClick = onClose)
                onClick != null -> Modifier.clickable(role = Role.Button, onClick = onClick)
                else -> Modifier
            })
            .padding(horizontal = 20.dp)) {
            content()
            if (!last) SettingsDivider()
        }
    }
}

@Composable
internal fun BotRoleScheduleText(title: String, next: String) {
    val colors = MaterialTheme.colorScheme
    Layout(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 12.dp),
        content = {
            Text(title, style = SettingsTypography.item, color = colors.onSurface)
            if (next.isNotBlank()) Text(next, style = SettingsTypography.item, color = colors.onSurface.copy(alpha = .52f))
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val unconstrainedMinimums = constraints.copy(minWidth = 0, minHeight = 0)
        val name = measurables[0].measure(unconstrainedMinimums)
        val time = measurables.getOrNull(1)?.measure(unconstrainedMinimums)
        val inline = time == null || name.width + 12.dp.roundToPx() + time.width <= width
        val height = if (inline) maxOf(name.height, time?.height ?: 0)
        else name.height + 2.dp.roundToPx() + requireNotNull(time).height
        val rowHeight = height.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, rowHeight) {
            name.placeRelative(0, if (inline) (rowHeight - name.height) / 2 else 0)
            time?.let {
                if (inline) it.placeRelative(width - it.width, (rowHeight - it.height) / 2)
                else it.placeRelative(0, name.height + 2.dp.roundToPx())
            }
        }
    }
}
