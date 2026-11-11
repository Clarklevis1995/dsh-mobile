package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import com.clarklevis.dsh.android.localization.appText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Color
import kotlin.math.ceil

internal data class DshMathFormula(val latex: String, val display: Boolean) {
    val source: String get() = "dsh-math://${if (display) "display" else "inline"}/" +
        Base64.encodeToString(latex.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        fun fromSource(source: String): DshMathFormula? {
            val display = when {
                source.startsWith("dsh-math://display/") -> true
                source.startsWith("dsh-math://inline/") -> false
                else -> return null
            }
            return runCatching {
                DshMathFormula(String(Base64.decode(source.substringAfterLast('/'), Base64.URL_SAFE), Charsets.UTF_8), display)
            }.getOrNull()
        }
    }
}

/** 在 CommonMark 消费反斜杠前保护公式，代码、链接和原消息的复制内容保持原样。 */
internal fun prepareMarkdownMath(source: String, forSelection: Boolean = false): String {
    if ('$' !in source && "\\(" !in source && "\\[" !in source) return source
    val result = StringBuilder(source.length)
    var index = 0
    var lineStart = true
    var fence: Pair<Char, Int>? = null
    fun matches(token: String, at: Int = index) = source.startsWith(token, at)
    fun run(at: Int): Int {
        var end = at
        while (end < source.length && source[end] == source[at]) end++
        return end - at
    }
    fun appendThrough(end: Int) {
        result.append(source, index, end)
        lineStart = end > index && source[end - 1] == '\n'
        index = end
    }
    while (index < source.length) {
        if (lineStart) {
            val end = source.indexOf('\n', index).let { if (it < 0) source.length else it + 1 }
            var marker = index
            while (marker < end && (source[marker] == ' ' || source[marker] == '>')) marker++
            // 支持列表和引用中嵌套的代码围栏。
            val listPrefix = Regex("^(?:[-+*]|[0-9]+[.)]) +").find(source.substring(marker, end))
            if (listPrefix != null) marker += listPrefix.value.length
            if (Regex("^\\[[^\\]]+\\]:").containsMatchIn(source.substring(marker, end))) {
                appendThrough(end); continue
            }
            if (marker < end && source[marker] in "`~" && run(marker) >= 3) {
                val count = run(marker)
                val active = fence
                if (active == null) fence = source[marker] to count
                else if (active.first == source[marker] && count >= active.second &&
                    source.substring(marker + count, end).isBlank()) fence = null
                appendThrough(end); continue
            }
            if (fence != null || matches("    ") || source[index] == '\t') {
                appendThrough(end); continue
            }
        }
        lineStart = false
        if (matches("![")) {
            var end = index + 2
            var depth = 1
            while (end < source.length && depth > 0) {
                if (source[end] == '\\') { end = minOf(end + 2, source.length); continue }
                if (source[end] == '[') depth++
                if (source[end] == ']') depth--
                end++
            }
            appendThrough(if (depth == 0) end - 1 else end); continue
        }
        if (source[index] == '`') {
            val count = run(index)
            var end = index + count
            var closing = end
            while (end < source.length) {
                if (source[end] == '`') {
                    val length = run(end)
                    if (length == count) { closing = end + length; break }
                    end += length
                } else end++
            }
            appendThrough(closing); continue
        }
        if (matches("](")) {
            var end = index + 2
            var depth = 1
            while (end < source.length && depth > 0) {
                if (source[end] == '\\') { end = minOf(end + 2, source.length); continue }
                if (source[end] == '(') depth++
                if (source[end] == ')') depth--
                end++
            }
            appendThrough(end); continue
        }
        if (matches("https://") || matches("http://")) {
            var end = index
            while (end < source.length && !source[end].isWhitespace()) end++
            appendThrough(end); continue
        }
        if (source[index] == '<' && index + 1 < source.length &&
            (source[index + 1].isLetter() || source[index + 1] in "/!?")) {
            val end = source.indexOf('>', index)
            if (end >= 0) { appendThrough(end + 1); continue }
        }
        val opening = when {
            matches("\\[") -> "\\["
            matches("\\(") -> "\\("
            matches("$$") -> "$$"
            matches("$") -> "$"
            else -> null
        }
        if (opening != null) {
            val closing = when (opening) { "\\[" -> "\\]"; "\\(" -> "\\)"; else -> opening }
            val display = opening == "\\[" || opening == "$$"
            val start = index + opening.length
            var end = start
            var found = -1
            val validStart = opening != "$" || (start < source.length && !source[start].isWhitespace())
            while (validStart && end < source.length) {
                if (!display && source[end] == '\n') break
                if (matches(closing, end)) {
                    val after = end + closing.length
                    val validEnd = opening != "$" || (end > start && !source[end - 1].isWhitespace() &&
                        (after == source.length || (!source[after].isDigit() && source[after] != '$')))
                    if (validEnd) { found = end; break }
                }
                end += if (source[end] == '\\') 2 else 1
            }
            if (found > start) {
                var latex = source.substring(start, found)
                val prefix = source.substring(source.lastIndexOf('\n', index - 1) + 1, index)
                if (display && Regex("^(?:[ \\t]*>[ \\t]?)+[ \\t]*$").matches(prefix)) {
                    latex = latex.replace(Regex("(?m)^[ \\t]*(?:>[ \\t]?)+"), "")
                }
                latex = latex.trim()
                if (latex.isNotEmpty()) {
                    if (forSelection) {
                        source.substring(index, found + closing.length).codePoints().forEach {
                            result.append("&#$it;")
                        }
                        index = found + closing.length
                        continue
                    }
                    val alt = buildString {
                        for (char in latex) {
                            if (char in "\\[]`*_<>|") append('\\')
                            append(if (char == '\n') ' ' else char)
                        }
                    }
                    result.append("![$alt](${DshMathFormula(latex, display).source})")
                    index = found + closing.length
                    continue
                }
            }
            appendThrough(start); continue
        }
        appendThrough(minOf(index + if (source[index] == '\\') 2 else 1, source.length))
    }
    return result.toString()
}

