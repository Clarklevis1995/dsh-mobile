package com.clarklevis.dsh.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.platform.AndroidGatewayDiagnostics
import com.clarklevis.dsh.android.platform.RtcGatewayTransport
import com.clarklevis.dsh.shared.platform.GatewayConnectionSpec
import com.clarklevis.dsh.shared.platform.GatewayTransportEvent
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 显式传入 rtcFixtureUrl 才启动实连；凭据来自 scripts/webrtc-test-gateway.mjs 的临时设备。 */
@RunWith(AndroidJUnit4::class)
class RtcGatewayDeviceTest {
    @Test fun nativeDualChannelInteroperatesAndOldGatewayStillWorks() =
        runBlocking {
            val base = InstrumentationRegistry.getArguments().getString("rtcFixtureUrl")
            assumeTrue("需要本机隔离测试网关", base != null)
            for (mode in listOf("rtc", "legacy", "fallback")) {
                val client = OkHttpClient()
                val response =
                    client.newCall(Request.Builder().url("$base/?mode=$mode").build()).execute().use {
                        Json.parseToJsonElement(requireNotNull(it.body).string()).jsonObject
                    }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
                val transport =
                    RtcGatewayTransport(
                        InstrumentationRegistry.getInstrumentation().targetContext,
                        scope,
                        AndroidGatewayDiagnostics.disabled(),
                        // 隔离的 RTC 库互通测试显式使用 RTC；产品默认内网策略单独验证。
                        rtcPolicy = { true },
                    )
                val frames = Channel<String>(Channel.UNLIMITED)
                val failures = Channel<GatewayTransportState.Failed>(Channel.UNLIMITED)
                val collectors =
                    listOf(transport.events, transport.conversationEvents).map { events ->
                        scope.launch {
                            events.collect { event ->
                                when (event) {
                                    is GatewayTransportEvent.Frame -> frames.send(event.value.text)
                                    is GatewayTransportEvent.State ->
                                        (event.value as? GatewayTransportState.Failed)?.let {
                                            failures.send(
                                                it,
                                            )
                                        }
                                }
                            }
                        }
                    }

                suspend fun awaitKind(kind: String, requestID: String? = null): JsonObject =
                    withTimeout(35000) {
                        while (true) {
                            val failure = failures.tryReceive().getOrNull()
                            check(failure == null) { "transport failed: $failure" }
                            val frame =
                                select<String> {
                                    frames.onReceive { it }
                                    failures.onReceive { throw IllegalStateException("transport failed: $it") }
                                }
                            val value = Json.parseToJsonElement(frame).jsonObject
                            if (value["kind"]?.jsonPrimitive?.content == kind &&
                                (requestID == null || value["requestId"]?.jsonPrimitive?.content == requestID)) return@withTimeout value
                        }
                        error("unreachable")
                    }
                try {
                    val url =
                        response
                            .getValue("url")
                            .jsonPrimitive.content
                            .replace("127.0.0.1", java.net.URI(base).host)
                    transport.open(
                        GatewayConnectionSpec(
                            1,
                            url,
                            response.getValue("deviceId").jsonPrimitive.content,
                            bearerToken = response.getValue("token").jsonPrimitive.content,
                            expectedGatewayId = response.getValue("gatewayId").jsonPrimitive.content,
                        ),
                    )
                    awaitKind("hello")
                    transport.confirmControlHandshake()
                    if (mode == "rtc") assertTrue(transport.connectionMethod.value.startsWith("WebRTC"))
                    if (mode != "rtc") assertEquals("WebSocket", transport.connectionMethod.value)
                    val probeID = "network-check:native:$mode"
                    val probeStarted = android.os.SystemClock.elapsedRealtime()
                    transport.send("{\"type\":\"ping\",\"requestId\":\"$probeID\"}")
                    assertEquals(probeID, awaitKind("pong", probeID)["requestId"]?.jsonPrimitive?.content)
                    assertTrue(android.os.SystemClock.elapsedRealtime() - probeStarted < 3000)
                    transport.send("{\"type\":\"workspaces\"}")
                    assertEquals(
                        "rtc-workspace",
                        awaitKind("workspaces")["items"]!!
                            .jsonArray[0]
                            .jsonObject["workspaceId"]!!
                            .jsonPrimitive.content,
                    )
                    transport.send("{\"type\":\"history\",\"sessionId\":\"interop-session\"}")
                    awaitKind("history")
                    if (mode == "rtc") {
                        // 16 MiB 请求真的经过 libwebrtc/SCTP 和网关分片解码。
                        val overhead = "{\"type\":\"ping\",\"padding\":\"\"}".length
                        transport.send("{\"type\":\"ping\",\"padding\":\"${"x".repeat(16777216 - overhead)}\"}")
                        awaitKind("pong")
                    }
                } finally {
                    transport.close()
                    collectors.forEach { it.cancel() }
                    scope.cancel()
                    client.dispatcher.executorService.shutdown()
                    client.connectionPool.evictAll()
                }
            }
        }
}
