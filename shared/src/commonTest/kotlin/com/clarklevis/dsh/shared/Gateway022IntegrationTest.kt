package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.protocol.GatewayQuoteTextParts
import com.clarklevis.dsh.shared.protocol.GatewayTextPart
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Gateway022IntegrationTest {
    private fun payload(text: String): JsonValue = JsonValue.fromJsonElement(wireJson.parseToJsonElement(text))

    @Test
    fun quoteSendKeepsOrderAndOmitsLegacyText() {
        val parts = listOf(GatewayTextPart("text", "前文"), GatewayTextPart("quote", "第一行\n🙂"), GatewayTextPart("text", "后文"))
        val request = GatewayRequests.message("", emptyList(), "s", null, "Asia/Shanghai", requestId = "send", textParts = parts)
        val body = payload(request.payload)
        assertNull(body["text"])
        assertEquals("第一行\n🙂", body["textParts"]?.arrayValue?.get(1)?.get("text")?.stringValue)
        assertEquals("send", body["requestId"]?.stringValue)
    }

    @Test
    fun invalidQuotesAreRejected() {
        assertFailsWith<IllegalArgumentException> { GatewayTextPart.validate(listOf(GatewayTextPart("quote", " \n"))) }
        assertFailsWith<IllegalArgumentException> { GatewayTextPart.validate(List(101) { GatewayTextPart("text", "x") }) }
        assertFailsWith<IllegalArgumentException> { GatewayTextPart.validate(listOf(GatewayTextPart("html", "x"))) }
    }

    @Test
    fun markdownDraftKeepsFencedCodeAndNormalizesCrLf() {
        val parts = GatewayQuoteTextParts.parse("```kotlin\r\n> code\r\n```\r\n> 原文🙂\r\n\r\n正文")!!
        assertEquals(listOf("text", "quote", "text"), parts.map { it.type })
        assertEquals("```kotlin\n> code\n```", parts[0].text)
        assertEquals("原文🙂", parts[1].text)
        assertFalse(GatewayQuoteTextParts.removeQuote("> 原文\n\n正文", 0).contains("原文"))
        assertNull(GatewayQuoteTextParts.parse("```\n> code\n```"))
    }

    @Test
    fun liveAndHistoryQuotesNormalizeIdentically() {
        val live = GatewayWireDecoder.decode("""{"kind":"event","sessionId":"s","seq":1,"event":{"type":"user/message","requestId":"r","textParts":[{"type":"quote","text":"原文"}]}}""")
        val history = GatewayWireDecoder.decode("""{"kind":"history","events":[{"type":"user/message","seq":1,"time":1,"data":{"content":[{"type":"text","text":"> 原文"}],"source":{"kind":"user","rpcId":"r"},"textParts":[{"type":"quote","text":"原文"}]}}]}""")
        val event = history.events!!.single().normalized("s").event
        assertEquals(live.event!!.textParts, event.textParts)
        assertEquals(live.event.requestId, event.requestId)
    }

    @Test
    fun hostScheduleEditIncludesNullRevisionAndKeepsUnknownTiming() {
        val patch = payload("""{"timing":{"kind":"future-rule","custom":true},"active":false}""")
        val body = payload(GatewayRequests.scheduleEdit("r", "task", null, patch).payload)
        assertEquals(JsonValue.NullValue, body["expectedUpdatedAt"])
        assertEquals(patch, body["patch"])
        assertNull(body["sessionId"])
        assertFailsWith<IllegalArgumentException> { GatewayRequests.scheduleEdit("r", "task", null) }
    }

    @Test
    fun flatScheduleAndNumericCleanupDecodeWithoutLegacyTypeConflicts() {
        val task = GatewayWireDecoder.decode("""{"kind":"schedule-create","id":"t","target":{"kind":"new_once"},"updatedAt":"rev"}""")
        assertEquals("new_once", task.schedule?.get("target")?.get("kind")?.stringValue)
        val cleanup = GatewayWireDecoder.decode("""{"kind":"schedule-delete-inactive","deleted":7}""")
        assertEquals(7, cleanup.deletedCount)
        assertNull(cleanup.deleted)
        assertTrue(GatewayWireDecoder.decode("""{"kind":"schedule-edit","deleted":true}""").deleted == true)
    }

    @Test
    fun paginationAndCleanupRequireValidInputs() {
        val body = payload(GatewayRequests.hostScheduleList("r", active = false, limit = 20, offset = 40).payload)
        assertEquals(false, body["active"]?.booleanValue)
        assertEquals(40.0, body["offset"]?.doubleValue)
        assertNull(body["sessionId"])
        assertFailsWith<IllegalArgumentException> { GatewayRequests.hostScheduleList("r", limit = 501) }
        assertFailsWith<IllegalArgumentException> { GatewayRequests.scheduleDeleteInactive("r", false) }
    }

    @Test
    fun pluginUpdateCorrelatesFinalResultAndRetainsRestoreFailure() {
        val request = GatewayRequests.pluginUpdate("r", "pkg", "1.2.3")
        assertEquals("plugin-update-result", request.responseKind)
        assertEquals(JsonValue.NullValue, payload(request.payload)["registry"])
        val result = GatewayWireDecoder.decode("""{"kind":"plugin-update-result","requestId":"r","result":{"from":"1","to":"2","result":{"application":"failed"},"restore":{"application":"failed"}}}""")
        assertEquals("failed", result.result?.get("restore")?.get("application")?.stringValue)
        assertFailsWith<IllegalArgumentException> { GatewayRequests.pluginVersions("", "pkg") }
    }

    @Test
    fun queueEditQuotesDoNotRemoveAttachments() {
        val body = payload(GatewayRequests.queueEdit("s", "item", "r", "", listOf(GatewayTextPart("quote", "原文"))).payload)
        assertEquals("edit", body["action"]?.stringValue)
        assertNull(body["text"])
        assertNull(body["images"])
        assertNull(body["files"])
    }
}
