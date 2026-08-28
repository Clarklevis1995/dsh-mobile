package com.clarklevis.dsh.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentPresetLabelsTest {
    @Test
    fun builtInPresetIdsUseIosChineseLabels() {
        assertEquals("标准模式", agentPresetDisplayName("standard", "Standard"))
        assertEquals("PTC 模式", agentPresetDisplayName("code", "Code"))
        assertEquals("极简模式", agentPresetDisplayName("minimal", "Minimal"))
        assertEquals("创造模式", agentPresetDisplayName("cordis", "Cordis"))
    }

    @Test
    fun customPresetKeepsGatewayName() {
        assertEquals("团队预设", agentPresetDisplayName("team", "团队预设"))
    }
}
