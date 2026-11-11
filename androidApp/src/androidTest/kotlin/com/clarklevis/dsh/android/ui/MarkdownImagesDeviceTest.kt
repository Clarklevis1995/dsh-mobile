package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.text.Spanned
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.platform.MarkdownImageAsset
import com.clarklevis.dsh.android.AndroidWorkspaceLocalFile
import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MarkdownImagesDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule val compose = createComposeRule()
    private val source = "images/lake.png"
    private fun asset(): MarkdownImageAsset {
        val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.MAGENTA) }
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return MarkdownImageAsset(bitmap.asImageBitmap(), bytes, 800, 400, "image/png")
    }
    @Test fun assistantBodyImageKeepsDimensionsWhileLoadingAndOpensPreview() {
        val gate = CompletableDeferred<MarkdownImageAsset>()
        val image = asset()
        val reference = GatewayImageAttachment("generated", "image/png", image.bytes.size, 800, 400, "lake.png")
        var loads = 0
        val context = DshMarkdownImageContext("gateway:session", mapOf(source to reference), cached = { null }, load = { loads++; gate.await() })
        compose.setContent {
            DshTheme {
                CompositionLocalProvider(LocalDshMarkdownImages provides context) {
                    Box(Modifier.width(320.dp).background(Color.White)) {
                        DshLazyMarkdownText("已为你画好。\n\n![瘦西湖春景]($source)\n\n完成。")
                    }
                }
            }
        }
        val node = compose.onNodeWithTag("markdown-image:$source")
        val before = node.fetchSemanticsNode().boundsInRoot
        assertEquals(before.width / 2, before.height, 1f)
        compose.onNodeWithText("正在加载图片…").assertIsDisplayed()
        compose.runOnIdle { gate.complete(image) }
        compose.onNodeWithContentDescription("瘦西湖春景").assertIsDisplayed()
        val after = node.fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        val pixels = node.captureToImage().toPixelMap()
        assertTrue(pixels[pixels.width / 2, pixels.height / 2].red > 0.9f)
        assertTrue("圆角之外不能绘制图片", pixels[0, 0].green > 0.8f)
        assertEquals(1, loads)
        node.performClick()
        compose.onNodeWithTag("conversation-image-preview").assertIsDisplayed()
    }
    @Test fun failedImageRetryAndLazyRemountKeepTheSameSlot() {
        val image = asset()
        var attempts = 0
        var cached: MarkdownImageAsset? = null
        val context = DshMarkdownImageContext("gateway:session", mapOf(source to GatewayImageAttachment("i", "image/png", 20, 800, 400)),
            cached = { cached }, load = {
                attempts++
                if (attempts == 1) error("offline")
                image.also { cached = it }
            })
        var mounted by mutableStateOf(true)
        compose.setContent {
            DshTheme {
                CompositionLocalProvider(LocalDshMarkdownImages provides context) {
                    Box(Modifier.width(320.dp)) { if (mounted) DshLazyMarkdownText("![湖]($source)") }
                }
            }
        }
        compose.onNodeWithText("加载失败 · 点击重试").assertIsDisplayed()
        val before = compose.onNodeWithTag("markdown-image:$source").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("markdown-image:$source").performClick()
        compose.onNodeWithContentDescription("湖").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("markdown-image:$source").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { mounted = false }
        compose.waitForIdle()
        compose.runOnIdle { mounted = true }
        compose.onNodeWithContentDescription("湖").assertIsDisplayed()
        assertEquals(2, attempts)
        assertEquals(before, compose.onNodeWithTag("markdown-image:$source").fetchSemanticsNode().boundsInRoot)
    }
    @Test fun markdownAstRecognizesReferenceImagesButNotCodeLiterals() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { DshTheme { DshLazyMarkdownText("text") } }
        compose.runOnIdle {
            val palette = dshTestPalette()
            val renderer = buildDshMarkwon(context, palette, interactiveTables = true)
            val markdown = "`![literal](no.png)`\n\n```md\n![fenced](also-no.png)\n```\n\n![湖][lake]\n\n[lake]: images/lake.png"
            val rendered = renderer.toMarkdown(markdown) as Spanned
            val markers = rendered.getSpans(0, rendered.length, DshMarkdownImageMarker::class.java)
            assertEquals(1, markers.size)
            assertEquals(source, markers.single().source)
        }
    }

    @Test fun tableImageKeepsRowHeightAndOpensOriginalPreview() {
        val gate = CompletableDeferred<MarkdownImageAsset>()
        val image = asset()
        val context = DshMarkdownImageContext("table", mapOf(source to GatewayImageAttachment("i", "image/png", 20, 800, 400)),
            cached = { null }, load = { gate.await() })
        compose.setContent {
            DshTheme {
                DshMenuHost {
                    CompositionLocalProvider(LocalDshMarkdownImages provides context) {
                        Box(Modifier.width(360.dp)) {
                            DshMarkdownText("| 图片 | 说明 |\n| --- | --- |\n| 前文 ![表格图]($source) 后文 | 图片说明 |")
                        }
                    }
                }
            }
        }
        val grid = compose.onNodeWithTag("markdown-table-grid")
        val before = grid.fetchSemanticsNode().boundsInRoot
        val slot = compose.onNodeWithTag("markdown-image:$source")
        val imageBefore = slot.fetchSemanticsNode().boundsInRoot
        assertEquals(imageBefore.width / 2, imageBefore.height, 1f)
        compose.runOnIdle { gate.complete(image) }
        compose.onNodeWithContentDescription("表格图").assertIsDisplayed()
        assertEquals(before, grid.fetchSemanticsNode().boundsInRoot)
        assertEquals(imageBefore, slot.fetchSemanticsNode().boundsInRoot)
        slot.performClick()
        compose.onNodeWithTag("conversation-image-preview").assertIsDisplayed()
    }

    @Test fun markdownFilePreviewResolvesBodyAndHtmlTableImagesFromRemoteDirectory() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File.createTempFile("markdown-images-", ".md", app.cacheDir)
        val image = asset()
        val loaded = mutableSetOf<String>()
        val context = DshMarkdownImageContext("file", emptyMap(), cached = { null }, load = {
            loaded += it
            image
        })
        file.writeText("![正文图](../screenshots/body.png)\n\n| 图 |\n| --- |\n| <IMG ALT='表格图' SRC='../../shared/table.png' /> |")
        try {
            val local = AndroidWorkspaceLocalFile(file, "s", "docs/guides/README.md", "README.md", "text/markdown", "preview")
            compose.setContent {
                DshTheme {
                    CompositionLocalProvider(LocalDshMarkdownImages provides context) {
                        WorkspaceCodePreviewSheet(local, {}, {})
                    }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("workspace-markdown-preview-tab").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("workspace-markdown-preview-tab").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("表格图").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("正文图").assertExists()
            compose.runOnIdle { assertEquals(setOf("docs/screenshots/body.png", "shared/table.png"), loaded) }
        } finally { file.delete() }
    }

    @Test fun htmlImageEntitiesAreDecodedAndCodeSamplesRemainText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { DshTheme { DshMarkdownText("text") } }
        compose.runOnIdle {
            val renderer = buildDshMarkwon(context, dshTestPalette(), interactiveTables = true)
            val markdown = "| HTML | Markdown | 代码 |\n| --- | --- | --- |\n" +
                "| <IMG ALT='今日&#x622a;图' SRC='https://example.com/today.png?a=1&amp;b=2' /> | ![湖]($source) | `<img src='no.png'>` |"
            val rendered = renderer.toMarkdown(markdown) as Spanned
            val table = rendered.getSpans(0, rendered.length, DshTableMarker::class.java).single().table
            assertTrue(table.rows[1][0].hasImages)
            assertTrue(table.rows[1][1].hasImages)
            assertFalse(table.rows[1][2].hasImages)
            val cell = table.rows[1][0]
            val marker = cell.text.getSpans(0, cell.text.length, DshMarkdownImageMarker::class.java).single()
            assertEquals("https://example.com/today.png?a=1&b=2", marker.source)
            assertEquals("今日截图", marker.alt)
            assertEquals("今日截图", cell.plainText)
            assertEquals(0, rendered.getSpans(0, rendered.length, DshMarkdownImageMarker::class.java).size)
        }
    }

    @Test fun documentImagePathsKeepAbsoluteAndRemoteSourcesAndDecodePercentOnce() {
        assertEquals("docs/screenshots/a.png", resolveMarkdownImageSource("../screenshots/a.png", "docs/guides/README.md"))
        assertEquals("/workspace/docs/a.png", resolveMarkdownImageSource("./a.png", "/workspace/docs/README.md"))
        assertEquals("/workspace/shared/a.png", resolveMarkdownImageSource("/workspace/shared/a.png", "docs/README.md"))
        assertEquals("https://example.com/a.png?q=1", resolveMarkdownImageSource("https://example.com/a.png?q=1", "docs/README.md"))
        val escaped = resolveMarkdownImageSource("./a%2520.png", "docs/README.md")
        assertEquals("docs/a%20.png", com.clarklevis.dsh.shared.facade.ConversationFileReferences.filePath(escaped))
        assertTrue(runCatching { resolveMarkdownImageSource("../outside.png", "README.md") }.isFailure)
        assertTrue(runCatching { resolveMarkdownImageSource("ftp://example.com/a.png", "README.md") }.isFailure)
    }

    @Test fun unknownDimensionsKeepSlotStableAndRoundActualImageCorners() {
        val image = asset()
        val context = DshMarkdownImageContext("unknown", emptyMap(), cached = { image }, load = { error("already cached") })
        compose.setContent {
            DshTheme {
                CompositionLocalProvider(LocalDshMarkdownImages provides context) {
                    Box(Modifier.width(320.dp).background(Color.White)) { DshMarkdownText("![湖]($source)") }
                }
            }
        }
        val node = compose.onNodeWithTag("markdown-image:$source")
        val pixels = node.captureToImage().toPixelMap()
        assertEquals(pixels.width, pixels.height)
        assertTrue(pixels[pixels.width / 2, pixels.height / 2].red > 0.9f)
        assertTrue("留白内的图片角也必须裁成圆角", pixels[0, pixels.height / 4 + 1].green > 0.8f)
    }
    private fun dshTestPalette() = DshMarkdownPalette(0xff111111.toInt(), 0xff0066ff.toInt(), 0xff999999.toInt(),
        0xffeeeeee.toInt(), 0xfffafafa.toInt(), 0xff888888.toInt(), 1, 8, 0xffeeeeee.toInt(), 6f, 4f, 1f)
}
