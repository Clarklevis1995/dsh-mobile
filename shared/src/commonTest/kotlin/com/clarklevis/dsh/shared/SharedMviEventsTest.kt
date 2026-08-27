package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMviEventEmitter
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SharedMviEventsTest {
    @Test
    fun subscriptionReceivesSnapshotThenOrderedTransitionsAndStopsAfterCancel() {
        val emitter = SharedMviEventEmitter("session-control")
        val received = mutableListOf<SharedMviEvent>()
        val subscription = emitter.subscribe(
            SharedMviEventObserver(received::add),
            snapshotPayloadJson = "{\"value\":0}"
        )

        emitter.emitTransition("tx-1", "{\"value\":1}", "[]")
        emitter.emitTransition("tx-2", "{\"value\":2}", "[{\"kind\":\"request\"}]")
        subscription.cancel()
        subscription.cancel()
        emitter.emitTransition("tx-3", "{\"value\":3}", "[]")

        assertEquals(listOf(0L, 1L, 2L), received.map { it.sequence })
        assertEquals(listOf("snapshot", "transition", "transition"), received.map { it.kind })
        assertEquals("tx-2", received.last().transactionId)
        assertEquals("[{\"kind\":\"request\"}]", received.last().effectsJson)
    }

    @Test
    fun reentrantEmissionIsQueuedUntilAllObserversReceiveCurrentEvent() {
        val emitter = SharedMviEventEmitter("question")
        val delivery = mutableListOf<String>()
        emitter.subscribe(SharedMviEventObserver { event ->
            if (event.kind == "snapshot") return@SharedMviEventObserver
            delivery += "first:${event.sequence}"
            if (event.sequence == 1L) emitter.emitTransition("tx-2", "{}")
        }, "{}")
        emitter.subscribe(SharedMviEventObserver { event ->
            if (event.kind != "snapshot") delivery += "second:${event.sequence}"
        }, "{}")

        emitter.emitTransition("tx-1", "{}")

        assertEquals(
            listOf("first:1", "second:1", "first:2", "second:2"),
            delivery
        )
    }

    @Test
    fun observerFailureDoesNotEscapeOrBlockOtherObservers() {
        val emitter = SharedMviEventEmitter("session-list")
        var healthyObserverCalls = 0
        emitter.subscribe(SharedMviEventObserver { error("platform callback failed") }, "{}")
        emitter.subscribe(SharedMviEventObserver { event ->
            if (event.kind == "transition") healthyObserverCalls++
        }, "{}")

        emitter.emitTransition("tx-1", "{}")

        assertEquals(1, healthyObserverCalls)
    }

    @Test
    fun transactionAndErrorIdentifiersMustBeExplicit() {
        val emitter = SharedMviEventEmitter("session-control")

        assertFailsWith<IllegalArgumentException> { emitter.emitTransition("", "{}") }
        assertFailsWith<IllegalArgumentException> { emitter.emitError("tx", "", null) }
    }
}
