package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.gateway.GatewayRequest
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.gateway.GatewayRequestLanePolicy
import com.clarklevis.dsh.shared.protocol.GatewayQuoteTextParts
import com.clarklevis.dsh.shared.protocol.GatewayTextPart
import com.clarklevis.dsh.shared.protocol.PresentedFile
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Serializable
data class BotChat(
    val id: String,
    val name: String,
    val room: Boolean = false,
    val role: String = "",
    val preview: String = "",
    val image: String? = null,
    val lastAt: Double = 0.0,
    val running: Boolean = false,
    val part: Int = 0,
    val imageRef: String? = null,
    val memberIds: List<String> = emptyList(),
    val sessionId: String? = null,
    val profile: BotRoleProfile? = null,
    val hidden: Boolean = false
)

@Serializable
data class BotRoleProfile(
    val instructions: String = "",
    val model: String? = null,
    val effort: String? = null,
    val workDir: String? = null,
    val permission: String? = null,
    val privateRole: Boolean = false,
    val appliedModel: String? = null,
    val appliedEffort: String? = null
)

@Serializable
data class BotRoleOption(val id: String, val title: String)

@Serializable
data class BotMemory(val id: String, val scope: String, val topic: String? = null, val text: String)

@Serializable
data class BotRoleDetails(
    val botId: String,
    val memories: List<BotMemory> = emptyList(),
    val inputTokens: Long? = null,
    val outputTokens: Long? = null
)

@Serializable
data class BotChatMessage(
    val id: String,
    val kind: String,
    val text: String,
    val name: String = "",
    val botId: String? = null,
    val streaming: Boolean = false,
    val delivery: String? = null,
    val sessionId: String = "",
    val images: List<BotChatImage> = emptyList(),
    val timeMs: Long? = null,
    val activity: String? = null,
    val questionAnswers: List<BotQuestionAnswer> = emptyList(),
    val files: List<PresentedFile> = emptyList(),
    val turn: Long? = null
) {
    val quoteParts: List<GatewayTextPart> by lazy {
        if (kind == "user" && text.contains("> ")) GatewayQuoteTextParts.parse(text).orEmpty() else emptyList()
    }
}

@Serializable
data class BotQuestionAnswer(val question: String, val choices: List<BotAnswerChoice>)

@Serializable
data class BotAnswerChoice(val label: String, val letter: String? = null)

@Serializable
data class BotChatImage(
    val attachmentId: String,
    val sessionId: String,
    val mediaType: String = "image/png",
    val name: String = "",
    val width: Int = 0,
    val height: Int = 0
)

@Serializable
data class BotSchedule(val id: String, val title: String, val status: String, val next: String, val botId: String? = null, val sessionId: String? = null)

@Serializable
data class BotLiveWork(val kind: String, val botId: String? = null, val startedAtMs: Long? = null)

data class BotChatIndicator(val running: Boolean, val unreadCount: Int) {
    val unreadText: String get() = if (unreadCount > 99) "99+" else unreadCount.toString()
}

@Serializable
data class BotActivity(val id: String, val text: String, val chatId: String? = null, val kind: String = "", val name: String = "")

@Serializable
data class BotInteraction(
    val id: String,
    val kind: String,
    val chatId: String,
    val sessionId: String,
    val title: String,
    val detail: String = "",
    val options: List<String> = emptyList(),
    val multiSelect: Boolean = false
)

@Serializable
data class BotReferenceSearch(
    val chatId: String,
    val query: String,
    val quoted: Boolean,
    val loading: Boolean = true,
    val items: List<BotMentionCandidate> = emptyList(),
    val unavailable: Boolean = false
)

/** 新会话与群聊共用的有序草稿；未知提交结果不自动重试。 */
@Serializable
data class BotConversationState(
    val supported: Boolean = false,
    val memberIds: List<String> = emptyList(),
    val message: String = "",
    val saving: Boolean = false,
    val error: String? = null,
    val errorDetail: String? = null,
    val uncertain: Boolean = false,
    val createdId: String? = null,
    val openedId: String? = null
) {
    val adminId: String? get() = memberIds.firstOrNull()
}

@Serializable
data class SharedBotSnapshot(
    val connected: Boolean = false,
    val supported: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val mainBotId: String? = null,
    val chats: List<BotChat> = emptyList(),
    val selectedChatId: String? = null,
    val messages: List<BotChatMessage> = emptyList(),
    val historyLoading: Boolean = false,
    val hasEarlier: Boolean = false,
    val feedActive: Boolean = false,
    val schedules: List<BotSchedule> = emptyList(),
    val recent: List<BotActivity> = emptyList(),
    val interactions: List<BotInteraction> = emptyList(),
    val models: List<BotRoleOption> = emptyList(),
    val permissions: List<BotRoleOption> = emptyList(),
    val defaultModel: String? = null,
    val effortModel: String? = null,
    val efforts: List<BotRoleOption> = emptyList(),
    val memoryAvailable: Boolean = true,
    val schedulesAvailable: Boolean = false,
    val roleDetails: BotRoleDetails? = null,
    val roleSaving: Boolean = false,
    val roleSettingsSupported: Boolean = false,
    val attachmentsSupported: Boolean = false,
    val referencesSupported: Boolean = false,
    val referenceSearch: BotReferenceSearch? = null,
    val attachmentSending: Boolean = false,
    val lastAttachmentAck: String? = null,
    val roleError: String? = null,
    val computerEnabled: Boolean = false,
    val writable: Boolean = true,
    val creation: BotCreationState = BotCreationState(),
    val creationModels: List<BotRoleOption> = emptyList(),
    val defaultCreationModel: String? = null,
    val conversation: BotConversationState = BotConversationState(),
    val rosterIds: List<String> = emptyList(),
    val mainBotIds: List<String> = emptyList(),
    val botDeletionSupported: Boolean = false,
    val liveWork: Map<String, BotLiveWork> = emptyMap(),
    val unreadCounts: Map<String, Int> = emptyMap(),
) {
    fun unreadCount(chatId: String): Int = unreadCounts[chatId] ?: 0

    fun chatIndicator(chatId: String): BotChatIndicator? {
        val running = chatId in liveWork
        val unread = unreadCount(chatId)
        return if (running || unread > 0) BotChatIndicator(running, unread) else null
    }

    val roster: List<BotChat>
        get() {
            val ranks = rosterIds.withIndex().associate { it.value to it.index }
            return chats.filterNot(BotChat::room).sortedBy { if (it.id == mainBotId) -1 else ranks[it.id] ?: Int.MAX_VALUE }
        }

    fun isMainBot(id: String): Boolean = id == mainBotId || id in mainBotIds

    fun visibleLiveWork(chatId: String): BotLiveWork? {
        val work = liveWork[chatId] ?: return null
        if (interactions.any { it.chatId == chatId }) return null
        if (work.kind == "thinking" && selectedChatId == chatId && messages.lastOrNull()?.streaming == true) return null
        return work
    }

    val canCreateBot: Boolean
        get() = connected && writable && creation.supported
}

data class SharedBotTransition(val snapshot: SharedBotSnapshot, val requests: List<GatewayRequest> = emptyList())

@Serializable
private data class BotCache(
    val version: Int = 1,
    val snapshot: SharedBotSnapshot,
    val histories: Map<String, List<BotChatMessage>>,
    val optimistic: Map<String, List<BotChatMessage>>,
    val noticeBoot: String? = null,
    val noticeSeq: Long = 0L,
    val readThrough: Map<String, Long> = emptyMap(),
)

/** 每个主机一个实例，由平台 Store 串行调用；所有写操作均不自动重试。 */
class SharedBotStore {
    private var state = SharedBotSnapshot()
    private var enabled = false
    private var referenceRequest: Pair<String, BotReferenceSearch>? = null
    private var ordinal = 0L
    private var revision = -1L
    private var boot: String? = null
    private var noticeSeq = 0L
    private var noticeTip = 0L
    private var feedBoot: String? = null
    private val queuedNotices = mutableMapOf<Long, JsonObject>()
    private val readThrough = mutableMapOf<String, Long>()
    private var foreground = true
    private var stateDirty = false
    private var subscription: String? = null
    private val pending = linkedMapOf<String, Pending>()
    private var conversationSend: String? = null
    private val histories = linkedMapOf<String, List<BotChatMessage>>()
    private var projectedHistorySource: List<BotChatMessage> = emptyList()
    private var projectedHistory: List<BotChatMessage> = emptyList()
    private val cursors = mutableMapOf<String, JsonObject?>()
    private val dirty = mutableSetOf<String>()
    private val optimistic = mutableMapOf<String, List<BotChatMessage>>()
    private val sessionChats = mutableMapOf<String, String>()
    private val streams = linkedMapOf<String, BotChatMessage>()
    private val wantedAvatars = linkedMapOf<String, String>()
    private val attemptedAvatars = mutableMapOf<String, String>()
    private val outgoing = mutableListOf<GatewayRequest>()

