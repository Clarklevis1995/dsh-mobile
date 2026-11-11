// 原生 Observer 必须实现全部接口回调；协议版本、字节偏移和资源预算与网关契约逐项对应，保留原值便于审计。
@file:Suppress("MagicNumber", "TooManyFunctions")

package com.clarklevis.dsh.android.platform

import android.content.Context
import com.clarklevis.dsh.shared.transport.MgwCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer

/** 仅数据通道；不创建音视频 track，也不读取麦克风、摄像头。 */
internal class AndroidRtcPeer(
    context: Context,
    private val name: String,
    iceJson: String,
    private val callback: (String, String, ByteArray?) -> Unit,
) {
    private val factory = factory(context)
    private val codec = MgwCodec(if (name == "control") 1 else 2)
    private val sendLock = Mutex()

    @Volatile private var disposed = false
    private var localReady = false
    private var remoteReady = false
    private val statsPending =
        java.util.concurrent.atomic
            .AtomicBoolean()

    fun requestPathStats() {
        if (disposed || !statsPending.compareAndSet(false, true)) return
        pc.getStats { report ->
            statsPending.set(false)
            if (disposed) return@getStats
            val types = setOf("transport", "candidate-pair", "local-candidate", "remote-candidate")
            val keys =
                setOf(
                    "selectedCandidatePairId",
                    "localCandidateId",
                    "remoteCandidateId",
                    "state",
                    "selected",
                    "candidateType",
                    "address",
                    "ip",
                    "port",
                    "protocol",
                )
            val stats =
                report.statsMap.values.filter { it.type in types }.map { stat ->
                    buildJsonObject {
                        put("id", stat.id)
                        put("type", stat.type)
                        stat.members.filterKeys { it in keys }.forEach { (key, value) ->
                            when (value) {
                                is Boolean -> put(key, JsonPrimitive(value))
                                is Number -> put(key, JsonPrimitive(value))
                                is String -> put(key, JsonPrimitive(value))
                            }
                        }
                    }
                }
            emit("path-stats", JsonArray(stats).toString())
        }
    }

    private val localCandidates = ArrayDeque<IceCandidate?>()
    private val remoteCandidates = ArrayDeque<IceCandidate>()
    private val pc: PeerConnection
    private val dc: DataChannel

    init {
        val ice =
            Json.parseToJsonElement(iceJson).jsonArray.map { item ->
                val urls = item.jsonObject.getValue("urls")
                val values = if (urls is JsonArray) urls.map { it.jsonPrimitive.content } else listOf(urls.jsonPrimitive.content)
                PeerConnection.IceServer.builder(values).createIceServer()
            }
        val configuration =
            PeerConnection.RTCConfiguration(ice).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            }
        pc =
            requireNotNull(
                factory.createPeerConnection(
                    configuration,
                    object : PeerConnection.Observer {
                        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit

                        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                            if (state == PeerConnection.IceConnectionState.FAILED) emit("unavailable", "ice-failed")
                            if (state == PeerConnection.IceConnectionState.CLOSED) emit("unavailable", "ice-closed")
                        }

                        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                            // FAILED 可以是 DTLS 指纹错误，保守终止，不能当普通打洞失败降级。
                            if (state == PeerConnection.PeerConnectionState.FAILED) emit("security", "connection-lost")
                        }

                        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

                        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                                synchronized(localCandidates) {
                                    if (localReady) emit("end-of-candidates") else localCandidates.addLast(null)
                                }
                            }
                        }

                        override fun onIceCandidate(candidate: IceCandidate?) {
                            candidate ?: return
                            synchronized(localCandidates) {
                                if (localReady) {
                                    signalCandidate(candidate)
                                } else {
                                    if (localCandidates.size >= 256) emit("security") else localCandidates.addLast(candidate)
                                }
                            }
                        }

                        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

                        override fun onAddStream(stream: MediaStream?) = emit("security")

                        override fun onRemoveStream(stream: MediaStream?) = Unit

                        override fun onDataChannel(channel: DataChannel?) {
                            channel?.close()
                            emit("security")
                        }

                        override fun onRenegotiationNeeded() = Unit

                        override fun onAddTrack(
                            receiver: RtpReceiver?,
                            streams: Array<out MediaStream>?,
                        ) = emit("security")
                    },
                ),
            )
        dc = requireNotNull(pc.createDataChannel("dsh-mobile-$name-v1", DataChannel.Init().apply { ordered = true }))
        dc.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() {
                    when (dc.state()) {
                        DataChannel.State.OPEN -> emit("open")
                        DataChannel.State.CLOSED -> emit("unavailable", "datachannel-closed")
                        else -> Unit
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    if (bytes.size > 16384 || bytes.isEmpty()) {
                        emit("security")
                    } else {
                        callback(if (buffer.binary) "binary" else "text", "", bytes)
                    }
                }
            },
        )
    }

    fun start() {
        pc.createOffer(
            observer(onCreate = { description ->
                pc.setLocalDescription(
                    observer(onSet = {
                        synchronized(localCandidates) {
                            emit("offer", description.description)
                            localReady = true
                            while (localCandidates.isNotEmpty()) {
                                val candidate = localCandidates.removeFirst()
                                if (candidate == null) emit("end-of-candidates") else signalCandidate(candidate)
                            }
                        }
                    }),
                    description,
                )
            }),
            MediaConstraints(),
        )
    }

    fun signal(text: String) {
        if (disposed) return
        val frame = Json.parseToJsonElement(text).jsonObject
        when (frame["signalType"]?.jsonPrimitive?.content) {
            "answer" -> {
                pc.setRemoteDescription(
                    observer(onSet = {
                        synchronized(remoteCandidates) {
                            remoteReady = true
                            while (remoteCandidates.isNotEmpty()) pc.addIceCandidate(remoteCandidates.removeFirst())
                        }
                    }),
                    SessionDescription(SessionDescription.Type.ANSWER, frame.getValue("sdp").jsonPrimitive.content),
                )
            }

            "candidate" -> {
                val value = frame.getValue("candidate").jsonObject
                val candidate =
                    IceCandidate(
                        value["sdpMid"]?.jsonPrimitive?.contentOrNull,
                        value["sdpMLineIndex"]?.jsonPrimitive?.intOrNull ?: 0,
                        value.getValue("candidate").jsonPrimitive.content,
                    )
                synchronized(remoteCandidates) {
                    if (remoteReady) {
                        pc.addIceCandidate(candidate)
                    } else if (remoteCandidates.size >= 256) {
                        emit("security")
                    } else {
                        remoteCandidates.addLast(candidate)
                    }
                }
            }
        }
    }

    fun text(text: String) {
        check(!disposed && dc.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false)))
    }

    suspend fun send(
        text: String,
        frameLimit: Int,
        messageLimit: Int,
    ) = sendLock.withLock {
        check(!disposed)
        val frames = codec.encode(text, frameLimit, messageLimit)
        withTimeout(30000) {
            for (frame in frames) {
                if (dc.bufferedAmount() > 256 * 1024) {
                    while (!disposed && dc.bufferedAmount() > 64 * 1024) delay(10)
                }
                check(!disposed && dc.send(DataChannel.Buffer(ByteBuffer.wrap(frame), true)))
            }
        }
    }

    fun receive(bytes: ByteArray): String? = codec.receive(bytes, android.os.SystemClock.elapsedRealtime())

    fun checkIdle() = codec.checkIdle(android.os.SystemClock.elapsedRealtime())

    fun close() {
        if (disposed) return
        disposed = true
        dc.unregisterObserver()
        dc.close()
        pc.close()
        dc.dispose()
        pc.dispose()
        codec.close()
        synchronized(localCandidates) { localCandidates.clear() }
        synchronized(remoteCandidates) { remoteCandidates.clear() }
    }

    private fun signalCandidate(candidate: IceCandidate) =
        emit(
            "candidate",
            buildJsonObject {
                put("candidate", candidate.sdp.removePrefix("a="))
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }.toString(),
        )

    private fun emit(
        kind: String,
        text: String = "",
    ) {
        if (!disposed) callback(kind, text, null)
    }

    private fun observer(
        onCreate: (SessionDescription) -> Unit = {},
        onSet: () -> Unit = {},
    ) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            if (!disposed && sdp != null) onCreate(sdp)
        }

        override fun onSetSuccess() {
            if (!disposed) onSet()
        }

        override fun onCreateFailure(error: String?) = emit("unavailable", "sdp-offer-failed")

        override fun onSetFailure(error: String?) = emit("security", "sdp-set-failed")
    }

    companion object {
        @Volatile private var sharedFactory: PeerConnectionFactory? = null

        private fun factory(context: Context): PeerConnectionFactory =
            sharedFactory ?: synchronized(this) {
                sharedFactory ?: run {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions
                            .builder(context.applicationContext)
                            .setEnableInternalTracer(false)
                            .createInitializationOptions(),
                    )
                    PeerConnectionFactory.builder().createPeerConnectionFactory().also { sharedFactory = it }
                }
            }
    }
}
