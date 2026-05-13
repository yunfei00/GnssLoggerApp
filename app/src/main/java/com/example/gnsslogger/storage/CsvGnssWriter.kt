package com.example.gnsslogger.storage

import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.Location
import android.os.Build
import com.example.gnsslogger.data.GnssSatelliteRecord
import com.example.gnsslogger.gnss.RawGnssMeasurementsFrame
import com.example.gnsslogger.util.TimeUtils
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale

class CsvGnssWriter(
    private val satelliteFile: File,
    private val rawMeasurementsFile: File?,
    private val nmeaFile: File?,
    private val deviceModel: String,
    private val androidVersion: String,
    private val packageVersion: String,
) {
    private val lock = Any()
    private var satelliteWriter: BufferedWriter? = null
    private var rawMeasurementsWriter: BufferedWriter? = null
    private var nmeaWriter: BufferedWriter? = null

    fun open() {
        synchronized(lock) {
            closeWritersLocked()
            satelliteWriter = openWriter(satelliteFile, SATELLITE_HEADER)
            rawMeasurementsWriter = rawMeasurementsFile?.let { openWriter(it, RAW_MEASUREMENTS_HEADER) }
            nmeaWriter = nmeaFile?.let { openWriter(it, NMEA_HEADER) }
        }
    }

    fun writeSatelliteFrame(
        timestampMs: Long,
        elapsedRealtimeNanos: Long,
        sessionId: String,
        location: Location?,
        satelliteCount: Int,
        usedInFixCount: Int,
        satellites: List<GnssSatelliteRecord>,
    ): Long {
        synchronized(lock) {
            val w = satelliteWriter ?: return 0L
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

    fun writeRawMeasurementsFrame(
        timestampMs: Long,
        elapsedRealtimeNanos: Long,
        sessionId: String,
        frame: RawGnssMeasurementsFrame,
    ): Long {
        synchronized(lock) {
            val w = rawMeasurementsWriter ?: return 0L
            val tsIso = TimeUtils.formatIsoUtcUtc(timestampMs)
            val clock = frame.clock

            var lines = 0L
            for (measurement in frame.measurements) {
                val row = buildRawMeasurementRow(
                    timestampMs = timestampMs,
                    timestampIso = tsIso,
                    elapsedRealtimeNanos = elapsedRealtimeNanos,
                    sessionId = sessionId,
                    clock = clock,
                    measurement = measurement,
                ).joinToString(",")
                w.write(row)
                w.newLine()
                lines++
            }
            w.flush()
            return lines
        }
    }

    fun writeNmeaMessage(
        timestampMs: Long,
        elapsedRealtimeNanos: Long,
        sessionId: String,
        nmeaTimestampMs: Long,
        message: String,
    ): Long {
        synchronized(lock) {
            val w = nmeaWriter ?: return 0L
            val row = listOf(
                timestampMs.toString(),
                escapeCsv(TimeUtils.formatIsoUtcUtc(timestampMs)),
                elapsedRealtimeNanos.toString(),
                escapeCsv(deviceModel),
                escapeCsv(androidVersion),
                escapeCsv(packageVersion),
                escapeCsv(sessionId),
                nmeaTimestampMs.toString(),
                escapeCsv(message.trimEnd('\r', '\n')),
            ).joinToString(",")
            w.write(row)
            w.newLine()
            w.flush()
            return 1L
        }
    }

    fun closeSafely() {
        synchronized(lock) {
            closeWritersLocked()
        }
    }

    private fun openWriter(file: File, header: String): BufferedWriter {
        file.parentFile?.mkdirs()
        return BufferedWriter(
            OutputStreamWriter(FileOutputStream(file, false), StandardCharsets.UTF_8),
        ).also {
            it.write(header)
            it.newLine()
            it.flush()
        }
    }

    private fun closeWritersLocked() {
        closeWriter(satelliteWriter)
        closeWriter(rawMeasurementsWriter)
        closeWriter(nmeaWriter)
        satelliteWriter = null
        rawMeasurementsWriter = null
        nmeaWriter = null
    }

    private fun closeWriter(writer: BufferedWriter?) {
        try {
            writer?.flush()
        } catch (_: Exception) {
        }
        try {
            writer?.close()
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun buildRawMeasurementRow(
        timestampMs: Long,
        timestampIso: String,
        elapsedRealtimeNanos: Long,
        sessionId: String,
        clock: GnssClock,
        measurement: GnssMeasurement,
    ): List<String> = listOf(
        timestampMs.toString(),
        escapeCsv(timestampIso),
        elapsedRealtimeNanos.toString(),
        escapeCsv(deviceModel),
        escapeCsv(androidVersion),
        escapeCsv(packageVersion),
        escapeCsv(sessionId),
        clock.timeNanos.toString(),
        if (clock.hasFullBiasNanos()) clock.fullBiasNanos.toString() else "",
        if (clock.hasBiasNanos()) formatDouble(clock.biasNanos) else "",
        if (clock.hasBiasUncertaintyNanos()) formatDouble(clock.biasUncertaintyNanos) else "",
        if (clock.hasDriftNanosPerSecond()) formatDouble(clock.driftNanosPerSecond) else "",
        if (clock.hasDriftUncertaintyNanosPerSecond()) {
            formatDouble(clock.driftUncertaintyNanosPerSecond)
        } else {
            ""
        },
        clock.hardwareClockDiscontinuityCount.toString(),
        measurement.constellationType.toString(),
        escapeCsv(com.example.gnsslogger.gnss.ConstellationMapper.toName(measurement.constellationType)),
        measurement.svid.toString(),
        formatDouble(measurement.timeOffsetNanos),
        measurement.state.toString(),
        measurement.receivedSvTimeNanos.toString(),
        measurement.receivedSvTimeUncertaintyNanos.toString(),
        formatDouble(measurement.cn0DbHz),
        formatDouble(measurement.pseudorangeRateMetersPerSecond),
        formatDouble(measurement.pseudorangeRateUncertaintyMetersPerSecond),
        measurement.accumulatedDeltaRangeState.toString(),
        formatDouble(measurement.accumulatedDeltaRangeMeters),
        formatDouble(measurement.accumulatedDeltaRangeUncertaintyMeters),
        if (measurement.hasCarrierFrequencyHz()) formatFloat(measurement.carrierFrequencyHz) else "",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && measurement.hasBasebandCn0DbHz()) {
            formatDouble(measurement.basebandCn0DbHz)
        } else {
            ""
        },
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && measurement.hasAutomaticGainControlLevelDb()) {
            formatDouble(measurement.automaticGainControlLevelDb)
        } else {
            ""
        },
        if (measurement.hasSnrInDb()) formatDouble(measurement.snrInDb) else "",
        measurement.multipathIndicator.toString(),
    )

    companion object {
        const val SATELLITE_HEADER =
            "timestamp_ms,timestamp_iso,elapsed_realtime_nanos,device_model,android_version," +
                "package_version,session_id,provider,latitude,longitude,altitude,accuracy," +
                "speed,bearing,satellite_count,used_in_fix_count,constellation_type,constellation_name," +
                "svid,cn0_dbhz,elevation_deg,azimuth_deg,used_in_fix,carrier_frequency_hz," +
                "baseband_cn0_dbhz,has_almanac,has_ephemeris"

        const val RAW_MEASUREMENTS_HEADER =
            "timestamp_ms,timestamp_iso,elapsed_realtime_nanos,device_model,android_version," +
                "package_version,session_id,clock_time_nanos,clock_full_bias_nanos," +
                "clock_bias_nanos,clock_bias_uncertainty_nanos,clock_drift_nanos_per_second," +
                "clock_drift_uncertainty_nanos_per_second,hardware_clock_discontinuity_count," +
                "constellation_type,constellation_name,svid,time_offset_nanos,state,received_sv_time_nanos," +
                "received_sv_time_uncertainty_nanos,cn0_dbhz,pseudorange_rate_mps," +
                "pseudorange_rate_uncertainty_mps,accumulated_delta_range_state," +
                "accumulated_delta_range_m,accumulated_delta_range_uncertainty_m,carrier_frequency_hz," +
                "baseband_cn0_dbhz,automatic_gain_control_db,snr_db,multipath_indicator"

        const val NMEA_HEADER =
            "timestamp_ms,timestamp_iso,elapsed_realtime_nanos,device_model,android_version," +
                "package_version,session_id,nmea_timestamp_ms,message"

        private fun escapeCsv(value: String): String {
            val needsQuote = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
            if (!needsQuote) return value
            return "\"" + value.replace("\"", "\"\"") + "\""
        }

        private fun formatDouble(v: Double): String =
            if (v.isFinite()) String.format(Locale.US, "%.9f", v) else ""

        private fun formatFloat(v: Float): String =
            if (v.isFinite()) String.format(Locale.US, "%.6f", v) else ""
    }
}
