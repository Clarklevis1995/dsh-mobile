package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.localization.appText
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal enum class DshPickerPresentation { FLOATING_GLASS, SYSTEM_SHEET }

/** 公共选择面板：任务使用内缩玻璃，日志使用原生风格白底与拖动条。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DshPickerSheet(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    height: Dp = 340.dp,
    confirmLabel: String = appText("确定"),
    confirmEnabled: Boolean = true,
    tag: String = "dsh-picker-sheet",
    contentPadding: Dp = 20.dp,
    presentation: DshPickerPresentation = DshPickerPresentation.FLOATING_GLASS,
    content: @Composable BoxScope.() -> Unit
) {
    val floating = presentation == DshPickerPresentation.FLOATING_GLASS
    val shape = if (floating) RoundedCornerShape(42.dp) else RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    // 在进入独立窗口前取得父面板的采样源；材质必须跟随内容一起滑动。
    val glass = Modifier.dshWheelSheetGlass(shape)
    val contentHeight = minOf(height, adaptiveWindowSize().height * 0.85f)
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val sheetColors = if (floating) colors else colors.copy(
        surface = if (dark) Color(0xFF1C1C1E) else Color.White,
        onSurface = if (dark) Color.White else Color.Black,
        onSurfaceVariant = Color(0xFF8E8E93),
        primary = if (dark) Color(0xFF0A84FF) else Color(0xFF007AFF),
        surfaceVariant = if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
    )
    MaterialTheme(colorScheme = sheetColors) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            modifier = Modifier.adaptiveSheetBounds().then(if (floating) Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp) else Modifier),
            sheetMaxWidth = adaptiveSheetWidth(),
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = shape,
            containerColor = if (floating) Color.Transparent else MaterialTheme.colorScheme.surface,
            contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal) },
            dragHandle = if (floating) null else {{
                Box(Modifier.padding(top = 6.dp, bottom = 2.dp).size(width = 36.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), CircleShape))
            }}
        ) {
            DshMenuColumnHost {
                Column(Modifier.fillMaxWidth().then(if (floating) glass else Modifier).testTag(tag)
                    .navigationBarsPadding().height(contentHeight).padding(top = 8.dp)) {
                    DshPickerHeader(title, onDismiss, confirmLabel, enabled = confirmEnabled, capsuleActions = true, onAction = onConfirm)
                    Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = contentPadding),
                        contentAlignment = Alignment.Center, content = content)
                }
            }
        }
    }
}

/** 有界懒加载滚轮；首尾留空、不循环，选中行吸附到中央，避免创建一万条 View。 */
@Composable
internal fun DshNumberWheel(
    selected: Int,
    minimum: Int,
    maximum: Int,
    label: String,
    tag: String,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    selectionInset: Dp = 28.dp,
    format: (Int) -> String,
    onChange: (Int) -> Unit
) {
    require(minimum <= maximum)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceIn(minimum, maximum) - minimum)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val itemHeight = 40.dp
    val itemPx = with(LocalDensity.current) { itemHeight.toPx() }
    val wheelScroll = remember {
        object : NestedScrollConnection {
            // 滚轮到首尾后留在边界，剩余手势不传给面板触发意外关闭。
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(0f, available.y)
            override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(0f, available.y)
        }
    }
    val currentChange by rememberUpdatedState(onChange)
    val centered by remember(state, itemPx, minimum, maximum) {
        derivedStateOf {
            (state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset / itemPx)
                .roundToInt().plus(minimum).coerceIn(minimum, maximum)
        }
    }
    LaunchedEffect(state, minimum, maximum, itemPx, haptics) {
        var previous = centered
        var wasScrolling = false
        snapshotFlow { centered to state.isScrollInProgress }.distinctUntilChanged().collect { (value, scrolling) ->
            // SegmentTick 经 AndroidX 适配旧系统；首帧和相同刻度不触发，收尾刻度不漏触发。
            if ((scrolling || wasScrolling) && value != previous) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            previous = value
            wasScrolling = scrolling
            currentChange(value)
        }
    }
    Box(modifier.fillMaxWidth().height(height).clipToBounds().nestedScroll(wheelScroll).testTag(tag)
        .semantics {
            contentDescription = label
            stateDescription = centered.toString()
            progressBarRangeInfo = ProgressBarRangeInfo(centered.toFloat(), minimum.toFloat()..maximum.toFloat(), (maximum - minimum - 1).coerceAtLeast(0))
            setProgress { value ->
                scope.launch { state.animateScrollToItem(value.roundToInt().coerceIn(minimum, maximum) - minimum) }
                true
            }
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().padding(horizontal = selectionInset).height(itemHeight)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), CircleShape)
            .testTag("$tag-selection"))
        LazyColumn(
            Modifier.fillMaxSize().testTag("$tag-list"),
            state = state,
            contentPadding = PaddingValues(vertical = (height - itemHeight) / 2),
            flingBehavior = rememberSnapFlingBehavior(state)
        ) {
            items(maximum - minimum + 1, key = { it + minimum }) { index ->
                Box(Modifier.fillMaxWidth().height(itemHeight)
                    .graphicsLayer {
                        val distance = index - state.firstVisibleItemIndex - state.firstVisibleItemScrollOffset / itemPx
                        alpha = (1f - abs(distance) * 0.38f).coerceIn(0f, 1f)
                        rotationX = (-distance * 24f).coerceIn(-75f, 75f)
                        cameraDistance = 12f * density
                    }
                    .clickable(role = Role.Button) { scope.launch { state.animateScrollToItem(index) } }
                    .semantics { this.selected = minimum + index == centered }, contentAlignment = Alignment.Center) {
                    Text(format(minimum + index), fontSize = 22.sp, lineHeight = 28.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun DshPickerHeader(title: String, onDismiss: () -> Unit, action: String? = null,
    enabled: Boolean = true, dismissEnabled: Boolean = true, dismissLabel: String = appText("取消"),
    capsuleActions: Boolean = false, onAction: () -> Unit = {}) {
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = if (capsuleActions) 16.dp else 12.dp)) {
        if (capsuleActions) {
            DshPickerCapsuleAction(dismissLabel, dismissEnabled, onDismiss, Modifier.align(Alignment.CenterStart))
        } else {
            TextButton(onClick = onDismiss, enabled = dismissEnabled, modifier = Modifier.align(Alignment.CenterStart)) { Text(dismissLabel, fontSize = 17.sp) }
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.Center))
        if (action != null) {
            if (capsuleActions) DshPickerCapsuleAction(action, enabled, onAction, Modifier.align(Alignment.CenterEnd))
            else TextButton(onClick = onAction, enabled = enabled, modifier = Modifier.align(Alignment.CenterEnd)) { Text(action, fontSize = 17.sp) }
        }
    }
}

@Composable
private fun DshPickerCapsuleAction(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.height(44.dp).widthIn(min = 64.dp)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )), shape
            )
            .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), shape)
            .clip(shape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.3f),
            maxLines = 1)
    }
}


