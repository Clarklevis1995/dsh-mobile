package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.toolImageReferences

/** 以插件保存的完整路径关联尺寸，不能用同名文件猜测图片。 */
object MarkdownImageReferences {
    fun from(records: List<SessionEvent>): Map<String, GatewayImageAttachment> = buildMap {
        fun visit(value: JsonValue?, depth: Int) {
            if (value == null || depth > 16) return
            if (value["kind"]?.stringValue == "dsh-image-gen") {
                val path = value["savedTo"]?.stringValue?.let(ConversationFileReferences::filePath)
                val image = toolImageReferences(JsonValue.ObjectValue(mapOf("meta" to value))).singleOrNull()
                if (path != null && image != null) put(path, image)
            }
            value.arrayValue.orEmpty().forEach { visit(it, depth + 1) }
            for (key in listOf("meta", "message", "content")) visit(value[key], depth + 1)
            if (value["kind"]?.stringValue == "dsh-image-gen-batch") visit(value["images"], depth + 1)
        }
        records.forEach { visit(it.event.raw, 0) }
    }
}
