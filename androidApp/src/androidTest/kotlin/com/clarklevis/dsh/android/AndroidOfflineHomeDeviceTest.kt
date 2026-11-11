package com.clarklevis.dsh.android

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.platform.AndroidChatHistoryCache
import com.clarklevis.dsh.android.ui.ConversationScreen
import com.clarklevis.dsh.android.ui.DshTheme
import com.clarklevis.dsh.android.ui.WorkspaceScreen
import com.clarklevis.dsh.shared.facade.CachedHistoryWindow
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
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 真 Runtime + 模拟传输 + 真 Room + Compose，验证主页离线可读而非仅测试辅助筛选函数。 */
class AndroidOfflineHomeDeviceTest {
    @Test fun coldStartConnectsAutomaticallyAfterTunnelPrerequisiteWithoutButtonTap() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        withGraph(storedConnectionPrerequisite = {
            entered.complete(Unit)
            ready.await()
        }) { graph, transport ->
            withTimeout(5_000) { entered.await() }
            assertEquals(null, transport.generation)
            ready.complete(Unit)
            handshake(graph, transport)
            assertEquals("CONNECTED", graph.stateHolder.gatewayState.connection.name)
        }
    }

    @get:Rule val compose = createComposeRule()

    @get:Rule val locale = com.clarklevis.dsh.android.ui.ChineseDeviceLocaleRule()
    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private var animatorScale: String? = null

    @Before fun pauseBackgroundAnimationForDeterministicComposeIdling() {
        animatorScale = android.provider.Settings.Global.getString(application.contentResolver, "animator_duration_scale")
        shell("settings put global animator_duration_scale 0")
    }

    @After fun restoreAnimationSetting() {
        val scale = animatorScale
        shell(if (scale == null) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $scale")
    }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    @Test fun connectedHomeAndSearchRemainVisibleAfterDisconnectAndCachedRowStillOpens() = runBlocking {
        withGraph { graph, transport ->
            handshake(graph, transport)
            transport.receive(sessions)
            transport.receive(workspaces)
            waitFor { graph.stateHolder.snapshot.workspaces.size == 1 && graph.stateHolder.snapshot.sessions.size == 2 }
            onMain {
                graph.stateHolder.selectWorkspace("w")
                graph.stateHolder.selectSession("s")
            }
            waitFor { "s" in transport.historySessions() }
            transport.receive(history("s", "needle cached body"))
            waitFor { graph.stateHolder.snapshot.conversation.any { it.text == "needle cached body" } }
            waitFor {
                AndroidChatHistoryCache.shared(application).search(graph.gatewayLocalId, "needle", null, 50).contains("needle cached body")
            }
            var opened by mutableStateOf(false)
            compose.setContent {
                DshTheme {
                    if (opened) {
                        ConversationScreen(graph.stateHolder, onPickImage = {}, onBack = { opened = false })
                    } else {
                        WorkspaceScreen(graph.stateHolder, {
                            graph.stateHolder.selectSession(it)
                            opened = true
                        }, {}, {}, {}, {})
                    }
                }
            }
            compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("needle")
            compose.waitUntil(15_000) { graph.stateHolder.localChatMatches.isNotEmpty() }
            compose.onNodeWithTag("workspace-session-s").performScrollTo().assertIsDisplayed()
            val before = graph.stateHolder.snapshot
            val bounds = compose.onNodeWithTag("workspace-hero-title").fetchSemanticsNode().boundsInRoot
            graph.gatewayRuntime.disconnect()
            waitFor { graph.stateHolder.offlineBrowsing }
            transport.receive("""{"kind":"search","query":"needle","items":[]}""")
            delay(150)
            assertEquals(before.sessions, graph.stateHolder.snapshot.sessions)
            assertEquals(before.workspaces, graph.stateHolder.snapshot.workspaces)
            assertEquals(before.selectedSessionId, graph.stateHolder.snapshot.selectedSessionId)
            assertEquals("w", graph.stateHolder.selectedWorkspaceId)
            assertEquals(before.conversation, graph.stateHolder.snapshot.conversation)
            assertTrue(graph.stateHolder.localChatMatches.isNotEmpty())
            assertEquals(null, graph.stateHolder.remoteSearchResultQuery)
            assertEquals(bounds, compose.onNodeWithTag("workspace-hero-title").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithText("未连接", useUnmergedTree = true).assertExists()
            val requests = transport.payloads("history").size to transport.payloads("subscribe").size
            compose.onNodeWithTag("workspace-session-s").performScrollTo().performClick()
            compose.waitUntil(15_000) { graph.stateHolder.snapshot.conversation.any { it.text == "needle cached body" } }
            assertFalse(graph.stateHolder.snapshot.selectedHistoryIsLoading)
            assertEquals(requests, transport.payloads("history").size to transport.payloads("subscribe").size)
            assertEquals(null, graph.stateHolder.platformError)
        }
    }

    @Test fun coldOfflineCatalogRestoresWorkspaceAndSelectedBodyThenFreshEmptyAuthorityRemovesList() = runBlocking {
        val gatewayId = "offline-reopen-" + UUID.randomUUID()
        withGraph(gatewayId, clearOnClose = false) { graph, transport ->
            handshake(graph, transport)
            transport.receive(sessions)
            transport.receive(workspaces)
            waitFor { graph.stateHolder.snapshot.sessions.size == 2 && graph.stateHolder.availableWorkspaces.size == 1 }
            onMain {
                graph.stateHolder.selectWorkspace("w")
                graph.stateHolder.selectSession("s")
            }
            waitFor { "s" in transport.historySessions() }
            transport.receive(history("s", "offline reopened needle"))
            waitFor { graph.stateHolder.snapshot.conversation.isNotEmpty() }
            graph.gatewayRuntime.disconnect()
            waitFor {
                AndroidChatHistoryCache.shared(application).loadOfflineCatalog(gatewayId)?.contains("Offline kept title") == true &&
                    AndroidChatHistoryCache.shared(application).loadTypedPage(gatewayId, "s", null, 80) != null
            }
            delay(250)
        }
        withGraph(gatewayId, autoConnect = false) { graph, transport ->
            waitFor { graph.stateHolder.snapshot.sessions.size == 2 && graph.stateHolder.snapshot.conversation.isNotEmpty() }
            assertEquals("w", graph.stateHolder.selectedWorkspaceId)
            assertEquals("s", graph.stateHolder.snapshot.selectedSessionId)
            assertEquals("offline reopened needle", graph.stateHolder.snapshot.conversation.single().text)
            assertTrue(transport.payloads("history").isEmpty())
            assertTrue(transport.payloads("subscribe").isEmpty())
            onMain { graph.stateHolder.search("needle") }
            waitFor { graph.stateHolder.localChatMatches.isNotEmpty() }
            onMain { graph.stateHolder.connect() }
            handshake(graph, transport)
            transport.receive("""{"kind":"sessions","items":[]}""")
            transport.receive("""{"kind":"workspaces","items":[]}""")
            waitFor { graph.stateHolder.snapshot.sessions.isEmpty() && graph.stateHolder.snapshot.workspaces.isEmpty() }
            waitFor { AndroidChatHistoryCache.shared(application).loadOfflineCatalog(gatewayId)?.contains("Offline kept title") == false }
        }
    }

    @Test fun warmNetworkWindowCanPageCachedOlderOfflineAndStopsAtLocalBoundaryWithoutSending() = runBlocking {
        withGraph { graph, transport ->
            handshake(graph, transport)
            transport.receive(sessions)
            waitFor { graph.stateHolder.snapshot.sessions.size == 2 }
            onMain { graph.stateHolder.selectSession("s") }
            waitFor { "s" in transport.historySessions() }
            transport.receive(
                """{"kind":"history","sessionId":"s","historyFormatVersion":1,"events":[{"type":"user/message","seq":400,"time":400,"data":{"content":[{"type":"text","text":"latest"}]}}],"hasMore":true,"nextBeforeSeq":400}"""
            )
            waitFor { graph.stateHolder.snapshot.conversation.size == 1 }
            graph.gatewayRuntime.disconnect()
            waitFor { graph.stateHolder.offlineBrowsing }
            val cache = AndroidChatHistoryCache.shared(application)
            // 模拟此前已读过的本地窗口。network tail 不应阻断其离线分页。
            delay(200)
            cache.save(
                graph.gatewayLocalId,
                "s",
                Json.encodeToString(
                    CachedHistoryWindow(
                        sessionId = "s",
                        historyFormatVersion = 1,
                        events = (1..400).map { SessionEvent("s", it, it.toDouble(), GatewayEvent("user/message", text = "cached $it")) },
                        hasMore = true,
                        nextBeforeSequence = 1
                    )
                ),
                System.nanoTime()
            )
            val count = transport.payloads("history").size
            onMain { graph.stateHolder.loadOlderHistory() }
            waitFor { graph.stateHolder.snapshot.conversation.size == 161 }
            onMain { graph.stateHolder.loadOlderHistory() }
            waitFor { graph.stateHolder.snapshot.conversation.size == 321 }
            onMain { graph.stateHolder.loadOlderHistory() }
            waitFor { graph.stateHolder.snapshot.conversation.size == 400 }
            onMain { graph.stateHolder.loadOlderHistory() }
            waitFor { !graph.stateHolder.snapshot.selectedHistoryHasMore }
            assertFalse(graph.stateHolder.snapshot.selectedHistoryIsLoading)
            assertFalse(graph.stateHolder.snapshot.selectedHistoryIsLoadingOlder)
            assertEquals(count, transport.payloads("history").size)
            assertEquals("latest", graph.stateHolder.snapshot.conversation.last().text)
        }
    }

    @Test fun offlineUncachedSessionShowsExplanationWithoutSpinnerOrNetworkRequest() = runBlocking {
        withGraph(autoConnect = false) { graph, transport ->
            onMain {
                graph.stateHolder.wirePayload = sessions
                graph.stateHolder.submitWirePayload()
            }
            waitFor { graph.stateHolder.snapshot.sessions.size == 2 }
            onMain { graph.stateHolder.selectSession("uncached") }
            compose.setContent { DshTheme { ConversationScreen(graph.stateHolder, onPickImage = {}, onBack = {}) } }
            compose.waitUntil(15_000) { !graph.stateHolder.snapshot.selectedHistoryIsLoading }
            compose.onNodeWithTag("offline-history-unavailable").assertIsDisplayed()
            compose.onNodeWithTag("history-loading-overlay").assertDoesNotExist()
            assertTrue(transport.payloads("history").isEmpty())
            assertTrue(transport.payloads("subscribe").isEmpty())
            assertEquals(null, graph.stateHolder.platformError)
        }
    }

    @Test fun clearThenDisconnectAndColdReopenDoesNotRecreateOldBodyButKeepsCatalog() = runBlocking {
        val gatewayId = "offline-clear-" + UUID.randomUUID()
        withGraph(gatewayId, clearOnClose = false) { graph, transport ->
            handshake(graph, transport)
            transport.receive(sessions)
            waitFor { graph.stateHolder.snapshot.sessions.size == 2 }
            onMain { graph.stateHolder.selectSession("s") }
            waitFor { "s" in transport.historySessions() }
            transport.receive(history("s", "cleared old body"))
            waitFor { graph.stateHolder.snapshot.conversation.isNotEmpty() }
            waitFor { AndroidChatHistoryCache.shared(application).loadTypedPage(gatewayId, "s", null, 80) != null }
            onMain { graph.stateHolder.refreshCacheStorage(clear = true) }
            waitFor { !graph.stateHolder.cacheMaintenanceRunning }
            graph.gatewayRuntime.disconnect()
            waitFor { graph.stateHolder.offlineBrowsing }
            delay(250)
            assertEquals("cleared old body", graph.stateHolder.snapshot.conversation.single().text)
            assertEquals(null, AndroidChatHistoryCache.shared(application).loadTypedPage(gatewayId, "s", null, 80))
            waitFor { AndroidChatHistoryCache.shared(application).loadOfflineCatalog(gatewayId) != null }
        }
        withGraph(gatewayId, autoConnect = false) { graph, transport ->
            waitFor { graph.stateHolder.snapshot.sessions.size == 2 }
            delay(200)
            assertTrue(graph.stateHolder.snapshot.conversation.isEmpty())
            assertFalse(graph.stateHolder.snapshot.selectedHistoryIsLoading)
            assertTrue(transport.payloads("history").isEmpty())
            assertEquals(null, AndroidChatHistoryCache.shared(application).loadTypedPage(gatewayId, "s", null, 80))
        }
    }

    private suspend fun handshake(graph: AndroidAppGraph, transport: FakeTransport) {
        waitFor { transport.generation != null }
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true,"protocol":3,"historyFormatVersion":1}""")
        waitFor { graph.stateHolder.gatewayState.connection.name == "CONNECTED" }
    }

    private suspend fun withGraph(
        gatewayId: String = "offline-device-" + UUID.randomUUID(),
        autoConnect: Boolean = true,
        clearOnClose: Boolean = true,
        storedConnectionPrerequisite: suspend () -> Unit = {},
        block: suspend (AndroidAppGraph, FakeTransport) -> Unit
    ) {
        val transport = FakeTransport()
        val graph = AndroidAppGraph(
            application,
            transportOverride = transport,
            preferencesOverride = object : GatewayPreferences {
                private val value =
                    MutableStateFlow(
                        GatewayPreferencesSnapshot(endpoint = "wss://gateway.example/ws/mobile", automaticallyConnect = autoConnect)
                    )
                override val snapshots = value
                override suspend fun load() = value.value
                override suspend fun update(snapshot: GatewayPreferencesSnapshot) {
                    value.value = snapshot
                }
            },
            credentialStoreOverride = object : GatewayCredentialStore {
                override suspend fun loadOrCreateDeviceId() = "prefetch-test-device"
                override suspend fun loadToken(endpoint: String) = "token"
                override suspend fun saveToken(endpoint: String, token: String) = Unit
                override suspend fun deleteToken(endpoint: String) = Unit
            },
            networkMonitorOverride = object : GatewayNetworkMonitor {
                override val state = MutableStateFlow(GatewayNetworkState.AVAILABLE)
            },
            gatewayLocalId = gatewayId,
            storedConnectionPrerequisite = storedConnectionPrerequisite
        )
        graph.stateHolder
        try {
            block(graph, transport)
        } finally {
            onMain { graph.stateHolder.close() }
            graph.gatewayScope.cancel()
            graph.applicationScope.cancel()
            if (clearOnClose) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    AndroidChatHistoryCache.shared(application).clear(graph.gatewayLocalId)
                    AndroidChatHistoryCache.shared(application).forgetOfflineCatalog(graph.gatewayLocalId)
                }
            }
        }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private suspend fun waitFor(predicate: suspend () -> Boolean) = withTimeout(15_000) { while (!predicate()) delay(20) }

    private class FakeTransport : GatewayTransport {
        override val state = MutableStateFlow<GatewayTransportState>(GatewayTransportState.Closed())
        override val events = MutableSharedFlow<GatewayTransportEvent>(extraBufferCapacity = 128)

        @Volatile var generation: Long? = null
        private val sent = mutableListOf<String>()
        override suspend fun open(spec: GatewayConnectionSpec) {
            generation = spec.generation
            emit(GatewayTransportState.Opening(spec.generation))
        }
        override suspend fun send(text: String) {
            synchronized(sent) { sent += text }
        }
        override suspend fun close() {
            emit(GatewayTransportState.Closed(generation ?: 0))
        }
        fun opened() = emit(GatewayTransportState.Open(requireNotNull(generation)))
        fun receive(raw: String) {
            check(
                events.tryEmit(GatewayTransportEvent.Frame(GatewayTransportFrame(requireNotNull(generation), raw, raw.toByteArray().size)))
            )
        }
        fun payloads(type: String) = synchronized(sent) {
            sent.map { Json.parseToJsonElement(it).jsonObject }.filter { it["type"]?.jsonPrimitive?.content == type }
        }
        fun historySessions() = payloads("history").map { it.getValue("sessionId").jsonPrimitive.content }
        private fun emit(value: GatewayTransportState) {
            state.value = value
            check(events.tryEmit(GatewayTransportEvent.State(value)))
        }
    }

    companion object {
        private val sessions =
            """{"kind":"sessions","items":[{"sessionId":"s","projections":{"values":{"title":"Offline kept title"}},"updatedAt":100,"running":false,"blank":false},{"sessionId":"uncached","projections":{"values":{"title":"Uncached title"}},"updatedAt":1,"running":false,"blank":false}]}"""
        private val workspaces =
            """{"kind":"workspaces","items":[{"workspaceId":"w","path":"/offline/workspace","title":"Offline workspace","sessionIds":["s","uncached"],"createdAt":"now","updatedAt":"now"}]}"""
        private fun history(id: String, text: String) =
            """{"kind":"history","sessionId":"$id","historyFormatVersion":1,"events":[{"type":"user/message","seq":1,"time":1,"data":{"content":[{"type":"text","text":"$text"}]}}],"hasMore":false,"bytes":100}"""
    }
}
