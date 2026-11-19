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
    val profile: BotRoleProfile? = null
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
    val files: List<PresentedFile> = emptyList()
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
    val writable: Boolean = true
)

data class SharedBotTransition(val snapshot: SharedBotSnapshot, val requests: List<GatewayRequest> = emptyList())

@Serializable
private data class BotCache(
    val version: Int = 1,
    val snapshot: SharedBotSnapshot,
    val histories: Map<String, List<BotChatMessage>>,
    val optimistic: Map<String, List<BotChatMessage>>
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
    private var stateDirty = false
    private var subscription: String? = null
    private val pending = linkedMapOf<String, Pending>()
    private val histories = linkedMapOf<String, List<BotChatMessage>>()
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

    fun exportCacheJson(): String {
        var pages = histories.mapValues { it.value.takeLast(100) }
        val cached = state.copy(connected = false, loading = false, historyLoading = false,
            feedActive = false, selectedChatId = null, messages = emptyList(), interactions = emptyList(), error = null,
            roleSaving = false, roleDetails = null, roleError = null, effortModel = null, efforts = emptyList(),
            roleSettingsSupported = false, attachmentsSupported = false, attachmentSending = false, lastAttachmentAck = null,
            referencesSupported = false, referenceSearch = null)
        val uncertain = optimistic.mapValues { (_, messages) -> messages.map { it.copy(delivery = "unknown") } }
        var json = wireJson.encodeToString(BotCache(snapshot = cached, histories = pages, optimistic = uncertain))
        while (json.length > 7_500_000 && pages.isNotEmpty()) {
            pages = pages - pages.keys.first()
            json = wireJson.encodeToString(BotCache(snapshot = cached, histories = pages, optimistic = uncertain))
        }
        return json.takeIf { it.length <= 7_500_000 } ?: ""
    }

    fun restoreCacheJson(json: String): SharedBotTransition {
        if (enabled || json.length > 7_500_000) return result()
        val cached = runCatching { wireJson.decodeFromString<BotCache>(json) }.getOrNull() ?: return result()
        if (cached.version != 1) return result()
        state = cached.snapshot.copy(connected = false, supported = false, loading = false,
            historyLoading = false, feedActive = false, selectedChatId = null, messages = emptyList(), interactions = emptyList())
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
            markUncertain()
            pending.clear()
            referenceRequest = null
            attemptedAvatars.clear()
            revision = -1
            subscription = null
            streams.clear()
            state = state.copy(loading = false, historyLoading = false, feedActive = false, roleSaving = false, attachmentsSupported = false,
                roleSettingsSupported = false, referencesSupported = false, referenceSearch = null)
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
        if (id != null && state.connected && state.supported) readChat(id)
        return result()
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
                    referencesSupported = "reference-candidates" in capabilities)
            }
            "bot-stream-reset" -> if (frame.text("subscriptionId") == subscription) {
                subscription = null
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
            "error" -> frame.text("requestId")?.let { failed(it, frame.text("code") ?: "bot-request-failed") }
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
        call("notices", buildJsonObject { boot?.let { put("boot", it) }; put("since", noticeSeq) })
        state.selectedChatId?.let { readChat(it) }
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
                if (obj["unchanged"]?.jsonPrimitive?.booleanOrNull != true) {
                    sessionChats.clear()
                    (obj.array("bots") + obj.array("rooms")).forEach { entry ->
                        val peer = entry as? JsonObject ?: return@forEach
                        val peerId = peer.text("id") ?: return@forEach
                        sessionChats[peer.text("sessionId") ?: peerId] = peerId
                    }
                    val work = obj["work"] as? JsonObject
                    val bots = (work?.get("bots") as? JsonObject).orEmpty()
                    val rooms = (work?.get("rooms") as? JsonObject).orEmpty()
                    val peers = obj.array("bots").mapNotNull { peer(it, false, bots) } +
                        obj.array("rooms").mapNotNull { peer(it, true, rooms) }
                    state = state.copy(mainBotId = obj.text("mainBotId"), chats = peers.sortedByDescending { it.lastAt },
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
                    wantedAvatars.clear()
                    for (entry in obj.array("bots")) {
                        val bot = entry as? JsonObject ?: continue
                        val id = bot.text("id") ?: continue
                        val ref = (bot["avatar"] as? JsonObject)?.text("imageRef") ?: continue
                        wantedAvatars[id] = ref
                    }
                    attemptedAvatars.keys.retainAll(wantedAvatars.keys)
                } else {
                    val work = obj["work"] as? JsonObject
                    state = state.copy(chats = state.chats.map { chat -> chat.copy(running =
                        ((work?.get(if (chat.room) "rooms" else "bots") as? JsonObject)?.get(chat.id))
                            ?.let { it != JsonNull && (it !is JsonArray || it.isNotEmpty()) } == true) })
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
                boot = obj.text("boot")
                noticeSeq = obj["seq"]?.jsonPrimitive?.longOrNull ?: noticeSeq
                obj.array("notices").forEach { (it as? JsonObject)?.let(::notice) }
            }
        }
    }

    private fun event(kind: String?, data: JsonObject) {
        when (kind) {
            "hello" -> {
                val nextBoot = data.text("boot")
                if (boot != null && boot != nextBoot) {
                    revision = -1
                    histories.clear(); cursors.clear(); streams.clear()
                }
                call("notices", buildJsonObject { boot?.let { put("boot", it) }; put("since", noticeSeq) })
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
            "notice" -> { notice(data); readState() }
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

    private fun notice(value: JsonObject) {
        val seq = value["seq"]?.jsonPrimitive?.longOrNull ?: return
        noticeSeq = maxOf(noticeSeq, seq)
        if (value.text("kind") in setOf("done", "question", "approval", "group", "message")) {
            val activity = BotActivity("${boot.orEmpty()}:$seq", value.text("text").orEmpty(),
                value.text("roomId") ?: value.text("botId"), value.text("kind").orEmpty(), value.text("name").orEmpty())
            state = state.copy(recent = (listOf(activity) + state.recent).distinctBy { it.id }.take(30))
        }
    }

    private fun peer(value: JsonElement, room: Boolean, running: Map<String, JsonElement>): BotChat? {
        val obj = value as? JsonObject ?: return null
        if (obj["hidden"]?.jsonPrimitive?.booleanOrNull == true) return null
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
                obj["private"]?.jsonPrimitive?.booleanOrNull == true, obj.text("appliedModel"), obj.text("appliedEffort")))
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

    private fun failed(id: String, code: String) {
        val request = pending.remove(id) ?: return
        if (id == subscription) subscription = null
        if (request.endpoint == "avatar") { loadAvatars(); return }
        if (request.endpoint == "reference-candidates") { finishReferences(id, null); return }
        if (request.endpoint in setOf("update-bot", "duplicate-bot", "set-main", "memory-add", "memory-edit",
                "memory-forget", "schedule-create", "schedule-delete")) {
            state = state.copy(roleSaving = false, roleError = code)
            return
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

    private fun result(): SharedBotTransition {
        val selected = state.selectedChatId
        state = state.copy(messages = if (selected == null) emptyList() else
            histories[selected].orEmpty() + optimistic[selected].orEmpty() + streams.values.filter { it.text.isNotEmpty() },
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
        0 -> emptyList()
        1, 2 -> listOf(BotAvatarSeat(0.0, 0.0, 2.0 / 3), BotAvatarSeat(1.0 / 3, 1.0 / 3, 2.0 / 3))
        3 -> listOf(BotAvatarSeat(2.0 / 9, 0.0, 5.0 / 9), BotAvatarSeat(0.0, 4.0 / 9, 5.0 / 9),
            BotAvatarSeat(4.0 / 9, 4.0 / 9, 5.0 / 9))
        else -> listOf(BotAvatarSeat(0.0, 0.0, 0.5), BotAvatarSeat(0.5, 0.0, 0.5),
            BotAvatarSeat(0.0, 0.5, 0.5), BotAvatarSeat(0.5, 0.5, 0.5))
    }
}
