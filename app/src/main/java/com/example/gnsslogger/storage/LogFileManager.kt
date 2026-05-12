package com.example.gnsslogger.storage

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SessionPaths(
    val sessionId: String,
    val csvAbsolutePath: String,
    val directoryAbsolutePath: String,
    val csvFileName: String,
)

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
        val safePrefix = filenamePrefix.ifBlank { "gnss_log" }
        val csvFile = File(dir, "${safePrefix}_$ymdhms.csv")
        return SessionPaths(
            sessionId = sessionId,
            csvAbsolutePath = csvFile.absolutePath,
            directoryAbsolutePath = dir.absolutePath,
            csvFileName = csvFile.name,
        )
    }

    fun gnssRootDir(): File = resolveGnssRootDir()

    private fun resolveGnssRootDir(): File {
        context.getExternalFilesDir("gnss")?.let { return it }
        return File(context.filesDir, "gnss").apply { mkdirs() }
    }
}
