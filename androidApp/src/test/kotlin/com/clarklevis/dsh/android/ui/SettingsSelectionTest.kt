package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.protocol.GatewayModelGroup
import com.clarklevis.dsh.shared.protocol.GatewayModelItem
import com.clarklevis.dsh.shared.protocol.GatewayModelReasoning
import com.clarklevis.dsh.shared.protocol.GatewayReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSelectionTest {
    private val efforts = listOf(
        GatewayReasoningEffort("low", "Low"),
        GatewayReasoningEffort("high", "High")
    )
    private val group = GatewayModelGroup(
        id = "deepseek",
        name = "DeepSeek",
        models = emptyList()
    )

    @Test
    fun selectingModelRetainsCompatibleCurrentEffortLikeIos() {
        val change = pendingDefaultModelChange(
            group = group,
            model = GatewayModelItem(
                id = "vision",
                name = "DeepSeek Vision",
                reasoning = GatewayModelReasoning(efforts, defaultEffort = "low")
            ),
            currentEffort = "high"
        )

        assertEquals("high", change.reasoningEffort)
        assertEquals("High", change.effortName)
        assertEquals("DeepSeek · DeepSeek Vision · High", change.summary)
    }

    @Test
    fun selectingModelFallsBackToItsDefaultEffort() {
        val change = pendingDefaultModelChange(
            group = group,
            model = GatewayModelItem(
                id = "vision",
                name = "DeepSeek Vision",
                reasoning = GatewayModelReasoning(efforts, defaultEffort = "low")
            ),
            currentEffort = "max"
        )

        assertEquals("low", change.reasoningEffort)
        assertEquals("Low", change.effortName)
    }
}
