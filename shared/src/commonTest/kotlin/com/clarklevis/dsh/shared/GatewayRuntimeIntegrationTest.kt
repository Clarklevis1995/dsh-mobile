package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.gateway.GatewayOutgoingImage
import com.clarklevis.dsh.shared.gateway.GatewayPairingPayloadException
import com.clarklevis.dsh.shared.gateway.GatewayPairingPayloadParser
import com.clarklevis.dsh.shared.gateway.GatewayRuntime
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeEvent
import com.clarklevis.dsh.shared.gateway.SplitGatewayTransport
import kotlinx.coroutines.CompletableDeferred
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
import com.clarklevis.dsh.shared.protocol.GatewayApprovalOutcome
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
    fun networkDiagnosticsAreColdCorrelatedAndCancelledWithThePage() = runTest {
        val transport = FakeTransport()
        val runtime = GatewayRuntime(transport, FakePreferences(), FakeCredentials(), FakeAttachmentCache(),
            FakeNetworkMonitor(), FakeClock(0), backgroundScope, diagnosticsNow = { testScheduler.currentTime.toDouble() })
        val states = mutableListOf<com.clarklevis.dsh.shared.diagnostics.NetworkDiagnosticsSnapshot>()
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("ws://localhost/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","capabilities":["network-diagnostics-v1"]}""")
        runCurrent()
        fun probes() = transport.sentPayloads.filter { it.contains("network-check:") }
        val flow = runtime.monitorNetworkDiagnostics { "WebSocket" }
        advanceTimeBy(2000)
        runCurrent()
        assertTrue(probes().isEmpty(), "未订阅检测页时不发探测包")
        val collector = backgroundScope.launch { flow.collect(states::add) }
        runCurrent()
        assertEquals(1, probes().size)
        val id = wireJson.parseToJsonElement(probes().single()).jsonObject.getValue("requestId").jsonPrimitive.content
        advanceTimeBy(10)
        transport.receive("""{"kind":"pong"}""")
        runCurrent()
        advanceTimeBy(15)
        transport.receive("""{"kind":"pong","requestId":"$id"}""")
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(25.0, states.last().roundTripMs)
        assertEquals(0.0, states.last().lossPercent)
        assertTrue(states.last().receivedBytesPerSecond > 0)
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().none { it.frame.requestId == id })
        collector.cancel()
        runCurrent()
        val count = probes().size
        advanceTimeBy(6000)
        runCurrent()
        assertEquals(count, probes().size, "关闭页面后不再发送探测")
        val resumed = backgroundScope.launch { flow.collect(states::add) }
        runCurrent()
        assertEquals(0, states.last().timedOutProbes, "取消的在途探测不计入丢包")
        assertEquals(-1.0, states.last().roundTripMs)
        resumed.cancel()
        runCurrent()
    }

    @Test
    fun networkDiagnosticsDoNotProbeUnadvertisedOldGateway() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val states = mutableListOf<com.clarklevis.dsh.shared.diagnostics.NetworkDiagnosticsSnapshot>()
        runCurrent()
        runtime.connect("ws://localhost/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","capabilities":[]}""")
        runCurrent()
        val collector = backgroundScope.launch { runtime.monitorNetworkDiagnostics { "WebSocket" }.collect(states::add) }
        runCurrent()
        advanceTimeBy(4000)
        runCurrent()
        assertFalse(states.last().supported)
        assertEquals(-1.0, states.last().lossPercent)
        assertTrue(transport.sentPayloads.none { it.contains("network-check:") })
        collector.cancel()
        runCurrent()
    }

    @Test
    fun fileUploadUsesControlAndReceiptMessageUsesConversation() = runTest {
        val control = FakeTransport()
        val conversation = FakeTransport()
        val runtime = GatewayRuntime(SplitGatewayTransport(control, conversation), FakePreferences(), FakeCredentials(),
            FakeAttachmentCache(), FakeNetworkMonitor(), FakeClock(0), backgroundScope)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        backgroundScope.launch { runtime.conversationEvents.collect(events::add) }
        runCurrent()
        runtime.connect("ws://localhost/mobile")
        control.opened()
        control.receive("""{"kind":"hello","protocol":3,"capabilities":["split-channels","file-upload-v1"]}""")
        runCurrent()
        conversation.opened()
        conversation.receive("""{"kind":"hello","capabilities":["split-channels"]}""")
        runCurrent()
        val store = com.clarklevis.dsh.shared.facade.SharedFileUploadStore()
        store.bind("s1", "c1", true)
        val opening = store.add("a", "zero.zip", 0, "0".repeat(64)).requests.single()
        assertTrue(runtime.sendRequest(opening))
        assertTrue(control.sentPayloads.last().contains("file-upload-open"))
        assertTrue(conversation.sentPayloads.isEmpty())
        control.receive("""{"kind":"error","requestType":"file-upload-open","requestId":"${opening.correlationId}","sessionId":"s1","code":"file-upload-unavailable","message":"unavailable"}""")
        runCurrent()
        val error = events.filterIsInstance<GatewayRuntimeEvent.Frame>().single { it.frame.code == "file-upload-unavailable" }
        assertEquals("error", store.acceptFrame(error.rawJson).drafts.single().status)
        assertTrue(runtime.sendMessage("", emptyList(), "s1", null, "Asia/Shanghai", receipts = listOf("r1"), requestId = "send-1"))
        assertTrue(conversation.sentPayloads.last().contains("\"files\":[{\"receiptId\":\"r1\"}]"))
        conversation.receive("""{"kind":"sent","requestId":"send-1","sessionId":"s1"}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.requestId == "send-1" })
    }

    @Test
    fun finishTimeoutRetainsSocketAndAcceptsPriorCompletionDuringRetry() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","protocol":3,"capabilities":["file-upload-v1"]}""")
        runCurrent()
        val finish = com.clarklevis.dsh.shared.gateway.GatewayRequest("file-upload-finish", "file-upload-completed",
            targetSessionId = "s1", correlationId = "finish-1", timeoutMilliseconds = 50,
            payload = """{"type":"file-upload-finish","requestId":"finish-1","uploadId":"u1"}""")
        assertTrue(runtime.sendRequest(finish))
        advanceTimeBy(51)
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertTrue(events.any { it is GatewayRuntimeEvent.RequestTimedOut && it.correlationId == "finish-1" })
        assertTrue(runtime.sendRequest(finish.copy(correlationId = "finish-2",
            payload = """{"type":"file-upload-finish","requestId":"finish-2","uploadId":"u1"}""")))
        transport.receive("""{"kind":"file-upload-completed","requestId":"finish-1","sessionId":"s1","uploadId":"u1","receiptId":"r1"}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.receiptId == "r1" })
        advanceTimeBy(51)
        runCurrent()
        assertTrue(events.none { it is GatewayRuntimeEvent.RequestTimedOut && it.correlationId == "finish-2" })
    }

    @Test
    fun receiptMessageTimeoutAllowsIdenticalSubmissionWithoutReopeningSocket() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","protocol":3,"capabilities":["file-upload-v1"]}""")
        runCurrent()
        val request = GatewayRequests.message("", emptyList(), "s1", null, "Asia/Shanghai",
            receipts = listOf("r1"), requestId = "same-submission").copy(timeoutMilliseconds = 50)
        assertTrue(runtime.sendRequest(request))
        advanceTimeBy(51)
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertEquals(1, transport.connectionSpecs.size)
        assertTrue(runtime.sendRequest(request))
        assertEquals(transport.sentPayloads.takeLast(2).first(), transport.sentPayloads.last())
    }

    @Test
    fun fileDirectoryErrorsReachFileStoreWithTheirCodeAndRequestIdentity() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","protocol":3,"capabilities":["file-downloads","file-references-v1"]}""")
        runCurrent()
        val store = com.clarklevis.dsh.shared.facade.SharedWorkspaceFileStore()
        store.setWorkspaceSession("child", "lead")
        val transition = store.resolveReference("child", "promo/analysis/stills", "dir-1", true, null)
        assertTrue(runtime.sendRequest(requireNotNull(transition.request)))
        transport.receive("""
            {"kind":"error","requestType":"file-resolve","requestId":"dir-1","sessionId":"lead",
            "code":"file-not-regular","message":"only regular files can be previewed"}
        """)
        runCurrent()
        val response = events.filterIsInstance<GatewayRuntimeEvent.Frame>().single { it.frame.requestId == "dir-1" }
        val result = store.acceptFrame(response.rawJson)
        assertEquals("promo/analysis/stills", result.directory?.path)
        assertEquals("child", result.directory?.sessionId)
        assertNull(result.snapshot.lastError)
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().none { it.correlationId == "dir-1" })
        assertTrue(runtime.sendRequest(GatewayRequests.fileList("lead", "promo/analysis/stills", "list-1")))
    }

    @Test
    fun memberProfileResponsesAndTimeoutsDoNotCompleteOrRecycleForegroundHistory() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true,"capabilities":["subagent-conversations-v1"]}""")
        runCurrent()
        runtime.requestHistory("lead")
        runtime.requestHistory("next")
        fun profile(id: String) = com.clarklevis.dsh.shared.gateway.GatewayRequest(
            requestType = "history", responseKind = id, targetSessionId = "child", correlationId = id,
            payload = """{"type":"history","sessionId":"child","parentSessionId":"lead","subagentMode":"continuable","requestId":"$id","maxMessages":1}""",
            timeoutMilliseconds = 1_000
        )
        assertTrue(runtime.sendRequest(profile("team-profile-test-1")))
        transport.receive("""{"kind":"history","sessionId":"child","parentSessionId":"lead","requestId":"team-profile-test-1","events":[]}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.requestId == "team-profile-test-1" })
        assertTrue(transport.sentPayloads.none { it.contains("\"sessionId\":\"next\"") })
        assertTrue(runtime.sendRequest(profile("team-profile-test-2")))
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestTimedOut>().any { it.correlationId == "team-profile-test-2" })
        transport.receive("""{"kind":"history","sessionId":"child","requestId":"team-profile-test-2","events":[]}""")
        transport.receive("""{"kind":"history","sessionId":"lead","events":[]}""")
        runCurrent()
        assertTrue(transport.sentPayloads.any { it.contains("\"sessionId\":\"next\"") })
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().none { it.frame.requestId == "team-profile-test-2" })
        runtime.disconnect()
    }

    @Test
    fun teamChildRoutingAndSingleFlightNeverInstallSupersededSubscription() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true,"capabilities":["agent-team-v1","subagent-conversations-v1"]}""")
        runCurrent()
        runtime.registerConversationTarget("""{"sessionId":"a","parentSessionId":"lead","subagentMode":"continuable"}""")
        runtime.subscribe("a")
        runtime.subscribe("b")
        runtime.subscribe("a")
        assertEquals(1, transport.sentTypes.count { it == "subscribe" })
        transport.receive("""{"kind":"subscribed","sessionId":"a","subscriptionId":"old-a"}""")
        runCurrent()
        assertEquals(2, transport.sentTypes.count { it == "subscribe" })
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().none { it.frame.subscriptionId == "old-a" })
        transport.receive("""{"kind":"subscribed","sessionId":"a","subscriptionId":"new-a"}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.subscriptionId == "new-a" })
        runtime.requestHistory("a", beforeSequence = 3, historyFormatVersion = 4)
        runtime.sendMessage("继续分析", listOf(GatewayOutgoingImage("image/png", "AAAA", "frame.png")), "a", "wrong", "Asia/Shanghai", "steer")
        runtime.sendRequest(GatewayRequests.sessionCancel("a"))
        for (type in listOf("subscribe", "history", "message", "session-cancel")) {
            val obj = wireJson.parseToJsonElement(transport.sentPayloads.last { wireJson.parseToJsonElement(it).jsonObject["type"]?.jsonPrimitive?.content == type }).jsonObject
            assertEquals("lead", obj["parentSessionId"]?.jsonPrimitive?.content)
            assertEquals("continuable", obj["subagentMode"]?.jsonPrimitive?.content)
            assertNull(obj["workspaceId"])
        }
        runtime.registerConversationTarget("""{"sessionId":"c","parentSessionId":"a","subagentMode":"one-shot"}""")
        val before = transport.sentPayloads.size
        assertFalse(runtime.sendMessage("不能发送", emptyList(), "c", null, "Asia/Shanghai"))
        assertEquals(before, transport.sentPayloads.size)
    }

    @Test
    fun speechRequestsUseExactIdsAndLongTimeoutAndKeepSubscriptionErrors() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true,"capabilities":["speech-input-v1"]}""")
        runCurrent()
        transport.receive("""{"kind":"workspaces","items":[]}""")
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        fun speech(type: String, kind: String, id: String) = com.clarklevis.dsh.shared.gateway.GatewayRequest(
            requestType = type, responseKind = kind, correlationId = id,
            lanePolicy = com.clarklevis.dsh.shared.gateway.GatewayRequestLanePolicy.REJECT_IF_BUSY,
            payload = """{"type":"$type","requestId":"$id"}""", timeoutMilliseconds = 185_000
        )
        assertTrue(runtime.sendRequest(speech("speech-transcribe", "speech-transcript", "record-1")))
        transport.receive("""{"kind":"speech-transcript","requestId":"stale","text":"old"}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().none { it.frame.kind == "speech-transcript" })
        advanceTimeBy(31_000)
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestTimedOut>().none { it.requestType == "speech-transcribe" })
        transport.receive("""{"kind":"speech-transcript","requestId":"record-1","text":"new"}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.requestId == "record-1" })
        assertTrue(runtime.sendRequest(speech("speech-transcribe", "speech-transcript", "record-2")))
        transport.receive("""{"kind":"error","requestType":"speech-transcribe","requestId":"record-2","code":"speech/transcription-failed","message":"model unavailable"}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.code == "speech/transcription-failed" })
        assertTrue(runtime.sendRequest(speech("speech-subscribe", "speech-subscribed", "watch-1")))
        transport.receive("""{"kind":"speech-subscribed","requestId":"watch-1"}""")
        runCurrent()
        transport.receive("""{"kind":"error","requestType":"speech-subscribe","requestId":"watch-1","code":"speech/unavailable"}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.requestId == "watch-1" && it.frame.kind == "error" })
    }

    @Test
    fun presetRequestsCorrelateIdsAndPreserveHostErrorsAndUnsubscribedNotifications() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true,"capabilities":["session-agent-preset"]}""")
        runCurrent()
        runtime.sendRequest(GatewayRequests.sessionAgentPreset("s1", "q1"))
        transport.receive("""{"kind":"session-agent-preset","sessionId":"s1","requestId":"old","agentPreset":"wrong","locked":false}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().none { it.frame.kind == "session-agent-preset" })
        transport.receive("""{"kind":"session-agent-preset-updated","sessionId":"other","locked":true,"seq":99}""")
        transport.receive("""{"kind":"session-agent-preset","sessionId":"s1","requestId":"q1","agentPreset":"standard","locked":false}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.sessionId == "other" })
        runtime.sendRequest(GatewayRequests.selectAgentPreset("s1", "minimal", "select1"))
        transport.receive("""{"kind":"error","requestType":"select-agent-preset","sessionId":"s1","requestId":"select1","code":"agent-preset/locked","message":"对话开始后不可修改"}""")
        runCurrent()
        val error = events.filterIsInstance<GatewayRuntimeEvent.Frame>().last().frame
        assertEquals("agent-preset/locked", error.code)
        assertEquals("select1", error.requestId)
        assertEquals("对话开始后不可修改", error.message)
        assertTrue(runtime.sendRequest(GatewayRequests.sessionAgentPreset("s1", "q2")))
    }

    @Test
    fun queuedAndSteeringPromptsDoNotLeakBackgroundTurnCounts() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        for (mode in listOf("queue", "queue", "steer")) {
            assertTrue(runtime.sendMessage("test", emptyList(), "s", null, "UTC", mode))
            transport.receive("""{"kind":"sent","sessionId":"s","mode":"$mode"}""")
            runCurrent()
        }
        transport.receive("""{"kind":"session-queue","sessionId":"s","items":[{"placement":"queued"}]}""")
        transport.receive("""{"sessionId":"s","seq":9,"time":1,"event":{"type":"turn/end"}}""")
        runCurrent()
        assertTrue(runtime.state.value.shouldKeepAliveInBackground)
        transport.receive("""{"kind":"session-queue","sessionId":"s","items":[]}""")
        runCurrent()
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
    }

    @Test
    fun queueAcknowledgementMustMatchItemAndFailureAllowsNextAction() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertTrue(runtime.sendRequest(GatewayRequests.queueUpdate("a", "q1", "steer")))
        transport.receive("""{"kind":"queue-item-updated","sessionId":"a","itemId":"other","action":"steer","accepted":true}""")
        runCurrent()
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.kind == "queue-item-updated" })
        transport.receive("""{"kind":"error","requestType":"queue-update","sessionId":"a","itemId":"q1","message":"no longer pending"}""")
        runCurrent()
        assertTrue(runtime.sendRequest(GatewayRequests.queueUpdate("a", "q2", "remove")))
        transport.receive("""{"kind":"queue-item-updated","sessionId":"a","itemId":"q2","action":"remove","accepted":true}""")
        runCurrent()
        assertEquals("q2", events.filterIsInstance<GatewayRuntimeEvent.Frame>().last { it.frame.kind == "queue-item-updated" }.frame.itemId)
    }

    @Test
    fun rtcPairingReopensWithPersistedCredentialsBeforeAnyBusinessHello() = runTest {
        val raw = FakeTransport()
        val upgraded = object : com.clarklevis.dsh.shared.platform.GatewayRtcTransport, GatewayTransport by raw {
            override val conversationEvents: Flow<GatewayTransportEvent> = kotlinx.coroutines.flow.emptyFlow()
            override fun resetRtcRecovery() = Unit
        }
        val credentials = FakeCredentials()
        val runtime = GatewayRuntime(upgraded, FakePreferences(), credentials, FakeAttachmentCache(),
            FakeNetworkMonitor(), FakeClock(0), backgroundScope)
        runCurrent()
        runtime.pair(encodeBase64Url(
            """{"version":2,"publicUrl":"wss://gateway.example/ws/mobile","pairingCode":"pair-code","expiresAt":60000}"""
        ))
        raw.opened()
        raw.receive("""{"kind":"paired","token":"saved-token"}""")
        runCurrent()
        assertEquals("saved-token", credentials.tokens["wss://gateway.example/ws/mobile"])
        raw.receive("""{"kind":"hello","protocol":3}""")
        runCurrent()
        assertEquals(2, raw.connectionSpecs.size)
        assertEquals("saved-token", raw.connectionSpecs.last().bearerToken)
        assertNull(raw.connectionSpecs.last().pairingCode)
        assertTrue(raw.sentPayloads.isEmpty())
        assertFalse(runtime.state.value.connection == GatewayConnectionState.CONNECTED)
        raw.opened()
        raw.receive("""{"kind":"hello","protocol":3}""")
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
    }

    @Test
    fun lanPairingKeepsOriginalWebSocketWithoutRtcReopen() = runTest {
        val raw = FakeTransport()
        val transport = object : com.clarklevis.dsh.shared.platform.GatewayRtcTransport, GatewayTransport by raw {
            override val conversationEvents: Flow<GatewayTransportEvent> = kotlinx.coroutines.flow.emptyFlow()
            override fun resetRtcRecovery() = Unit
        }
        val credentials = FakeCredentials()
        val runtime = GatewayRuntime(transport, FakePreferences(), credentials, FakeAttachmentCache(),
            FakeNetworkMonitor(), FakeClock(0), backgroundScope)
        runCurrent()
        runtime.pair(encodeBase64Url(
            """{"version":2,"publicUrl":"ws://192.168.1.94:3081/ws/mobile","pairingCode":"pair-code","expiresAt":60000}"""
        ))
        raw.opened()
        raw.receive("""{"kind":"paired","token":"saved-token"}""")
        runCurrent()
        raw.receive("""{"kind":"hello","protocol":3}""")
        runCurrent()
        assertEquals("saved-token", credentials.tokens["ws://192.168.1.94:3081/ws/mobile"])
        assertEquals(1, raw.connectionSpecs.size)
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
    }

    @Test
    fun pairingMustPersistTokenBeforeAcceptingHello() = runTest {
        val transport = FakeTransport()
        val credentials = FakeCredentials()
        val runtime = GatewayRuntime(
            transport, FakePreferences(), credentials, FakeAttachmentCache(),
            FakeNetworkMonitor(), FakeClock(0), backgroundScope
        )
        runCurrent()
        runtime.pair(encodeBase64Url(
            """{"version":2,"publicUrl":"wss://gateway.example/ws/mobile","pairingCode":"once","expiresAt":4102444800000}"""
        ))
        transport.opened()
        transport.receive("""{"kind":"hello","protocol":3}""")
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        assertTrue(credentials.tokens.isEmpty())
    }

    @Test
    fun gatewayIdentityMismatchNeverSavesTokenOrStartsConversation() = runTest {
        val control = FakeTransport()
        val conversation = FakeTransport()
        val credentials = FakeCredentials()
        val runtime = GatewayRuntime(
            SplitGatewayTransport(control, conversation), FakePreferences(), credentials,
            FakeAttachmentCache(), FakeNetworkMonitor(), FakeClock(0), backgroundScope,
            expectedGatewayId = "d56a1098-8519-43a1-9dce-fb99863bf5bb"
        )
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        control.opened()
        control.receive("""{"kind":"paired","token":"must-not-save","gatewayId":"a56a1098-8519-43a1-9dce-fb99863bf5bb"}""")
        runCurrent()
        assertTrue(credentials.tokens.isEmpty())
        assertTrue(conversation.connectionSpecs.isEmpty())
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, control.connectionSpecs.size)
    }

    @Test
    fun savedGatewayIdentityCannotDowngradeToLegacyHello() = runTest {
        val transport = FakeTransport()
        val runtime = GatewayRuntime(
            transport, FakePreferences(), FakeCredentials(), FakeAttachmentCache(),
            FakeNetworkMonitor(), FakeClock(0), backgroundScope,
            expectedGatewayId = "d56a1098-8519-43a1-9dce-fb99863bf5bb"
        )
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","capabilities":[]}""")
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        assertFalse(runtime.sendRequest(GatewayRequests.simple("sessions")))
    }

    @Test
    fun untrustedEndpointIsRejectedBeforeOpeningTransport() = runTest {
        val transport = FakeTransport()
        val runtime = GatewayRuntime(
            transport, FakePreferences(), FakeCredentials(), FakeAttachmentCache(),
            FakeNetworkMonitor(), FakeClock(0), backgroundScope,
            trustedEndpoints = listOf("wss://trusted.example/ws/mobile")
        )
        assertFailsWith<IllegalArgumentException> { runtime.connect("wss://untrusted.example/ws/mobile") }
        assertTrue(transport.connectionSpecs.isEmpty())
    }

    @Test
    fun conversationIdentityMustMatchAuthenticatedControl() = runTest {
        val control = FakeTransport()
        val conversation = FakeTransport()
        val runtime = GatewayRuntime(
            SplitGatewayTransport(control, conversation), FakePreferences(), FakeCredentials(),
            FakeAttachmentCache(), FakeNetworkMonitor(), FakeClock(0), backgroundScope,
            expectedGatewayId = "d56a1098-8519-43a1-9dce-fb99863bf5bb"
        )
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        control.opened()
        control.receive("""{"kind":"hello","gatewayId":"d56a1098-8519-43a1-9dce-fb99863bf5bb","capabilities":["split-channels"]}""")
        runCurrent()
        runtime.subscribe("same-session")
        assertTrue(conversation.sentPayloads.isEmpty())
        conversation.opened()
        conversation.receive("""{"kind":"hello","gatewayId":"a56a1098-8519-43a1-9dce-fb99863bf5bb","capabilities":["split-channels"]}""")
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        assertTrue(conversation.sentPayloads.isEmpty())
    }

    @Test
    fun splitTransportDeliversEntireRc2SubscriptionOnOneOrderedQueue() = runTest {
        val control = FakeTransport()
        val conversation = FakeTransport()
        val runtime = GatewayRuntime(SplitGatewayTransport(control, conversation), FakePreferences(), FakeCredentials(),
            FakeAttachmentCache(), FakeNetworkMonitor(), FakeClock(0), backgroundScope)
        val kinds = mutableListOf<String>()
        val controlKinds = mutableListOf<String>()
        backgroundScope.launch { runtime.events.collect { if (it is GatewayRuntimeEvent.Frame) controlKinds += it.frame.kind } }
        backgroundScope.launch { runtime.conversationEvents.collect { if (it is GatewayRuntimeEvent.Frame) kinds += it.frame.kind } }
        runCurrent()
        runtime.connect("ws://localhost/mobile")
        control.opened()
        control.receive("""{"kind":"hello","historyFormatVersion":3,"capabilities":["split-channels","assistant-stream-v1"]}""")
        runCurrent()
        conversation.opened()
        conversation.receive("""{"kind":"hello","capabilities":["split-channels"]}""")
        runCurrent()
        runtime.subscribe("s")
        conversation.receive("""{"kind":"subscribed","sessionId":"s","subscriptionId":"sub"}""")
        conversation.receive("""{"kind":"session-snapshot","sessionId":"s","subscriptionId":"sub","streamId":"stream","cursor":41,"historyFormatVersion":3,"events":[]}""")
        conversation.receive("""{"kind":"assistant-stream","sessionId":"s","subscriptionId":"sub","streamId":"stream","frame":{"type":"start","revision":1}}""")
        conversation.receive("""{"kind":"event","sessionId":"s","subscriptionId":"sub","streamId":"stream","seq":42,"time":1,"event":{"type":"assistant/message","text":"Final"}}""")
        conversation.receive("""{"kind":"assistant-stream","sessionId":"s","subscriptionId":"sub","streamId":"stream","frame":{"type":"end","revision":2}}""")
        conversation.receive("""{"kind":"session-stream-reset","sessionId":"s","subscriptionId":"sub","streamId":"stream","retrying":true}""")
        control.receive("""{"kind":"projection-baseline","projections":{}}""")
        runCurrent()
        assertEquals(listOf("hello", "subscribed", "session-snapshot", "assistant-stream", "event", "assistant-stream", "session-stream-reset"), kinds)
        assertEquals(listOf("projection-baseline"), controlKinds)
    }

    @Test
    fun splitTransportKeepsFilesMovingWhileConversationConsumerIsBlocked() = runTest {
        val control = FakeTransport()
        val conversation = FakeTransport()
        val runtime = GatewayRuntime(
            SplitGatewayTransport(control, conversation), FakePreferences(), FakeCredentials(),
            FakeAttachmentCache(), FakeNetworkMonitor(), FakeClock(0), backgroundScope
        )
        val releaseConversation = CompletableDeferred<Unit>()
        val controlFrames = mutableListOf<String>()
        val sequences = mutableListOf<Int>()
        backgroundScope.launch {
            runtime.events.collect { if (it is GatewayRuntimeEvent.Frame) controlFrames += it.frame.kind }
        }
        backgroundScope.launch {
            runtime.conversationEvents.collect {
                releaseConversation.await()
                if (it is GatewayRuntimeEvent.Frame && it.frame.kind == "event") sequences += requireNotNull(it.frame.seq)
            }
        }
        runCurrent()
        runtime.connect("ws://localhost/mobile")
        runCurrent()
        control.opened()
        control.receive("""{"kind":"hello","capabilities":["split-channels"],"protocol":3}""")
        runCurrent()
        assertEquals("control", control.connectionSpecs.single().channel)
        assertEquals("conversation", conversation.connectionSpecs.single().channel)
        conversation.opened()
        conversation.receive("""{"kind":"hello","capabilities":["split-channels"],"protocol":3}""")
        runCurrent()
        runtime.subscribe("s1")
        runtime.requestHistory("s1")
        assertTrue("subscribe" in conversation.sentTypes && "history" in conversation.sentTypes)
        assertFalse("subscribe" in control.sentTypes || "history" in control.sentTypes)
        repeat(24) { index ->
            conversation.receive("""{"sessionId":"s1","seq":${index + 1},"time":1,"event":{"type":"assistant/chunk","text":"x","chunkType":"text-delta","turn":1,"step":1}}""")
        }
        runCurrent()
        assertTrue(sequences.isEmpty())
        // 对话有界队列已满；控制请求、回执仍必须完成，且不依赖放开对话消费者。
        assertTrue(runtime.sendRequest(GatewayRequests.fileList("s1", null, "files-split")))
        control.receive("""{"kind":"file-list","requestId":"files-split","sessionId":"s1","path":".","entries":[]}""")
        runCurrent()
        assertTrue("file-list" in controlFrames)
        assertTrue(runtime.sendRequest(GatewayRequests.fileResolve("s1", "/work/report.pdf", "resolve-split")))
        control.receive("""{"kind":"file-resolved","requestId":"stale","sessionId":"s1","path":"report.pdf","size":1}""")
        runCurrent()
        assertFalse("file-resolved" in controlFrames)
        control.receive("""{"kind":"file-resolved","requestId":"resolve-split","sessionId":"s1","path":"report.pdf","name":"report.pdf","size":1}""")
        runCurrent()
        assertTrue("file-resolved" in controlFrames)
        assertFalse("file-resolve" in conversation.sentTypes)
        assertTrue(runtime.sendRequest(GatewayRequests.fileDownloadRead("transfer-split", 0)))
        control.receive("""{"kind":"file-download-chunk","transferId":"transfer-split","offset":0,"data":"YQ==","eof":true}""")
        runCurrent()
        assertTrue("file-download-chunk" in controlFrames)
        assertFalse("event" in controlFrames)
        assertFalse("file-list" in conversation.sentTypes || "file-download-read" in conversation.sentTypes)
        releaseConversation.complete(Unit)
        runCurrent()
        assertEquals((1..24).toList(), sequences)
    }

    @Test
    fun splitTransportReusesPairingTokenAndFallsBackForOldGateways() = runTest {
        val control = FakeTransport()
        val conversation = FakeTransport()
        val transport = SplitGatewayTransport(control, conversation)
        backgroundScope.launch { transport.events.collect {} }
        backgroundScope.launch { transport.conversationEvents.collect {} }
        runCurrent()
        val spec = GatewayConnectionSpec(1, "ws://localhost/mobile", "device", pairingCode = "single-use")
        transport.open(spec)
        control.opened()
        control.receive("""{"kind":"paired","token":"long-lived"}""")
        control.receive("""{"kind":"hello","capabilities":["split-channels"]}""")
        runCurrent()
        assertTrue(conversation.connectionSpecs.isEmpty())
        transport.confirmControlHandshake()
        assertEquals("long-lived", conversation.connectionSpecs.single().bearerToken)
        assertNull(conversation.connectionSpecs.single().pairingCode)
        conversation.opened()
        conversation.receive("""{"kind":"hello","capabilities":["split-channels"]}""")
        runCurrent()
        transport.close()
        transport.open(spec.copy(generation = 2, pairingCode = null, bearerToken = "long-lived"))
        control.opened()
        control.receive("""{"kind":"hello","capabilities":[]}""")
        runCurrent()
        transport.send(GatewayRequests.history("s1").payload)
        assertEquals(1, conversation.connectionSpecs.size)
        assertTrue("history" in control.sentTypes)
    }

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

        assertTrue(runtime.sendRequest(GatewayRequests.sessionCancel("session-new")))
        assertEquals("session-cancel", transport.sentTypes.last())
        transport.receive(
            """{"kind":"session-cancelled","sessionId":"session-new","accepted":true}"""
        )
        runCurrent()
        assertEquals(
            true,
            events.filterIsInstance<GatewayRuntimeEvent.Frame>()
                .last { it.frame.kind == "session-cancelled" }
                .frame.accepted
        )
        assertTrue(runtime.state.value.shouldKeepAliveInBackground)

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
    fun manualPairingStopsAfterRecoverableFailureAndConnectionTimeout() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(
            transport = transport,
            connectionAttemptTimeoutMilliseconds = 100
        )
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()

        val pairingPayload = encodeBase64Url(
            """{"version":2,"publicUrl":"wss://gateway.example/ws/mobile","pairingCode":"one-time","expiresAt":101}"""
        )
        runtime.pair(pairingPayload)
        runCurrent()
        val firstGeneration = transport.connectionSpecs.single().generation
        transport.fail(
            GatewayTransportState.Failed(
                generation = firstGeneration,
                recoverable = true,
                reason = "websocket-failure"
            )
        )
        runCurrent()

        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        assertEquals(1, transport.connectionSpecs.size)

        runtime.pair(pairingPayload)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        assertEquals("connection-timeout", runtime.state.value.lastError)
        assertEquals(2, transport.connectionSpecs.size)
        runtime.applicationDidEnterBackground()
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size, "回前台不得自动重用一次性配对码")
    }

    @Test
    fun taskAndGoalRealtimePushesAreAcceptedWithoutAnOutstandingQuery() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport = transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()

        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true,"capabilities":["tasks","goals"]}""")
        transport.receive(
            """{"kind":"tasks-updated","sessionId":"s1","asOfSeq":8,"todos":[{"content":"检查 SDK","status":"in_progress"}]}"""
        )
        transport.receive(
            """{"kind":"goal-updated","sessionId":"s1","asOfSeq":9,"goal":{"goal":{"id":"goal-1","revision":2,"objective":"完成 Android 对接","phase":"active"},"roundsStarted":1}}"""
        )
        runCurrent()

        val taskUpdate = events.filterIsInstance<GatewayRuntimeEvent.Frame>()
            .first { it.frame.kind == "tasks-updated" }.frame
        assertEquals("检查 SDK", taskUpdate.todos?.single()?.content)
        val goalUpdate = events.filterIsInstance<GatewayRuntimeEvent.Frame>()
            .first { it.frame.kind == "goal-updated" }.frame
        assertEquals(2, goalUpdate.goal?.goal?.revision)
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
    fun emptySessionCreationCorrelatesBeforeControlsAndNeverPrompts() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true,"capabilities":["session-create","commands"]}""")
        runCurrent()

        assertTrue(runtime.sendRequest(GatewayRequests.createSession("create-1", "workspace-1")))
        assertTrue(transport.sentPayloads.last().contains("\"workspaceId\":\"workspace-1\""))
        transport.receive("""{"kind":"session-created","requestId":"stale","sessionId":"wrong"}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().none { it.frame.kind == "session-created" })
        transport.receive("""{"kind":"session-created","requestId":"create-1","sessionId":"empty-1"}""")
        runCurrent()
        assertEquals("empty-1", events.filterIsInstance<GatewayRuntimeEvent.Frame>().last().correlatedSessionId)
        assertTrue(runtime.sendRequest(GatewayRequests.sessionControl("models", "empty-1")))
        assertTrue(runtime.sendRequest(GatewayRequests.sessionControl("permission-options", "empty-1")))
        assertTrue(runtime.sendRequest(GatewayRequests.slashCommands("empty-1")))
        assertTrue(transport.sentTypes.containsAll(listOf("models", "permission-options", "commands")))
        assertFalse(transport.sentTypes.contains("message"))
    }

    @Test
    fun backgroundHistoryOriginStaysLocalAndSurvivesFramesErrorsFailuresAndCancellation() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertTrue(runtime.tryRequestHistoryInBackground("same-session"))
        // 旧协议无显式 sessionId，Runtime 使用 active request 的精确本地归属。
        transport.receive("""{"kind":"history","events":[],"hasMore":false,"bytes":0}""")
        runCurrent()
        val background = events.filterIsInstance<GatewayRuntimeEvent.Frame>().last { it.frame.kind == "history" }
        assertTrue(background.isBackgroundHistory)
        assertEquals("same-session", background.correlatedSessionId)
        assertFalse(transport.sentPayloads.last().contains("isBackgroundHistory"))
        runtime.requestHistory("same-session", beforeSequence = 10, historyFormatVersion = 1)
        transport.receive("""{"kind":"history","events":[],"hasMore":false,"bytes":0}""")
        runCurrent()
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.Frame>().last { it.frame.kind == "history" }.isBackgroundHistory)
        assertTrue(runtime.tryRequestHistoryInBackground("background-error"))
        transport.receive("""{"kind":"error","requestType":"history","code":"history/unavailable"}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any {
            it.isBackgroundHistory && it.targetSessionId == "background-error"
        })
        runtime.requestHistory("same-session", beforeSequence = 5, historyFormatVersion = 1)
        transport.receive("""{"kind":"error","requestType":"history","code":"history/unavailable"}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any {
            !it.isBackgroundHistory && it.targetSessionId == "same-session"
        })
        transport.failNextSend = true
        assertFalse(runtime.tryRequestHistoryInBackground("background-send-failed"))
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any {
            it.isBackgroundHistory && it.targetSessionId == "background-send-failed"
        })
        assertTrue(runtime.tryRequestHistoryInBackground("background-cancelled"))
        runtime.disconnect()
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestCancelled>().any {
            it.isBackgroundHistory && it.targetSessionId == "background-cancelled"
        })
    }

    @Test
    fun backgroundChildHistoryKeepsDurableParentAndItsOwnErrorOwnership() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true,"capabilities":["subagent-conversations-v1"]}""")
        runCurrent()
        // 二级子智能体必须携带直接父级，不能改为团队根会话。
        runtime.registerConversationTarget("""{"sessionId":"child","parentSessionId":"parent","subagentMode":"continuable"}""")
        assertTrue(runtime.tryRequestHistoryInBackground("child", historyFormatVersion = 4))
        val request = wireJson.parseToJsonElement(transport.sentPayloads.last()).jsonObject
        assertEquals("child", request["sessionId"]?.jsonPrimitive?.content)
        assertEquals("parent", request["parentSessionId"]?.jsonPrimitive?.content)
        assertEquals("continuable", request["subagentMode"]?.jsonPrimitive?.content)
        assertFalse(runtime.tryRequestHistoryInBackground("another-child"))
        transport.receive("""{"kind":"error","sessionId":"child","requestType":"history","code":"session/agent-busy"}""")
        runCurrent()
        val failure = events.filterIsInstance<GatewayRuntimeEvent.Frame>().last { it.frame.kind == "error" }
        assertTrue(failure.isBackgroundHistory)
        assertEquals("child", failure.correlatedSessionId)
        // 后台失败已释放 lane，Lead 的前台请求不继承父级地址。
        assertTrue(runtime.requestHistory("lead"))
        val leadRequest = wireJson.parseToJsonElement(transport.sentPayloads.last()).jsonObject
        assertEquals("lead", leadRequest["sessionId"]?.jsonPrimitive?.content)
        assertNull(leadRequest["parentSessionId"])
        runtime.disconnect()
    }

    @Test
    fun backgroundHistoryNeverQueuesOrCoalescesForegroundLane() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        // 离线尝试安静跳过，不产生前台业务错误。
        assertFalse(runtime.tryRequestHistoryInBackground("background"))
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        runtime.requestHistory("foreground-active")
        runtime.requestHistory("foreground-queued")
        assertFalse(runtime.tryRequestHistoryInBackground("background"))
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestCancelled>().none { it.targetSessionId == "foreground-queued" })
        transport.receive("""{"kind":"history","sessionId":"foreground-active","events":[],"hasMore":false,"bytes":0}""")
        runCurrent()
        assertFalse(runtime.tryRequestHistoryInBackground("background"))
        transport.receive("""{"kind":"history","sessionId":"foreground-queued","events":[],"hasMore":false,"bytes":0}""")
        runCurrent()
        assertEquals(2, transport.sentTypes.count { it == "history" })
        assertTrue(runtime.tryRequestHistoryInBackground("background"))
        runtime.requestHistory("foreground-next")
        assertFalse(runtime.tryRequestHistoryInBackground("background-other"))
        transport.receive("""{"kind":"history","sessionId":"background","events":[],"hasMore":false,"bytes":0}""")
        runCurrent()
        assertEquals(4, transport.sentTypes.count { it == "history" })
        assertFalse(runtime.tryRequestHistoryInBackground("background-other"))
        transport.receive("""{"kind":"history","sessionId":"foreground-next","events":[],"hasMore":false,"bytes":0}""")
        runCurrent()
        assertEquals(listOf("foreground-active", "foreground-queued", "background", "foreground-next"),
            events.filterIsInstance<GatewayRuntimeEvent.Frame>().filter { it.frame.kind == "history" }.map { it.correlatedSessionId })
    }

    @Test
    fun backgroundHistoryUsesBoundedTimeoutAndRecyclesLegacySocketBeforeNextForeground() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        transport.receive("""{"kind":"workspaces","items":[]}""")
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertTrue(runtime.tryRequestHistoryInBackground("background"))
        runtime.requestHistory("foreground")
        advanceTimeBy(9_999)
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestTimedOut>().none { it.requestType == "history" })
        advanceTimeBy(1)
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestTimedOut>().any { it.targetSessionId == "background" && it.isBackgroundHistory })
        assertTrue(transport.closeCount > 0)
        assertEquals(1, transport.sentTypes.count { it == "history" })
        assertFalse(runtime.tryRequestHistoryInBackground("background-other"))
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
    fun openingExistingSessionAcceptsReplayedApprovalAfterSubscribeReceipt() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()

        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertTrue(runtime.subscribe("existing-session"))
        transport.receive("""{"kind":"subscribed","sessionId":"existing-session"}""")
        transport.receive(
            """{"kind":"approval-requested","rpcId":"approval-rpc","sessionId":"existing-session","approvalId":"approval-1","toolName":"Bash","callId":"call-1","reason":"需要执行命令","replay":true}"""
        )
        runCurrent()

        val replay = events.filterIsInstance<GatewayRuntimeEvent.Frame>()
            .last { it.frame.kind == "approval-requested" }
        assertEquals("approval-rpc", replay.frame.rpcId)
        assertEquals("existing-session", replay.frame.sessionId)
        assertEquals(true, replay.frame.replay)
        assertEquals("existing-session", replay.correlatedSessionId)
        assertFalse(events.any {
            it is GatewayRuntimeEvent.RequestRejected && it.reason == "unexpected-response"
        })
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
        runtime.applicationDidEnterBackground()
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
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
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

        assertTrue(runtime.sendRequest(GatewayRequests.approvalResponse(
            rpcId = "rpc-approval-1",
            sessionId = "session-a",
            approvalId = "approval-1",
            outcome = GatewayApprovalOutcome.ALLOWED_ONCE
        )))
        assertFalse(runtime.sendRequest(GatewayRequests.approvalResponse(
            rpcId = "rpc-approval-2",
            sessionId = "session-a",
            approvalId = "approval-2",
            outcome = GatewayApprovalOutcome.REJECTED
        )))
        assertEquals("approval-response", transport.sentTypes.last())
        assertTrue(transport.sentPayloads.last().contains("\"outcome\":\"allowed-once\""))
        transport.receive(
            """{"kind":"approval-response","rpcId":"wrong","sessionId":"session-a","approvalId":"approval-1","outcome":"allowed-once","accepted":true}"""
        )
        runCurrent()
        transport.receive(
            """{"kind":"approval-response","rpcId":"rpc-approval-1","sessionId":"session-a","approvalId":"approval-1","outcome":"allowed-once","accepted":true}"""
        )
        runCurrent()
        assertTrue(events.any {
            it is GatewayRuntimeEvent.Frame && it.frame.kind == "approval-response" &&
                it.frame.rpcId == "rpc-approval-1"
        })
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
    fun idleBackgroundDoesNotRecycleConnectionForPendingSnapshotTimeouts() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        val closes = transport.closeCount
        runtime.applicationDidEnterBackground()
        advanceTimeBy(65_000)
        runCurrent()
        assertEquals(closes, transport.closeCount)
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertFalse(events.any { it is GatewayRuntimeEvent.RequestTimedOut })

        runtime.applicationDidBecomeActive()
        runCurrent()
        transport.receive("""{"kind":"pong"}""")
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(closes, transport.closeCount)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(events.any { it is GatewayRuntimeEvent.RequestTimedOut })
        assertEquals(2, transport.connectionSpecs.size)
    }

    @Test
    fun delayedBackgroundResponseKeepsOriginalCorrelationAndSocketForQueuedRequest() = runTest {
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
        runtime.applicationDidEnterBackground()
        val closes = transport.closeCount
        advanceTimeBy(2_000)
        transport.receive("""{"kind":"history","events":[],"hasMore":false,"bytes":0}""")
        runCurrent()
        assertEquals(2, transport.sentTypes.count { it == "history" })
        assertEquals("session-a", events.filterIsInstance<GatewayRuntimeEvent.Frame>()
            .last { it.frame.kind == "history" }.correlatedSessionId)
        advanceTimeBy(2_000)
        transport.receive("""{"kind":"history","events":[],"hasMore":false,"bytes":0}""")
        runCurrent()
        assertEquals("session-b", events.filterIsInstance<GatewayRuntimeEvent.Frame>()
            .last { it.frame.kind == "history" }.correlatedSessionId)
        assertEquals(closes, transport.closeCount)
        assertEquals(1, transport.connectionSpecs.size)
        assertFalse(events.any { it is GatewayRuntimeEvent.RequestTimedOut })
    }

    @Test
    fun endingLastBackgroundTurnPausesOutstandingQueryTimeout() = runTest {
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
        runtime.sendMessage("work", emptyList(), "session-a", null, "UTC")
        transport.receive("""{"kind":"sent","sessionId":"session-a"}""")
        runCurrent()
        runtime.applicationDidEnterBackground()
        advanceTimeBy(400)
        transport.receive("""{"kind":"event","sessionId":"session-a","event":{"type":"turn/end"}}""")
        runCurrent()
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
        val closes = transport.closeCount
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(closes, transport.closeCount)
        assertFalse(events.any { it is GatewayRuntimeEvent.RequestTimedOut })
    }

    @Test
    fun idleBackgroundConnectionStaysOpenAndForegroundRecoversWithoutActiveTurn() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        val specCount = transport.connectionSpecs.size
        val closeCount = transport.closeCount
        runtime.applicationDidEnterBackground()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertEquals(closeCount, transport.closeCount)
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(specCount, transport.connectionSpecs.size)
        runtime.applicationDidEnterBackground()
        transport.fail(GatewayTransportState.Failed(
            generation = transport.connectionSpecs.last().generation,
            reason = "websocket-failure", recoverable = true
        ))
        runCurrent()
        assertEquals(specCount, transport.connectionSpecs.size, "空闲后台不主动发起连接")
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(specCount + 1, transport.connectionSpecs.size)
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        runtime.disconnect()
        runtime.applicationDidEnterBackground()
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(specCount + 1, transport.connectionSpecs.size, "手动断开后不得自动重连")
    }

    @Test
    fun pongCompletesManualPingLaneAndDoesNotCauseLaterConnectionRecycle() = runTest {
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
        assertTrue(runtime.sendRequest(GatewayRequests.ping()))
        transport.receive("""{"kind":"pong"}""")
        runCurrent()
        assertTrue(runtime.sendRequest(GatewayRequests.ping()), "Pong 必须释放 Ping lane")
        transport.receive("""{"kind":"pong"}""")
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)
        assertFalse(events.any { it is GatewayRuntimeEvent.RequestTimedOut })
        transport.receive("""{"kind":"pong"}""")
        runCurrent()
        assertEquals(3, events.filterIsInstance<GatewayRuntimeEvent.Frame>().count { it.frame.kind == "pong" })
    }

    @Test
    fun foregroundProbesHealthySocketAndKeepsItAfterPong() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        val sessionsBeforeResume = transport.sentTypes.count { it == "sessions" }
        runtime.applicationDidEnterBackground()
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(1, transport.sentTypes.count { it == "ping" })
        assertEquals(sessionsBeforeResume + 1, transport.sentTypes.count { it == "sessions" })
        transport.receive("""{"kind":"pong"}""")
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
    }

    @Test
    fun foregroundStaleSocketIsRebuiltAfterTwoSecondsUsingCurrentEndpoint() = runTest {
        val transport = FakeTransport()
        val endpoints = listOf("wss://lan.example/ws/mobile", "wss://cloud.example/ws/mobile")
        val runtime = newRuntime(transport, trustedEndpoints = endpoints)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect(endpoints.first())
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        val oldGeneration = transport.connectionSpecs.last().generation
        runtime.applicationDidEnterBackground()
        runtime.applicationDidBecomeActive()
        runCurrent()
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)
        assertEquals(endpoints.first(), transport.connectionSpecs.last().endpoint)
        transport.receiveAt(oldGeneration, """{"kind":"pong"}""")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
    }

    @Test
    fun failureArrivingJustAfterForegroundReconnectsWithoutBackoffOrEndpointRotation() = runTest {
        val transport = FakeTransport()
        val clock = RecordingDelayClock()
        val endpoints = listOf("wss://lan.example/ws/mobile", "wss://cloud.example/ws/mobile")
        val runtime = newRuntime(transport, clock = clock, trustedEndpoints = endpoints)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect(endpoints.first())
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        runtime.applicationDidEnterBackground()
        runtime.applicationDidBecomeActive()
        runCurrent()
        transport.receive("""{"kind":"pong"}""")
        runCurrent()
        transport.fail(GatewayTransportState.Failed(
            transport.connectionSpecs.last().generation, reason = "websocket-failure", recoverable = true
        ))
        runCurrent()
        assertTrue(clock.delays.isEmpty())
        assertEquals(2, transport.connectionSpecs.size)
        assertEquals(endpoints.first(), transport.connectionSpecs.last().endpoint)
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size, "重复前台通知不得打断正在建立的连接")
        transport.opened()
        runCurrent()
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size, "鉴权期间也不得反复重建")
    }

    @Test
    fun foregroundCancelsLongReconnectBackoff() = runTest {
        val transport = FakeTransport()
        val clock = RecordingDelayClock()
        val runtime = newRuntime(transport, clock = clock)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        repeat(6) { attempt ->
            if (attempt > 0) {
                advanceTimeBy(clock.delays.last())
                runCurrent()
            }
            transport.fail(GatewayTransportState.Failed(
                transport.connectionSpecs.last().generation, reason = "websocket-failure", recoverable = true
            ))
            runCurrent()
        }
        assertEquals(30_000L, clock.delays.last())
        val attempts = transport.connectionSpecs.size
        runtime.applicationDidEnterBackground()
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(attempts + 1, transport.connectionSpecs.size)
        assertEquals(6, clock.delays.size)
    }

    @Test
    fun returningToBackgroundCancelsProbeAndReusesPendingPingOnNextForeground() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        runtime.applicationDidEnterBackground()
        runtime.applicationDidBecomeActive()
        runCurrent()
        runtime.applicationDidEnterBackground()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(1, transport.sentTypes.count { it == "ping" })
        transport.receive("""{"kind":"pong"}""")
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)
        runtime.applicationDidEnterBackground()
        runtime.applicationDidBecomeActive()
        runCurrent()
        runtime.disconnect()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size, "手动断开后不得由探测触发重连")
    }

    @Test
    fun foregroundRetriesPreviouslyConnectedHostAfterSingleEndpointHandshakeTimeout() = runTest {
        val transport = FakeTransport()
        val endpoint = "wss://gateway.example/ws/mobile"
        val runtime = newRuntime(
            transport,
            connectionAttemptTimeoutMilliseconds = 100,
            trustedEndpoints = listOf(endpoint)
        )
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect(endpoint)
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)

        runtime.applicationDidEnterBackground()
        transport.fail(GatewayTransportState.Failed(
            transport.connectionSpecs.last().generation, reason = "websocket-failure", recoverable = true
        ))
        runCurrent()
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)
        transport.opened()
        runCurrent()
        assertEquals(GatewayConnectionState.AUTHENTICATING, runtime.state.value.connection)
        runtime.applicationDidEnterBackground()
        advanceTimeBy(101)
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        assertEquals("connection-timeout", runtime.state.value.lastError)
        assertEquals(2, transport.connectionSpecs.size)

        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(3, transport.connectionSpecs.size, "握手超时后回前台无需重新点击主机")
        assertEquals(endpoint, transport.connectionSpecs.last().endpoint)
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
    }

    @Test
    fun backgroundRecoveryDeadlineDoesNotBlockForegroundReconnect() = runTest {
        val transport = FakeTransport()
        val network = FakeNetworkMonitor()
        val runtime = newRuntime(transport, network = network, recoveryWindowMilliseconds = 1_000)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertTrue(runtime.sendMessage("work", emptyList(), "session-a", null, "UTC"))
        runtime.applicationDidEnterBackground()
        network.mutableState.value = GatewayNetworkState.UNAVAILABLE
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals("recovery-timeout", runtime.state.value.lastError)
        network.mutableState.value = GatewayNetworkState.AVAILABLE
        runCurrent()
        val specCount = transport.connectionSpecs.size
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(specCount + 1, transport.connectionSpecs.size)
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
    }

    @Test
    fun backgroundMessageTimeoutMarksClosedConnectionAndForegroundReconnects() = runTest {
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
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
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
    fun manualDisconnectStopsStoredAutoConnectUntilExplicitConnect() = runTest {
        val preferences = FakePreferences()
        val credentials = FakeCredentials().apply {
            tokens["ws://127.0.0.1:3080/ws/mobile"] = "stored-token"
        }
        val transport = FakeTransport()
        val runtime = GatewayRuntime(
            transport, preferences, credentials, FakeAttachmentCache(),
            FakeNetworkMonitor(), FakeClock(0), backgroundScope
        )
        runCurrent()
        assertTrue(runtime.connectStoredIfPaired())
        runtime.disconnect()
        assertFalse(preferences.load().automaticallyConnect)

        val restartedTransport = FakeTransport()
        val restarted = GatewayRuntime(
            restartedTransport, preferences, credentials, FakeAttachmentCache(),
            FakeNetworkMonitor(), FakeClock(0), backgroundScope
        )
        runCurrent()
        assertFalse(restarted.connectStoredIfPaired())
        assertTrue(restartedTransport.connectionSpecs.isEmpty())

        restarted.connect("ws://127.0.0.1:3080/ws/mobile")
        assertTrue(preferences.load().automaticallyConnect)
        assertEquals(1, restartedTransport.connectionSpecs.size)
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
    fun backgroundLastTurnEndKeepsConnectionAndStoredConnectKeepsWaitingDeadline() = runTest {
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
        transport.receive("""{"kind":"sent","sessionId":"session-a"}""")
        runCurrent()
        runtime.applicationDidEnterBackground()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        val closeBeforeEnd = transport.closeCount
        transport.receive(
            """{"sessionId":"session-a","seq":1,"time":1,"event":{"type":"turn/end"}}"""
        )
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
        assertEquals(closeBeforeEnd, transport.closeCount)

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

    @Test
    fun foregroundReplacesStalledHandshakeWithPreviouslyConnectedEndpoint() = runTest {
        val transport = FakeTransport()
        val endpoints = listOf("wss://public.example/ws/mobile", "ws://192.0.2.10:3081/ws/mobile")
        val runtime = newRuntime(transport, clock = RecordingDelayClock(), trustedEndpoints = endpoints)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect(endpoints.first())
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        transport.fail(GatewayTransportState.Failed(transport.connectionSpecs.last().generation,
            reason = "websocket-failure", recoverable = true))
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)
        assertEquals(endpoints.last(), transport.connectionSpecs.last().endpoint)
        val stalledGeneration = transport.connectionSpecs.last().generation
        runtime.applicationDidEnterBackground()
        advanceTimeBy(1_000)
        runtime.applicationDidBecomeActive()
        runCurrent()
        val returnedAt = testScheduler.currentTime
        assertEquals(3, transport.connectionSpecs.size, "Foreground must replace a stalled pre-background attempt")
        assertEquals(endpoints.first(), transport.connectionSpecs.last().endpoint)
        transport.receiveAt(stalledGeneration, """{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTING, runtime.state.value.connection,
            "A stale handshake must not authenticate the replacement connection")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertEquals(0L, testScheduler.currentTime - returnedAt)
    }

    @Test
    fun foregroundLateFailureRetriesPreviouslyConnectedEndpointOnceBeforeLanFallback() = runTest {
        val transport = FakeTransport()
        val endpoints = listOf("wss://public.example/ws/mobile", "ws://192.0.2.10:3081/ws/mobile")
        val runtime = newRuntime(transport, clock = RecordingDelayClock(), trustedEndpoints = endpoints)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect(endpoints.first())
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        runtime.applicationDidEnterBackground()
        runtime.applicationDidBecomeActive()
        runCurrent()
        transport.receive("""{"kind":"pong"}""")
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        transport.fail(GatewayTransportState.Failed(transport.connectionSpecs.last().generation,
            reason = "websocket-failure", recoverable = true))
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)
        assertEquals(endpoints.first(), transport.connectionSpecs.last().endpoint)
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertEquals(3_000L, testScheduler.currentTime, "A usable public endpoint must recover without the LAN timeout")
        // The foreground retry is bounded and does not disable ordinary failover.
        advanceTimeBy(6_000)
        runCurrent()
        transport.fail(GatewayTransportState.Failed(transport.connectionSpecs.last().generation,
            reason = "websocket-failure", recoverable = true))
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, transport.connectionSpecs.size)
        assertEquals(endpoints.last(), transport.connectionSpecs.last().endpoint)
    }

    @Test
    fun foregroundResumeRetryDoesNotBypassAuthenticationFailureOrManualDisconnect() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport, clock = RecordingDelayClock(),
            trustedEndpoints = listOf("wss://public.example/ws/mobile", "ws://192.0.2.10:3081/ws/mobile"))
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect("wss://public.example/ws/mobile")
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        runtime.applicationDidEnterBackground()
        runtime.applicationDidBecomeActive()
        runCurrent()
        transport.receive("""{"kind":"pong"}""")
        runCurrent()
        transport.fail(GatewayTransportState.Failed(transport.connectionSpecs.last().generation,
            httpStatus = 401, recoverable = false))
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        advanceTimeBy(5_001)
        runCurrent()
        runtime.applicationDidEnterBackground()
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size, "Authentication failure stays blocked across foreground transitions")
        runtime.disconnect()
        runtime.applicationDidEnterBackground()
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size, "Explicit disconnection stays disconnected")
        assertEquals(GatewayConnectionState.DISCONNECTED, runtime.state.value.connection)
    }

    @Test
    fun foregroundResumeRetryCanBeConsumedOnlyOnceWithinTheWindow() = runTest {
        val transport = FakeTransport()
        val endpoints = listOf("wss://public.example/ws/mobile", "ws://192.0.2.10:3081/ws/mobile")
        val runtime = newRuntime(transport, clock = RecordingDelayClock(), trustedEndpoints = endpoints)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect(endpoints.first())
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        runtime.applicationDidEnterBackground()
        runtime.applicationDidBecomeActive()
        runCurrent()
        transport.receive("""{"kind":"pong"}""")
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        transport.fail(GatewayTransportState.Failed(transport.connectionSpecs.last().generation,
            reason = "websocket-failure", recoverable = true))
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)
        assertEquals(endpoints.first(), transport.connectionSpecs.last().endpoint)
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        transport.fail(GatewayTransportState.Failed(transport.connectionSpecs.last().generation,
            reason = "websocket-failure", recoverable = true))
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size, "A second failure must use ordinary bounded backoff")
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, transport.connectionSpecs.size)
        assertEquals(endpoints.last(), transport.connectionSpecs.last().endpoint)
    }

    @Test
    fun foregroundReturnsToPreviouslyConnectedEndpointAfterBackgroundFallbackTimeout() = runTest {
        val transport = FakeTransport()
        val endpoints = listOf("wss://public.example/ws/mobile", "ws://192.0.2.10:3081/ws/mobile")
        val runtime = newRuntime(transport, clock = RecordingDelayClock(), trustedEndpoints = endpoints)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect(endpoints.first())
        transport.opened()
        transport.receive("""{"kind":"hello","authenticated":true}""")
        runCurrent()
        transport.fail(GatewayTransportState.Failed(transport.connectionSpecs.last().generation,
            reason = "websocket-failure", recoverable = true))
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(endpoints.last(), transport.connectionSpecs.last().endpoint)
        runtime.applicationDidEnterBackground()
        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(3, transport.connectionSpecs.size)
        assertEquals(endpoints.first(), transport.connectionSpecs.last().endpoint,
            "Foreground must not retry a failed LAN endpoint ahead of the authenticated public endpoint")
    }

    private fun kotlinx.coroutines.test.TestScope.newRuntime(
        transport: FakeTransport,
        network: FakeNetworkMonitor = FakeNetworkMonitor(),
        cache: FakeAttachmentCache = FakeAttachmentCache(),
        credentials: FakeCredentials = FakeCredentials(),
        requestTimeoutMilliseconds: Long = 30_000,
        recoveryWindowMilliseconds: Long = 60_000,
        connectionAttemptTimeoutMilliseconds: Long = 15_000,
        clock: GatewayClock = FakeClock(0),
        trustedEndpoints: List<String> = emptyList()
    ): GatewayRuntime = GatewayRuntime(
        transport,
        FakePreferences(),
        credentials,
        cache,
        network,
        clock,
        backgroundScope,
        requestTimeoutMilliseconds,
        recoveryWindowMilliseconds,
        connectionAttemptTimeoutMilliseconds,
        trustedEndpoints = trustedEndpoints
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

    private class RecordingDelayClock : GatewayClock {
        val delays = mutableListOf<Long>()
        override fun nowEpochMilliseconds(): Long = 0
        override suspend fun delay(milliseconds: Long) {
            delays += milliseconds
            kotlinx.coroutines.delay(milliseconds)
        }
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
