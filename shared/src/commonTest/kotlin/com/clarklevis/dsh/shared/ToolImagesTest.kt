package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.projection.ConversationItemKind
import com.clarklevis.dsh.shared.projection.ConversationProjector
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.RawSessionEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolImagesTest {
    private val image = GatewayImageAttachment("sha256:one", "image/png", 100, 1536, 1024)
    private val imageJson = """{"attachmentId":"sha256:one","mediaType":"image/png","bytes":100,"width":1536,"height":1024}"""
    private fun raw(type: String, data: String, seq: Int = 1) = Json.decodeFromString<RawSessionEvent>(
        """{"type":"$type","seq":$seq,"time":1,"data":$data}"""
    ).normalized("session")
    private fun record(seq: Int, event: GatewayEvent) = SessionEvent("session", seq, 1.0, event)

    @Test fun oldGatewayAndDisabledPluginKeepExistingRendering() {
        val events = listOf(
            record(1, GatewayEvent("user/message", text = "hello", images = listOf(image))),
            raw("tool/result", """{"message":{"content":[{"type":"text","text":"result"}]}}""", 2),
            record(3, GatewayEvent("assistant/chunk", turn = 1, step = 0, chunkType = "text-delta", text = "reply")),
            record(4, GatewayEvent("assistant/message", turn = 1, step = 0, text = "reply!"))
        )
        val items = ConversationProjector.make(events)
        assertEquals(listOf(ConversationItemKind.USER, ConversationItemKind.TOOL_RESULT, ConversationItemKind.ASSISTANT), items.map { it.kind })
        assertEquals("result", items[1].text)
        assertEquals("reply!", items.last().text)
        assertEquals(listOf(image), items.first().images)
    }

    @Test fun rawNestedMetadataAndConversationHistoryRestoreImages() {
        for (data in listOf(
            """{"message":{"content":[{"type":"image","attachment":$imageJson}]}}""",
            """{"message":{"content":[{"type":"tool-result","content":[{"type":"image","attachment":$imageJson}]}]}}""",
            """{"meta":{"kind":"dsh-image-gen-batch","images":[{"kind":"dsh-image-gen","attachment":$imageJson}]}}""",
            """{"images":[$imageJson,$imageJson]}"""
        )) {
            val items = ConversationProjector.make(listOf(raw("tool/result", data)))
            assertEquals(listOf(image), items.last().images)
            assertEquals(ConversationItemKind.GENERATED_IMAGES, items.last().kind)
        }
    }

    @Test fun ptcAndCodeDispatchNeedNeitherTurnNorAssistantText() {
        for (type in listOf("tool/ptc-dispatch", "tool/code-dispatch")) {
            val event = raw(type, """{"rootCallId":"run","content":[{"type":"image","attachment":$imageJson}]}""")
            val projector = ConversationProjector()
            projector.fold(listOf(event))
            projector.fold(listOf(event))
            assertEquals(1, projector.items.size)
            assertEquals(listOf(image), projector.items.single().images)
            assertEquals(projector.items, ConversationProjector.make(listOf(event)))
        }
    }

    @Test fun malformedOptionalImagesDoNotDropToolTextOrAssistantText() {
        for (type in listOf("tool/result", "assistant/message")) {
            val frame = GatewayWireDecoder.decode("""{"kind":"event","sessionId":"session","seq":1,"time":1,"event":{"type":"$type","text":"reply","preview":"output","images":[{},null,{"attachmentId":"bad"},$imageJson]}}""")
            assertEquals("reply", frame.event?.text)
            assertEquals("output", frame.event?.preview)
            assertEquals(listOf(image), frame.event?.images)
        }
    }

    @Test fun failedResultsAndInvalidReferencesDoNotProduceImages() {
        for (data in listOf(
            """{"isError":true,"images":[$imageJson]}""",
            """{"error":{"message":"failed"},"images":[$imageJson]}""",
            """{"message":{"isError":true,"content":[{"type":"image","attachment":$imageJson}]}}""",
            """{"images":[{"attachmentId":"x","mediaType":"image/svg+xml","bytes":1,"width":1,"height":1}]}"""
        )) assertTrue(ConversationProjector.make(listOf(raw("tool/result", data))).none { it.kind == ConversationItemKind.GENERATED_IMAGES })
    }

    @Test fun legacyRealtimeInjectionWithoutCallIdDeduplicatesOnlyInsideCurrentUserTurn() {
        val items = ConversationProjector.make(listOf(
            record(1, GatewayEvent("tool/result", callId = "generate", images = listOf(image))),
            record(2, GatewayEvent("user/message", source = "tool", images = listOf(image))),
            record(3, GatewayEvent("user/message", text = "next turn")),
            record(4, GatewayEvent("user/message", source = "tool", images = listOf(image)))
        ))
        assertEquals(2, items.count { it.kind == ConversationItemKind.GENERATED_IMAGES })
    }

    @Test fun internalToolInjectionDeduplicatesByCallAndKeepsRealUserUpload() {
        val tool = record(1, GatewayEvent("tool/ptc-dispatch", rootCallId = "run", images = listOf(image)))
        val injected = raw("user/message", """{"source":{"kind":"tool","callId":"run"},"content":[{"type":"image","attachment":$imageJson}]}""", 2)
        val user = record(3, GatewayEvent("user/message", images = listOf(image)))
        val items = ConversationProjector.make(listOf(tool, injected, user))
        assertEquals(2, items.count { it.images.isNotEmpty() })
        assertEquals(ConversationItemKind.USER, items.last().kind)
        val fallback = ConversationProjector()
        fallback.fold(listOf(injected))
        assertEquals(listOf(image), fallback.items.single().images)
        fallback.fold(listOf(tool.copy(seq = 4)))
        assertEquals(1, fallback.items.size)
        assertEquals("session-4-images", fallback.items.single().id)
    }
}
