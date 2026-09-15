package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedQueueStore
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class SharedQueueStoreTest {
    private fun row(id: String = "q1", placement: String = "queued", content: String = """[{"type":"text","text":"再写一个 👏"}]""") =
        """{"id":"$id","placement":"$placement","message":{"id":"m-$id","content":$content}}"""
    private fun update(store: SharedQueueStore, session: String = "a", rows: String = row()) =
        store.acceptFrame("""{"kind":"session-queue","sessionId":"$session","items":[$rows]}""")
    private fun ack(store: SharedQueueStore, session: String = "a", action: String = "remove", accepted: Boolean = true) =
        store.acceptFrame("""{"kind":"queue-item-updated","sessionId":"$session","itemId":"q1","action":"$action","accepted":$accepted}""")

    @Test fun snapshotsReplaceAndDeduplicateWithoutShowingSteeringOrContext() {
        val store = SharedQueueStore()
        val state = update(store, rows = listOf(row(), row(), row("q2", "steering"), row("q3", "context")).joinToString())
        assertEquals(listOf("q1"), state.queues["a"]?.map { it.id })
        assertTrue(update(store, rows = "").queues["a"].orEmpty().isEmpty())
        update(store, "b")
        assertTrue(store.acceptFrame("""{"kind":"session-queues","queues":{}}""").queues.isEmpty())
    }

    @Test fun editWaitsForRemoveAcknowledgementAndRestoresOnceToOriginalSession() {
        val store = SharedQueueStore(); update(store)
        val request = assertNotNull(store.beginAction("a", "q1", "edit"))
        assertEquals("remove", wireJson.parseToJsonElement(request.payload).jsonObject["action"]?.jsonPrimitive?.content)
        assertNull(store.takeDraft("a"))
        assertNull(store.beginAction("a", "q1", "steer"))
        ack(store, session = "b")
        assertEquals("q1", store.snapshot().pendingItemId)
        update(store, rows = "") // Host 广播可能先于确认抵达。
        ack(store)
        assertNull(store.takeDraft("b"))
        store.resetConnection()
        assertEquals("再写一个 👏", store.takeDraft("a"))
        ack(store)
        assertNull(store.takeDraft("a"))
    }

    @Test fun rejectionNeverDeletesOrRestoresDraft() {
        val store = SharedQueueStore(); update(store)
        store.beginAction("a", "q1", "edit")
        assertNotNull(ack(store, accepted = false).lastError)
        assertNull(store.takeDraft("a"))
        assertEquals(1, store.snapshot().queues["a"]?.size)
        store.beginAction("a", "q1", "edit")
        store.acceptFrame("""{"kind":"error","requestType":"queue-update","sessionId":"a","itemId":"q1","message":"no longer pending"}""")
        assertNull(store.snapshot().pendingItemId)
        assertNull(store.takeDraft("a"))
    }

    @Test fun unrelatedErrorsAndAcknowledgementsDoNotFinishPendingAction() {
        val store = SharedQueueStore(); update(store)
        store.beginAction("a", "q1", "steer")
        ack(store, action = "remove")
        store.acceptFrame("""{"kind":"error","requestType":"queue-update","sessionId":"b","message":"failed"}""")
        assertEquals("q1", store.snapshot().pendingItemId)
        ack(store, action = "steer")
        assertNull(store.snapshot().pendingItemId)
        assertTrue(store.snapshot().queues["a"].orEmpty().isEmpty())
        assertNull(store.takeDraft("a"))
    }

    @Test fun attachmentsRemainVisibleButCannotBeSilentlyDroppedByEditing() {
        val store = SharedQueueStore()
        val item = update(store, rows = row(content = """[{"type":"text","text":"看图片"},{"type":"image","source":{"type":"base64","data":"AA=="}}]""")).queues["a"]!!.single()
        assertEquals(1, item.attachmentCount)
        assertFalse(item.editable)
        assertNull(store.beginAction("a", "q1", "edit"))
        assertNotNull(store.beginAction("a", "q1", "steer"))
    }

    @Test fun resetAndTransportFailureReleasePendingWithoutInventingSuccess() {
        val store = SharedQueueStore(); update(store)
        store.beginAction("a", "q1", "edit")
        store.failPending("timeout")
        assertNull(store.takeDraft("a"))
        assertNull(store.snapshot().pendingItemId)
        store.reset()
        ack(store)
        assertTrue(store.snapshot().queues.isEmpty())
        assertNull(store.takeDraft("a"))
    }

    @Test fun malformedBaselineDoesNotPartiallyOverwriteValidQueues() {
        val store = SharedQueueStore(); update(store)
        val state = store.acceptFrame("""{"kind":"session-queues","queues":{"b":[],"c":{}}}""")
        assertEquals(setOf("a"), state.queues.keys)
        assertNotNull(state.lastError)
    }

    @Test fun messageModeAndQueueMutationUseDistinctWireRequests() {
        for (mode in listOf("queue", "steer")) {
            val message = GatewayRequests.message("你好", emptyList(), "a", null, "Asia/Shanghai", mode)
            val payload = wireJson.parseToJsonElement(message.payload).jsonObject
            assertEquals(mode, payload["mode"]?.jsonPrimitive?.content)
            assertEquals("message", message.requestType)
        }
        val promote = GatewayRequests.queueUpdate("a", "q1", "steer")
        assertEquals("queue-item-updated", promote.responseKind)
        assertEquals("queue-update", promote.requestType)
        assertEquals("q1", wireJson.parseToJsonElement(promote.payload).jsonObject["itemId"]?.jsonPrimitive?.content)
    }
}
