package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.ConversationTarget
import com.clarklevis.dsh.shared.facade.SharedTeamStore
import com.clarklevis.dsh.shared.facade.TeamMemberProfileRequest
import com.clarklevis.dsh.shared.facade.TeamTaskView
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.json.*
import kotlin.test.*

class SharedTeamStoreTest {
    @Test fun avatarsStayDistinctAndStableAcrossMembershipChangesAndRestart() {
        fun members(ids: List<String>) = """{"members":[${ids.joinToString(",") {
            """{"id":"$it","name":"$it","role":"teammate","phase":"active"}"""
        }}],"tasks":[]}"""
        val ids = (1..25).map { "member-$it" }
        val s = store()
        update(s, members(ids.take(8)))
        val first = s.snapshot("lead").members.associate { it.id to it.avatarIndex }
        assertEquals(8, first.values.toSet().size)
        update(s, members(ids.reversed()), 121)
        val all = s.snapshot("lead").members.associate { it.id to it.avatarIndex }
        assertEquals(25, all.values.toSet().size)
        first.forEach { (id, index) -> assertEquals(index, all[id]) }
        update(s, members(ids.take(3)), 122)
        s.snapshot("lead")
        update(s, members(ids), 123)
        assertEquals(all, s.snapshot("lead").members.associate { it.id to it.avatarIndex })
        val restarted = store()
        assertTrue(restarted.restoreAvatarAssignmentsJson(s.avatarAssignmentsJson()))
        update(restarted, members(ids.reversed()))
        assertEquals(all, restarted.snapshot("lead").members.associate { it.id to it.avatarIndex })
        assertFalse(store().snapshot("lead").visible, "头像缓存不能开启团队入口")
    }

    @Test fun avatarAssignmentIsOrderIndependentAndRejectsMalformedCache() {
        val s = store(); update(s)
        val first = s.snapshot("lead").members.associate { it.id to it.avatarIndex }
        val reversed = store()
        val value = wireJson.parseToJsonElement(team).jsonObject
        val reordered = JsonObject(value + ("members" to JsonArray(value.getValue("members").jsonArray.reversed())))
        update(reversed, reordered.toString())
        assertEquals(first, reversed.snapshot("lead").members.associate { it.id to it.avatarIndex })
        assertFalse(s.restoreAvatarAssignmentsJson("""[{"leadSessionId":"lead","sessionId":"a","index":144}]"""))
        assertTrue(s.restoreAvatarAssignmentsJson("""[{"leadSessionId":"lead","sessionId":"a","index":143}]"""))
        assertEquals(first, s.snapshot("lead").members.associate { it.id to it.avatarIndex }, "迟到缓存不能换掉已显示的头像")
    }

    @Test fun ordinaryReviewSubagentsAndMarketingTeamKeepDifferentBoards() {
        val s = store()
        val tasks = (1..3).joinToString(",") { i ->
            """{"id":"task-$i","revision":3,"subject":"宣传片任务 $i","description":"完整说明","status":"completed","ownerName":"scout","blockedBy":[],"writeScopes":[],"ready":false,"writeScopeWarnings":[]}"""
        }
        s.acceptFrameJson("""{"kind":"agent-team-updated","sessionId":"marketing","asOfSeq":2560,"agentTeam":{"members":[{"id":"marketing","name":"lead","role":"lead","phase":"active"},{"id":"scout","name":"scout","role":"teammate","phase":"active"}],"tasks":[$tasks]}}""")
        s.acceptFrameJson("""{"kind":"agent-team-updated","sessionId":"remotion","asOfSeq":9000,"agentTeam":{"members":[{"id":"remotion","name":"lead","role":"lead","phase":"active"}],"tasks":[]}}""")
        s.acceptFrameJson("""{"kind":"subagents-updated","sessionId":"remotion","asOfSeq":9000,"subagentCatalog":[{"id":"qa-1","mode":"continuable","label":"独立终检宣传片"},{"id":"qa-2","mode":"continuable","label":"复检 v2 修复项"},{"id":"qa-3","mode":"continuable","label":"独立终检 v3 whaleness 版"}]}""")
        val ordinary = s.snapshot("remotion")
        assertFalse(ordinary.isTeam)
        assertEquals(4, ordinary.members.size)
        assertTrue(ordinary.tasks.isEmpty())
        assertTrue(s.snapshot("marketing").isTeam)
        assertEquals(3, s.snapshot("marketing").tasks.size)
        assertTrue(s.snapshot("marketing").tasks.all { it.status == "completed" })
    }

