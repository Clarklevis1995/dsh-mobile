package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.clarklevis.dsh.shared.facade.SharedApprovalStatusSnapshot
import com.clarklevis.dsh.shared.protocol.GatewayApprovalOutcome
import com.clarklevis.dsh.shared.protocol.GatewayPendingApprovalRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Regression tests for the approval-card command preview cap: a long multi-line
 * command must scroll inside a capped preview so the 拒绝 / 允许一次 buttons stay
 * on-screen and clickable (they used to be pushed off the bottom edge).
 */
class ApprovalRequestCardDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private fun approvalRequest() = GatewayPendingApprovalRequest(
        rpcId = "test-rpc-1",
        sessionId = "test-session",
        approvalId = "test-approval",
        toolName = "Bash",
        callId = "test-call",
        reason = "执行本地 provisioning profile 审计脚本"
    )

    private fun longCommand(): String =
        (1..60).joinToString("\n") {
            "echo \"audit step $it: verify provisioning profile payload and expiry date\""
        }

    @Test
    fun longCommandCapsPreviewAndKeepsButtonsOnScreen() {
        var density: Density? = null
        compose.setContent {
            density = LocalDensity.current
            DshTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("approval-card-host")
                ) {
                    ApprovalRequestCard(
                        request = approvalRequest(),
                        status = SharedApprovalStatusSnapshot(kind = "pending"),
                        commandPreview = longCommand(),
                        details = null,
                        onDecision = {}
                    )
                }
            }
        }

        val capPx = with(density!!) { 148.dp.toPx() }

        // The preview is rendered and capped at the 148.dp limit (the fix).
        val preview = compose.onNodeWithTag("approval-command-preview").assertIsDisplayed()
        val previewHeight = preview.fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "preview height ${previewHeight}px exceeds the 148.dp cap ${capPx}px",
            previewHeight <= capPx + 1f
        )

        // The action row stays inside the visible host area instead of being
        // pushed past the bottom edge by the preview.
        val actions = compose.onNodeWithTag("approval-actions").assertIsDisplayed()
        val host = compose.onNodeWithTag("approval-card-host")
        val hostHeight = host.fetchSemanticsNode().boundsInRoot.height
        val actionsBottom = actions.fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(
            "action row bottom ${actionsBottom}px exceeds host height ${hostHeight}px",
            actionsBottom <= hostHeight + 1f
        )
    }

    @Test
    fun shortCommandHugsContentInsteadOfPaddingToTheCap() {
        var density: Density? = null
        compose.setContent {
            density = LocalDensity.current
            DshTheme {
                Box(Modifier.fillMaxSize()) {
                    ApprovalRequestCard(
                        request = approvalRequest(),
                        status = SharedApprovalStatusSnapshot(kind = "pending"),
                        commandPreview = "echo hi",
                        details = null,
                        onDecision = {}
                    )
                }
            }
        }

        val capPx = with(density!!) { 148.dp.toPx() }
        val preview = compose.onNodeWithTag("approval-command-preview").assertIsDisplayed()
        val previewHeight = preview.fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "single-line preview height ${previewHeight}px should hug content, not fill the 148.dp cap ${capPx}px",
            previewHeight in 1f..(capPx - 1f)
        )
        compose.onNodeWithTag("approval-actions").assertIsDisplayed()
    }

    @Test
    fun allowOnceButtonRemainsClickableForLongCommand() {
        var decision: GatewayApprovalOutcome? = null
        compose.setContent {
            DshTheme {
                Box(Modifier.fillMaxSize()) {
                    ApprovalRequestCard(
                        request = approvalRequest(),
                        status = SharedApprovalStatusSnapshot(kind = "pending"),
                        commandPreview = longCommand(),
                        details = null,
                        onDecision = { decision = it }
                    )
                }
            }
        }

        compose.onNodeWithText("允许一次").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(GatewayApprovalOutcome.ALLOWED_ONCE, decision)
    }
}
