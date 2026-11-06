package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.gateway.GatewayRequestLanePolicy
import com.clarklevis.dsh.shared.i18n.UiMessage
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.random.Random

private const val WAVEFORM_HISTORY_SIZE = 45
private const val WAVEFORM_TRIGGER_LEVEL = 0.08f
private const val RECORDING_WARNING_SECONDS = 10
private const val SECONDS_PER_MINUTE = 60

/** 两端只渲染此状态；识别流程、音量归一化与草稿冲突策略只有一份。 */
data class SharedVoiceInputSnapshot(
    val phase: String = "idle",
    val voiceMode: Boolean = false,
    val keyboardVisible: Boolean = false,
    val supported: Boolean = false,
    val connected: Boolean = false,
    val ready: Boolean = false,
    val canPrepare: Boolean = false,
    val preparing: Boolean = false,
    val preparationProgress: Double = 0.0,
    val level: Float = 0f,
    val waveformLevels: List<Float> = List(WAVEFORM_HISTORY_SIZE) { 0f },
    val waveformActive: Boolean = false,
    val alertMessage: UiMessage? = null,
    val recordingSeconds: Double = 0.0,
    val recordingLimitSeconds: Double = VoiceAudioFormat.MAX_DURATION_SECONDS,
    val cancelArmed: Boolean = false,
    val error: UiMessage? = null,
    val pendingTranscript: String? = null,
) {
    val hint: UiMessage get() = UiMessage("voice.hint")
    val visualActivity: Float get() = if (waveformActive) 1f else 0f
    val active: Boolean get() = phase != "idle"

    // 松手后任务继续在后台执行，蒙层和输入保护只随用户持有录音显示。
    val overlayVisible: Boolean get() = phase in setOf("authorizing", "starting", "recording")
    val inputBlocked: Boolean get() = phase in setOf("authorizing", "starting", "recording")
    val touchPending: Boolean get() = phase == "priming"
    val recording: Boolean get() = phase in setOf("authorizing", "starting", "recording", "stopping")
    val remainingRecordingSeconds: Double get() = (recordingLimitSeconds - recordingSeconds).coerceAtLeast(0.0)
    val recordingLimitApproaching: Boolean get() = phase == "recording" && remainingRecordingSeconds <= RECORDING_WARNING_SECONDS
    val recordingTimeLabel: String get() = "${clock(recordingSeconds.toInt())} / ${clock(ceil(recordingLimitSeconds).toInt())}"

    private fun clock(seconds: Int): String =
        "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"

    /** 蒙层外不显示状态行；为 null 时平台不渲染。 */
    val status: UiMessage? get() =
        when {
            !overlayVisible -> null
            cancelArmed -> UiMessage("voice.status.releaseToCancel")
            phase == "authorizing" -> UiMessage("voice.status.authorizing")
            phase == "starting" -> UiMessage("voice.status.starting")
            recordingLimitApproaching -> UiMessage("voice.status.limitApproaching")
            phase == "recording" -> UiMessage("voice.status.recording")
            preparing -> UiMessage("voice.status.preparing")
            else -> error
        }
}

data class VoiceHardwareEffect(
    val action: String,
    val recordingId: String = "",
    val discard: Boolean = false,
    val message: UiMessage? = null,
    val delayMilliseconds: Long = 0,
)

data class SharedVoiceInputTransition(
    val snapshot: SharedVoiceInputSnapshot,
    val requests: List<GatewayRequest> = emptyList(),
    val effects: List<VoiceHardwareEffect> = emptyList(),
    val draftUpdate: String? = null,
)

/** 平台主线程串行发送 UI/硬件 Intent；一个状态源接收携带录音 ID 的 I/O effect 回调。 */
@Suppress("TooManyFunctions")
class SharedVoiceInputStore {
    private var state = SharedVoiceInputSnapshot()
    private var contextId: String? = null
    private var draft = ""
    private var originalDraft = ""
    private var originalRevision = 0L
    private var draftRevision = 0L
    private val nonce = Random.nextLong().toULong().toString(16)
    private var ordinal = 0L
    private var recordingId: String? = null
    private var catalogId: String? = null
    private var followId: String? = null
    private var preparationId: String? = null
    private var providerId: String? = null
    private var language: String? = null
    private var recordedProvider: String? = null
    private var recordedLanguage: String? = null
    private var startedAt = 0L
    private var phaseStartedAt = 0L
    private var stopWhenCaptureStarts = false
    private var captureReady = false
    private var maxAudioBytes = VoiceAudioFormat.MAX_CAPTURE_BYTES
    private var maxDurationSeconds = VoiceAudioFormat.MAX_DURATION_SECONDS