    @Test fun rc3EmptyTeamAndHistoricalTasksRemainVisible() {
        val s = store()
        update(s, """{"enabled":true,"tasks":[]}""")
        assertTrue(s.snapshot("lead").visible)
        assertTrue(s.snapshot("lead").isTeam)
        update(s, """{"enabled":false,"tasks":[{"id":"t","subject":"历史","status":"completed","ownerId":"gone"}],"failure":"诊断"}""", 121)
        val snapshot = s.snapshot("lead")
        assertTrue(snapshot.visible)
        assertTrue(snapshot.isTeam)
        assertEquals("gone", snapshot.tasks.single().ownerId)
        assertNotNull(snapshot.error)
        update(s, "null", 122)
        assertEquals("unavailable", s.snapshot("lead").state)
        assertEquals(snapshot.tasks, s.snapshot("lead").tasks)
        update(s, """{"enabled":false,"tasks":[]}""", 123)
        assertFalse(s.snapshot("lead").visible)
    }

    @Test fun rc3OwnerIdentityUsesIdsAndFullPathsWithoutDescriptionMatching() {
        val s = store()
        fun catalog(parent: String, value: String) {
            assertTrue(s.acceptFrameJson("""{"kind":"subagents-updated","sessionId":"$parent","subagentCatalog":$value}"""))
        }
        catalog("lead", """[{"id":"r","name":"reviewer","label":"检查分镜","mode":"continuable"},{"id":"w","name":"writer","mode":"continuable"},{"id":"old","label":"旧描述","mode":"continuable"}]""")
        catalog("r", """[{"id":"c1","name":"checker","mode":"continuable"},{"id":"c4","label":"未命名的检查代理","mode":"continuable"}]""")
        catalog("w", """[{"id":"c2","name":"checker","mode":"continuable"}]""")
        catalog("old", """[{"id":"c3","name":"checker","mode":"continuable"}]""")
        val members = s.snapshot("lead").members
        assertEquals("reviewer", members.first { it.id == "r" }.name)
        assertEquals("检查分镜", members.first { it.id == "r" }.detail)
        assertEquals("reviewer/checker", members.first { it.id == "c1" }.name)
        assertEquals("old/checker", members.first { it.id == "c3" }.identityName)
        assertEquals("reviewer/c4", members.first { it.id == "c4" }.name)
        fun task(id: String? = null, name: String? = null) = TeamTaskView("t", subject = "任务", status = "pending", ownerId = id, ownerName = name)
        assertEquals("r", task(name = "reviewer").owner(members)?.id)
        assertEquals("c1", task(name = "reviewer/checker").owner(members)?.id)
        assertNull(task(name = "checker").owner(members))
        assertNull(task(name = "检查分镜").owner(members))
        assertNull(task(name = "旧描述").owner(members))
        assertEquals("old", task(name = "old").owner(members)?.id)
        assertEquals("c2", task(id = "c2", name = "reviewer/checker").owner(members)?.id)
        assertTrue(task(id = "c2").isOwnedBy("c2", members))
        assertFalse(task(id = "missing", name = "reviewer").isOwnedBy("r", members))
        assertTrue(task(name = "reviewer/checker").isOwnedBy("c1", members))
        assertFalse(task().isOwnedBy("r", members))
        assertFalse(task(id = "c2").isUnassigned)
        assertEquals("writer/checker", task(id = "c2").ownerDisplayName(members))
        assertNull(task(id = "missing", name = "reviewer").owner(members))
        assertEquals("reviewer", task(id = "missing", name = "reviewer").ownerDisplayName(members))
        assertEquals("missing", task(id = "missing").ownerDisplayName(members))
        assertTrue(task().isUnassigned)
        assertEquals("r", wireJson.decodeFromString<ConversationTarget>(s.targetJson("c1")).parentSessionId)
    }

