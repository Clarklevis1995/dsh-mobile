package whaleness

import com.clarklevis.dsh.shared.domain.SessionListState
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.facade.CachedHistoryWindow
import com.clarklevis.dsh.shared.facade.SharedOfflineCatalogCodec
import com.clarklevis.dsh.shared.facade.SharedSessionListSnapshot
import com.clarklevis.dsh.shared.facade.SharedSessionSummarySnapshot
import com.clarklevis.dsh.shared.projection.ConversationProjector
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class HarmonyCacheDocument(
    val id: String,
    val text: String,
)

@Serializable
data class HarmonyCacheWindow(
    val sessionId: String,
    val json: String,
    val documents: List<HarmonyCacheDocument>,
)

@Serializable
data class HarmonyCacheExport(
    val catalog: String? = null,
    val windows: List<HarmonyCacheWindow> = emptyList(),
)

internal data class RestoredCatalog(
    val sessions: SessionListState,
    val workspaces: List<GatewayWorkspace>,
    val targets: String?,
    val avatars: String?,
)

/** 磁盘实现由 ArkData 提供；目录与稳定历史格式继续使用共享 Codec。 */
internal object CacheSupport {
    fun catalog(
        state: SessionListState,
        workspaces: List<GatewayWorkspace>,
        selectedWorkspace: String?,
        targets: String,
        avatars: String,
    ): String? {
        val codec = SharedOfflineCatalogCodec()
        val sessions = state.sessions.sortedByDescending { it.lastActivityEpochSeconds }.take(SharedOfflineCatalogCodec.MAXIMUM_SESSIONS)
        val selected = state.selectedSessionId?.takeIf { id -> sessions.any { it.id == id } }
        val snapshot =
            SharedSessionListSnapshot(
                sessions.map {
                    SharedSessionSummarySnapshot(
                        it.id,
                        it.title,
                        it.lastActivityEpochSeconds,
                        it.isRunning,
                        it.hasUnread,
                        it.agentPreset,
                        it.hasConversation,
                        it.parentSessionId,
                        it.origin,
                    )
                },
                state.archivedSessionIds.take(SharedOfflineCatalogCodec.MAXIMUM_SESSIONS),
                selected,
            )
        val basic = codec.create(wireJson.encodeToString(snapshot), wireJson.encodeToString(workspaces), selectedWorkspace) ?: return null
        val routed = codec.withConversationTargets(basic, targets) ?: basic
        return codec.withTeamAvatars(routed, avatars) ?: routed
    }

    fun restore(json: String): RestoredCatalog {
        val codec = SharedOfflineCatalogCodec()
        val normalized = requireNotNull(codec.normalize(json)) { "离线目录格式不可用，请连接网关重新同步" }
        val list = wireJson.decodeFromString<SharedSessionListSnapshot>(requireNotNull(codec.sessionListJson(normalized)))
        val workspaces = wireJson.decodeFromString<List<GatewayWorkspace>>(requireNotNull(codec.workspacesJson(normalized)))
        val directories = mutableMapOf<String, String>()
        workspaces.forEach { workspace -> workspace.sessionIds.forEach { id -> directories.getOrPut(id) { workspace.path } } }
        val rows =
            list.sessions.map { row ->
                SessionSummary(
                    row.id,
                    row.title,
                    row.lastActivityEpochSeconds,
                    false,
                    row.hasUnread,
                    row.agentPreset,
                    row.hasConversation,
                    row.parentSessionId,
                    row.origin,
                    directories[row.id],
                )
            }
        return RestoredCatalog(
            SessionListState(
                rows,
                list.archivedSessionIds.toSet(),
                list.selectedSessionId?.takeIf { id ->
                    rows.any {
                        it.id ==
                            id
                    }
                },
            ),
            workspaces,
            codec.conversationTargetsJson(normalized),
            codec.teamAvatarsJson(normalized),
        )
    }

    fun window(json: String): HarmonyCacheWindow {
        val window = wireJson.decodeFromString<CachedHistoryWindow>(json)
        window.validate(window.sessionId)
        val projector = ConversationProjector().apply { rebuild(window.events) }
        val documents =
            projector.items.mapNotNull { item ->
                val text =
                    listOf(
                        item.text,
                        item.files.joinToString("\n") {
                            it.name ?: it.path
                        },
                        item.images.mapNotNull { it.name }.joinToString("\n"),
                    ).filter(String::isNotBlank).joinToString("\n")
                if (text.isBlank()) null else HarmonyCacheDocument(item.id, text)
            }
        return HarmonyCacheWindow(window.sessionId, json, documents)
    }
}
