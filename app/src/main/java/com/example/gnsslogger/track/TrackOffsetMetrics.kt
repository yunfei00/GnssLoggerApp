package com.example.gnsslogger.track

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot

data class TrackOffsetStats(
    val sampleCount: Int,
    val averageOffsetMeters: Double,
    val p95OffsetMeters: Double,
    val maxOffsetMeters: Double,
)

fun calculateTrackOffsetStats(
    reference: List<TrackPoint>,
    target: List<TrackPoint>,
): TrackOffsetStats? {
    if (reference.isEmpty() || target.isEmpty()) return null

    val originLatRad = Math.toRadians((reference + target).map { it.latitude }.average())
    val projectedReference = reference.map { it.project(originLatRad) }
    val offsets = target.map { point ->
        val projectedPoint = point.project(originLatRad)
        projectedReference.distanceToTrack(projectedPoint)
    }.filter { it.isFinite() }

    if (offsets.isEmpty()) return null

    val sorted = offsets.sorted()
    val p95Index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)
    return TrackOffsetStats(
        sampleCount = sorted.size,
        averageOffsetMeters = sorted.average(),
        p95OffsetMeters = sorted[p95Index],
        maxOffsetMeters = sorted.last(),
    )
}

fun formatTrackOffset(meters: Double?): String =
    meters?.let {
        if (it >= 1000.0) {
            String.format(Locale.US, "%.2f km", it / 1000.0)
        } else {
            String.format(Locale.US, "%.1f m", it)
        }
    } ?: "--"

private fun List<ProjectedPoint>.distanceToTrack(point: ProjectedPoint): Double {
    if (isEmpty()) return Double.NaN
    if (size == 1) return first().distanceTo(point)
    return zipWithNext().minOf { (start, end) ->
        point.distanceToSegment(start, end)
    }
}

private fun TrackPoint.project(originLatRad: Double): ProjectedPoint =
    ProjectedPoint(
        x = Math.toRadians(longitude) * EARTH_RADIUS_M * cos(originLatRad),
        y = Math.toRadians(latitude) * EARTH_RADIUS_M,
    )

private fun ProjectedPoint.distanceToSegment(start: ProjectedPoint, end: ProjectedPoint): Double {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0.0) return distanceTo(start)

    val t = (((x - start.x) * dx + (y - start.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
    val closest = ProjectedPoint(
        x = start.x + t * dx,
        y = start.y + t * dy,
    )
    return distanceTo(closest)
}

private fun ProjectedPoint.distanceTo(other: ProjectedPoint): Double =
    hypot(x - other.x, y - other.y)

private data class ProjectedPoint(
    val x: Double,
    val y: Double,
)

private const val EARTH_RADIUS_M = 6371000.0