@Composable
internal fun DshClockPicker(time: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val parts = remember(time) { time.take(8).split(':').map { it.toIntOrNull() ?: 0 } }
    var hour by remember { mutableStateOf(parts.getOrElse(0) { 8 }) }
    var minute by remember { mutableStateOf(parts.getOrElse(1) { 0 }) }
    var second by remember { mutableStateOf(parts.getOrElse(2) { 0 }) }
    var keepFraction by remember { mutableStateOf(true) }
    val hasFraction = time.drop(9).any { it != '0' }
    val wheelHeight = if (hasFraction) 180.dp else 220.dp
    DshPickerSheet(appText("时间"), onDismiss, onConfirm = {
        onConfirm(String.format(Locale.US, "%02d:%02d:%02d", hour, minute, second) + if (keepFraction) time.drop(8) else "")
    }, tag = "dsh-clock-sheet") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ClockWheel(appText("小时"), hour, 23, Modifier.weight(1f), wheelHeight) { hour = it }
                ClockWheel(appText("分钟"), minute, 59, Modifier.weight(1f), wheelHeight) { minute = it }
                ClockWheel(appText("秒"), second, 59, Modifier.weight(1f), wheelHeight) { second = it }
            }
            if (hasFraction) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(appText("保留毫秒"))
                    Switch(checked = keepFraction, onCheckedChange = { keepFraction = it })
                }
            }
        }
    }
}

@Composable
private fun ClockWheel(label: String, selected: Int, maximum: Int, modifier: Modifier, height: Dp, onChange: (Int) -> Unit) {
    Column(modifier.testTag("schedule-clock-$maximum-$label"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFF8E8E93),
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
        DshNumberWheel(selected, 0, maximum, label, "schedule-clock-values-$maximum-$label",
            height = height, selectionInset = 2.dp,
            format = { String.format(Locale.US, "%02d", it) }, onChange = onChange)
    }
}

