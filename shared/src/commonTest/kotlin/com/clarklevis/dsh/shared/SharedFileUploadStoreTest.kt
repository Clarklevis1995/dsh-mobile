package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedFileUploadStore
import com.clarklevis.dsh.shared.facade.SharedUploadTransition
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.projection.ConversationProjector
import com.clarklevis.dsh.shared.protocol.*
import kotlinx.serialization.decodeFromString
import kotlin.test.*

class SharedFileUploadStoreTest {
    @Test fun workspaceDraftReplacementKeepsMetadataButReissuesSessionBoundReceipts() {
        val store = store()
        val oldFinish = finishEmpty(store)
        complete(store, oldFinish)
        assertEquals(listOf("receipt-1"), store.receipts())
        val rebound = store.bind("s2", "connection-1", true)
        assertEquals("empty.zip", rebound.drafts.single().name)
        assertTrue(store.receipts().isEmpty())
        assertFalse(store.canSend())
        val next = store.retry("a")
        assertEquals("file-upload-open", next.requests.single().requestType)
        assertTrue(next.requests.single().payload.contains("s2"))
        complete(store, oldFinish)
        assertTrue(store.receipts().isEmpty(), "旧会话迟到的凭证不得进入新草稿")
    }
    private val digest = "7d1a54127b222502f5b79b5fb0803061152a44f92b37e23c6527baf665d4da9a"
    private fun store() = SharedFileUploadStore().apply { bind("s1", "connection-1", true) }
    private fun opened(store: SharedFileUploadStore, request: SharedUploadTransition, size: Long = 7, chunkBytes: Int = 3): SharedUploadTransition = store.accept(
        GatewayFrame("file-upload-opened", requestId = request.requests.single().correlationId,
            uploadId = "u1", sessionId = "s1", size = size, chunkBytes = chunkBytes)
    )
    private fun finishEmpty(store: SharedFileUploadStore): SharedUploadTransition {
        val request = store.add("a", "empty.zip", 0, digest)
        return opened(store, request, size = 0)
    }
    private fun complete(store: SharedFileUploadStore, request: SharedUploadTransition, size: Long = 0, receipt: String = "receipt-1") = store.accept(
        GatewayFrame("file-upload-completed", requestId = request.requests.single().correlationId,
            uploadId = "u1", sessionId = "s1", receiptId = receipt, sha256 = digest,
            file = PresentedFile(attachmentId = "opaque", name = "原始文件.zip", bytes = size))
    )

    @Test fun respectsServerChunkSizeAndAcknowledgedOffset() {
        val store = store()
        var next = opened(store, store.add("a", "binary.zip", 7, digest))
        assertEquals(3, next.read!!.length)
        assertEquals(0L, next.drafts.single().confirmedBytes)
        assertFalse(store.canSend())
        for ((offset, bytes) in listOf(0L to 3, 3L to 3, 6L to 1)) {
            val read = next.read!!
            assertEquals(offset, read.offset)
            assertEquals(bytes, read.length)
            val chunk = store.chunkReady(read.token, if (bytes == 3) "YWJj" else "Zw==", bytes)
            assertEquals(offset, chunk.drafts.single().confirmedBytes)
            assertNull(chunk.read)
            next = store.accept(GatewayFrame("file-upload-chunk-accepted",
                requestId = chunk.requests.single().correlationId, uploadId = "u1", sessionId = "s1", nextOffset = offset + bytes))
        }
        assertEquals("committing", next.drafts.single().status)
        assertFalse(store.canSend())
        complete(store, next, 7)
        assertTrue(store.canSend())
        assertEquals(listOf("receipt-1"), store.receipts())
    }

    @Test fun zeroByteFileSkipsChunksAndWaitsForCommit() {
        val store = store()
        val next = finishEmpty(store)
        assertNull(next.read)
        assertEquals("file-upload-finish", next.requests.single().requestType)
        assertFalse(store.canSend())
        complete(store, next)
        assertTrue(store.canSend())
    }

    @Test fun queueHasOnlyOneActiveFile() {
        val store = store()
        val first = store.add("a", "a.zip", 0, digest)
        assertTrue(store.add("b", "b.zip", 0, digest).requests.isEmpty())
        val finishing = opened(store, first, size = 0)
        val next = complete(store, finishing)
        assertEquals("file-upload-open", next.requests.single().requestType)
        assertEquals(listOf("ready", "opening"), next.drafts.map { it.status })
    }

