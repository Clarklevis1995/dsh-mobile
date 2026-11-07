package com.clarklevis.dsh.android.ui

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.facade.MarkdownSelectionHtml
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Node
import org.commonmark.renderer.html.HtmlRenderer
import org.json.JSONTokener

internal fun markdownHasSelectionTable(source: String): Boolean {
    fun contains(node: Node): Boolean {
        if (node is TableBlock) return true
        var child = node.firstChild
        while (child != null) {
            if (contains(child)) return true
            child = child.next
        }
        return false
    }
    return contains(markdownInteractionParser.parse(source))
}

internal fun selectionBodyHtml(source: String): String = HtmlRenderer.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
    .escapeHtml(true).build().render(markdownInteractionParser.parse(source))

/** 表格与正文共用浏览器原生选区；系统文字菜单由 DshMenuWebView 接入项目浮层。 */
@Composable
internal fun DshTableTextSelectionPage(markdown: String, onDismiss: () -> Unit) {
    val onQuote = LocalMessageQuote.current
    val colors = dshDocumentColors()
    val dark = colors.background == androidx.compose.ui.graphics.Color.Black
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val fontSize = with(density) { 17.sp.toPx() / density.density }.toDouble()
    val body = remember(markdown) { selectionBodyHtml(markdown) }
    val html = remember(body, fontSize, dark) { MarkdownSelectionHtml.document(body, fontSize, dark, appText("代码")) }
    val appearance = "window.dshSetAppearance($fontSize,$dark);"
    val latestAppearance by rememberUpdatedState(appearance)
    var menu by remember { mutableStateOf<DshNativeTextMenu?>(null) }
    DshDocumentDialog(onDismiss) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().background(colors.background).documentPageInsets()
                .testTag("markdown-selection-page")) {
                DshDocumentPageHeader(appText("选择文字"), "markdown-selection-close", onDismiss)
                AndroidView(factory = { context ->
                    DshMenuWebView(android.view.ContextThemeWrapper(context.createConfigurationContext(configuration),
                        com.clarklevis.dsh.android.R.style.ThemeOverlay_MarkdownSelection)).apply {
                        setBackgroundColor(colors.background.toArgb())
                        settings.javaScriptEnabled = true
                        settings.textZoom = 100
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.blockNetworkLoads = true
                        if (android.os.Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
                        onMenuChanged = { native ->
                            val actions = native?.actions.orEmpty().filter {
                                it.first == appText("复制") || it.first == appText("全选")
                            }.map { (label, action) ->
                                if (label == appText("复制")) label to {
                                    evaluateJavascript("window.dshSelectedText()") { result ->
                                        val text = JSONTokener(result).nextValue() as? String
                                        if (!text.isNullOrEmpty()) context.copyMarkdownText(text)
                                        // 先读取选区再关闭菜单，避免 ActionMode 清空尚未读取的选中文字。
                                        dismissTextMenu()
                                    }
                                } else label to action
                            }
                            val quoteActions = if (onQuote == null) emptyList() else listOf(appText("引用") to {
                                evaluateJavascript("window.dshSelectedText()") { result ->
                                    val text = JSONTokener(result).nextValue() as? String
                                    if (!text.isNullOrBlank()) onQuote(text)
                                    dismissTextMenu()
                                    onDismiss()
                                }
                            })
                            menu = native?.copy(actions = actions + quoteActions)
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                view.evaluateJavascript(latestAppearance + "window.dshSelectInitial();", null)
                            }
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                        }
                        tag = body
                        loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    }
                }, update = { view ->
                    view.setBackgroundColor(colors.background.toArgb())
                    if (view.tag != body) {
                        view.tag = body
                        view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    } else view.evaluateJavascript(appearance, null)
                }, onRelease = { view ->
                    view.onMenuChanged = null
                    menu = null
                    view.dismissTextMenu()
                    view.stopLoading()
                    view.destroy()
                }, modifier = Modifier.fillMaxWidth().weight(1f).testTag("markdown-table-text-selection"))
            }
            menu?.let { native ->
                DshTextActions(Rect(native.bounds.left.toFloat(), native.bounds.top.toFloat(),
                    native.bounds.right.toFloat(), native.bounds.bottom.toFloat()), native.dismiss, native.actions)
            }
        }
    }
}
