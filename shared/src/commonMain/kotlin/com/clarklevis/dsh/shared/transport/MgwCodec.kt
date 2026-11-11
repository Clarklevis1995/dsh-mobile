// 协议版本、字节偏移和资源预算与网关契约逐项对应，保留原值便于审计。
@file:Suppress("MagicNumber")

package com.clarklevis.dsh.shared.transport

/** MGW1 编解码器：每通道单独实例，序号始终为 Long，禁止通过浮点转换。 */
class MgwCodec(
    private val channel: Int,
) {
    private var sendSequence = 1L
    private var receiveSequence = 1L
    private var expectedChunk = 0
    private var chunks = 0
    private var buffer: ByteArray? = null
    private var offset = 0
    private var lastChunkAt = 0L
    val hasPartialMessage: Boolean get() = buffer != null

    init {
        require(channel == 1 || channel == 2)
    }

    fun encode(
        text: String,
        maxFrameBytes: Int = 16384,
        maxLogicalBytes: Int = 16777216,
    ): List<ByteArray> {
        val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
        require(bytes.isNotEmpty() && bytes.size <= maxLogicalBytes) { "transport/ws-required" }
        require(maxFrameBytes in 33..16384 && sendSequence > 0)
        val size = maxFrameBytes - 32
        val count = (bytes.size + size - 1) / size
        val sequence = sendSequence
        val result =
            (0 until count).map { index ->
                val start = index * size
                val length = minOf(size, bytes.size - start)
                ByteArray(32 + length).also { frame ->
                    frame[0] = 77
                    frame[1] = 71
                    frame[2] = 87
                    frame[3] = 49
                    frame[4] = 1
                    frame[6] = channel.toByte()
                    put(frame, 8, sequence, 8)
                    put(frame, 16, index.toLong(), 4)
                    put(frame, 20, count.toLong(), 4)
                    put(frame, 24, bytes.size.toLong(), 4)
                    put(frame, 28, length.toLong(), 4)
                    bytes.copyInto(frame, 32, start, start + length)
                }
            }
        sendSequence++
        return result
    }

    // 所有头字段和连续性校验必须在复制 payload 之前完成。
    @Suppress("CyclomaticComplexMethod")
    fun receive(
        frame: ByteArray,
        nowMs: Long,
    ): String? {
        checkIdle(nowMs)
        require(frame.size in 33..16384)
        require(frame[0] == 77.toByte() && frame[1] == 71.toByte() && frame[2] == 87.toByte() && frame[3] == 49.toByte())
        require(frame[4] == 1.toByte() && frame[5] == 0.toByte() && frame[6] == channel.toByte() && frame[7] == 0.toByte())
        val sequence = read(frame, 8, 8)
        val index = read(frame, 16, 4)
        val count = read(frame, 20, 4)
        val total = read(frame, 24, 4)
        val length = read(frame, 28, 4)
        require(sequence == receiveSequence && sequence > 0 && index == expectedChunk.toLong())
        require(total in 1..16777216 && count in 1..total && length == (frame.size - 32).toLong())
        if (buffer == null) {
            require(index == 0L)
            chunks = count.toInt()
            buffer = ByteArray(total.toInt())
        }
        val bytes = requireNotNull(buffer)
        require(count == chunks.toLong() && total == bytes.size.toLong() && index < count && length <= bytes.size - offset)
        frame.copyInto(bytes, offset, 32)
        offset += length.toInt()
        expectedChunk++
        lastChunkAt = nowMs
        if (expectedChunk != chunks) {
            require(offset < bytes.size)
            return null
        }
        require(offset == bytes.size)
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        buffer = null
        offset = 0
        expectedChunk = 0
        receiveSequence++
        return text
    }

    fun checkIdle(nowMs: Long) {
        require(buffer == null || nowMs - lastChunkAt < 30000) { "transport/chunk-timeout" }
    }

    fun close() {
        buffer = null
        offset = 0
        expectedChunk = 0
    }

    private fun read(
        bytes: ByteArray,
        start: Int,
        size: Int,
    ): Long {
        var value = 0L
        repeat(size) { value = (value shl 8) or (bytes[start + it].toLong() and 255) }
        return value
    }

    private fun put(
        bytes: ByteArray,
        start: Int,
        value: Long,
        size: Int,
    ) {
        repeat(size) { bytes[start + it] = (value ushr ((size - it - 1) * 8)).toByte() }
    }
}
