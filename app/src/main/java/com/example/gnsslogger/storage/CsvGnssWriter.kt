package com.example.gnsslogger.storage

import android.location.Location
import com.example.gnsslogger.data.GnssSatelliteRecord
import com.example.gnsslogger.util.TimeUtils
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale

class CsvGnssWriter(
    private val file: File,
    private val deviceModel: String,
    private val androidVersion: String,
    private val packageVersion: String,
) {
    private val lock = Any()
    private var writer: BufferedWriter? = null

    fun open() {
        synchronized(lock) {
            closeWriterLocked()
            file.parentFile?.mkdirs()
            writer = BufferedWriter(
                OutputStreamWriter(FileOutputStream(file, false), StandardCharsets.UTF_8),
            )
            writer!!.write(HEADER)
            writer!!.newLine()
            writer!!.flush()
        }
    }

    fun writeSatelliteFrame(
        timestampMs: Long,
        elapsedRealtimeNanos: Long,
        sessionId: String,
        sceneName: String,
        location: Location?,
        satelliteCount: Int,
        usedInFixCount: Int,
        satellites: List<GnssSatelliteRecord>,
    ): Long {
        synchronized(lock) {
            val w = writer ?: return 0L
            val tsIso = TimeUtils.formatIsoUtcUtc(timestampMs)
            val latStr = location?.latitude?.let { formatDouble(it) } ?: ""
            val lonStr = location?.longitude?.let { formatDouble(it) } ?: ""
            val altStr = location?.let { l -> if (l.hasAltitude()) formatDouble(l.altitude) else "" } ?: ""
            val accStr = location?.let { l -> if (l.hasAccuracy()) formatFloat(l.accuracy) else "" } ?: ""
            val spdStr = location?.let { l -> if (l.hasSpeed()) formatFloat(l.speed) else "" } ?: ""
            val brgStr = location?.let { l -> if (l.hasBearing()) formatFloat(l.bearing) else "" } ?: ""
            val provider = location?.provider ?: ""

            var lines = 0L
            for (s in satellites) {
                val row = listOf(
                    timestampMs.toString(),
                    escapeCsv(tsIso),
                    elapsedRealtimeNanos.toString(),
                    escapeCsv(deviceModel),
                    escapeCsv(androidVersion),
                    escapeCsv(packageVersion),
                    escapeCsv(sessionId),
                    escapeCsv(sceneName),
                    escapeCsv(provider),
                    latStr,
                    lonStr,
                    altStr,
                    accStr,
                    spdStr,
                    brgStr,
                    satelliteCount.toString(),
                    usedInFixCount.toString(),
                    s.constellationType.toString(),
                    escapeCsv(s.constellationName),
                    s.svid.toString(),
                    formatFloat(s.cn0DbHz),
                    formatFloat(s.elevationDegrees),
                    formatFloat(s.azimuthDegrees),
                    if (s.usedInFix) "1" else "0",
                    s.carrierFrequencyHz?.let { formatFloat(it) } ?: "",
                    s.basebandCn0DbHz?.let { formatFloat(it) } ?: "",
                    if (s.hasAlmanac) "1" else "0",
                    if (s.hasEphemeris) "1" else "0",
                ).joinToString(",")
                w.write(row)
                w.newLine()
                lines++
            }
            w.flush()
            return lines
        }
    }

    fun closeSafely() {
        synchronized(lock) {
            closeWriterLocked()
        }
    }

    private fun closeWriterLocked() {
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
            "timestamp_ms,timestamp_iso,elapsed_realtime_nanos,device_model,android_version," +
                "package_version,session_id,scene_name,provider,latitude,longitude,altitude,accuracy," +
                "speed,bearing,satellite_count,used_in_fix_count,constellation_type,constellation_name," +
                "svid,cn0_dbhz,elevation_deg,azimuth_deg,used_in_fix,carrier_frequency_hz," +
                "baseband_cn0_dbhz,has_almanac,has_ephemeris"

        private fun escapeCsv(value: String): String {
            val needsQuote = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
            if (!needsQuote) return value
            return "\"" + value.replace("\"", "\"\"") + "\""
        }

        private fun formatDouble(v: Double): String = String.format(Locale.US, "%.8f", v)

        private fun formatFloat(v: Float): String =
            if (v.isFinite()) String.format(Locale.US, "%.6f", v) else ""
    }
}
