package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedWorkspaceFileStoreTest {
    @Test
    fun listsDirectoriesBeforeFilesAndBuildsNestedRequest() {
        val store = SharedWorkspaceFileStore()
        val start = store.load("s1", null, "list-1")
        assertTrue(start.request?.payload?.contains("\"type\":\"file-list\"") == true)

        val result = store.acceptFrame(
            """{"kind":"file-list","requestId":"list-1","sessionId":"s1","path":".","entries":[{"name":"z.txt","path":"z.txt","kind":"file","bytes":3,"mediaType":"text/plain"},{"name":"build","path":"build","kind":"directory"}]}"""
        )

        assertEquals(listOf("build", "z.txt"), result.snapshot.entries.map { it.name })
        val nested = store.load("s1", "build", "list-2")
        assertTrue(nested.request?.payload?.contains("\"path\":\"build\"") == true)
    }

    @Test
    fun downloadsChunksByExactOffsetAndVerifiesSha256() {
        val store = SharedWorkspaceFileStore()
        store.download("s1", "build/hello.txt", "open-1", "preview")
        val opened = store.acceptFrame(
            """{"kind":"file-download-opened","requestId":"open-1","transferId":"t1","sessionId":"s1","path":"build/hello.txt","name":"hello.txt","mediaType":"text/plain","size":5,"chunkBytes":3}"""
        )
        assertTrue(opened.request?.payload?.contains("\"offset\":0") == true)

        val first = store.acceptFrame(
            """{"kind":"file-download-chunk","transferId":"t1","offset":0,"data":"aGVs","eof":false}"""
        )
        assertEquals("aGVs", first.appendBase64Data)
        assertEquals(3, first.snapshot.activeDownload?.receivedBytes)
        assertTrue(first.request?.payload?.contains("\"offset\":3") == true)

        val final = store.acceptFrame(
            """{"kind":"file-download-chunk","transferId":"t1","offset":3,"data":"bG8=","eof":true,"sha256":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"}"""
        )
        assertEquals("hello.txt", final.completion?.name)
        assertEquals("preview", final.completion?.purpose)
        assertNull(final.snapshot.activeDownload)
        assertNull(final.snapshot.lastError)
    }

    @Test
    fun rejectsTraversalOffsetAndDigestMismatch() {
        val store = SharedWorkspaceFileStore()
        val invalidPath = runCatching { store.download("s1", "../secret", "o1", "preview") }
        assertTrue(invalidPath.isFailure)

        store.download("s1", "a.txt", "o2", "download")
        store.acceptFrame(
            """{"kind":"file-download-opened","requestId":"o2","transferId":"t2","sessionId":"s1","path":"a.txt","name":"a.txt","mediaType":"text/plain","size":1,"chunkBytes":1}"""
        )
        val badOffset = store.acceptFrame(
            """{"kind":"file-download-chunk","transferId":"t2","offset":1,"data":"YQ==","eof":true,"sha256":"ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"}"""
        )
        assertEquals("file-download-offset-mismatch", badOffset.snapshot.lastError)
        assertNotNull(badOffset.request)
        assertEquals("t2", badOffset.discardTransferId)
    }
}
