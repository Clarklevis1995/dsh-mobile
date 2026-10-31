// 协议版本、字节偏移和资源预算与网关契约逐项对应，保留原值便于审计。
@file:Suppress("MagicNumber")

package com.clarklevis.dsh.shared.transport

import com.clarklevis.dsh.shared.gateway.GatewayIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 平台只执行动作，不把 bootstrap、票据或 SDP 交给业务层。所有入口必须串行调用。 */
class RtcUpgradeAction(
    val kind: String,
    val channel: String = "control",
    val text: String = "",
) {
    override fun toString() = "RtcUpgradeAction(kind=$kind, channel=$channel, text=<redacted>)"
}

// 公开方法分别对应原生传输事件；由单一状态机维持整组不变量。
@Suppress("TooManyFunctions")
class RtcUpgradeMachine(
    private val expectedGatewayId: String,
    private val nonce: String,
) {
    var phase: String = "initial"
        private set
    var mode: String = ""
        private set
    var diagnosticReason: String = ""
        private set
    var diagnosticPhase: String = "initial"
        private set
    var timeoutMs: Int = 12000
        private set
    var maxFrameBytes: Int = 16384
        private set
    var maxLogicalMessageBytes: Int = 16777216
        private set
    private var gatewayId = ""
    private var sessionId: String? = null
    private var selectMode = ""
    private val tickets = mutableMapOf<String, String>()
    private val opened = mutableSetOf<String>()
    private val bound = mutableSetOf<String>()
    private val hellos = mutableMapOf<String, String>()
    private val outgoing = mutableMapOf<String, Int>()
    private val outgoingEnded = mutableSetOf<String>()
    private val incoming = mutableMapOf<String, Int>()
    private val answered = mutableSetOf<String>()
    private var readyReceived = false
    private var selected = false
    private val json = Json { ignoreUnknownKeys = true }

    // 信令阶段在同一分发边界校验，避免跨阶段动作绕过提交门控。
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition")
    fun webSocket(text: String): List<RtcUpgradeAction> =
        guarded {
            // 业务帧可能很大；已选定 WS 后不再重复扫描业务 JSON。
            if (phase == "websocket") return@guarded listOf(action("deliver", text = text))
            val frame = objectFrame(text, if (phase == "initial") 262144 else 65536)
            val kind = frame.string("kind")
            if (phase == "initial") {
                if (kind != "hello") {
                    require(kind == "paired")
                    return@guarded listOf(action("deliver", text = text))
                }
                GatewayIdentity.validate(expectedGatewayId, frame.string("gatewayId"))
                gatewayId = requireNotNull(frame.string("gatewayId"))
                val transport = frame["transport"] as? JsonObject
                if (transport?.get("bootstrap")?.jsonPrimitive?.booleanOrNull != true) {
                    diagnostic("gateway-no-upgrade")
                    phase = "websocket"
                    mode = "websocket"
                    return@guarded listOf(action("selected", text = mode), action("deliver", text = text))
                }
                require(transport["version"]?.jsonPrimitive?.intOrNull == 1)
                require((frame["capabilities"] as? JsonArray)?.any { it.jsonPrimitive.content == "webrtc-upgrade-v1" } == true)
                timeoutMs = (transport["negotiateTimeoutMs"]?.jsonPrimitive?.intOrNull ?: 12000).coerceIn(100, 60000)
                phase = "negotiating"
                listOf(
                    action("deadline", text = timeoutMs.toString()),
                    ws(
                        buildJsonObject {
                            put("type", "transport-negotiate")
                            put("requestId", "n-$nonce")
                            put("version", 1)
                            putJsonArray("preferred") {
                                add("webrtc")
                                add("websocket")
                            }
                            put("receiveLimits", limits())
                        },
                    ),
                )
            } else {
                when (kind) {
                    "transport-offered" -> {
                        require(phase == "negotiating" && sessionId == null && frame.string("requestId") == "n-$nonce")
                        sessionId =
                            requireNotNull(frame.string("transportSessionId")).also {
                                require(
                                    it.matches(
                                        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"),
                                    ),
                                )
                            }
                        require((frame["channels"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() == CHANNELS.toSet())
                        val limits = frame["receiveLimits"]!!.jsonObject
                        maxFrameBytes = minOf(16384, limits["maxFrameBytes"]!!.jsonPrimitive.int).also { require(it >= 1024) }
                        maxLogicalMessageBytes =
                            minOf(16777216, limits["maxLogicalMessageBytes"]!!.jsonPrimitive.int).also { require(it > 0) }
                        val ice = frame["iceServers"] as? JsonArray ?: error("ice")
                        require(ice.size <= 16)
                        // 仅使用已鉴权网关下发的 STUN，不接受 TURN 或隐式公共服务器。
                        ice.forEach { server ->
                            val urls = server.jsonObject["urls"] ?: error("ice")
                            val values = if (urls is JsonArray) urls else listOf(urls)
                            require(values.isNotEmpty() && values.all { it.jsonPrimitive.content.startsWith("stun:") })
                        }
                        val bind = frame["bindTickets"]!!.jsonObject
                        CHANNELS.forEach { name ->
                            tickets[name] = requireNotNull(bind.string(name)).also { require(it.length in 32..1024) }
                        }
                        CHANNELS.map { action("rtc-start", it, ice.toString()) }
                    }

                    "transport-signal" -> {
                        if (phase == "selecting-websocket") return@guarded emptyList()
                        require(
                            phase in setOf("negotiating", "selecting-webrtc") &&
                                frame.string("transportSessionId") == sessionId && sessionId != null,
                        )
                        // ready 与 selected 之间仍有在途 ICE；此时只接收候选/EOC，不接受新 answer。
                        if (phase == "selecting-webrtc") {
                            require(frame.string("signalType") in setOf("candidate", "end-of-candidates"))
                        }
                        val name = requireChannel(frame.string("channel"))
                        val seq = (incoming[name] ?: 0) + 1
                        require(frame["signalSeq"]?.jsonPrimitive?.intOrNull == seq)
                        incoming[name] = seq
                        when (frame.string("signalType")) {
                            "answer" -> {
                                require(answered.add(name))
                                require((frame.string("sdp")?.encodeToByteArray()?.size ?: Int.MAX_VALUE) <= 49152)
                                require(frame.string("sdp")!!.lineSequence().count { it.startsWith("m=") } == 1)
                                require(frame.string("sdp")!!.lineSequence().any { it.startsWith("m=application ") })
                            }

                            "candidate" -> {
                                require(
                                    (
                                        frame["candidate"]?.jsonObject?.string("candidate")?.let {
                                            it.startsWith("candidate:") && it.encodeToByteArray().size <= 4096
                                        }
                                    ) == true,
                                )
                            }

                            "end-of-candidates" -> {
                                Unit
                            }

                            else -> {
                                error("signal")
                            }
                        }
                        listOf(action("rtc-signal", name, text))
                    }

                    "transport-ready" -> {
                        require(phase == "negotiating" && frame.string("transportSessionId") == sessionId)
                        require(
                            frame["channels"]?.jsonObject?.let {
                                CHANNELS.all { name -> it.string(name) == "bound" }
                            } == true,
                        )
                        readyReceived = true
                        if (bound.size == 2) select("webrtc") else emptyList()
                    }

                    "transport-selected" -> {
                        if (phase == "active" && frame.string("requestId") == "s-$nonce" &&
                            frame.string("transportSessionId") == sessionId && frame.string("mode") == mode
                        ) {
                            return@guarded emptyList()
                        }
                        require(phase.startsWith("selecting-") && frame.string("requestId") == "s-$nonce")
                        require(frame.string("transportSessionId") == sessionId && frame.string("mode") == selectMode)
                        selected = true
                        mode = selectMode
                        tickets.clear()
                        if (mode == "websocket") {
                            phase = "websocket"
                            listOf(action("cancel-deadline"), action("selected", text = mode))
                        } else {
                            activate()
                        }
                    }

                    "transport-failed" -> {
                        require(frame.string("transportSessionId") == sessionId)
                        if (frame["recoverable"]?.jsonPrimitive?.booleanOrNull != true) {
                            terminal(false, "gateway-security-failure")
                        } else if (phase == "negotiating") {
                            fallback("gateway-rtc-unavailable")
                        } else {
                            terminal(true, "gateway-rtc-lost")
                        }
                    }

                    "error" -> {
                        if (frame.string("code") == "transport/negotiation-ended" &&
                            phase == "selecting-websocket"
                        ) {
                            emptyList()
                        } else {
                            terminal(false)
                        }
                    }

                    "pong" -> {
                        emptyList()
                    }

                    else -> {
                        error("unexpected-signal")
                    }
                }
            }
        }

    fun localSignal(
        channel: String,
        signalType: String,
        payload: String,
    ): List<RtcUpgradeAction> =
        guarded {
            if (phase != "negotiating") return@guarded emptyList()
            val name = requireChannel(channel)
            // 原生库可能因接口变动重复回调 complete；一旦发出 EOC，本代次候选流必须封口。
            if (name in outgoingEnded) return@guarded emptyList()
            require(sessionId != null)
            val seq = (outgoing[name] ?: 0) + 1
            outgoing[name] = seq
            listOf(
                ws(
                    buildJsonObject {
                        put("type", "transport-signal")
                        put("transportSessionId", sessionId)
                        put("channel", name)
                        put("signalSeq", seq)
                        put("signalType", signalType)
                        when (signalType) {
                            "offer" -> {
                                require(seq == 1 && payload.encodeToByteArray().size <= 49152)
                                put("sdp", payload)
                            }

                            "candidate" -> {
                                require(seq > 1)
                                val candidate = objectFrame(payload, 8192)
                                require(candidate.string("candidate")?.startsWith("candidate:") == true)
                                require(candidate.string("candidate")!!.encodeToByteArray().size <= 4096)
                                put("candidate", candidate)
                            }

                            "end-of-candidates" -> {
                                require(seq > 1)
                                outgoingEnded.add(name)
                            }

                            else -> {
                                error("signal")
                            }
                        }
                    },
                ),
            )
        }

    fun rtcOpened(channel: String): List<RtcUpgradeAction> =
        guarded {
            if (phase != "negotiating") return@guarded emptyList()
            val name = requireChannel(channel)
            require(opened.add(name))
            listOf(
                action(
                    "rtc-text",
                    name,
                    buildJsonObject {
                        put("type", "transport-bind")
                        put("version", 1)
                        put("gatewayId", gatewayId)
                        put("transportSessionId", sessionId)
                        put("channel", name)
                        put("ticket", tickets.remove(name) ?: error("ticket"))
                    }.toString(),
                ),
            )
        }

    fun rtcText(
        channel: String,
        text: String,
    ): List<RtcUpgradeAction> =
        guarded {
            val name = requireChannel(channel)
            require(phase == "negotiating" && name in opened && name !in bound)
            val frame = objectFrame(text, 4096)
            require(frame.string("kind") == "transport-bound" && frame["version"]?.jsonPrimitive?.intOrNull == 1)
            require(
                frame.string(
                    "gatewayId",
                ) == gatewayId && frame.string("transportSessionId") == sessionId && frame.string("channel") == name,
            )
            bound.add(name)
            if (readyReceived && bound.size == 2) select("webrtc") else emptyList()
        }

    fun rtcMessage(
        channel: String,
        text: String,
    ): List<RtcUpgradeAction> =
        guarded {
            val name = requireChannel(channel)
            require(name in bound)
            if (phase == "active") {
                require(text.encodeToByteArray().size <= 16777216)
                json.parseToJsonElement(text).jsonObject
                return@guarded listOf(action("deliver", name, text))
            }
            require(phase == "selecting-webrtc" && !hellos.containsKey(name))
            require(hellos.values.sumOf { it.encodeToByteArray().size } + text.encodeToByteArray().size <= 262144)
            val frame = objectFrame(text, 262144)
            require(
                frame.string("kind") == "hello" && frame["transport"]
                    ?.jsonObject
                    ?.get("bootstrap")
                    ?.jsonPrimitive
                    ?.booleanOrNull != true,
            )
            GatewayIdentity.validate(gatewayId, frame.string("gatewayId"))
            hellos[name] = text
            activate()
        }

    /** 原生库失败仅在 commit 之前允许显式回退；证书/协议错误调用 securityFailure。 */
    fun rtcUnavailable(): List<RtcUpgradeAction> =
        when (phase) {
            "negotiating" -> fallback("native-rtc-unavailable")
            "selecting-websocket", "websocket", "closed" -> emptyList()
            else -> terminal(true, "rtc-connection-lost")
        }

    fun securityFailure(): List<RtcUpgradeAction> = terminal(false)

    fun deadline(): List<RtcUpgradeAction> =
        if (phase ==
            "negotiating"
        ) {
            fallback("negotiation-timeout")
        } else if (phase != "active" && phase != "websocket") {
            terminal(true, "selection-timeout")
        } else {
            emptyList()
        }

    fun close() {
        phase = "closed"
        tickets.clear()
        hellos.clear()
    }

    private fun activate(): List<RtcUpgradeAction> {
        if (!selected || hellos.size != 2) return emptyList()
        phase = "active"
        val result =
            listOf(action("cancel-deadline"), action("selected", text = "webrtc")) +
                CHANNELS.map { action("rtc-send", it, "{\"type\":\"ping\"}") } +
                CHANNELS.map { action("deliver", it, hellos.getValue(it)) }
        hellos.clear()
        return result
    }

    private fun fallback(reason: String): List<RtcUpgradeAction> {
        diagnostic(reason)
        if (sessionId == null) return terminal(true, reason)
        tickets.clear()
        hellos.clear()
        return listOf(action("rtc-close")) + select("websocket")
    }

    private fun select(value: String): List<RtcUpgradeAction> {
        selectMode = value
        phase = "selecting-$value"
        return listOf(
            ws(
                buildJsonObject {
                    put("type", "transport-select")
                    put("requestId", "s-$nonce")
                    put("mode", value)
                    sessionId?.let { put("transportSessionId", it) }
                },
            ),
        )
    }

    private fun diagnostic(reason: String) {
        diagnosticReason = reason
        diagnosticPhase = phase
    }

    private fun terminal(
        recoverable: Boolean,
        reason: String = "protocol-validation-failed",
    ): List<RtcUpgradeAction> {
        diagnostic(reason)
        close()
        return listOf(action("rtc-close"), action(if (recoverable) "retry-websocket" else "security-failure"))
    }

    private inline fun guarded(block: () -> List<RtcUpgradeAction>): List<RtcUpgradeAction> {
        if (phase == "closed") return emptyList()
        return try {
            block()
        } catch (_: Exception) {
            terminal(false)
        }
    }

    private fun limits() =
        buildJsonObject {
            put("maxFrameBytes", 16384)
            put("maxLogicalMessageBytes", 16777216)
        }

    private fun objectFrame(
        text: String,
        limit: Int,
    ): JsonObject {
        require(text.encodeToByteArray().size <= limit)
        StrictTransportJson.validate(text)
        return json.parseToJsonElement(text).jsonObject
    }

    private fun requireChannel(name: String?) = requireNotNull(name).also { require(it in CHANNELS) }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

    private fun action(
        kind: String,
        channel: String = "control",
        text: String = "",
    ) = RtcUpgradeAction(kind, channel, text)

    private fun ws(frame: JsonObject) = action("ws-send", text = frame.toString())

    companion object {
        private val CHANNELS = listOf("control", "conversation")
    }
}

/** kotlinx.serialization 默认接受重复键；信令解析前扫描每层对象，按解码后的键判重。 */
internal object StrictTransportJson {
    // 递归对象/数组扫描共用一个游标，解码键后判重。
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    fun validate(text: String) {
        var cursor = 0

        fun whitespace() {
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        }

        fun string(): String {
            val start = cursor
            require(text[cursor++] == '"')
            while (cursor < text.length) {
                when (text[cursor++]) {
                    '\\' -> cursor++
                    '"' -> return Json.decodeFromString<String>(text.substring(start, cursor))
                }
            }
            error("json")
        }

        fun value(depth: Int) {
            require(depth <= 32)
            whitespace()
            require(cursor < text.length)
            when (text[cursor]) {
                '{' -> {
                    cursor++
                    whitespace()
                    val keys = mutableSetOf<String>()
                    if (cursor < text.length && text[cursor] != '}') {
                        while (true) {
                            whitespace()
                            require(keys.add(string()))
                            whitespace()
                            require(text[cursor++] == ':')
                            value(depth + 1)
                            whitespace()
                            if (text[cursor] != ',') break
                            cursor++
                        }
                    }
                    require(text[cursor++] == '}')
                }

                '[' -> {
                    cursor++
                    whitespace()
                    if (cursor < text.length && text[cursor] != ']') {
                        while (true) {
                            value(depth + 1)
                            whitespace()
                            if (text[cursor] != ',') break
                            cursor++
                        }
                    }
                    require(text[cursor++] == ']')
                }

                '"' -> {
                    string()
                }

                else -> {
                    val start = cursor
                    while (cursor < text.length &&
                        text[cursor] !in ",}] \n\r\t"
                    ) {
                        cursor++
                    }
                    require(cursor > start)
                }
            }
        }
        value(0)
        whitespace()
        require(cursor == text.length)
    }
}
