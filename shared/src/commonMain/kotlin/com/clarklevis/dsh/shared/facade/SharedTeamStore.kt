package com.clarklevis.dsh.shared.facade

import com.clarklevis.dsh.shared.i18n.UiMessage
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class ConversationTarget(val sessionId: String, val parentSessionId: String? = null, val subagentMode: String? = null) {
    val canSend: Boolean get() = parentSessionId == null || subagentMode == "continuable"

    fun decorate(payload: String): String? {
        if (parentSessionId == null) return payload
        require(parentSessionId.isNotBlank() && parentSessionId != sessionId)
        val objectValue = wireJson.parseToJsonElement(payload).jsonObject
        val type = objectValue["type"]?.jsonPrimitive?.content
        if (type !in CHILD_REQUESTS) return null
        if (type in setOf("message", "session-cancel") && !canSend) return null
        return JsonObject(objectValue.toMutableMap().apply {
            put("parentSessionId", JsonPrimitive(parentSessionId))
            put("subagentMode", JsonPrimitive(subagentMode ?: "unknown"))
            remove("workspaceId")
            if (type == "message") { remove("cwd"); remove("agentPreset") }
        }).toString()
    }

    companion object {
        private val CHILD_REQUESTS = setOf("history", "subscribe", "message", "session-cancel", "tasks", "goal", "question-answer", "question-cancel", "approval-response")
    }
}

@Serializable
data class TeamMemberView(
    val id: String, val name: String, val role: String = "teammate", val phase: String = "active",
    val mode: String = "continuable", val parentSessionId: String? = null,
    val model: String? = null, val detail: String? = null, val running: Boolean? = null,
    val error: String? = null, val canOpen: Boolean = true, val avatarIndex: Int = 0,
    val identityName: String? = null
)

@Serializable
data class TeamAvatarAssignment(val leadSessionId: String, val sessionId: String, val index: Int)

@Serializable
data class TeamTaskView(
    val id: String, val revision: Int = 0, val subject: String, val description: String = "",
    val status: String, val ownerName: String? = null, val blockedBy: List<String> = emptyList(),
    val writeScopes: List<String> = emptyList(), val ready: Boolean = false,
    val writeScopeWarnings: List<String> = emptyList(), val ownerId: String? = null
) {
    val isUnassigned: Boolean get() = ownerId == null && ownerName == null

    fun owner(members: List<TeamMemberView>): TeamMemberView? = if (ownerId != null) {
        members.firstOrNull { it.id == ownerId }
    } else {
        members.singleOrNull { (it.identityName ?: it.name) == ownerName }
    }

    fun isOwnedBy(sessionId: String?, members: List<TeamMemberView>): Boolean =
        sessionId != null && (if (ownerId != null) ownerId == sessionId else owner(members)?.id == sessionId)

    fun ownerDisplayName(members: List<TeamMemberView>): String? = owner(members)?.name ?: ownerName ?: ownerId
}

@Serializable
data class TeamSnapshot(
    val leadSessionId: String = "", val members: List<TeamMemberView> = emptyList(),
    val tasks: List<TeamTaskView> = emptyList(), val state: String = "loading",
    val error: UiMessage? = null, val visible: Boolean = false, val isTeam: Boolean = false
)

@Serializable
data class TeamMemberProfileRequest(val sessionId: String, val requestId: String, val payload: String)