    private fun store() = SharedTeamStore().apply {
        acceptFrameJson("""{"kind":"hello","capabilities":["agent-team-v1","subagent-conversations-v1"]}""")
    }
    private val team = """{"members":[{"id":"lead","name":"lead","role":"lead","phase":"active"},{"id":"a","name":"scout","role":"teammate","phase":"active"}],"tasks":[{"id":"task-1","revision":3,"subject":"素材勘察","description":"完整说明","status":"completed","ownerName":"scout","blockedBy":[],"writeScopes":["promo/analysis"],"ready":false,"writeScopeWarnings":[]}]}"""
    private fun update(s: SharedTeamStore, value: String = team, seq: Int = 120) {
        assertTrue(s.acceptFrameJson("""{"kind":"agent-team-updated","sessionId":"lead","asOfSeq":$seq,"agentTeam":$value}"""))
    }

    @Test fun teamMemberUsesCatalogDescriptionBeforeAnyMemberHistoryIsOpened() {
        val s = store(); update(s)
        s.acceptFrameJson("""{"kind":"subagents-updated","sessionId":"lead","asOfSeq":121,"subagentCatalog":[{"id":"a","mode":"continuable","label":"原创合成配乐与音效"}]}""")
        val snapshot = s.snapshot("lead")
        assertEquals("原创合成配乐与音效", snapshot.members.last().detail)
        assertEquals("a", snapshot.tasks.single().owner(snapshot.members)?.id,
            "旧名册的身份名称不能被只有 label 的旧目录覆盖")
    }

    @Test fun memberProfilesAreBoundedAddressedAndCachedBeforeSelection() {
        val s = store()
        val members = listOf("a", "b", "c").joinToString(",") { """{"id":"$it","name":"$it","role":"teammate","phase":"active"}""" }
        update(s, """{"members":[{"id":"lead","name":"lead","role":"lead","phase":"active"},$members],"tasks":[]}""")
        fun requests() = wireJson.decodeFromString<List<TeamMemberProfileRequest>>(s.memberProfileRequestsJson("lead"))
        val initial = requests()
        assertEquals(listOf("a", "b"), initial.map { it.sessionId })
        assertTrue(requests().isEmpty())
        val payload = wireJson.parseToJsonElement(initial.first().payload).jsonObject
        assertEquals("history", payload["type"]?.jsonPrimitive?.content)
        assertEquals("lead", payload["parentSessionId"]?.jsonPrimitive?.content)
        assertEquals(1, payload["maxMessages"]?.jsonPrimitive?.int)
        assertEquals(4096, payload["maxBytes"]?.jsonPrimitive?.int)
        for (request in initial) {
            assertTrue(s.acceptFrameJson("""{"kind":"history","sessionId":"${request.sessionId}","parentSessionId":"lead","requestId":"${request.requestId}","projections":{"asOfSeq":4,"values":{"modelSelection":{"model":"ready-model"},"subagent":{"mode":"continuable","label":"完整成员说明"}}}}"""))
        }
        val third = requests().single()
        assertEquals("c", third.sessionId)
        s.finishMemberProfileRequest(third.requestId)
        assertTrue(requests().isEmpty())
        assertEquals("ready-model", s.snapshot("lead").members[1].model)
        assertEquals("完整成员说明", s.snapshot("lead").members[1].detail)
        assertFalse(s.snapshot("other").visible)
    }

    @Test fun lateOrWrongAddressMemberProfileCannotOverwriteNewMetadata() {
        val s = store(); update(s)
        val request = wireJson.decodeFromString<List<TeamMemberProfileRequest>>(s.memberProfileRequestsJson("lead")).single()
        assertFalse(s.acceptFrameJson("""{"kind":"history","sessionId":"a","parentSessionId":"wrong","requestId":"${request.requestId}","projections":{"asOfSeq":900,"values":{"modelSelection":{"model":"wrong"}}}}"""))
        assertTrue(s.memberProfileRequestPending(request.requestId))
        s.acceptFrameJson("""{"kind":"session-snapshot","sessionId":"a","projections":{"asOfSeq":20,"values":{"modelSelection":{"model":"new-model"}}}}""")
        s.acceptFrameJson("""{"kind":"history","sessionId":"a","parentSessionId":"lead","requestId":"${request.requestId}","projections":{"asOfSeq":10,"values":{"modelSelection":{"model":"old-model"}}}}""")
        assertEquals("new-model", s.snapshot("lead").members.last().model)
        assertFalse(s.acceptFrameJson("""{"kind":"history","sessionId":"a","requestId":"${request.requestId}","projections":{"asOfSeq":999,"values":{"modelSelection":{"model":"late-model"}}}}"""))
        assertEquals("new-model", s.snapshot("lead").members.last().model)
    }

