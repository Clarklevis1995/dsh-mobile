package com.clarklevis.dsh.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.clarklevis.dsh.shared.gateway.GatewayProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GatewayHostRowDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule val compose = createComposeRule()

    @Test
    fun wholeItemSelectsHostAndEditingStaysIndependent() {
        var managing by mutableStateOf(false)
        var selects = 0
        var edits = 0
        val profile = GatewayProfile(localId = "hit-test", gatewayName = "192.168.1.19", endpoints = emptyList())
        compose.setContent {
            DshTheme {
                GatewayHostRow(profile = profile, active = true, online = true, managing = managing,
                    selected = false, onSelect = { selects++ }, onEdit = { edits++ },
                    modifier = Modifier.testTag("host-row"))
            }
        }
        val points = listOf(0.02f to 0.5f, 0.98f to 0.5f, 0.5f to 0.02f, 0.5f to 0.98f, 0.75f to 0.5f)
        for ((x, y) in points) {
            compose.onNodeWithTag("host-row").performTouchInput { click(Offset(width * x, height * y)) }
        }
        assertEquals(points.size, selects)
        assertEquals(0, edits)
        compose.runOnIdle { managing = true }
        compose.onNodeWithContentDescription("编辑 ${profile.displayName}").performClick()
        assertEquals(1, edits)
        assertEquals(points.size, selects)
        compose.onNodeWithTag("host-row").performTouchInput { click(Offset(width * 0.5f, height * 0.02f)) }
        assertEquals(points.size + 1, selects)
        assertEquals(1, edits)
    }
}
