package com.clarklevis.dsh.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import com.clarklevis.dsh.shared.projection.TrajectoryNode
import com.clarklevis.dsh.shared.projection.TrajectoryNodeKind
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import org.junit.Rule
import org.junit.Test

class TrajectoryUiDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun liveNodesUpdateOverviewAndTimelineWithoutReenteringPage() {
        lateinit var publish: (List<TrajectoryNode>) -> Unit
        compose.setContent {
            var nodes by remember { mutableStateOf(listOf(node("stream", "正在生成", 1, 1.0, 1.2))) }
            publish = { nodes = it }
            DshTheme { TrajectoryPage(nodes = nodes, isActive = true) }
        }

        compose.onNode(hasText("1 Turns")).assertIsDisplayed()
        compose.onNode(hasText("正在生成")).assertIsDisplayed()

        compose.runOnIdle {
            publish(
                listOf(
                    node("final", "生成完成", 1, 1.0, 3.0),
                    node("turn-two", "第二轮", 2, 4.0, 5.0)
                )
            )
        }

        compose.onNode(hasText("2 Turns")).assertIsDisplayed()
        compose.onNode(hasText("生成完成")).assertIsDisplayed()
        compose.onNode(hasText("Turn 2")).assertIsDisplayed()
    }

    private fun node(
        id: String,
        subtitle: String,
        turn: Int,
        start: Double,
        end: Double
    ): TrajectoryNode {
        val record = SessionEvent(
            sessionId = "session",
            seq = turn,
            time = end,
            event = GatewayEvent("assistant/message", turn = turn, step = 1, text = subtitle)
        )
        return TrajectoryNode(
            id = id,
            kind = TrajectoryNodeKind.ASSISTANT,
            title = "Assistant",
            subtitle = subtitle,
            startSequence = turn,
            endSequence = turn,
            startEpochSeconds = start,
            endEpochSeconds = end,
            records = listOf(record)
        )
    }
}
