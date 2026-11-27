package com.clarklevis.dsh.android.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.projection.GenuiBlockParser
import com.clarklevis.dsh.shared.projection.GenuiSegment
import com.clarklevis.dsh.shared.projection.GenuiSegmentState
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlin.math.abs
import org.json.JSONObject

private const val GENUI_ORIGIN = "https://genui.local"
private const val GENUI_URL = "$GENUI_ORIGIN/genui/genui.html"
private val GENUI_ASSETS = mapOf(
    "genui.html" to "text/html",
    "genui-bridge.js" to "application/javascript",
    "standalone-runtime.js" to "application/javascript",
    "echarts-core.js" to "application/javascript",
    "icons.js" to "application/javascript"
)

@Composable
internal fun GenuiSegmentView(
    segment: GenuiSegment,
    source: String,
    markdown: @Composable (String) -> Unit,
    onAction: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val fontScale = LocalDensity.current.fontScale
    val isDark = isSystemInDarkTheme()
    val fallbackText = remember(segment, source) {
        GenuiBlockParser.fallbackMarkdown(segment, source)
    }
    Box(modifier) {
        when (segment.state) {
            GenuiSegmentState.INVALID -> markdown(fallbackText)
            GenuiSegmentState.STREAMING -> Box(
                Modifier.fillMaxWidth().height(44.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            GenuiSegmentState.READY -> {
                var cssHeight by remember(segment.id, segment.rawJson) { mutableFloatStateOf(72f) }
                var failed by remember(segment.id, segment.rawJson) { mutableStateOf(false) }
                var textMenu by remember(segment.id, segment.rawJson) { mutableStateOf<DshNativeTextMenu?>(null) }
                if (failed) {
                    markdown(fallbackText)
                } else {
                    // With viewport width=device-width, initial-scale=1 and zoom disabled,
                    // 1 CSS pixel equals 1 density-independent pixel (1 dp) across all densities.
                    val heightDp = cssHeight.coerceIn(1f, 20_000f).dp
                    Box(
                        Modifier.fillMaxWidth()
                            .height(heightDp)
                            .clip(RoundedCornerShape(16.dp))
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                GenuiWebView(context).apply {
                                    onMenuChanged = { textMenu = it }
                                    configure(
                                        blockId = segment.id,
                                        rawJson = segment.rawJson,
                                        contentVersion = segment.contentVersion,
                                        interactive = segment.interactive,
                                        isDark = isDark,
                                        fontScale = fontScale,
                                        onResize = { cssHeight = it },
                                        onFailure = { failed = true },
                                        onAction = onAction
                                    )
                                }
                            },
                            update = { view ->
                                view.onMenuChanged = { textMenu = it }
                                view.bridge.configure(
                                    { cssHeight = it },
                                    { failed = true },
                                    onAction
                                )
                                view.bridge.update(
                                    blockId = segment.id,
                                    rawJson = segment.rawJson,
                                    contentVersion = segment.contentVersion,
                                    interactive = segment.interactive,
                                    isDark = isDark,
                                    fontScale = fontScale
                                )
                            },
                            onRelease = { view ->
                                textMenu = null
                                view.release()
                            }
                        )
                    }
                    textMenu?.let { native ->
                        DshTextActions(
                            Rect(
                                native.bounds.left.toFloat(),
                                native.bounds.top.toFloat(),
                                native.bounds.right.toFloat(),
                                native.bounds.bottom.toFloat()
                            ),
                            native.dismiss,
                            native.actions
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GenuiDetailsSheet(details: String, onDismiss: () -> Unit) {
    ModalBottomSheet(
        modifier = Modifier.adaptiveSheetBounds(),
        sheetMaxWidth = adaptiveSheetWidth(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding(),
            verticalArrangement = Arrangement.Top
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(appText("详情"), modifier = Modifier.align(Alignment.Center), style = SettingsTypography.title)
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text(appText("完成"))
                }
            }
            SelectionContainer(
                Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
            ) {
                Text(details, Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private class GenuiWebView(context: Context) : DshMenuWebView(context) {
    val bridge = GenuiWebBridge()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var gestureAxis = 0 // 0 = undecided, 1 = horizontal, 2 = vertical

    init {
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isNestedScrollingEnabled = false
        addJavascriptInterface(bridge, "AndroidGenui")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !request.isForMainFrame || request.url.toString() != GENUI_URL

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (request.method != "GET") return blockedResponse()
                val url = request.url
                val name = url.lastPathSegment
                if (url.toString() == GENUI_URL && name == "genui.html") {
                    return assetResponse(name, "text/html")
                }
                if (url.scheme == "https" && url.host == "genui.local" &&
                    url.path == "/genui/$name" && name in GENUI_ASSETS
                ) {
                    return assetResponse(requireNotNull(name), requireNotNull(GENUI_ASSETS[name]))
                }
                return blockedResponse()
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url == GENUI_URL) bridge.onPageFinished()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame || request.url.lastPathSegment in GENUI_ASSETS) bridge.fail()
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                bridge.fail()
                return true
            }
        }
    }

    override fun scrollTo(x: Int, y: Int) {
        // Keep vertical scroll pinned to 0; height is driven by ResizeObserver so the outer list owns vertical scrolling.
        super.scrollTo(x, 0)
    }

    override fun overScrollBy(
        deltaX: Int,
        deltaY: Int,
        scrollX: Int,
        scrollY: Int,
        scrollRangeX: Int,
        scrollRangeY: Int,
        maxOverScrollX: Int,
        maxOverScrollY: Int,
        isTouchEvent: Boolean
    ): Boolean = super.overScrollBy(
        deltaX,
        0,
        scrollX,
        0,
        scrollRangeX,
        0,
        maxOverScrollX,
        0,
        isTouchEvent
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                gestureAxis = 0
                return super.onTouchEvent(event)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (gestureAxis == 0 && (dx > touchSlop || dy > touchSlop)) {
                    gestureAxis = if (dy > dx) 2 else 1
                    if (gestureAxis == 1) {
                        // Horizontal drag: keep inside card for wide tables, carousels, and sliders.
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else {
                        // Vertical drag: hand off immediately to the parent conversation list.
                        parent?.requestDisallowInterceptTouchEvent(false)
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                        return false
                    }
                }
                if (gestureAxis == 2) return false
                return super.onTouchEvent(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasVertical = gestureAxis == 2
                gestureAxis = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                return if (wasVertical) false else super.onTouchEvent(event)
            }
            else -> return super.onTouchEvent(event)
        }
    }

    fun configure(
        blockId: String,
        rawJson: String,
        contentVersion: String,
        interactive: Boolean,
        isDark: Boolean,
        fontScale: Float,
        onResize: (Float) -> Unit,
        onFailure: () -> Unit,
        onAction: (String, String) -> Unit
    ) {
        bridge.configure(onResize, onFailure, onAction)
        bridge.attach(this)
        bridge.resetLoadState()
        bridge.update(blockId, rawJson, contentVersion, interactive, isDark, fontScale)
        loadUrl(GENUI_URL)
    }

    // WebResourceResponse takes ownership of the InputStream and closes it after WebView consumes the body.
    private fun assetResponse(name: String, mimeType: String): WebResourceResponse = runCatching {
        val stream = context.assets.open("genui/$name")
        WebResourceResponse(
            mimeType,
            "UTF-8",
            200,
            "OK",
            mapOf("Cache-Control" to "no-store"),
            stream
        )
    }.getOrElse { blockedResponse() }

    fun release() {
        onMenuChanged = null
        dismissTextMenu()
        bridge.release()
        removeJavascriptInterface("AndroidGenui")
        stopLoading()
        destroy()
    }
}

internal class GenuiWebBridge {
    private val handler = Handler(Looper.getMainLooper())
    private var blockId = ""
    private var rawJson = ""
    private var contentVersion = ""
    private var interactive = false
    private var isDark = false
    private var fontScale = 1f
    private var loaded = false
    private var activeRenderId = 0
    private var renderedBlockId: String? = null
    private var renderedRawJson: String? = null
    private var renderedInteractive: Boolean? = null
    private var renderedIsDark: Boolean? = null
    private var renderedFontScale: Float? = null
    private var lastHeight = 72f
    private var onResize: ((Float) -> Unit)? = null
    private var onFailure: (() -> Unit)? = null
    private var onAction: ((String, String) -> Unit)? = null

    fun attach(view: WebView) {
        this.view = view
    }

    fun configure(
        onResize: (Float) -> Unit,
        onFailure: () -> Unit,
        onAction: (String, String) -> Unit
    ) {
        this.onResize = onResize
        this.onFailure = onFailure
        this.onAction = onAction
    }

    fun update(
        blockId: String,
        rawJson: String,
        contentVersion: String,
        interactive: Boolean,
        isDark: Boolean,
        fontScale: Float
    ) {
        val blockChanged = this.blockId.isNotEmpty() && this.blockId != blockId
        if (blockChanged) {
            resetLoadState()
        }
        this.blockId = blockId
        this.rawJson = rawJson
        this.contentVersion = contentVersion
        this.interactive = interactive
        this.isDark = isDark
        this.fontScale = fontScale
        if (blockChanged) {
            onResize?.invoke(lastHeight)
            view?.loadUrl(GENUI_URL)
            return
        }
        syncIfLoaded()
    }

    private fun syncIfLoaded() {
        if (!loaded || rawJson.isEmpty()) return
        val webView = view ?: return
        if (renderedBlockId == blockId && renderedRawJson == rawJson) {
            if (renderedIsDark != isDark) {
                renderedIsDark = isDark
                val theme = JSONObject.quote(if (isDark) "dark" else "light")
                webView.evaluateJavascript(
                    "if (typeof window.setGenuiTheme === 'function') window.setGenuiTheme($theme);",
                    null
                )
            }
            if (renderedFontScale == null || abs((renderedFontScale ?: 0f) - fontScale) >= 0.001f) {
                renderedFontScale = fontScale
                val scale = String.format(Locale.US, "%.4f", fontScale)
                webView.evaluateJavascript(
                    "if (typeof window.setGenuiFontScale === 'function') window.setGenuiFontScale($scale);",
                    null
                )
            }
            if (renderedInteractive != interactive) {
                renderedInteractive = interactive
                val flag = if (interactive) "true" else "false"
                webView.evaluateJavascript(
                    "if (typeof window.setGenuiInteractive === 'function') window.setGenuiInteractive($flag);",
                    null
                )
            }
            return
        }

        activeRenderId += 1
        val renderId = activeRenderId
        val payload = JSONObject.quote(rawJson)
        val theme = JSONObject.quote(if (isDark) "dark" else "light")
        val scale = String.format(Locale.US, "%.4f", fontScale)
        val interactiveFlag = if (interactive) "true" else "false"
        renderedBlockId = blockId
        renderedRawJson = rawJson
        renderedInteractive = interactive
        renderedIsDark = isDark
        renderedFontScale = fontScale
        webView.evaluateJavascript(
            """
            (function() {
              if (typeof window.renderGenui !== 'function') return false;
              window.renderGenui($payload, $theme, $renderId, $interactiveFlag);
              window.setGenuiFontScale($scale);
              return true;
            })()
            """.trimIndent()
        ) { result ->
            if (renderId == activeRenderId && result != "true") fail(renderId)
        }
    }

    fun onPageFinished() {
        loaded = true
        syncIfLoaded()
    }

    fun resetLoadState() {
        loaded = false
        renderedBlockId = null
        renderedRawJson = null
        renderedInteractive = null
        renderedIsDark = null
        renderedFontScale = null
        lastHeight = 72f
        activeRenderId += 1
    }

    fun fail(expectedRenderId: Int = activeRenderId) {
        if (expectedRenderId != activeRenderId) return
        activeRenderId += 1
        renderedRawJson = null
        handler.post { onFailure?.invoke() }
    }

    fun release() {
        loaded = false
        activeRenderId += 1
        renderedBlockId = null
        renderedRawJson = null
        renderedInteractive = null
        renderedIsDark = null
        renderedFontScale = null
        view = null
        onResize = null
        onFailure = null
        onAction = null
    }

    @JavascriptInterface
    fun post(name: String?, value: String?, renderId: Int) {
        val eventName = name ?: return
        val eventValue = value ?: return
        handler.post {
            if (renderId != activeRenderId || activeRenderId <= 0) return@post
            when (eventName) {
                "resize" -> {
                    val parsed = eventValue.toFloatOrNull() ?: return@post
                    if (!parsed.isFinite()) return@post
                    val next = parsed.coerceIn(1f, 20_000f)
                    if (abs(next - lastHeight) >= 0.5f) {
                        lastHeight = next
                        onResize?.invoke(next)
                    }
                }
                "action" -> {
                    if (!interactive || eventValue.length > 16_384 || blockId.isEmpty() || contentVersion.length != 64) return@post
                    onAction?.invoke(eventValue, contentVersion)
                }
                "error" -> fail(renderId)
            }
        }
    }

    private var view: WebView? = null
}

private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "UTF-8",
    403,
    "Blocked",
    emptyMap(),
    ByteArrayInputStream(ByteArray(0))
)