    @Test fun sessionCacheCoalescesInitialQueriesAndStoresChangesBeforeOpening() {
        val s = store()
        val query = wireJson.parseToJsonElement(s.queryIfNeededJson("lead", "agent-team")!!).jsonObject
        assertNull(s.queryIfNeededJson("lead", "agent-team"))
        assertTrue(s.acceptFrameJson("""{"kind":"agent-team","sessionId":"lead","requestId":${query["requestId"]},"asOfSeq":120,"agentTeam":$team}"""))
        assertNull(s.queryIfNeededJson("lead", "agent-team"))
        update(s, team.replace("素材勘察", "已更新的任务"), 121)
        assertEquals("已更新的任务", s.snapshot("lead").tasks.single().subject)
        assertNull(s.queryIfNeededJson("lead", "agent-team"))
        assertNotNull(s.queryIfNeededJson("other", "agent-team"))
        assertNotNull(s.queryJson("lead", "agent-team"), "手动刷新仍可重新查询")
    }

    @Test fun baselineEmptyResultsAreCachedAndReconnectKeepsDataWhileRevalidating() {
        val s = store()
        s.acceptFrameJson("""{"kind":"projection-baseline","projections":{"lead":{"asOfSeq":120,"values":{"agentTeam":$team,"subagentCatalog":[]}}}}""")
        assertNull(s.queryIfNeededJson("lead", "agent-team"))
        assertNull(s.queryIfNeededJson("lead", "subagents"))
        s.acceptFrameJson("""{"kind":"hello","capabilities":["agent-team-v1","subagent-conversations-v1"]}""")
        assertEquals(1, s.snapshot("lead").tasks.size)
        assertNotNull(s.queryIfNeededJson("lead", "agent-team"))
        assertNotNull(s.queryIfNeededJson("lead", "subagents"))
        s.acceptFrameJson("""{"kind":"projection-baseline","projections":{"lead":{"asOfSeq":121,"values":{"agentTeam":{"members":[],"tasks":[]},"subagentCatalog":[]}}}}""")
        assertFalse(s.snapshot("lead").visible)
        assertNull(s.queryIfNeededJson("lead", "agent-team"))
        assertNull(s.queryIfNeededJson("lead", "subagents"))
    }

    @Test fun failedInitialQueryCanRetryAndDoesNotDiscardCachedBoard() {
        val s = store()
        val query = wireJson.parseToJsonElement(s.queryIfNeededJson("lead", "agent-team")!!).jsonObject
        assertTrue(s.acceptFrameJson("""{"kind":"error","sessionId":"lead","requestType":"agent-team","requestId":${query["requestId"]},"message":"暂不可用"}"""))
        assertNotNull(s.queryIfNeededJson("lead", "agent-team"))
        update(s)
        update(s, "null", 121)
        assertEquals(1, s.snapshot("lead").tasks.size)
        assertNotNull(s.queryJson("lead", "agent-team"))
    }

    @Test fun leadOnlyDoesNotExposeEntryButActualChildDoes() {
        val s = store()
        update(s, """{"members":[{"id":"lead","name":"lead","role":"lead","phase":"active"}],"tasks":[]}""")
        assertFalse(s.snapshot("lead").visible)
        update(s, seq = 121)
        assertTrue(s.snapshot("lead").visible)
        assertEquals("scout", s.snapshot("lead").tasks.single().ownerName)
        assertEquals("completed", s.snapshot("lead").tasks.single().status)
    }

    @Test fun entryFollowsActualMembersAsLeadStartsAndFinishesDelegation() {
        val s = store()
        val empty = """{"members":[{"id":"lead","name":"lead","role":"lead","phase":"active"}],"tasks":[]}"""
        assertFalse(s.snapshot("lead").visible)
        update(s, empty)
        assertFalse(s.snapshot("lead").visible)
        update(s, team.replace("\"phase\":\"active\"", "\"phase\":\"provisioning\""), 121)
        assertTrue(s.snapshot("lead").visible)
        update(s, seq = 122)
        s.acceptFrameJson("""{"kind":"session-status","sessionId":"a","running":false}""")
        assertTrue(s.snapshot("lead").visible, "成员停止运行后仍可查看历史与已完成任务")
        assertFalse(s.snapshot("another-lead").visible)
        update(s, empty, 123)
        assertFalse(s.snapshot("lead").visible)
        s.acceptFrameJson("""{"kind":"subagents-updated","sessionId":"lead","asOfSeq":124,"subagentCatalog":[{"id":"review","mode":"one-shot"}]}""")
        assertTrue(s.snapshot("lead").visible)
        s.acceptFrameJson("""{"kind":"subagents-updated","sessionId":"lead","asOfSeq":125,"subagentCatalog":[]}""")
        assertFalse(s.snapshot("lead").visible)
    }