    @Test fun invalidOffsetCancelsAndDoesNotAdvance() {
        val store = store()
        val read = opened(store, store.add("a", "a.pdf", 7, digest)).read!!
        val chunk = store.chunkReady(read.token, "YWJj", 3)
        val failed = store.accept(GatewayFrame("file-upload-chunk-accepted", requestId = chunk.requests.single().correlationId,
            uploadId = "u1", sessionId = "s1", nextOffset = 4))
        assertEquals("error", failed.drafts.single().status)
        assertEquals(0L, failed.drafts.single().confirmedBytes)
        assertEquals("file-upload-cancel", failed.requests.single().requestType)
    }

    @Test fun staleReadAndFrameAfterCancellationAreIgnored() {
        val store = store()
        val open = store.add("a", "a.pdf", 7, digest)
        val read = opened(store, open).read!!
        store.remove("a")
        assertTrue(store.chunkReady(read.token, "YWJj", 3).requests.isEmpty())
        assertTrue(opened(store, open).drafts.isEmpty())
    }

    @Test fun removingBeforeOpenAckCancelsTheLateUpload() {
        val store = store()
        val opening = store.add("a", "a.pdf", 7, digest)
        store.remove("a")
        val late = opened(store, opening)
        assertTrue(late.drafts.isEmpty())
        assertEquals("file-upload-cancel", late.requests.single().requestType)
        assertTrue(late.requests.single().payload.contains("u1"))
    }

    @Test fun completedReceiptSurvivesReconnectWithoutUploadingAgain() {
        val store = store()
        complete(store, finishEmpty(store))
        assertTrue(store.canSend())
        val disconnected = store.bind("s1", null, false)
        assertFalse(store.canSend())
        assertEquals("ready", disconnected.drafts.single().status)
        assertEquals("receipt-1", disconnected.drafts.single().receiptId)
        assertTrue(store.receipts().isEmpty(), "离线时不能发送，即使已保存完成凭证")
        assertTrue(disconnected.requests.isEmpty())
        val reconnected = store.bind("s1", "connection-2", true)
        assertTrue(reconnected.requests.isEmpty())
        assertTrue(store.canSend())
        assertEquals(listOf("receipt-1"), store.receipts())
    }

    @Test fun completedReceiptAndSubmissionIdentitySurviveDirectTransportReplacement() {
        val store = store()
        complete(store, finishEmpty(store))
        assertEquals("submission-1", store.submission("分析文件", "", "submission-1"))
        assertTrue(store.bind("s1", "connection-2", true).requests.isEmpty())
        assertEquals("submission-1", store.submission("分析文件", "", "submission-2"))
        store.bind("s1", null, false)
        store.bind("s1", "connection-3", true)
        assertEquals("submission-1", store.submission("分析文件", "", "submission-3"))
        assertEquals(listOf("receipt-1"), store.receipts())
    }

    @Test fun unfinishedUploadsRestartAutomaticallyAndRejectOldCallbacksAtEveryStage() {
        for (stage in listOf("opening", "reading", "chunk", "committing")) {
            val store = store()
            val open = store.add("a", "a.zip", if (stage == "committing") 0 else 7, digest)
            val work = when (stage) {
                "opening" -> open
                "committing" -> opened(store, open, size = 0)
                else -> opened(store, open)
            }
            val chunk = if (stage == "chunk") store.chunkReady(work.read!!.token, "YWJj", 3) else null
            assertEquals("waiting", store.bind("s1", null, false).drafts.single().status)
            assertTrue(store.bind("s1", null, false).requests.isEmpty(), "重复离线通知不能上传")
            val resumed = store.bind("s1", "connection-2", true)
            val newOpen = resumed.requests.single()
            assertEquals("file-upload-open", newOpen.requestType)
            assertNotEquals(open.requests.single().correlationId, newOpen.correlationId)
            assertEquals(0L, resumed.drafts.single().confirmedBytes)
            assertTrue(opened(store, open, size = if (stage == "committing") 0 else 7).requests.isEmpty())
            work.read?.let { assertTrue(store.chunkReady(it.token, "YWJj", 3).requests.isEmpty()) }
            chunk?.let {
                assertTrue(store.accept(GatewayFrame("file-upload-chunk-accepted", requestId = it.requests.single().correlationId,
                    uploadId = "u1", sessionId = "s1", nextOffset = 3)).requests.isEmpty())
            }
            if (stage == "committing") complete(store, work)
            assertEquals("opening", store.snapshot().single().status, "$stage 的旧回调不得影响新上传")
            assertTrue(store.receipts().isEmpty())
            assertTrue(store.bind("s1", "connection-2", true).requests.isEmpty(), "重复选择会话不能再次创建上传")
        }
    }

