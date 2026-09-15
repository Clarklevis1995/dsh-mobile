package com.clarklevis.dsh.android.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.clarklevis.dsh.shared.facade.SharedQueueItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConversationQueueDeviceTest {
    @get:Rule val compose = createComposeRule()
    private fun item(id: String) = SharedQueueItem(id, id, "消息 $id", 0, true)

    @Test fun singleRowExposesEditRemoveAndSteerWithCorrectIdentity() {
        val actions = mutableListOf<Pair<String, String>>()
        compose.setContent { DshTheme { QueueDock("a", listOf(item("q1")), null, true, true) { id, action -> actions += id to action } } }
        compose.onNodeWithText("消息 q1").assertIsDisplayed()
        compose.onNodeWithContentDescription("编辑排队消息").performClick()
        compose.onNodeWithContentDescription("删除排队消息").performClick()
        compose.onNodeWithContentDescription("立即插话").performClick()
        compose.runOnIdle { assertEquals(listOf("q1" to "edit", "q1" to "remove", "q1" to "steer"), actions) }
    }

    @Test fun multipleRowsCollapseAndExpandInHostOrder() {
        compose.setContent { DshTheme { QueueDock("a", listOf(item("q1"), item("q2")), null, true, true) { _, _ -> } } }
        compose.onNodeWithText("消息 q1").assertDoesNotExist()
        compose.onNodeWithText("排队消息 · 2").performClick()
        compose.onNodeWithText("消息 q1").assertIsDisplayed()
        compose.onNodeWithText("消息 q2").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("立即插话").assertCountEquals(2)
        compose.onNodeWithText("排队消息 · 2").performClick()
        compose.onNodeWithText("消息 q1").assertDoesNotExist()
    }

    @Test fun stoppedAgentAndDisconnectedStateDisableActions() {
        compose.setContent { DshTheme { QueueDock("a", listOf(item("q1")), null, false, false) { _, _ -> error("Disconnected actions must not run") } } }
        compose.onNodeWithContentDescription("编辑排队消息").assertIsNotEnabled()
        compose.onNodeWithContentDescription("删除排队消息").assertIsNotEnabled()
        compose.onNodeWithContentDescription("立即插话").assertIsNotEnabled()
    }

    @Test fun pendingActionBlocksOtherRows() {
        compose.setContent { DshTheme { QueueDock("a", listOf(item("q1"), item("q2")), "q1", true, true) { _, _ -> error("Pending action must block duplicates") } } }
        compose.onNodeWithText("排队消息 · 2").performClick()
        compose.onAllNodesWithContentDescription("删除排队消息").assertCountEquals(1)
        compose.onNodeWithContentDescription("删除排队消息").assertIsNotEnabled()
    }
}
