package com.clarklevis.dsh.android.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Date
import java.util.zip.ZipFile

class HourlyLogStoreTest {
    @Test fun countsNestedResidualFilesAgainstProductionLimit() {
        val directory = Files.createTempDirectory("nested-log-residuals").toFile()
        try {
            val residual = directory.resolve("abandoned/nested/export.partial")
            checkNotNull(residual.parentFile).mkdirs()
            RandomAccessFile(residual, "rw").use { it.setLength(200_000_001) }
            val store = HourlyLogStore(directory)
            assertFalse(residual.exists())
            store.append("latest")
            store.export()
            assertTrue(directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= 200_000_000)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun preventsExportStreamFromWritingPastItsBudget() {
        val bytes = ByteArrayOutputStream()
        val stream = BudgetOutputStream(bytes, 3)
        stream.write(byteArrayOf(1, 2))
        stream.write(3)
        assertTrue(runCatching { stream.write(byteArrayOf(4, 5)) }.isFailure)
        assertTrue(runCatching { stream.write(4) }.isFailure)
        assertEquals(3, bytes.size())
    }

    @Test fun refusesAnEntryLargerThanTheEntireBudget() {
        val directory = Files.createTempDirectory("oversized-log-entry").toFile()
        try {
            val store = HourlyLogStore(directory, 128)
            assertTrue(runCatching { store.append("x".repeat(256)) }.isFailure)
            assertEquals(0L, directory.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun rotatesHourlyAndExportsCurrentWrites() {
        val directory = Files.createTempDirectory("hourly-logs").toFile()
        try {
            val store = HourlyLogStore(directory)
            val first = Date(1_800_000_000_000)
            store.append("first", first)
            store.append("same-hour", Date(first.time + 60_000))
            store.append("next-hour", Date(first.time + 3_600_000))
            val logs = directory.listFiles()!!.filter { it.extension == "log" }
            assertEquals(2, logs.size)
            assertTrue(logs.all { it.name.matches(Regex("\\d{10}00\\.log")) })
            ZipFile(store.export()).use { zip ->
                assertEquals(2, zip.size())
                val text = zip.entries().asSequence().joinToString { zip.getInputStream(it).reader().readText() }
                assertTrue(text.contains("same-hour"))
                assertTrue(text.contains("next-hour"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun splitsBusyHourAndKeepsLatestChunkForExport() {
        val directory = Files.createTempDirectory("busy-hour").toFile()
        try {
            val store = HourlyLogStore(directory, 2_000_000)
            val date = Date(1_800_000_000_000)
            repeat(150) { store.append("entry-$it " + "x".repeat(16_000), date) }
            assertTrue(directory.listFiles()!!.any { it.name.contains("-part-") })
            ZipFile(store.export()).use { zip ->
                val text = zip.entries().asSequence().joinToString { zip.getInputStream(it).reader().readText() }
                assertTrue(text.contains("entry-149"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun enforcesCombinedBudgetAndRemovesStaleExportsOnStartup() {
        val directory = Files.createTempDirectory("bounded-logs").toFile()
        try {
            val limit = 2_000_000L
            val store = HourlyLogStore(directory, limit)
            repeat(160) { index ->
                store.append("$index " + "x".repeat(16_000), Date(1_800_000_000_000 + index * 3_600_000L))
                assertTrue(directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= limit)
            }
            assertFalse(directory.listFiles()!!.any { it.readText().contains(" 0 xxx") })
            store.export()
            assertTrue(directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= limit)
            HourlyLogStore(directory, limit)
            assertFalse(directory.resolve("exports").exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
