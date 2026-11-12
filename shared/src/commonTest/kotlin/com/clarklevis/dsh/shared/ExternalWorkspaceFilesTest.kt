package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileStore
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalWorkspaceFilesTest {
    @Test
    fun previewsExternalPosixFileWithoutInventingCacheVersion() {
        verifiesExternalDownload("/tmp/deliver/底部图标 前后.png", "/tmp/deliver/底部图标 前后.png")
    }

    @Test
    fun previewsExternalWindowsFileWithCanonicalSlashes() {
        verifiesExternalDownload("D:\\DSH\\04-临时\\deliver\\底部图标 前后.png", "D:/DSH/04-临时/deliver/底部图标 前后.png")
    }

    @Test
    fun previewsExternalUncFile() {
        verifiesExternalDownload("\\\\server\\share\\deliver\\前后.png", "//server/share/deliver/前后.png")
    }

    @Test
    fun downloadsExternalFileAndKeepsChildWorkspaceBinding() {
        val store = SharedWorkspaceFileStore()
        store.setWorkspaceSession("child", "parent")
        val request = store.download("child", "/tmp/result.png", "open", "download")
        assertTrue(request.request!!.payload.contains("\"sessionId\":\"parent\""))
        val opened = store.acceptFrame(opened("parent", "/tmp/result.png", "open"))
        assertEquals("child", opened.snapshot.activeDownload?.sessionId)
        val done = store.acceptFrame(finalChunk)
        assertEquals("download", done.completion?.purpose)
        assertEquals("child", done.completion?.sessionId)
        assertEquals("/tmp/result.png", done.completion?.path)
    }

    @Test
    fun continuesToRejectExternalDirectoryListingAndTraversal() {
        for (path in listOf("/tmp/file", "D:/deliver/file", "../file", "/tmp/../file", "D:\\deliver\\..\\file")) {
            assertFailsWith<IllegalArgumentException>(path) { SharedWorkspaceFileStore().load("s1", path, "list") }
        }
        for (path in listOf("/tmp/../secret", "D:/deliver/../secret", "/tmp//secret", "/", "D:/", "bad\u0000name")) {
            assertFailsWith<IllegalArgumentException>(path) { SharedWorkspaceFileStore().download("s1", path, "open", "download") }
        }
        val store = SharedWorkspaceFileStore()
        store.load("s1", null, "list")
        val result = store.acceptFrame("""{"kind":"file-list","requestId":"list","sessionId":"s1","path":".","entries":[{"kind":"file","name":"file","path":"/tmp/file","bytes":5}]}""")
        assertEquals("file-entry-invalid", result.snapshot.lastError)
    }

    @Test
    fun rejectsDifferentOpenedFileAndIgnoresOtherSessionResolution() {
        val store = SharedWorkspaceFileStore()
        store.resolve("s1", "/tmp/result.png", "resolve", true)
        assertNull(store.acceptFrame(resolved("other", "/tmp/result.png")).request)
        assertNotNull(store.acceptFrame(resolved("s1", "/tmp/result.png")).request)
        val result = store.acceptFrame(opened("s1", "/tmp/other.png", "resolve-download"))
        assertEquals("file-download-open-invalid", result.snapshot.lastError)
        assertNull(result.snapshot.activeDownload)
    }

    @Test
    fun preservesMachineReadableHostErrorsForLocalizedMessages() {
        val store = SharedWorkspaceFileStore()
        store.resolve("s1", "/tmp/result.png", "resolve", true)
        val result = store.acceptFrame("""{"kind":"error","requestType":"file-resolve","requestId":"resolve","sessionId":"s1","code":"file-not-allowed","message":"path must stay inside the session working directory"}""")
        assertEquals("file-not-allowed", result.snapshot.lastError)
    }

    private fun verifiesExternalDownload(input: String, canonical: String) {
        val store = SharedWorkspaceFileStore()
        val request = store.resolveReference("s1", input, "resolve", true, "/work")
        assertTrue(request.request!!.payload.contains(JsonPrimitive(input).toString()))
        val resolved = store.acceptFrame(resolved("s1", input))
        assertNull(resolved.snapshot.lastError)
        assertNull(resolved.cacheLookup)
        assertTrue(resolved.request!!.payload.contains(JsonPrimitive(canonical).toString()))
        val opened = store.acceptFrame(opened("s1", canonical, "resolve-download"))
        assertNotNull(opened.request)
        val done = store.acceptFrame(finalChunk)
        assertNull(done.snapshot.lastError)
        assertEquals(canonical, done.completion?.path)
        assertEquals("preview", done.completion?.purpose)
        assertNull(done.completion?.modifiedAt)
        assertNull(done.snapshot.activeDownload)
    }

    private fun resolved(session: String, path: String) = """{"kind":"file-resolved","requestId":"resolve","sessionId":"$session","path":${JsonPrimitive(path)},"name":"result.png","size":5}"""

    private fun opened(session: String, path: String, request: String) = """{"kind":"file-download-opened","requestId":"$request","transferId":"t1","sessionId":"$session","path":${JsonPrimitive(path)},"name":"result.png","size":5,"chunkBytes":5}"""

    private val finalChunk = """{"kind":"file-download-chunk","transferId":"t1","offset":0,"data":"aGVsbG8=","eof":true,"sha256":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"}"""
}
