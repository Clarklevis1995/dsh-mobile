package com.clarklevis.dsh.shared.protocol

/** 只接受持久附件引用；插件关闭、旧网关和损坏的可选图片都不影响正文。 */
fun GatewayImageAttachment.isValidToolImage(): Boolean =
    attachmentId.isNotBlank() && mediaType in setOf("image/png", "image/jpeg", "image/webp", "image/gif") &&
        bytes > 0 && width > 0 && height > 0

fun toolImageReferences(data: JsonValue): List<GatewayImageAttachment> {
    if (data["isError"]?.booleanValue == true || data["message"]?.get("isError")?.booleanValue == true ||
        data["error"]?.let { it != JsonValue.NullValue && it != JsonValue.BooleanValue(false) } == true
    ) return emptyList()
    val images = linkedMapOf<String, GatewayImageAttachment>()
    fun add(value: JsonValue?) {
        fun integer(key: String): Int? = value?.get(key)?.doubleValue?.takeIf {
            it.isFinite() && it > 0 && it <= Int.MAX_VALUE && it == it.toInt().toDouble()
        }?.toInt()
        val image = GatewayImageAttachment(
            value?.get("attachmentId")?.stringValue ?: return,
            value["mediaType"]?.stringValue ?: return,
            integer("bytes") ?: return, integer("width") ?: return, integer("height") ?: return,
            value["name"]?.stringValue
        )
        if (image.isValidToolImage() && image.attachmentId !in images) images[image.attachmentId] = image
    }
    fun visit(value: JsonValue?, depth: Int = 0) {
        if (depth > 16) return
        value?.arrayValue.orEmpty().forEach { block ->
            when (block["type"]?.stringValue) {
                "image" -> add(block["attachment"])
                "tool-result" -> if (block["isError"]?.booleanValue != true) visit(block["content"], depth + 1)
            }
        }
    }
    data["images"]?.arrayValue.orEmpty().forEach(::add)
    visit(data["message"]?.get("content") ?: data["content"])
    val meta = data["meta"]
    val candidates = if (meta?.get("kind")?.stringValue == "dsh-image-gen-batch") meta["images"]?.arrayValue.orEmpty() else listOfNotNull(meta)
    candidates.filter { it["kind"]?.stringValue == "dsh-image-gen" }.forEach { add(it["attachment"]) }
    return images.values.toList()
}

fun GatewayEvent.toolImages(): List<GatewayImageAttachment> {
    if (isError == true || !error.isNullOrBlank()) return emptyList()
    return (images.orEmpty().filter { it.isValidToolImage() } + raw?.let(::toolImageReferences).orEmpty())
        .distinctBy { it.attachmentId }
}
