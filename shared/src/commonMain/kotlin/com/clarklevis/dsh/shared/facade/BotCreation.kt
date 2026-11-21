package com.clarklevis.dsh.shared.facade

import kotlinx.serialization.Serializable

@Serializable
data class BotCreationColor(val id: String, val stops: List<String>)

@Serializable
data class BotCreationShape(val id: String, val images: Map<String, String>)

@Serializable
data class BotCreationLook(val shape: String, val color: String)

@Serializable
data class BotCreationAgent(
    val id: String,
    val title: String,
    val available: Boolean,
    val signedIn: Boolean? = null,
    val image: String? = null
)

@Serializable
data class BotAvatarPhoto(val id: String, val name: String, val image: String)

@Serializable
data class BotCreationAppearance(val shapes: List<BotCreationShape>, val colors: List<BotCreationColor>) {
    fun image(shape: String, color: String): String? = shapes.firstOrNull { it.id == shape }?.images?.get(color)

    fun randomLook(previous: BotCreationLook?): BotCreationLook? {
        val shapes = shapes.filter { it.id != previous?.shape }.ifEmpty { shapes }
        val colors = colors.filter { it.id !in setOf("black", "gray", "brown", "aurora") && it.id != previous?.color }
            .ifEmpty { colors }
        return BotCreationLook(shapes.randomOrNull()?.id ?: return null, colors.randomOrNull()?.id ?: return null)
    }
}

@Serializable
data class BotCreationState(
    val supported: Boolean = false,
    val appearance: BotCreationAppearance? = null,
    val agents: List<BotCreationAgent> = emptyList(),
    val agentRoute: String? = null,
    val agentModels: List<BotRoleOption> = emptyList(),
    val photos: List<BotAvatarPhoto> = emptyList(),
    val photoOffset: Int = 0,
    val hasMorePhotos: Boolean = false,
    val loadingAppearance: Boolean = false,
    val loadingAgents: Boolean = false,
    val loadingAgentModels: Boolean = false,
    val loadingPhotos: Boolean = false,
    val saving: Boolean = false,
    val createdId: String? = null,
    val error: String? = null,
    val appearanceFailed: Boolean = false,
    val agentsFailed: Boolean = false,
    val photosFailed: Boolean = false
)

object BotCreationRules {
    // i18n-ignore: DS Bot 协议保留的群聊称呼
    private val reservedNames = setOf("all", "everyone", "所有人", "全体", "user", "you") // i18n-ignore: DS Bot 协议保留的名称
    private val localRef = Regex("^local-[a-z0-9-]{1,96}/.+$")
    private val photo = Regex("^data:image/(png|jpeg|webp|gif);base64,[A-Za-z0-9+/]+={0,2}$")
    const val PHOTO_BYTES = 110_000
    const val PHOTO_SIDE = 512
    const val NAME_LIMIT = 40

    fun freshName(base: String, roster: List<BotChat>): String {
        val names = roster.asSequence().filterNot(BotChat::room).map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in names) return base
        var index = 2
        while ("${base.lowercase()} $index" in names) index++
        return "$base $index"
    }

    fun nameProblem(name: String, roster: List<BotChat>): String? {
        val value = name.trim()
        return when {
            value.isEmpty() || value.length > NAME_LIMIT -> "length"
            value.any { it == '@' || it == '`' || it.code < 32 || it.code == 127 } -> "characters"
            value.lowercase() in reservedNames -> "reserved"
            roster.any { !it.room && it.name.equals(value, ignoreCase = true) } -> "duplicate"
            else -> null
        }
    }

    fun isLocalModel(ref: String): Boolean = localRef.matches(ref)

    fun isPhoto(value: String): Boolean = value.length <= 160_000 && photo.matches(value)
}
