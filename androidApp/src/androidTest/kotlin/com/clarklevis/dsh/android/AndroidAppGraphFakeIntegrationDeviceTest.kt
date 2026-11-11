package com.clarklevis.dsh.android

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.clarklevis.dsh.android.ui.WorkspaceFilePreviewHost
import com.clarklevis.dsh.android.ui.ConversationScreen
import com.clarklevis.dsh.android.ui.DshTheme
import com.clarklevis.dsh.android.ui.decodeWorkspaceLoadingLogo
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.clarklevis.dsh.shared.protocol.GatewayDirectoryItem
import java.util.UUID
import java.util.Locale
import org.junit.Rule
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.shared.gateway.gatewayAttachmentCacheKey
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.platform.GatewayAttachmentCache
import com.clarklevis.dsh.shared.platform.GatewayClock
import com.clarklevis.dsh.shared.platform.GatewayConnectionSpec
import com.clarklevis.dsh.shared.platform.GatewayCredentialStore
import com.clarklevis.dsh.shared.platform.GatewayNetworkMonitor
import com.clarklevis.dsh.shared.platform.GatewayNetworkState
import com.clarklevis.dsh.shared.platform.GatewayPreferences
import com.clarklevis.dsh.shared.platform.GatewayPreferencesSnapshot
import com.clarklevis.dsh.shared.platform.GatewayTransport
import com.clarklevis.dsh.shared.platform.GatewayTransportEvent
import com.clarklevis.dsh.shared.platform.GatewayTransportFrame
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAppGraphFakeIntegrationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun connectorStatusTraversesRuntimeAndMissingRegistrationIsNotReportedAsOffline() = runBlocking<Unit> {
        val transport = FakeTransport()
        val graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport, preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials, attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork, clockOverride = FakeClock,
            gatewayLocalId = "connectors-${UUID.randomUUID()}"
        )
        val holder = graph.stateHolder
        try {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive("""{"kind":"hello","authenticated":true,"protocol":3,"capabilities":["device-tools-v1"]}""")
            waitUntil { transport.payloadsOfType("device-tools-register").isNotEmpty() }
            val sent = org.json.JSONObject(transport.payloadsOfType("device-tools-register").last().toString())
            assertEquals("android", sent.getString("platform"))
            transport.receive(org.json.JSONObject().put("kind", "device-tools-registered").put("version", 1)
                .put("requestId", sent.getString("requestId")).put("capabilityRevision", sent.getInt("capabilityRevision"))
                .put("connectionLeaseId", "graph-lease").put("statusQuery", true).put("permissionSync", true)
                .put("permissions", org.json.JSONObject()).put("modules", sent.getJSONArray("modules")).toString())
            val connectors = requireNotNull(holder.deviceConnectors)
            waitUntil { connectors.registrationState == com.clarklevis.dsh.android.platform.devicetools.ConnectorRegistrationState.REGISTERED }
            onMain { connectors.visibility(true); connectors.refreshRegistrationStatus() }
            waitUntil { transport.payloadsOfType("device-tools-status").isNotEmpty() }
            val status = org.json.JSONObject(transport.payloadsOfType("device-tools-status").last().toString())
            transport.receive(org.json.JSONObject().put("kind", "device-tools-status").put("version", 1)
                .put("requestId", status.getString("requestId")).put("status", "not-registered").toString())
            waitUntil { connectors.registrationState == com.clarklevis.dsh.android.platform.devicetools.ConnectorRegistrationState.NOT_REGISTERED }
            assertTrue(connectors.connected)
            assertEquals("CONNECTED", holder.gatewayState.connection.name)
        } finally { onMain { holder.close() } }
    }

    @Test
    fun assistantMarkdownImageDownloadsThroughRuntimeWithoutDuplicatingToolImage() = runBlocking<Unit> {
        val transport = FakeTransport()
        val graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport, preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials, attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork, clockOverride = FakeClock,
            gatewayLocalId = "markdown-image-${UUID.randomUUID()}"
        )
        val holder = graph.stateHolder
        val png = tinyPng()
        try {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive("""{"kind":"hello","authenticated":true,"protocol":3,"historyFormatVersion":1,"capabilities":["file-downloads","file-references-v1"]}""")
            waitUntil { holder.gatewayState.connection.name == "CONNECTED" }
            transport.receive("""{"kind":"sessions","items":[{"sessionId":"markdown-body","title":"生成西湖风景图","updatedAt":1,"blank":false,"running":false}]}""")
            waitUntil { holder.snapshot.sessions.any { it.id == "markdown-body" } }
            onMain { holder.selectSession("markdown-body") }
            waitUntil { transport.payloadsOfType("history").isNotEmpty() }
            transport.receive("""{"kind":"history","sessionId":"markdown-body","historyFormatVersion":1,"events":[
                {"type":"user/message","seq":1,"time":1,"data":{"content":[{"type":"text","text":"生成西湖风景图"}],"source":{"kind":"user"}}},
                {"type":"tool/result","seq":2,"time":2,"data":{"turn":1,"step":1,"callId":"generate","meta":{"kind":"dsh-image-gen","savedTo":"images/lake.png","attachment":{"attachmentId":"generated-lake","mediaType":"image/png","bytes":${png.size},"width":2,"height":2}}}},
                {"type":"assistant/message","seq":3,"time":3,"data":{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"已为你画好。\n\n![瘦西湖春景](images/lake.png)"}]}}}
            ],"hasMore":false,"bytes":1000}""")
            waitUntil { holder.snapshot.markdownImages["images/lake.png"]?.width == 2 }
            compose.setContent { DshTheme { ConversationScreen(holder, onPickImage = {}, onBack = {}) } }
            waitUntil { transport.payloadsOfType("file-resolve").isNotEmpty() }
            val imageNode = compose.onNodeWithTag("markdown-image:images/lake.png")
            imageNode.assertIsDisplayed()
            val bounds = imageNode.fetchSemanticsNode().boundsInRoot
            val request = transport.payloadsOfType("file-resolve").last().getValue("requestId").jsonPrimitive.content
            transport.receive("""{"kind":"file-resolved","requestId":"$request","sessionId":"markdown-body","path":"images/lake.png","name":"lake.png","mediaType":"image/png","size":${png.size},"modifiedAt":1}""")
            waitUntil { transport.payloadsOfType("file-download-open").isNotEmpty() }
            transport.receive("""{"kind":"file-download-opened","requestId":"$request-download","sessionId":"markdown-body","transferId":"markdown-transfer","path":"images/lake.png","name":"lake.png","mediaType":"image/png","size":${png.size},"chunkBytes":${png.size}}""")
            waitUntil { transport.payloadsOfType("file-download-read").isNotEmpty() }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
            transport.receive("""{"kind":"file-download-chunk","transferId":"markdown-transfer","offset":0,"data":"${Base64.encodeToString(png, Base64.NO_WRAP)}","eof":true,"sha256":"$hash"}""")
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("瘦西湖春景").fetchSemanticsNodes().size == 1 }
            assertEquals(bounds, imageNode.fetchSemanticsNode().boundsInRoot)
            assertEquals(1, transport.payloadsOfType("file-download-open").size)
            assertTrue(holder.workspaceFilePreviewRequest == null && holder.completedWorkspaceFile == null)
            compose.onNodeWithTag("conversation-image:generated-lake").assertDoesNotExist()
            imageNode.performClick()
            compose.onNodeWithTag("conversation-image-preview").assertIsDisplayed()
        } finally { onMain { holder.close() } }
    }

    @Test
    fun previewAppearsBeforeDownloadAndBecomesCodeWithoutReopeningSheet() = runBlocking {
        withFilePreview { holder, transport ->
            onMain { holder.openWorkspaceFile(fileEntry(), "preview") }
            val request = requireNotNull(holder.workspaceFilePreviewRequest)
            compose.onNodeWithText("正在加载中").assertIsDisplayed()
            compose.onNodeWithText("0%").assertIsDisplayed()
            compose.onNodeWithTag("workspace-loading-logo").assertIsDisplayed()
            compose.onNodeWithTag("workspace-code-document").assertDoesNotExist()
            val loadingBounds = compose.onNodeWithTag("workspace-file-preview-sheet").fetchSemanticsNode().boundsInRoot
            completePreviewDownload(transport)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("workspace-code-document").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("hello").assertIsDisplayed()
            assertEquals(request.id, holder.workspaceFilePreviewRequest?.id)
            assertEquals(loadingBounds, compose.onNodeWithTag("workspace-file-preview-sheet").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("workspace-preview-loading").assertDoesNotExist()
            compose.onNodeWithContentDescription("关闭文件预览").performClick()
            compose.onNodeWithTag("workspace-file-preview-sheet").assertDoesNotExist()
            assertTrue(holder.workspaceFilePreviewRequest == null && holder.completedWorkspaceFile == null)
            assertEquals(0f, holder.workspaceFilePreviewProgress)
        }
    }

    @Test
    fun closingDuringResolveIgnoresLateResponsesAndAllowsReopen() = runBlocking {
        withFilePreview { holder, transport ->
            onMain { holder.openConversationFile(holder.gatewayLocalId, "android-demo", "a.txt") }
            waitUntil("transport.payloadsOfType(\"file-resolve\").isNotEmpty()") { transport.payloadsOfType("file-resolve").isNotEmpty() }
            val firstId = transport.payloadsOfType("file-resolve").last().getValue("requestId").jsonPrimitive.content
            compose.onNodeWithText("正在加载中").assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭文件预览").performClick()
            compose.onNodeWithTag("workspace-file-preview-sheet").assertDoesNotExist()
            assertTrue(transport.payloadsOfType("file-download-open").isEmpty())
            onMain { holder.openConversationFile(holder.gatewayLocalId, "android-demo", "a.txt") }
            compose.onNodeWithText("0%").assertIsDisplayed()
            transport.receive(resolvedFile(firstId))
            waitUntil("transport.payloadsOfType(\"file-resolve\").size == 2") { transport.payloadsOfType("file-resolve").size == 2 }
            assertTrue(firstId != transport.payloadsOfType("file-resolve").last().getValue("requestId").jsonPrimitive.content)
            completePreviewDownload(transport)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("workspace-code-document").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("hello").assertIsDisplayed()
        }
    }

    @Test
    fun closingDuringTransferCancelsReadsAndCacheHitStillUsesSamePreviewRequest() = runBlocking {
        withFilePreview { holder, transport ->
            onMain { holder.openWorkspaceFile(fileEntry(), "preview") }
            waitUntil("transport.payloadsOfType(\"file-resolve\").isNotEmpty()") { transport.payloadsOfType("file-resolve").isNotEmpty() }
            val requestId = transport.payloadsOfType("file-resolve").last().getValue("requestId").jsonPrimitive.content
            transport.receive(resolvedFile(requestId))
            waitUntil("transport.payloadsOfType(\"file-download-open\").isNotEmpty()") { transport.payloadsOfType("file-download-open").isNotEmpty() }
            transport.receive(openedFile("$requestId-download", "cancel-transfer"))
            waitUntil("transport.payloadsOfType(\"file-download-read\").isNotEmpty()") { transport.payloadsOfType("file-download-read").isNotEmpty() }
            transport.receive("""{"kind":"file-download-chunk","transferId":"cancel-transfer","offset":0,"data":"aGVs","eof":false}""")
            waitUntil("holder.workspaceFileDownloadProgress == 0.6f") { holder.workspaceFileDownloadProgress == 0.6f }
            compose.onNodeWithText("60%").assertIsDisplayed()
            assertEquals(0.6f, holder.workspaceFilePreviewProgress)
            compose.onNodeWithContentDescription("关闭文件预览").performClick()
            compose.onNodeWithTag("workspace-file-preview-sheet").assertDoesNotExist()
            waitUntil("transport.payloadsOfType(\"file-download-cancel\").isNotEmpty()") { transport.payloadsOfType("file-download-cancel").isNotEmpty() }
            val reads = transport.payloadsOfType("file-download-read").size
            transport.receive("""{"kind":"file-download-chunk","transferId":"cancel-transfer","offset":3,"data":"bG8=","eof":true,"sha256":"$HELLO_DIGEST"}""")
            compose.waitForIdle()
            assertTrue(holder.completedWorkspaceFile == null)
            assertEquals(reads, transport.payloadsOfType("file-download-read").size)
            compose.onNodeWithTag("workspace-file-preview-sheet").assertDoesNotExist()
            onMain { holder.openWorkspaceFile(fileEntry(), "preview") }
            waitUntil("transport.payloadsOfType(\"file-resolve\").size == 2") { transport.payloadsOfType("file-resolve").size == 2 }
            completePreviewDownload(transport)
            waitUntil("holder.completedWorkspaceFile != null") { holder.completedWorkspaceFile != null }
            compose.onNodeWithContentDescription("关闭文件预览").performClick()
            compose.onNodeWithTag("workspace-file-preview-sheet").assertDoesNotExist()
            val opens = transport.payloadsOfType("file-download-open").size
            val resolves = transport.payloadsOfType("file-resolve").size
            onMain { holder.openWorkspaceFile(fileEntry(), "preview") }
            val cachedRequest = requireNotNull(holder.workspaceFilePreviewRequest)
            compose.onNodeWithText("正在加载中").assertIsDisplayed()
            waitUntil("transport.payloadsOfType(\"file-resolve\").size > resolves") { transport.payloadsOfType("file-resolve").size > resolves }
            transport.receive(resolvedFile(transport.payloadsOfType("file-resolve").last().getValue("requestId").jsonPrimitive.content))
            waitUntil("holder.completedWorkspaceFile != null") { holder.completedWorkspaceFile != null }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("workspace-code-document").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(opens, transport.payloadsOfType("file-download-open").size)
            assertEquals(cachedRequest.id, holder.workspaceFilePreviewRequest?.id)
            assertEquals(1f, holder.workspaceFilePreviewProgress)
        }
    }

    @Test
    fun logoApngPreservesFramesTransparencyAndLoopTiming() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val data = resources.openRawResource(R.raw.whaleness_loading).use { it.readBytes() }
        val animation = decodeWorkspaceLoadingLogo(data)
        assertEquals(48, animation.frames.size)
        assertEquals(50, animation.frameMilliseconds)
        assertTrue(animation.frames.all { it.width == 192 && it.height == 192 })
        assertTrue(animation.frames.all { it.toPixelMap()[0, 0].alpha == 0f })
        val first = IntArray(192 * 192).also { animation.frames[0].readPixels(it) }
        val later = IntArray(192 * 192).also { animation.frames[12].readPixels(it) }
        assertTrue(!first.contentEquals(later))
        val corrupted = data.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { decodeWorkspaceLoadingLogo(corrupted) }
    }

    private suspend fun withFilePreview(block: suspend (AndroidSharedStateHolder, FakeTransport) -> Unit) {
        val originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val transport = FakeTransport()
        val graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport, preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials, attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork, clockOverride = FakeClock,
            gatewayLocalId = "preview-test-${UUID.randomUUID()}"
        )
        val holder = graph.stateHolder
        try {
            waitUntil("transport.specs.isNotEmpty()") { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive("""{"kind":"hello","authenticated":true,"protocol":3,"capabilities":["file-downloads","file-references-v1"]}""")
            waitUntil("holder.gatewayState.connection.name == \"CONNECTED\"") { holder.gatewayState.connection.name == "CONNECTED" }
            onMain { holder.loadFixture() }
            waitUntil("holder.snapshot.selectedSessionId == \"android-demo\"") { holder.snapshot.selectedSessionId == "android-demo" }
            compose.setContent { MaterialTheme { WorkspaceFilePreviewHost(holder) } }
            try {
                block(holder, transport)
            } catch (failure: Throwable) {
                throw AssertionError("预览状态：${holder.workspaceFilePreviewRequest}, opening=${holder.conversationFileIsOpening}, progress=${holder.workspaceFileDownloadProgress}, error=${holder.platformError}; 请求：${transport.sentTypes}", failure)
            }
        } finally {
            onMain { holder.close() }
            Locale.setDefault(originalLocale)
        }
    }

    private fun fileEntry() = GatewayDirectoryItem(name = "a.txt", path = "a.txt", kind = "file", bytes = 5)
    private fun resolvedFile(requestId: String) =
        """{"kind":"file-resolved","requestId":"$requestId","sessionId":"android-demo","path":"a.txt","name":"a.txt","mediaType":"text/plain","size":5,"modifiedAt":10.5}"""
    private fun openedFile(requestId: String, transferId: String) =
        """{"kind":"file-download-opened","requestId":"$requestId","transferId":"$transferId","sessionId":"android-demo","path":"a.txt","name":"a.txt","mediaType":"text/plain","size":5,"chunkBytes":100}"""

    private suspend fun completePreviewDownload(transport: FakeTransport) {
        waitUntil("transport.payloadsOfType(\"file-resolve\").isNotEmpty()") { transport.payloadsOfType("file-resolve").isNotEmpty() }
        val requestId = transport.payloadsOfType("file-resolve").last().getValue("requestId").jsonPrimitive.content
        transport.receive(resolvedFile(requestId))
        waitUntil("transport.payloadsOfType(\"file-download-open\").any { it.getValue(\"requestId\").jsonPrimitive.content == \"$requestId-download\" }") { transport.payloadsOfType("file-download-open").any { it.getValue("requestId").jsonPrimitive.content == "$requestId-download" } }
        val transferId = UUID.randomUUID().toString()
        transport.receive(openedFile("$requestId-download", transferId))
        waitUntil("transport.payloadsOfType(\"file-download-read\").any { it.getValue(\"transferId\").jsonPrimitive.content == transferId }") { transport.payloadsOfType("file-download-read").any { it.getValue("transferId").jsonPrimitive.content == transferId } }
        transport.receive("""{"kind":"file-download-chunk","transferId":"$transferId","offset":0,"data":"aGVsbG8=","eof":true,"sha256":"$HELLO_DIGEST"}""")
        waitUntil("transport.payloadsOfType(\"file-resolve\").any { it.getValue(\"requestId\").jsonPrimitive.content == \"$transferId-verify\" }") { transport.payloadsOfType("file-resolve").any { it.getValue("requestId").jsonPrimitive.content == "$transferId-verify" } }
        transport.receive(resolvedFile("$transferId-verify"))
    }

    private companion object {
        const val HELLO_DIGEST = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    }

    @Test
    fun runningComposerKeepsCircularBackgroundBeforeAndAfterStopRequest() = runBlocking<Unit> {
        val transport = FakeTransport()
        val graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock
        )
        val holder = graph.stateHolder
        try {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive("""{"kind":"hello","authenticated":true,"protocol":3,"capabilities":["session-cancel"]}""")
            waitUntil { holder.gatewayState.connection.name == "CONNECTED" }
            transport.receive(
                """{"kind":"sessions","items":[{
                    "sessionId":"stop-button","updatedAt":1,"running":true,"blank":false
                }]}"""
            )
            waitUntil("运行中的会话已载入") { holder.snapshot.sessions.any { it.id == "stop-button" } }
            onMain { holder.selectSession("stop-button") }
            waitUntil("运行中的会话显示停止按钮") { holder.showsSessionStopButton }
            compose.setContent {
                DshTheme { ConversationScreen(holder, onPickImage = {}, onBack = {}) }
            }
            val action = compose.onNodeWithTag("composer-action")
            val input = compose.onNodeWithTag("composer-input")
            input.assertIsNotFocused()
            action.assertIsDisplayed()
            assertTrue("收起时停止按钮不能覆盖文字输入区",
                input.fetchSemanticsNode().boundsInRoot.right < action.fetchSemanticsNode().boundsInRoot.left)
            val compactScreenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            graph.application.cacheDir.resolve("composer-compact-stop.png").outputStream().use {
                compactScreenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compactScreenshot.recycle()
            for (pending in listOf(false, true)) {
                if (pending) {
                    action.performClick()
                    waitUntil { !holder.canCancelSelectedSession }
                    action.assertIsNotEnabled()
                } else {
                    action.assertIsEnabled()
                }
                val image = action.captureToImage()
                val pixels = image.toPixelMap()
                for (x in listOf(1, image.width - 2)) {
                    for (y in listOf(1, image.height - 2)) {
                        val corner = pixels[x, y]
                        assertTrue("停止按钮四角不能出现蓝色方形背景，pending=$pending", corner.blue - corner.red < 0.1f)
                    }
                }
                val edge = pixels[image.width / 2, image.height / 8]
                assertTrue("圆形内部应保留蓝色背景", edge.blue - edge.red > 0.2f)
                val output = graph.application.cacheDir.resolve("composer-stop-$pending.png")
                output.outputStream().use {
                    image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            assertEquals(1, transport.payloadsOfType("session-cancel").size)
            assertEquals("stop-button", transport.payloadsOfType("session-cancel").single().getValue("sessionId").jsonPrimitive.content)
            transport.receive("""{"kind":"sessions","items":[{"sessionId":"stop-button","updatedAt":2,"running":false,"blank":false}]}""")
            waitUntil { !holder.showsSessionStopButton }
            action.assertIsNotDisplayed()
            input.assertIsNotFocused()
        } finally {
            onMain { holder.close() }
        }
    }

    @Test
    fun queueRoundTripKeepsDraftUntilAckAndEditResubmitsThenSteers() = runBlocking {
        val transport = FakeTransport()
        val graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport, preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials, attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork, clockOverride = FakeClock
        )
        val holder = graph.stateHolder
        try {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive("""{"kind":"hello","authenticated":true,"protocol":3,"capabilities":["queue-control","session-cancel"]}""")
            waitUntil { holder.gatewayState.connection.name == "CONNECTED" }
            onMain { holder.loadFixture() }
            waitUntil { holder.snapshot.selectedSessionId == "android-demo" }
            onMain { holder.messageDraft = "queue-original"; holder.sendMessage() }
            waitUntil { transport.sentTypes.contains("message") }
            assertEquals("queue-original", holder.messageDraft)
            assertTrue(!holder.canSend)
            assertEquals("queue", transport.payloadsOfType("message").last().getValue("mode").jsonPrimitive.content)
            transport.receive("""{"kind":"sent","sessionId":"android-demo","mode":"queue"}""")
            transport.receive("""{"kind":"session-queue","sessionId":"android-demo","items":[{"id":"q1","placement":"queued","message":{"id":"m1","content":[{"type":"text","text":"queue-original"}]}}]}""")
            waitUntil { holder.messageDraft.isEmpty() && holder.selectedQueueItems.size == 1 }
            onMain { holder.updateQueuedMessage("q1", "edit") }
            waitUntil { transport.payloadsOfType("queue-update").isNotEmpty() }
            assertEquals("remove", transport.payloadsOfType("queue-update").last().getValue("action").jsonPrimitive.content)
            onMain { holder.messageDraft = "existing-draft" }
            transport.receive("""{"kind":"queue-item-updated","sessionId":"android-demo","itemId":"q1","action":"remove","accepted":true}""")
            waitUntil { holder.messageDraft == "existing-draft\n\nqueue-original" }
            assertTrue(holder.selectedQueueItems.isEmpty())
            onMain { holder.messageDraft = "queue-edited"; holder.sendMessage() }
            waitUntil { transport.payloadsOfType("message").size == 2 }
            assertEquals("queue-edited", transport.payloadsOfType("message").last().getValue("text").jsonPrimitive.content)
            transport.receive("""{"kind":"sent","sessionId":"android-demo","mode":"queue"}""")
            transport.receive("""{"kind":"session-queue","sessionId":"android-demo","items":[{"id":"q2","placement":"queued","message":{"id":"m2","content":[{"type":"text","text":"queue-edited"}]}}]}""")
            waitUntil { holder.messageDraft.isEmpty() && holder.selectedQueueItems.firstOrNull()?.id == "q2" }
            onMain { holder.updateQueuedMessage("q2", "steer") }
            waitUntil { transport.payloadsOfType("queue-update").size == 2 }
            assertEquals("steer", transport.payloadsOfType("queue-update").last().getValue("action").jsonPrimitive.content)
            transport.receive("""{"kind":"queue-item-updated","sessionId":"android-demo","itemId":"q2","action":"steer","accepted":true}""")
            waitUntil { holder.selectedQueueItems.isEmpty() }
        } finally { onMain { holder.close() } }
    }

    @Test
    fun injectedProductGraphRunsRuntimeHolderProjectionHistoryAndVisibleAttachment() = runBlocking {
        val transport = FakeTransport()
        val cache = FakeCache()
        val decoderThreads = mutableListOf<Thread>()
        val graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = cache,
            gatewayLocalId = "history-window-${UUID.randomUUID()}",
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
            frameDecoderOverride = { raw ->
                synchronized(decoderThreads) { decoderThreads += Thread.currentThread() }
                GatewayWireDecoder.decode(raw)
            }
        )
        val holder = graph.stateHolder
        try {
            waitUntil("传输初始化") { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive("""{"kind":"hello","authenticated":true,"protocol":3,"historyFormatVersion":1}""")
            waitUntil("连接完成") { holder.gatewayState.connection.name == "CONNECTED" }
            transport.receive("""{"kind":"pong","message":"${"x".repeat(1_000_000)}"}""")
            waitUntil("大帧在后台解码") { synchronized(decoderThreads) { decoderThreads.size >= 2 } }
            assertTrue(synchronized(decoderThreads) { decoderThreads.all { it !== Looper.getMainLooper().thread } })

            onMain { holder.loadFixture() }
            val historyBefore = transport.sentTypes.count { it == "history" }
            onMain { holder.selectSession("android-demo") }
            waitUntil("首屏历史请求") { transport.sentTypes.count { it == "history" } > historyBefore }
            val initialHistoryRequest = transport.payloadsOfType("history").last()
            assertEquals(60, initialHistoryRequest.getValue("maxMessages").jsonPrimitive.int)
            assertEquals(4 * 1_024 * 1_024, initialHistoryRequest.getValue("maxBytes").jsonPrimitive.int)
            assertEquals("conversation", initialHistoryRequest.getValue("view").jsonPrimitive.content)
            transport.receive(
                """{"kind":"history","sessionId":"android-demo","historyFormatVersion":1,"events":[{"type":"user/message","seq":1,"time":1,"data":{"content":[{"type":"text","text":"product-history"}],"source":{"kind":"user"}}}],"hasMore":true,"nextBeforeSeq":0,"bytes":64}"""
            )
            waitUntil("首屏正文完成投影") { holder.snapshot.conversation.any { it.text == "product-history" } }
            delay(100)
            assertEquals(historyBefore + 1, transport.sentTypes.count { it == "history" })

            onMain { holder.loadOlderHistory() }
            waitUntil("较早历史请求") { transport.sentTypes.count { it == "history" } == historyBefore + 2 }
            val olderHistoryRequest = transport.payloadsOfType("history").last()
            assertEquals(1, olderHistoryRequest.getValue("beforeSeq").jsonPrimitive.int)
            assertEquals(120, olderHistoryRequest.getValue("maxMessages").jsonPrimitive.int)
            assertEquals("conversation", olderHistoryRequest.getValue("view").jsonPrimitive.content)
            transport.receive(
                """{"kind":"history","sessionId":"android-demo","historyFormatVersion":1,"events":[],"hasMore":false,"bytes":0}"""
            )
            waitUntil("较早历史请求结束") { !holder.snapshot.selectedHistoryIsLoading }

            assertTrue(graph.gatewayRuntime.requestHistory("android-demo"))
            waitUntil("主动历史刷新请求") { transport.sentTypes.count { it == "history" } >= historyBefore + 3 }
            transport.receive(
                """{"kind":"error","requestType":"history","sessionId":"android-demo","code":"synthetic"}"""
            )
            waitUntil("历史错误反馈") { holder.platformError?.startsWith("history:") == true }
            val beforeRetry = transport.sentTypes.count { it == "history" }
            assertTrue(graph.gatewayRuntime.requestHistory("android-demo"))
            waitUntil("历史错误后重试") { transport.sentTypes.count { it == "history" } > beforeRetry }

            val chunks = List(1_200) { "长" }
            chunks.forEachIndexed { index, text ->
                transport.receive(
                    """{"kind":"event","sessionId":"android-demo","seq":${index + 2},"time":${index + 2},"event":{"type":"assistant/chunk","turn":2,"step":1,"chunkType":"text-delta","text":"$text"}}"""
                )
            }
            val finalText = chunks.joinToString("")
            transport.receive(
                """{"kind":"event","sessionId":"android-demo","seq":1202,"time":1202,"event":{"type":"assistant/message","turn":2,"step":1,"text":"$finalText"}}"""
            )
            waitUntil("1200个增量归并为最终回复") {
                holder.snapshot.conversation.any { it.text == finalText && it.epochSeconds == 1202.0 }
            }
            // 最终回复沿用流式行ID以保持布局稳定，按最终事件水位及唯一正文验收。
            val finalReply = holder.snapshot.conversation.single { it.text == finalText }
            assertEquals(1202.0, finalReply.epochSeconds, 0.0)
            assertTrue(finalReply.id.startsWith("stream-"))

            val png = tinyPng()
            transport.receive(
                """{"kind":"event","sessionId":"android-demo","seq":1300,"time":1300,"event":{"type":"assistant/message","turn":2,"step":1,"text":"image","images":[{"attachmentId":"same-id","mediaType":"image/png","bytes":${png.size},"width":2,"height":2}]}}"""
            )
            waitUntil("带图片的消息投影") { holder.snapshot.conversation.any { item -> item.images.any { it.attachmentId == "same-id" } } }
            onMain { holder.updateVisibleAttachments(setOf("same-id")) }
            waitUntil("可见图片请求") { transport.sentTypes.lastOrNull() == "attachment" }
            transport.receive(
                """{"kind":"attachment","sessionId":"android-demo","attachment":{"attachmentId":"same-id","mediaType":"image/png","bytes":${png.size},"width":2,"height":2},"data":"${Base64.encodeToString(png, Base64.NO_WRAP)}"}"""
            )
            waitUntil("可见图片解码完成") { holder.attachmentStates["same-id"] == AttachmentLoadState.LOADED }
            assertTrue("same-id" in holder.attachmentThumbnails)
            assertTrue(gatewayAttachmentCacheKey("android-demo", "same-id") in cache.values)
        } finally {
            onMain { holder.close() }
        }
    }

    private suspend fun waitUntil(description: String = "状态更新", predicate: () -> Boolean) {
        try {
            withTimeout(5_000) {
                while (!predicate()) delay(20)
            }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("等待超时：$description", failure)
        }
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun tinyPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private class FakeTransport : GatewayTransport {
        private val mutableState = MutableStateFlow<GatewayTransportState>(GatewayTransportState.Closed())
        private val mutableEvents = MutableSharedFlow<GatewayTransportEvent>(extraBufferCapacity = 2_048)
        val specs = mutableListOf<GatewayConnectionSpec>()
        private val payloads = mutableListOf<String>()
        val sentTypes: List<String>
            get() = synchronized(payloads) {
                payloads.map { Json.parseToJsonElement(it).jsonObject.getValue("type").jsonPrimitive.content }
            }
        fun payloadsOfType(type: String) = synchronized(payloads) {
            payloads.map { Json.parseToJsonElement(it).jsonObject }
                .filter { it.getValue("type").jsonPrimitive.content == type }
        }
        override val state: StateFlow<GatewayTransportState> = mutableState
        override val events: Flow<GatewayTransportEvent> = mutableEvents

        override suspend fun open(spec: GatewayConnectionSpec) {
            synchronized(specs) { specs += spec }
            emit(GatewayTransportState.Opening(spec.generation))
        }

        override suspend fun send(text: String) {
            synchronized(payloads) { payloads += text }
        }

        override suspend fun close() {
            emit(GatewayTransportState.Closed(specs.lastOrNull()?.generation ?: 0))
        }

        fun open() = emit(GatewayTransportState.Open(specs.last().generation))

        fun receive(json: String) {
            check(
                mutableEvents.tryEmit(
                    GatewayTransportEvent.Frame(
                        GatewayTransportFrame(specs.last().generation, json, json.toByteArray().size)
                    )
                )
            )
        }

        private fun emit(value: GatewayTransportState) {
            mutableState.value = value
            check(mutableEvents.tryEmit(GatewayTransportEvent.State(value)))
        }
    }

    private class FakePreferences : GatewayPreferences {
        private val value = MutableStateFlow(
            GatewayPreferencesSnapshot(endpoint = "wss://gateway.example/ws/mobile")
        )
        override val snapshots: Flow<GatewayPreferencesSnapshot> = value
        override suspend fun load(): GatewayPreferencesSnapshot = value.value
        override suspend fun update(snapshot: GatewayPreferencesSnapshot) {
            value.value = snapshot
        }
    }

    private object FakeCredentials : GatewayCredentialStore {
        override suspend fun loadOrCreateDeviceId(): String = "device"
        override suspend fun loadToken(endpoint: String): String = "token"
        override suspend fun saveToken(endpoint: String, token: String) = Unit
        override suspend fun deleteToken(endpoint: String) = Unit
    }

    private class FakeCache : GatewayAttachmentCache {
        val values = mutableMapOf<String, ByteArray>()
        override suspend fun read(attachmentId: String): ByteArray? = values[attachmentId]
        override suspend fun write(attachmentId: String, bytes: ByteArray): Boolean {
            values[attachmentId] = bytes
            return true
        }
        override suspend fun removeExpired() = Unit
    }

    private object FakeNetwork : GatewayNetworkMonitor {
        override val state: StateFlow<GatewayNetworkState> =
            MutableStateFlow(GatewayNetworkState.AVAILABLE)
    }

    private object FakeClock : GatewayClock {
        override fun nowEpochMilliseconds(): Long = 0
        override suspend fun delay(milliseconds: Long) = kotlinx.coroutines.delay(milliseconds)
    }
}
