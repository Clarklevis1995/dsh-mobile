package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.MobileScheduledTask
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File
import java.util.Locale
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.clarklevis.dsh.shared.protocol.ScheduleTargetDraft
import com.clarklevis.dsh.shared.protocol.ScheduleTimingDraft
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace

class ScheduledTaskParityDeviceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var previousLocale: Locale
    @Before fun chinese() { previousLocale = Locale.getDefault(); Locale.setDefault(Locale.SIMPLIFIED_CHINESE) }
    @After fun restore() { Locale.setDefault(previousLocale) }

    @Test fun groupedControlsUseMatchingLightAndDarkBackgrounds() {
        var dark by mutableStateOf(false)
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                ScheduleTheme {
                    DshMenuHost {
                        Column(Modifier.width(390.dp).fillMaxHeight()
                            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            ScheduleTargetControls(ScheduleTargetDraft("new_once", "/projects/b"), true,
                                listOf(GatewayWorkspace("b", "/projects/b", "新闻工作区", emptyList(), "", "")), true, {})
                            ScheduleTimingControls(ScheduleTimingDraft("daily", "1", "2099-01-31", "08:00:37.000", "Asia/Shanghai", "1", "31", ""), true, {})
                        }
                    }
                }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val pixel = bitmap.getPixel(5, 5)
            if (night) assertTrue(android.graphics.Color.red(pixel) < 30) else assertTrue(android.graphics.Color.red(pixel) > 240)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), if (night) "schedule-controls-dark.png" else "schedule-controls-light.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun pausedCardOnlyRevealsEditAndDeleteAndKeepsHistoryInsideDetails() {
        val raw = GatewayWireDecoder.decode("""{"kind":"schedule-catalog","items":[{"id":"p1","sessionId":"s1","status":"paused","kind":"daily","title":"每日新闻汇总","prompt":"汇总科技和金融新闻","time":"08:00:37.000","timeZone":"Asia/Shanghai","scheduledAt":"2099-01-31T00:00:00Z"}]}""").items!!.single()
        val task = MobileScheduledTask.from(raw)!!
        var revealed by mutableStateOf<String?>(null)
        var edits = 0
        var historyOpens = 0
        compose.setContent {
            DshTheme {
                ScheduleTheme {
                    Box(Modifier.width(390.dp).background(Color.White).padding(20.dp)) {
                        ScheduledTaskCard(task, "新闻会话", revealed, { revealed = it }, {}, { edits++ }, {}, false,
                            onShowHistory = { historyOpens++ })
                    }
                }
            }
        }
        compose.onNodeWithText("已暂停").assertIsDisplayed()
        compose.onNodeWithTag("scheduled-card-p1").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("恢复").assertDoesNotExist()
        compose.onNodeWithContentDescription("暂停").assertDoesNotExist()
        compose.onNodeWithContentDescription("立即执行").assertDoesNotExist()
        compose.onNodeWithContentDescription("执行记录").assertDoesNotExist()
        assertEquals(compose.onNodeWithTag("scheduled-card-p1").fetchSemanticsNode().boundsInRoot.left,
            compose.onNodeWithTag("scheduled-card-body-p1").fetchSemanticsNode().boundsInRoot.left, 1f)
        compose.onNodeWithTag("scheduled-card-p1").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onRoot().captureToImage().let { image ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "schedule-edit-pencil.png").outputStream().use {
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithContentDescription("编辑每日新闻汇总").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, edits); revealed = null }
        compose.onNodeWithTag("scheduled-card-p1").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.waitForIdle()
        assertEquals(compose.onNodeWithTag("scheduled-card-p1").fetchSemanticsNode().boundsInRoot.left,
            compose.onNodeWithTag("scheduled-card-body-p1").fetchSemanticsNode().boundsInRoot.left, 1f)
        // 真实触摸在侧滑结束后有 350ms 防误触窗口。
        val closedAt = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(1000) { android.os.SystemClock.uptimeMillis() - closedAt >= 350L }
        compose.onNodeWithContentDescription("展开任务详情").performClick()
        compose.onNodeWithTag("scheduled-task-history").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, historyOpens) }
        compose.onRoot().captureToImage().let { image ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "schedule-paused-parity.png").outputStream().use {
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