/** 每个网关实例独占。共享任务与成员内部 todos 完全分离；每个投影单独维护水位。 */
class SharedTeamStore {
    private data class Projection(val value: JsonElement?, val seq: Long?, val state: String)
    private val projections = mutableMapOf<Pair<String, String>, Projection>()
    private val lastValues = mutableMapOf<Pair<String, String>, JsonElement>()
    private val running = mutableMapOf<String, Boolean>()
    private val statusDuringQuery = mutableMapOf<String, Boolean>()
    private var sessionsQueryPending = false
    private val queryIds = mutableMapOf<Pair<String, String>, String>()
    private val confirmedProjections = mutableSetOf<Pair<String, String>>()
    private val profileAttempts = mutableSetOf<String>()
    private val profileRequests = mutableMapOf<String, ConversationTarget>()
    private val errors = mutableMapOf<String, UiMessage>()
    private var capabilities = emptySet<String>()
    private var controlKeys = emptySet<Pair<String, String>>()
    private var nextRequest = 0L
    private val targets = mutableMapOf<String, ConversationTarget>()
    private val avatars = mutableMapOf<Pair<String, String>, TeamAvatarAssignment>()

    fun avatarAssignmentsJson(): String = wireJson.encodeToString(
        avatars.values.sortedWith(compareBy({ it.leadSessionId }, { it.sessionId }))
    )

    fun restoreAvatarAssignmentsJson(json: String): Boolean = runCatching {
        require(json.length <= SharedOfflineCatalogCodec.MAXIMUM_BYTES)
        val restored = wireJson.decodeFromString<List<TeamAvatarAssignment>>(json)
        require(validTeamAvatarAssignments(restored))
        restored.forEach { avatar ->
            val key = avatar.leadSessionId to avatar.sessionId
            if (key !in avatars && avatars.size < SharedOfflineCatalogCodec.MAXIMUM_SESSIONS) avatars[key] = avatar
        }
        true
    }.getOrDefault(false)

    private fun assignAvatars(leadSessionId: String, members: List<TeamMemberView>): List<TeamMemberView> {
        val occupied = avatars.values.filter { it.leadSessionId == leadSessionId }.map { it.index }.toMutableSet()
        members.sortedBy { it.id }.forEach { member ->
            val key = leadSessionId to member.id
            if (key !in avatars && avatars.size < SharedOfflineCatalogCodec.MAXIMUM_SESSIONS) {
                // 固定哈希跨平台一致；优先使用十二种不同轮廓，再扩展不同配色。
                var hash = 2166136261u
                member.id.encodeToByteArray().forEach { hash = (hash xor it.toUByte().toUInt()) * 16777619u }
                val first = (hash % 12u).toInt()
                val index = (0 until 144).map { ((first + it % 12) % 12) + (it / 12) * 12 }
                    .firstOrNull { it !in occupied } ?: (hash % 144u).toInt()
                avatars[key] = TeamAvatarAssignment(leadSessionId, member.id, index)
                occupied += index
            }
        }
        return members.map { it.copy(avatarIndex = avatars[leadSessionId to it.id]?.index ?: 0) }
    }

    /** 只持久化已由 Host 确认的父子地址，不保存投影水位或订阅状态。 */
    fun routingJson(): String = wireJson.encodeToString(targets.values.sortedBy { it.sessionId })

    fun restoreRoutingJson(json: String): Boolean = runCatching {
        require(json.length <= SharedOfflineCatalogCodec.MAXIMUM_BYTES)
        val restored = wireJson.decodeFromString<List<ConversationTarget>>(json)
        require(validConversationTargets(restored))
        // 异步磁盘恢复不能覆盖本次连接已经确认的地址。
        restored.forEach { if (it.sessionId !in targets) targets[it.sessionId] = it }
        true
    }.getOrDefault(false)

