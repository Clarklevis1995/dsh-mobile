package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileScheduledTaskTest {
    @Test
    fun catalogEntryPreservesRuleAndLastDelivery() {
        val frame = GatewayWireDecoder.decode(
            """{"kind":"schedule-catalog","items":[{"id":"task-1","sessionId":"s1","status":"active","kind":"daily","title":"技术简报","prompt":"搜索最新动态","time":"08:00","timeZone":"Asia/Shanghai","scheduledAt":"2099-01-01T00:00:00.000Z","lastDelivery":{"deliveredAt":"2098-12-31T00:00:01.000Z","messageId":"m1"}}]}"""
        )
        val task = requireNotNull(frame.items?.single()?.let(MobileScheduledTask::from))
        assertEquals("s1", task.sessionId)
        assertEquals("技术简报", task.title)
        assertEquals("08:00", task.raw["time"]?.stringValue)
        assertEquals("m1", task.lastDelivery?.get("messageId")?.stringValue)
    }

    @Test
    fun malformedCatalogEntryIsIgnored() {
        val frame = GatewayWireDecoder.decode("""{"kind":"schedule-catalog","items":[{"id":"task-1"}]}""")
        assertNull(frame.items?.single()?.let(MobileScheduledTask::from))
    }
}
