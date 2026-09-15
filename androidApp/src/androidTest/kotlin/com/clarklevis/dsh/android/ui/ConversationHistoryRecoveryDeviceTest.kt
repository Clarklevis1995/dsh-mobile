package com.clarklevis.dsh.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import org.junit.Rule
import org.junit.Test

class ConversationHistoryRecoveryDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun coldSnapshotShowsMaskThenHistoryWithoutEmptyWelcome() {
        val holder = showWaitingConversation()
        try {
            compose.onNodeWithTag("history-loading-overlay").assertIsDisplayed()
            compose.onNodeWithText("操作远端 DSH Agent").assertDoesNotExist()
            compose.runOnIdle {
                holder.frame(
                    """{"kind":"session-snapshot","sessionId":"s","subscriptionId":"sub","streamId":"stream","historyFormatVersion":3,"cursor":10,"events":[{"type":"user/message","seq":10,"time":100,"data":{"content":[{"type":"text","text":"恢复的历史消息"}],"source":{"kind":"user"}}}],"hasMore":false}"""
                )
            }
            compose.onNodeWithTag("history-loading-overlay").assertDoesNotExist()
            compose.onNodeWithText("操作远端 DSH Agent").assertDoesNotExist()
            compose.onNodeWithTag("conversation-timeline").assertIsDisplayed()
            // 再次订阅时已有内容应一直可读。
            compose.runOnIdle { holder.selectSession("s") }
            compose.onNodeWithTag("history-loading-overlay").assertDoesNotExist()
            compose.onNodeWithText("操作远端 DSH Agent").assertDoesNotExist()
        } finally {
            compose.runOnIdle { holder.close() }
        }
    }

    @Test
    fun genuinelyEmptySnapshotShowsWelcomeAfterLoadingEnds() {
        val holder = showWaitingConversation()
        try {
            compose.onNodeWithText("操作远端 DSH Agent").assertDoesNotExist()
            compose.runOnIdle {
                holder.frame(
                    """{"kind":"session-snapshot","sessionId":"s","subscriptionId":"sub","streamId":"stream","historyFormatVersion":3,"cursor":0,"events":[],"hasMore":false}"""
                )
            }
            compose.onNodeWithTag("history-loading-overlay").assertDoesNotExist()
            compose.onNodeWithText("操作远端 DSH Agent").assertIsDisplayed()
        } finally {
            compose.runOnIdle { holder.close() }
        }
    }

    @Test
    fun failedOpeningShowsRetryInsteadOfEmptyWelcome() {
        val holder = showWaitingConversation()
        try {
            compose.runOnIdle {
                holder.frame(
                    """{"kind":"session-stream-reset","sessionId":"s","subscriptionId":"sub","streamId":"opening","retrying":false,"message":"Host refuses this format v0 Session"}"""
                )
            }
            compose.onNodeWithTag("history-loading-overlay").assertDoesNotExist()
            compose.onNodeWithText("操作远端 DSH Agent").assertDoesNotExist()
            compose.onNodeWithTag("history-load-failure").assertIsDisplayed()
            compose.onNodeWithText("重新加载历史").assertIsDisplayed()
        } finally {
            compose.runOnIdle { holder.close() }
        }
    }

    private fun showWaitingConversation(): AndroidSharedStateHolder {
        val holder = AndroidSharedStateHolder().apply {
            frame("""{"kind":"hello","historyFormatVersion":3,"capabilities":["assistant-stream-v1"]}""")
            selectSession("s")
            frame("""{"kind":"subscribed","sessionId":"s","subscriptionId":"sub","assistantStream":true}""")
        }
        compose.setContent {
            DshTheme { ConversationScreen(holder, onPickImage = {}, onBack = {}) }
        }
        return holder
    }

    private fun AndroidSharedStateHolder.frame(raw: String) {
        wirePayload = raw
        submitWirePayload()
    }
}
