package whaleness

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

class UploadSupportTest {
    private val engine = HarmonyEngine()
    private val hash = "a".repeat(64)

    private fun command(value: HarmonyCommand): HarmonyResult = wireJson.decodeFromString(engine.dispatch(wireJson.encodeToString(value)))

    private fun frame(raw: String) = command(HarmonyCommand("frame", frame = raw))

    private fun payload(value: HarmonyResult) = wireJson.parseToJsonElement(value.outbound.single().text).jsonObject

    private fun ready(): HarmonyResult {
        command(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1/ws/mobile"))
        frame("""{"kind":"hello","authenticated":true,"capabilities":["file-upload-v1"]}""")
        return command(HarmonyCommand("select", sessionId = "s"))
    }

    private fun add(size: Long = 0) =
        command(HarmonyCommand("file-add", sessionId = "s", itemId = "local", fileName = "中文 空格.txt", byteSize = size, sha256 = hash))

    private fun opened(
        value: HarmonyResult,
        size: Long = 0,
    ): HarmonyResult =
        frame(
            """{"kind":"file-upload-opened","sessionId":"s","requestId":"${payload(
                value,
            )["requestId"]!!.jsonPrimitive.content}","uploadId":"upload","size":$size,"chunkBytes":65536}""",
        )

    private fun completed(value: HarmonyResult) =
        frame(
            """{"kind":"file-upload-completed","sessionId":"s","requestId":"${payload(
                value,
            )["requestId"]!!.jsonPrimitive.content}","uploadId":"upload","receiptId":"receipt","sha256":"$hash","file":{"attachmentId":"attachment","name":"中文 空格.txt","bytes":0,"path":".attachments/file"}}""",
        )

    @Test fun zeroByteUploadRequiresMatchingReceiptBeforeAttachmentOnlySendAndAcknowledgement() {
        ready()
        val finish = opened(add())
        assertEquals("file-upload-finish", payload(finish)["type"]!!.jsonPrimitive.content)
        val uploaded = completed(finish)
        assertTrue(uploaded.uploads!!.canSend)
        val sent = command(HarmonyCommand("send"))
        assertTrue(sent.ok)
        val body = payload(sent)
        assertEquals(
            "receipt",
            body["files"]!!
                .jsonArray
                .single()
                .jsonObject["receiptId"]!!
                .jsonPrimitive.content,
        )
        assertTrue(sent.state!!.pendingRequest)
        assertNull(frame("""{"kind":"sent","sessionId":"s"}""").uploads)
        val acknowledged = frame("""{"kind":"sent","sessionId":"s","requestId":"${body["requestId"]!!.jsonPrimitive.content}"}""")
        assertTrue(acknowledged.uploads!!.drafts.isEmpty())
        assertTrue(acknowledged.sent)
    }

    @Test fun chunkReadUsesExactOffsetLengthAndRejectsIncorrectServerAcknowledgement() {
        ready()
        val next = opened(add(3), 3)
        val read = next.uploadRead!!
        assertEquals(0, read.offset)
        assertEquals(3, read.length)
        val chunk = command(HarmonyCommand("file-chunk", sessionId = "s", requestKey = read.token, text = "YWJj", byteCount = 3))
        assertEquals("file-upload-chunk", payload(chunk)["type"]!!.jsonPrimitive.content)
        val bad =
            frame(
                """{"kind":"file-upload-chunk-accepted","sessionId":"s","requestId":"${payload(
                    chunk,
                )["requestId"]!!.jsonPrimitive.content}","uploadId":"upload","nextOffset":1}""",
            )
        assertEquals(
            "error",
            bad.uploads!!
                .drafts
                .single()
                .status,
        )
        assertFalse(bad.uploads!!.canSend)
        assertTrue(bad.outbound.all { it.text.contains("file-upload-cancel") })
    }

    @Test fun staleReadAndTimerCannotAdvanceNewSessionOrRequest() {
        ready()
        val open = add(3)
        val oldKey = open.state!!.pendingOperations.single { it.startsWith("uploads:") }
        val next = opened(open, 3)
        assertNull(command(HarmonyCommand("operation-timeout", requestKey = oldKey)).uploads)
        command(HarmonyCommand("select", sessionId = "other"))
        val stale =
            command(HarmonyCommand("file-chunk", sessionId = "s", requestKey = next.uploadRead!!.token, text = "YWJj", byteCount = 3))
        assertFalse(stale.ok)
        assertTrue(stale.outbound.isEmpty())
    }

    @Test fun onlyFinishMayRetryAutomaticallyAndUsesItsDeclaredTimeout() {
        ready()
        val finish = opened(add())
        val request = payload(finish)
        val key = finish.state!!.pendingOperations.single { it.startsWith("uploads:") }
        assertEquals(60_000, finish.state!!.operationTimeouts[key])
        val retry = command(HarmonyCommand("operation-timeout", requestKey = key))
        assertEquals("file-upload-finish", payload(retry)["type"]!!.jsonPrimitive.content)
        assertTrue(request["requestId"] != payload(retry)["requestId"])
        assertNull(command(HarmonyCommand("operation-timeout", requestKey = key)).uploads)
    }

    @Test fun invalidReceiptAndDisconnectRequireExplicitReupload() {
        ready()
        completed(opened(add()))
        val sent = command(HarmonyCommand("send", text = "附件"))
        val rid = payload(sent)["requestId"]!!.jsonPrimitive.content
        val failed =
            frame(
                """{"kind":"error","requestType":"message","sessionId":"s","requestId":"$rid","code":"session/attachment-invalid","message":"附件凭证已失效"}""",
            )
        assertEquals(
            "error",
            failed.uploads!!
                .drafts
                .single()
                .status,
        )
        assertFalse(failed.uploads!!.canSend)
        val disconnected = command(HarmonyCommand("disconnect"))
        assertFalse(disconnected.uploads!!.available)
    }

    @Test fun unsupportedHostAndInvalidMetadataNeverOpenAnUpload() {
        command(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1/ws/mobile"))
        frame("""{"kind":"hello","authenticated":true}""")
        command(HarmonyCommand("select", sessionId = "s"))
        assertFalse(add().ok)
        ready()
        val invalid =
            command(HarmonyCommand("file-add", sessionId = "s", itemId = "bad", fileName = "../escape", byteSize = 1, sha256 = hash))
        assertFalse(invalid.ok)
        assertTrue(invalid.outbound.isEmpty())
    }
}
