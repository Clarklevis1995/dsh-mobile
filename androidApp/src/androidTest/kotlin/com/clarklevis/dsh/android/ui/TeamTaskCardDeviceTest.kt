package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.DshAndroidApplication
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.facade.TeamMemberView
import com.clarklevis.dsh.shared.facade.TeamTaskView
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test

class TeamTaskCardDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun liveGatewayTeamModeSavesAndConfirmsDisable() {
        val session = InstrumentationRegistry.getArguments().getString("rc3SessionId")
        assumeNotNull(session)
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DshAndroidApplication
        compose.waitUntil(20_000) { application.hosts.ready && application.hosts.activeProfile != null }
        lateinit var holder: AndroidSharedStateHolder
        compose.runOnIdle {
            holder = application.hosts.activeGraph.stateHolder
            holder.connect()
        }
        compose.waitUntil(20_000) { holder.gatewayState.connection == GatewayConnectionState.CONNECTED }
        val previous = holder.snapshot.selectedSessionId
        var original: Boolean? = null
        try {
            compose.runOnIdle { holder.selectSession(requireNotNull(session)) }
            compose.waitUntil(20_000) { holder.snapshot.selectedSessionId == session && holder.teamMode.canChange }
            original = holder.teamMode.enabled
            val tasks = holder.teamSnapshot.tasks.map { it.id }.toSet()
            if (original == true) {
                compose.runOnIdle { holder.setTeamModeEnabled(false, confirmed = true) }
                compose.waitUntil(20_000) { holder.teamMode.enabled == false && !holder.teamMode.pending }
            }
            compose.setContent { DshTheme { DshMenuHost {
                androidx.compose.foundation.layout.Column(Modifier.width(360.dp)) {
                    ConversationMoreMenu(true, {}, true, {}, holder.teamMode, { holder.setTeamModeEnabled(it) })
                    ModelControl(holder)
                }
                TeamModeDisableConfirmation(holder)
            } } }
            compose.onNodeWithTag("conversation-team-mode-toggle").assertDoesNotExist()
            compose.onNodeWithTag("conversation-more-menu-toggle").performClick()
            compose.onNodeWithTag("conversation-team-mode-info").assertDoesNotExist()
            compose.onNodeWithTag("conversation-team-mode-toggle").performClick()
            compose.waitUntil(20_000) { holder.teamMode.enabled == true && !holder.teamMode.pending }
            assertEquals(tasks, holder.teamSnapshot.tasks.map { it.id }.toSet())
            compose.onNodeWithTag("conversation-more-menu-toggle").performClick()
            saveTeamModeColor("on")
            compose.onNodeWithTag("conversation-team-mode-info").assertDoesNotExist()
            compose.onNodeWithTag("conversation-team-mode-toggle").performClick()
            compose.onNodeWithText(appText("确认停用智能体团队协作？")).assertIsDisplayed()
            assertEquals(true, holder.teamMode.enabled)
            compose.onNodeWithText(appText("取消")).performClick()
            assertEquals(true, holder.teamMode.enabled)
            compose.onNodeWithTag("conversation-more-menu-toggle").performClick()
            compose.onNodeWithTag("conversation-team-mode-info").assertDoesNotExist()
            compose.onNodeWithTag("conversation-team-mode-toggle").performClick()
            compose.onNodeWithText(appText("确认停用")).performClick()
            compose.waitUntil(20_000) { holder.teamMode.enabled == false && !holder.teamMode.pending }
            assertEquals(tasks, holder.teamSnapshot.tasks.map { it.id }.toSet())
            compose.onNodeWithTag("conversation-more-menu-toggle").performClick()
            saveTeamModeColor("off")
            compose.onNodeWithText(appText("工作区文件")).performClick()
        } finally {
            if (original != null) {
                compose.runOnIdle { if (holder.teamMode.enabled != original) holder.setTeamModeEnabled(original, confirmed = true) }
                compose.waitUntil(20_000) { holder.teamMode.enabled == original && !holder.teamMode.pending }
            }
            compose.runOnIdle { previous?.let(holder::selectSession) }
        }
    }

    private fun saveTeamModeColor(state: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "team-mode-color-$state.png").outputStream().use {
            compose.onNodeWithTag("conversation-more-menu").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun teamModeMenuFitsNormalAndLargeFonts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("team-more-menu-visual-test", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val appearance = AppearanceSettings(preferences)
        compose.setContent {
            DshTheme(appearance) { DshMenuHost {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                        ConversationMoreMenu(true, {}, true, {},
                            com.clarklevis.dsh.shared.facade.TeamModeSnapshot(
                                supported = true, enabled = true, canChange = true), {})
                    }
                }
            } }
        }
        var normalHeight = 0
        for (fontScale in listOf(1f, 1.5f)) {
            compose.runOnIdle { appearance.selectFontScale(fontScale) }
            compose.onNodeWithTag("conversation-more-menu-toggle").performClick()
            compose.onNodeWithText(appText("工作区文件")).assertIsDisplayed()
            compose.onNodeWithText(appText("会话状态")).assertIsDisplayed()
            compose.onNodeWithText(appText("启用智能体团队协作")).assertIsDisplayed()
            compose.onNodeWithTag("conversation-team-mode-info").assertDoesNotExist()
            val bitmap = compose.onNodeWithTag("conversation-more-menu").captureToImage().asAndroidBitmap()
            if (fontScale == 1f) normalHeight = bitmap.height else assertTrue(bitmap.height > normalHeight)
            File(context.getExternalFilesDir(null), "team-more-menu-$fontScale.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("conversation-team-mode-toggle").performClick()
            compose.waitForIdle()
        }
    }

    @Test
    fun teamModeMenuHidesForOldGatewayAndDisablesPendingWrite() {
        val mode = androidx.compose.runtime.mutableStateOf(com.clarklevis.dsh.shared.facade.TeamModeSnapshot())
        var writes = 0
        compose.setContent { DshTheme { DshMenuHost { androidx.compose.foundation.layout.Column(Modifier.width(310.dp)) {
            TeamModeMenuItems(mode.value, { writes++ })
        } } } }
        compose.onNodeWithTag("conversation-team-mode-toggle").assertDoesNotExist()
        compose.runOnIdle { mode.value = mode.value.copy(supported = true, enabled = false, canChange = true) }
        compose.onNodeWithTag("conversation-team-mode-toggle").performClick()
        compose.runOnIdle { assertEquals(1, writes); mode.value = mode.value.copy(pending = true, canChange = false) }
        compose.onNodeWithText(appText("正在保存团队设置…")).assertIsDisplayed()
        compose.onNodeWithTag("conversation-team-mode-toggle").performClick()
        compose.runOnIdle { assertEquals(1, writes) }
    }

    private val task = TeamTaskView(
        id = "task-1", revision = 3, subject = "素材分析", description = "任务说明",
        status = "completed", ownerName = "scout", writeScopes = listOf("analysis")
    )
    private val owner = TeamMemberView(id = "scout-session", name = "scout", avatarIndex = 2)

    @Test
    fun cardAndDisclosureBothToggleDetailsWithoutOpeningConversation() {
        val selected = mutableListOf<String>()
        compose.setContent {
            DshTheme { TeamTaskCard(task, owner, emptyList()) { selected += it.id } }
        }
        compose.onNodeWithText(appText("版本：%1\$s", 3)).assertDoesNotExist()
        compose.onNodeWithTag("team-task-toggle-task-1").performClick()
        compose.onNodeWithText(appText("版本：%1\$s", 3)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<String>(), selected) }
        compose.onNodeWithText("素材分析", useUnmergedTree = true).performClick()
        compose.onNodeWithText(appText("版本：%1\$s", 3)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), selected) }
        compose.onNodeWithText("素材分析", useUnmergedTree = true).performClick()
        compose.onNodeWithText(appText("版本：%1\$s", 3)).assertIsDisplayed()
        compose.onNodeWithTag("team-task-toggle-task-1").performClick()
        compose.onNodeWithText(appText("版本：%1\$s", 3)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), selected) }
    }

    @Test
    fun onlyOwnerOpensConversationWithoutTogglingDetails() {
        var selections = 0
        compose.setContent {
            DshTheme { TeamTaskCard(task, owner, emptyList()) { selections++ } }
        }
        compose.onNodeWithTag("team-task-owner-task-1").performClick()
        compose.onNodeWithText(appText("版本：%1\$s", 3)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, selections) }
        compose.onNodeWithTag("team-task-toggle-task-1").performClick()
        compose.onNodeWithTag("team-task-owner-task-1").performClick()
        compose.onNodeWithText(appText("版本：%1\$s", 3)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, selections) }
    }

    @Test
    fun idOnlyOwnerShowsResolvedNameAndOpensCorrectSession() {
        var selected: String? = null
        val idTask = task.copy(ownerName = null, ownerId = owner.id)
        compose.setContent {
            DshTheme { TeamTaskCard(idTask, idTask.owner(listOf(owner)), emptyList()) { selected = it.id } }
        }
        compose.onNodeWithText(appText("负责人：%1\$s", owner.name)).assertIsDisplayed()
        compose.onNodeWithTag("team-task-owner-task-1").performClick()
        compose.runOnIdle { assertEquals(owner.id, selected) }
    }

    @Test
    fun unknownIdNeverOpensSameNamedMember() {
        var selections = 0
        val unknownTask = task.copy(ownerId = "missing")
        compose.setContent {
            DshTheme { TeamTaskCard(unknownTask, unknownTask.owner(listOf(owner)), emptyList()) { selections++ } }
        }
        compose.onNodeWithText(appText("负责人：%1\$s", "scout")).assertIsDisplayed()
        compose.onNodeWithTag("team-task-owner-task-1").performClick()
        compose.runOnIdle { assertEquals(0, selections) }
    }

    @Test
    fun rc3LongOwnerPathAtNormalAndLargeFontSizes() {
        val scale = mutableFloatStateOf(1f)
        val member = owner.copy(name = "reviewer/checker-with-a-long-name", identityName = "reviewer/checker-with-a-long-name")
        val idTask = task.copy(ownerId = member.id, ownerName = null)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.floatValue)) {
                DshTheme {
                    Box(Modifier.width(350.dp)) {
                        TeamTaskCard(idTask, idTask.owner(listOf(member)), emptyList()) { }
                    }
                }
            }
        }
        for (fontScale in listOf(1f, 1.5f)) {
            compose.runOnIdle { scale.floatValue = fontScale }
            compose.onNodeWithText(appText("负责人：%1\$s", member.name)).assertIsDisplayed()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "rc3-team-owner-$fontScale.png").outputStream().use {
                compose.onNodeWithTag("team-task-task-1").captureToImage().asAndroidBitmap()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    // 真实会话由调用者显式传入，普通设备回归不会写入或新建真实任务。
    @Test
    fun liveGatewayTeamFilteringAndOwnerNavigation() {
        val session = InstrumentationRegistry.getArguments().getString("rc3SessionId")
        assumeNotNull(session)
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as DshAndroidApplication
        compose.waitUntil(20_000) { application.hosts.ready && application.hosts.activeProfile != null }
        lateinit var holder: AndroidSharedStateHolder
        compose.runOnIdle {
            holder = application.hosts.activeGraph.stateHolder
            holder.connect()
        }
        compose.waitUntil(20_000) { holder.gatewayState.connection == GatewayConnectionState.CONNECTED }
        val previous = holder.snapshot.selectedSessionId
        try {
            compose.runOnIdle { holder.selectSession(requireNotNull(session)) }
            compose.waitUntil(20_000) { holder.teamSnapshot.state == "ready" && holder.teamSnapshot.tasks.size >= 4 }
            for (name in listOf("reviewer", "writer")) {
                compose.runOnIdle { holder.selectTeamMember(holder.teamSnapshot.members.first { it.name == name }) }
                compose.waitUntil(20_000) { holder.teamSnapshot.members.any { it.name == "$name/checker" } }
            }
            val team = holder.teamSnapshot
            val task = team.tasks.first { it.subject == "RC3多层分支复核" }
            val owner = requireNotNull(task.owner(team.members))
            assertEquals("reviewer/checker", owner.name)
            assertEquals(1, team.tasks.count { it.isUnassigned })
            assertTrue(team.members.any { it.name == "writer/checker" && it.id != owner.id })
            compose.runOnIdle { holder.selectTeamMember(team.members.first { it.id == session }) }
            compose.waitUntil(20_000) { holder.snapshot.selectedSessionId == session }
            compose.setContent { DshTheme { DshMenuHost { TeamEntryButton(holder) } } }
            fun openBoard() {
                compose.onNodeWithTag("team-entry").performClick()
                compose.onNodeWithText(appText("查看完整团队内容")).performClick()
            }
            openBoard()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(appText("未分配")))
            compose.onNodeWithText(appText("未分配")).performClick()
            compose.onNodeWithText(appText("未分配")).assertIsSelected()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("RC3待分配"))
            compose.onNodeWithText("RC3待分配").assertIsDisplayed()
            compose.onNodeWithText("RC3检查分镜").assertDoesNotExist()
            compose.onNodeWithText("RC3撰写摘要").assertDoesNotExist()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(appText("全部")))
            compose.onNodeWithText(appText("全部")).performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("team-task-${task.id}"))
            compose.onNodeWithTag("team-task-owner-${task.id}").performClick()
            compose.waitUntil(20_000) { holder.snapshot.selectedSessionId == owner.id }
            assertTrue(holder.isViewingTeamMember)
            openBoard()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(appText("当前成员")))
            compose.onNodeWithText(appText("当前成员")).performClick()
            compose.onNodeWithText(appText("当前成员")).assertIsSelected()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("RC3多层分支复核"))
            compose.onNodeWithText("RC3多层分支复核").assertIsDisplayed()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "rc3-live-task.png").outputStream().use {
                compose.onNodeWithTag("team-task-${task.id}").captureToImage().asAndroidBitmap()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            File(context.getExternalFilesDir(null), "rc3-live-team.json").writeText(Json.encodeToString(holder.teamSnapshot))
            compose.onNodeWithTag("team-sheet-done").performClick()
        } finally {
            if (previous != null) compose.runOnIdle { holder.selectSession(previous) }
        }
    }

    @Test
    fun unassignedTaskCanExpandWithoutOpeningAnotherConversation() {
        var selections = 0
        compose.setContent {
            DshTheme { TeamTaskCard(task.copy(ownerName = null), null, emptyList()) { selections++ } }
        }
        compose.onNodeWithTag("team-task-toggle-task-1").performClick()
        compose.onNodeWithText(appText("版本：%1\$s", 3)).assertIsDisplayed()
        compose.onNodeWithText("素材分析", useUnmergedTree = true).performClick()
        compose.onNodeWithText(appText("版本：%1\$s", 3)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, selections) }
    }
}
