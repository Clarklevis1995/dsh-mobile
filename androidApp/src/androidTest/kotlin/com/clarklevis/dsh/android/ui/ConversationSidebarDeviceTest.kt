package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ConversationSidebarDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule val compose = createComposeRule()
    private var animatorScale: String? = null

    @Before fun pauseBackgroundAnimation() {
        animatorScale = Settings.Global.getString(InstrumentationRegistry.getInstrumentation().targetContext.contentResolver, "animator_duration_scale")
        shell("settings put global animator_duration_scale 0")
    }

    @After fun restoreBackgroundAnimation() {
        shell(animatorScale?.let { "settings put global animator_duration_scale $it" } ?: "settings delete global animator_duration_scale")
    }

    @Test
    fun floatingSidebarSwitchesSessionsClosesOnBackAndKeepsContentWidth() {
        var selected by mutableStateOf("one")
        var destination = ""
        val sessions = listOf("one", "two").map { SessionSummary(it, it, 1.0, false, false) }
        compose.setContent {
            DshTheme {
                DshMenuHost {
                    Box(Modifier.fillMaxSize().testTag("sidebar-page")) {
                        Column(Modifier.padding(24.dp)) {
                            ConversationSidebarButton(
                                listOf(GatewayWorkspace("workspace", "/project", "project", listOf("one", "two"), "", "")),
                                sessions, selected, {}, { selected = it },
                                { destination = "plugins" }, { destination = "tasks" }, { destination = "settings" }
                            )
                        }
                        Text("正文", Modifier.align(Alignment.Center).testTag("sidebar-content"))
                    }
                }
            }
        }
        val original = compose.onNodeWithTag("sidebar-content").fetchSemanticsNode().boundsInRoot
        fun open() { compose.onNodeWithTag("conversation-sidebar-toggle").performClick() }
        open()
        compose.onNodeWithTag("conversation-sidebar").assertIsDisplayed()
        captureSidebar()
        compose.onNodeWithTag("conversation-sidebar-session-one").assertIsSelected()
        assertEquals(original, compose.onNodeWithTag("sidebar-content").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("conversation-sidebar-session-two").performClick()
        compose.onNodeWithTag("conversation-sidebar").assertDoesNotExist()
        compose.runOnIdle { assertEquals("two", selected) }
        open()
        compose.onNodeWithTag("conversation-sidebar-session-two").assertIsSelected()
        pressBack()
        compose.onNodeWithTag("conversation-sidebar").assertDoesNotExist()
        for ((tag, expected) in listOf("plugins" to "plugins", "scheduled-tasks" to "tasks", "settings" to "settings")) {
            open()
            compose.onNodeWithTag("conversation-sidebar-$tag").performClick()
            compose.onNodeWithTag("conversation-sidebar").assertDoesNotExist()
            compose.runOnIdle { assertEquals(expected, destination) }
        }
    }

    @Test
    fun toolbarEntryIsWiredToAppNavigation() {
        val holder = AndroidSharedStateHolder().apply { loadFixture() }
        val sessionId = holder.snapshot.sessions.first().id
        try {
            compose.setContent {
                DshTheme {
                    DshProductApp(holder, {}, {}, null, {})
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("workspace-session-$sessionId").performScrollTo().performClick()
            compose.onNodeWithTag("conversation-sidebar-floating").assertDoesNotExist()
            compose.onNodeWithTag("conversation-sidebar-toggle").assertIsDisplayed().performClick()
            compose.onNodeWithTag("conversation-sidebar-plugins").performClick()
            compose.onNodeWithText("插件").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("conversation-sidebar-toggle").assertIsDisplayed().performClick()
            compose.onNodeWithTag("conversation-sidebar-settings").performClick()
            compose.onNodeWithText("设置").assertIsDisplayed()
            compose.runOnIdle { assertEquals(sessionId, holder.snapshot.selectedSessionId) }
        } finally {
            compose.runOnIdle { holder.close() }
        }
    }

    @Test
    fun adaptiveToolbarEntryPreservesViewportAndDraft() {
        val holder = AndroidSharedStateHolder().apply {
            loadFixture()
            wirePayload = """{"kind":"question-resolved","rpcId":"android-question"}"""
            submitWirePayload()
            messageDraft = "侧边栏关闭后保留草稿"
        }
        try {
            compose.setContent {
                DshTheme {
                    ConversationScreen(holder, onPickImage = {}, onBack = {})
                }
            }
            compose.waitForIdle()
            val trigger = compose.onNodeWithTag("conversation-sidebar-toggle")
            val viewport = compose.onNodeWithTag("conversation-pager")
            val original = viewport.fetchSemanticsNode().boundsInRoot
            assertStaticTitle()
            val button = trigger.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertHeaderLayout(wide = original.width >= with(compose.density) { 600.dp.toPx() })
            compose.onNodeWithTag("conversation-sidebar-floating").assertDoesNotExist()
            assertSidebarButtonPosition(wide = original.width >= with(compose.density) { 600.dp.toPx() })
            captureSidebar("conversation-sidebar-toolbar.png")
            assertTrue(button.width >= with(compose.density) { 44.dp.toPx() } - 1)
            trigger.performClick()
            val panel = compose.onNodeWithTag("conversation-sidebar").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("侧边栏应保持在屏幕内", panel.width <= original.width)
            assertEquals(original, viewport.fetchSemanticsNode().boundsInRoot)
            captureSidebar("conversation-sidebar-phone-open.png")
            pressBack()
            compose.onNodeWithTag("conversation-sidebar").assertDoesNotExist()
            trigger.assertIsDisplayed().performClick()
            compose.onNodeWithTag("conversation-sidebar-session-${holder.snapshot.selectedSessionId}").performClick()
            compose.onNodeWithTag("conversation-sidebar").assertDoesNotExist()
            trigger.performClick()
            pressBack()
            compose.onNodeWithTag("conversation-sidebar").assertDoesNotExist()
            compose.runOnIdle { assertEquals("侧边栏关闭后保留草稿", holder.messageDraft) }
            assertEquals(original, viewport.fetchSemanticsNode().boundsInRoot)
            captureSidebar("conversation-sidebar-phone-closed.png")
        } finally {
            compose.runOnIdle { holder.close() }
        }
    }

    @Test
    fun independentSidebarButtonWorksAcrossFoldingAndMultiWindow() {
        val holder = AndroidSharedStateHolder().apply {
            loadFixture()
            messageDraft = "折叠切换保留草稿"
        }
        var fullWidthDp by mutableStateOf(390)
        try {
            compose.setContent {
                val actualWindowInfo = LocalWindowInfo.current
                val density = LocalDensity.current
                val reportedSize = with(density) { IntSize(fullWidthDp.dp.roundToPx(), 820.dp.roundToPx()) }
                val windowInfo = object : WindowInfo by actualWindowInfo {
                    override val containerSize = reportedSize
                }
                CompositionLocalProvider(LocalWindowInfo provides windowInfo) {
                    DshTheme {
                        Box(Modifier.width(390.dp).fillMaxSize()) {
                            ConversationScreen(holder, onPickImage = {}, onBack = {})
                        }
                    }
                }
            }
            for (width in listOf(390, 840, 1280, 600, 599, 390)) {
                compose.runOnIdle { fullWidthDp = width }
                assertStaticTitle()
                assertHeaderLayout(wide = width >= 600)
                assertSidebarButtonPosition(wide = width >= 600)
                compose.onNodeWithTag("conversation-sidebar-toggle").assertIsDisplayed().performClick()
                compose.onNodeWithTag("conversation-sidebar").assertIsDisplayed()
                pressBack()
                compose.onNodeWithTag("conversation-sidebar").assertDoesNotExist()
            }
            compose.runOnIdle { assertEquals("折叠切换保留草稿", holder.messageDraft) }
        } finally {
            compose.runOnIdle { holder.close() }
        }
    }

    private fun assertSidebarButtonPosition(wide: Boolean) {
        val button = compose.onNodeWithTag("conversation-sidebar-toggle").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("conversation-page-title").fetchSemanticsNode().boundsInRoot
        val more = compose.onNodeWithContentDescription("更多").fetchSemanticsNode().boundsInRoot
        if (wide) {
            val back = compose.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInRoot
            assertTrue("大屏侧栏按钮应保留在返回按钮旁", button.left >= back.right && button.right <= title.left)
            assertEquals(back.center.y, button.center.y, 1f)
        } else {
            assertTrue("窄屏侧栏按钮应在标题右侧、更多按钮左侧", button.left >= title.right && button.right <= more.left)
            assertEquals(more.center.y, button.center.y, 1f)
        }
    }

    private fun assertHeaderLayout(wide: Boolean) {
        val titleNode = compose.onNodeWithTag("conversation-title-text", useUnmergedTree = true)
        val modeNode = compose.onNodeWithTag("conversation-mode-label", useUnmergedTree = true).assertIsDisplayed()
        val title = titleNode.fetchSemanticsNode().boundsInRoot
        val mode = modeNode.fetchSemanticsNode().boundsInRoot
        if (wide) {
            assertTrue("大屏模式应保留在标题右侧", mode.left >= title.right)
            assertEquals("大屏模式应与标题同一行", title.center.y, mode.center.y, with(compose.density) { 3.dp.toPx() })
        } else {
            assertTrue("窄屏模式应位于标题下方", mode.top >= title.bottom)
        }
        val titleLayouts = mutableListOf<TextLayoutResult>()
        val modeLayouts = mutableListOf<TextLayoutResult>()
        titleNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        modeNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(modeLayouts) }
        assertEquals(if (wide) 17.sp else 16.sp, titleLayouts.single().layoutInput.style.fontSize)
        assertEquals(if (wide) 12.sp else 11.sp, modeLayouts.single().layoutInput.style.fontSize)
    }

    private fun assertStaticTitle() {
        val title = compose.onNodeWithTag("conversation-page-title").assertIsDisplayed()
        title.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithTag("conversation-title-chevron", useUnmergedTree = true).assertDoesNotExist()
        title.performTouchInput { click() }
        compose.onNodeWithTag("conversation-sidebar").assertDoesNotExist()
    }

    private fun captureSidebar(fileName: String = "conversation-sidebar-tablet.png") {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        instrumentation.targetContext.cacheDir.resolve(fileName).outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
