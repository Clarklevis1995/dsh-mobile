package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DshCodeSyntaxHighlighterDeviceTest {
    @Test
    fun highlightUsesLanguagesAndThemeWithoutChangingSource() {
        val code = "// 注释😀\r\n\tval text = \"你好 return\"\r\n\treturn 42\r\n"
        val light = DshCodeSyntaxHighlighter.highlight(code, "kt", false)
        val dark = DshCodeSyntaxHighlighter.highlight(code, "kotlin", true)
        assertEquals(code, light.toString())
        assertEquals(code, dark.toString())
        val colors = light.getSpans(0, light.length, ForegroundColorSpan::class.java).map { it.foregroundColor }
        assertTrue(colors.distinct().size >= 4)
        assertNotEquals(colors, dark.getSpans(0, dark.length, ForegroundColorSpan::class.java).map { it.foregroundColor })
        assertTrue(
            DshCodeSyntaxHighlighter
                .highlight("def", "swift", false)
                .getSpans(0, 3, ForegroundColorSpan::class.java)
                .isEmpty(),
        )
    }

    @Test
    fun markwonRendersMultipleLanguagesInBothThemesAndPreservesCodeCopy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val density = context.resources.displayMetrics.density
            val source =
                "```kotlin\n// Kotlin 示例\nfun greet(name: String): String {\n" +
                    "    val text = \"你好😀\"\n    return text + name + 42\n}\n```\n\n" +
                    "```python\n# Python 示例\ndef greet(name):\n    return \"hello\" + name\n```\n\n" +
                    "```latex\n\\frac{1}{2} % LaTeX 源码\n```"
            for (dark in listOf(false, true)) {
                val view =
                    DshMarkdownTextView(context).apply {
                        setTextColor(if (dark) Color.WHITE else Color.BLACK)
                        setBackgroundColor(if (dark) Color.BLACK else Color.WHITE)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                        setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), 0)
                    }
                val rendered = buildDshMarkwon(context, palette(dark)).toMarkdown(source)
                assertTrue(rendered.getSpans(0, rendered.length, ForegroundColorSpan::class.java).size > 12)
                assertEquals(
                    "// Kotlin 示例\nfun greet(name: String): String {\n    val text = \"你好😀\"\n    return text + name + 42\n}\n",
                    rendered
                        .getSpans(0, rendered.length, DshCodeCopySpan::class.java)
                        .first { it.code.startsWith("// Kotlin") }
                        .code,
                )
                buildDshMarkwon(context, palette(dark)).setParsedMarkdown(view, rendered)
                val width = (390 * density).toInt()
                val height = (850 * density).toInt()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, width, height)
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(image))
                val pixels = IntArray(width * height)
                image.getPixels(pixels, 0, width, 0, 0, width, height)
                assertTrue(
                    pixels.count { color ->
                        maxOf(Color.red(color), Color.green(color), Color.blue(color)) -
                            minOf(Color.red(color), Color.green(color), Color.blue(color)) > 40
                    } > 300,
                )
                File(context.getExternalFilesDir(null), "syntax-${if (dark) "dark" else "light"}.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
        }
    }

    @Test
    fun selectionCodePreservesHighlightAndExactWhitespace() {
        val code = "\tdef greet():\n\t\treturn \"你好😀\"\n"
        val source = "```python\n$code```"
        val rendered =
            renderMarkdownSelectionText(
                source,
                17f,
                1f,
                DshSelectionStyle.LIGHT_CODE,
                DshSelectionStyle.LIGHT_ACCENT,
                Color.GRAY,
            )
        assertEquals(code.removeSuffix("\n"), markdownSelectionText(rendered, 0, rendered.length))
        val spans = rendered.getSpans(0, rendered.length, ForegroundColorSpan::class.java)
        assertTrue(spans.size >= 3)
        val keyword = rendered.toString().indexOf("def")
        assertTrue(spans.any { rendered.getSpanStart(it) == keyword })
    }

    private fun palette(dark: Boolean) =
        DshMarkdownPalette(
            textColor = if (dark) Color.WHITE else Color.BLACK,
            linkColor = 0xFF1478F2.toInt(),
            tableBorderColor = Color.GRAY,
            tableHeaderColor = Color.LTGRAY,
            tableOddRowColor = Color.TRANSPARENT,
            taskOutlineColor = Color.GRAY,
            tableBorderWidthPx = 1,
            tableCellPaddingPx = 8,
            inlineCodeBackgroundColor = if (dark) DshSelectionStyle.DARK_CODE else DshSelectionStyle.LIGHT_CODE,
            inlineCodeCornerRadiusPx = 6f,
            inlineCodeHorizontalPaddingPx = 4f,
            inlineCodeVerticalPaddingPx = 1f,
        )
}