    fun snapshotJson(leadSessionId: String): String = wireJson.encodeToString(snapshot(leadSessionId))
    fun snapshot(leadSessionId: String): TeamSnapshot {
        val team = projections[leadSessionId to "agentTeam"]
        val value = (team?.value ?: lastValues[leadSessionId to "agentTeam"]) as? JsonObject
        val members = mutableListOf<TeamMemberView>()
        val visited = mutableSetOf<String>()
        fun appendChildren(parent: String) {
            if (!visited.add(parent)) return
            val parentTeam = (projections[parent to "agentTeam"]?.value ?: lastValues[parent to "agentTeam"]) as? JsonObject
            val catalog = catalog(parent)
            // 旧宿主名册只作兼容；目录名称优先，label 只用于描述和旧目录显示回退。
            (parentTeam?.get("members") as? JsonArray).orEmpty().forEach { raw ->
                val m = raw as? JsonObject ?: return@forEach
                val id = m.text("id")?.takeIf { it.isNotBlank() } ?: return@forEach
                if (members.any { it.id == id } || (parent != leadSessionId && id == parent)) return@forEach
                val role = m.text("role") ?: "teammate"
                val entry = catalog.firstOrNull { it.text("id") == id }
                val path = if (!entry?.text("name").isNullOrBlank()) identityPath(id)
                    else m.text("name") ?: if (entry != null) identityPath(id) else id
                members += member(id, entry?.text("name")?.takeIf { it.isNotBlank() }?.let { path } ?: m.text("name") ?: id,
                    role, m.text("phase") ?: "unknown", m.text("error"),
                    if (role == "lead") targets[id]?.parentSessionId else parent,
                    if (role == "lead") "continuable" else resolvedMode(id, entry?.text("mode") ?: "continuable"))
                    .copy(identityName = path)
            }
            catalog.forEach { m ->
                val id = m.text("id")?.takeIf { it.isNotBlank() && it != parent && it !in visited } ?: return@forEach
                if (members.none { it.id == id }) {
                    val path = identityPath(id)
                    val displayName = if (targets[parent]?.parentSessionId != null ||
                        !m.text("name").isNullOrBlank()) path else m.text("label") ?: id
                    members += member(id, displayName,
                        "subagent", "active", null, parent, resolvedMode(id, m.text("mode") ?: "unknown"))
                        .copy(identityName = path)
                }
                appendChildren(id)
            }
        }
        appendChildren(leadSessionId)
        if ((value?.get("members") as? JsonArray).isNullOrEmpty() &&
            catalog(leadSessionId).isNotEmpty() && members.isNotEmpty() && members.none { it.id == leadSessionId }) {
            members.add(0, member(leadSessionId, "lead", "lead", "active", null,
                targets[leadSessionId]?.parentSessionId, "continuable").copy(identityName = identityPath(leadSessionId)))
        }
        val tasks = value?.get("tasks")?.let { runCatching { wireJson.decodeFromJsonElement<List<TeamTaskView>>(it) }.getOrNull() }.orEmpty().filter { it.status != "deleted" }
        // 已启用的空团队和关闭后的历史任务都需要保留入口。
        val enabled = (value?.get("enabled") as? JsonPrimitive)?.booleanOrNull == true
        val hasTeamMembers = "agent-team-v1" in capabilities && members.any { it.id != leadSessionId && it.role == "teammate" }
        val hasSubagents = "subagent-conversations-v1" in capabilities && members.any { it.id != leadSessionId && it.role == "subagent" }
        return TeamSnapshot(leadSessionId, assignAvatars(leadSessionId, members), tasks,
            if ("agent-team-v1" !in capabilities) "unsupported" else team?.state ?: "loading",
            errors[leadSessionId] ?: value?.text("failure")?.let { UiMessage.raw(it) },
            ("agent-team-v1" in capabilities && (enabled || tasks.isNotEmpty() || hasTeamMembers)) || hasSubagents,
            enabled || members.any { it.role == "teammate" } || tasks.isNotEmpty())
    }

    private fun catalog(parent: String): List<JsonObject> =
        ((projections[parent to "subagentCatalog"]?.value ?: lastValues[parent to "subagentCatalog"]) as? JsonArray)
            .orEmpty().mapNotNull { it as? JsonObject }

    /** 根为 lead；第一层不加 lead/，未命名链路使用 ID，绝不使用描述拼身份。 */
    private fun identityPath(id: String): String {
        val segments = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var current = id
        while (visited.add(current)) {
            val parent = targets[current]?.parentSessionId ?: break
            segments += catalog(parent).firstOrNull { it.text("id") == current }
                ?.text("name")?.takeIf { it.isNotBlank() } ?: current
            current = parent
        }
        return if (segments.isEmpty()) "lead" else segments.asReversed().joinToString("/")
    }

