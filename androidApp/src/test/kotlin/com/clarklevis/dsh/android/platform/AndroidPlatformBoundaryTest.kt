package com.clarklevis.dsh.android.platform

import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidPlatformBoundaryTest {
    @Test
    fun gatewayClockDelegatesToCoroutineDelayWithoutRecursion() = runTest {
        AndroidGatewayClock.delay(1)
    }

    @Test
    fun imageInputStopsAtHardLimitBeforeUnboundedAllocation() {
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            readBytesWithLimit(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
        )
        assertThrows(IllegalArgumentException::class.java) {
            readBytesWithLimit(ByteArrayInputStream(ByteArray(9)), 8)
        }
    }

    @Test
    fun diskCacheEvictsExpiredTemporaryAndLeastRecentlyUsedFiles() {
        val evictions = attachmentCacheEvictions(
            files = listOf(
                CacheFileMetadata("expired", 1, 10),
                CacheFileMetadata("partial.tmp", 1, 99),
                CacheFileMetadata("oldest", 6, 91),
                CacheFileMetadata("newest", 6, 92)
            ),
            now = 100,
            ttlMilliseconds = 20,
            maximumBytes = 8
        )
        assertEquals(setOf("expired", "partial.tmp", "oldest"), evictions)
    }

    @Test
    fun thumbnailSamplingBoundsHighResolutionSources() {
        val sample = thumbnailSampleSize(12_000, 8_000)
        assertEquals(16, sample)
        assertTrue(12_000 / sample <= AndroidAttachmentThumbnailer.MAXIMUM_THUMBNAIL_SIDE)
        assertTrue(
            (12_000 / sample).toLong() * (8_000 / sample) <=
                AndroidAttachmentThumbnailer.MAXIMUM_THUMBNAIL_PIXELS
        )
    }

    @Test
    fun weightedLruEvictsOldestAndDropsCrossSessionEntries() {
        val cache = BoundedLruCache<String, ByteArray>(maximumWeight = 6) { it.size.toLong() }
        cache.put("old", ByteArray(3))
        cache.put("current", ByteArray(3))
        assertEquals(3, cache.get("old")?.size)
        val evicted = cache.put("new", ByteArray(3))
        assertEquals(listOf("current"), evicted)
        assertFalse("current" in cache.snapshot())
        assertEquals(setOf("old", "new"), cache.snapshot().keys)
        cache.retainKeys(setOf("new"))
        assertEquals(setOf("new"), cache.snapshot().keys)
        assertEquals(3, cache.currentWeight())
    }

    @Test
    fun thumbnailSamplingUsesActualDisplayPixelBudget() {
        val sample = thumbnailSampleSize(
            width = 12_000,
            height = 8_000,
            maximumSide = 600,
            maximumPixels = 240_000
        )
        assertTrue(12_000 / sample <= 600)
        assertTrue((12_000 / sample).toLong() * (8_000 / sample) <= 240_000)
    }

    @Test
    fun websocketTextUtf8LengthHasNoFrameCopyAndStopsAtBoundary() {
        assertEquals(8, utf8ByteCountWithinLimit("A中🙂", 8))
        assertEquals(null, utf8ByteCountWithinLimit("A中🙂", 7))
        assertEquals(4, utf8ByteCountWithinLimit("🙂", 4))
    }
}
