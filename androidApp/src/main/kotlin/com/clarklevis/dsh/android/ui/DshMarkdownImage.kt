package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.clarklevis.dsh.android.platform.MarkdownImageAsset
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.facade.ConversationFileReferences
import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import org.commonmark.node.Image as MarkdownImage
import org.commonmark.node.Node
import org.commonmark.node.HtmlInline
import org.commonmark.node.HtmlBlock
import org.commonmark.node.Paragraph
import org.commonmark.node.Text as MarkdownText
import io.noties.markwon.html.HtmlEmptyTagReplacement
import io.noties.markwon.html.HtmlTag
import io.noties.markwon.html.MarkwonHtmlParserImpl

internal data class DshMarkdownImageMarker(val source: String, val alt: String)

internal class DshMarkdownImagePlugin : AbstractMarkwonPlugin() {
    override fun beforeRender(node: Node) {
        fun visit(parent: Node) {
            var child = parent.firstChild
            while (child != null) {
                val next = child.next
                val html = when (child) {
                    is HtmlInline -> child.literal
                    is HtmlBlock -> child.literal
                    else -> null
                }
                val images = html?.let(::htmlOnlyImages).orEmpty()
                if (images.isNotEmpty()) {
                    val replacement = if (child is HtmlBlock) Paragraph() else null
                    if (replacement != null) child.insertBefore(replacement)
                    images.forEach { marker ->
                        val image = MarkdownImage().apply {
                            destination = marker.source
                            appendChild(MarkdownText(marker.alt))
                        }
                        if (replacement != null) replacement.appendChild(image) else child.insertBefore(image)
                    }
                    child.unlink()
                } else visit(child)
                child = next
            }
        }
        visit(node)
    }

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(MarkdownImage::class.java) { visitor, node ->
            val formula = DshMathFormula.fromSource(node.destination)
            if (formula != null) {
                val start = visitor.length()
                visitor.builder().append(formula.latex.replace('\n', ' '))
                visitor.setSpans(start, DshInlineMathSpan(formula))
                if (formula.display) visitor.setSpans(start, formula)
                return@on
            }
            val start = visitor.length()
            visitor.visitChildren(node)
            val alt = visitor.builder().subSequence(start, visitor.length()).toString()
            if (visitor.length() == start) visitor.builder().append('\uFFFC')
            visitor.setSpans(start, DshMarkdownImageMarker(node.destination, alt))
        }
    }
}

/** 复用 Markwon 的 HTML tokenizer，支持引号、大小写和实体，代码节点不参与转换。 */
private fun htmlOnlyImages(html: String): List<DshMarkdownImageMarker> {
    if (!html.contains("<img", ignoreCase = true)) return emptyList()
    val images = mutableListOf<DshMarkdownImageMarker>()
    val parser = MarkwonHtmlParserImpl.create(object : HtmlEmptyTagReplacement() {
        override fun replace(tag: HtmlTag): String? {
            if (tag.name() != "img") return super.replace(tag)
            val source = tag.attributes()["src"]?.takeIf(String::isNotBlank) ?: return null
            images += DshMarkdownImageMarker(source, tag.attributes()["alt"].orEmpty())
            return "\uFFFC"
        }
    })
    val output = StringBuilder()
    parser.processFragment(output, html)
    return images.takeIf { output.toString().replace("\uFFFC", "").isBlank() }.orEmpty()
}

internal class DshMarkdownImageContext(
    val id: String,
    val references: Map<String, GatewayImageAttachment>,
    val cached: (String) -> MarkdownImageAsset?,
    val load: suspend (String) -> MarkdownImageAsset,
    val gatewayId: String? = null,
    val sessionId: String? = null,
    val documentPath: String? = null
) {
    fun source(path: String): String = documentPath?.let { resolveMarkdownImageSource(path, it) } ?: path
    fun relativeTo(path: String) = DshMarkdownImageContext(
        "$id:${path.length}:$path", references, cached, load, gatewayId, sessionId, path
    )
}

