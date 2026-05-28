package com.example.gnsslogger.track

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.Locale

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val timestampMs: Long? = null,
    val timestampText: String? = null,
    val accuracyM: Double? = null,
    val quality: String? = null,
)

data class ParsedTrack(
    val name: String,
    val sourceType: String,
    val points: List<TrackPoint>,
)

data class TrackStats(
    val pointCount: Int,
    val distanceMeters: Double,
    val averageAccuracyM: Double?,
    val bestAccuracyM: Double?,
    val worstAccuracyM: Double?,
)

fun List<TrackPoint>.trackStats(): TrackStats {
    val distance = zipWithNext().sumOf { (a, b) ->
        haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
    }
    val accuracies = mapNotNull { it.accuracyM?.takeIf(Double::isFinite) }
    return TrackStats(
        pointCount = size,
        distanceMeters = distance,
        averageAccuracyM = accuracies.takeIf { it.isNotEmpty() }?.average(),
        bestAccuracyM = accuracies.minOrNull(),
        worstAccuracyM = accuracies.maxOrNull(),
    )
}

fun formatTrackDistance(meters: Double): String =
    if (meters >= 1000.0) {
        String.format(Locale.US, "%.2f km", meters / 1000.0)
    } else {
        String.format(Locale.US, "%.0f m", meters)
    }

fun formatTrackAccuracy(meters: Double?): String =
    meters?.let { String.format(Locale.US, "%.1f m", it) } ?: "--"

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusM = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val rLat1 = Math.toRadians(lat1)
    val rLat2 = Math.toRadians(lat2)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(rLat1) * cos(rLat2) * sin(dLon / 2) * sin(dLon / 2)
    return earthRadiusM * 2 * atan2(sqrt(a), sqrt(1 - a))
}