    private fun member(id: String, name: String, role: String, phase: String, error: String?, parent: String?, mode: String): TeamMemberView {
        val values = (projections[id to "modelSelection"]?.value ?: lastValues[id to "modelSelection"]) as? JsonObject
        val model = (values?.get("next") as? JsonObject)?.text("model") ?: values?.text("model")
        val identity = (projections[id to "subagent"]?.value ?: lastValues[id to "subagent"]) as? JsonObject
        val catalog = parent?.let { (projections[it to "subagentCatalog"]?.value ?: lastValues[it to "subagentCatalog"]) as? JsonArray }
        val label = catalog.orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { it.text("id") == id }?.text("label")
        return TeamMemberView(id, name, role, phase, mode, parent, model, identity?.text("label") ?: label, running[id], error,
            phase == "active" && mode != "external" && (parent == null || "subagent-conversations-v1" in capabilities))
    }

    private fun resolvedMode(id: String, fallback: String): String = (projections[id to "subagent"]?.value as? JsonObject)?.text("mode") ?: fallback
    fun isChild(sessionId: String): Boolean = targets[sessionId]?.parentSessionId != null
    fun canSend(sessionId: String): Boolean {
        val target = targets[sessionId] ?: return true
        if (!target.canSend || "subagent-conversations-v1" !in capabilities) return false
        val parent = target.parentSessionId ?: return true
        val team = projections[parent to "agentTeam"]
        val member = (team?.value as? JsonObject)?.get("members")?.jsonArray.orEmpty()
            .mapNotNull { it as? JsonObject }.firstOrNull { it.text("id") == sessionId }
        return member == null || (member.text("phase") == "active" && team?.state == "ready")
    }
    fun targetJson(sessionId: String): String = wireJson.encodeToString(targets[sessionId] ?: ConversationTarget(sessionId))

    /** 文件属于根会话工作区；会话内容仍使用子智能体的直接父级地址。 */
    fun workspaceSessionId(sessionId: String): String {
        var current = sessionId
        val visited = mutableSetOf<String>()
        while (visited.add(current)) {
            current = targets[current]?.parentSessionId ?: return current
        }
        // 异常地址不可借用其他团队的工作区，交由原会话请求路径报告失败。
        return sessionId
    }
    fun decorateRequestJson(payload: String): String? = runCatching {
        val obj = wireJson.parseToJsonElement(payload).jsonObject
        val id = obj.text("sessionId") ?: return payload
        if (obj.text("type") in setOf("message", "session-cancel") && !canSend(id)) return null
        (targets[id] ?: ConversationTarget(id)).decorate(payload)
    }.getOrNull()

    /** 会话初始化按需补齐缓存；菜单和看板只读取快照，不负责请求。 */
    fun queryIfNeededJson(leadSessionId: String, kind: String): String? {
        val key = when (kind) {
            "agent-team" -> "agentTeam"
            "subagents" -> "subagentCatalog"
            else -> return null
        }
        val address = leadSessionId to key
        val projection = projections[address]
        if (address in confirmedProjections && projection?.state == "ready" && projection.value != null) return null
        if ((leadSessionId to kind) in queryIds) return null
        return queryJson(leadSessionId, kind)
    }

    fun queryJson(leadSessionId: String, kind: String): String? {
        if (kind == "agent-team" && "agent-team-v1" !in capabilities) return null
        if (kind == "subagents" && "subagent-conversations-v1" !in capabilities) return null
        if (kind !in setOf("agent-team", "subagents")) return null
        val requestId = "team-${++nextRequest}"
        queryIds[leadSessionId to kind] = requestId
        return buildJsonObject { put("type", kind); put("sessionId", leadSessionId); put("requestId", requestId) }.toString()
    }

