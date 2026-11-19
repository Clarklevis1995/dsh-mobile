package com.clarklevis.dsh.android.ui

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import android.util.LruCache
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 全部插件页面共用；列表项离开屏幕后仍可复用已经准备好的图片。 */
internal class PluginIconImageCache(private val assets: AssetManager) {
    private sealed interface Source {
        data class DataUrl(val value: String) : Source
        data class Asset(val name: String) : Source
    }

    private val images = object : LruCache<Source, Bitmap>(8 * 1_024 * 1_024) {
        override fun sizeOf(key: Source, value: Bitmap): Int = value.allocationByteCount + when (key) {
            is Source.DataUrl -> key.value.length * 2
            is Source.Asset -> key.name.length * 2
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val lock = Mutex()
    private val inFlight = mutableMapOf<Source, Deferred<Bitmap?>>()

    fun peekDataUrl(dataURL: String?): Bitmap? = dataURL?.let { images.get(Source.DataUrl(it)) }

    suspend fun loadDataUrl(dataURL: String?): Bitmap? =
        dataURL?.takeIf { it.length < 256_000 }?.let { image(Source.DataUrl(it)) }

    suspend fun load(dataURL: String?, fallback: String): Bitmap? {
        if (dataURL != null && dataURL.length < 256_000) {
            image(Source.DataUrl(dataURL))?.let { return it }
        }
        val artwork = when (fallback) {
            "shell", "agent-loop", "subagent", "web-search" -> fallback
            else -> "default"
        }
        return image(Source.Asset(artwork))
    }

    private suspend fun image(source: Source): Bitmap? {
        images.get(source)?.let { return it }
        val task = lock.withLock {
            images.get(source)?.let { return it }
            inFlight[source] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    val bitmap = when (source) {
                        is Source.Asset -> assets.open("plugin-artwork/${source.name}.svg").use { renderSVG(it.readBytes()) }
                        is Source.DataUrl -> decodeDataUrl(source.value)
                    }
                    bitmap?.also {
                        it.prepareToDraw()
                        images.put(source, it)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } finally {
                    lock.withLock { inFlight.remove(source) }
                }
            }.also { inFlight[source] = it }
        }
        // 等待者取消（例如快速滑出屏幕）不会取消共享的解码任务。
        return task.await()
    }

    private fun decodeDataUrl(source: String): Bitmap? {
        val comma = source.indexOf(',')
        if (comma < 0 || source.substring(0, comma) !in IMAGE_HEADERS) return null
        val bytes = Base64.decode(source.substring(comma + 1), Base64.DEFAULT)
        return if (source.startsWith("data:image/svg+xml;")) renderSVG(bytes) else decodeBitmap(bytes)
    }

    private fun renderSVG(bytes: ByteArray): Bitmap {
        SVG.setInternalEntitiesEnabled(false)
        val svg = SVG.getFromInputStream(bytes.inputStream())
        // 无 viewBox 的 SVG 也按其原始尺寸缩放，避免只渲染出左上角。
        if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) {
            svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        }
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        val bitmap = Bitmap.createBitmap(ICON_PIXELS, ICON_PIXELS, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bitmap))
        return bitmap
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > ICON_PIXELS) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        private const val ICON_PIXELS = 256
        private val IMAGE_HEADERS = setOf("png", "jpeg", "gif", "webp", "svg+xml").map { "data:image/$it;base64" }.toSet()
        @Volatile private var instance: PluginIconImageCache? = null

        fun trimMemory() {
            instance?.images?.evictAll()
        }

        fun shared(context: Context): PluginIconImageCache = instance ?: synchronized(this) {
            instance ?: PluginIconImageCache(context.applicationContext.assets).also { instance = it }
        }
    }
}
