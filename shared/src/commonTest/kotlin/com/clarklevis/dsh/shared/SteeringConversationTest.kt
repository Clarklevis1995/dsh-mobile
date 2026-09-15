package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedConversationPatch
import com.clarklevis.dsh.shared.facade.SharedConversationStore
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.projection.ConversationItemKind
import com.clarklevis.dsh.shared.projection.ConversationProjector
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.RawSessionEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.encodeToString
import kotlin.test.*

class SteeringConversationTest {
    private fun raw(id: String, content: String = """[{"type":"text","text":"Go"}]""") =
        JsonValue.fromJsonElement(wireJson.parseToJsonElement("""{"id":"$id","content":$content}"""))
    private fun message(id: String, seq: Int = 1, session: String = "s") =
        RawSessionEvent("user/message", seq, 100.0, raw(id)).normalized(session)
    private fun row(id: String, placement: String = "steering") =
        """{"id":"q-$id","placement":"$placement","message":{"id":"$id","content":[{"type":"text","text":"Go"}]}}"""

    @Test fun steeringIsUserBubbleAndNeverAdvancesDurableSequence() {
        val p = ConversationProjector()
        p.fold(listOf(SessionEvent("s", 8, 90.0, GatewayEvent("turn/start"))))
        val changes = p.replaceSteeringMessages(listOf(message("a"), message("b")))
        assertEquals(8, p.lastSequence)
        assertEquals(2, changes.size)
        assertEquals(listOf("Go", "Go"), p.items.map { it.text })
        assertTrue(p.items.all { it.kind == ConversationItemKind.USER })
        assertEquals(2, p.items.map { it.id }.distinct().size)
        assertTrue(p.replaceSteeringMessages(listOf(message("a"), message("b"))).isEmpty())
    }

    @Test fun durableMessagePromotesOnlyItsOwnBubbleAndStaleQueueCannotDuplicateIt() {
        val p = ConversationProjector()
        p.replaceSteeringMessages(listOf(message("a"), message("b")))
        val before = p.items.map { it.id }
        val operations = p.foldWithOperations(listOf(message("b", 10)))
        assertEquals(listOf("replace"), operations.map { it.kind })
        assertEquals(before, p.items.map { it.id })
        p.replaceSteeringMessages(listOf(message("a"), message("b")))
        assertEquals(2, p.items.size)
        p.replaceSteeringMessages(emptyList())
        assertEquals(listOf("s-user-b"), p.items.map { it.id })
    }

    @Test fun eventBeforeQueueDoesNotCreateSecondBubble() {
        val p = ConversationProjector()
        p.fold(listOf(message("a")))
        assertTrue(p.replaceSteeringMessages(listOf(message("a"))).isEmpty())
        assertEquals(1, p.items.size)
    }

    @Test fun legacyEventsWithoutIdentityConsumeOneMatchingPendingMessageOnly() {
        val p = ConversationProjector()
        p.fold(listOf(SessionEvent("s", 1, 50.0, GatewayEvent("user/message", text = "Go"))))
        p.replaceSteeringMessages(listOf(message("a"), message("b")))
        p.fold(listOf(SessionEvent("s", 2, 101.0, GatewayEvent("user/message", text = "Go"))))
        assertEquals(3, p.items.size)
        p.replaceSteeringMessages(emptyList())
        assertEquals(listOf("s-1", "s-user-a"), p.items.map { it.id })
    }

    @Test fun attachmentOnlySteeringSurvivesPromotion() {
        val data = raw("image", """[{"type":"image","attachment":{"attachmentId":"att-1","mediaType":"image/png","bytes":2,"width":1,"height":1}}]""")
        val record = RawSessionEvent("user/message", 3, 100.0, data).normalized("s")
        val p = ConversationProjector()
        p.replaceSteeringMessages(listOf(record))
        p.fold(listOf(record))
        assertEquals("att-1", p.items.single().images.single().attachmentId)
    }

    @Test fun storeRebasesPendingMessagesWithoutChangingWatermarkOrRepeatingCommittedOnes() {
        val store = SharedConversationStore()
        val patches = mutableListOf<SharedConversationPatch>()
        store.subscribe(SharedMviEventObserver { event ->
            if (event.kind == "transition") patches += wireJson.decodeFromString<SharedConversationPatch>(event.statePayloadJson!!)
        })
        assertTrue(store.replaceSteeringMessages("s", "[${row("a")},${row("q", "queued")},${row("c", "context")}]").accepted)
        assertEquals(1, patches.last().operations.size)
        assertEquals(-1, patches.last().lastSequence)
        assertTrue(store.replaceSession("s", "[]").accepted)
        assertEquals(1, patches.last().replacementItems!!.size)
        assertTrue(store.replaceSession("s", wireJson.encodeToString(listOf(message("a", 5)))).accepted)
        assertEquals(1, patches.last().replacementItems!!.size)
        assertEquals(5, patches.last().lastSequence)
        store.clearSession("s")
        store.replaceSession("s", "[]")
        assertTrue(patches.last().replacementItems!!.isEmpty())
    }
}
