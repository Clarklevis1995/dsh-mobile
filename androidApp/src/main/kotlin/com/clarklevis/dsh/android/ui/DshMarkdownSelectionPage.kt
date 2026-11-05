package com.clarklevis.dsh.android.ui

import android.text.method.ArrowKeyMovementMethod
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import com.clarklevis.dsh.android.localization.appText

/** 全文快照放在独立窗口中选择，不改动聊天列表的分块、排版和滚动位置。 */
@Composable
internal fun DshMarkdownSelectionPage(markdown: String, onDismiss: () -> Unit) {
    if (remember(markdown) { markdownHasSelectionTable(markdown) }) {
        DshTableTextSelectionPage(markdown, onDismiss)
        return
    }
    val onQuote = LocalMessageQuote.current
    val configuration = LocalConfiguration.current
    val dark = configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    val background = if (dark) Color.Black else Color.White
    val foreground = if (dark) Color.White else Color.Black
    val accent = if (dark) DshSelectionStyle.DARK_ACCENT else DshSelectionStyle.LIGHT_ACCENT
    val codeBackground = if (dark) DshSelectionStyle.DARK_CODE else DshSelectionStyle.LIGHT_CODE
    val density = LocalDensity.current
    val fontSizePx = with(density) { DshSelectionStyle.BODY_SIZE.sp.toPx() }
    val padding = with(density) { DshSelectionStyle.HORIZONTAL_INSET.dp.roundToPx() }
    val lineSpacing = with(density) { 4.dp.toPx() }
    var selectionAnchor by remember { mutableStateOf<DshSelectionToolbarAnchor?>(null) }
    var selectionView by remember { mutableStateOf<DshMarkdownTextView?>(null) }
    val rendered = remember(markdown, fontSizePx, density.density, dark) {
        renderMarkdownSelectionText(markdown, fontSizePx, density.density, codeBackground, accent, 0xFF8E8E93.toInt())
    }
    DshDocumentDialog(onDismiss) {
        DshMenuBackdropHost { backdrop ->
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().then(backdrop).background(background)
                    .documentPageInsets().testTag("markdown-selection-page")) {
                    DshDocumentPageHeader(appText("选择文字"), "markdown-selection-close", onDismiss)
                    AndroidView(factory = { viewContext ->
                        DshMarkdownTextView(android.view.ContextThemeWrapper(viewContext.createConfigurationContext(configuration),
                            com.clarklevis.dsh.android.R.style.ThemeOverlay_MarkdownSelection)).apply {
                            isSelectionPage = true
                            onSelectionToolbarChanged = { selectionAnchor = it }
                            selectionView = this
                            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontSizePx)
                            applySelectionColors(foreground.toArgb(), accent)
                            setPadding(padding, with(density) { DshSelectionStyle.TOP_INSET.dp.roundToPx() },
                                padding, with(density) { DshSelectionStyle.BOTTOM_INSET.dp.roundToPx() })
                            includeFontPadding = false
                            setLineSpacing(lineSpacing, 1f)
                            gravity = Gravity.TOP or Gravity.START
                            isVerticalScrollBarEnabled = true
                            text = rendered
                            tag = rendered
                            // 选择页内链接作为正文选择，点击不跳离当前页面。
                            movementMethod = ArrowKeyMovementMethod.getInstance()
                            contentDescription = appText("选择文字")
                            doOnLayout { beginTextSelection() }
                        }
                    }, update = { view ->
                        view.applySelectionColors(foreground.toArgb(), accent)
                        if (view.tag !== rendered) {
                            val start = view.selectionStart
                            val end = view.selectionEnd
                            val scroll = view.scrollY
                            val selecting = view.hasSelectionActionMode
                            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontSizePx)
                            view.text = rendered
                            view.tag = rendered
                            if (selecting && start >= 0 && end > start && end <= view.text.length) {
                                view.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION,
                                    android.os.Bundle().apply {
                                        putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                                        putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
                                    })
                            }
                            view.doOnLayout { it.scrollTo(0, scroll) }
                        }
                    }, onRelease = {
                        it.onSelectionToolbarChanged = null
                        it.resetTextInteraction()
                        selectionView = null
                        selectionAnchor = null
                    },
                        modifier = Modifier.fillMaxWidth().weight(1f))
                }
                selectionAnchor?.let { anchor ->
                    DshSelectionToolbar(anchor,
                        onCopy = { selectionView?.onTextContextMenuItem(android.R.id.copy) },
                        onSelectAll = { selectionView?.onTextContextMenuItem(android.R.id.selectAll) },
                        onQuote = onQuote?.let { action -> {
                            selectionView?.let { view ->
                                val start = minOf(view.selectionStart, view.selectionEnd).coerceAtLeast(0)
                                val end = maxOf(view.selectionStart, view.selectionEnd).coerceAtMost(view.text.length)
                                if (end > start) action(markdownSelectionText(view.text, start, end))
                            }
                            onDismiss()
                        } })
                }
            }
        }
    }
}

private fun DshMarkdownTextView.applySelectionColors(foreground: Int, accent: Int) {
    setTextColor(foreground)
    setLinkTextColor(accent)
    highlightColor = (accent and 0x00FFFFFF) or 0x33000000
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        textSelectHandleLeft?.mutate()?.let { it.setTint(accent); setTextSelectHandleLeft(it) }
        textSelectHandleRight?.mutate()?.let { it.setTint(accent); setTextSelectHandleRight(it) }
        textSelectHandle?.mutate()?.let { it.setTint(accent); setTextSelectHandle(it) }
    }
}
