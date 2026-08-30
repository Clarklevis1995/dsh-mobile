package com.clarklevis.dsh.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.clarklevis.dsh.shared.protocol.GatewayContextPressure
import com.clarklevis.dsh.shared.protocol.GatewaySessionStats
import com.clarklevis.dsh.shared.protocol.GatewaySessionStatsSnapshot
import com.clarklevis.dsh.shared.protocol.GatewaySessionTokenUsage
import com.clarklevis.dsh.shared.protocol.GatewaySessionTokenUsageTotals
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatusUiDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun capsuleOpensPopoverAndFullStatsSheet() {
        val snapshot = GatewaySessionStatsSnapshot(
            asOfSeq = 2_809,
            stats = GatewaySessionStats(
                turns = 2,
                steps = 3,
                llmMs = 27_100.0,
                toolMs = 36_200.0,
                ttftMs = 1_941.0,
                ttftSteps = 3,
                decodeMs = 900.0,
                decodeTokens = 100
            ),
            tokenUsage = GatewaySessionTokenUsage(
                GatewaySessionTokenUsageTotals(
                    inputTokens = 9_400,
                    outputTokens = 1_000,
                    cacheReadTokens = 2_000,
                    cacheWriteTokens = 100,
                    reasoningTokens = 500
                )
            ),
            contextPressure = GatewayContextPressure(
                pressureTokens = 9_400,
                projectedTokens = 10_400,
                contextWindow = 1_000_000
            )
        )
        compose.setContent {
            var showSheet by remember { mutableStateOf(false) }
            DshTheme {
                SessionStatsBanner(snapshot, "session-test") { showSheet = true }
                if (showSheet) SessionStatsSheet(snapshot) { showSheet = false }
            }
        }

        compose.onNode(hasTestTag("session-stats-capsule") and hasText("2 轮 · 3 步"))
            .assertIsDisplayed()
            .performClick()
        compose.onNode(hasTestTag("session-stats-popover")).assertIsDisplayed()
        compose.onNode(hasText("首 token 平均")).assertIsDisplayed()
        compose.onNode(hasTestTag("view-full-session-stats")).performClick()
        compose.onNode(hasTestTag("session-stats-sheet")).assertIsDisplayed()
        compose.onNode(hasText("会话状态")).assertIsDisplayed()
        compose.onNode(hasText("执行")).assertIsDisplayed()

        val sheetBottom = compose.onNode(hasTestTag("session-stats-sheet"))
            .fetchSemanticsNode().boundsInRoot.bottom
        val windowBottom = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            .fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        assertTrue(
            "Bottom sheet must cover the window bottom: sheet=$sheetBottom, window=$windowBottom",
            sheetBottom >= windowBottom
        )
    }
}
