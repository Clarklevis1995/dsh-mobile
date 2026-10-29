package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class AdaptiveLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var previousLocale: Locale

    @Before fun useChineseLabels() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
    }

    @After fun restoreLocale() { Locale.setDefault(previousLocale) }

    @Test
    fun contentIsCenteredAndCappedWhileBackgroundRemainsFullWidth() {
        var narrow by mutableStateOf(false)
        compose.setContent {
            DshTheme {
                Box(Modifier.fillMaxSize().testTag("full-window")) {
                    Box(
                        Modifier.then(if (narrow) Modifier.width(360.dp) else Modifier.fillMaxWidth())
                            .height(400.dp).testTag("viewport")
                    ) {
                        Box(Modifier.adaptiveContentWidth().height(100.dp).testTag("reading-column"))
                    }
                }
            }
        }
        fun check() {
            val viewport = bounds("viewport")
            val content = bounds("reading-column")
            assertEquals(minOf(viewport.width, px(1040f)), content.width, 2f)
            assertEquals(viewport.center.x, content.center.x, 2f)
        }
        check()
        compose.runOnIdle { narrow = true }
        check()
        assertEquals(px(360f).coerceAtMost(bounds("full-window").width), bounds("reading-column").width, 2f)
    }

    @Test
    fun workspaceReflowsWithoutLosingItsContentAsWindowShrinksAndExpands() {
        var narrow by mutableStateOf(false)
        var short by mutableStateOf(false)
        var initialUsesColumns = false
        compose.setContent {
            DshTheme {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val width = if (narrow) minOf(maxWidth, 360.dp) else maxWidth
                    val height = if (short) 400.dp else maxHeight
                    initialUsesColumns = AdaptiveLayout.workspaceUsesColumns(maxWidth.value, maxHeight.value)
                    Box(Modifier.size(width, height)) {
                        AdaptiveWorkspaceContent(
                            window = DpSize(width, height),
                            header = { Text("Header") },
                            actions = { Text("工作区与新建会话") },
                            recentSessions = { Text("最近会话与搜索") }
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        if (initialUsesColumns) {
            assertTrue(bounds("workspace-actions").right < bounds("workspace-recents").left)
        }
        compose.runOnIdle { narrow = true }
        compose.onNodeWithTag("workspace-single-column").assertIsDisplayed()
        assertTrue(bounds("workspace-actions").bottom < bounds("workspace-recents").top)
        compose.runOnIdle { narrow = false; short = true }
        compose.onNodeWithTag("workspace-single-column").assertIsDisplayed()
        compose.runOnIdle { short = false }
        compose.onNodeWithTag(if (initialUsesColumns) "workspace-columns" else "workspace-single-column")
            .assertIsDisplayed()
    }

    @Test
    fun conversationKeepsMatchingReadingAndComposerWidthsAcrossWindowChanges() {
        val holder = AndroidSharedStateHolder().apply {
            loadFixture()
            wirePayload = """{"kind":"question-resolved","rpcId":"android-question"}"""
            submitWirePayload()
        }
        var narrow by mutableStateOf(false)
        compose.setContent {
            DshTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.then(if (narrow) Modifier.width(360.dp) else Modifier.fillMaxWidth()).fillMaxSize()) {
                        ConversationScreen(holder, onPickImage = {}, onBack = {})
                    }
                }
            }
        }
        try {
            fun check() {
                val list = bounds("conversation-message-list")
                val composer = bounds("conversation-composer-column")
                assertEquals(list.center.x, composer.center.x, 2f)
                assertEquals(list.width, composer.width, 2f)
                assertTrue(list.width <= px(1040f) + 2f)
                compose.onNodeWithTag("conversation-tabs").assertDoesNotExist()
                compose.onNodeWithTag("conversation-page-title").assertIsDisplayed()
                compose.onNodeWithTag("composer-input").assertIsDisplayed()
            }
            check()
            compose.runOnIdle { narrow = true }
            check()
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test
    fun settingsPluginDetailsAndTrajectoryRespectSharedReadingWidth() {
        val holder = AndroidSharedStateHolder().apply { loadFixture() }
        var page by mutableStateOf(0)
        compose.setContent {
            DshTheme {
                when (page) {
                    0 -> SettingsScreen(holder, {}, {}, {}, {})
                    1 -> AgentPresetSelectionScreen(holder, {})
                    2 -> DefaultModelSelectionScreen(holder, {})
                    3 -> PluginsScreen(holder, {}, {}, {})
                    4 -> PluginBundleDetailScreen(holder, "missing", {})
                    5 -> OfficialPluginDetailScreen(holder, "missing", {})
                    6 -> TrajectoryPage(nodes = holder.trajectoryNodes, isActive = true)
                    else -> FontSizeSettingsScreen {}
                }
            }
        }
        try {
            val tags = listOf("settings-content", "settings-selection-content", "settings-selection-content",
                "plugin-content", "plugin-content", "plugin-content", "trajectory-page", "font-size-content")
            for ((index, tag) in tags.withIndex()) {
                compose.runOnIdle { page = index }
                compose.onNodeWithTag(tag).assertIsDisplayed()
                assertTrue("第 $index 页未限宽", bounds(tag).width <= px(1040f) + 2f)
            }
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test
    fun fontControlsRemainReachableInLowHeightSplitScreen() {
        compose.setContent {
            DshTheme {
                Box(Modifier.fillMaxWidth().height(360.dp)) { FontSizeSettingsScreen {} }
            }
        }
        compose.onNodeWithTag("font-size-value").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("font-size-reset").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun scheduledTaskSearchLeavesBackButtonUsableInNarrowFoldedWindow() {
        val holder = AndroidSharedStateHolder()
        compose.setContent {
            DshTheme {
                Box(Modifier.width(280.dp).fillMaxSize()) { ScheduledTasksScreen(holder, {}, {}) }
            }
        }
        try {
            compose.onNodeWithContentDescription("搜索定时任务").performClick()
            compose.onNodeWithContentDescription("关闭搜索").assertIsDisplayed()
            assertTrue(bounds("scheduled-search-controls").left >= bounds("scheduled-back").right)
            compose.onNodeWithTag("scheduled-back").assertIsDisplayed()
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test
    fun directorySheetFitsTabletAndPhoneWithoutStretchingAcrossWindow() {
        val holder = AndroidSharedStateHolder()
        compose.setContent {
            DshTheme { AdaptiveWindowHost { WorkspaceDirectoryBrowserSheet(holder, {}) } }
        }
        try {
            compose.onNodeWithTag("directory-browser-sheet").assertIsDisplayed()
            assertTrue(bounds("directory-browser-sheet").width <= px(720f) + 2f)
        } finally { compose.runOnIdle { holder.close() } }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun px(dp: Float): Float = dp * compose.density.density
}