    private data class Pending(val endpoint: String, val chatId: String? = null, val older: Boolean = false, val part: Int = 0)

    fun snapshot(): SharedBotSnapshot = state
    fun snapshotJson(): String = wireJson.encodeToString(state)

    fun searchChats(query: String): List<BotChat> {
        val term = query.trim()
        // hidden 仅隐藏聊天入口，群成员、头像和角色管理仍需完整团队数据。
        val visible = state.chats.filter { !it.hidden || (!it.room && state.isMainBot(it.id)) }
        if (term.isEmpty()) return visible.filter { it.id != state.mainBotId }

        return visible.filter { chat ->
            chat.name.contains(term, ignoreCase = true) || chat.preview.contains(term, ignoreCase = true) ||
                histories[chat.id].orEmpty().any { message ->
                    (message.kind == "user" || message.kind == "bot" || message.kind == "post") &&
                        message.text.contains(term, ignoreCase = true)
                }
        }
    }

    fun exportCacheJson(): String {
        var pages = histories.mapValues { it.value.takeLast(100) }
        val cached = state.copy(connected = false, loading = false, historyLoading = false,
            feedActive = false, selectedChatId = null, messages = emptyList(), interactions = emptyList(), error = null,
            roleSaving = false, roleDetails = null, roleError = null, effortModel = null, efforts = emptyList(),
            roleSettingsSupported = false, attachmentsSupported = false, attachmentSending = false, lastAttachmentAck = null,
            referencesSupported = false, referenceSearch = null, botDeletionSupported = false, liveWork = emptyMap(), creation = BotCreationState(), conversation = BotConversationState())
        val uncertain = optimistic.mapValues { (_, messages) -> messages.map { it.copy(delivery = "unknown") } }
        val cache = BotCache(snapshot = cached, histories = pages, optimistic = uncertain,
            noticeBoot = boot, noticeSeq = noticeSeq, readThrough = readThrough.toMap())
        var json = wireJson.encodeToString(cache)
        while (json.length > 7_500_000 && pages.isNotEmpty()) {
            pages = pages - pages.keys.first()
            json = wireJson.encodeToString(cache.copy(histories = pages))
        }
        return json.takeIf { it.length <= 7_500_000 } ?: ""
    }

    fun restoreCacheJson(json: String): SharedBotTransition {
        if (enabled || json.length > 7_500_000) return result()
        val cached = runCatching { wireJson.decodeFromString<BotCache>(json) }.getOrNull() ?: return result()
        if (cached.version != 1) return result()
        boot = cached.noticeBoot
        noticeSeq = cached.noticeSeq.coerceAtLeast(0)
        noticeTip = noticeSeq
        readThrough.clear()
        readThrough.putAll(cached.readThrough.filterValues { it > noticeSeq })
        state = cached.snapshot.copy(connected = false, supported = false, loading = false,
            historyLoading = false, feedActive = false, selectedChatId = null, messages = emptyList(), interactions = emptyList(), liveWork = emptyMap())
        histories.clear()
        histories.putAll(cached.histories.entries.toList().takeLast(12).associate { it.key to it.value.takeLast(100) })
        optimistic.clear()
        optimistic.putAll(cached.optimistic.mapValues { (_, messages) -> messages.map { it.copy(delivery = "unknown") } })
        return result()
    }

    fun activate(supported: Boolean, connected: Boolean): SharedBotTransition {
        if (!enabled) state = state.copy(connected = false)
        enabled = true
        return connectionChanged(supported, connected)
    }

    fun connectionChanged(supported: Boolean, connected: Boolean): SharedBotTransition {
        val reconnect = connected && (!state.connected || !state.supported)
        state = state.copy(supported = supported, connected = connected)
        if (!connected) {
            val deletionUnconfirmed = pending.values.any { it.endpoint == "delete-bot" }
            conversationSend = null
            state = state.copy(conversation = state.conversation.copy(supported = false, saving = false,
                uncertain = state.conversation.uncertain || state.conversation.saving,
                error = if (state.conversation.saving) "uncertain" else state.conversation.error))
            markUncertain()
            pending.clear()
            referenceRequest = null
            attemptedAvatars.clear()
            revision = -1
            subscription = null
            feedBoot = null
            queuedNotices.clear()
            streams.clear()
            state = state.copy(loading = false, historyLoading = false, feedActive = false, roleSaving = false, attachmentsSupported = false,
                roleSettingsSupported = false, referencesSupported = false, referenceSearch = null, botDeletionSupported = false,
                liveWork = emptyMap(),
                roleError = if (deletionUnconfirmed) "bot-delete-unconfirmed" else state.roleError,
                creation = state.creation.copy(supported = false, saving = false, loadingAppearance = false,
                    loadingAgents = false, loadingAgentModels = false, loadingPhotos = false,
                    error = if (state.creation.saving) "uncertain" else state.creation.error))
        } else if (enabled && supported && reconnect) refreshAll()
        return result()
    }

    fun refresh(): SharedBotTransition {
        if (state.connected && state.supported) refreshAll()
        return result()
    }

    fun selectChat(id: String?): SharedBotTransition {
        if (id != state.selectedChatId) {
            streams.clear()
            state = state.copy(referenceSearch = null)
        }
        state = state.copy(selectedChatId = id, error = null)
        if (foreground) markChatRead(id)
        if (id != null && state.connected && state.supported) readChat(id)
        return result()
    }

    fun searchConversationBots(query: String): List<BotChat> {
        val term = query.trim()
        return state.roster.filter { term.isEmpty() || it.name.contains(term, true) || it.role.contains(term, true) }
    }

    fun beginConversation(): SharedBotTransition {
        val draft = state.conversation
        if (!draft.saving) state = state.copy(conversation = if (draft.createdId != null || draft.openedId != null)
            BotConversationState(supported = draft.supported) else draft.copy(error = if (draft.uncertain) "uncertain" else null,
                errorDetail = if (draft.uncertain) draft.errorDetail else null))
        return result()
    }

    fun toggleConversationMember(id: String): SharedBotTransition {
        val draft = state.conversation
        if (draft.saving || draft.createdId != null || draft.uncertain || state.roster.none { it.id == id }) return result()
        val members = if (id in draft.memberIds) draft.memberIds - id else draft.memberIds + id
        state = state.copy(conversation = draft.copy(memberIds = members, error = null, errorDetail = null))
        return result()
    }

    fun updateConversationMessage(text: String): SharedBotTransition {
        if (!state.conversation.saving && state.conversation.createdId == null && !state.conversation.uncertain && text.length <= 100_000)
            state = state.copy(conversation = state.conversation.copy(message = text))
        return result()
    }

    fun openConversationBot(id: String): SharedBotTransition {
        val bot = state.roster.firstOrNull { it.id == id } ?: return result()
        if (state.conversation.saving) return result()
        if (bot.hidden) {
            if (!canWriteConversation()) return result()
            if (call("set-flags", buildJsonObject { put("id", id); put("hidden", false) }, id) != null)
                state = state.copy(conversation = state.conversation.copy(saving = true, error = null, errorDetail = null))
        } else state = state.copy(conversation = state.conversation.copy(openedId = id))
        return result()
    }

    fun createConversationRoom(): SharedBotTransition {
        val draft = state.conversation
        if (!canWriteConversation() || draft.saving || draft.uncertain || draft.createdId != null ||
            draft.memberIds.distinct().size < 2 || draft.memberIds.any { id -> state.roster.none { it.id == id } }) return result()
        if (call("create-room", buildJsonObject {
            put("members", JsonArray(draft.memberIds.map(::JsonPrimitive)))
            put("admin", requireNotNull(draft.adminId))
            if (draft.message.isBlank()) put("greet", true)
        }) != null) state = state.copy(conversation = draft.copy(saving = true, error = null, errorDetail = null))
        return result()
    }

    /** 群已创建后仅重试首条消息，绝不重新建群。 */
    fun retryConversationMessage(): SharedBotTransition {
        if (canWriteConversation() && !state.conversation.saving && !state.conversation.uncertain)
            sendConversationMessage()
        return result()
    }

    /** 只有用户核对聊天列表后，才能解除未知建群结果的防重复保护。 */
    fun confirmConversationNotCreated(): SharedBotTransition {
        val draft = state.conversation
        if (!draft.saving && draft.uncertain && draft.createdId == null)
            state = state.copy(conversation = draft.copy(uncertain = false, error = null, errorDetail = null))
        return result()
    }

    fun enterCreatedConversation(): SharedBotTransition {
        if (!state.conversation.saving) state = state.copy(conversation = state.conversation.copy(openedId = state.conversation.createdId))
        return result()
    }

    private fun refreshConversationState() {
        // 创建前发出的 state 快照不能覆盖刚创建的群或刚恢复的可见性。
        pending.entries.removeAll { it.value.endpoint == "state" }
        revision = -1
        stateDirty = false
        readState()
    }

