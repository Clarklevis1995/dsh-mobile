package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.facade.TeamMemberView
import com.clarklevis.dsh.shared.facade.TeamTaskView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TeamTaskCardDeviceTest {
    @get:Rule
    val compose = createComposeRule()

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