    fun snapshot(): SharedVoiceInputSnapshot = state

    private fun id() = "voice-$nonce-${++ordinal}"

    private fun result(
        requests: List<GatewayRequest> = emptyList(),
        effects: List<VoiceHardwareEffect> = emptyList(),
        draftUpdate: String? = null,
    ) = SharedVoiceInputTransition(state, requests, effects, draftUpdate)

    private fun request(
        type: String,
        requestId: String = id(),
        content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {
        },
    ): GatewayRequest {
        val response =
            when (type) {
                "speech-subscribe" -> "speech-subscribed"
                "speech-unsubscribe" -> "speech-unsubscribed"
                "speech-prepare" -> "speech-preparing"
                "speech-transcribe" -> "speech-transcript"
                "speech-cancel" -> "speech-cancelled"
                else -> type
            }
        return GatewayRequest(
            requestType = type,
            responseKind = response,
            correlationId = requestId,
            lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY,
            payload =
                buildJsonObject {
                    put("type", type)
                    put("requestId", requestId)
                    content()
                }.toString(),
            timeoutMilliseconds = if (type == "speech-transcribe") 185_000 else null,
        )
    }

    fun open(context: String, currentDraft: String): SharedVoiceInputTransition {
        val cancelled = if (contextId != context && contextId != null) suspendCapture() else result()
        if (contextId != context) {
            state = state.copy(voiceMode = false, pendingTranscript = null, error = null, alertMessage = null)
        }
        contextId = context
        val draftTransition = draftChanged(currentDraft)
        return cancelled.copy(
            snapshot = state,
            effects =
            cancelled.effects + draftTransition.effects +
                if (canWarmCapture()) listOf(VoiceHardwareEffect("warmCapture")) else emptyList()
        )
    }

    fun draftChanged(value: String): SharedVoiceInputTransition {
        if (draft != value) {
            draft = value
            draftRevision++
        }
        if (value.isNotBlank() && !state.recording && !state.touchPending) return setVoiceMode(false)
        return result()
    }

    /** 输入方式由共享层持有；退出语音态只取消持有中的录音，后台转写仍可回填草稿。 */
    fun setVoiceMode(enabled: Boolean): SharedVoiceInputTransition {
        if (state.voiceMode == enabled) return result()
        val cancelled = if (!enabled && (state.inputBlocked || state.touchPending)) cancel() else result()
        state = state.copy(voiceMode = enabled, keyboardVisible = if (enabled) false else state.keyboardVisible)
        val effects = when {
            !enabled -> listOf(VoiceHardwareEffect("releaseCapture"))
            canWarmCapture() -> listOf(VoiceHardwareEffect("warmCapture"))
            else -> emptyList()
        }
        return cancelled.copy(snapshot = state, effects = cancelled.effects + effects)
    }

    private fun canWarmCapture(): Boolean = contextId != null && state.voiceMode && !state.keyboardVisible && state.ready && !state.active

    fun keyboardChanged(visible: Boolean): SharedVoiceInputTransition {
        state = state.copy(keyboardVisible = visible)
        return if (visible) setVoiceMode(false) else result()
    }

    fun connectionChanged(
        connected: Boolean,
        supported: Boolean,
    ): SharedVoiceInputTransition {
        if (!connected || !supported) {
            val cancelled = suspendCapture()
            catalogId = null
            followId = null
            state =
                state.copy(
                    connected = connected,
                    supported = supported,
                    ready = false,
                    canPrepare = false,
                    preparing = false,
                )
            return cancelled.copy(snapshot = state, requests = emptyList())
        }
        if (state.connected && state.supported && followId != null) return result()
        state = state.copy(connected = true, supported = true, ready = false, canPrepare = false, preparing = false)
        catalogId = id()
        followId = id()
        return result(
            listOf(
                request("speech-catalog", requireNotNull(catalogId)),
                request("speech-subscribe", requireNotNull(followId)),
            ),
        )
    }