    @Test fun entryRequiresValidChildAndMatchingGatewayCapability() {
        val s = store()
        s.acceptFrameJson("""{"kind":"subagents-updated","sessionId":"lead","subagentCatalog":[{"id":"lead"},{"id":" "}]}""")
        assertFalse(s.snapshot("lead").visible)
        update(s, """{"members":[{"id":"lead","role":"teammate"},{"id":" ","role":"teammate"}],"tasks":[]}""")
        assertFalse(s.snapshot("lead").visible)
        val teamOnly = SharedTeamStore()
        teamOnly.acceptFrameJson("""{"kind":"hello","capabilities":["agent-team-v1"]}""")
        teamOnly.acceptFrameJson("""{"kind":"subagents-updated","sessionId":"lead","subagentCatalog":[{"id":"review"}]}""")
        assertFalse(teamOnly.snapshot("lead").visible)
        update(teamOnly)
        assertTrue(teamOnly.snapshot("lead").visible)
    }

    @Test fun fullReplacementRemovesTasksAndKeepsUnknownOwners() {
        val s = store(); update(s)
        update(s, team.replace("scout\",\"blockedBy", "missing\",\"blockedBy"), 121)
        assertEquals("missing", s.snapshot("lead").tasks.single().ownerName)
        update(s, """{"members":[],"tasks":[]}""", 122)
        assertTrue(s.snapshot("lead").tasks.isEmpty())
        assertFalse(s.snapshot("lead").visible)
    }

    @Test fun oldQueryCannotOverwriteNewLiveValueAndMigrationIsNotAuthoritative() {
        val s = store()
        val request = wireJson.parseToJsonElement(s.queryJson("lead", "agent-team")!!).jsonObject
        update(s, seq = 130)
        s.acceptFrameJson("""{"kind":"agent-team","sessionId":"lead","requestId":${request["requestId"]},"projectionState":"ready","asOfSeq":120,"agentTeam":{"members":[],"tasks":[]}}""")
        assertEquals(1, s.snapshot("lead").tasks.size)
        s.acceptFrameJson("""{"kind":"agent-team","sessionId":"lead","requestId":${request["requestId"]},"projectionState":"migration-required","agentTeam":{"members":[],"tasks":[]}}""")
        assertEquals(1, s.snapshot("lead").tasks.size)
    }

    @Test fun sameSeqNullInvalidatesWithoutTurningLastGoodDataIntoEmptyBoard() {
        val s = store(); update(s)
        update(s, "null")
        assertEquals("unavailable", s.snapshot("lead").state)
        assertTrue(s.snapshot("lead").visible)
        assertEquals(1, s.snapshot("lead").tasks.size)
    }

    @Test fun projectionClocksAreIndependentAndBaselineCanResetThem() {
        val s = store(); update(s)
        s.acceptFrameJson("""{"kind":"subagent-timing-updated","sessionId":"lead","asOfSeq":999,"subagentTiming":{"settledMs":3}}""")
        update(s, """{"members":[],"tasks":[]}""", 121)
        assertTrue(s.snapshot("lead").tasks.isEmpty())
        s.acceptFrameJson("""{"kind":"projection-baseline","projections":{"lead":{"asOfSeq":3,"values":{"agentTeam":$team}}}}""")
        assertEquals(1, s.snapshot("lead").tasks.size)
    }

    @Test fun runningNotificationDuringQueryWinsOverEarlierSummary() {
        val s = store(); update(s); s.sessionsRequested()
        s.acceptFrameJson("""{"kind":"session-status","sessionId":"a","running":true}""")
        s.acceptFrameJson("""{"kind":"sessions","items":[{"sessionId":"a","running":false}]}""")
        assertEquals(true, s.snapshot("lead").members.last().running)
        assertEquals("completed", s.snapshot("lead").tasks.single().status)
    }

