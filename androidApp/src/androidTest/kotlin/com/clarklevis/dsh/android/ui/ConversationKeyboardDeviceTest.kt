package com.clarklevis.dsh.android.ui

import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.shared.facade.SharedVoiceInputStore
import com.clarklevis.dsh.shared.facade.VoiceInputInteraction
import android.util.Base64
import org.junit.Rule
import org.junit.Test

class ConversationKeyboardDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyComposerSwitchesBetweenWaveformAndKeyboardAndDraftUsesSend() {
        val fixture = showConversation()
        try {
            val composer = compose.onNodeWithTag("conversation-composer-column")
            val defaultHeight = composer.fetchSemanticsNode().boundsInRoot.height
            val action = compose.onNodeWithTag("composer-action")
            action.assertIsEnabled().assertContentDescriptionEquals("切换到语音输入")
            val editor = compose.onNodeWithTag("composer-input")
            editor.performTouchInput { longClick() }
            compose.runOnIdle {
                assertTrue("文字态长按不能录音", !fixture.holder.voiceInput.state.active)
            }
            action.performClick()
            compose.onNodeWithTag("composer-input").assertDoesNotExist()
            compose.onNodeWithTag("composer-voice-hold").assertIsDisplayed()
            compose.onNodeWithText("按住说话").assertIsDisplayed()
            action.assertContentDescriptionEquals("切换到文字输入")
            waitForIme(fixture.view, false)
            assertTrue("切换语音态不能改变输入框默认高度", kotlin.math.abs(composer.fetchSemanticsNode().boundsInRoot.height - defaultHeight) < 1f)
            compose.runOnIdle { assertTrue(!fixture.holder.voiceInput.state.active) }
            val image = compose.onNodeWithTag("conversation-composer-column").captureToImage()
            val output = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("voice-mode-composer.png")
            output.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            action.performClick()
            compose.onNodeWithTag("composer-voice-hold").assertDoesNotExist()
            editor.assertIsFocused()
            waitForIme(fixture.view, true)
            editor.performTextInput("先编辑再发送")
            action.assertContentDescriptionEquals("发送")
            editor.performTextClearance()
            action.assertContentDescriptionEquals("切换到语音输入")
        } finally {
            fixture.holder.close()
        }
    }

    @Test
    fun inputFocusMovesLatestMessageAboveImeAndTimelineTapHidesIme() {
        val fixture = showConversation()
        val latestMessage = compose.onNode(
            hasTestTag("user-text-bubble") and hasText("末尾标记", substring = true)
        )
        latestMessage.assertIsDisplayed()
        val messageTopBeforeIme = latestMessage.fetchSemanticsNode().boundsInRoot.top

        compose.onNodeWithTag("composer-input").performClick().assertIsFocused()
        waitForIme(fixture.view, visible = true)
        compose.waitUntil(timeoutMillis = 5_000) {
            latestMessage.fetchSemanticsNode().boundsInRoot.top < messageTopBeforeIme - 100f
        }

        compose.onNodeWithTag("conversation-timeline").performTouchInput {
            click(Offset(center.x, 40f))
        }

        compose.onNodeWithTag("composer-input").assertIsNotFocused()
        waitForIme(fixture.view, visible = false)
        fixture.holder.close()
    }

    @Test
    fun successfulSendClearsFocusAndHidesIme() {
        val fixture = showConversation()
        compose.runOnIdle { fixture.holder.messageDraft = "你好" }
        compose.onNodeWithTag("composer-input").performClick().assertIsFocused()
        waitForIme(fixture.view, visible = true)
        val submission = compose.runOnIdle {
            fixture.holder.captureMessageSubmissionForTest()
        }

        compose.runOnIdle {
            fixture.holder.applyMessageSendResultForTest(submission, sent = true)
        }

        compose.onNodeWithTag("composer-input").assertIsNotFocused()
        waitForIme(fixture.view, visible = false)
        fixture.holder.close()
    }

    @Test
    fun transcribedDraftKeepsNativeTapEditingAndImeInput() {
        val shared = SharedVoiceInputStore()
        shared.open("keyboard-session", "")
        shared.setVoiceMode(true)
        val request = shared.connectionChanged(true, true).requests.first { it.requestType == "speech-catalog" }
        shared.acceptJson("""{"kind":"speech-catalog","requestId":"${request.correlationId}","available":true,"selection":{"providerId":"p"},"providers":[{"id":"p","preparation":{"phase":"ready"}}]}""")
        val token = shared.press(1000).effects.first { it.action == "permission" }.recordingId
        shared.permissionResult(token, true, 1001)
        shared.captureStarted(token, 1002)
        shared.release(1500)
        shared.captureFinished(token, Base64.encodeToString(ByteArray(16000), Base64.NO_WRAP), 1501)
        val fixture = showConversation(shared)
        compose.onNodeWithTag("voice-recording-overlay").assertDoesNotExist()
        compose.onNodeWithText("正在整理录音").assertDoesNotExist()
        compose.onNodeWithText("正在转成文字").assertDoesNotExist()
        compose.onNodeWithTag("composer-voice-hold").assertExists()
        compose.onNodeWithTag("composer-input").assertDoesNotExist()
        compose.runOnIdle {
            fixture.holder.voiceInput.accept("""{"kind":"speech-transcript","requestId":"$token","text":"语音草稿"}""")
        }
        val editor = compose.onNodeWithTag("composer-input")
        editor.assertIsNotFocused().assertTextContains("语音草稿")
        waitForIme(fixture.view, false)
        editor.performTouchInput { click() }
        editor.assertIsFocused()
        waitForIme(fixture.view, true)
        editor.performTextInput("修改")
        editor.assertTextContains("语音草稿修改")
        compose.onNodeWithTag("conversation-timeline").performTouchInput { click(Offset(center.x, 40f)) }
        waitForIme(fixture.view, false)
        // 实际触摸短按，不通过语义 performClick 绕过手势拦截。
        editor.performTouchInput { click() }
        editor.assertIsFocused()
        waitForIme(fixture.view, true)
        editor.performTextInput("再编辑")
        editor.assertTextContains("再编辑", substring = true)
        fixture.holder.close()
    }

    @Test
    fun continuedVoiceHoldCannotStartTextSelectionOrShowIme() {
        val shared = SharedVoiceInputStore()
        shared.open("keyboard-session", "")
        shared.setVoiceMode(true)
        val request = shared.connectionChanged(true, true).requests.first { it.requestType == "speech-catalog" }
        shared.acceptJson("""{"kind":"speech-catalog","requestId":"${request.correlationId}","available":true,"selection":{"providerId":"p"},"providers":[{"id":"p","preparation":{"phase":"ready"}}]}""")
        val fixture = showConversation(shared)
        var editor = compose.onNodeWithTag("composer-voice-hold")
        var token = ""
        try {
            // Down 先交给专用语音区，随后注入已启动的录音，模拟硬件结果，不启用麦克风。
            compose.mainClock.autoAdvance = false
            editor.performTouchInput { down(center) }
            compose.runOnIdle {
                token = shared.press(1000).effects.first { it.action == "permission" }.recordingId
                shared.permissionResult(token, true, 1001)
                shared.captureStarted(token, 1002)
                fixture.holder.voiceInput.draftChanged("")
            }
            compose.mainClock.advanceTimeByFrame()
            editor.performTouchInput {
                advanceEventTime(1200)
                moveTo(center + Offset(1f, 0f))
            }
            compose.mainClock.advanceTimeBy(1200)
            assertTrue("录音时文本框不得获得焦点", !editor.fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false })
            assertTrue("继续长按不得打断录音", fixture.holder.voiceInput.state.phase == "recording")
            assertTrue("录音时键盘不得显示", ViewCompat.getRootWindowInsets(fixture.view)?.isVisible(WindowInsetsCompat.Type.ime()) != true)
            compose.onNodeWithTag("voice-recording-overlay").assertExists()
            editor.performTouchInput { up() }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertTrue("松手应完成录音而非被原生手势取消", fixture.holder.voiceInput.state.phase == "stopping")
            compose.onNodeWithTag("voice-recording-overlay").assertDoesNotExist()
            compose.onNodeWithText("正在整理录音").assertDoesNotExist()
            assertTrue(!editor.fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false })
            compose.runOnIdle {
                shared.captureFinished(token, Base64.encodeToString(ByteArray(16000), Base64.NO_WRAP), 1501)
                fixture.holder.voiceInput.draftChanged("")
                fixture.holder.voiceInput.accept("""{"kind":"speech-transcript","requestId":"$token","text":"继续长按回归"}""")
            }
            editor = compose.onNodeWithTag("composer-input")
            editor.assertIsNotFocused().assertTextContains("继续长按回归")
            waitForIme(fixture.view, false)
            editor.performTouchInput { click() }
            editor.assertIsFocused()
            waitForIme(fixture.view, true)
            editor.performTextInput("修改")
            editor.assertTextContains("修改", substring = true)
        } finally {
            compose.mainClock.autoAdvance = true
            fixture.holder.close()
        }
    }

    @Test
    fun briefTapStillOpensEditorWithoutTriggeringVoice() {
        val fixture = showConversation()
        try {
            compose.runOnIdle { fixture.holder.voiceInput.connectionChanged(true, false) }
            val editor = compose.onNodeWithTag("composer-input")
            editor.performTouchInput {
                down(center)
                advanceEventTime(180)
                up()
            }
            editor.assertIsFocused().performTextInput("短按编辑")
            editor.assertTextContains("短按编辑")
            compose.onNodeWithText("语音输入暂不可用").assertDoesNotExist()
            compose.onNodeWithTag("voice-recording-overlay").assertDoesNotExist()
        } finally {
            fixture.holder.close()
        }
    }

    @Test
    fun unsupportedVoiceShowsStandardAlertWithoutLosingDraft() {
        val fixture = showConversation()
        try {
            // 现行入口为独立语音模式；文字输入框长按保持系统选择行为。
            compose.onNodeWithTag("composer-action").performClick()
            compose.runOnIdle {
                fixture.holder.messageDraft = "保留草稿"
                fixture.holder.voiceInput.connectionChanged(true, false)
                fixture.holder.voiceInput.setVoiceMode(true)
                fixture.holder.voiceInput.press()
            }
            compose.onNodeWithText("语音输入暂不可用").assertIsDisplayed()
            compose.runOnIdle { org.junit.Assert.assertEquals("保留草稿", fixture.holder.messageDraft) }
            compose.onNodeWithText("知道了").performClick()
            compose.runOnIdle { fixture.holder.voiceInput.setVoiceMode(false) }
            val editor = compose.onNodeWithTag("composer-input")
            editor.assertTextContains("保留草稿")
            editor.performTouchInput { click() }
            editor.assertIsFocused().performTextInput("继续编辑")
            editor.assertTextContains("继续编辑", substring = true)
        } finally { fixture.holder.close() }
    }

    @Test
    fun approvalArrivalWhileViewingTrajectoryReturnsToVisibleActions() {
        val fixture = showConversation()
        try {
            val switchPage = compose.onNodeWithTag("conversation-pager").fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
            compose.runOnIdle { assertTrue(switchPage.action()) }
            compose.waitForIdle()
            compose.onNodeWithTag("composer-input").assertIsNotDisplayed()
            compose.runOnIdle {
                fixture.holder.wirePayload =
                    """{"kind":"approval-requested","rpcId":"approval-rpc","sessionId":"keyboard-session","approvalId":"approval-id","toolName":"Bash","reason":"需要确认命令"}"""
                fixture.holder.submitWirePayload()
            }
            compose.onNodeWithText("允许一次").assertIsDisplayed()
            compose.onNodeWithText("拒绝").assertIsDisplayed()
        } finally {
            fixture.holder.close()
        }
    }

    @Test
    fun approvalArrivalWhileEditingHidesKeyboardAndShowsActions() {
        val fixture = showConversation()
        try {
            compose.onNodeWithTag("composer-input").performClick().assertIsFocused()
            waitForIme(fixture.view, visible = true)
            compose.runOnIdle {
                fixture.holder.wirePayload =
                    """{"kind":"approval-requested","rpcId":"approval-rpc","sessionId":"keyboard-session","approvalId":"approval-id","toolName":"Bash","reason":"需要确认命令"}"""
                fixture.holder.submitWirePayload()
            }
            waitForIme(fixture.view, visible = false)
            compose.onNodeWithText("允许一次").assertIsDisplayed()
            compose.onNodeWithText("拒绝").assertIsDisplayed()
        } finally {
            fixture.holder.close()
        }
    }

    @Test
    fun anotherSessionsApprovalDoesNotInterruptTrajectoryAndAppearsOnSelection() {
        val fixture = showConversation()
        try {
            val switchPage = compose.onNodeWithTag("conversation-pager").fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
            compose.runOnIdle { assertTrue(switchPage.action()) }
            compose.waitForIdle()
            compose.runOnIdle {
                fixture.holder.wirePayload =
                    """{"kind":"approval-requested","rpcId":"other-rpc","sessionId":"other-session","approvalId":"other-id","toolName":"Bash","reason":"另一个会话的审批","replay":true}"""
                fixture.holder.submitWirePayload()
            }
            compose.onNodeWithTag("composer-input").assertIsNotDisplayed()
            compose.onNodeWithText("允许一次").assertDoesNotExist()
            compose.runOnIdle { fixture.holder.selectSession("other-session") }
            compose.onNodeWithText("另一个会话的审批").assertIsDisplayed()
            compose.onNodeWithText("允许一次").assertIsDisplayed()
        } finally {
            fixture.holder.close()
        }
    }

    @Test
    fun particleMaskCoversComposerAndSwitchesToLightRedForCancellation() {
        val shared = SharedVoiceInputStore()
        shared.open("keyboard-session", "")
        shared.setVoiceMode(true)
        val request = shared.connectionChanged(true, true).requests.first { it.requestType == "speech-catalog" }
        shared.acceptJson("""{"kind":"speech-catalog","requestId":"${request.correlationId}","available":true,"selection":{"providerId":"p"},"providers":[{"id":"p","preparation":{"phase":"ready"}}]}""")
        val token = shared.press(1000).effects.first { it.action == "permission" }.recordingId
        shared.permissionResult(token, true, 1001)
        shared.captureStarted(token, 1002)
        repeat(50) { shared.level(token, 0.01f + (it % 9) * 0.006f, 2640L + it * 40) }
        val fixture = showConversation(shared)
        compose.runOnIdle {
            (fixture.view.context as ComponentActivity).enableEdgeToEdge()
            (fixture.view.context as ComponentActivity).window.isNavigationBarContrastEnforced = false
        }
        compose.waitForIdle()
        val mask = compose.onNodeWithTag("voice-recording-overlay")
        val maskBounds = mask.fetchSemanticsNode().boundsInRoot
        val editorBounds = compose.onNodeWithTag("composer-voice-hold").fetchSemanticsNode().boundsInRoot
        assertTrue(maskBounds.top <= editorBounds.top && maskBounds.bottom >= editorBounds.bottom)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun assertCoversSystemNavigation(name: String, red: Boolean) {
            val screen = instrumentation.uiAutomation.takeScreenshot()
            val location = IntArray(2)
            compose.runOnIdle { fixture.view.getLocationOnScreen(location) }
            val bounds = mask.fetchSemanticsNode().boundsInRoot
            assertTrue("蒙层高度应达到屏幕的五分之二", bounds.height >= screen.height * 0.37f)
            assertTrue("蒙层高度不得超过屏幕五分之二", bounds.height <= screen.height * VoiceInputInteraction.OVERLAY_HEIGHT_FRACTION + 2f)
            val bottom = bounds.bottom + location[1]
            assertTrue("蒙层必须延伸到真实屏幕底边，不能止于导航栏之上", bottom >= screen.height - 1f)
            for (fraction in listOf(0.1f, 0.9f)) {
                val pixel = screen.getPixel((screen.width * fraction).toInt(), screen.height - 2)
                if (red) assertTrue("取消色应覆盖系统导航区域", android.graphics.Color.red(pixel) > android.graphics.Color.blue(pixel))
                else assertTrue("录音色应覆盖系统导航区域", android.graphics.Color.blue(pixel) > android.graphics.Color.red(pixel))
            }
            val output = instrumentation.targetContext.cacheDir.resolve(name)
            output.outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screen.recycle()
        }
        fun capture(name: String) = mask.captureToImage().also { image ->
            val file = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve(name)
            file.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val blue = capture("voice-recording-blue.png").toPixelMap()[20, mask.captureToImage().height - 20]
        assertTrue(blue.blue > blue.red)
        assertCoversSystemNavigation("voice-navbar-recording.png", red = false)
        compose.runOnIdle { fixture.holder.voiceInput.move(-100f) }
        val redImage = capture("voice-cancellation-red.png")
        val red = redImage.toPixelMap()[20, redImage.height - 20]
        assertTrue(red.red > red.blue && red.red > 0.8f)
        assertCoversSystemNavigation("voice-navbar-cancel.png", red = true)
        fixture.holder.close()
    }

    @Test
    fun recordingOverlayFadesBothWaysAndHasCurvedTopEdge() {
        val shared = SharedVoiceInputStore()
        shared.open("keyboard-session", "")
        shared.setVoiceMode(true)
        val request = shared.connectionChanged(true, true).requests.first { it.requestType == "speech-catalog" }
        shared.acceptJson("""{"kind":"speech-catalog","requestId":"${request.correlationId}","available":true,"selection":{"providerId":"p"},"providers":[{"id":"p","preparation":{"phase":"ready"}}]}""")
        fun startCapture() {
            val token = shared.press(1000).effects.first { it.action == "permission" }.recordingId
            shared.permissionResult(token, true, 1001)
            shared.captureStarted(token, 1002)
        }
        startCapture()
        val fixture = showConversation(shared)
        try {
            val mask = compose.onNodeWithTag("voice-recording-overlay")
            fun bottomRed(): Float {
                val image = mask.captureToImage()
                return image.toPixelMap()[20, image.height - 20].red
            }
            val image = mask.captureToImage()
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
                .resolve("voice-curved-edge.png").outputStream().use {
                    image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            val pixels = image.toPixelMap()
            // 正文现在会从顶部玻璃后方穿过；在上部渐变的稳定区域检查曲边，
            // 避免极浅的边缘颜色被背后的消息位置和像素取整主导。
            val topY = (image.height * 0.20f).toInt()
            val middle = pixels[image.width / 2, topY]
            val side = pixels[(image.width * 0.04f).toInt(), topY]
            assertTrue("椭圆渐变顶部两侧应比中间更透明：side=$side, middle=$middle", side.red > middle.red + 0.10f)
            val opaqueRed = bottomRed()
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { fixture.holder.voiceInput.cancel() }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(96)
            mask.assertExists()
            assertTrue("淡出中间帧应已变淡但尚未移除", bottomRed() > opaqueRed + 0.08f)
            compose.mainClock.advanceTimeBy(320)
            mask.assertDoesNotExist()
            compose.runOnIdle {
                startCapture()
                fixture.holder.voiceInput.draftChanged("")
            }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(32)
            val enteringRed = bottomRed()
            val enteringTop = mask.fetchSemanticsNode().boundsInRoot.top
            compose.mainClock.advanceTimeBy(360)
            val settledRed = bottomRed()
            val settledTop = mask.fetchSemanticsNode().boundsInRoot.top
            assertTrue("蒙层入场应轻微上移", enteringTop > settledTop + 0.5f)
            val earlyOpacity = (1 - enteringRed) / (1 - settledRed)
            assertTrue("淡入开始 32ms 应已清晰可见，不能慢起步", earlyOpacity > 0.25f)
            assertTrue("淡入仍应保留平滑过渡", earlyOpacity < 0.90f)
        } finally {
            compose.mainClock.autoAdvance = true
            fixture.holder.close()
        }
    }

    private fun showConversation(voiceStore: SharedVoiceInputStore = SharedVoiceInputStore()): Fixture {
        val holder = AndroidSharedStateHolder(voiceStore = voiceStore).apply {
            wirePayload =
                """{"kind":"sessions","items":[{"sessionId":"keyboard-session","updatedAt":1786937352000,"running":false,"blank":false,"cwd":"/tmp/keyboard","agentPreset":"standard"}]}"""
            submitWirePayload()
            selectSession("keyboard-session")
            repeat(14) { index ->
                val text = if (index == 13) {
                    "末尾标记 ${"最后一条内容".repeat(8)}"
                } else {
                    "第${index + 1}条消息 ${"用于填充会话列表".repeat(8)}"
                }
                wirePayload =
                    """{"kind":"event","sessionId":"keyboard-session","seq":${index + 1},"time":${1786937353 + index},"event":{"type":"user/message","source":"user","text":"$text"}}"""
                submitWirePayload()
            }
        }
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            DshTheme {
                ConversationScreen(
                    stateHolder = holder,
                    onPickImage = {},
                    onBack = {}
                )
            }
        }
        return Fixture(holder, view)
    }

    private fun waitForIme(view: View, visible: Boolean) {
        compose.waitUntil(timeoutMillis = 5_000) {
            ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == visible
        }
    }

    private data class Fixture(
        val holder: AndroidSharedStateHolder,
        val view: View
    )
}
