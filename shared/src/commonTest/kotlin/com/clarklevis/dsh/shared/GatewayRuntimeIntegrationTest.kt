package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.gateway.GatewayOutgoingImage
import com.clarklevis.dsh.shared.gateway.GatewayPairingPayloadException
import com.clarklevis.dsh.shared.gateway.GatewayPairingPayloadParser
import com.clarklevis.dsh.shared.gateway.GatewayRuntime
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.gateway.gatewayAttachmentCacheKey
import com.clarklevis.dsh.shared.platform.GatewayAttachmentCache
import com.clarklevis.dsh.shared.platform.GatewayClock
import com.clarklevis.dsh.shared.platform.GatewayConnectionSpec
import com.clarklevis.dsh.shared.platform.GatewayCredentialStore
import com.clarklevis.dsh.shared.platform.GatewayNetworkMonitor
import com.clarklevis.dsh.shared.platform.GatewayNetworkState
import com.clarklevis.dsh.shared.platform.GatewayPreferences
import com.clarklevis.dsh.shared.platform.GatewayPreferencesSnapshot
import com.clarklevis.dsh.shared.platform.GatewayTransport
import com.clarklevis.dsh.shared.platform.GatewayTransportFrame
import com.clarklevis.dsh.shared.platform.GatewayTransportEvent
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAnswer
import com.clarklevis.dsh.shared.protocol.GatewayQuestion
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.RawSessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayRuntimeIntegrationTest {
    @Test
    fun fakeTransportCoversPairingCorrelationAttachmentAndReconnect() = runTest {
        val transport = FakeTransport()
        val preferences = FakePreferences()
        val credentials = FakeCredentials()
        val cache = FakeAttachmentCache()
        val network = FakeNetworkMonitor()
        val clock = FakeClock(now = 1_786_937_355_000)
        val runtime = GatewayRuntime(
            transport,
            preferences,
            credentials,
            cache,
            network,
            clock,
            backgroundScope
        )
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()

        val pairingPayload = encodeBase64Url(
            """{"version":2,"publicUrl":"wss://gateway.example/ws/mobile","pairingCode":"one-time","expiresAt":1786937455000}"""
        )
        runtime.pair(pairingPayload)
        runCurrent()
        assertEquals("one-time", transport.connectionSpecs.single().pairingCode)
        assertNull(transport.connectionSpecs.single().bearerToken)
        assertEquals("device-installation", transport.connectionSpecs.single().deviceId)

        transport.opened()
        transport.receive("""{"kind":"paired","token":"long-lived-secret","device":{"id":"d1"}}""")
        transport.receive(
            """{"kind":"hello","protocol":3,"capabilities":["images"],"authenticated":true,"port":3080,"clients":1}"""
        )
        runCurrent()
        assertEquals("long-lived-secret", credentials.tokens["wss://gateway.example/ws/mobile"])
        val pairedEvent = events.filterIsInstance<GatewayRuntimeEvent.Frame>().first { it.frame.kind == "paired" }
        assertNull(pairedEvent.frame.token)
        assertFalse("long-lived-secret" in pairedEvent.rawJson)
        assertFalse("long-lived-secret" in pairedEvent.toString())
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertEquals(setOf("images"), runtime.state.value.capabilities)
        assertEquals(listOf("workspaces", "sessions"), transport.sentTypes.takeLast(2))
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        val sessionsFrame = events.filterIsInstance<GatewayRuntimeEvent.Frame>().last()
        assertEquals("sessions", sessionsFrame.frame.kind)
        assertNull(sessionsFrame.correlatedSessionId)

        runtime.subscribe("session-1")
        runtime.requestHistory("session-1", maxBytes = 4 * 1_024 * 1_024, view = "mobile")
        transport.receive(
            """{"kind":"history","sessionId":"wrong-session","events":[],"hasMore":false,"bytes":0}"""
        )
        runCurrent()
        assertTrue(events.any {
            it is GatewayRuntimeEvent.RequestRejected &&
                it.requestType == "transport" && it.reason == "session-mismatch"
        })
        assertFalse(events.any {
            it is GatewayRuntimeEvent.Frame && it.frame.kind == "history"
        })

        transport.receive("""{"kind":"history","events":[],"hasMore":false,"bytes":0}""")
        runCurrent()
        val history = events.filterIsInstance<GatewayRuntimeEvent.Frame>().last()
        assertEquals("history", history.frame.kind)
        assertEquals("session-1", history.correlatedSessionId)

        runtime.requestAttachment("session-1", "attachment-1")
        transport.receive(
            """{"kind":"attachment","sessionId":"session-1","attachment":{"attachmentId":"attachment-1","mediaType":"image/png","bytes":3,"width":1,"height":1},"data":"AQID"}"""
        )
        runCurrent()
        assertTrue(
            cache.values[gatewayAttachmentCacheKey("session-1", "attachment-1")]!!
                .contentEquals(byteArrayOf(1, 2, 3))
        )
        val cachedFrame = events.filterIsInstance<GatewayRuntimeEvent.Frame>()
            .last { it.frame.kind == "attachment" }
        assertNull(cachedFrame.frame.data)
        assertFalse("AQID" in cachedFrame.rawJson)

        runtime.sendMessage(
            text = "hello",
            images = listOf(GatewayOutgoingImage("image/png", "AQID", "pixel.png")),
            sessionId = null,
            workspaceId = "workspace-1",
            clientTimeZone = "Asia/Shanghai"
        )
        assertTrue(runtime.state.value.hasUnassociatedTurn)
        transport.receive("""{"kind":"sent","sessionId":"session-new"}""")
        runCurrent()
        assertEquals(setOf("session-new"), runtime.state.value.activeTurnSessionIds)
        transport.receive(
            """{"sessionId":"session-new","seq":9,"time":1786937355,"event":{"type":"turn/end"}}"""
        )
        runCurrent()
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)

        network.mutableState.value = GatewayNetworkState.UNAVAILABLE
        runCurrent()
        assertEquals(GatewayConnectionState.WAITING_FOR_NETWORK, runtime.state.value.connection)
        network.mutableState.value = GatewayNetworkState.AVAILABLE
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)
        assertEquals("long-lived-secret", transport.connectionSpecs.last().bearerToken)
        transport.opened()
        transport.receive("""{"kind":"hello","protocol":3,"authenticated":true}""")
        runCurrent()
        assertEquals("subscribe", transport.sentTypes.last())
    }

    @Test
    fun pairingParserRejectsExpiredAndNonWebSocketPayloads() {
        val expired = encodeBase64Url(
            """{"version":2,"publicUrl":"wss://gateway.example/ws/mobile","pairingCode":"code","expiresAt":99}"""
        )
        assertFailsWith<GatewayPairingPayloadException> {
            GatewayPairingPayloadParser.parse(expired, nowEpochMilliseconds = 100)
        }
        val http = encodeBase64Url(
            """{"version":2,"publicUrl":"https://gateway.example/ws/mobile","pairingCode":"code","expiresAt":101}"""
        )
        assertFailsWith<GatewayPairingPayloadException> {
            GatewayPairingPayloadParser.parse(http, nowEpochMilliseconds = 100)
        }

        val description = GatewayConnectionSpec(
            generation = 1,
            endpoint = "wss://gateway.example/ws/mobile",
            deviceId = "private-device-id",
            bearerToken = "private-token",
            pairingCode = "private-pairing-code"
        ).toString()
        assertFalse("private-device-id" in description)
        assertFalse("private-token" in description)
        assertFalse("private-pairing-code" in description)
        val payloadDescription = GatewayPairingPayloadParser.parse(
            encodeBase64Url(
                """{"version":2,"publicUrl":"wss://gateway.example/ws/mobile","pairingCode":"private-code","expiresAt":101}"""
            ),
            nowEpochMilliseconds = 100
        ).toString()
        assertFalse("private-code" in payloadDescription)
        assertFalse("gateway.example" in payloadDescription)
        assertFailsWith<GatewayPairingPayloadException> {
            GatewayPairingPayloadParser.parse(
                encodeBase64Url(
                    """{"version":2,"publicUrl":"wss://user:secret@gateway.example/ws/mobile","pairingCode":"code","expiresAt":101}"""
                ),
                nowEpochMilliseconds = 100
            )
        }
        val image = GatewayOutgoingImage("image/png", "private-base64", "private-name.png")
        val outgoingDescription = image.toString() + GatewayRequests.message(
            text = "private-message",
            images = listOf(image),
            sessionId = "session-a",
            workspaceId = null,
            clientTimeZone = "UTC"
        ).toString()
        assertFalse("private-base64" in outgoingDescription)
        assertFalse("private-name" in outgoingDescription)
        assertFalse("private-message" in outgoingDescription)
        val questionDescription = GatewayQuestionAnswer(
            id = "q1",
            selected = listOf("private-selection"),
            custom = "private-custom"
        ).toString()
        assertFalse("private-selection" in questionDescription)
        assertFalse("private-custom" in questionDescription)
        assertFalse(
            "private-question" in GatewayQuestion(
                id = "q1",
                question = "private-question",
                detail = "private-detail"
            ).toString()
        )
        assertFalse(
            "private-event" in GatewayEvent(
                type = "assistant/message",
                text = "private-event",
                raw = JsonValue.StringValue("private-raw")
            ).toString()
        )
        assertFalse(
            "private-history" in RawSessionEvent(
                type = "user/message",
                seq = 1,
                time = 1.0,
                data = JsonValue.StringValue("private-history")
            ).toString()
        )
        assertFalse(
            "private-frame" in GatewayTransportFrame(1, "private-frame", 13).toString()
        )
    }

    @Test
    fun sameKindUsesActiveThenLatestQueuedWithoutCrossSessionOverwrite() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()

        runtime.requestHistory("session-a")
        runtime.requestHistory("session-b")
        runtime.requestHistory("session-c")
        runCurrent()
        assertEquals(1, transport.sentTypes.count { it == "history" })
        assertTrue(events.any {
            it is GatewayRuntimeEvent.RequestCancelled &&
                it.targetSessionId == "session-b" && it.reason == "request-coalesced"
        })
        transport.receive("""{"kind":"history","events":[],"hasMore":false,"bytes":0}""")
        runCurrent()
        assertEquals(
            "session-a",
            events.filterIsInstance<GatewayRuntimeEvent.Frame>().last { it.frame.kind == "history" }
                .correlatedSessionId
        )
        assertEquals(1, transport.connectionSpecs.size)
        assertEquals(0, transport.closeCount)
        assertEquals(2, transport.sentTypes.count { it == "history" })
        transport.receive("""{"kind":"history","events":[],"hasMore":false,"bytes":0}""")
        runCurrent()
        assertEquals(
            "session-c",
            events.filterIsInstance<GatewayRuntimeEvent.Frame>().last { it.frame.kind == "history" }
                .correlatedSessionId
        )
    }

    @Test
    fun duplicateBootstrapSnapshotsCompleteSequentiallyWithoutReconnectLoop() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()

        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()

        // hello 已发送 workspaces/sessions；Android 页面进入 CONNECTED 后还会请求一次产品快照。
        runtime.sendRequest(GatewayRequests.simple("workspaces"))
        runtime.requestSessions()
        runtime.sendRequest(GatewayRequests.simple("default-model"))
        runtime.sendRequest(GatewayRequests.simple("default-model"))
        runCurrent()

        transport.receive("""{"kind":"workspaces","items":[]}""")
        transport.receive("""{"kind":"sessions","items":[]}""")
        transport.receive("""{"kind":"default-model","provider":"deepseek-official","model":"deepseek-v4-flash-vision-exp","reasoningEffort":"low"}""")
        runCurrent()
        assertEquals(2, transport.sentTypes.count { it == "workspaces" })
        assertEquals(2, transport.sentTypes.count { it == "sessions" })
        assertEquals(2, transport.sentTypes.count { it == "default-model" })

        transport.receive("""{"kind":"workspaces","items":[]}""")
        transport.receive("""{"kind":"sessions","items":[]}""")
        transport.receive("""{"kind":"default-model","provider":"deepseek-official","model":"deepseek-v4-flash-vision-exp","reasoningEffort":"low"}""")
        runCurrent()

        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertEquals(1, transport.connectionSpecs.size)
        assertEquals(0, transport.closeCount)
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.RequestCancelled>().any {
            it.reason == "connection-recycled"
        })
    }

    @Test
    fun authenticationFailureBlocksAutomaticReconnectUntilNewIntent() = runTest {
        val transport = FakeTransport()
        val network = FakeNetworkMonitor()
        val runtime = newRuntime(transport, network = network)
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        val failedGeneration = transport.connectionSpecs.last().generation
        transport.fail(
            GatewayTransportState.Failed(
                generation = failedGeneration,
                httpStatus = 401,
                recoverable = false
            )
        )
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        network.mutableState.value = GatewayNetworkState.UNAVAILABLE
        network.mutableState.value = GatewayNetworkState.AVAILABLE
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)
        runtime.connect("wss://gateway.example/ws/mobile")
        assertEquals(2, transport.connectionSpecs.size)
    }

    @Test
    fun messageErrorValidatesExplicitSessionAndInvalidAttachmentNeverEmitsFrame() = runTest {
        val transport = FakeTransport()
        val cache = FakeAttachmentCache()
        val runtime = newRuntime(transport, cache = cache)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()

        assertFalse(
            runtime.sendMessage(
                "",
                List(5) { GatewayOutgoingImage("image/png", "AQID") },
                "session-a",
                null,
                "UTC"
            )
        )
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)

        runtime.sendMessage("hello", emptyList(), "session-a", null, "UTC")
        transport.receive("""{"kind":"error","requestType":"message","sessionId":"session-b"}""")
        runCurrent()
        assertEquals(setOf("session-a"), runtime.state.value.activeTurnSessionIds)
        transport.receive("""{"kind":"error","requestType":"message","sessionId":"session-a"}""")
        runCurrent()
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)

        runtime.requestAttachment("session-a", "bad")
        transport.receive(
            """{"kind":"attachment","sessionId":"session-a","attachment":{"attachmentId":"bad","mediaType":"image/png","bytes":4,"width":1,"height":1},"data":"AQID"}"""
        )
        runCurrent()
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.kind == "attachment" })
        assertTrue(events.any { it is GatewayRuntimeEvent.RequestRejected && it.reason == "attachment-invalid" })
    }

    @Test
    fun staleGenerationCannotCompleteNewConnectionRequest() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        val oldGeneration = transport.connectionSpecs.last().generation
        runtime.disconnect()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        runtime.requestHistory("session-new")
        transport.receiveAt(
            oldGeneration,
            """{"kind":"history","events":[],"hasMore":false,"bytes":0}"""
        )
        runCurrent()
        assertTrue(events.any { it is GatewayRuntimeEvent.RequestRejected && it.reason == "stale-frame" })
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.kind == "history" })
    }

    @Test
    fun nonIdempotentRequestsRejectWhileBusyAndKeepTurnAccountingExact() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()

        transport.failNextSend = true
        assertFalse(runtime.sendMessage("failed", emptyList(), "session-a", null, "UTC"))
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
        assertTrue(runtime.sendMessage("one", emptyList(), "session-a", null, "UTC"))
        assertFalse(runtime.sendMessage("two", emptyList(), "session-a", null, "UTC"))
        assertFalse(runtime.sendMessage("three", emptyList(), "session-a", null, "UTC"))
        assertEquals(1, transport.sentTypes.count { it == "message" })
        assertEquals(setOf("session-a"), runtime.state.value.activeTurnSessionIds)
        transport.receive("""{"kind":"error","requestType":"message","sessionId":"session-a"}""")
        runCurrent()
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)

        val answer = listOf(GatewayQuestionAnswer(id = "q1", selected = listOf("yes")))
        assertTrue(runtime.answerQuestion("rpc-1", "session-a", answer))
        assertFalse(runtime.answerQuestion("rpc-2", "session-a", answer))
        assertFalse(runtime.cancelQuestion("rpc-3", "session-a"))
        assertEquals(1, transport.sentTypes.count { it == "question-answer" })
        transport.receive("""{"kind":"question-response","rpcId":"wrong","sessionId":"session-a"}""")
        runCurrent()
        assertTrue(runtime.state.value.connection == GatewayConnectionState.CONNECTED)
        transport.receive("""{"kind":"question-response","rpcId":"rpc-1","sessionId":"session-a"}""")
        runCurrent()
    }

    @Test
    fun timeoutCancelsActiveAdvancesLatestAndRejectsLateGeneration() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport, requestTimeoutMilliseconds = 1_000)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        transport.receive("""{"kind":"workspaces","items":[]}""")
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        runtime.requestHistory("session-a")
        runtime.requestHistory("session-b")
        val oldGeneration = transport.connectionSpecs.last().generation

        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(events.any {
            it is GatewayRuntimeEvent.RequestTimedOut && it.targetSessionId == "session-a"
        })
        assertEquals(2, transport.connectionSpecs.size)
        transport.receiveAt(oldGeneration, """{"kind":"history","events":[],"hasMore":false,"bytes":0}""")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertEquals(2, transport.sentTypes.count { it == "history" })
        transport.receive("""{"kind":"history","events":[],"hasMore":false,"bytes":0}""")
        runCurrent()
        assertEquals(
            "session-b",
            events.filterIsInstance<GatewayRuntimeEvent.Frame>().last { it.frame.kind == "history" }
                .correlatedSessionId
        )
    }

    @Test
    fun backgroundMessageTimeoutSuspendsIdleConnectionAndForegroundReconnects() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport, requestTimeoutMilliseconds = 1_000)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        transport.receive("""{"kind":"workspaces","items":[]}""")
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertTrue(runtime.sendMessage("timeout", emptyList(), "session-a", null, "UTC"))
        runtime.applicationDidEnterBackground()
        val closeBefore = transport.closeCount
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(events.any {
            it is GatewayRuntimeEvent.RequestTimedOut && it.requestType == "message" &&
                it.targetSessionId == "session-a"
        })
        assertEquals(GatewayConnectionState.SUSPENDED, runtime.state.value.connection)
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
        assertTrue(transport.closeCount > closeBefore)

        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)
        assertEquals(GatewayConnectionState.CONNECTING, runtime.state.value.connection)
    }

    @Test
    fun activeTurnSurvivesBackgroundNetworkRecoveryUntilTurnEnd() = runTest {
        val transport = FakeTransport()
        val network = FakeNetworkMonitor()
        val runtime = newRuntime(transport, network = network, recoveryWindowMilliseconds = 5_000)
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertTrue(runtime.sendMessage("work", emptyList(), "session-a", null, "UTC"))
        runtime.applicationDidEnterBackground()
        network.mutableState.value = GatewayNetworkState.UNAVAILABLE
        runCurrent()
        assertEquals(GatewayConnectionState.WAITING_FOR_NETWORK, runtime.state.value.connection)
        assertEquals(setOf("session-a"), runtime.state.value.activeTurnSessionIds)

        network.mutableState.value = GatewayNetworkState.AVAILABLE
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        transport.receive(
            """{"sessionId":"session-a","seq":4,"time":1,"event":{"type":"turn/end"}}"""
        )
        runCurrent()
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
    }

    @Test
    fun activeTurnRecoveryWindowExpiresFailClosed() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport, recoveryWindowMilliseconds = 1_000)
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertTrue(runtime.sendMessage("work", emptyList(), "session-a", null, "UTC"))
        transport.fail(
            GatewayTransportState.Failed(
                generation = transport.connectionSpecs.last().generation,
                reason = "websocket-failure",
                recoverable = true
            )
        )
        runCurrent()
        val expiredGeneration = transport.connectionSpecs.last().generation
        val closesBeforeDeadline = transport.closeCount
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        assertEquals("recovery-timeout", runtime.state.value.lastError)
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
        assertTrue(transport.closeCount > closesBeforeDeadline)
        transport.receiveAt(expiredGeneration, """{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
    }

    @Test
    fun helloRecoveryDeduplicatesSubscribeAndSessionsWithoutReconnectLoop() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        transport.receive("""{"kind":"workspaces","items":[]}""")
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()

        assertTrue(runtime.subscribe("session-a"))
        assertTrue(runtime.subscribe("session-b"))
        transport.receive("""{"kind":"subscribed"}""")
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)
        assertEquals(2, transport.sentTypes.count { it == "subscribe" })
        transport.receive("""{"kind":"subscribed"}""")
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)

        assertTrue(runtime.requestSessions())
        assertTrue(runtime.requestSessions())
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)
        assertEquals(3, transport.sentTypes.count { it == "sessions" })
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)
        assertEquals(0, transport.closeCount)
    }

    @Test
    fun storedConnectIsIdempotentWhileConnectingAndConnected() = runTest {
        val transport = FakeTransport()
        val preferences = FakePreferences()
        val credentials = FakeCredentials().apply {
            tokens["ws://127.0.0.1:3080/ws/mobile"] = "stored-token"
        }
        val runtime = GatewayRuntime(
            transport,
            preferences,
            credentials,
            FakeAttachmentCache(),
            FakeNetworkMonitor(),
            FakeClock(0),
            backgroundScope
        )
        runCurrent()
        assertTrue(runtime.connectStoredIfPaired())
        assertTrue(runtime.connectStoredIfPaired())
        assertEquals(1, transport.connectionSpecs.size)
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertTrue(runtime.sendMessage("active turn", emptyList(), "session-a", null, "UTC"))
        assertTrue(runtime.connectStoredIfPaired())
        assertEquals(1, transport.connectionSpecs.size)
        assertEquals(setOf("session-a"), runtime.state.value.activeTurnSessionIds)
    }

    @Test
    fun attachmentFifoDeliversThreeItemsAndIgnoresDuplicateTerminal() = runTest {
        val transport = FakeTransport()
        val cache = FakeAttachmentCache()
        val runtime = newRuntime(transport, cache = cache)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertTrue(runtime.requestAttachment("session-a", "a"))
        assertTrue(runtime.requestAttachment("session-a", "b"))
        assertTrue(runtime.requestAttachment("session-a", "c"))
        assertEquals(1, transport.sentTypes.count { it == "attachment" })
        transport.receive(attachmentFrame("a"))
        runCurrent()
        assertEquals(2, transport.sentTypes.count { it == "attachment" })
        transport.receive(attachmentFrame("b"))
        runCurrent()
        assertEquals(3, transport.sentTypes.count { it == "attachment" })
        transport.receive(attachmentFrame("c"))
        transport.receive("""{"kind":"error","requestType":"attachment","sessionId":"session-a"}""")
        runCurrent()
        assertEquals(
            setOf("a", "b", "c").mapTo(mutableSetOf()) {
                gatewayAttachmentCacheKey("session-a", it)
            },
            cache.values.keys
        )
        assertEquals(
            listOf("a", "b", "c"),
            events.filterIsInstance<GatewayRuntimeEvent.AttachmentCached>().map { it.attachmentId }
        )
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any {
            it.requestType == "attachment" && it.correlationId in setOf("a", "b", "c")
        })
    }

    @Test
    fun everyHistoryFailureCarriesSessionSoLoadingCanTerminateAndRetry() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        assertFalse(runtime.requestHistory("offline-session"))
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any {
            it.requestType == "history" && it.targetSessionId == "offline-session"
        })

        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertTrue(runtime.requestHistory("session-a"))
        transport.receive("""{"kind":"error","requestType":"history","sessionId":"session-a"}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any {
            it.requestType == "history" && it.targetSessionId == "session-a"
        })
        assertTrue(runtime.requestHistory("session-a"))
    }

    @Test
    fun orderedFailureInvalidatesGenerationBeforeLaterBufferedHello() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        val generation = transport.connectionSpecs.single().generation
        transport.failThenReceive(
            GatewayTransportState.Failed(generation, reason = "incoming-overflow", recoverable = true),
            generation,
            """{"kind":"hello","authenticated":true}"""
        )
        runCurrent()
        assertTrue(runtime.state.value.connection != GatewayConnectionState.CONNECTED)
        assertTrue(events.any { it is GatewayRuntimeEvent.RequestRejected && it.reason == "stale-frame" })
    }

    @Test
    fun attachmentResponseMustMatchActiveAttachmentIdAndUnknownErrorReleasesTurn() = runTest {
        val transport = FakeTransport()
        val cache = FakeAttachmentCache()
        val runtime = newRuntime(transport, cache = cache)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()

        runtime.requestAttachment("session-a", "attachment-a")
        transport.receive(
            """{"kind":"attachment","sessionId":"session-a","attachment":{"attachmentId":"attachment-b","mediaType":"image/png","bytes":3,"width":1,"height":1},"data":"AQID"}"""
        )
        runCurrent()
        assertTrue(cache.values.isEmpty())
        transport.receive(
            """{"kind":"attachment","sessionId":"session-a","attachment":{"attachmentId":"attachment-a","mediaType":"image/png","bytes":3,"width":1,"height":1},"data":"AQID"}"""
        )
        runCurrent()
        assertTrue(
            cache.values.containsKey(gatewayAttachmentCacheKey("session-a", "attachment-a")),
            "cache=${cache.values.keys}, events=$events"
        )

        assertTrue(runtime.requestAttachment("session-a", "attachment-c"))
        assertTrue(runtime.requestAttachment("session-a", "attachment-d"))
        transport.receive("""{"kind":"error","requestType":"attachment","sessionId":"session-a"}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestCancelled>().any {
            it.requestType == "attachment" && it.targetSessionId == "session-a"
        })
        assertTrue(runtime.requestAttachment("session-a", "attachment-e"))

        runtime.sendMessage("work", emptyList(), "session-a", null, "UTC")
        transport.receive("""{"kind":"error","message":"server rejected request"}""")
        runCurrent()
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
    }

    @Test
    fun backgroundLastTurnEndClosesImmediatelyAndStoredConnectKeepsWaitingDeadline() = runTest {
        val transport = FakeTransport()
        val network = FakeNetworkMonitor()
        val runtime = newRuntime(
            transport,
            network = network,
            recoveryWindowMilliseconds = 1_000
        )
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertTrue(runtime.sendMessage("active", emptyList(), "session-a", null, "UTC"))
        runtime.applicationDidEnterBackground()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        val closeBeforeEnd = transport.closeCount
        transport.receive(
            """{"sessionId":"session-a","seq":1,"time":1,"event":{"type":"turn/end"}}"""
        )
        runCurrent()
        assertEquals(GatewayConnectionState.SUSPENDED, runtime.state.value.connection)
        assertTrue(transport.closeCount > closeBeforeEnd)

        runtime.applicationDidBecomeActive()
        runCurrent()
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertTrue(runtime.sendMessage("recover", emptyList(), "session-a", null, "UTC"))
        network.mutableState.value = GatewayNetworkState.UNAVAILABLE
        runCurrent()
        assertEquals(GatewayConnectionState.WAITING_FOR_NETWORK, runtime.state.value.connection)
        val specCount = transport.connectionSpecs.size
        assertTrue(runtime.connectStoredIfPaired())
        assertEquals(specCount, transport.connectionSpecs.size)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        assertEquals("recovery-timeout", runtime.state.value.lastError)
    }

    @Test
    fun credentialFailureFromConnectedClosesOldTransportBeforeFailedState() = runTest {
        val transport = FakeTransport()
        val credentials = FakeCredentials()
        val runtime = newRuntime(transport, credentials = credentials)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        val closeBefore = transport.closeCount
        credentials.failDeviceRead = true
        runtime.connect("wss://other.example/ws/mobile")
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        assertEquals("credential-access-failed", runtime.state.value.lastError)
        assertTrue(transport.closeCount > closeBefore)
    }

    @Test
    fun sameAttachmentIdInDifferentSessionsUsesDifferentCacheEntries() = runTest {
        val transport = FakeTransport()
        val cache = FakeAttachmentCache()
        val runtime = newRuntime(transport, cache = cache)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        runtime.requestAttachment("session-a", "same")
        transport.receive(attachmentFrame("same", "session-a"))
        runCurrent()
        runtime.requestAttachment("session-b", "same")
        transport.receive(attachmentFrame("same", "session-b"))
        runCurrent()
        assertEquals(
            setOf(
                gatewayAttachmentCacheKey("session-a", "same"),
                gatewayAttachmentCacheKey("session-b", "same")
            ),
            cache.values.keys
        )
    }

    private fun kotlinx.coroutines.test.TestScope.newRuntime(
        transport: FakeTransport,
        network: FakeNetworkMonitor = FakeNetworkMonitor(),
        cache: FakeAttachmentCache = FakeAttachmentCache(),
        credentials: FakeCredentials = FakeCredentials(),
        requestTimeoutMilliseconds: Long = 30_000,
        recoveryWindowMilliseconds: Long = 60_000
    ): GatewayRuntime = GatewayRuntime(
        transport,
        FakePreferences(),
        credentials,
        cache,
        network,
        FakeClock(0),
        backgroundScope,
        requestTimeoutMilliseconds,
        recoveryWindowMilliseconds
    )

    private class FakeTransport : GatewayTransport {
        private val mutableTransportState = MutableStateFlow<GatewayTransportState>(GatewayTransportState.Closed())
        private val mutableEvents = MutableSharedFlow<GatewayTransportEvent>(extraBufferCapacity = 64)
        val connectionSpecs = mutableListOf<GatewayConnectionSpec>()
        val sentPayloads = mutableListOf<String>()
        var failNextSend = false
        var closeCount = 0

        override val state: StateFlow<GatewayTransportState> = mutableTransportState.asStateFlow()
        override val events: Flow<GatewayTransportEvent> = mutableEvents.asSharedFlow()

        val sentTypes: List<String>
            get() = sentPayloads.map {
                wireJson.parseToJsonElement(it).jsonObject.getValue("type").jsonPrimitive.content
            }

        override suspend fun open(spec: GatewayConnectionSpec) {
            connectionSpecs += spec
            emitState(GatewayTransportState.Opening(spec.generation))
        }

        override suspend fun send(text: String) {
            if (failNextSend) {
                failNextSend = false
                throw IllegalStateException("synthetic-send-failure")
            }
            sentPayloads += text
        }

        override suspend fun close() {
            closeCount += 1
            emitState(GatewayTransportState.Closed(connectionSpecs.lastOrNull()?.generation ?: 0))
        }

        fun opened() {
            emitState(GatewayTransportState.Open(connectionSpecs.last().generation))
        }

        fun receive(json: String) {
            receiveAt(connectionSpecs.last().generation, json)
        }

        fun receiveAt(generation: Long, json: String) {
            assertTrue(
                mutableEvents.tryEmit(
                    GatewayTransportEvent.Frame(
                        GatewayTransportFrame(generation, json, json.encodeToByteArray().size)
                    )
                )
            )
        }

        fun fail(failure: GatewayTransportState.Failed) {
            emitState(failure)
        }

        fun failThenReceive(failure: GatewayTransportState.Failed, generation: Long, json: String) {
            emitState(failure)
            receiveAt(generation, json)
        }

        private fun emitState(value: GatewayTransportState) {
            mutableTransportState.value = value
            assertTrue(mutableEvents.tryEmit(GatewayTransportEvent.State(value)))
        }
    }

    private class FakePreferences : GatewayPreferences {
        private val mutable = MutableStateFlow(GatewayPreferencesSnapshot())
        override val snapshots: Flow<GatewayPreferencesSnapshot> = mutable
        override suspend fun load(): GatewayPreferencesSnapshot = mutable.value
        override suspend fun update(snapshot: GatewayPreferencesSnapshot) {
            mutable.value = snapshot
        }
    }

    private class FakeCredentials : GatewayCredentialStore {
        val tokens = mutableMapOf<String, String>()
        var failDeviceRead = false
        override suspend fun loadOrCreateDeviceId(): String {
            if (failDeviceRead) error("synthetic credential failure")
            return "device-installation"
        }
        override suspend fun loadToken(endpoint: String): String? = tokens[endpoint]
        override suspend fun saveToken(endpoint: String, token: String) {
            tokens[endpoint] = token
        }
        override suspend fun deleteToken(endpoint: String) {
            tokens.remove(endpoint)
        }
    }

    private class FakeAttachmentCache : GatewayAttachmentCache {
        val values = mutableMapOf<String, ByteArray>()
        override suspend fun read(attachmentId: String): ByteArray? = values[attachmentId]
        override suspend fun write(attachmentId: String, bytes: ByteArray): Boolean {
            values[attachmentId] = bytes
            return true
        }
        override suspend fun removeExpired() = Unit
    }

    private class FakeNetworkMonitor : GatewayNetworkMonitor {
        val mutableState = MutableStateFlow(GatewayNetworkState.AVAILABLE)
        override val state: StateFlow<GatewayNetworkState> = mutableState.asStateFlow()
    }

    private class FakeClock(private val now: Long) : GatewayClock {
        override fun nowEpochMilliseconds(): Long = now
        override suspend fun delay(milliseconds: Long) = Unit
    }

    companion object {
        private const val BASE64_URL_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        private fun encodeBase64Url(value: String): String {
            val bytes = value.encodeToByteArray()
            val result = StringBuilder((bytes.size * 4 + 2) / 3)
            var index = 0
            while (index < bytes.size) {
                val first = bytes[index++].toInt() and 0xff
                val second = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
                val third = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
                result.append(BASE64_URL_ALPHABET[first shr 2])
                result.append(BASE64_URL_ALPHABET[((first and 0x03) shl 4) or if (second >= 0) second shr 4 else 0])
                if (second >= 0) {
                    result.append(BASE64_URL_ALPHABET[((second and 0x0f) shl 2) or if (third >= 0) third shr 6 else 0])
                }
                if (third >= 0) result.append(BASE64_URL_ALPHABET[third and 0x3f])
            }
            return result.toString()
        }

        private fun attachmentFrame(attachmentId: String, sessionId: String = "session-a"): String =
            """{"kind":"attachment","sessionId":"$sessionId","attachment":{"attachmentId":"$attachmentId","mediaType":"image/png","bytes":3,"width":1,"height":1},"data":"AQID"}"""
    }
}
