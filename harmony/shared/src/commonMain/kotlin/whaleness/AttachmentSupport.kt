package whaleness

import com.clarklevis.dsh.shared.gateway.GatewayAttachmentPayload
import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import kotlinx.serialization.Serializable

@Serializable
data class HarmonyAttachment(
    val key: String,
    val sessionId: String,
    val image: GatewayImageAttachment,
    val status: String,
    val error: String? = null,
)

internal data class AttachmentChange(
    val view: HarmonyAttachment? = null,
    val request: GatewayRequest? = null,
)

internal class AttachmentSupport {
    private var serial = 0L
    private var pending: HarmonyAttachment? = null

    fun reset() {
        pending = null
    }

    fun pendingKeys(): List<String> = listOfNotNull(pending?.key)

    fun open(
        session: String,
        id: String,
        items: List<ConversationItem>,
    ): AttachmentChange {
        check(pending == null) { "请等待当前图片读取完成" }
        val image = items.asSequence().flatMap { it.images.asSequence() }.firstOrNull { it.attachmentId == id } ?: error("图片引用已失效，请刷新会话")
        require(
            image.bytes in 1..GatewayAttachmentPayload.MAXIMUM_BYTES &&
                image.width > 0 &&
                image.height > 0 &&
                image.width.toLong() * image.height <= 100_000_000L,
        ) { "图片大小或尺寸超出预览范围" }
        require(image.mediaType in setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif", "image/bmp")) {
            "此图片格式暂不支持预览"
        }
        val value = HarmonyAttachment("attachment:${++serial}", session, image, "loading")
        pending = value
        return AttachmentChange(value, GatewayRequests.attachment(session, id))
    }

    fun accept(frame: GatewayFrame): AttachmentChange? {
        val target = pending ?: return null
        if (frame.kind == "attachment" &&
            frame.sessionId == target.sessionId &&
            frame.attachment?.attachmentId == target.image.attachmentId
        ) {
            pending = null
            val valid = frame.attachment == target.image && GatewayAttachmentPayload.validatedBytes(frame) != null
            return AttachmentChange(target.copy(status = if (valid) "ready" else "error", error = if (valid) null else "图片校验失败，请重试"))
        }
        if (frame.kind == "error" &&
            frame.requestType == "attachment" &&
            (frame.sessionId == null || frame.sessionId == target.sessionId)
        ) {
            pending = null
            return AttachmentChange(target.copy(status = "error", error = "图片读取失败，请重试"))
        }
        return null
    }

    fun timedOut(key: String): AttachmentChange {
        val target = pending?.takeIf { it.key == key } ?: return AttachmentChange()
        pending = null
        return AttachmentChange(target.copy(status = "error", error = "图片读取超时，请重试"))
    }
}
