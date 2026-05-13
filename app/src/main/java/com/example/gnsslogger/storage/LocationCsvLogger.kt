package com.example.gnsslogger.storage

import android.location.Location
import android.os.Build
import com.example.gnsslogger.util.TimeUtils
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale

class LocationCsvLogger(private val file: File) {
    private var writer: BufferedWriter? = null

    fun open() {
        closeSafely()
        file.parentFile?.mkdirs()
        writer = BufferedWriter(
            OutputStreamWriter(FileOutputStream(file, false), StandardCharsets.UTF_8),
        ).also {
            it.write(HEADER)
            it.newLine()
            it.flush()
        }
    }

    fun writeLocation(location: Location, scene: String = "default") {
        val tsMs = System.currentTimeMillis()
        val row = listOf(
            tsMs.toString(),
            escapeCsv(TimeUtils.formatIsoLocal(tsMs)),
            escapeCsv(location.provider ?: ""),
            formatDouble(location.latitude),
            formatDouble(location.longitude),
            if (location.hasAltitude()) formatDouble(location.altitude, 2) else "",
            if (location.hasSpeed()) formatFloat(location.speed) else "",
            if (location.hasBearing()) formatFloat(location.bearing) else "",
            if (location.hasAccuracy()) formatFloat(location.accuracy) else "",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
                formatFloat(location.verticalAccuracyMeters)
            } else {
                ""
            },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
                formatFloat(location.speedAccuracyMetersPerSecond)
            } else {
                ""
            },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasBearingAccuracy()) {
                formatFloat(location.bearingAccuracyDegrees)
            } else {
                ""
            },
            location.elapsedRealtimeNanos.toString(),
            escapeCsv(scene),
        ).joinToString(",")

        writer?.apply {
            write(row)
            newLine()
            flush()
        }
    }

    fun closeSafely() {
        try {
            writer?.flush()
        } catch (_: Exception) {
        }
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
    }

    companion object {
        const val HEADER =
            "timestamp_ms,timestamp_iso,provider,latitude,longitude,altitude_m,speed_mps,bearing_deg," +
                "accuracy_m,vertical_accuracy_m,speed_accuracy_mps,bearing_accuracy_deg," +
                "elapsed_realtime_nanos,scene"

        private fun escapeCsv(value: String): String {
            val needsQuote = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
            if (!needsQuote) return value
            return "\"" + value.replace("\"", "\"\"") + "\""
        }

        private fun formatDouble(v: Double, fraction: Int = 8): String =
            if (v.isFinite()) String.format(Locale.US, "%.${fraction}f", v) else ""

        private fun formatFloat(v: Float): String =
            if (v.isFinite()) String.format(Locale.US, "%.6f", v) else ""
    }
}
