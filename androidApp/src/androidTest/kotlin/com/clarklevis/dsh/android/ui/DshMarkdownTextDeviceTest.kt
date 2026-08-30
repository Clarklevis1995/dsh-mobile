package com.clarklevis.dsh.android.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.noties.markwon.ext.tables.TableRowSpan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DshMarkdownTextDeviceTest {
    @Test
    fun gfmTableIsRenderedAsTableSpans() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val markwon = buildDshMarkwon(
            context,
            DshMarkdownPalette(
                textColor = 0xFF10131A.toInt(),
                linkColor = 0xFF1478F2.toInt(),
                tableBorderColor = 0x3310131A,
                tableHeaderColor = 0x1410131A,
                tableOddRowColor = 0x0910131A,
                taskOutlineColor = 0x7310131A,
                tableBorderWidthPx = 1,
                tableCellPaddingPx = 8
            )
        )
        val markdown = """
            | 优先级 | 主文案 | 感觉 |
            | :--- | :--- | :---: |
            | ⭐ 推荐 | **平安喜乐** | 温暖 |
            | 备选 | ~~旧文案~~ | 克制 |
        """.trimIndent()

        val rendered = markwon.toMarkdown(markdown)
        val tableRows = rendered.getSpans(
            0,
            rendered.length,
            TableRowSpan::class.java
        )

        assertTrue("GFM 表格应生成 TableRowSpan", tableRows.isNotEmpty())
        assertFalse("表格分隔符不应作为普通正文显示", rendered.toString().contains(":---"))
    }
}
