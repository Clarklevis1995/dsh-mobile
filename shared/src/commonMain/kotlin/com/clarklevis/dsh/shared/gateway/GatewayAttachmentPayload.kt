package com.clarklevis.dsh.shared.gateway

import com.clarklevis.dsh.shared.protocol.GatewayFrame

/** 网络层与平台桥共同使用的附件大小、Base64 和字节一致性校验。 */
object GatewayAttachmentPayload {
    const val MAXIMUM_BYTES = 8 * 1_024 * 1_024

    fun validatedBytes(frame: GatewayFrame): ByteArray? {
        val attachment = frame.attachment ?: return null
        if (frame.kind != "attachment" || frame.sessionId.isNullOrBlank() || attachment.bytes !in 0..MAXIMUM_BYTES) return null
        val encoded = frame.data ?: return null
        if (encoded.length > ((attachment.bytes + 2) / 3) * 4) return null
        val bytes = decodeBase64(encoded) ?: return null
        return bytes.takeIf { it.size == attachment.bytes }
    }
}