    private fun beginCapture(
        phase: String,
        nowMilliseconds: Long,
    ) {
        stopWhenCaptureStarts = false
        captureReady = false
        recordingId = id()
        phaseStartedAt = nowMilliseconds
        originalDraft = draft
        originalRevision = draftRevision
        recordedProvider = providerId
        recordedLanguage = language
        state =
            state.copy(
                phase = phase,
                error = null,
                alertMessage = null,
                waveformLevels = List(WAVEFORM_HISTORY_SIZE) { 0f },
                level = 0f,
                waveformActive = false,
                recordingSeconds = 0.0,
                cancelArmed = false,
                pendingTranscript = null,
            )
    }

    /** 已授权时从真实 down 启动预采集；未确认长按前不显示蒙层、不发送音频。 */
    fun touchDown(
        nowMilliseconds: Long,
        microphoneGranted: Boolean,
    ): SharedVoiceInputTransition {
        if (!state.voiceMode ||
            !microphoneGranted ||
            state.active ||
            state.keyboardVisible ||
            !state.connected ||
            !state.supported ||
            !state.ready
        ) {
            return result()
        }
        beginCapture("priming", nowMilliseconds)
        return result(effects = listOf(VoiceHardwareEffect("startTicker"), VoiceHardwareEffect("start", requireNotNull(recordingId))))
    }

    /** 短按或原生手势失败只丢弃预采集，不影响已经确认的长按或后台转写。 */
    fun touchFinished(): SharedVoiceInputTransition = if (state.touchPending) cancel() else result()

    fun press(nowMilliseconds: Long): SharedVoiceInputTransition {
        if (!state.voiceMode) return result()
        if (state.touchPending) {
            state = state.copy(phase = if (captureReady) "recording" else "starting")
            phaseStartedAt = nowMilliseconds
            return result(effects = listOf(VoiceHardwareEffect("hapticStart")))
        }
        if (state.active || state.keyboardVisible) return result()
        val unavailableMessage =
            when {
                !state.connected -> UiMessage("voice.unavailable.notConnected")
                !state.supported -> UiMessage("voice.unavailable.unsupported")
                !state.ready && state.preparing -> UiMessage("voice.unavailable.preparing")
                !state.ready -> UiMessage("voice.unavailable.notReady")
                else -> null
            }
        if (unavailableMessage != null) {
            state = state.copy(alertMessage = unavailableMessage, error = null)
            return result()
        }
        beginCapture("authorizing", nowMilliseconds)
        return result(
            effects =
                listOf(
                    VoiceHardwareEffect("startTicker"),
                    VoiceHardwareEffect("hapticStart"),
                    VoiceHardwareEffect("permission", requireNotNull(recordingId)),
                ),
        )
    }

    fun permissionResult(
        id: String,
        granted: Boolean,
        nowMilliseconds: Long,
    ): SharedVoiceInputTransition {
        if (id != recordingId || state.phase != "authorizing") return result()
        if (!granted) return fail(UiMessage("voice.permissionDenied"))
        phaseStartedAt = nowMilliseconds
        state = state.copy(phase = "starting")
        return result(effects = listOf(VoiceHardwareEffect("start", id)))
    }

    fun captureStarted(
        id: String,
        nowMilliseconds: Long,
    ): SharedVoiceInputTransition {
        if (id == recordingId && state.phase == "stopping" && stopWhenCaptureStarts) {
            stopWhenCaptureStarts = false
            phaseStartedAt = nowMilliseconds
            return result(effects = listOf(stopCapture(id)))
        }
        if (id == recordingId && state.touchPending) {
            captureReady = true
            startedAt = nowMilliseconds
            return result()
        }
        if (id == recordingId && state.phase == "recording") return result()
        if (id != recordingId || state.phase != "starting") {
            return result(effects = listOf(VoiceHardwareEffect("stop", id, true)))
        }
        startedAt = nowMilliseconds
        phaseStartedAt = nowMilliseconds
        captureReady = true
        state = state.copy(phase = "recording")
        return result()
    }

