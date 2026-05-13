package com.example.gnsslogger.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NmeaExporterTest {

    @Test
    fun exportFromNmeaCsvWritesOnlyStandardNmeaSentences() {
        val dir = Files.createTempDirectory("nmea-export-test").toFile()
        try {
            val input = dir.resolve("session_20260513_153000_nmea.csv")
            val output = dir.resolve("session_20260513_153000.nmea")
            input.writeText(
                """
                timestamp_ms,timestamp_iso,elapsed_realtime_nanos,device_model,android_version,package_version,session_id,nmea_timestamp_ms,message
                1,2026-05-13T23:03:53Z,11,Pixel,16,1.0,session-a,1000,"${'$'}GNGSA,A,3,07,14,17,,,,,,,,,,1.4,1.1,0.9,1*39"
                2,2026-05-13T23:03:53Z,12,Pixel,16,1.0,session-a,1001,
                3,2026-05-13T23:03:53Z,13,Pixel,16,1.0,session-a,1002,not-nmea
                4,2026-05-13T23:03:53Z,14,Pixel,16,1.0,session-a,1003,"${'$'}GNVTG,,T,,M,0.0,N,0.0,K,A*3D"
                5,2026-05-13T23:03:53Z,15,Pixel,16,1.0,session-a,1004,"${'$'}GNRMC,230353.00,A,3413.958806,N,10900.271728,E,0.0,,130526,4.5,W,A,V*65"
                """.trimIndent(),
            )

            val result = NmeaExporter.exportFromNmeaCsv(input, output)
            val lines = output.readLines()
            val text = output.readText()

            assertTrue(result.outputFile.exists())
            assertTrue(result.sentenceCount == 3)
            assertFalse(lines.first().startsWith("timestamp_ms"))
            assertTrue(lines.all { it.startsWith("$") })
            assertFalse(text.contains("timestamp_ms"))
            assertFalse(text.contains("device_model"))
            assertFalse(text.contains("session_id"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