    private fun canWriteConversation(): Boolean = state.connected && state.supported && state.writable && state.conversation.supported

    private fun sendConversationMessage() {
        val draft = state.conversation
        val room = draft.createdId ?: return
        conversationSend = call("send", buildJsonObject { put("sessionId", room); put("text", draft.message.trim()) }, room)
        state = state.copy(conversation = draft.copy(saving = conversationSend != null,
            error = if (conversationSend == null) "busy" else null, errorDetail = null))
        conversationSend?.let { request -> optimistic[room] = optimistic[room].orEmpty() +
            BotChatMessage(request, "user", draft.message.trim(), delivery = "sending") }
    }

    fun setForeground(active: Boolean): SharedBotTransition {
        foreground = active
        if (active) markChatRead(state.selectedChatId)
        return result()
    }

    private fun markChatRead(id: String?) {
        if (id == null) return
        if (noticeTip > noticeSeq) readThrough[id] = maxOf(noticeTip, readThrough[id] ?: 0L)
        if (state.unreadCount(id) > 0) state = state.copy(unreadCounts = state.unreadCounts - id)
    }

    fun loadEarlier(): SharedBotTransition {
        val id = state.selectedChatId
        if (id != null && cursors[id] != null && state.connected) readChat(id, true)
        return result()
    }

    fun sendMessage(text: String): SharedBotTransition = sendMessageWithAttachments(text, "[]", "[]")

    fun sendMessageWithAttachments(text: String, imagesJson: String, receiptsJson: String): SharedBotTransition =
        sendMentionMessage(text, text, imagesJson, receiptsJson)

    fun sendMentionMessage(text: String, displayText: String, imagesJson: String, receiptsJson: String): SharedBotTransition {
        val id = state.selectedChatId ?: return result()
        if (!state.connected || !state.supported || !state.writable || text.length > 100_000) return result()
        val images = runCatching { wireJson.parseToJsonElement(imagesJson) as? JsonArray }.getOrNull() ?: return result()
        val receipts = runCatching { wireJson.parseToJsonElement(receiptsJson) as? JsonArray }.getOrNull() ?: return result()
        val withAttachments = images.isNotEmpty() || receipts.isNotEmpty()
        if (!withAttachments && text.isBlank()) return result()
        val session = state.chats.firstOrNull { it.id == id }?.sessionId
        if (withAttachments && (!state.attachmentsSupported || session == null ||
                images.size + receipts.size > 20 || pending.values.any { it.endpoint == "send-attachments" })) return result()
        val requestId = call(if (withAttachments) "send-attachments" else "send", buildJsonObject {
            put("sessionId", if (withAttachments) session else id)
            put("text", text.trim())
            if (withAttachments) {
                put("chatId", id)
                put("images", images)
                put("receipts", receipts)
            }
        }, id)
        if (requestId != null) optimistic[id] = optimistic[id].orEmpty() +
            BotChatMessage(requestId, "user", displayText.trim(), delivery = "sending")
        return result()
    }

    fun searchReferences(query: String, quoted: Boolean): SharedBotTransition {
        val chat = state.selectedChatId
        if (chat == null || !state.referencesSupported || !state.connected || query.length > 256 || query.any { it.isISOControl() }) return result()
        val current = state.referenceSearch
        if (current == null || current.chatId != chat || current.query != query || current.quoted != quoted || current.unavailable) {
            state = state.copy(referenceSearch = BotReferenceSearch(chat, query, quoted))
        }
        requestReferences()
        return result()
    }

    fun clearReferenceSearch(): SharedBotTransition {
        state = state.copy(referenceSearch = null)
        return result()
    }

    private fun requestReferences() {
        val search = state.referenceSearch ?: return
        if (!search.loading || referenceRequest != null) return
        val requestId = call("reference-candidates", buildJsonObject {
            put("chatId", search.chatId); put("query", search.query); put("quoted", search.quoted)
        }, search.chatId)
        if (requestId != null) referenceRequest = requestId to search
        else state = state.copy(referenceSearch = search.copy(loading = false, unavailable = true))
    }

    private fun finishReferences(id: String, obj: JsonObject?) {
        val requested = referenceRequest?.takeIf { it.first == id }?.second ?: return
        referenceRequest = null
        if (state.referenceSearch == requested) {
            val items = obj?.array("items").orEmpty().take(48).mapNotNull { row ->
                runCatching { wireJson.decodeFromJsonElement<BotMentionCandidate>(row) }.getOrNull()
            }
            state = state.copy(referenceSearch = requested.copy(loading = false, items = items, unavailable = obj == null))
        }
        requestReferences()
    }

    fun openCreation(): SharedBotTransition {
        if (!canCreate()) return result()
        creation { it.copy(createdId = null, error = null) }
        if (state.creation.appearance == null && call("creation-appearance") != null) {
            creation { it.copy(loadingAppearance = true, appearanceFailed = false) }
        }
        return result()
    }

    fun loadCreationAgents(): SharedBotTransition {
        if (canCreate() && call("local-agents") != null) creation { it.copy(loadingAgents = true, agentsFailed = false) }
        return result()
    }

    fun loadCreationAgentModels(route: String): SharedBotTransition {
        if (canCreate() && state.creation.agents.any { it.id == route && it.available }) {
            val pending = call("local-agent-models", buildJsonObject { put("route", route) }, route)
            if (pending != null || state.creation.agentRoute != route) {
                creation { it.copy(agentRoute = route, agentModels = emptyList(), loadingAgentModels = true) }
            }
        }
        return result()
    }

    fun loadCreationPhotos(reset: Boolean): SharedBotTransition {
        if (!canCreate() || state.creation.loadingPhotos || (!reset && !state.creation.hasMorePhotos)) return result()
        val offset = if (reset) 0 else state.creation.photoOffset
        if (call("avatar-library", buildJsonObject { put("offset", offset) }, offset.toString()) != null) {
            creation { it.copy(loadingPhotos = true, photosFailed = false) }
        }
        return result()
    }

    fun importCreationPhoto(image: String, name: String): SharedBotTransition {
        if (!canCreate() || state.creation.loadingPhotos || !BotCreationRules.isPhoto(image)) return result()
        if (call("avatar-import", buildJsonObject {
            put("items", JsonArray(listOf(buildJsonObject {
                put("image", image)
                put("name", name.take(120))
            })))
        }) != null) creation { it.copy(loadingPhotos = true, photosFailed = false) }
        return result()
    }

    fun createBot(name: String, shape: String, color: String, image: String?,
        model: String?, effort: String?, agentAppearance: Boolean): SharedBotTransition {
        if (!canCreate() || state.creation.saving || state.creation.error == "uncertain" ||
            BotCreationRules.nameProblem(name, state.chats) != null ||
            state.creation.appearance?.image(shape, color) == null ||
            (image != null && !BotCreationRules.isPhoto(image))) return result()
        val route = model?.takeIf(BotCreationRules::isLocalModel)?.substringBefore('/')
        val validModel = if (route != null) state.creation.agents.any { it.id == route && it.available } &&
            (model == "$route/default" || state.creation.agentRoute == route && state.creation.agentModels.any { it.id == model })
        else model.isNullOrBlank() || state.creationModels.any { it.id == model }
        if (!validModel) return result()
        if (call("create-bot", buildJsonObject {
            put("name", name.trim())
            put("color", color)
            put("avatar", buildJsonObject {
                put("shape", shape)
                image?.let { put("image", it) }
            })
            model?.takeIf(String::isNotBlank)?.let { put("model", it) }
            if (agentAppearance && route != null && image == null) put("agentAppearance", true)
            effort?.takeIf { route == null && state.effortModel == model && state.efforts.any { choice -> choice.id == it } }
                ?.let { put("effort", it) }
        }) != null) creation { it.copy(saving = true, createdId = null, error = null) }
        return result()
    }

    private fun canCreate(): Boolean = state.canCreateBot

    private fun creation(update: (BotCreationState) -> BotCreationState) {
        state = state.copy(creation = update(state.creation))
    }

    fun openRole(id: String): SharedBotTransition {
        if (state.chats.none { it.id == id && !it.room }) return result()
        if (state.roleDetails?.botId != id) state = state.copy(roleDetails = BotRoleDetails(id), roleError = null)
        if (state.connected && state.supported) {
            call("memory-view", buildJsonObject { put("botId", id) }, id)
            call("usage-summary", buildJsonObject { put("botId", id) }, id)
            call("schedules")
        }
        return result()
    }

    fun loadRoleEfforts(ref: String): SharedBotTransition {
        if (ref.isBlank() || ref == state.effortModel) return result()
        state = state.copy(effortModel = ref, efforts = emptyList())
        if (state.connected) call("efforts", buildJsonObject { put("ref", ref) }, ref)
        return result()
    }