internal data class DshMathImage(val bitmap: Bitmap, val baseline: Float)
private data class MathImageKey(val formula: DshMathFormula, val size: Float, val color: Int)
private data class MathImageResult(val image: DshMathImage?)

/** JLaTeXMath 内部字体和宏表可变，串行生成并缓存不可变位图，避免消息滚动时重复排版。 */
internal object DshMathRenderer {
    private val cache = object : LruCache<MathImageKey, MathImageResult>(16 * 1024 * 1024) {
        override fun sizeOf(key: MathImageKey, value: MathImageResult) = value.image?.bitmap?.allocationByteCount ?: 128
    }

    @Synchronized
    fun cached(formula: DshMathFormula, size: Float, color: Int): DshMathImage? =
        cache.get(MathImageKey(formula, size, color))?.image

    @Synchronized
    fun image(formula: DshMathFormula, size: Float, color: Int): DshMathImage? {
        if (formula.latex.length > 8_000 || !size.isFinite() || size <= 0) return null
        val key = MathImageKey(formula, size, color)
        cache.get(key)?.let { return it.image }
        val image = runCatching {
            val latex = formula.latex.replace('｛', '{').replace('｝', '}')
            val icon = TeXFormula(latex).TeXIconBuilder().setSize(size)
                .setStyle(if (formula.display) TeXConstants.STYLE_DISPLAY else TeXConstants.STYLE_TEXT)
                .setFGColor(Color(color)).build()
            val width = icon.iconWidth
            val height = icon.iconHeight
            require(width in 1..16_384 && height in 1..4_096 && width.toLong() * height <= 4_194_304)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val graphics = DshMathGraphics(Canvas(bitmap))
            icon.paintIcon(null, graphics, 0, 0)
            DshMathImage(bitmap, icon.insets.top + icon.box.height * size)
        }.getOrNull()
        cache.put(key, MathImageResult(image))
        return image
    }
}

