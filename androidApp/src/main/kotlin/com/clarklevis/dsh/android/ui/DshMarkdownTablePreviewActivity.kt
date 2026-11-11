package com.clarklevis.dsh.android.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** 预览拥有独立的方向和生命周期，不修改聊天 Activity 的 requestedOrientation。 */
class DshMarkdownTablePreviewActivity : ComponentActivity() {
    private var sourceFile: File? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppearanceSettings.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 只接受本应用缓存目录下的快照，避免 Intent 携带大段正文触发 Binder 大小限制。
        sourceFile = intent.getStringExtra(EXTRA_SOURCE)?.let { name ->
            File(cacheDir, name).takeIf { it.parentFile == cacheDir && name.startsWith("table-preview-") }
        }
        setContent {
            DshTheme {
                DshMenuHost {
                    val context = LocalContext.current
                    val palette = dshMarkdownPalette(compact = false)
                    val renderer = remember(palette) { buildDshMarkwon(context, palette, interactiveTables = true) }
                    val snapshot by produceState<PreviewSnapshot?>(null, sourceFile, renderer) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                val data = JSONObject(requireNotNull(sourceFile).readText())
                                val rendered = renderer.toMarkdown(data.getString("markdown"))
                                val tables = rendered.getSpans(0, rendered.length, DshTableMarker::class.java)
                                    .sortedBy(rendered::getSpanStart)
                                PreviewSnapshot(tables[data.getInt("index")].table, data.getDouble("textSize").toFloat(),
                                    data.optString("imageGatewayId").takeIf(String::isNotEmpty),
                                    data.optString("imageSessionId").takeIf(String::isNotEmpty),
                                    data.optString("imageDocumentPath").takeIf(String::isNotEmpty))
                            }.getOrNull()
                        }
                        if (value == null) finish()
                    }
                    snapshot?.let { current ->
                        val holder = (application as? com.clarklevis.dsh.android.DshAndroidApplication)?.graph
                            ?.takeIf { it.gatewayLocalId == current.gatewayId }?.stateHolder
                        val base = holder?.let { rememberDshMarkdownImages(it, current.sessionId) }
                        val images = remember(base, current.documentPath) {
                            current.documentPath?.let { base?.relativeTo(it) } ?: base
                        }
                        androidx.compose.runtime.CompositionLocalProvider(LocalDshMarkdownImages provides images) {
                            DshMarkdownTablePreview(current.table, current.textSize,
                                onClose = { finish() },
                                onRotate = {
                                    requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                    } else {
                                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                    }
                                },
                                onOpenFile = { path ->
                                    setResult(RESULT_OK, Intent().putExtra(EXTRA_OPEN_FILE, path))
                                    finish()
                                })
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 配置重建继续使用同一份快照；真正退出才清理。
        if (isFinishing) sourceFile?.delete()
    }

    private data class PreviewSnapshot(val table: DshTableData, val textSize: Float,
        val gatewayId: String?, val sessionId: String?, val documentPath: String?)

    companion object {
        private const val EXTRA_SOURCE = "table_preview_source"
        internal const val EXTRA_OPEN_FILE = "table_preview_open_file"

        internal fun createIntent(context: Context, markdown: String, tableIndex: Int, textSizeSp: Float,
            images: DshMarkdownImageContext? = null): Intent {
            val file = File.createTempFile("table-preview-", ".json", context.cacheDir)
            try {
                file.writeText(JSONObject().put("markdown", markdown).put("index", tableIndex)
                    .put("textSize", textSizeSp.toDouble())
                    .put("imageGatewayId", images?.gatewayId)
                    .put("imageSessionId", images?.sessionId)
                    .put("imageDocumentPath", images?.documentPath).toString())
                return Intent(context, DshMarkdownTablePreviewActivity::class.java).putExtra(EXTRA_SOURCE, file.name)
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }
    }
}