    private fun canEditRole(id: String): Boolean = state.connected && state.roleSettingsSupported &&
        !state.roleSaving && state.writable && state.chats.any { it.id == id && !it.room }

    fun updateRole(id: String, changesJson: String): SharedBotTransition {
        if (!canEditRole(id)) return result()
        val changes = runCatching { wireJson.parseToJsonElement(changesJson) as? JsonObject }.getOrNull() ?: return result()
        if (changes.isEmpty() || "id" in changes) return result()
        return roleWrite("update-bot", id, JsonObject(changes + ("id" to JsonPrimitive(id))))
    }

    fun saveRoleMemory(id: String, entryId: String?, value: String): SharedBotTransition {
        val entry = state.roleDetails?.takeIf { it.botId == id }?.memories?.firstOrNull { it.id == entryId }
        if (!state.memoryAvailable || value.isBlank() || (entryId != null && entry == null)) return result()
        return roleWrite(if (entry == null) "memory-add" else "memory-edit", id, buildJsonObject {
            put("botId", id)
            if (entry == null) put("text", value.trim())
            else {
                put("scope", entry.scope)
                entry.topic?.let { put("topic", it) }
                put("text", entry.text)
                put("next", value.trim())
            }
        })
    }

    fun forgetRoleMemory(id: String, entryId: String): SharedBotTransition {
        val entry = state.roleDetails?.takeIf { it.botId == id }?.memories?.firstOrNull { it.id == entryId }
            ?: return result()
        return roleWrite("memory-forget", id, buildJsonObject {
            put("botId", id)
            put("scope", entry.scope)
            entry.topic?.let { put("topic", it) }
            put("text", entry.text)
        })
    }

    fun scheduleRoleDaily(id: String, title: String, prompt: String, time: String, zone: String): SharedBotTransition {
        if (!state.schedulesAvailable || title.isBlank() || prompt.isBlank() || !Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(time)) return result()
        return roleWrite("schedule-create", id, buildJsonObject {
            put("target", id)
            put("title", title.trim())
            put("prompt", prompt.trim())
            put("when", buildJsonObject { put("kind", "daily"); put("time", time); put("timeZone", zone) })
        })
    }

    fun deleteRoleSchedule(id: String, taskId: String): SharedBotTransition {
        val session = state.schedules.firstOrNull { it.id == taskId && it.botId == id }?.sessionId ?: return result()
        return roleWrite("schedule-delete", id, buildJsonObject { put("sessionId", session); put("id", taskId) })
    }

    fun duplicateRole(id: String): SharedBotTransition =
        roleWrite("duplicate-bot", id, buildJsonObject { put("id", id) })

    fun deleteRole(id: String): SharedBotTransition {
        if (!state.botDeletionSupported || state.isMainBot(id)) return result()
        return roleWrite("delete-bot", id, buildJsonObject { put("id", id) })
    }

    fun replacePrimaryRole(current: String, replacement: String): SharedBotTransition {
        if (state.mainBotId != current || current == replacement ||
            state.chats.none { it.id == replacement && !it.room && it.profile?.privateRole != true }) return result()
        return roleWrite("set-main", current, buildJsonObject {
            put("id", replacement); put("main", true); put("primary", true); put("replace", current)
        })
    }

    private fun roleWrite(endpoint: String, id: String, payload: JsonObject): SharedBotTransition {
        if (canEditRole(id) && call(endpoint, payload, id) != null) {
            state = state.copy(roleSaving = true, roleError = null)
        }
        return result()
    }

    fun requestFailed(requestId: String, code: String): SharedBotTransition {
        failed(requestId, code)
        return result()
    }

    fun answerQuestion(id: String, selectedJson: String, custom: String): SharedBotTransition {
        val interaction = state.interactions.firstOrNull { it.id == id && it.kind == "question" } ?: return result()
        if (!state.connected) return result()
        val selected = runCatching { wireJson.parseToJsonElement(selectedJson) as? JsonArray }.getOrNull() ?: return result()
        call("answer-question", buildJsonObject {
            put("sessionId", interaction.sessionId); put("questionId", id)
            put("answer", buildJsonObject { put("selected", selected); if (custom.isNotBlank()) put("custom", custom.trim()) })
        })
        return result()
    }

    fun answerApproval(id: String, allow: Boolean): SharedBotTransition {
        if (!state.connected || state.interactions.none { it.id == id && it.kind == "approval" }) return result()
        call("approval-answer", buildJsonObject { put("id", id); put("choice", if (allow) "allow" else "reject") })
        return result()
    }