/** 相对路径以远端文档目录为基准，根目录边界继续交给网关校验。 */
internal fun resolveMarkdownImageSource(source: String, documentPath: String): String {
    if (source.startsWith("https://", true) || source.startsWith("http://", true)) return source
    val path = ConversationFileReferences.filePath(source) ?: error("Unsupported image source")
    if (path.startsWith('/')) return source
    val directory = documentPath.substringBeforeLast('/', "")
    val components = mutableListOf<String>()
    for (part in "$directory/$path".split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> { require(components.isNotEmpty()); components.removeAt(components.lastIndex) }
            else -> components.add(part)
        }
    }
    require(components.isNotEmpty())
    // 加载器入口统一解码一次，保留文件名里本来就存在的百分号。
    return ((if (documentPath.startsWith('/')) "/" else "") + components.joinToString("/")).replace("%", "%25")
}

@Composable
internal fun rememberDshMarkdownImages(
    holder: com.clarklevis.dsh.android.AndroidSharedStateHolder,
    sessionId: String?
): DshMarkdownImageContext? {
    val references = holder.snapshot.markdownImages.takeIf { holder.snapshot.selectedSessionId == sessionId }.orEmpty()
    return remember(holder, sessionId, references) {
        sessionId?.let { id -> DshMarkdownImageContext(
            "${holder.gatewayLocalId}:$id", references,
            cached = { holder.cachedMarkdownImage(id, it) }, load = { holder.loadMarkdownImage(id, it) },
            gatewayId = holder.gatewayLocalId, sessionId = id
        ) }
    }
}
internal val LocalDshMarkdownImages = staticCompositionLocalOf<DshMarkdownImageContext?> { null }

@Composable
internal fun DshMarkdownImage(marker: DshMarkdownImageMarker) {
    val context = LocalDshMarkdownImages.current
    if (context == null) { Text(marker.alt); return }
    val source = remember(context.id, marker.source) { runCatching { context.source(marker.source) }.getOrNull() }
    val reference = source?.let(ConversationFileReferences::filePath)?.let(context.references::get)
    val ratio = remember(context.id, marker.source) {
        reference?.let { it.width.toFloat() / it.height }?.takeIf { it.isFinite() && it > 0 }?.coerceIn(0.1f, 10f) ?: 1f
    }
    var asset by remember(context.id, marker.source) { mutableStateOf(source?.let(context.cached)) }
    var failed by remember(context.id, marker.source) { mutableStateOf(false) }
    var retry by remember(context.id, marker.source) { mutableIntStateOf(0) }
    var preview by remember(context.id, marker.source) { mutableStateOf(false) }
    LaunchedEffect(context.id, marker.source, retry) {
        if (asset != null) return@LaunchedEffect
        failed = false
        try { asset = context.load(requireNotNull(source)) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
        .testTag("markdown-image:${marker.source}")
        .clickable(enabled = asset != null || failed) { if (failed) retry++ else preview = true },
        contentAlignment = Alignment.Center) {
        val loaded = asset
        if (loaded != null) Image(loaded.image, marker.alt.ifEmpty { appText("图片") },
            Modifier.fillMaxSize().drawWithCache {
                // 缺少远端尺寸时保持占位高度，圆角仍落在实际图片边缘，不能只裁剪留白外框。
                val scale = minOf(size.width / loaded.image.width, size.height / loaded.image.height)
                val width = loaded.image.width * scale
                val height = loaded.image.height * scale
                val left = (size.width - width) / 2
                val top = (size.height - height) / 2
                val path = Path().apply {
                    addRoundRect(RoundRect(left, top, left + width, top + height, CornerRadius(12.dp.toPx())))
                }
                onDrawWithContent { clipPath(path) { this@onDrawWithContent.drawContent() } }
            }, contentScale = ContentScale.Fit)
        else Text(appText(if (failed) "加载失败 · 点击重试" else "正在加载图片…"),
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
    }
    if (preview) asset?.let { loaded ->
        val attachment = GatewayImageAttachment("markdown-preview", loaded.mediaType, loaded.bytes.size, loaded.width, loaded.height,
            reference?.name ?: marker.alt.ifEmpty { "image" })
        ConversationImagePreviewDialog(ConversationImagePreviewRequest(listOf(attachment), 0),
            mapOf(attachment.attachmentId to loaded.image), onDismiss = { preview = false }, loadOriginal = { loaded.bytes })
    }
}
