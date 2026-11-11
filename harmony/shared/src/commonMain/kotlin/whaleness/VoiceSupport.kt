package whaleness

import com.clarklevis.dsh.shared.facade.SharedVoiceInputStore
import com.clarklevis.dsh.shared.facade.SharedVoiceInputTransition
import com.clarklevis.dsh.shared.facade.VoiceHardwareEffect
import com.clarklevis.dsh.shared.gateway.GatewayRequest
import kotlinx.serialization.Serializable

@Serializable
data class HarmonyVoice(
    val sessionId: String = "",
    val phase: String = "idle",
    val voiceMode: Boolean = false,
    val connected: Boolean = false,
    val supported: Boolean = false,
    val ready: Boolean = false,
    val canPrepare: Boolean = false,
    val preparing: Boolean = false,
    val progress: Double = 0.0,
    val level: Float = 0f,
    val waveform: List<Float> = emptyList(),
    val alert: String? = null,
    val error: String? = null,
    val pendingTranscript: String? = null,
    val seconds: Double = 0.0,
    val timeLabel: String = "",
    val status: String = "",
    val blocked: Boolean = false,
    val overlay: Boolean = false,
    val cancelArmed: Boolean = false,
    val draft: String? = null,
)

@Serializable
data class HarmonyVoiceEffect(
    val action: String,
    val recordingId: String,
    val discard: Boolean,
    val message: String,
    val delayMilliseconds: Long,
)

internal data class VoiceChange(
    val view: HarmonyVoice,
    val requests: List<GatewayRequest>,
    val effects: List<HarmonyVoiceEffect>,
)

/** 只映射 Intent 和硬件 effect；录音、转写水位、取消和草稿冲突沿用共享状态机。 */
internal class VoiceSupport {
    private val store = SharedVoiceInputStore()
    private var session = ""
    private var draft = ""

    fun command(
        command: HarmonyCommand,
        selected: String?,
        online: Boolean,
        capabilities: List<String>,
        child: Boolean,
    ): VoiceChange {
        val effects = mutableListOf<VoiceHardwareEffect>()
        val requests = mutableListOf<GatewayRequest>()

        fun collect(transition: SharedVoiceInputTransition): SharedVoiceInputTransition {
            effects += transition.effects
            requests += transition.requests
            return transition
        }
        var change: SharedVoiceInputTransition
        if (command.op == "voice-sync") {
            if (session != selected.orEmpty()) {
                collect(store.leave())
                session = selected.orEmpty()
            }
            draft = command.text
            change = collect(store.open(session, draft))
            change = collect(store.connectionChanged(online && !child && session.isNotEmpty(), "speech-input-v1" in capabilities))
        } else if (command.op == "voice-leave") {
            change = collect(store.leave())
            session = ""
        } else if (command.sessionId != session || selected != session) {
            change = SharedVoiceInputTransition(store.snapshot())
        } else {
            if (!online || child || "speech-input-v1" !in capabilities) {
                collect(store.connectionChanged(online && !child, "speech-input-v1" in capabilities))
            }
            if (draft != command.text) {
                draft = command.text
                collect(store.draftChanged(draft))
            }
            change =
                collect(
                    when (command.op) {
                        "voice-draft" -> SharedVoiceInputTransition(store.snapshot())
                        "voice-mode" -> store.setVoiceMode(command.mode == "on")
                        "voice-press" -> store.press(command.nowMillis)
                        "voice-move" -> store.pointerMoved(command.byteSize.toFloat())
                        "voice-release" -> store.release(command.nowMillis)
                        "voice-cancel" -> store.cancel()
                        "voice-suspend" -> store.suspendCapture()
                        "voice-permission" -> store.permissionResult(command.value, command.mode == "granted", command.nowMillis)
                        "voice-started" -> store.captureStarted(command.value, command.nowMillis)
                        "voice-failed" -> store.captureFailed(command.value, "录音暂不可用，请检查麦克风权限或占用情况")
                        "voice-level" -> store.level(command.value, command.byteCount / 10000f, command.nowMillis)
                        "voice-finished" -> store.captureFinished(command.value, command.input, command.nowMillis)
                        "voice-tick" -> store.tick(command.nowMillis)
                        "voice-prepare" -> store.prepare()
                        "voice-insert" -> store.insertPending()
                        "voice-dismiss" -> store.dismissFeedback()
                        "voice-frame" -> store.acceptJson(command.frame)
                        else -> error("未知语音操作")
                    },
                )
        }
        change.draftUpdate?.let { draft = it }
        val value = change.snapshot
        return VoiceChange(
            HarmonyVoice(
                session,
                value.phase,
                value.voiceMode,
                value.connected,
                value.supported,
                value.ready,
                value.canPrepare,
                value.preparing,
                value.preparationProgress,
                value.level,
                value.waveformLevels,
                value.alertMessage,
                value.error,
                value.pendingTranscript,
                value.recordingSeconds,
                value.recordingTimeLabel,
                value.status,
                value.inputBlocked,
                value.overlayVisible,
                value.cancelArmed,
                change.draftUpdate,
            ),
            requests,
            effects.map { HarmonyVoiceEffect(it.action, it.recordingId, it.discard, it.message, it.delayMilliseconds) },
        )
    }
}
