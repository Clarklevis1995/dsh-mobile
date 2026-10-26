package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val LocalMenuOwner = staticCompositionLocalOf<android.view.View?> { null }

/** 每个 Activity / Dialog 各有一个宿主，文字菜单和背景采样始终属于当前窗口。 */
@Composable
internal fun DshMenuHost(content: @Composable () -> Unit) {
    val owner = LocalView.current
    if (LocalMenuOwner.current === owner) { content(); return }
    val provider = remember(owner) { DshTextContextMenuProvider() }
    val legacy = remember(owner) { DshLegacyTextToolbar() }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy()) }
    DisposableEffect(provider, legacy) { onDispose { provider.session?.close(); legacy.hide() } }
    CompositionLocalProvider(LocalMenuOwner provides owner,
        LocalTextContextMenuDropdownProvider provides provider,
        LocalTextContextMenuToolbarProvider provides provider,
        LocalTextToolbar provides legacy) {
        DshMenuBackdropHost { backdrop ->
            Box(backdrop.onGloballyPositioned { coordinates = it }, propagateMinConstraints = true) {
                content()
                val session = provider.session
                val destination = coordinates
                if (session != null && destination?.isAttached == true) {
                    // 跨窗口求公共祖先会抛 IllegalArgumentException("layouts are not part of the same hierarchy")。
                    // 真实输入框在 Dialog 窗口、宿主在 Activity 窗口时就会出现，取不到坐标时安静收起菜单，绝不炸进程。
                    val bounds = runCatching {
                        session.data.contentBounds(destination).translate(destination.positionInWindow())
                    }.getOrNull()
                    if (bounds == null) {
                        androidx.compose.runtime.LaunchedEffect(session) { session.close() }
                    } else {
                        val items = session.data.data().components.filterIsInstance<TextContextMenuItem>()
                        DshTextActions(bounds, session::close, items.map { item ->
                            val label = when (item.key) {
                                TextContextMenuKeys.CopyKey -> "复制"
                                TextContextMenuKeys.CutKey -> "剪切"
                                TextContextMenuKeys.PasteKey -> "粘贴"
                                TextContextMenuKeys.SelectAllKey -> "全选"
                                else -> item.label
                            }
                            com.clarklevis.dsh.android.localization.appText(label) to { item.onClick(session) }
                        })
                    }
                }
                legacy.request?.let { DshTextActions(it.bounds, legacy::hide, it.actions) }
            }
        }
    }
}

private class DshTextContextMenuProvider : TextContextMenuProvider {
    private val mutex = Mutex()
    var session by mutableStateOf<Session?>(null)
        private set
    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        mutex.withLock {
            val active = Session(dataProvider)
            session = active
            try { active.closed.await() } finally { if (session === active) session = null }
        }
    }
    class Session(val data: TextContextMenuDataProvider) : TextContextMenuSession {
        val closed = CompletableDeferred<Unit>()
        override fun close() { closed.complete(Unit) }
    }
}

private data class DshTextToolbarRequest(val bounds: Rect, val actions: List<Pair<String, () -> Unit>>)

/** 兼容仍调用旧 TextToolbar 的 Compose 输入组件，禁止回退到平台 ActionMode 菜单。 */
private class DshLegacyTextToolbar : TextToolbar {
    var request by mutableStateOf<DshTextToolbarRequest?>(null)
        private set
    override val status: TextToolbarStatus get() = if (request == null) TextToolbarStatus.Hidden else TextToolbarStatus.Shown
    override fun hide() { request = null }
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        request = DshTextToolbarRequest(rect, buildList {
            fun action(label: String, callback: (() -> Unit)?) {
                if (callback != null) add(com.clarklevis.dsh.android.localization.appText(label) to { callback(); hide() })
            }
            action("剪切", onCutRequested); action("复制", onCopyRequested)
            action("粘贴", onPasteRequested); action("全选", onSelectAllRequested)
        })
    }
}

@Composable
internal fun DshTextActions(bounds: Rect, onDismiss: () -> Unit, actions: List<Pair<String, () -> Unit>>) {
    if (actions.isEmpty()) return
    val shadow = with(LocalDensity.current) { 24.dp.roundToPx() }
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    val position = remember(bounds, shadow, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
                popupContentSize: IntSize): IntOffset {
                val panelHeight = popupContentSize.height - shadow * 2
                val above = bounds.top.toInt() - gap - panelHeight
                val y = if (above >= shadow) above - shadow else bounds.bottom.toInt() + gap
                return IntOffset((bounds.center.x.toInt() - popupContentSize.width / 2)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
            }
        }
    }
    DshMenuPopup(true, onDismiss, position, properties = PopupProperties(focusable = false),
        shape = CircleShape, whiteSurface = true) {
        Row(Modifier.horizontalScroll(rememberScrollState()).testTag("dsh-text-menu")) {
            actions.forEach { (label, action) -> DshMenuAction(label, action) }
        }
    }
}

/** 底部面板保留 ColumnScope 的顺序布局，同时建立当前窗口的菜单宿主。 */
@Composable
internal fun DshMenuColumnHost(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    DshMenuHost {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth(), content = content)
    }
}
