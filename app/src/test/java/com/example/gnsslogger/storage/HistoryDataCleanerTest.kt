package com.example.gnsslogger.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class HistoryDataCleanerTest {

    @Test
    fun cleanDeletesHistoryFilesButKeepsConfigCurrentSessionAndOutsideFiles() {
        val temp = Files.createTempDirectory("history-cleaner-test").toFile()
        try {
            val root = temp.resolve("gnss").apply { mkdirs() }
            val sessionDir = root.resolve("session_old").apply { mkdirs() }
            val oldRaw = sessionDir.resolve("session_old_raw.csv").apply { writeText("raw") }
            val oldNmea = sessionDir.resolve("session_old.nmea").apply { writeText("${'$'}GNRMC") }
            val oldKml = sessionDir.resolve("session_old.kml").apply { writeText("<kml/>") }
            val oldTrack = root.resolve("session_old_track.csv").apply { writeText("track") }
            val config = root.resolve("settings.json").apply { writeText("{}") }
            val currentSessionFile = root.resolve("current_raw.csv").apply { writeText("current") }
            val outside = temp.resolve("outside_raw.csv").apply { writeText("outside") }

            val cleaner = HistoryDataCleaner(
                roots = listOf(root),
                excludedFiles = setOf(currentSessionFile),
            )
            val scan = cleaner.scan()
            val result = cleaner.clean()

            assertEquals(4, scan.fileCount)
            assertEquals(4, result.deletedFileCount)
            assertFalse(oldRaw.exists())
            assertFalse(oldNmea.exists())
            assertFalse(oldKml.exists())
            assertFalse(oldTrack.exists())
            assertFalse(sessionDir.exists())
            assertTrue(config.exists())
            assertTrue(currentSessionFile.exists())
            assertTrue(outside.exists())
            assertTrue(result.failedFiles.isEmpty())
        } finally {
            temp.deleteRecursively()
        }
    }
}
