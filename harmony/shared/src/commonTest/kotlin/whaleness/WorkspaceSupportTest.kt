package whaleness

import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceSupportTest {
    private val engine = HarmonyEngine()

    private fun command(value: HarmonyCommand): HarmonyResult = wireJson.decodeFromString(engine.dispatch(wireJson.encodeToString(value)))

    private fun frame(raw: String) = command(HarmonyCommand("frame", frame = raw))

    private fun payload(result: HarmonyResult) = wireJson.parseToJsonElement(result.outbound.single().text).jsonObject

    private fun ready() {
        command(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1/ws/mobile"))
        frame("""{"kind":"hello","authenticated":true,"capabilities":["file-downloads"]}""")
        command(HarmonyCommand("select", sessionId = "s"))
    }

    private fun open(): HarmonyResult {
        val request = command(HarmonyCommand("files-resolve", sessionId = "s", path = "中文 空格.txt"))
        val id = payload(request)["requestId"]!!.jsonPrimitive.content
        return frame(
            """{"kind":"file-download-opened","requestId":"$id","transferId":"transfer","sessionId":"s","path":"中文 空格.txt","name":"中文 空格.txt","mediaType":"text/plain","size":5,"chunkBytes":3}""",
        )
    }

    private fun ack(
        result: HarmonyResult,
        outcome: String = "ok",
    ) = command(HarmonyCommand("files-write-complete", sessionId = "s", requestKey = result.fileIo!!.token, outcome = outcome))

    @Test fun downloadCannotReadAheadOfDiskAndPublishesOnlyAfterFinalCommit() {
        ready()
        val opened = open()
        assertEquals("create", opened.fileIo!!.operation)
        assertTrue(opened.outbound.isEmpty())
        val read = ack(opened)
        assertEquals("file-download-read", payload(read)["type"]!!.jsonPrimitive.content)
        val first = frame("""{"kind":"file-download-chunk","transferId":"transfer","offset":0,"data":"aGVs","eof":false}""")
        assertEquals(3, first.fileIo!!.length)
        assertTrue(first.outbound.isEmpty())
        val next = ack(first)
        assertEquals("3", payload(next)["offset"]!!.jsonPrimitive.content)
        val last =
            frame(
                """{"kind":"file-download-chunk","transferId":"transfer","offset":3,"data":"bG8=","eof":true,"sha256":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"}""",
            )
        assertEquals(2, last.fileIo!!.length)
        assertNull(last.fileCompletion)
        val finalWrite = ack(last)
        assertEquals("finish", finalWrite.fileIo!!.operation)
        assertNull(finalWrite.fileCompletion)
        val complete = ack(finalWrite)
        assertEquals("中文 空格.txt", complete.fileCompletion!!.name)
        assertEquals(5, complete.fileCompletion!!.size)
        assertFalse(complete.workspaceFiles!!.busy)
    }

    @Test fun listUsesRequestIdentityAndLateDirectoryCannotReplaceCurrentState() {
        ready()
        val request = command(HarmonyCommand("files-open", sessionId = "s"))
        val id = payload(request)["requestId"]!!.jsonPrimitive.content
        frame("""{"kind":"file-list","requestId":"old","sessionId":"s","path":"wrong","entries":[]}""")
        val loaded =
            frame(
                """{"kind":"file-list","requestId":"$id","sessionId":"s","path":".","entries":[{"name":"中文 空格.txt","path":"中文 空格.txt","kind":"file","bytes":5}]}""",
            )
        assertEquals(".", loaded.workspaceFiles!!.path)
        assertEquals(
            "中文 空格.txt",
            loaded.workspaceFiles!!
                .entries
                .single()
                .name,
        )
        assertFalse(loaded.workspaceFiles!!.busy)
    }

    @Test fun diskFailureCancelsTransferAndRejectsLateWriteAcknowledgement() {
        ready()
        val opened = open()
        val failed = ack(opened, "failed")
        assertEquals("transfer", failed.fileDiscard)
        assertTrue(failed.workspaceFiles!!.error!!.contains("写入失败"))
        assertTrue(failed.outbound.all { it.text.contains("file-download-cancel") })
        val old = ack(opened)
        assertNull(old.fileIo)
        assertNull(old.fileCompletion)
        assertTrue(old.outbound.isEmpty())
    }

    @Test fun incorrectHashDiscardsPrivateCopyWithoutMakingPreviewAvailable() {
        ready()
        ack(open())
        val invalid =
            frame(
                """{"kind":"file-download-chunk","transferId":"transfer","offset":0,"data":"aGVsbG8=","eof":true,"sha256":"${"0".repeat(
                    64,
                )}"}""",
            )
        assertNull(invalid.fileCompletion)
        assertNull(invalid.fileIo)
        assertEquals("transfer", invalid.fileDiscard)
        assertTrue(invalid.workspaceFiles!!.error!!.contains("完整性"))
    }

    @Test fun changingSessionCancelsDiskTicketAndOldTimerDoesNotCancelNewRead() {
        ready()
        val opened = open()
        val key = opened.fileIo!!.token
        val read = ack(opened)
        assertTrue(read.outbound.isNotEmpty())
        assertTrue(command(HarmonyCommand("operation-timeout", requestKey = key)).outbound.isEmpty())
        val switched = command(HarmonyCommand("select", sessionId = "other"))
        assertEquals("transfer", switched.fileDiscard)
        val old = ack(opened)
        assertNull(old.fileCompletion)
        assertNull(old.fileIo)
        assertTrue(old.outbound.isEmpty())
    }

    @Test fun encodedControlsAreNotAcceptedAsWorkspaceLinks() {
        val bad: HarmonyResult =
            wireJson.decodeFromString(
                engine.dispatch(wireJson.encodeToString(HarmonyCommand("render-link", text = "file:///tmp/a%0Ab.txt"))),
            )
        assertEquals("invalid", bad.presentation!!.linkKind)
    }
}
