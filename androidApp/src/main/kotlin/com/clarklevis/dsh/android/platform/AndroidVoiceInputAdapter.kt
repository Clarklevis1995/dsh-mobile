package com.clarklevis.dsh.android.platform

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.facade.SharedVoiceInputStore
import com.clarklevis.dsh.shared.facade.SharedVoiceInputTransition
import com.clarklevis.dsh.shared.facade.VoiceAudioFormat
import com.clarklevis.dsh.shared.facade.VoiceInputInteraction
import com.clarklevis.dsh.shared.gateway.GatewayRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/** 平台执行权限、录音和网络 effect；Intent 方法转发到公共 Store，不保存并行业务状态。 */
@Suppress("TooManyFunctions")
class AndroidVoiceInputAdapter(
    private val context: Context?,
    private val send: (GatewayRequest) -> Unit,
    private val shared: SharedVoiceInputStore = SharedVoiceInputStore(),
    private val recorder: AndroidVoiceAudioCapturing = AndroidVoiceRecorder(),
) {
    private companion object {
        const val LEVEL_TICK_MILLISECONDS = 100L
    }

    var state by mutableStateOf(shared.snapshot())
        private set
    var onDraftUpdate: ((String) -> Unit)? = null
    var onPermissionNeeded: (() -> Unit)? = null
    var onHapticFeedback: ((String) -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var toast: Toast? = null
    private var permissionId: String? = null
    private val latency = VoiceInputLatency(context)

    fun open(
        context: String,
        draft: String,
    ) {
        apply(shared.open(context, draft))
        apply(shared.connectionChanged(state.connected, state.supported))
    }

    fun connectionChanged(
        connected: Boolean,
        supported: Boolean,
    ) = apply(shared.connectionChanged(connected, supported))

    fun accept(json: String) = apply(shared.acceptJson(json))

    fun draftChanged(text: String) = apply(shared.draftChanged(text))

    fun keyboardChanged(visible: Boolean) = apply(shared.keyboardChanged(visible))

    fun setVoiceMode(enabled: Boolean) = apply(shared.setVoiceMode(enabled))

    fun touchDown(uptimeMilliseconds: Long) {
        latency.touchDown(uptimeMilliseconds)
        apply(shared.touchDown(SystemClock.elapsedRealtime(), microphoneGranted()))
    }

    fun touchFinished() = apply(shared.touchFinished())

    fun suspendCapture() = apply(shared.suspendCapture())

    fun resumeCapture() = apply(shared.resumeCapture())

    private fun microphoneGranted(): Boolean =
        context?.let {
            ContextCompat.checkSelfPermission(it, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        } == true

    fun overlayDrawn() = latency.overlayDrawn()

    fun press() {
        latency.mark("gesture-recognized")
        apply(shared.press(SystemClock.elapsedRealtime()))
    }

    fun move(distanceDp: Float) = apply(shared.pointerMoved(distanceDp))

    fun release() = apply(shared.release(SystemClock.elapsedRealtime()))

    fun cancel() = apply(shared.cancel())

    fun leave() {
        apply(shared.leave())
        toast?.cancel()
        toast = null
    }

    fun prepare() = apply(shared.prepare())

    fun insertPending() = apply(shared.insertPending())

    fun dismissAlert() = apply(shared.dismissAlert())

    fun dismissFeedback() = apply(shared.dismissFeedback())

    fun permissionResult(granted: Boolean) {
        val id = permissionId ?: return
        permissionId = null
        apply(shared.permissionResult(id, granted, SystemClock.elapsedRealtime()))
    }

    fun requestFailed(
        requestId: String?,
        message: String,
    ) {
        if (requestId == null) return
        apply(shared.requestFailed(requestId, message))
    }

    private fun executePermission(token: String) {
        latency.mark("permission-check")
        val ctx = context
        when {
            ctx == null -> {
                apply(shared.permissionResult(token, false, SystemClock.elapsedRealtime()))
            }

            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> {
                apply(shared.permissionResult(token, true, SystemClock.elapsedRealtime()))
            }

            permissionId != null -> {
                apply(shared.captureFailed(token, appText("请先完成麦克风授权")))
            }

            else -> {
                permissionId = token
                if (onPermissionNeeded == null) permissionResult(false) else onPermissionNeeded?.invoke()
            }
        }
    }

    private fun apply(transition: SharedVoiceInputTransition) {
        val previousPhase = state.phase
        state = transition.snapshot
        if (previousPhase != state.phase) latency.mark("state-${state.phase}")
        transition.draftUpdate?.let { onDraftUpdate?.invoke(it) }
        transition.requests.forEach(send)
        transition.effects.forEach { effect ->
            when (effect.action) {
                "hapticStart", "hapticCancel", "hapticRelease" -> {
                    onHapticFeedback?.invoke(effect.action)
                }

                "showToast" -> {
                    toast?.cancel()
                    context?.applicationContext?.let { ctx ->
                        toast = Toast.makeText(ctx, appText(effect.message), Toast.LENGTH_SHORT).also { it.show() }
                    }
                }

                "warmCapture" -> {
                    if (microphoneGranted()) recorder.prepare()
                }

                "releaseCapture" -> {
                    recorder.release()
                }

                "permission" -> {
                    executePermission(effect.recordingId)
                }

                "start" -> {
                    val token = effect.recordingId
                    latency.mark("capture-start-dispatched")
                    recorder.start(
                        token,
                        started = {
                            latency.mark("hardware-recording")
                            apply(shared.captureStarted(token, SystemClock.elapsedRealtime()))
                        },
                        level = { apply(shared.level(id = token, rms = it, nowMilliseconds = SystemClock.elapsedRealtime())) },
                        finished = { apply(shared.captureFinished(token, it, SystemClock.elapsedRealtime())) },
                        failure = { apply(shared.captureFailed(token, appText("无法录音，请检查麦克风权限或通话状态"))) },
                    )
                }

                "stop" -> {
                    if (effect.discard || effect.delayMilliseconds == 0L) {
                        recorder.stop(effect.recordingId, effect.discard)
                    } else {
                        scope.launch {
                            delay(effect.delayMilliseconds)
                            recorder.stop(effect.recordingId, false)
                        }
                    }
                }

                "startTicker" -> {
                    ticker?.cancel()
                    ticker =
                        scope.launch {
                            while (true) {
                                delay(LEVEL_TICK_MILLISECONDS)
                                apply(shared.tick(SystemClock.elapsedRealtime()))
                            }
                        }
                }

                "stopTicker" -> {
                    ticker?.cancel()
                    ticker = null
                }
            }
        }
    }
}

/** 采集接口只执行硬件命令；started 表示设备已进入 RECORDSTATE_RECORDING，不等待首批 PCM 交付。 */
interface AndroidVoiceAudioCapturing {
    fun prepare()

    fun release()

    fun start(
        id: String,
        started: () -> Unit,
        level: (Float) -> Unit,
        finished: (String) -> Unit,
        failure: () -> Unit,
    )

    fun stop(
        id: String,
        discard: Boolean,
    )
}

/** 硬件读取不等待 UI；空闲预建 AudioRecord，但不调用 startRecording 或保存音频。 */
private class AndroidVoiceRecorder : AndroidVoiceAudioCapturing {
    private companion object {
        const val PCM_SCALE = 32768.0
        const val BYTE_MASK = 255
        const val BYTE_BITS = 8
        const val MILLISECONDS_PER_SECOND = 1000
        const val LEVEL_INTERVAL_MILLISECONDS = 50L
        const val BUFFER_MILLISECONDS = 200
    }

    private class Capture(
        val id: String,
        val generation: Long,
    ) {
        @Volatile var stopped = false

        @Volatile var discard = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val hardwareLock = Any()
    private var prepared: AudioRecord? = null
    private var generation = 0L

    @Volatile private var capture: Capture? = null

    @Suppress("MissingPermission")
    private fun createAudio(): AudioRecord {
        val minimum =
            AudioRecord.getMinBufferSize(
                VoiceAudioFormat.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        require(minimum > 0)
        val audio =
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                VoiceAudioFormat.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, VoiceAudioFormat.BYTES_PER_SECOND * BUFFER_MILLISECONDS / MILLISECONDS_PER_SECOND),
            )
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            error("AudioRecord initialization failed")
        }
        return audio
    }

    override fun prepare() {
        val requestedGeneration = synchronized(hardwareLock) { generation }
        scope.launch {
            if (capture != null || synchronized(hardwareLock) { prepared != null || generation != requestedGeneration }) return@launch
            val audio = runCatching { createAudio() }.getOrNull() ?: return@launch
            val retained =
                synchronized(hardwareLock) {
                    if (capture == null && generation == requestedGeneration && prepared == null) {
                        prepared = audio
                        true
                    } else {
                        false
                    }
                }
            if (!retained) audio.release()
        }
    }

    override fun release() {
        capture?.let { stop(it.id, true) }
        val old =
            synchronized(hardwareLock) {
                generation++
                prepared.also { prepared = null }
            }
        scope.launch { old?.release() }
    }

    @Suppress("MissingPermission")
    override fun start(
        id: String,
        started: () -> Unit,
        level: (Float) -> Unit,
        finished: (String) -> Unit,
        failure: () -> Unit,
    ) {
        capture?.let { stop(it.id, true) }
        val current = Capture(id, synchronized(hardwareLock) { generation })
        capture = current
        scope.launch {
            var audio: AudioRecord? = null
            var encoded: String? = null
            var failed = false
            try {
                if (current.stopped) return@launch
                audio = synchronized(hardwareLock) { prepared.also { prepared = null } } ?: createAudio()
                if (current.stopped) return@launch
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                scope.launch(Dispatchers.Main.immediate) { started() }
                val bytes = ByteArrayOutputStream()
                val samples =
                    ShortArray(
                        (
                            VoiceAudioFormat.SAMPLE_RATE * VoiceInputInteraction.CAPTURE_WINDOW_MILLISECONDS /
                                MILLISECONDS_PER_SECOND
                        ).toInt(),
                    )
                var first = true
                var lastLevelAt = 0L
                while (!current.stopped) {
                    val count = audio.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) {
                        check(current.stopped || count == 0) { "AudioRecord read failed" }
                    } else {
                        val retainedSamples = minOf(count, (VoiceAudioFormat.MAX_CAPTURE_BYTES - bytes.size()) / 2)
                        var energy = 0.0
                        repeat(count) { index ->
                            val sample = samples[index].toInt()
                            if (index < retainedSamples) {
                                bytes.write(sample and BYTE_MASK)
                                bytes.write((sample ushr BYTE_BITS) and BYTE_MASK)
                            }
                            val normalized = sample / PCM_SCALE
                            energy += normalized * normalized
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (first || now - lastLevelAt >= LEVEL_INTERVAL_MILLISECONDS) {
                            if (first) com.clarklevis.dsh.android.platform.LocalAppLog.d("VoiceInputLatency", "stage=first-pcm uptimeMs=$now")
                            first = false
                            lastLevelAt = now
                            val rms = sqrt(energy / count).toFloat()
                            // 只排队通知 UI，采集线程立即继续读取，避免蒙层首绘阻塞音频输入。
                            scope.launch(Dispatchers.Main.immediate) {
                                level(rms)
                            }
                        }
                    }
                }
                if (!current.discard) encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
            } catch (_: Exception) {
                failed = !current.discard
            } finally {
                runCatching { audio?.stop() }
                val retained =
                    synchronized(hardwareLock) {
                        if (audio != null && generation == current.generation && prepared == null && !failed) {
                            prepared = audio
                            true
                        } else {
                            false
                        }
                    }
                if (!retained) audio?.release()
                if (capture === current) capture = null
            }
            if (!current.discard) {
                scope.launch(Dispatchers.Main.immediate) {
                    if (failed) failure() else encoded?.let(finished)
                }
            }
        }
    }

    override fun stop(
        id: String,
        discard: Boolean,
    ) {
        val current = capture?.takeIf { it.id == id } ?: return
        current.discard = discard
        current.stopped = true
        // 20ms 读取窗口自行收尾，不在 UI 线程同步等待 AudioRecord.stop。
    }
}

/** 仅 Debug 输出阶段耗时，不保存音频、转写文本或另一套语音业务状态。 */
private class VoiceInputLatency(
    context: Context?,
) {
    private val enabled = (context?.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var touchedAt = 0L
    private var overlayLogged = false

    fun touchDown(uptimeMilliseconds: Long) {
        if (!enabled) return
        touchedAt = uptimeMilliseconds
        overlayLogged = false
        mark("touch-down")
    }

    fun overlayDrawn() {
        if (!enabled || overlayLogged) return
        overlayLogged = true
        mark("overlay-first-draw")
    }

    fun mark(stage: String) {
        if (!enabled || touchedAt == 0L) return
        com.clarklevis.dsh.android.platform.LocalAppLog.d("VoiceInputLatency", "$stage elapsed=${SystemClock.uptimeMillis() - touchedAt}ms")
    }
}