/** 0.2.0 绘制边框会残留 STROKE，字形必须恢复填充，避免 boxed 内文字变成空心。 */
private class DshMathGraphics(private val canvas: Canvas) : AndroidGraphics2D() {
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init { setCanvas(canvas) }

    override fun drawChars(chars: CharArray, offset: Int, length: Int, x: Int, y: Int) {
        font?.let {
            glyphPaint.typeface = it.typeface()
            glyphPaint.textSize = it.size()
        }
        glyphPaint.color = color.colorInt
        canvas.drawText(chars, offset, length, x.toFloat(), y.toFloat(), glyphPaint)
    }
}

internal class DshInlineMathSpan(val formula: DshMathFormula) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val image = DshMathRenderer.image(formula, paint.textSize, paint.color)
            ?: return ceil(paint.measureText(text, start, end)).toInt()
        if (fm != null) {
            fm.ascent = minOf(fm.ascent, -ceil(image.baseline).toInt())
            fm.descent = maxOf(fm.descent, ceil(image.bitmap.height - image.baseline).toInt())
            fm.top = minOf(fm.top, fm.ascent)
            fm.bottom = maxOf(fm.bottom, fm.descent)
        }
        return image.bitmap.width
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
                      top: Int, y: Int, bottom: Int, paint: Paint) {
        val image = DshMathRenderer.image(formula, paint.textSize, paint.color)
        if (image == null) canvas.drawText(text, start, end, x, y.toFloat(), paint)
        else canvas.drawBitmap(image.bitmap, x, y - image.baseline, null)
    }
}

@Composable
internal fun DshMathBlock(formula: DshMathFormula, textSizeSp: Float, color: Int,
                          copyMarkdown: String = "\\[${formula.latex}\\]", modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val onReply = LocalMessageQuote.current
    var anchor by remember { mutableStateOf<IntOffset?>(null) }
    var selection by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableStateOf(IntOffset.Zero) }
    val density = LocalDensity.current
    val sizePx = with(density) { textSizeSp.sp.toPx() }
    var image by remember(formula, sizePx, color) { mutableStateOf(DshMathRenderer.cached(formula, sizePx, color)) }
    var loaded by remember(formula, sizePx, color) { mutableStateOf(false) }
    LaunchedEffect(formula, sizePx, color) {
        image = withContext(Dispatchers.Default) { DshMathRenderer.image(formula, sizePx, color) }
        loaded = true
    }
    Box(modifier.fillMaxWidth().testTag("markdown-math-block")
        .onGloballyPositioned { coordinates ->
            val point = coordinates.localToWindow(Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
            position = IntOffset(point.x.toInt(), point.y.toInt())
        }
        .combinedClickable(onClick = {}, onLongClick = { anchor = position })
        .horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
        val current = image
        if (current != null) Image(current.bitmap.asImageBitmap(), formula.latex,
            Modifier.size(with(density) { current.bitmap.width.toDp() }, with(density) { current.bitmap.height.toDp() }))
        else Text(if (loaded) formula.latex else " ", color = androidx.compose.ui.graphics.Color(color), fontSize = textSizeSp.sp)
    }
    DshPointMenu(anchor, { anchor = null }) {
        DshMenuItem(text = { Text(appText("复制")) }, onClick = {
            anchor = null
            context.copyMarkdownText(markdownPlainText(copyMarkdown))
        })
        DshMenuItem(text = { Text(appText("选择文字")) }, onClick = {
            anchor = null
            selection = copyMarkdown
        })
        if (onReply != null) DshMenuItem(text = { Text(appText("回复")) }, onClick = {
            anchor = null
            onReply(markdownPlainText(copyMarkdown))
        })
    }
    selection?.let { source -> DshMarkdownSelectionPage(source) { selection = null } }
}
