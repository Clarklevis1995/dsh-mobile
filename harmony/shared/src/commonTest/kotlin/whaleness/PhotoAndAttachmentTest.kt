package whaleness

import com.clarklevis.dsh.shared.gateway.GatewayAttachmentPayload
import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationItemKind
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoAndAttachmentTest {
    private val engine = HarmonyEngine()

    private fun command(c: HarmonyCommand): HarmonyResult = wireJson.decodeFromString(engine.dispatch(wireJson.encodeToString(c)))

    private fun ready() {
        command(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1/ws/mobile"))
        command(HarmonyCommand("frame", frame = """{"kind":"hello","authenticated":true}"""))
        command(HarmonyCommand("select", sessionId = "s"))
    }

    private fun photo(
        id: String = "p",
        width: Int = 2,
    ) = command(
        HarmonyCommand(
            "image-add",
            sessionId = "s",
            itemId = id,
            fileName = "中文.jpg",
            byteSize = 4,
            sha256 = "a".repeat(64),
            imageWidth = width,
            imageHeight = 3,
            input = "/9j/2Q==",
        ),
    )

    @Test fun photosUseImageProtocolWithoutRequiringFileUploadCapabilityAndClearOnReceipt() {
        ready()
        val prepared = photo()
        assertTrue(prepared.uploads!!.canSend)
        assertEquals(1, prepared.uploads!!.photos.size)
        val sent = command(HarmonyCommand("send"))
        assertTrue(sent.ok)
        val message = wireJson.parseToJsonElement(sent.outbound.single().text).jsonObject
        assertEquals(
            "image/jpeg",
            message["images"]!!
                .jsonArray
                .single()
                .jsonObject["mediaType"]!!
                .jsonPrimitive.content,
        )
        assertFalse(message.containsKey("files"))
        val ack =
            command(
                HarmonyCommand(
                    "frame",
                    frame = """{"kind":"sent","sessionId":"s","requestId":"${message["requestId"]!!.jsonPrimitive.content}"}""",
                ),
            )
        assertTrue(ack.uploads!!.photos.isEmpty())
    }

    @Test fun malformedSizeAndFifthPhotoCannotMutateDraftsOrSendData() {
        ready()
        assertFalse(photo(width = 0).ok)
        repeat(4) { assertTrue(photo("p$it").ok) }
        val extra = photo("extra")
        assertFalse(extra.ok)
        assertTrue(extra.outbound.isEmpty())
        val switched = command(HarmonyCommand("select", sessionId = "other"))
        assertTrue(switched.uploads!!.photos.isEmpty())
    }

    private val image = GatewayImageAttachment("asset", "image/png", 3, 1, 1, "测试.png")

    private fun items() =
        listOf(ConversationItem("message", ConversationItemKind.USER, "你", "", images = listOf(image), epochSeconds = 0.0))

    @Test fun imageResponseRequiresCurrentSessionMetadataAndValidatedPayload() {
        val support = AttachmentSupport()
        support.open("s", "asset", items())
        assertNull(support.accept(GatewayFrame(kind = "attachment", sessionId = "other", attachment = image, data = "YWJj")))
        val wrong = support.accept(GatewayFrame(kind = "attachment", sessionId = "s", attachment = image.copy(bytes = 2), data = "YWI="))
        assertEquals("error", wrong!!.view!!.status)
        support.open("s", "asset", items())
        assertEquals(
            "ready",
            support.accept(GatewayFrame(kind = "attachment", sessionId = "s", attachment = image, data = "YWJj"))!!.view!!.status,
        )
    }

    @Test fun staleImageTimerAndUnknownReferenceCannotConsumeNextRequest() {
        val support = AttachmentSupport()
        val old = support.open("s", "asset", items()).view!!.key
        support.timedOut(old)
        val next = support.open("s", "asset", items()).view!!.key
        assertNull(support.timedOut(old).view)
        assertEquals(listOf(next), support.pendingKeys())
        support.reset()
        assertTrue(support.pendingKeys().isEmpty())
    }

    @Test fun sharedAttachmentValidatorRejectsOversizedEncodingBeforeDecoding() {
        assertNull(
            GatewayAttachmentPayload.validatedBytes(
                GatewayFrame(kind = "attachment", sessionId = "s", attachment = image.copy(bytes = 0), data = "YWJj"),
            ),
        )
        assertNull(
            GatewayAttachmentPayload.validatedBytes(GatewayFrame(kind = "attachment", sessionId = "s", attachment = image, data = "@@@=")),
        )
    }
}
