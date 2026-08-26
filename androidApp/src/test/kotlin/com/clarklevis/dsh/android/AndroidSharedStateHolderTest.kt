package com.clarklevis.dsh.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AndroidSharedStateHolderTest {
    @Test
    fun fixtureAndWireInputAreReducedBySharedStore() {
        val holder = AndroidSharedStateHolder()
        holder.loadFixture()
        assertEquals(2, holder.snapshot.conversation.size)
        assertEquals(1, holder.snapshot.pendingQuestionCount)

        holder.submitWirePayload()
        assertEquals("event", holder.snapshot.lastFrameKind)
        assertEquals("最终消息会替换流式临时消息。", holder.snapshot.conversation.last().text)
        assertNotNull(holder.snapshot.selectedSessionId)
    }
}