    @Test fun reconnectRestartsOnlyIncompleteFilesInTheQueue() {
        val store = store()
        val finishing = finishEmpty(store)
        store.add("b", "b.zip", 7, digest)
        store.add("c", "c.zip", 7, digest)
        complete(store, finishing)
        store.bind("s1", null, false)
        assertEquals(listOf("ready", "waiting", "waiting"), store.snapshot().map { it.status })
        val resumed = store.bind("s1", "connection-2", true)
        assertEquals(listOf("ready", "opening", "waiting"), resumed.drafts.map { it.status })
        assertEquals("receipt-1", resumed.drafts.first().receiptId)
        assertTrue(resumed.requests.single().payload.contains("b.zip"))
    }

    @Test fun invalidReceiptAutomaticallyReuploadsWithoutSendingAMessage() {
        val store = store()
        complete(store, finishEmpty(store))
        val renewed = store.invalidateReceipts()
        assertEquals("opening", renewed.drafts.single().status)
        assertEquals(listOf("file-upload-open"), renewed.requests.map { it.requestType })
        assertNull(renewed.drafts.single().receiptId)
        assertFalse(store.canSend())
        complete(store, opened(store, renewed, size = 0), receipt = "receipt-2")
        assertEquals(listOf("receipt-2"), store.receipts())
        assertTrue(store.canSend())
    }

    @Test fun capabilityRecoveryRetainsReceiptAndOfflineSessionSwitchInvalidatesIt() {
        val store = store()
        complete(store, finishEmpty(store))
        store.bind("s1", "connection-1", false)
        assertFalse(store.canSend())
        assertEquals("receipt-1", store.snapshot().single().receiptId)
        assertTrue(store.bind("s1", "connection-1", true).requests.isEmpty())
        assertTrue(store.canSend())
        store.bind("s1", null, false)
        store.bind("s2", null, false)
        store.bind("s1", "connection-2", true)
        assertNull(store.snapshot().single().receiptId, "切换会话后不能恢复此前会话的旧凭证")
        assertEquals("error", store.snapshot().single().status)
    }

    @Test fun genuineUploadErrorsAreNotRetriedByReconnect() {
        val store = store()
        val read = opened(store, store.add("a", "a.zip", 7, digest)).read!!
        store.readFailed(read.token, com.clarklevis.dsh.shared.i18n.UiMessage("upload.readFailed"))
        store.bind("s1", null, false)
        assertTrue(store.bind("s1", "connection-2", true).requests.isEmpty())
        assertEquals("error", store.snapshot().single().status)
        assertEquals("file-upload-open", store.retry("a").requests.single().requestType)
    }

    @Test fun switchSessionNeverSendsCancelOnAnotherSocket() {
        val store = store()
        opened(store, store.add("a", "a.pdf", 7, digest))
        assertTrue(store.bind("s2", "connection-2", true).requests.isEmpty())
        assertTrue(store.receipts().isEmpty())
    }

    @Test fun finishCanRetryAndAcceptEarlierCompletion() {
        val store = store()
        val finish = finishEmpty(store)
        val retry = store.timedOut(finish.requests.single().correlationId!!)
        assertEquals("file-upload-finish", retry.requests.single().requestType)
        assertNotEquals(finish.requests.single().correlationId, retry.requests.single().correlationId)
        val busy = store.accept(GatewayFrame("error", requestId = retry.requests.single().correlationId,
            requestType = "file-upload-finish", code = "file-upload-busy"))
        assertNotNull(busy.watch)
        assertEquals("committing", busy.drafts.single().status)
        complete(store, finish)
        assertTrue(store.canSend())
    }