    fun captureFailed(
        id: String,
        message: UiMessage,
    ): SharedVoiceInputTransition =
        when {
            id != recordingId -> result()
            state.touchPending -> cancel()
            else -> fail(message)
        }

    fun level(
        id: String,
        rms: Float,
        nowMilliseconds: Long,
    ): SharedVoiceInputTransition {
        if (id != recordingId || state.phase !in setOf("priming", "recording")) return result()
        val normalized =
            if (rms.isFinite()) {
                (
                    (
                        20.0 *
                            ln(
                                rms.coerceIn(0.0001f, 1f).toDouble(),
                            ) / ln(10.0) + 55.0
                    ) / 55.0
                ).coerceIn(0.0, 1.0).toFloat()
            } else {
                0f
            }
        val weight = if (normalized > state.level) 0.55f else 0.18f
        val smoothed = state.level + (normalized - state.level) * weight
        // 首次有效声音后保持波形到本次录音结束；停顿只降低振幅，不再退回自由粒子。
        state =
            state.copy(
                level = smoothed,
                waveformLevels = state.waveformLevels.drop(1) + smoothed,
                waveformActive = state.waveformActive || normalized > WAVEFORM_TRIGGER_LEVEL,
            )
        return result()
    }

    fun pointerMoved(verticalDistance: Float): SharedVoiceInputTransition {
        if (state.phase !in setOf("authorizing", "starting", "recording")) return result()
        val armed = verticalDistance < -64f
        if (armed == state.cancelArmed) return result()
        state = state.copy(cancelArmed = armed)
        return result(effects = if (armed) listOf(VoiceHardwareEffect("hapticCancel")) else emptyList())
    }

    fun release(nowMilliseconds: Long): SharedVoiceInputTransition {
        if (state.phase !in setOf("authorizing", "starting", "recording")) return result()
        val finished = finishHold(nowMilliseconds)
        return finished.copy(effects = listOf(VoiceHardwareEffect("hapticRelease")) + finished.effects)
    }

    // 自动达到录音上限也结束录音，但不伪造一次用户松手的触感。
    private fun finishHold(nowMilliseconds: Long): SharedVoiceInputTransition {
        if (state.phase == "authorizing" || state.cancelArmed) return cancel()
        if (state.phase == "starting") {
            stopWhenCaptureStarts = true
            state = state.copy(phase = "stopping", level = 0f, waveformActive = false)
            phaseStartedAt = nowMilliseconds
            return result()
        }
        if (state.phase != "recording") return result()
        state = state.copy(phase = "stopping", level = 0f, waveformActive = false)
        phaseStartedAt = nowMilliseconds
        return result(effects = listOf(stopCapture(requireNotNull(recordingId))))
    }

    private fun stopCapture(id: String) =
        VoiceHardwareEffect(
            "stop",
            id,
            delayMilliseconds = VoiceInputInteraction.CAPTURE_TAIL_MILLISECONDS,
        )

    fun captureFinished(
        id: String,
        pcmBase64: String,
        nowMilliseconds: Long,
    ): SharedVoiceInputTransition {
        if (id != recordingId || state.phase != "stopping") return result()
        val capturedPcm = runCatching { Base64.decode(pcmBase64) }.getOrNull() ?: return fail(UiMessage("voice.recordingInvalid"))
        if (capturedPcm.isEmpty()) return noSpeech()
        if (capturedPcm.size % 2 != 0 ||
            capturedPcm.size > VoiceAudioFormat.MAX_CAPTURE_BYTES
        ) {
            return fail(UiMessage("voice.recordingInvalid"))
        }
        // 到达上限的硬件回调可能多出一个采样窗口，统一裁到公布的限制。
        val allowedBytes =
            minOf(
                maxAudioBytes - VoiceAudioFormat.WAV_HEADER_BYTES,
                (maxDurationSeconds * VoiceAudioFormat.BYTES_PER_SECOND).toInt(),
            ).coerceAtLeast(0) / 2 * 2
        val pcm = capturedPcm.copyOf(minOf(capturedPcm.size, allowedBytes))
        if (pcm.size < VoiceAudioFormat.MIN_PCM_BYTES) return fail(UiMessage("voice.recordingLimitTooSmall"))
        val wav = VoiceAudioFormat.wav(VoiceAudioFormat.prepareRecognitionPcm(pcm, allowedBytes))
        phaseStartedAt = nowMilliseconds
        state =
            state.copy(
                phase = "transcribing",
                recordingSeconds =
                    pcm.size / VoiceAudioFormat.BYTES_PER_SECOND.toDouble(),
            )
        return result(
            listOf(
                request("speech-transcribe", id) {
                    put("audioBase64", Base64.encode(wav))
                    recordedProvider?.let { put("providerId", it) }
                    recordedLanguage?.let { put("language", it) }
                },
            ),
            effects = listOf(VoiceHardwareEffect("releaseCapture"))
        )
    }

