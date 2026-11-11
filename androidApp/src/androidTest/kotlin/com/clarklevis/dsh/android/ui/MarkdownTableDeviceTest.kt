package com.clarklevis.dsh.android.ui

import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TableLayout
import android.widget.TableRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.shared.facade.MarkdownTableExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MarkdownTableDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule val compose = createComposeRule()

    @Test fun readingTableKeepsLeadingInsetAfterRecomposition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("table-inset-test", Context.MODE_PRIVATE)
        val appearance = AppearanceSettings(preferences).apply { selectInterfaceStyle(InterfaceStyle.DARK) }
        var root: View? = null
        val source = "| 对比 | 结论 |\n| --- | --- |\n| 高端定价权 | 苹果 > 三星 > 华为 > 其他中国品牌。iPhone Duo 卖 15999 元仍有人排队，这是品牌溢价，不是参数。 |\n| 供应链抗压 | 三星（上游自供）> 苹果（议价 + 库存）> 华为（自研 + 国内链） |\n| 折叠屏 | 中国厂商领先做产品，但苹果一入场就拿到最高溢价。 |"
        try {
            compose.setContent {
                root = LocalView.current
                DshTheme(appearance) {
                    Column(Modifier.width(380.dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
                        DshMarkdownText(source)
                    }
                }
            }
            compose.waitForIdle()
            fun checkInsets() {
                compose.runOnIdle {
                    val table = descendants(checkNotNull(root)).filterIsInstance<TableLayout>().single()
                    val viewport = table.parent as HorizontalScrollView
                    val minimum = 11 * table.resources.displayMetrics.density
                    repeat(table.childCount) { rowIndex ->
                        val cell = (table.getChildAt(rowIndex) as TableRow).getChildAt(0) as DshMarkdownTextView
                        val inset = cell.left + cell.compoundPaddingLeft + cell.layout.getLineLeft(0) - cell.scrollX - viewport.scrollX
                        assertTrue("第 $rowIndex 行应保留左内边距；inset=$inset padding=${cell.paddingLeft} cellScroll=${cell.scrollX} tableScroll=${viewport.scrollX}", inset >= minimum)
                    }
                }
            }
            checkInsets()
            compose.runOnIdle {
                val table = descendants(checkNotNull(root)).filterIsInstance<TableLayout>().single()
                // 普通单元格不应因系统焦点恢复把表格横向推到末端。
                assertFalse("普通单元格不应抢占焦点", (table.getChildAt(0) as TableRow).getChildAt(1).requestFocusFromTouch())
            }
            checkInsets()
            compose.runOnIdle { appearance.selectInterfaceStyle(InterfaceStyle.LIGHT) }
            checkInsets()
            android.os.SystemClock.sleep(300)
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            context.cacheDir.resolve("table-reading-inset.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        } finally { preferences.edit().clear().apply() }
    }

    @Test fun tenColumnsKeepReadableWidthsAndCopyAndFullscreenWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var root: View? = null
        val header = listOf("编号", "任务名称", "负责人", "所属部门", "优先级", "开始日期", "截止日期", "进度状态", "主要交付物", "备注说明")
        val values = listOf("001", "**新版官网首页改版**", "王晓明", "设计部", "高", "2026-10-21", "2026-11-05", "进行中", "`design.pdf`", "需与前端确认动效成本，支持深色模式")
        val source = "前文\n\n|${header.joinToString("|")}|\n|${header.joinToString("|") { "---" }}|\n|${values.joinToString("|")}|\n\n后文"
        compose.setContent {
            root = LocalView.current
            DshTheme { Column(Modifier.verticalScroll(rememberScrollState())) { DshMarkdownText(source) } }
        }
        compose.onNodeWithTag("markdown-table-card").assertIsDisplayed()
        val table = compose.runOnIdle { descendants(checkNotNull(root)).filterIsInstance<TableLayout>().single() }
        compose.runOnIdle {
            val row = table.getChildAt(0) as ViewGroup
            val minimum = 95 * table.resources.displayMetrics.density
            assertEquals(10, row.childCount)
            repeat(10) { assertTrue("第 $it 列不应被压成竖排", row.getChildAt(it).width >= minimum) }
            assertTrue(table.width > checkNotNull(root).width * 2)
            assertEquals(2, table.childCount)
        }
        androidx.test.espresso.Espresso.onView(org.hamcrest.Matchers.instanceOf(HorizontalScrollView::class.java))
            .perform(androidx.test.espresso.action.ViewActions.swipeLeft())
        compose.runOnIdle {
            assertTrue("横向拖动应滚动表格", (table.parent as HorizontalScrollView).scrollX > 0)
        }
        compose.onNodeWithContentDescription(com.clarklevis.dsh.android.localization.appText("复制表格")).performClick()
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.runOnIdle {
            val plain = clipboard.primaryClip?.getItemAt(0)?.text.toString()
            assertTrue(plain.contains("新版官网首页改版"))
            assertTrue(plain.contains("design.pdf"))
            assertFalse(plain.contains("**")); assertFalse(plain.contains("`")); assertFalse(plain.contains("---"))
            assertEquals(10, plain.lineSequence().first().split('\t').size)
        }
        compose.onNodeWithContentDescription(com.clarklevis.dsh.android.localization.appText("全屏浏览表格")).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("markdown-table-fullscreen")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("markdown-table-fullscreen").assertIsDisplayed()
        android.os.SystemClock.sleep(4000)
        // 保存真实渲染，检查表格宽度、工具栏与同一行单元格的高度。
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("markdown-table-fullscreen.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        val downloads = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(com.clarklevis.dsh.android.localization.appText("下载 CSV")))
        downloads[downloads.fetchSemanticsNodes().lastIndex].performClick()
        android.os.SystemClock.sleep(1000)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // 第一次返回可能只关闭文件名输入键盘，只有选择器仍在前台时才继续取消。
        repeat(3) {
            if (automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true) {
                val time = android.os.SystemClock.uptimeMillis()
                automation.injectInputEvent(android.view.KeyEvent(time, time, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK, 0), true)
                automation.injectInputEvent(android.view.KeyEvent(time, time + 20, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK, 0), true)
                android.os.SystemClock.sleep(700)
            }
        }
        assertEquals("选择器取消后应回到应用", context.packageName, automation.rootInActiveWindow?.packageName?.toString())
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("markdown-table-fullscreen").assertIsDisplayed(); true }.getOrDefault(false)
        }
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("markdown-table-fullscreen").assertDoesNotExist()
        compose.onNodeWithTag("markdown-table-card").assertIsDisplayed()
        compose.runOnIdle { ((table.getChildAt(0) as ViewGroup).getChildAt(0)).performLongClick() }
        compose.onNodeWithText(com.clarklevis.dsh.android.localization.appText("选择文字")).performClick()
        compose.onNodeWithTag("markdown-selection-page").assertIsDisplayed()
    }

    @Test fun fullscreenRotationIsIsolatedAndBackAndCloseRestoreChat() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppearanceSettings.preferences(context)
        val originalStyle = preferences.getString("interface_style", null)
        val originalLanguage = preferences.getString("language", null)
        preferences.edit().putString("language", AppLanguage.SIMPLIFIED_CHINESE.name).commit()
        val appearance = AppearanceSettings(preferences).apply { selectInterfaceStyle(InterfaceStyle.DARK) }
        var host: androidx.activity.ComponentActivity? = null
        val source = "| 对比 | 结论 |\n| --- | --- |\n" +
            (1..8).joinToString("\n") { "| 供应链抗压$it | **信号稳定**、系统流畅，支持多设备协同，需确认价格与续航表现。这段较长内容用于检查旋转后列宽会充分利用横屏空间。 |" }
        compose.setContent {
            host = androidx.activity.compose.LocalActivity.current as androidx.activity.ComponentActivity
            DshTheme(appearance) {
                Column(Modifier.verticalScroll(rememberScrollState())) { DshMarkdownText(source) }
            }
        }
        val chat = checkNotNull(host)
        val originalOrientation = chat.requestedOrientation
        fun preview(): DshMarkdownTablePreviewActivity? {
            var result: DshMarkdownTablePreviewActivity? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                result = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<DshMarkdownTablePreviewActivity>().firstOrNull()
            }
            return result
        }
        fun snapshot(name: String, expectedBackground: Int) {
            compose.waitForIdle()
            android.os.SystemClock.sleep(500)
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            context.cacheDir.resolve("$name.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            val gutter = image.getPixel((10 * context.resources.displayMetrics.density).toInt(), image.height / 2)
            assertEquals("独立预览沿用应用深浅模式", expectedBackground, gutter)
            image.recycle()
        }
        var portraitColumnRatio: Double? = null
        var portraitRowHeight = 0
        fun waitForPreview(orientation: Int) {
            compose.waitUntil(15_000) { preview()?.resources?.configuration?.orientation == orientation }
            compose.waitUntil(15_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("markdown-table-fullscreen"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("markdown-table-fullscreen").assertIsDisplayed()
            compose.runOnIdle { assertEquals("预览不能修改聊天 Activity 方向", originalOrientation, chat.requestedOrientation) }
            val titleBounds = compose.onNodeWithTag("document-page-title").fetchSemanticsNode().boundsInRoot
            val pageBounds = compose.onNodeWithTag("markdown-table-fullscreen").fetchSemanticsNode().boundsInRoot
            assertEquals("横竖屏标题必须始终居中", pageBounds.center.x, titleBounds.center.x, 2f)
            androidx.test.espresso.Espresso.onView(org.hamcrest.Matchers.instanceOf(TableLayout::class.java)).check { view, error ->
                if (error != null) throw error
                val grid = view as TableLayout
                val viewport = grid.parent as HorizontalScrollView
                val row = grid.getChildAt(1) as TableRow
                val ratio = row.getChildAt(0).width.toDouble() / row.getChildAt(1).width
                if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    assertEquals("少列表格必须铺满横屏视口", viewport.width, grid.width)
                    assertEquals("扩展时保留各列比例", checkNotNull(portraitColumnRatio), ratio, 0.01)
                    assertTrue("横屏应减少正文换行", row.height < portraitRowHeight)
                } else {
                    portraitColumnRatio = ratio
                    portraitRowHeight = row.height
                }
            }
        }
        try {
            compose.onNodeWithContentDescription(com.clarklevis.dsh.android.localization.appText("全屏浏览表格")).performClick()
            waitForPreview(android.content.res.Configuration.ORIENTATION_PORTRAIT)
            compose.onNodeWithContentDescription(com.clarklevis.dsh.android.localization.appText("旋转屏幕")).performClick()
            waitForPreview(android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            snapshot("table-isolated-dark-landscape", android.graphics.Color.BLACK)
            compose.onNodeWithContentDescription(com.clarklevis.dsh.android.localization.appText("旋转屏幕")).performClick()
            waitForPreview(android.content.res.Configuration.ORIENTATION_PORTRAIT)
            compose.onNodeWithContentDescription(com.clarklevis.dsh.android.localization.appText("旋转屏幕")).performClick()
            waitForPreview(android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            compose.onNodeWithTag("markdown-table-close").performClick()
            compose.waitUntil(15_000) { preview() == null && chat.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            compose.onNodeWithTag("markdown-table-card").assertIsDisplayed()
            assertFalse("聊天页面不能被旋转销毁", chat.isDestroyed)
            assertEquals(originalOrientation, chat.requestedOrientation)
            // 重新进入的预览不能继承上一轮横屏状态，系统返回与关闭按钮行为相同。
            compose.runOnIdle { appearance.selectInterfaceStyle(InterfaceStyle.LIGHT) }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(com.clarklevis.dsh.android.localization.appText("全屏浏览表格")).performClick()
            waitForPreview(android.content.res.Configuration.ORIENTATION_PORTRAIT)
            compose.onNodeWithContentDescription(com.clarklevis.dsh.android.localization.appText("旋转屏幕")).performClick()
            waitForPreview(android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            snapshot("table-isolated-light-landscape", android.graphics.Color.WHITE)
            androidx.test.espresso.Espresso.pressBack()
            compose.waitUntil(15_000) { preview() == null && chat.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            compose.onNodeWithTag("markdown-table-card").assertIsDisplayed()
            assertEquals(originalOrientation, chat.requestedOrientation)
        } finally {
            val remainingPreview = preview()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { remainingPreview?.finish() }
            preferences.edit().apply {
                if (originalStyle == null) remove("interface_style") else putString("interface_style", originalStyle)
                if (originalLanguage == null) remove("language") else putString("language", originalLanguage)
            }.apply()
        }
    }

    @Test fun tableMarkersKeepRichCellsAndRespectCodeFencesAndAdjacentTables() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnIdle {
            val markwon = buildDshMarkwon(context, dshTestPalette(), interactiveTables = true)
            val code = markwon.toMarkdown("```md\n| a | b |\n| --- | --- |\n| x | y |\n```")
            assertTrue(code.getSpans(0, code.length, DshTableMarker::class.java).isEmpty())
            val rendered = markwon.toMarkdown("说明\n| 名称 | 值 |\n| --- | ---: |\n| **粗体** | [链接](https://example.com) |\n\n之后\n\n| a | b |\n| --- | --- |\n| 转义\\|竖线 | `x,y` |")
            val tables = rendered.getSpans(0, rendered.length, DshTableMarker::class.java).sortedBy(rendered::getSpanStart)
            assertEquals(2, tables.size)
            assertEquals(listOf(listOf("名称", "值"), listOf("粗体", "链接")), tables[0].table.plainRows)
            assertEquals("转义|竖线", tables[1].table.plainRows[1][0])
            assertTrue(MarkdownTableExport.csv(tables[1].table.plainRows).contains("\"x,y\""))
            assertTrue(rendered.toString().contains("之后"))
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun dshTestPalette() = DshMarkdownPalette(0xFF10131A.toInt(), 0xFF007AFF.toInt(), 0x3310131A, 0x1410131A,
        0x0910131A, 0x7310131A, 1, 8, 0xFFE9E9ED.toInt(), 6f, 4f, 1f)
}
