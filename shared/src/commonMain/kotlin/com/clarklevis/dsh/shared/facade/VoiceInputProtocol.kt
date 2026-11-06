package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.i18n.UiMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 硬件采样格式与边界；客户端不打包 ASR 模型，识别由官方主机插件处理。 */
object VoiceAudioFormat {
    const val SAMPLE_RATE = 16000
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2
    const val MAX_CAPTURE_BYTES = 4 * 1024 * 1024

    // 非空 PCM 交给官方识别服务，不按 200ms 提前判定无人声。
    const val MIN_PCM_BYTES = 2
    const val WAV_HEADER_BYTES = 44
    const val MAX_DURATION_SECONDS = 120.0

    private const val LEADING_CONTEXT_BYTES = BYTES_PER_SECOND / 5
    private const val TRAILING_CONTEXT_BYTES = BYTES_PER_SECOND / 2
    private const val PCM_SAMPLE_BYTES = 2
    private const val GAIN_MINIMUM_PEAK = 128
    private const val GAIN_TRIGGER_PEAK = 8192
    private const val GAIN_TARGET_PEAK = 16384.0
    private const val MAXIMUM_GAIN = 8.0
    private const val MINIMUM_GAIN_CREST_FACTOR = 3.0

    /** 给官方 VAD 留出首尾上下文；只补静音，不截掉已经采集的发音。 */
    internal fun prepareRecognitionPcm(
        pcm: ByteArray,
        maximumPcmBytes: Int,
    ): ByteArray {
        require(pcm.size % PCM_SAMPLE_BYTES == 0 && pcm.size <= maximumPcmBytes)
        val availableBytes = maximumPcmBytes - pcm.size
        val leadingBytes = minOf(LEADING_CONTEXT_BYTES, availableBytes) / PCM_SAMPLE_BYTES * PCM_SAMPLE_BYTES
        val trailingBytes =
            minOf(TRAILING_CONTEXT_BYTES, availableBytes - leadingBytes) / PCM_SAMPLE_BYTES * PCM_SAMPLE_BYTES
        val prepared = ByteArray(leadingBytes + pcm.size + trailingBytes)
        var peak = 0
        var energy = 0.0
        for (offset in pcm.indices step PCM_SAMPLE_BYTES) {
            val sample = pcmSample(pcm, offset)
            peak = maxOf(peak, abs(sample))
            energy += sample.toDouble() * sample
        }
        val rms = if (pcm.isEmpty()) 0.0 else sqrt(energy / (pcm.size / PCM_SAMPLE_BYTES))
        // 单次录音使用固定且有上限的增益，避免弱声被 VAD 漏掉；正常音量、近零输入和低峰均比的持续噪声保持原值。
        val gain =
            if (peak in GAIN_MINIMUM_PEAK until GAIN_TRIGGER_PEAK &&
                peak >= rms * MINIMUM_GAIN_CREST_FACTOR
            ) {
                minOf(MAXIMUM_GAIN, GAIN_TARGET_PEAK / peak)
            } else {
                1.0
            }
        if (gain == 1.0) {
            pcm.copyInto(prepared, leadingBytes)
        } else {
            for (offset in pcm.indices step PCM_SAMPLE_BYTES) {
                writePcmSample(prepared, leadingBytes + offset, (pcmSample(pcm, offset) * gain).roundToInt())
            }
        }
        return prepared
    }

    // PCM16 little-endian 的位宽、符号范围和字节掩码固定。
    @Suppress("MagicNumber")
    private fun pcmSample(
        bytes: ByteArray,
        offset: Int,
    ): Int = ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()

    @Suppress("MagicNumber")
    private fun writePcmSample(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        val sample = value.coerceIn(-32768, 32767)
        bytes[offset] = sample.toByte()
        bytes[offset + 1] = (sample shr 8).toByte()
    }

    // 数字是 RIFF/WAVE 的固定字节偏移和 PCM 格式字段，不能随配置变化。
    @Suppress("MagicNumber")
    internal fun wav(pcm: ByteArray): ByteArray {
        val wav = ByteArray(pcm.size + WAV_HEADER_BYTES)

        fun text(
            at: Int,
            value: String,
        ) {
            value.encodeToByteArray().copyInto(wav, at)
        }

        fun number(
            at: Int,
            value: Int,
            bytes: Int = 4,
        ) {
            repeat(bytes) { wav[at + it] = (value ushr (it * 8)).toByte() }
        }
        text(0, "RIFF")
        number(4, wav.size - 8)
        text(8, "WAVEfmt ")
        number(16, 16)
        number(20, 1, 2)
        number(22, 1, 2)
        number(24, SAMPLE_RATE)
        number(28, BYTES_PER_SECOND)
        number(32, 2, 2)
        number(34, 16, 2)
        text(36, "data")
        number(40, pcm.size)
        pcm.copyInto(wav, WAV_HEADER_BYTES)
        return wav
    }
}

internal fun JsonObject.voiceString(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.number(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull

internal data class VoiceCatalogState(
    val available: Boolean,
    val providerId: String?,
    val language: String?,
    val ready: Boolean,
    val canPrepare: Boolean,
    val preparing: Boolean,
    val progress: Double,
    val error: UiMessage?,
    val maxAudioBytes: Int?,
    val maxDurationSeconds: Double?,
) {
    companion object {
        fun parse(frame: JsonObject): VoiceCatalogState {
            val available = (frame["available"] as? JsonPrimitive)?.booleanOrNull == true
            val selection = frame["selection"] as? JsonObject
            val providerId = selection?.voiceString("providerId")
            val providers = (frame["providers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val provider = providers.firstOrNull { it.voiceString("id") == providerId }
            val languages =
                (provider?.get("languages") as? JsonArray)
                    .orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val recognitionLanguage = if ("auto" in languages) "auto" else selection?.voiceString("language")
            val preparation = provider?.get("preparation") as? JsonObject
            val phase = preparation?.voiceString("phase")
            val total = preparation?.number("totalBytes") ?: 0.0
            val completed = preparation?.number("completedBytes") ?: 0.0
            return VoiceCatalogState(
                available = available,
                providerId = providerId,
                language = recognitionLanguage,
                ready = available && phase in setOf("ready", "standby"),
                canPrepare = available && provider != null,
                preparing = phase in setOf("checking", "downloading", "loading", "waking", "cancelling"),
                progress = if (total > 0) (completed / total).coerceIn(0.0, 1.0) else 0.0,
                error = if (available) preparation?.voiceString("message")?.let { UiMessage.raw(it) } else UiMessage("voice.pluginDisabled"),
                maxAudioBytes =
                    frame
                        .number("maxAudioBytes")
                        ?.takeIf { it >= VoiceAudioFormat.WAV_HEADER_BYTES && it <= VoiceAudioFormat.MAX_CAPTURE_BYTES }
                        ?.toInt(),
                maxDurationSeconds = frame.number("maxDurationSeconds")?.takeIf { it > 0 && it.isFinite() },
            )
        }
    }
}