    fun tick(nowMilliseconds: Long): SharedVoiceInputTransition {
        if (state.phase == "recording") {
            state =
                state.copy(
                    recordingSeconds = ((nowMilliseconds - startedAt).coerceAtLeast(0) / 1000.0),
                )
            if (state.recordingSeconds >= state.recordingLimitSeconds) {
                val finished = finishHold(nowMilliseconds)
                if (finished.snapshot.phase != "stopping") return finished
                return finished.copy(
                    effects = finished.effects +
                        VoiceHardwareEffect("showToast", message = UiMessage("voice.limitReached"))
                )
            }
        }
        val timeout = if (state.phase == "transcribing") 180_000 else 30_000
        if (state.active && state.phase != "recording" &&
            nowMilliseconds - phaseStartedAt >= timeout
        ) {
            return fail(UiMessage("voice.timeout"))
        }
        return result()
    }

    fun prepare(): SharedVoiceInputTransition {
        val provider = providerId ?: return result()
        if (!state.canPrepare || state.preparing) return result()
        preparationId = id()
        state = state.copy(preparing = true, error = null)
        return result(listOf(request("speech-prepare", requireNotNull(preparationId)) { put("providerId", provider) }))
    }

    fun acceptJson(json: String): SharedVoiceInputTransition {
        val frame = runCatching { wireJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return result()
        return when (frame.voiceString("kind")) {
            "speech-catalog", "speech-state" -> acceptCatalog(frame)
            "speech-transcript" -> acceptTranscript(frame)
            "error" -> acceptError(frame.voiceString("requestId"), frame.voiceString("message")?.let { UiMessage.raw(it) })
            else -> result()
        }
    }

    private fun acceptCatalog(frame: JsonObject): SharedVoiceInputTransition {
        val requestId = frame.voiceString("requestId")
        if (requestId == null || requestId !in listOf(catalogId, followId)) return result()
        catalogId = null
        val catalog = VoiceCatalogState.parse(frame)
        providerId = catalog.providerId
        language = catalog.language
        maxAudioBytes = catalog.maxAudioBytes ?: maxAudioBytes
        maxDurationSeconds = catalog.maxDurationSeconds ?: maxDurationSeconds
        state =
            state.copy(
                ready = catalog.ready,
                canPrepare = catalog.canPrepare,
                preparing = catalog.preparing,
                preparationProgress = catalog.progress,
                recordingLimitSeconds = minOf(
                    maxDurationSeconds,
                    (maxAudioBytes - VoiceAudioFormat.WAV_HEADER_BYTES) / VoiceAudioFormat.BYTES_PER_SECOND.toDouble()
                ),
                error = catalog.error
            )
        return if (!catalog.available && state.active) {
            fail(UiMessage("voice.serviceClosed"))
        } else {
            result(
                effects =
                if (canWarmCapture()) {
                    listOf(VoiceHardwareEffect("warmCapture"))
                } else {
                    emptyList()
                }
            )
        }
    }

    private fun acceptTranscript(frame: JsonObject): SharedVoiceInputTransition {
        if (frame.voiceString("requestId") != recordingId || state.phase != "transcribing") return result()
        val text = frame.voiceString("text")?.trim().orEmpty()
        recordingId = null // 终态帧已结束远程任务，无需再向主机发送取消。
        if (text.isEmpty()) return noSpeech()
        val update = if (originalRevision == draftRevision) appendToDraft(originalDraft, text) else null
        state =
            state.copy(
                phase = "idle",
                voiceMode = false,
                level = 0f,
                waveformActive = false,
                cancelArmed = false,
                pendingTranscript = text.takeIf { update == null },
            )
        if (update != null) draftChanged(update)
        // 转写只更新草稿；焦点和键盘由用户点击原生输入框决定。
        return result(effects = listOf(VoiceHardwareEffect("stopTicker"), VoiceHardwareEffect("releaseCapture")), draftUpdate = update)
    }

    private fun acceptError(
        requestId: String?,
        message: UiMessage?,
    ): SharedVoiceInputTransition {
        if (requestId == null) return result()
        if (requestId == recordingId) {
            recordingId = null
            return fail(message ?: UiMessage("voice.recognitionFailed"))
        }
        if (requestId in listOf(catalogId, followId, preparationId)) {
            state = state.copy(error = message ?: UiMessage("voice.serviceUnavailable"), preparing = false)
            if (requestId != preparationId) {
                state = state.copy(ready = false, canPrepare = false)
                catalogId = null
                followId = null
                if (state.active) return fail(message ?: UiMessage("voice.serviceUnavailable"))
            }
            preparationId = null
        }
        return result()
    }

    private fun appendToDraft(
        current: String,
        transcript: String,
    ): String = if (current.isBlank()) transcript else "$current\n$transcript"

    fun insertPending(): SharedVoiceInputTransition {
        val text = state.pendingTranscript ?: return result()
        val update = appendToDraft(draft, text)
        draftChanged(update)
        state = state.copy(voiceMode = false, pendingTranscript = null)
        return result(effects = listOf(VoiceHardwareEffect("releaseCapture")), draftUpdate = update)
    }

    fun dismissAlert(): SharedVoiceInputTransition {
        state = state.copy(alertMessage = null, error = null)
        return result()
    }

    fun dismissFeedback(): SharedVoiceInputTransition {
        state = state.copy(error = null, pendingTranscript = null, alertMessage = null)
        return result()
    }

    fun requestFailed(
        requestId: String,
        message: UiMessage,
    ): SharedVoiceInputTransition = acceptError(requestId, message)

    fun cancel(): SharedVoiceInputTransition {
        val token = recordingId
        val requests =
            if (token != null && state.phase == "transcribing" &&
                state.connected
            ) {
                listOf(request("speech-cancel") { put("targetRequestId", token) })
            } else {
                emptyList()
            }
        recordingId = null
        stopWhenCaptureStarts = false
        captureReady = false
        state = state.copy(phase = "idle", level = 0f, waveformActive = false, cancelArmed = false)
        val effects =
            listOfNotNull(
                token?.let { VoiceHardwareEffect("stop", it, true) },
                VoiceHardwareEffect("stopTicker"),
            )
        return result(requests, effects)
    }

    fun resumeCapture(): SharedVoiceInputTransition =
        result(effects = if (canWarmCapture()) listOf(VoiceHardwareEffect("warmCapture")) else emptyList())

    fun suspendCapture(): SharedVoiceInputTransition {
        val cancelled = cancel()
        return cancelled.copy(effects = cancelled.effects + VoiceHardwareEffect("releaseCapture"))
    }

    fun leave(): SharedVoiceInputTransition {
        val cancelled = suspendCapture()
        state = state.copy(voiceMode = false)
        contextId = null
        val unsubscribe =
            if (followId != null &&
                state.connected
            ) {
                listOf(request("speech-unsubscribe"))
            } else {
                emptyList()
            }
        val requests = cancelled.requests + unsubscribe
        followId = null
        catalogId = null
        return cancelled.copy(snapshot = state, requests = requests)
    }

    private fun noSpeech(): SharedVoiceInputTransition {
        val cancelled = cancel()
        state = state.copy(error = null, alertMessage = null, pendingTranscript = null)
        return cancelled.copy(
            snapshot = state,
            effects = cancelled.effects + VoiceHardwareEffect("showToast", message = UiMessage("voice.noSpeech")),
        )
    }

    private fun fail(message: UiMessage): SharedVoiceInputTransition {
        val cancelled = cancel()
        state = state.copy(error = message, alertMessage = message)
        return cancelled.copy(snapshot = state)
    }
}
