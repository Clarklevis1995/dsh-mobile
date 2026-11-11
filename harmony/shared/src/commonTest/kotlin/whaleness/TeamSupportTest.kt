package whaleness

import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamSupportTest {
    private val engine = HarmonyEngine()

    private fun command(value: HarmonyCommand): HarmonyResult = wireJson.decodeFromString(engine.dispatch(wireJson.encodeToString(value)))

    private fun frame(raw: String) = command(HarmonyCommand("frame", frame = raw))

    private fun payload(
        result: HarmonyResult,
        type: String,
    ) = result.outbound.map { wireJson.parseToJsonElement(it.text).jsonObject }.first {
        it["type"]!!.jsonPrimitive.content ==
            type
    }

    private fun ready(): HarmonyResult {
        command(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1/ws/mobile"))
        frame(
            """{"kind":"hello","authenticated":true,"capabilities":["agent-team-v1","subagent-conversations-v1","session-agent-preset"]}""",
        )
        val selected = command(HarmonyCommand("select", sessionId = "lead"))
        return if (selected.outbound.any {
                it.text.contains("\"type\":\"agent-team\"")
            }
        ) {
            selected
        } else {
            command(HarmonyCommand("team-open", sessionId = "lead"))
        }
    }

    private fun team(
        opened: HarmonyResult,
        seq: Long = 10,
        subject: String = "真实任务",
    ): HarmonyResult {
        val id = payload(opened, "agent-team")["requestId"]!!.jsonPrimitive.content
        return frame(
            """{"kind":"agent-team","sessionId":"lead","requestId":"$id","asOfSeq":$seq,"agentTeam":{"members":[{"id":"lead","name":"主智能体","role":"lead","phase":"active"},{"id":"child","name":"成员甲","role":"teammate","phase":"active"}],"tasks":[{"id":"task","subject":"$subject","status":"pending"}]}}""",
        )
    }

    @Test fun teamSnapshotUsesSharedProjectionAndOldQueryCannotOverwriteTasks() {
        val open = ready()
        val loaded = team(open)
        assertTrue(loaded.team!!.lead.visible)
        assertEquals(
            "真实任务",
            loaded.team!!
                .lead.tasks
                .single()
                .subject,
        )
        assertNull(
            frame(
                """{"kind":"agent-team","sessionId":"lead","requestId":"old","asOfSeq":100,"agentTeam":{"members":[],"tasks":[]}}""",
            ).team,
        )
        assertNull(frame("""{"kind":"agent-team-updated","sessionId":"lead","asOfSeq":1,"agentTeam":{"members":[],"tasks":[]}}""").team)
    }

    @Test fun memberProfileReadsAreBoundedAndNeverReachConversationHistory() {
        val loaded = team(ready())
        val profile = loaded.outbound.single { it.text.contains("team-profile-") }
        val p = wireJson.parseToJsonElement(profile.text).jsonObject
        assertEquals("lead", p["parentSessionId"]!!.jsonPrimitive.content)
        val response =
            frame(
                """{"kind":"history","sessionId":"child","requestId":"${p["requestId"]!!.jsonPrimitive.content}","parentSessionId":"lead","projections":{"asOfSeq":10,"values":{"subagent":{"label":"资料已确认","mode":"continuable"},"modelSelection":{"model":"fixture"}}}}""",
            )
        assertTrue(response.patches.isEmpty())
        assertEquals("lead", response.state!!.selectedSessionId)
        assertEquals(
            "资料已确认",
            response.team!!
                .lead.members
                .first { it.id == "child" }
                .detail,
        )
    }

    @Test fun continuableMemberUsesParentAddressAndSkipsUnsupportedAgentQueries() {
        team(ready())
        val child = command(HarmonyCommand("team-child-open", sessionId = "child"))
        assertTrue(child.ok)
        assertTrue(child.state!!.childConversation)
        assertTrue(child.state!!.canSend)
        assertFalse(child.outbound.any { it.text.contains("agent-preset") })
        assertEquals("lead", payload(child, "subscribe")["parentSessionId"]!!.jsonPrimitive.content)
        val sent = command(HarmonyCommand("send", text = "继续处理"))
        assertTrue(sent.ok)
        val message = payload(sent, "message")
        assertEquals("child", message["sessionId"]!!.jsonPrimitive.content)
        assertEquals("lead", message["parentSessionId"]!!.jsonPrimitive.content)
        assertFalse(message.containsKey("cwd"))
        assertFalse(message.containsKey("workspaceId"))
        assertFalse(message.containsKey("agentPreset"))
    }

    @Test fun oneShotMemberIsReadableButCannotSendOrStop() {
        val open = ready()
        val rid = payload(open, "subagents")["requestId"]!!.jsonPrimitive.content
        frame(
            """{"kind":"subagents","sessionId":"lead","requestId":"$rid","asOfSeq":10,"subagentCatalog":[{"id":"once","label":"只读任务","mode":"oneshot"}]}""",
        )
        val child = command(HarmonyCommand("team-child-open", sessionId = "once"))
        assertTrue(child.ok)
        assertFalse(child.state!!.canSend)
        val send = command(HarmonyCommand("send", text = "不能发送"))
        assertFalse(send.ok)
        assertTrue(send.outbound.isEmpty())
        val stop = command(HarmonyCommand("cancel"))
        assertFalse(stop.ok)
        assertTrue(stop.outbound.isEmpty())
    }

    @Test fun timeoutReleasesOnlyItsOwnQueryAndAllowsExplicitRefresh() {
        val open = ready()
        val key = open.state!!.pendingOperations.first { it.startsWith("team:") }
        val timeout = command(HarmonyCommand("operation-timeout", requestKey = key))
        assertFalse(key in timeout.state!!.pendingOperations)
        assertEquals(
            "raw",
            timeout.team!!
                .lead.error!!
                .key,
        )
        assertTrue(
            timeout.team!!
                .lead.error!!
                .args
                .first()
                .contains("超时"),
        )
        val retry = command(HarmonyCommand("team-refresh", sessionId = "lead"))
        assertTrue(retry.outbound.any { it.text.contains("agent-team") })
        assertNull(command(HarmonyCommand("operation-timeout", requestKey = key)).team)
    }

    @Test fun hostResetRemovesOldMemberRoutingAndTokensDoNotBuildTeamViews() {
        team(ready())
        assertNull(frame("""{"kind":"pong"}""").team)
        command(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1:18897/ws/mobile"))
        frame("""{"kind":"hello","authenticated":true,"capabilities":["agent-team-v1"]}""")
        val old = command(HarmonyCommand("team-child-open", sessionId = "child"))
        assertFalse(old.ok)
        assertTrue(old.outbound.isEmpty())
    }
}