    fun acceptJson(json: String): SharedBotTransition {
        val frame = runCatching { wireJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return result()
        when (frame.text("kind")) {
            "bot-subscribed" -> if (frame.text("requestId") == subscription) {
                pending.remove(requireNotNull(subscription))
                val capabilities = (frame["hello"] as? JsonObject)?.array("capabilities").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                state = state.copy(feedActive = true, error = null, roleSettingsSupported = "role-settings" in capabilities,
                    attachmentsSupported = "bot-attachments" in capabilities,
                    referencesSupported = "reference-candidates" in capabilities,
                    botDeletionSupported = "bot-deletion" in capabilities,
                    creation = state.creation.copy(supported = "bot-creation" in capabilities),
                    conversation = state.conversation.copy(supported = "bot-conversations" in capabilities))
            }
            "bot-stream-reset" -> if (frame.text("subscriptionId") == subscription) {
                subscription = null
                feedBoot = null
                state = state.copy(feedActive = false, error = "bot-feed-interrupted")
                // 由平台的有界恢复计时器调用 refresh；防止插件停用时忙循环。
            }
            "bot-event" -> if (frame.text("subscriptionId") == subscription) {
                event(frame.text("botEvent"), frame["botData"] as? JsonObject ?: return result())
            }
            "bot-result" -> {
                val id = frame.text("requestId") ?: return result()
                val request = pending.remove(id) ?: return result()
                if (frame.text("endpoint") != request.endpoint) return result()
                reply(id, request, frame["value"])
            }
            "error" -> frame.text("requestId")?.let { failed(it, frame.text("code") ?: "bot-request-failed", frame.text("message")) }
        }
        loadAvatars()
        return result()
    }

    private fun refreshAll() {
        state = state.copy(error = null)
        if (subscription == null) {
            val id = nextId()
            subscription = id
            pending[id] = Pending("subscribe")
            outgoing += request("bot-subscribe", id, buildJsonObject { put("type", "bot-subscribe"); put("requestId", id) })
        }
        readState()
        call("schedules")
        readNotices()
        state.selectedChatId?.let { readChat(it) }
    }

    private fun readNotices() {
        call("notices", buildJsonObject { boot?.let { put("boot", it) }; put("since", noticeSeq) })
    }

    private fun readState() {
        if (pending.values.any { it.endpoint == "state" }) { stateDirty = true; return }
        if (call("state", buildJsonObject { put("avatars", "ref"); put("since", revision) }) != null) {
            state = state.copy(loading = true)
        }
    }

    private fun readChat(id: String, older: Boolean = false) {
        if (!state.connected || !state.supported) return
        val chat = state.chats.firstOrNull { it.id == id } ?: return
        if (pending.values.any { it.chatId == id && it.endpoint in setOf("chat", "room-chat") }) {
            if (!older) dirty.add(id)
            return
        }
        val cursor = if (older) cursors[id] else null
        val payload = buildJsonObject {
            put(if (chat.room) "roomId" else "botId", id)
            if (!chat.room) put("historyDetails", true)
            cursor?.let { put("cursor", it) }
        }
        call(if (chat.room) "room-chat" else "chat", payload, id, older,
            cursor?.get("part")?.jsonPrimitive?.intOrNull ?: chat.part)
        if (id == state.selectedChatId) state = state.copy(historyLoading = true)
    }

    private fun reply(id: String, request: Pending, value: JsonElement?) {
        val obj = value as? JsonObject ?: JsonObject(emptyMap())
        when (request.endpoint) {
            "reference-candidates" -> finishReferences(id, obj)
            "state" -> {
                val version = obj["revision"]?.jsonPrimitive?.longOrNull ?: -1L
                state = state.copy(loading = false)
                if (version < revision) return
                revision = version
                val unchanged = obj["unchanged"] == JsonPrimitive(true)
                val work = obj["work"] as? JsonObject
                if (!unchanged) {
                    sessionChats.clear()
                    (obj.array("bots") + obj.array("rooms")).forEach { entry ->
                        val peer = entry as? JsonObject ?: return@forEach
                        val peerId = peer.text("id") ?: return@forEach
                        sessionChats[peer.text("sessionId") ?: peerId] = peerId
                    }
                    val bots = (work?.get("bots") as? JsonObject).orEmpty()
                    val rooms = (work?.get("rooms") as? JsonObject).orEmpty()
                    val peers = obj.array("bots").mapNotNull { peer(it, false, bots) } +
                        obj.array("rooms").mapNotNull { peer(it, true, rooms) }
                    val availableModels = obj.array("models").mapNotNull { item ->
                        val row = item as? JsonObject ?: return@mapNotNull null
                        val ref = row.text("ref") ?: return@mapNotNull null
                        if (BotCreationRules.isLocalModel(ref) || row["needsKey"] == JsonPrimitive(true)) return@mapNotNull null
                        BotRoleOption(ref, row.text("name") ?: ref)
                    }.distinctBy(BotRoleOption::id)
                    val teamDefault = (obj["prefs"] as? JsonObject)?.text("defaultBotModel")
                    val creationDefault = sequenceOf(teamDefault, obj.text("defaultModel"), availableModels.firstOrNull()?.id)
                        .firstOrNull { candidate -> availableModels.any { it.id == candidate } }
                    state = state.copy(creationModels = availableModels, defaultCreationModel = creationDefault,
                        mainBotId = obj.text("mainBotId"), chats = peers.sortedByDescending { it.lastAt },
                        mainBotIds = obj.array("mainBotIds").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                        rosterIds = peers.filterNot(BotChat::room).map(BotChat::id),
                        models = obj.array("models").mapNotNull { item ->
                            val model = item as? JsonObject ?: return@mapNotNull null
                            val ref = model.text("ref") ?: return@mapNotNull null
                            BotRoleOption(ref, model.text("name") ?: ref)
                        },
                        permissions = (obj["permissions"] as? JsonObject)?.array("options").orEmpty().mapNotNull { item ->
                            val option = item as? JsonObject ?: return@mapNotNull null
                            val id = option.text("value") ?: return@mapNotNull null
                            BotRoleOption(id, option.text("label") ?: id)
                        },
                        defaultModel = obj.text("defaultModel") ?: obj.text("profileDefaultModel"),
                        memoryAvailable = obj["memory"] != JsonPrimitive(false),
                        computerEnabled = (obj["computer"] as? JsonObject)?.get("enabled")?.jsonPrimitive?.booleanOrNull == true,
                        writable = obj["readOnly"].let { it == null || it == JsonNull || it == JsonPrimitive(false) })
                    if (!state.conversation.saving && state.conversation.createdId == null && !state.conversation.uncertain) {
                        val members = state.conversation.memberIds.filter { id -> peers.any { it.id == id && !it.room } }
                        state = state.copy(conversation = state.conversation.copy(memberIds = members))
                    }
                    wantedAvatars.clear()
                    for (entry in obj.array("bots")) {
                        val bot = entry as? JsonObject ?: continue
                        val id = bot.text("id") ?: continue
                        val ref = (bot["avatar"] as? JsonObject)?.text("imageRef") ?: continue
                        wantedAvatars[id] = ref
                    }
                    attemptedAvatars.keys.retainAll(wantedAvatars.keys)
                } else if (work != null) {
                    state = state.copy(chats = state.chats.map { chat -> chat.copy(running =
                        ((work[if (chat.room) "rooms" else "bots"] as? JsonObject)?.get(chat.id))
                            ?.let { it != JsonNull && (it !is JsonArray || it.isNotEmpty()) } == true) })
                }
                if (work != null || !unchanged) {
                    state = state.copy(liveWork = if (work == null) emptyMap() else liveStatus(work))
                }
                val interactions = mutableListOf<BotInteraction>()
                if (obj["unchanged"]?.jsonPrimitive?.booleanOrNull == true) {
                    interactions += state.interactions.filter { it.kind != "approval" }
                } else (obj["questions"] as? JsonObject).orEmpty().forEach { (session, value) ->
                    val question = value as? JsonObject ?: return@forEach
                    val questionId = question.text("id") ?: return@forEach
                    interactions += BotInteraction(questionId, "question", sessionChats[session] ?: session, session,
                        question.text("question").orEmpty(), question.text("detail").orEmpty(),
                        question.array("options").mapNotNull { (it as? JsonObject)?.text("label") },
                        question["multiSelect"]?.jsonPrimitive?.booleanOrNull == true)
                }
                ((obj["work"] as? JsonObject)?.get("approvals") as? JsonArray).orEmpty().forEach { value ->
                    val approval = value as? JsonObject ?: return@forEach
                    if (approval["answerable"]?.jsonPrimitive?.booleanOrNull == false) return@forEach
                    interactions += BotInteraction(approval.text("id") ?: return@forEach, "approval",
                        approval.text("roomId") ?: approval.text("botId").orEmpty(), approval.text("sessionId").orEmpty(),
                        approval.text("reason") ?: approval.text("tool").orEmpty(),
                        (approval["detail"] as? JsonObject)?.text("text").orEmpty())
                }
                state = state.copy(interactions = interactions)
                state.selectedChatId?.let { if (it !in histories) readChat(it) }
                if (stateDirty) { stateDirty = false; readState() }
            }
            "avatar" -> {
                val botId = request.chatId ?: return
                val ref = obj.text("ref")
                val image = obj.text("image")?.takeIf { it.startsWith("data:image/") && it.length < 256_000 }
                if (obj.text("botId") == botId && ref != null && ref == wantedAvatars[botId] && image != null) {
                    state = state.copy(chats = state.chats.map { if (it.id == botId) it.copy(image = image, imageRef = ref) else it })
                }
            }
            "efforts" -> {
                if (request.chatId != state.effortModel) return
                state = state.copy(efforts = obj.array("efforts").mapNotNull { item ->
                    val option = item as? JsonObject ?: return@mapNotNull null
                    val level = option.text("id") ?: return@mapNotNull null
                    BotRoleOption(level, option.text("name") ?: level)
                })
            }
            "memory-view", "memory-add", "memory-edit", "memory-forget", "usage-summary" -> {
                if (request.endpoint != "memory-view" && request.endpoint != "usage-summary") {
                    state = state.copy(roleSaving = false, roleError = null)
                }
                val details = state.roleDetails?.takeIf { it.botId == request.chatId } ?: return
                state = state.copy(roleDetails = if (request.endpoint != "usage-summary") {
                    details.copy(memories = obj.array("entries").mapNotNull {
                        val row = it as? JsonObject ?: return@mapNotNull null
                        BotMemory(row.text("id") ?: return@mapNotNull null,
                            row.text("scope") ?: return@mapNotNull null, row.text("topic"),
                            row.text("text") ?: return@mapNotNull null)
                    })
                } else {
                    val month = obj["month"] as? JsonObject
                    details.copy(inputTokens = (month?.get("input") as? JsonPrimitive)?.longOrNull,
                        outputTokens = (month?.get("output") as? JsonPrimitive)?.longOrNull)
                })
            }
            "creation-appearance" -> {
                val appearance = runCatching { wireJson.decodeFromJsonElement<BotCreationAppearance>(obj) }.getOrNull()
                creation { it.copy(appearance = appearance, loadingAppearance = false, appearanceFailed = appearance == null) }
            }
            "local-agents" -> {
                val agents = obj.array("items").mapNotNull { row ->
                    runCatching { wireJson.decodeFromJsonElement<BotCreationAgent>(row) }.getOrNull()
                }.distinctBy(BotCreationAgent::id)
                creation { it.copy(agents = agents, loadingAgents = false) }
            }
            "local-agent-models" -> if (state.creation.agentRoute == request.chatId) {
                val models = obj.array("items").mapNotNull { row ->
                    runCatching { wireJson.decodeFromJsonElement<BotRoleOption>(row) }.getOrNull()
                }.distinctBy(BotRoleOption::id)
                creation { it.copy(agentModels = models, loadingAgentModels = false) }
            }
            "avatar-library" -> {
                val photos = obj.array("items").mapNotNull { row ->
                    runCatching { wireJson.decodeFromJsonElement<BotAvatarPhoto>(row) }.getOrNull()
                        ?.takeIf { BotCreationRules.isPhoto(it.image) }
                }
                val offset = request.chatId?.toIntOrNull() ?: 0
                val next = (obj["next"] as? JsonPrimitive)?.intOrNull ?: offset
                val total = (obj["total"] as? JsonPrimitive)?.intOrNull ?: next
                creation { it.copy(photos = ((if (offset == 0) emptyList() else it.photos) + photos).distinctBy(BotAvatarPhoto::id),
                    photoOffset = next, loadingPhotos = false, hasMorePhotos = next > offset && next < total) }
            }
            "avatar-import" -> {
                val failed = obj.array("failed").isNotEmpty()
                creation { it.copy(loadingPhotos = false, photosFailed = failed) }
                if (!failed && call("avatar-library", buildJsonObject { put("offset", 0) }, "0") != null) {
                    creation { it.copy(loadingPhotos = true) }
                }
            }
            "set-flags" -> {
                val bot = request.chatId ?: return
                state = state.copy(chats = state.chats.map { if (it.id == bot) it.copy(hidden = false) else it },
                    conversation = state.conversation.copy(saving = false, error = null, openedId = bot))
                refreshConversationState()
            }
            "create-room" -> {
                val room = peer(obj, true, emptyMap())?.takeIf { it.id.isNotBlank() }
                if (room == null) {
                    state = state.copy(conversation = state.conversation.copy(saving = false, uncertain = true, error = "uncertain"))
                } else {
                    val draft = state.conversation
                    state = state.copy(chats = state.chats.filterNot { it.id == room.id } + room,
                        conversation = draft.copy(createdId = room.id, saving = false))
                    if (draft.message.isBlank()) state = state.copy(conversation = state.conversation.copy(openedId = room.id))
                    else sendConversationMessage()
                }
                refreshConversationState()
            }
            "create-bot" -> {
                val created = obj.text("id")
                creation { it.copy(saving = false, createdId = created, error = if (created == null) "uncertain" else null) }
                revision = -1
                if (pending.values.any { it.endpoint == "state" }) stateDirty = true else readState()
            }
            "delete-bot" -> {
                val botId = request.chatId ?: return
                if (obj.text("id") == botId) {
                    removeDeletedBot(botId)
                } else {
                    state = state.copy(roleSaving = false, roleError = "bot-delete-unconfirmed")
                    revision = -1
                    readState()
                }
            }
            "update-bot", "duplicate-bot", "set-main" -> {
                state = state.copy(roleSaving = false, roleError = null)
                revision = -1
                readState()
            }
            "chat", "room-chat" -> {
                val chatId = request.chatId ?: return
                val counts = mutableMapOf<String, Int>()
                val page = obj.array("items").mapIndexedNotNull { index, entry ->
                    val item = entry as? JsonObject ?: return@mapIndexedNotNull null
                    val kind = item.text("kind") ?: return@mapIndexedNotNull null
                    val files = if (kind in setOf("bot", "post", "file")) presentedFiles(item) else emptyList()
                    if (kind == "file" && files.isEmpty()) return@mapIndexedNotNull null
                    val coordinate = item["seq"]?.toString() ?: item.text("id") ?: "row-$index"
                    val base = "$chatId:${request.part}:$coordinate:$kind"
                    val block = counts.getOrElse(base) { 0 }
                    counts[base] = block + 1
                    val sessionId = item.text("sessionId") ?: obj.text("sessionId") ?: chatId
                    val activity = item.text("event")?.takeIf {
                        kind == "event" && it in setOf("bot-created", "bot-messaged") && !item.text("name").isNullOrBlank()
                    }
                    BotChatMessage("$base:$block", kind, if (activity == null) messageText(item) else "",
                        item.text("name").orEmpty(), item.text("botId"),
                        timeMs = (item["time"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..253402300799999L },
                        activity = activity,
                        questionAnswers = if (kind == "answer") questionAnswers(item) else emptyList(),
                        files = files,
                        turn = (item["turn"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 },
                        sessionId = sessionId, images = item.array("imageRefs").take(20).mapNotNull { value ->
                            val ref = value as? JsonObject ?: return@mapNotNull null
                            val attachment = ref.text("attachmentId")?.takeIf { it.isNotBlank() && it.length <= 512 }
                                ?: return@mapNotNull null
                            val mime = ref.text("mime") ?: ref.text("mediaType") ?: "image/png"
                            if (mime !in setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp")) return@mapNotNull null
                            BotChatImage(attachment, ref.text("sessionId") ?: sessionId, mime,
                                ref.text("name").orEmpty().take(255),
                                (ref["width"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 16384) ?: 0,
                                (ref["height"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 16384) ?: 0)
                        }.distinctBy { it.sessionId to it.attachmentId })
                }
                val previous = histories[chatId].orEmpty()
                histories[chatId] = (if (request.older) page + previous else {
                    // 最新页替换重叠区，已经加载的较早历史仍保留。
                    val overlap = previous.indexOfFirst { old -> page.any { it.id == old.id } }
                    (if (overlap >= 0) previous.take(overlap) else emptyList()) + page
                }).distinctBy { it.id }.takeLast(2000)
                if (request.older || chatId !in cursors) cursors[chatId] = obj["cursor"] as? JsonObject
                if (!request.older) {
                    optimistic[chatId] = optimistic[chatId].orEmpty().filter { it.delivery != "sent" }
                    if (chatId == state.selectedChatId && chatId !in dirty) streams.entries.removeAll { !it.value.streaming }
                }
                if (chatId == state.selectedChatId) state = state.copy(historyLoading = false)
                if (dirty.remove(chatId)) readChat(chatId)
                while (histories.size > 12) {
                    val oldest = histories.keys.firstOrNull { it != state.selectedChatId } ?: break
                    histories.remove(oldest); cursors.remove(oldest)
                }
            }
            "send", "send-attachments" -> {
                val chat = request.chatId ?: return
                if (id == conversationSend) {
                    conversationSend = null
                    state = state.copy(conversation = state.conversation.copy(saving = false, openedId = chat, error = null))
                }
                if (request.endpoint == "send-attachments") state = state.copy(lastAttachmentAck = id)
                optimistic[chat] = optimistic[chat].orEmpty().map { if (it.id == id) it.copy(delivery = "sent") else it }
                readChat(chat)
                readState()
            }
            "answer-question", "approval-answer" -> {
                readState()
                state.selectedChatId?.let { readChat(it) }
            }
            "schedules", "schedule-create", "schedule-delete" -> state = state.copy(
                roleSaving = if (request.endpoint == "schedules") state.roleSaving else false,
                schedulesAvailable = obj["available"] == JsonPrimitive(true),
                schedules = obj.array("tasks").mapNotNull {
                val task = it as? JsonObject ?: return@mapNotNull null
                BotSchedule(task.text("id") ?: return@mapNotNull null, task.text("title").orEmpty(),
                    task.text("status").orEmpty(), task.text("scheduledAt").orEmpty(), task.text("botId"), task.text("sessionId"))
            })
            "notices" -> {
                if (obj["reset"]?.jsonPrimitive?.booleanOrNull == true) {
                    readState()
                    state.selectedChatId?.let { readChat(it) }
                }
                acceptNotices(obj)
            }
        }
    }

    private fun event(kind: String?, data: JsonObject) {
        when (kind) {
            "hello" -> {
                val nextBoot = data.text("boot")
                if (nextBoot != null) {
                    feedBoot = nextBoot
                    if (boot != null && boot != nextBoot) {
                        revision = -1
                        histories.clear(); cursors.clear(); streams.clear()
                    }
                    updateNoticeBoot(nextBoot, data.noticeSequence() ?: 0L)
                    noticeTip = maxOf(noticeTip, data.noticeSequence() ?: 0L)
                    if (foreground) markChatRead(state.selectedChatId)
                }
                readNotices()
                readState()
                state.selectedChatId?.let { readChat(it) }
            }
            "revision", "live" -> readState()
            "message" -> {
                readState()
                data.text("botId")?.takeIf { it == state.selectedChatId }?.let { readChat(it) }
            }
            "room" -> {
                readState()
                data.text("roomId")?.takeIf { it == state.selectedChatId }?.let { readChat(it) }
            }
            "notice" -> {
                val seq = data.noticeSequence() ?: return
                if (seq > noticeSeq) {
                    noticeTip = maxOf(noticeTip, seq)
                    if (queuedNotices.size < 200) queuedNotices[seq] = data
                    drainNotices()
                    if (noticeSeq < noticeTip) readNotices()
                }
                readState()
            }
            "stream" -> if (data.text("botId") == state.selectedChatId) {
                val id = data.text("attemptId") ?: return
                when (data.text("phase")) {
                    "start" -> streams[id] = BotChatMessage("stream:$id", "bot", "", streaming = true)
                    "end" -> {
                        streams[id]?.let { streams[id] = it.copy(streaming = false) }
                        state.selectedChatId?.let { readChat(it) }
                    }
                    else -> streams[id]?.let { streams[id] = it.copy(text = (it.text + data.text("text").orEmpty()).take(200_000)) }
                }
            }
        }
    }

    private fun updateNoticeBoot(nextBoot: String, tip: Long) {
        if (boot == nextBoot) return
        noticeSeq = if (boot == null) tip else 0L
        noticeTip = noticeSeq
        boot = nextBoot
        queuedNotices.clear()
        readThrough.clear()
    }

    private fun acceptNotices(value: JsonObject) {
        val responseBoot = value.text("boot") ?: return
        if (feedBoot != null && feedBoot != responseBoot) {
            readNotices()
            return
        }
        val tip = value.noticeSequence() ?: return
        updateNoticeBoot(responseBoot, tip)
        noticeTip = maxOf(noticeTip, tip)
        if (foreground && state.selectedChatId != null) markChatRead(state.selectedChatId)
        // 回放有固定保留窗口。用服务端确认的游标跨过已过期记录，随后合并先收到的实时事件。
        val replay = value.array("notices").filterIsInstance<JsonObject>()
            .mapNotNull { row -> row.noticeSequence()?.let { it to row } } + queuedNotices.entries.map { it.key to it.value }
        val restoreRecent = state.recent.isEmpty()
        replay.asSequence()
            .filter { (seq, _) -> seq <= tip && (seq > noticeSeq || restoreRecent) }
            .distinctBy { it.first }.sortedBy { it.first }
            .forEach { (seq, row) -> recordNotice(seq, row, countUnread = seq > noticeSeq) }
        noticeSeq = maxOf(noticeSeq, tip)
        queuedNotices.keys.removeAll { it <= noticeSeq }
        drainNotices()
    }

    private fun drainNotices() {
        if (boot == null) return
        while (noticeSeq < Long.MAX_VALUE) {
            val next = noticeSeq + 1
            val row = queuedNotices.remove(next) ?: break
            recordNotice(next, row)
            noticeSeq = next
        }
        readThrough.values.removeAll { it <= noticeSeq }
    }

    private fun recordNotice(seq: Long, value: JsonObject, countUnread: Boolean = true) {
        val kind = value.text("kind") ?: return
        val chatId = value.text("roomId")?.takeIf { it.isNotBlank() } ?: value.text("botId")?.takeIf { it.isNotBlank() }
        if (kind in setOf("done", "question", "approval", "group", "message")) {
            val activity = BotActivity("${boot.orEmpty()}:$seq", value.text("text").orEmpty(),
                chatId, kind, value.text("name").orEmpty())
            state = state.copy(recent = (listOf(activity) + state.recent).distinctBy { it.id }.take(30))
        }
        if (countUnread && chatId != null && (kind == "group" || kind == "message") &&
            !(foreground && state.selectedChatId == chatId) && seq > (readThrough[chatId] ?: 0)) {
            val count = state.unreadCount(chatId)
            if (count < Int.MAX_VALUE) state = state.copy(unreadCounts = state.unreadCounts + (chatId to count + 1))
        }
    }

    private fun JsonObject.noticeSequence(): Long? =
        (get("seq") as? JsonPrimitive)?.longOrNull?.takeIf { it in 0 until Long.MAX_VALUE }

    private fun liveStatus(work: JsonObject): Map<String, BotLiveWork> {
        val bots = work["bots"] as? JsonObject
        val rooms = work["rooms"] as? JsonObject
        val turns = work["turns"] as? JsonObject
        val steered = work["steered"] as? JsonObject
        return buildMap {
            for (chat in state.chats) {
                val entry: JsonObject?
                val started: Long?
                val author: String?
                if (chat.room) {
                    entry = rooms?.array(chat.id).orEmpty().filterIsInstance<JsonObject>()
                        .maxWithOrNull(compareBy({ !it.text("tool").isNullOrBlank() }, { it.epoch("since") ?: 0 }))
                    val turn = turns?.epoch(chat.sessionId ?: chat.id)
                    if (entry == null && turn == null) continue
                    started = entry?.epoch("since") ?: turn
                    author = entry?.text("botId")?.takeIf { it in chat.memberIds }
                } else {
                    val session = chat.sessionId ?: chat.id
                    entry = (bots?.get(chat.id) as? JsonObject)?.takeIf { it.text("roomId") == null }
                    val turn = turns?.epoch(session)
                    if (entry == null && turn == null) continue
                    started = listOfNotNull(entry?.epoch("since"), turn, steered?.epoch(session)).maxOrNull()
                    author = chat.id
                }
                val tool = entry?.text("tool")?.trim()?.lowercase()?.replace("-", "_").orEmpty()
                val kind = when {
                    entry != null && entry["switching"] == JsonPrimitive(true) && entry["folder"] == JsonPrimitive(true) -> "switching-folder"
                    tool.isEmpty() -> "thinking"
                    "web_search" in tool || "search_web" in tool || "websearch" in tool || "searchweb" in tool -> "web-search"
                    "web" in tool || "browse" in tool || "fetch" in tool -> "web-read"
                    "image" in tool || "photo" in tool -> "image"
                    else -> "working"
                }
                put(chat.id, BotLiveWork(kind, author, started))
            }
        }
    }

    private fun JsonObject.epoch(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..253402300799999L }

    private fun peer(value: JsonElement, room: Boolean, running: Map<String, JsonElement>): BotChat? {
        val obj = value as? JsonObject ?: return null
        val id = obj.text("id") ?: return null
        val previous = state.chats.firstOrNull { it.id == id }
        val work = running[id]
        val avatar = obj["avatar"] as? JsonObject
        val ref = avatar?.text("imageRef")
        val inline = avatar?.text("image")?.takeIf { it.startsWith("data:image/") && it.length < 256_000 }
        return BotChat(id, obj.text("name").orEmpty(), room, obj.text("role").orEmpty(), obj.text("preview").orEmpty(),
            inline ?: previous?.image?.takeIf { ref != null },
            obj["lastAt"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            work != null && work != JsonNull && (work !is JsonArray || work.isNotEmpty()),
            obj.array(if (room) "ownParts" else "parts").size,
            if (inline != null) ref else previous?.imageRef?.takeIf { ref != null },
            obj.array("members").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            obj.text("sessionId"),
            if (room) null else BotRoleProfile(obj.text("instructions").orEmpty(), obj.text("model"),
                obj.text("effort"), obj.text("workDir"), obj.text("permission"),
                obj["private"]?.jsonPrimitive?.booleanOrNull == true, obj.text("appliedModel"), obj.text("appliedEffort")),
            hidden = obj["hidden"]?.jsonPrimitive?.booleanOrNull == true)
    }

    private fun loadAvatars() {
        if (!state.connected || !state.supported) return
        val active = pending.values.count { it.endpoint == "avatar" }
        if (active >= 4) return
        val needed = state.chats.filter { chat ->
            val ref = wantedAvatars[chat.id]
            ref != null && (chat.image == null || chat.imageRef != ref) && attemptedAvatars[chat.id] != ref
        }
        for (chat in needed.take(4 - active)) {
            if (call("avatar", buildJsonObject { put("botId", chat.id) }, chat.id) != null) {
                attemptedAvatars[chat.id] = wantedAvatars.getValue(chat.id)
            }
        }
    }

    private fun messageText(item: JsonObject): String = item.text("text") ?: when (item.text("kind")) {
        "event" -> item.text("event").orEmpty()
        "answer" -> item.text("answer").orEmpty()
        "view" -> item.text("title").orEmpty()
        "closed" -> ""
        else -> ""
    }

    private fun questionAnswers(item: JsonObject): List<BotQuestionAnswer> {
        val answers = item.array("answers").filterIsInstance<JsonObject>()
        return item.array("questions").filterIsInstance<JsonObject>().take(20).mapNotNull { question ->
            val prompt = (question.text("question") ?: question.text("header"))?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val id = question.text("id") ?: return@mapNotNull null
            val answer = answers.firstOrNull { it.text("id") == id } ?: return@mapNotNull null
            val options = question.array("options").take(26).map { value ->
                when (value) {
                    is JsonObject -> value.text("label")
                    is JsonPrimitive -> value.contentOrNull
                    else -> null
                }
            }
            fun letter(index: Int) = if (index in 0..25) ('A' + index).toString() else null
            val selected = answer.array("selected").filterIsInstance<JsonPrimitive>()
                .mapNotNull { it.contentOrNull?.takeIf(String::isNotBlank) }.distinct().take(26)
            val choices = selected.map { label -> BotAnswerChoice(label, letter(options.indexOf(label))) }
            val custom = answer.text("custom")?.trim()?.takeIf { it.isNotEmpty() }
            val picked = if (custom == null) choices else choices + BotAnswerChoice(custom, letter(options.size))
            picked.takeIf { it.isNotEmpty() }?.let { BotQuestionAnswer(prompt, it) }
        }
    }

    private fun presentedFiles(item: JsonObject): List<PresentedFile> = item.array("files")
        .filterIsInstance<JsonObject>().take(20).mapNotNull { file ->
            val path = file.text("path")?.trim()?.takeIf {
                it.isNotEmpty() && it.length <= 4096 && !it.endsWith('/') && it.none { c -> c.isISOControl() }
            } ?: return@mapNotNull null
            PresentedFile(path = path, description = file.text("description")?.take(4096))
        }.distinctBy(PresentedFile::path)

    private fun removeDeletedBot(id: String) {
        histories.remove(id)
        cursors.remove(id)
        dirty.remove(id)
        optimistic.remove(id)
        sessionChats.values.removeAll { it == id }
        wantedAvatars.remove(id)
        attemptedAvatars.remove(id)
        readThrough.remove(id)
        val wasSelected = state.selectedChatId == id
        if (wasSelected) {
            streams.clear()
            referenceRequest = null
        }
        state = state.copy(
            chats = state.chats.filterNot { it.id == id }.map { chat ->
                if (id in chat.memberIds) chat.copy(memberIds = chat.memberIds - id) else chat
            },
            rosterIds = state.rosterIds - id,
            unreadCounts = state.unreadCounts - id,
            roleDetails = state.roleDetails?.takeUnless { it.botId == id },
            liveWork = state.liveWork.filter { (chatId, work) -> chatId != id && work.botId != id },
            roleSaving = false,
            roleError = null,
            selectedChatId = state.selectedChatId?.takeUnless { it == id },
            historyLoading = state.historyLoading && !wasSelected,
            referenceSearch = state.referenceSearch?.takeUnless { it.chatId == id },
            interactions = state.interactions.filterNot { it.chatId == id },
            schedules = state.schedules.filterNot { it.botId == id }
        )
        // 删除前已发出的读取响应可能仍含该 Bot；让它们过期，再读取完整的团队状态。
        pending.entries.removeAll { (_, request) ->
            request.chatId == id || request.endpoint == "state" || request.endpoint == "schedules"
        }
        stateDirty = false
        revision = -1
        readState()
        call("schedules")
    }

    private fun failed(id: String, code: String, detail: String? = null) {
        val request = pending.remove(id) ?: return
        if (request.endpoint in setOf("create-room", "set-flags") || id == conversationSend) {
            val uncertain = code in setOf("bot-timeout", "request-timeout", "timeout", "disconnected", "connection-lost", "not-connected", "bot-invalid-response")
            if (id == conversationSend) {
                conversationSend = null
                request.chatId?.let { chat -> optimistic[chat] = optimistic[chat].orEmpty().map {
                    if (it.id == id) it.copy(delivery = "unknown") else it
                }.filterNot { !uncertain && it.id == id } }
            }
            state = state.copy(conversation = state.conversation.copy(saving = false, error = if (uncertain) "uncertain" else code,
                errorDetail = detail?.take(1000), uncertain = uncertain && request.endpoint != "set-flags"))
            revision = -1
            if (state.connected) readState()
            return
        }
        if (id == subscription) subscription = null
        if (request.endpoint == "avatar") { loadAvatars(); return }
        if (request.endpoint == "reference-candidates") { finishReferences(id, null); return }
        if (request.endpoint == "delete-bot") {
            val error = when (code) {
                "bot/main-protected" -> code
                "bot-timeout", "request-timeout", "timeout", "disconnected", "connection-lost" -> "bot-delete-unconfirmed"
                else -> "bot-delete-failed"
            }
            state = state.copy(roleSaving = false, roleError = error)
            revision = -1
            readState()
            return
        }
        if (request.endpoint in setOf("update-bot", "duplicate-bot", "set-main", "memory-add", "memory-edit",
                "memory-forget", "schedule-create", "schedule-delete")) {
            state = state.copy(roleSaving = false, roleError = code)
            return
        }
        when (request.endpoint) {
            "creation-appearance" -> { creation { it.copy(loadingAppearance = false, appearanceFailed = true) }; return }
            "local-agents" -> { creation { it.copy(loadingAgents = false, agentsFailed = true) }; return }
            "local-agent-models" -> {
                if (request.chatId == state.creation.agentRoute) creation { it.copy(loadingAgentModels = false) }
                return
            }
            "avatar-library", "avatar-import" -> { creation { it.copy(loadingPhotos = false, photosFailed = true) }; return }
            "create-bot" -> {
                val error = if (code in setOf("bot-timeout", "request-timeout", "timeout", "disconnected", "connection-lost")) "uncertain" else code
                creation { it.copy(saving = false, error = error) }
                revision = -1
                if (pending.values.any { it.endpoint == "state" }) stateDirty = true else readState()
                return
            }
        }
        if (request.endpoint == "efforts") {
            if (state.effortModel == request.chatId) state = state.copy(effortModel = null)
            return
        }
        if (request.endpoint in setOf("memory-view", "usage-summary")) return
        if (request.endpoint in setOf("send", "send-attachments")) {
            val chat = request.chatId ?: return
            optimistic[chat] = optimistic[chat].orEmpty().map { if (it.id == id) it.copy(delivery = "unknown") else it }
        }
        state = state.copy(error = code, loading = false, historyLoading = false)
    }

    private fun markUncertain() {
        optimistic.keys.toList().forEach { chat -> optimistic[chat] = optimistic[chat].orEmpty().map {
            if (it.delivery == "sending") it.copy(delivery = "unknown") else it
        } }
    }

    private fun call(endpoint: String, payload: JsonObject = JsonObject(emptyMap()), chat: String? = null,
        older: Boolean = false, part: Int = 0): String? {
        if (pending.size >= 32 || (endpoint !in setOf("send", "send-attachments") && pending.values.any {
            it.endpoint == endpoint && it.chatId == chat
        })) return null
        val id = nextId()
        pending[id] = Pending(endpoint, chat, older, part)
        outgoing += GatewayRequests.botCall(endpoint, payload.toString(), id)
        return id
    }

    private fun request(type: String, id: String, body: JsonObject) = GatewayRequest(type, id,
        correlationId = id, lanePolicy = GatewayRequestLanePolicy.REJECT_IF_BUSY, payload = body.toString())

    private fun nextId(): String = "bot-${++ordinal}"

    private fun displayHistory(chatId: String): List<BotChatMessage> {
        val history = histories[chatId].orEmpty()
        // 群聊同一轮可能有多个发言者，保留网关原有的文件和发言归属。
        if (state.chats.firstOrNull { it.id == chatId }?.room != false) return history
        if (history !== projectedHistorySource) {
            projectedHistorySource = history
            projectedHistory = placeTurnFiles(history)
        }
        return projectedHistory
    }

    private fun placeTurnFiles(history: List<BotChatMessage>): List<BotChatMessage> {
        fun turnKey(message: BotChatMessage): Pair<String, Long>? = message.turn
            ?.takeIf { message.sessionId.isNotEmpty() }?.let { message.sessionId to it }

        val filesByTurn = mutableMapOf<Pair<String, Long>, LinkedHashMap<String, PresentedFile>>()
        for (message in history) {
            if (message.files.isEmpty()) continue
            val key = turnKey(message) ?: continue
            val files = filesByTurn.getOrPut(key) { linkedMapOf() }
            for (file in message.files) {
                if (file.description != null || file.path !in files) files[file.path] = file
            }
        }
        if (filesByTurn.isEmpty()) return history

        val lastReply = mutableMapOf<Pair<String, Long>, Int>()
        history.forEachIndexed { index, message ->
            val key = turnKey(message) ?: return@forEachIndexed
            if (key in filesByTurn && (message.kind == "bot" || message.kind == "post") &&
                (message.text.isNotBlank() || message.images.isNotEmpty())) lastReply[key] = index
        }
        return history.mapIndexedNotNull { index, message ->
            val key = turnKey(message) ?: return@mapIndexedNotNull message
            val target = lastReply[key] ?: return@mapIndexedNotNull message
            // 原始交付事件保留在历史缓存里，合并后翻页和刷新仍按原事件 ID 对齐。
            when {
                index == target -> message.copy(files = filesByTurn.getValue(key).values.toList())
                message.kind == "file" -> null
                message.files.isNotEmpty() -> message.copy(files = emptyList())
                else -> message
            }
        }
    }

    private fun result(): SharedBotTransition {
        val selected = state.selectedChatId
        state = state.copy(messages = if (selected == null) emptyList() else
            displayHistory(selected) + optimistic[selected].orEmpty() + streams.values.filter { it.text.isNotEmpty() },
            hasEarlier = selected != null && cursors[selected] != null,
            attachmentSending = pending.values.any { it.endpoint == "send-attachments" && it.chatId == selected })
        return SharedBotTransition(state, outgoing.toList()).also { outgoing.clear() }
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.array(key: String): List<JsonElement> = (get(key) as? JsonArray).orEmpty()
}

/** 群头像在手机与平板共用相同的归一化位置。 */
data class BotAvatarSeat(val x: Double, val y: Double, val side: Double)

object BotAvatarLayout {
    fun seats(memberCount: Int): List<BotAvatarSeat> = when (memberCount) {
        0, 1 -> emptyList()
        2 -> listOf(BotAvatarSeat(0.0, 0.0, 2.0 / 3), BotAvatarSeat(1.0 / 3, 1.0 / 3, 2.0 / 3))
        3 -> listOf(BotAvatarSeat(2.0 / 9, 0.0, 5.0 / 9), BotAvatarSeat(0.0, 4.0 / 9, 5.0 / 9),
            BotAvatarSeat(4.0 / 9, 4.0 / 9, 5.0 / 9))
        else -> listOf(BotAvatarSeat(0.0, 0.0, 0.5), BotAvatarSeat(0.5, 0.0, 0.5),
            BotAvatarSeat(0.0, 0.5, 0.5), BotAvatarSeat(0.5, 0.5, 0.5))
    }
}
