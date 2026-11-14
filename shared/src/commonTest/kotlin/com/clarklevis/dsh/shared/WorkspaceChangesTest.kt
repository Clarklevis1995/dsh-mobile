package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedWorkspaceChangesStore
import com.clarklevis.dsh.shared.facade.WorkspaceReviewPresentation
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.protocol.*
import kotlin.test.*

class WorkspaceChangesTest {
    @Test fun reviewAbsolutePathsUseHostWorkspaceWithoutDecodingFileNames() {
        assertEquals("/Users/demo/project/src/前 后%20.swift", WorkspaceReviewPresentation.absolutePath("./src/前 后%20.swift", "/Users/demo/project/"))
        assertEquals("/tmp/outside.txt", WorkspaceReviewPresentation.absolutePath("/tmp/outside.txt", "/project"))
        assertEquals("C:/DSH/project/src/app.js", WorkspaceReviewPresentation.absolutePath("src\\app.js", "C:\\DSH\\project\\"))
        assertEquals("D:/outside/app.js", WorkspaceReviewPresentation.absolutePath("D:\\outside\\app.js", null))
        assertEquals("//server/share/app.js", WorkspaceReviewPresentation.absolutePath("\\\\server\\share\\app.js", null))
        assertEquals("/app.js", WorkspaceReviewPresentation.absolutePath("app.js", "/"))
        assertNull(WorkspaceReviewPresentation.absolutePath("app.js", null))
        assertNull(WorkspaceReviewPresentation.absolutePath("app.js", "relative-root"))
    }

    @Test fun reviewSeparatesMarkersAndPreservesCodeIndentationAndLogicalNumbers() {
        val diff = WorkspaceChangesDiff("text", "app.js", "app.js", hunks = listOf(
            WorkspaceDiffHunk(8, 2, 8, 3, listOf(" const ready = true;", "-  old();", "+  next();", "+  +value;")),
            WorkspaceDiffHunk(13, 1, 14, 1, listOf(" "))
        ))
        val rows = WorkspaceReviewPresentation.rows(diff)
        assertEquals(listOf(8, 9, 9, 10, 14), rows.mapNotNull { it.lineNumber })
        assertEquals("  +value;", rows.single { it.lineNumber == 10 }.text)
        assertEquals("  old();", rows.single { it.kind == "deleted" }.text)
        assertEquals("", rows.last().text)
        assertEquals("省略 3 行未修改内容", rows.single { it.kind == "omitted" }.text)
        assertEquals("第 8–10 行", rows.first().text)
    }

    @Test fun deletedOnlyReviewUsesOriginalLineRange() {
        val diff = WorkspaceChangesDiff("text", "a.md", "a.md", hunks = listOf(
            WorkspaceDiffHunk(51, 2, 51, 0, listOf("-a", "-b"))
        ))
        val rows = WorkspaceReviewPresentation.rows(diff)
        assertEquals("第 51–52 行", rows.first().text)
        assertEquals(listOf(51, 52), rows.mapNotNull { it.lineNumber })
    }
    private fun event(seq: Int = 160, turn: Int = 3, session: String = "s1") = GatewayFrame(
        kind = "event", sessionId = session, seq = seq, event = GatewayEvent("workspace/changes", turn = turn))

    @Test fun liveHistoryAndSnapshotShareCoordinates() {
        for (kind in listOf("event", "history", "session-snapshot")) {
            val store = SharedWorkspaceChangesStore()
            store.open("s1", true, true)
            val frame = if (kind == "event") event() else GatewayWireDecoder.decode(
                """{"kind":"$kind","sessionId":"s1","events":[{"type":"workspace/changes","seq":160,"time":1,"data":{"turn":3}}]}""")
            val first = store.accept(frame)
            assertEquals(160, first.snapshot.seq)
            assertEquals(3, first.snapshot.turn)
            assertEquals(1, first.requests.size)
            assertTrue(store.accept(frame).requests.isEmpty())
            assertTrue(store.accept(event(120, 2)).requests.isEmpty())
            assertEquals(160, store.snapshot().seq)
        }
    }

