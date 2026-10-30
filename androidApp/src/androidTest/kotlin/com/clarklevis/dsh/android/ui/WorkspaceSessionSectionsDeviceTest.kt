package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import java.util.Locale

class WorkspaceSessionSectionsDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule val compose = createComposeRule()
    private var animatorScale: String? = null

    @Test
    fun allWorkspaceNewSessionOnlyShowsChineseReminder() {
        verifyCreationReminder(Locale.SIMPLIFIED_CHINESE, "请先选择工作区",
            "请先选择一个具体工作区，再创建新会话。", "知道了")
    }

    @Test
    fun allWorkspaceNewSessionOnlyShowsEnglishReminder() {
        verifyCreationReminder(Locale.ENGLISH, "Select a workspace first",
            "Select a specific workspace before creating a new session.", "Got it")
    }

    private fun verifyCreationReminder(locale: Locale, title: String, message: String, confirm: String) {
        Locale.setDefault(locale)
        val holder = AndroidSharedStateHolder()
        var creations = 0
        try {
            holder.wirePayload = """{"kind":"workspaces","items":[
                {"workspaceId":"w1","path":"/repo","title":"Project","sessionIds":[],"createdAt":"","updatedAt":""}
            ]}"""
            holder.submitWirePayload()
            holder.selectWorkspace(AndroidSharedStateHolder.ALL_WORKSPACES_ID)
            compose.setContent {
                DshTheme {
                    AdaptiveWindowHost { WorkspaceScreen(holder, {}, { creations++ }, {}, {}, {}) }
                }
            }
            compose.onNodeWithTag("workspace-new-session").performScrollTo().performClick()
            compose.onNodeWithTag("workspace-required-alert").assertIsDisplayed()
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, creations) }
            compose.onNodeWithText(confirm).performClick()
            compose.onNodeWithTag("workspace-required-alert").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(0, creations)
                holder.selectWorkspace("w1")
            }
            compose.onNodeWithTag("workspace-new-session").performScrollTo().performClick()
            compose.onNodeWithTag("workspace-required-alert").assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, creations) }
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Before
    fun pauseBackgroundAnimation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        animatorScale = Settings.Global.getString(context.contentResolver, "animator_duration_scale")
        shell("settings put global animator_duration_scale 0")
    }

    @After
    fun restoreBackgroundAnimation() {
        shell(animatorScale?.let { "settings put global animator_duration_scale $it" }
            ?: "settings delete global animator_duration_scale")
    }

    @Test
    fun sectionsExpandIndependentlyAndSingleWorkspaceStillShowsMoreThanFiveRows() {
        val holder = AndroidSharedStateHolder()
        try {
            val rows = (0..<13).joinToString(",") { id ->
                """{"sessionId":"s$id","updatedAt":${1_800_000_000 - id},"running":false,"blank":false,
                    "projections":{"values":{"title":"任务 $id"}}}"""
            }
            holder.wirePayload = """{"kind":"sessions","items":[$rows]}"""
            holder.submitWirePayload()
            holder.wirePayload = """{"kind":"workspaces","items":[
                {"workspaceId":"w1","path":"/secret-one","title":"移动端工程","sessionIds":["s0","s1","s2","s3","s4","s5"],"createdAt":"","updatedAt":""},
                {"workspaceId":"w2","path":"/secret-two","title":"网关服务","sessionIds":["s6","s7","s8","s9","s10","s11","s12"],"createdAt":"","updatedAt":""}
            ]}"""
            holder.submitWirePayload()
            holder.selectWorkspace(AndroidSharedStateHolder.ALL_WORKSPACES_ID)
            compose.setContent {
                DshTheme {
                    AdaptiveWindowHost {
                        WorkspaceScreen(holder, {}, {}, {}, {}, {})
                    }
                }
            }
            val list = compose.onNodeWithTag("workspace-session-list")
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("workspace-section-w1").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("workspace-section-w1").assertTextContains("移动端工程")
            list.performScrollToNode(hasTestTag("workspace-section-toggle-w1"))
            compose.onNodeWithTag("workspace-session-s5").assertDoesNotExist()
            compose.onNodeWithTag("workspace-section-toggle-w1").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasTestTag("workspace-section-toggle-w1") and hasText("收起")).fetchSemanticsNodes().isNotEmpty()
            }
            list.performScrollToNode(hasTestTag("workspace-session-s5"))
            compose.onNodeWithTag("workspace-session-s5").assertIsDisplayed()
            assertFalse(compose.onNodeWithTag("workspace-session-s5").fetchSemanticsNode()
                .config[SemanticsProperties.Text].any { it.text.contains("移动端工程") })
            list.performScrollToNode(hasTestTag("workspace-section-toggle-w2"))
            compose.onNodeWithTag("workspace-section-toggle-w2").assertTextContains("展开其余 2 个会话")

            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.getExternalFilesDir(null), "workspace-sessions-collapsed.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()

            list.performScrollToNode(hasTestTag("workspace-section-toggle-w1"))
            compose.onNodeWithTag("workspace-section-toggle-w1").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasTestTag("workspace-section-toggle-w1") and hasText("展开其余 1 个会话"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("workspace-session-s5").assertDoesNotExist()
            compose.runOnIdle { holder.selectWorkspace("w1") }
            list.performScrollToIndex(0)
            compose.onNodeWithTag("workspace-session-s5").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("workspace-section-toggle-w1").assertDoesNotExist()
        } finally {
            compose.runOnIdle { holder.close() }
        }
    }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