    @Test fun chunkTimeoutNeverReplaysChunk() {
        val store = store()
        val read = opened(store, store.add("a", "a.pdf", 7, digest)).read!!
        val chunk = store.chunkReady(read.token, "YWJj", 3)
        val failed = store.timedOut(chunk.requests.single().correlationId!!)
        assertEquals("error", failed.drafts.single().status)
        assertEquals(listOf("file-upload-cancel"), failed.requests.map { it.requestType })
    }

    @Test fun checksumMismatchCannotBecomeReady() {
        val store = store()
        val finish = finishEmpty(store)
        val failed = store.accept(GatewayFrame("file-upload-completed", requestId = finish.requests.single().correlationId,
            uploadId = "u1", sessionId = "s1", receiptId = "r", sha256 = "0".repeat(64),
            file = PresentedFile(attachmentId = "opaque", name = "a", bytes = 0)))
        assertEquals("error", failed.drafts.single().status)
        assertFalse(store.canSend())
    }

    @Test fun invalidNameSizeAndDigestAreRejectedLocally() {
        val store = store()
        for (name in listOf("../a", "a\\b", ".", "..", "a\u0000b", "a".repeat(256))) {
            assertFailsWith<IllegalArgumentException> { store.add("a", name, 0, digest) }
        }
        assertFailsWith<IllegalArgumentException> { store.add("a", "a.zip", 536_870_913, digest) }
        assertFailsWith<IllegalArgumentException> { store.add("a", "a.zip", 0, "invalid") }
    }

    @Test fun submissionRetryKeepsRequestIdAndUsesReceiptsOnly() {
        val store = store()
        complete(store, finishEmpty(store))
        assertEquals("submission-1", store.submission("", "", "submission-1"))
        assertEquals("submission-1", store.submission("", "", "submission-2"))
        val payload = GatewayRequests.message("", emptyList(), "s1", null, "Asia/Shanghai",
            receipts = store.receipts(), requestId = "submission-1").payload
        assertTrue(payload.contains("\"files\":[{\"receiptId\":\"receipt-1\"}]"))
        assertFalse(payload.contains("attachmentId"))
        assertFalse(payload.contains("path"))
        assertEquals("submission-3", store.submission("new", "", "submission-3"))
    }

    @Test fun mixedMessageRetainsTextImagesAndReceipt() {
        val payload = GatewayRequests.message("分析附件", listOf(com.clarklevis.dsh.shared.gateway.GatewayOutgoingImage("image/png", "AA==")),
            "s1", null, "Asia/Shanghai", receipts = listOf("r1"), requestId = "mixed").payload
        val json = wireJson.decodeFromString<JsonValue>(payload)
        assertEquals("分析附件", json["text"]?.stringValue)
        assertEquals("AA==", json["images"]?.arrayValue?.single()?.get("data")?.stringValue)
        assertEquals("r1", json["files"]?.arrayValue?.single()?.get("receiptId")?.stringValue)
    }

    @Test fun invalidSessionResponseFailsWithoutHanging() {
        val store = store()
        val request = store.add("a", "a.pdf", 7, digest).requests.single()
        val failed = store.accept(GatewayFrame("file-upload-opened", requestId = request.correlationId,
            sessionId = "wrong", uploadId = "u1", size = 7, chunkBytes = 3))
        assertEquals("error", failed.drafts.single().status)
        assertFalse(store.canSend())
    }

    @Test fun pureFileHistoryAndLiveProjectToSameCards() {
        val history = wireJson.decodeFromString<RawSessionEvent>("""{"type":"user/message","seq":1,"time":1000,"data":{"content":[{"type":"file","attachment":{"attachmentId":"opaque","name":"报告.pdf","bytes":7}}],"source":{"kind":"user"}}}""")
        val live = GatewayWireDecoder.decode("""{"kind":"event","sessionId":"s1","seq":1,"time":1000,"event":{"type":"user/message","source":"user","files":[{"attachmentId":"opaque","name":"报告.pdf","bytes":7}]}}""")
        val fromHistory = ConversationProjector().apply { fold(listOf(history.normalized("s1"))) }.items.single()
        val fromLive = ConversationProjector().apply { fold(listOf(SessionEvent("s1", 1, 1000.0, live.event!!))) }.items.single()
        assertEquals(fromHistory, fromLive)
        assertEquals("报告.pdf", fromLive.files.single().name)
        assertTrue(fromLive.files.single().path.isEmpty())
        assertTrue(fromLive.text.isEmpty())
    }
}