    @Test fun childAddressIsAttachedToEverySupportedOperationAndNoOrdinaryForkInference() {
        val s = store(); update(s)
        for (type in listOf("history", "subscribe", "message", "session-cancel", "tasks", "goal")) {
            val payload = s.decorateRequestJson("""{"type":"$type","sessionId":"a","workspaceId":"wrong","text":"hi","mode":"steer","images":[{"mediaType":"image/png","data":"AAAA"}]}""")!!
            val obj = wireJson.parseToJsonElement(payload).jsonObject
            assertEquals("lead", obj["parentSessionId"]?.jsonPrimitive?.content)
            assertEquals("continuable", obj["subagentMode"]?.jsonPrimitive?.content)
            assertEquals("steer", obj["mode"]?.jsonPrimitive?.content)
            assertNull(obj["workspaceId"])
            assertNotNull(obj["images"])
        }
        assertNull(s.decorateRequestJson("""{"type":"select-model","sessionId":"a"}"""))
        assertEquals("""{"type":"history","sessionId":"fork"}""", s.decorateRequestJson("""{"type":"history","sessionId":"fork"}"""))
    }

    @Test fun nestedTargetUsesImmediateParentAndOneShotIsReadOnly() {
        val s = store(); update(s)
        val request = wireJson.parseToJsonElement(s.queryJson("a", "subagents")!!).jsonObject
        s.acceptFrameJson("""{"kind":"subagents","sessionId":"a","requestId":${request["requestId"]},"projectionState":"ready","asOfSeq":2,"subagentCatalog":[{"id":"c","mode":"one-shot","label":"review"}]}""")
        val target = wireJson.decodeFromString<ConversationTarget>(s.targetJson("c"))
        assertEquals("a", target.parentSessionId)
        assertFalse(s.canSend("c"))
        assertNull(s.decorateRequestJson("""{"type":"message","sessionId":"c","text":"no"}"""))
        assertNotNull(s.decorateRequestJson("""{"type":"history","sessionId":"c"}"""))
    }

    @Test fun durableRoutingRestoresBeforeProjectionsAndDoesNotOverwriteLiveIdentity() {
        val original = store(); update(original)
        original.acceptFrameJson("""{"kind":"subagents-updated","sessionId":"a","subagentCatalog":[{"id":"review","mode":"one-shot"}]}""")
        val restored = store()
        assertTrue(restored.restoreRoutingJson(original.routingJson()))
        assertEquals("a", wireJson.decodeFromString<ConversationTarget>(restored.targetJson("review")).parentSessionId)
        assertNull(restored.decorateRequestJson("""{"type":"models","sessionId":"a"}"""))
        assertFalse(restored.canSend("review"))
        val request = wireJson.parseToJsonElement(restored.decorateRequestJson("""{"type":"history","sessionId":"review"}""")!!).jsonObject
        assertEquals("a", request["parentSessionId"]?.jsonPrimitive?.content)
        restored.acceptFrameJson("""{"kind":"subagents-updated","sessionId":"new-parent","subagentCatalog":[{"id":"review","mode":"continuable"}]}""")
        assertTrue(restored.restoreRoutingJson(original.routingJson()))
        assertEquals("new-parent", wireJson.decodeFromString<ConversationTarget>(restored.targetJson("review")).parentSessionId)
        assertFalse(restored.restoreRoutingJson("""[{"sessionId":"x","parentSessionId":"x","subagentMode":"continuable"}]"""))
        assertFalse(restored.isChild("x"))
    }

    @Test fun independentGatewayInstancesCannotShareTaskIdsOrTargets() {
        val first = store(); val second = store(); update(first)
        assertFalse(second.snapshot("lead").visible)
        assertFalse(second.isChild("a"))
        assertTrue(first.isChild("a"))
    }

    @Test fun staleRequestIdIsIgnoredAndFailedProvisioningCannotBeOpened() {
        val s = store(); s.queryJson("lead", "agent-team")
        val old = wireJson.parseToJsonElement(s.queryJson("lead", "agent-team")!!).jsonObject
        s.queryJson("lead", "agent-team")
        assertFalse(s.acceptFrameJson("""{"kind":"agent-team","sessionId":"lead","requestId":${old["requestId"]},"asOfSeq":9,"agentTeam":$team}"""))
        update(s, team.replace("\"phase\":\"active\"", "\"phase\":\"failed\""))
        assertFalse(s.snapshot("lead").members.last().canOpen)
        assertFalse(s.canSend("a"))
    }
}