    /** 会话发现成员后预取少量历史元数据；最多两条并发，不订阅、不切换视图。 */
    fun memberProfileRequestsJson(leadSessionId: String): String {
        val requests = mutableListOf<TeamMemberProfileRequest>()
        if ("subagent-conversations-v1" !in capabilities) return "[]"
        for (member in snapshot(leadSessionId).members) {
            if (profileRequests.size >= 2) break
            if (member.role == "lead" || !member.canOpen || member.id in profileAttempts) continue
            if ((member.id to "modelSelection") in confirmedProjections && member.detail != null) continue
            val target = targets[member.id] ?: continue
            if (target.parentSessionId == null || target.subagentMode == "external") continue
            val requestId = "team-profile-${++nextRequest}"
            val payload = buildJsonObject {
                put("type", "history"); put("sessionId", member.id); put("requestId", requestId)
                put("maxMessages", 1); put("maxBytes", 4096); put("view", "conversation")
            }.toString()
            val decorated = target.decorate(payload) ?: continue
            profileRequests[requestId] = target
            profileAttempts += member.id
            requests += TeamMemberProfileRequest(member.id, requestId, decorated)
        }
        return wireJson.encodeToString(requests)
    }

    fun finishMemberProfileRequest(requestId: String) { profileRequests.remove(requestId) }
    fun memberProfileRequestPending(requestId: String): Boolean = requestId in profileRequests

    private fun acceptMemberProfile(frame: JsonObject): Boolean {
        val requestId = frame.text("requestId") ?: return false
        val target = profileRequests[requestId] ?: return false
        if (frame.text("sessionId") != target.sessionId) return false
        frame.text("parentSessionId")?.let { if (it != target.parentSessionId) return false }
        profileRequests.remove(requestId)
        if (frame.text("kind") != "history" || targets[target.sessionId] != target) return false
        val projection = frame["projections"] as? JsonObject ?: return false
        (projection["values"] as? JsonObject).orEmpty().forEach { (key, value) ->
            if (key in setOf("modelSelection", "subagent", "subagentTiming")) {
                install(target.sessionId, key, value, projection["asOfSeq"]?.jsonPrimitive?.longOrNull, "ready")
            }
        }
        rebuildTargets()
        return true
    }

    fun sessionsRequested() { sessionsQueryPending = true; statusDuringQuery.clear() }

