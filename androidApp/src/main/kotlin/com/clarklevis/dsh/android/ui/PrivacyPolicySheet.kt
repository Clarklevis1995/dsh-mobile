package com.clarklevis.dsh.android.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.view.ContextThemeWrapper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText

private const val PRIVACY_POLICY_URL = "https://clarklevis1995.github.io/dsh-mobile/privacy/"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivacyPolicyBottomSheet(onDismiss: () -> Unit) {
    var isLoading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var nativeMenu by remember { mutableStateOf<DshNativeTextMenu?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    val dark = isSystemInDarkTheme()
    val textZoom = (LocalDensity.current.fontScale * 100).toInt()
    val hostContext = LocalContext.current
    val pageBackground = if (dark) Color(0xFF0C1420) else Color(0xFFF7F9FC)
    val titleSidePadding = (40.dp + 32.dp * LocalDensity.current.fontScale).coerceAtLeast(80.dp)

    ModalBottomSheet(
        modifier = Modifier.adaptiveSheetBounds(),
        sheetMaxWidth = adaptiveSheetWidth(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // WebView 不参与 Compose 嵌套滚动，禁用弹窗拖动以避免截获正文滑动。
        sheetGesturesEnabled = false,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        // 网页延伸至导航条下方；只避让横向系统区域，不再留出白色底边。
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal) },
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = !dark,
            isAppearanceLightNavigationBars = !dark
        ),
        dragHandle = {
            Box(Modifier.padding(top = 8.dp)) {
                Box(
                    Modifier.size(width = 36.dp, height = 5.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f), CircleShape)
                )
            }
        }
    ) {
        DshMenuColumnHost {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f)) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                        .heightIn(min = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        appText("隐私政策"),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = titleSidePadding),
                        style = SettingsTypography.title,
                        textAlign = TextAlign.Center
                    )
                    Surface(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.CenterEnd).heightIn(min = 36.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 2.dp
                    ) {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                            Text(appText("完成"), style = SettingsTypography.item)
                        }
                    }
                }

                Box(Modifier.fillMaxWidth().weight(1f).background(pageBackground)) {
                    key(reloadKey, dark) {
                        AndroidView(
                            factory = {
                                // Compose 的主题不会改 Activity 主题；显式传给 WebView，
                                // 让网页的 prefers-color-scheme 跟随应用外观设置。
                                val configuration = Configuration().apply {
                                    uiMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                                }
                                val themedContext = ContextThemeWrapper(
                                    hostContext,
                                    if (dark) R.style.Theme_PrivacyPolicy_Dark else R.style.Theme_PrivacyPolicy_Light
                                ).apply {
                                    // 使用宿主窗口服务，深浅色仅覆盖资源配置。
                                    applyOverrideConfiguration(configuration)
                                }
                                DshMenuWebView(themedContext).apply {
                                    onMenuChanged = { nativeMenu = it }
                                    setBackgroundColor(pageBackground.toArgb())
                                    settings.javaScriptEnabled = false
                                    settings.textZoom = textZoom
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        isForceDarkAllowed = false
                                    }
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                            isLoading = true
                                            loadFailed = false
                                        }

                                        override fun onPageFinished(view: WebView, url: String?) {
                                            isLoading = false
                                        }

                                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                            if (request.isForMainFrame) {
                                                isLoading = false
                                                loadFailed = true
                                            }
                                        }

                                        override fun onReceivedHttpError(
                                            view: WebView,
                                            request: WebResourceRequest,
                                            errorResponse: WebResourceResponse
                                        ) {
                                            if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                                                isLoading = false
                                                loadFailed = true
                                            }
                                        }

                                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                            if (request.url.scheme != "mailto") return false
                                            runCatching {
                                                view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                                            }
                                            return true
                                        }
                                    }
                                    loadUrl(PRIVACY_POLICY_URL)
                                }
                            },
                            update = { webView -> webView.settings.textZoom = textZoom },
                            onRelease = { webView ->
                                webView.dismissTextMenu()
                                webView.onMenuChanged = null
                                nativeMenu = null
                                webView.stopLoading()
                                webView.webViewClient = WebViewClient()
                                webView.destroy()
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    nativeMenu?.let { menu ->
                        DshTextActions(
                            androidx.compose.ui.geometry.Rect(
                                menu.bounds.left.toFloat(),
                                menu.bounds.top.toFloat(),
                                menu.bounds.right.toFloat(),
                                menu.bounds.bottom.toFloat()
                            ),
                            menu.dismiss,
                            menu.actions
                        )
                    }

                    if (isLoading) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                    if (loadFailed) {
                        Column(
                            Modifier.fillMaxSize().background(pageBackground),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(appText("无法打开隐私政策"), style = SettingsTypography.description)
                            TextButton(onClick = {
                                loadFailed = false
                                isLoading = true
                                reloadKey++
                            }) { Text(appText("重试"), style = SettingsTypography.item) }
                        }
                    }
                }
            }
        }
    }
}
