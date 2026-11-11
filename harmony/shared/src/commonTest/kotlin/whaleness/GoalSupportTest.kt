package whaleness

import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalSupportTest {
    private val support = GoalSupport()

    private fun frame(json: String): GoalChange? = support.accept(json, GatewayWireDecoder.decode(json))

    private fun goal(
        revision: Int,
        sequence: Int,
        objective: String = "original",
        phase: String = "active",
    ) = frame(
        """{"kind":"goal","sessionId":"s","asOfSeq":$sequence,"goal":{"goal":{"id":"goal","revision":$revision,"objective":"$objective","phase":"$phase"},"roundsStarted":2}}""",
    )!!

    private fun ready(child: Boolean = false) {
        support.open("s", true, listOf("tasks", "goals"), child)
        frame("""{"kind":"tasks","sessionId":"s","asOfSeq":1,"todos":[]}""")
        goal(7, 10)
    }

    private fun command(
        op: String,
        revision: Long = 7,
    ) = HarmonyCommand(op, sessionId = "s", itemId = "goal", draftRevision = revision, text = "new objective")

    @Test fun readsAndMutationUseSharedWaterlineAndExpectedReference() {
        ready()
        assertEquals(
            "original",
            goal(3, 2, "stale")
                .view.goal!!
                .goal.objective,
        )
        val pending = support.command(command("goal-pause"))
        assertEquals(
            "active",
            pending.view.goal!!
                .goal.phase,
        )
        val payload = wireJson.parseToJsonElement(pending.requests.single().payload).jsonObject
        assertEquals("7", payload["ref"]!!.jsonObject["revision"]!!.jsonPrimitive.content)
        val ack = frame("""{"kind":"goal-pause","sessionId":"s"}""")!!
        assertTrue(ack.view.loading)
        assertEquals("goal", ack.requests.single().requestType)
        val confirmed = goal(8, 11, phase = "paused")
        assertFalse(confirmed.view.loading)
        assertEquals(
            "paused",
            confirmed.view.goal!!
                .goal.phase,
        )
    }

    @Test fun editorCannotWriteOverNewRevisionAndDoesNotSendOnRejection() {
        ready()
        goal(8, 11, "updated elsewhere")
        assertFailsWith<IllegalArgumentException> { support.command(command("goal-edit")) }
        assertTrue(support.pendingKeys().isEmpty())
    }

    @Test fun staleTimerCannotFinishLaterRequestAndTimeoutQuarantinesWrites() {
        ready()
        support.command(command("goal-edit"))
        val key = support.pendingKeys().single()
        assertNull(support.timedOut("old-$key"))
        assertTrue(support.timedOut(key)!!.view.uncertain)
        goal(8, 11, "maybe committed")
        assertFailsWith<IllegalArgumentException> { support.command(command("goal-pause", 8)) }
        assertNull(support.timedOut(key))
    }

    @Test fun changingSessionProtectsOldMutationAndIgnoresOldAcknowledgement() {
        ready()
        support.command(command("goal-clear"))
        support.open("other", true, listOf("goals"), false)
        assertNull(frame("""{"kind":"goal-clear","sessionId":"s"}"""))
        val reopened = support.open("s", true, listOf("goals"), false)
        assertTrue(reopened.view.uncertain)
    }

    @Test fun childGoalIsReadableButCannotMutate() {
        ready(true)
        assertFailsWith<IllegalArgumentException> { support.command(command("goal-pause")) }
        assertNotNull(goal(8, 11).view.goal)
    }

    @Test fun tasksCannotRegressOrBindToAnotherSession() {
        ready()
        val first =
            frame(
                """{"kind":"tasks-updated","sessionId":"s","asOfSeq":12,"todos":[{"content":"real task","status":"in_progress"}]}""",
            )!!
        assertEquals(
            "real task",
            first.view.tasks!!
                .single()
                .content,
        )
        assertNull(frame("""{"kind":"tasks","sessionId":"other","asOfSeq":99,"todos":[]}"""))
        val stale = frame("""{"kind":"tasks","sessionId":"s","asOfSeq":2,"todos":[]}""")!!
        assertEquals(
            "real task",
            stale.view.tasks!!
                .single()
                .content,
        )
    }
}
