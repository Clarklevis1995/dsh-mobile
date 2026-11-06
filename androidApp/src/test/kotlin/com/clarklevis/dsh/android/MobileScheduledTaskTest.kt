package com.clarklevis.dsh.android

import com.clarklevis.dsh.android.ui.ScheduledTaskStatusFilter
import com.clarklevis.dsh.android.ui.filteredScheduledTasks
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

    @Test
    fun filtersTasksByStatusAndTitleOrPrompt() {
        val frame = GatewayWireDecoder.decode("""
            {"kind":"schedule-catalog","items":[
              {"id":"active-news","sessionId":"s1","status":"active","kind":"daily","title":"每日新闻", "prompt":"汇总科技动态","scheduledAt":"2099-01-01"},
              {"id":"ended-news","sessionId":"s2","status":"completed","kind":"daily","title":"新闻归档", "prompt":"整理历史记录","scheduledAt":"2099-01-02"},
              {"id":"active-weather","sessionId":"s3","status":"active","kind":"daily","title":"天气提醒", "prompt":"播报天气","scheduledAt":"2099-01-03"}
            ]}
        """.trimIndent())
        val tasks = frame.items.orEmpty().mapNotNull(MobileScheduledTask::from)
        val paused = tasks.first().copy(id = "paused-news", status = "paused")
        val withPaused = tasks + paused
        assertEquals(listOf("paused-news"), filteredScheduledTasks(withPaused,
            ScheduledTaskStatusFilter.PAUSED, "新闻").map { it.id })
        assertEquals(listOf("ended-news"), filteredScheduledTasks(withPaused,
            ScheduledTaskStatusFilter.ENDED, "新闻").map { it.id })
        assertEquals(listOf("active-news"), filteredScheduledTasks(tasks,
            ScheduledTaskStatusFilter.ACTIVE, " 科技 ").map { it.id })
        assertEquals(listOf("ended-news"), filteredScheduledTasks(tasks,
            ScheduledTaskStatusFilter.ENDED, "新闻").map { it.id })
        assertEquals(listOf("active-news", "ended-news"), filteredScheduledTasks(tasks,
            ScheduledTaskStatusFilter.ALL, "新闻").map { it.id })
    }
}