    @Test fun staleResponsesCannotCrossSessionsOrReplaceNewCoordinates() {
        val store = SharedWorkspaceChangesStore()
        store.open("s1", true, true)
        val request = store.accept(event()).requests.single()
        store.open("s2", true, true)
        val summary = WorkspaceChangesSummary(3, 2, 10, 2, emptyList())
        store.accept(GatewayFrame("workspace-changes-summary", sessionId = "s1", seq = 160,
            requestId = request.correlationId, summary = summary))
        assertNull(store.snapshot().summary)
        store.open("s1", true, true)
        val newer = store.accept(event(170)).requests.single()
        store.accept(GatewayFrame("workspace-changes-summary", sessionId = "s1", seq = 160,
            requestId = newer.correlationId, summary = summary))
        assertNull(store.snapshot().summary)
    }

    @Test fun missingIsErrorAndRetryPreservesOriginalFileIndex() {
        val store = SharedWorkspaceChangesStore()
        store.open("s1", true, true)
        val request = store.accept(event()).requests.single()
        store.accept(GatewayFrame("error", sessionId = "s1", seq = 160, requestId = request.correlationId,
            code = "workspace-changes-missing", requestType = request.requestType))
        assertNull(store.snapshot().summary)
        assertEquals("workspace-changes-missing", store.snapshot().error)
        val retry = store.retrySummary().requests.single()
        store.accept(GatewayFrame("workspace-changes-summary", sessionId = "s1", seq = 160,
            requestId = retry.correlationId, summary = WorkspaceChangesSummary(3, 9, 10, 2, listOf(
                WorkspaceChangedFile("z", "z", 1, 1), WorkspaceChangedFile("a", "a", 9, 1)))))
        val diffRequest = store.selectFile(1).requests.single()
        assertEquals(1, wireJson.parseToJsonElement(diffRequest.payload).let { (it as kotlinx.serialization.json.JsonObject)["index"].toString().toInt() })
        assertEquals(9, store.snapshot().summary?.total)
        store.closeDiff()
        store.accept(GatewayFrame("workspace-changes-diff", sessionId = "s1", seq = 160, index = 1,
            requestId = diffRequest.correlationId, diff = WorkspaceChangesDiff("binary", "a", "a")))
        assertNull(store.snapshot().diff)
    }

    @Test fun rowNumbersRespectHunksAndPreserveWhitespace() {
        val diff = WorkspaceChangesDiff("text", "x", "x", hunks = listOf(
            WorkspaceDiffHunk(4, 2, 7, 3, listOf(" \t中🙂", "-  old ", "+  new ", "+")),
            WorkspaceDiffHunk(40, 1, 60, 1, listOf(" next"))))
        val rows = diff.rows()
        assertEquals(listOf(null, 4, 5, null, null, null, 40), rows.map { it.oldLine })
        assertEquals(listOf(null, 7, null, 8, 9, null, 60), rows.map { it.newLine })
        assertEquals(" \t中🙂", rows[1].text)
        assertEquals("+  new ", rows[3].text)
        assertEquals("+", rows[4].text)
    }

    @Test fun disconnectEndsLoadingAndLateResponsesCannotCommit() {
        val store = SharedWorkspaceChangesStore()
        store.open("s1", true, true)
        val request = store.accept(event()).requests.single()
        store.open("s1", true, false)
        assertEquals("disconnected", store.snapshot().error)
        assertFalse(store.snapshot().loading)
        store.accept(GatewayFrame("workspace-changes-summary", sessionId = "s1", seq = 160,
            requestId = request.correlationId, summary = WorkspaceChangesSummary(3, 0, 0, 0, emptyList())))
        assertNull(store.snapshot().summary)
        assertEquals(1, store.open("s1", true, true).requests.size)
    }

    @Test fun unsupportedGatewayDoesNotQueryAndTimeoutAllowsRetry() {
        val store = SharedWorkspaceChangesStore()
        store.open("s1", false, true)
        assertTrue(store.accept(event()).requests.isEmpty())
        assertFalse(store.snapshot().visible)
        val request = store.open("s1", true, true).requests.single()
        store.requestFailed(requireNotNull(request.correlationId))
        assertFalse(store.snapshot().loading)
        assertEquals(1, store.retrySummary().requests.size)
        assertFailsWith<IllegalArgumentException> { GatewayRequests.workspaceChanges("s1", -1, null, "x") }
    }
}