    /** 返回是否影响团队 UI；token 帧不会触发快照桥接。 */
    fun acceptFrameJson(json: String): Boolean = runCatching {
        val frame = wireJson.parseToJsonElement(json).jsonObject
        val kind = frame.text("kind") ?: return false
        val id = frame.text("sessionId")
        if (kind in setOf("history", "error") && frame.text("requestId")?.startsWith("team-profile-") == true) {
            return acceptMemberProfile(frame)
        }
        when (kind) {
            "hello" -> { capabilities = frame["capabilities"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet(); running.clear(); statusDuringQuery.clear(); sessionsQueryPending = false; queryIds.clear(); confirmedProjections.clear(); profileAttempts.clear(); profileRequests.clear(); true }
            "session-status" -> { if (id != null) frame["running"]?.jsonPrimitive?.booleanOrNull?.let { running[id] = it; if (sessionsQueryPending) statusDuringQuery[id] = it }; true }
            "sessions" -> {
                frame["items"]?.jsonArray.orEmpty().forEach { raw -> val item = raw.jsonObject; item.text("sessionId")?.let { sid -> item["running"]?.jsonPrimitive?.booleanOrNull?.let { running[sid] = it } } }
                running.putAll(statusDuringQuery); statusDuringQuery.clear(); sessionsQueryPending = false; true
            }
            "projection-baseline" -> {
                val entries = (frame["projections"] as? JsonObject).orEmpty()
                val keys = mutableSetOf<Pair<String, String>>()
                entries.forEach { (sid, raw) ->
                    val p = raw as? JsonObject ?: return@forEach
                    (p["values"] as? JsonObject).orEmpty().forEach { (key, v) ->
                        if (key in KEYS) { keys += sid to key; install(sid, key, v, p["asOfSeq"]?.jsonPrimitive?.longOrNull, "ready", true) }
                    }
                }
                (controlKeys - keys).forEach { projections[it] = Projection(null, null, "unavailable"); confirmedProjections.remove(it) }
                controlKeys = keys; rebuildTargets(); true
            }
            "agent-team", "subagents", "agent-team-updated", "subagents-updated", "subagent-identity-updated", "subagent-timing-updated" -> {
                if (id == null) return false
                val queryKind = kind.removeSuffix("-updated")
                if (kind in setOf("agent-team", "subagents") && frame.text("requestId") != queryIds[id to queryKind]) return false
                if (kind in setOf("agent-team", "subagents")) queryIds.remove(id to queryKind)
                val key = when(kind) { "agent-team", "agent-team-updated" -> "agentTeam"; "subagents", "subagents-updated" -> "subagentCatalog"; "subagent-identity-updated" -> "subagent"; else -> "subagentTiming" }
                install(id, key, frame[key], frame["asOfSeq"]?.jsonPrimitive?.longOrNull, frame.text("projectionState") ?: "ready")
                if (kind.endsWith("-updated")) controlKeys = controlKeys + (id to key)
                errors.remove(id); rebuildTargets(); true
            }
            "session-snapshot", "history" -> {
                if (id == null) return false
                val p = frame["projections"] as? JsonObject ?: return false
                (p["values"] as? JsonObject).orEmpty().forEach { (key, v) -> if (key in KEYS) install(id, key, v, p["asOfSeq"]?.jsonPrimitive?.longOrNull, "ready") }
                rebuildTargets(); true
            }
            "error" -> if (id != null && frame.text("requestType") in setOf("agent-team", "subagents")) {
                val requestKind = frame.text("requestType")!!
                if (frame.text("requestId") != queryIds[id to requestKind]) return false
                queryIds.remove(id to requestKind)
                errors[id] = frame.text("message")?.let { UiMessage.raw(it) } ?: UiMessage("team.loadFailed"); true
            } else false
            else -> false
        }
    }.getOrDefault(false)

    private fun install(id: String, key: String, raw: JsonElement?, seq: Long?, state: String, baseline: Boolean = false) {
        val old = projections[id to key]
        val value = raw?.takeUnless { it is JsonNull }
        if (!baseline && old?.seq != null && (seq == null || seq < old.seq)) return
        if (value != null) lastValues[id to key] = value
        projections[id to key] = Projection(value, seq, if (value == null && state == "ready") "unavailable" else state)
        if (value != null && state == "ready") confirmedProjections.add(id to key)
        else confirmedProjections.remove(id to key)
    }
    private fun rebuildTargets() {
        projections.forEach { (address, projection) ->
            if (address.second == "agentTeam") (projection.value as? JsonObject)?.get("members")?.jsonArray.orEmpty().forEach { raw ->
                val m = raw.jsonObject; val id = m.text("id") ?: return@forEach
                if (m.text("role") != "lead") targets[id] = ConversationTarget(id, address.first, "continuable")
            }
            if (address.second == "subagentCatalog") (projection.value as? JsonArray).orEmpty().forEach { raw ->
                val m = raw.jsonObject; val id = m.text("id") ?: return@forEach
                if (!targets.containsKey(id) || targets[id]?.subagentMode != "continuable") targets[id] = ConversationTarget(id, address.first, resolvedMode(id, m.text("mode") ?: "unknown"))
            }
        }
        targets.toMap().forEach { (id, t) -> targets[id] = t.copy(subagentMode = resolvedMode(id, t.subagentMode ?: "unknown")) }
    }
    companion object { private val KEYS = setOf("agentTeam", "subagentCatalog", "subagent", "subagentTiming", "modelSelection") }
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
