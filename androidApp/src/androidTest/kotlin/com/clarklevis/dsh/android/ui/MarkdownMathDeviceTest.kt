package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Spanned
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class MarkdownMathDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun palette(color: Int = Color.BLACK) = DshMarkdownPalette(
        color, Color.BLUE, Color.GRAY, Color.LTGRAY, Color.TRANSPARENT, Color.GRAY,
        1, 8, Color.LTGRAY, 8f, 6f, 2f
    )

    @Test fun completeRegisteredSymbolAndStructuralCorpus() {
        val cases = org.json.JSONArray(InstrumentationRegistry.getInstrumentation().context.assets
            .open("latex-math.json").bufferedReader().use { it.readText() })
        val failed = sortedSetOf<String>()
        for (index in 0 until cases.length()) {
            val item = cases.getJSONObject(index)
            val latex = item.getString("latex")
            for (display in listOf(false, true)) for (color in listOf(Color.BLACK, Color.WHITE)) {
                val image = DshMathRenderer.image(DshMathFormula(latex, display), 42f, color)
                if (image == null || !(0 until image.bitmap.height).any { y ->
                    (0 until image.bitmap.width).any { x -> Color.alpha(image.bitmap.getPixel(x, y)) > 0 }
                }) failed += "${item.getString("id")} $latex"
            }
        }
        println("LATEX-AUDIT cases=${cases.length()} failures=$failed")
        assertTrue(failed.joinToString("\n"), failed.isEmpty())
    }

    @Test fun delimitersCodeLinksPricesAndIncompleteStreamingAreHandled() {
        val renderer = buildDshMarkwon(context, palette(), interactiveTables = true)
        val source = "正文 \\(x_n=b\\)，以及 ${'$'}E=mc^2${'$'}。\n\n" +
            "\\[\\boxed{\\lim_{n\\to\\infty}x_n=b}\\]\n\n" +
            "${'$'}${'$'}\\frac{1}{2}${'$'}${'$'}\n\n`\\(literal\\)`\n\n" +
            "```tex\n\\[\\boxed{code}\\]\n```\n\n" +
            "- ```tex\n  ${'$'}code${'$'}\n  ```\n\n" +
            "[链接](https://example.com/${'$'}literal${'$'})\n\n" +
            "![${'$'}literal${'$'}](image.png)\n\n价格 ${'$'}5 和 ${'$'}10，转义 \\${'$'}x，未完成 \\[\\boxed{x"
        val rendered = renderer.toMarkdown(source)
        val math = rendered.getSpans(0, rendered.length, DshInlineMathSpan::class.java)
        assertEquals(4, math.size)
        assertEquals(listOf("x_n=b", "E=mc^2", "\\boxed{\\lim_{n\\to\\infty}x_n=b}", "\\frac{1}{2}"), math.sortedBy(rendered::getSpanStart).map { it.formula.latex })
        assertEquals(2, rendered.getSpans(0, rendered.length, DshMathFormula::class.java).size)
        assertEquals(1, rendered.getSpans(0, rendered.length, DshMarkdownImageMarker::class.java).size)
        assertTrue(rendered.toString().contains("价格 ${'$'}5 和 ${'$'}10"))
        assertFalse(prepareMarkdownMath("未完成 ${'$'}${'$'}x+1").contains("dsh-math"))
        val code = "> ```tex\n> ${'$'}x${'$'}\n> ```\n\n    ${'$'}indented${'$'}\n\n[ref]: https://example.com/${'$'}x${'$'}"
        assertEquals(code, prepareMarkdownMath(code))
        val quote = renderer.toMarkdown("> \\[\n> \\boxed{x=2}\n> \\]")
        assertEquals("\\boxed{x=2}", quote.getSpans(0, quote.length, DshMathFormula::class.java).single().latex)
    }

    @Test fun boxedFractionsMatricesAndNestedScriptsPaintInBothThemes() {
        val examples = listOf(
            "\\boxed{\\lim_{n\\to\\infty}x_n=b}",
            "\\left(\\frac{1}{b-a}\\int_a^b f(x)^n\\,dx\\right)^{1/n}",
            "\\boxed{\\frac{1}{2}}", "\\boxed{x}^{2}_{n}", "\\boxed{\\boxed{x=2}}",
            "\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}",
            "f(x)=\\begin{cases}x^2&x>0\\\\0&x\\le0\\end{cases}",
            "\\sqrt{x^2+y^2}+\\alpha+\\beta"
        )
        for (latex in examples) for (display in listOf(false, true)) for (color in listOf(Color.BLACK, Color.WHITE)) {
            val formula = DshMathFormula(latex, display)
            val image = DshMathRenderer.image(formula, 42f, color)
            assertNotNull("实际排版失败: $latex display=$display", image)
            image!!
            assertTrue(image.baseline > 0 && image.baseline <= image.bitmap.height)
            assertTrue(image.bitmap.width > 0)
            var painted = 0
            for (y in 0 until image.bitmap.height) for (x in 0 until image.bitmap.width) {
                val pixel = image.bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 100) {
                    assertEquals(color and 0xFFFFFF, pixel and 0xFFFFFF)
                    painted++
                }
            }
            assertTrue("不能仅返回空白占位图", painted > 12)
            assertSame(image, DshMathRenderer.image(formula, 42f, color))
        }
        assertNotNull(DshMathRenderer.image(DshMathFormula("\\boxed｛x=2｝", true), 42f, Color.BLACK))
        assertNull(DshMathRenderer.image(DshMathFormula("\\notARealCommand{x}", false), 42f, Color.BLACK))
        assertNull(DshMathRenderer.image(DshMathFormula("x".repeat(8_001), false), 42f, Color.BLACK))
    }

    @Test fun inlineSpanUsesMathBaselineAndReservesAscentAndDescent() {
        val formula = DshMathFormula("\\boxed{x=2}", false)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 48f; color = Color.BLACK }
        val image = requireNotNull(DshMathRenderer.image(formula, paint.textSize, paint.color))
        val span = DshInlineMathSpan(formula)
        val metrics = paint.fontMetricsInt
        assertEquals(image.bitmap.width, span.getSize(paint, formula.latex, 0, formula.latex.length, metrics))
        assertTrue(-metrics.ascent >= image.baseline)
        assertTrue(metrics.descent >= image.bitmap.height - image.baseline)
        val output = Bitmap.createBitmap(image.bitmap.width + 20, 180, Bitmap.Config.ARGB_8888)
        span.draw(Canvas(output), formula.latex, 0, formula.latex.length, 10f, 0, 110, 180, paint)
        val rows = (0 until output.height).filter { y -> (0 until output.width).any { x -> Color.alpha(output.getPixel(x, y)) > 100 } }
        val imageRows = (0 until image.bitmap.height).filter { y -> (0 until image.bitmap.width).any { x -> Color.alpha(image.bitmap.getPixel(x, y)) > 100 } }
        assertEquals(110f - image.baseline + imageRows.first(), rows.first().toFloat(), 1f)
        assertEquals(110f - image.baseline + imageRows.last(), rows.last().toFloat(), 1f)
    }

    @Test fun boxedGlyphsStayFilledAfterDrawingTheFrame() {
        fun painted(bitmap: Bitmap, left: Int = 0, top: Int = 0, right: Int = bitmap.width, bottom: Int = bitmap.height): Int =
            (top until bottom).sumOf { y -> (left until right).count { x -> Color.alpha(bitmap.getPixel(x, y)) > 128 } }
        val plain = requireNotNull(DshMathRenderer.image(DshMathFormula("MMMM", true), 60f, Color.BLACK)).bitmap
        val boxed = requireNotNull(DshMathRenderer.image(DshMathFormula("\\boxed{MMMM}", true), 60f, Color.BLACK)).bitmap
        val columns = (0 until boxed.width).filter { x -> (0 until boxed.height).count { y -> Color.alpha(boxed.getPixel(x, y)) > 128 } > boxed.height / 2 }
        val rows = (0 until boxed.height).filter { y -> (0 until boxed.width).count { x -> Color.alpha(boxed.getPixel(x, y)) > 128 } > boxed.width * .8 }
        assertTrue(columns.size >= 2 && rows.size >= 2)
        val interior = painted(boxed, columns.last { it < boxed.width / 2 } + 2, rows.last { it < boxed.height / 2 } + 2,
            columns.first { it > boxed.width / 2 } - 1, rows.first { it > boxed.height / 2 } - 1)
        assertEquals("方框不能把后续文字变成空心描边", painted(plain).toDouble(), interior.toDouble(), painted(plain) * .15)
    }

    @Test fun copyingAndSelectionKeepOriginalLatexAndCode() {
        val source = "正文 \\(\\boxed{x_n=2}\\) 与 ${'$'}y^2${'$'}\n\n\\[\\frac{1}{2}\\]\n\n`\\boxed{example}`"
        val expected = source.replace("`", "")
        assertEquals(expected, markdownPlainText(source))
        val selected = renderMarkdownSelectionText(source, 42f, 2f, Color.LTGRAY, Color.BLUE, Color.GRAY).toString()
        assertTrue(selected.contains("\\(\\boxed{x_n=2}\\)"))
        assertTrue(selected.contains("\\[\\frac{1}{2}\\]"))
        assertFalse(selected.contains("dsh-math"))
        val invalid = buildDshMarkwon(context, palette()).toMarkdown("\\(\\notARealCommand{x}\\)")
        val span = invalid.getSpans(0, invalid.length, DshInlineMathSpan::class.java).single()
        val paint = Paint().apply { textSize = 42f }
        assertTrue(span.getSize(paint, invalid, 0, invalid.length, paint.fontMetricsInt) > 0)
    }

    @Test fun readingViewDisplaysRealFormulasAndCodeInLightAndDark() {
        val dark = mutableStateOf(false)
        val source = "普通公式\n\n\\[\\sqrt[n]{b_1\\cdots b_n}\\le\\dfrac{b_1+\\cdots+b_n}{n}\\]\n\n" +
            "因此\n\n\\[\\boxed{G_n\\le\\frac{1}{n C_n}\\sum_{k=1}^n c_k a_k}\\tag{1}\\]\n\n正文 \\(\\boxed{x=2}\\) 正文。\n\n" +
            "```tex\n\\boxed{code_example}\n```"
        compose.setContent {
            DshTheme {
                DshMenuHost {
                    Column(Modifier.width(360.dp).background(if (dark.value) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White)
                        .padding(16.dp).testTag("math-fixture")) {
                        DshRenderedMarkdownText(source,
                            buildDshMarkwon(context, palette(if (dark.value) Color.WHITE else Color.BLACK), interactiveTables = true).toMarkdown(source),
                            palette(if (dark.value) Color.WHITE else Color.BLACK), 16f, 4f, Modifier)
                    }
                }
            }
        }
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark.value = isDark }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("\\boxed{G_n\\le\\frac{1}{n C_n}\\sum_{k=1}^n c_k a_k}\\tag{1}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("\\boxed{G_n\\le\\frac{1}{n C_n}\\sum_{k=1}^n c_k a_k}\\tag{1}").assertIsDisplayed()
            compose.onNodeWithTag("markdown-code-card").assertIsDisplayed()
            val bitmap = compose.onNodeWithTag("math-fixture").captureToImage().asAndroidBitmap()
            File(context.filesDir, "android-math-${if (isDark) "dark" else "light"}.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun longDisplayFormulaScrollsWithoutShrinking() {
        val formula = DshMathFormula("\\boxed{" + (1..28).joinToString("+") { "x_{$it}" } + "}", true)
        compose.setContent { DshTheme { androidx.compose.foundation.layout.Box(Modifier.width(280.dp)) { DshMathBlock(formula, 20f, Color.BLACK) } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(formula.latex).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNodeWithTag("markdown-math-block")
        val initial = node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange]
        assertEquals(0f, initial.value(), 0f)
        assertTrue(initial.maxValue() > 0)
        node.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val after = node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue(after.value() > 0)
    }

    @Test fun displayFormulaKeepsMessageCopyMenuAndLatexDelimiters() {
        val source = "答案：\n\n\\[\\boxed{x=2}\\]"
        compose.setContent {
            DshTheme { DshMenuHost { DshMarkdownText(source) } }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("\\boxed{x=2}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("markdown-math-block").performTouchInput { longClick() }
        compose.onNodeWithText("复制").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals(source, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }
}
