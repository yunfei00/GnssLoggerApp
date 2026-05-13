package com.example.gnsslogger.storage

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SessionPaths(
    val sessionId: String,
    val satelliteCsvAbsolutePath: String,
    val rawCsvAbsolutePath: String,
    val nmeaCsvAbsolutePath: String,
    val nmeaTextAbsolutePath: String,
    val locationCsvAbsolutePath: String,
    val trackKmlAbsolutePath: String,
    val directoryAbsolutePath: String,
    val satelliteCsvFileName: String,
    val rawCsvFileName: String,
    val nmeaCsvFileName: String,
    val nmeaTextFileName: String,
    val locationCsvFileName: String,
    val trackKmlFileName: String,
) {
    val csvAbsolutePath: String
        get() = satelliteCsvAbsolutePath

    val csvFileName: String
        get() = satelliteCsvFileName
}

class LogFileManager(private val context: Context) {

    fun createNewSession(
        filenamePrefix: String,
        useDateSubdir: Boolean,
    ): SessionPaths {
        val sessionId = UUID.randomUUID().toString()
        val now = Date()
        val ymd = SimpleDateFormat("yyyyMMdd", Locale.US).format(now)
        val ymdhms = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        val root = resolveGnssRootDir()
        val dir = if (useDateSubdir) File(root, ymd) else root
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建目录: ${dir.absolutePath}")
        }
        val safePrefix = sanitizeFilenamePrefix(filenamePrefix)
        val satelliteCsvFile = File(dir, "${safePrefix}_${ymdhms}_satellites.csv")
        val rawCsvFile = File(dir, "${safePrefix}_${ymdhms}_raw.csv")
        val nmeaCsvFile = File(dir, "${safePrefix}_${ymdhms}_nmea.csv")
        val nmeaTextFile = File(dir, "${safePrefix}_${ymdhms}.nmea")
        val locationCsvFile = File(dir, "${safePrefix}_${ymdhms}_location.csv")
        val trackKmlFile = File(dir, "${safePrefix}_${ymdhms}_track.kml")
        return SessionPaths(
            sessionId = sessionId,
            satelliteCsvAbsolutePath = satelliteCsvFile.absolutePath,
            rawCsvAbsolutePath = rawCsvFile.absolutePath,
            nmeaCsvAbsolutePath = nmeaCsvFile.absolutePath,
            nmeaTextAbsolutePath = nmeaTextFile.absolutePath,
            locationCsvAbsolutePath = locationCsvFile.absolutePath,
            trackKmlAbsolutePath = trackKmlFile.absolutePath,
            directoryAbsolutePath = dir.absolutePath,
            satelliteCsvFileName = satelliteCsvFile.name,
            rawCsvFileName = rawCsvFile.name,
            nmeaCsvFileName = nmeaCsvFile.name,
            nmeaTextFileName = nmeaTextFile.name,
            locationCsvFileName = locationCsvFile.name,
            trackKmlFileName = trackKmlFile.name,
        )
    }

    fun gnssRootDir(): File = resolveGnssRootDir()

    private fun resolveGnssRootDir(): File {
        context.getExternalFilesDir("gnss")?.let { return it }
        return File(context.filesDir, "gnss").apply { mkdirs() }
    }

    private fun sanitizeFilenamePrefix(filenamePrefix: String): String {
        val cleaned = filenamePrefix
            .trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_', '.', '-')
        return cleaned.ifBlank { "gnss_log" }
    }
}
