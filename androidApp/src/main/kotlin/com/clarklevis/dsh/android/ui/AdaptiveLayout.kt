package com.clarklevis.dsh.android.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.DisplayFeature
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** 与 iOS 使用相同的阅读宽度与分栏断点；只依赖当前窗口，不判断设备型号。 */
internal object AdaptiveLayout {
    val PageWidth = 1_040.dp
    val WorkspaceWidth = 1_440.dp
    val SheetWidth = 720.dp

    fun workspaceUsesColumns(width: Float, height: Float, fontScale: Float = 1f): Boolean =
        width >= 900f && height >= 500f && fontScale < 1.5f

    fun pluginColumnCount(width: Float, fontScale: Float = 1f): Int =
        if (width >= 760f && fontScale < 1.5f) 2 else 1
}

/** 放松父级的最小宽度再限宽；不裁切绘制，卡片阴影可自然延伸到两侧。 */
internal fun Modifier.adaptiveContentWidth(maximumWidth: Dp = AdaptiveLayout.PageWidth): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = maximumWidth).fillMaxWidth()

private data class AdaptiveWindowPane(val bounds: IntRect, val window: IntSize)
private val LocalAdaptivePane = staticCompositionLocalOf<AdaptiveWindowPane?> { null }

/** 分隔式铰链使用较大的连续区域；普通折痕、展开和平板窗口仍使用完整窗口。 */
internal fun continuousWindowPane(window: IntSize, hinge: IntRect?, vertical: Boolean): IntRect {
    val full = IntRect(0, 0, window.width, window.height)
    if (hinge == null) return full
    if (vertical) {
        if (hinge.left <= 0 || hinge.right >= window.width || hinge.bottom <= 0 || hinge.top >= window.height) return full
        val left = IntRect(0, 0, hinge.left, window.height)
        val right = IntRect(hinge.right, 0, window.width, window.height)
        return if (left.width >= right.width) left else right
    }
    if (hinge.top <= 0 || hinge.bottom >= window.height || hinge.right <= 0 || hinge.left >= window.width) return full
    val top = IntRect(0, 0, window.width, hinge.top)
    val bottom = IntRect(0, hinge.bottom, window.width, window.height)
    return if (top.height >= bottom.height) top else bottom
}

@Composable
internal fun AdaptiveWindowHost(content: @Composable () -> Unit) {
    val activity = LocalActivity.current
    val infoFlow = remember(activity) {
        activity?.let { WindowInfoTracker.getOrCreate(it).windowLayoutInfo(it).map { info -> info.displayFeatures } }
            ?: flowOf(emptyList<DisplayFeature>())
    }
    val info by infoFlow.collectAsStateWithLifecycle(initialValue = emptyList<DisplayFeature>())
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .onGloballyPositioned { origin = it.positionInWindow() }
    ) {
        val window = IntSize(constraints.maxWidth, constraints.maxHeight)
        val fold = info.filterIsInstance<FoldingFeature>().firstOrNull {
            it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL
        }
        val hinge = fold?.bounds?.let {
            IntRect(it.left, it.top, it.right, it.bottom)
                .translate(IntOffset(-origin.x.roundToInt(), -origin.y.roundToInt()))
        }
        val pane = continuousWindowPane(window, hinge, fold?.orientation == FoldingFeature.Orientation.VERTICAL)
        CompositionLocalProvider(LocalAdaptivePane provides AdaptiveWindowPane(pane, window)) {
            Box(
                Modifier.offset { pane.topLeft }
                    .size(with(density) { pane.width.toDp() }, with(density) { pane.height.toDp() })
            ) { content() }
        }
    }
}

@Composable
internal fun adaptiveWindowSize(): DpSize {
    val pane = LocalAdaptivePane.current
    val pixels = pane?.bounds?.size ?: LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) { DpSize(pixels.width.toDp(), pixels.height.toDp()) }
}

@Composable
internal fun adaptiveSheetWidth(): Dp = minOf(AdaptiveLayout.SheetWidth, adaptiveWindowSize().width)

@Composable
internal fun Modifier.adaptiveSheetBounds(): Modifier {
    val pane = LocalAdaptivePane.current ?: return this
    if (pane.bounds == IntRect(0, 0, pane.window.width, pane.window.height)) return this
    val density = LocalDensity.current
    return offset {
        IntOffset(pane.bounds.center.x - pane.window.width / 2, pane.bounds.bottom - pane.window.height)
    }.heightIn(max = with(density) { pane.bounds.height.toDp() })
}

/** 首页与 iPad 一样：宽窗口左侧工作区，右侧最近会话；窄窗口恢复单栏。 */
@Composable
internal fun AdaptiveWorkspaceContent(
    window: DpSize,
    header: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    recentSessions: @Composable () -> Unit
) {
    val columns = AdaptiveLayout.workspaceUsesColumns(window.width.value, window.height.value, LocalDensity.current.fontScale)
    Column(
        Modifier.adaptiveContentWidth(AdaptiveLayout.WorkspaceWidth)
            .padding(horizontal = if (columns) 40.dp else 22.dp, vertical = 18.dp)
            .testTag(if (columns) "workspace-columns" else "workspace-single-column"),
        verticalArrangement = Arrangement.spacedBy(if (columns) 40.dp else 18.dp)
    ) {
        header()
        if (columns) {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(minOf(440.dp, window.width * 0.38f)).testTag("workspace-actions"),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) { actions() }
                Column(Modifier.weight(1f).testTag("workspace-recents"),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) { recentSessions() }
            }
        } else {
            Spacer(Modifier.height(44.dp))
            Column(Modifier.testTag("workspace-actions"), verticalArrangement = Arrangement.spacedBy(18.dp)) { actions() }
            Column(Modifier.testTag("workspace-recents"), verticalArrangement = Arrangement.spacedBy(18.dp)) { recentSessions() }
        }
    }
}
