package com.example.gnsslogger.track

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackOffsetMetricsTest {

    @Test
    fun calculateTrackOffsetStats_usesDistanceToReferenceLine() {
        val reference = listOf(
            TrackPoint(latitude = 34.0, longitude = 108.0),
            TrackPoint(latitude = 34.0, longitude = 108.002),
        )
        val target = listOf(
            TrackPoint(latitude = 34.0001, longitude = 108.0002),
            TrackPoint(latitude = 34.0001, longitude = 108.0010),
            TrackPoint(latitude = 34.0001, longitude = 108.0018),
        )

        val stats = requireNotNull(calculateTrackOffsetStats(reference, target))

        assertEquals(3, stats.sampleCount)
        assertEquals(11.1, stats.averageOffsetMeters, 0.5)
        assertEquals(11.1, stats.p95OffsetMeters, 0.5)
        assertEquals(11.1, stats.maxOffsetMeters, 0.5)
    }

    @Test
    fun calculateTrackOffsetStats_reportsP95AndMaxSeparately() {
        val reference = listOf(
            TrackPoint(latitude = 34.0, longitude = 108.0),
            TrackPoint(latitude = 34.0, longitude = 108.002),
        )
        val target = List(19) { index ->
            TrackPoint(latitude = 34.0001, longitude = 108.0001 + index * 0.00001)
        } + TrackPoint(latitude = 34.001, longitude = 108.001)

        val stats = requireNotNull(calculateTrackOffsetStats(reference, target))

        assertEquals(20, stats.sampleCount)
        assertEquals(11.1, stats.p95OffsetMeters, 0.5)
        assertEquals(111.2, stats.maxOffsetMeters, 1.0)
    }

    @Test
    fun calculateTrackOffsetStats_returnsNullWhenReferenceOrTargetMissing() {
        val point = TrackPoint(latitude = 34.0, longitude = 108.0)

        assertEquals(null, calculateTrackOffsetStats(emptyList(), listOf(point)))
        assertEquals(null, calculateTrackOffsetStats(listOf(point), emptyList()))
    }

    @Test
    fun formatTrackOffset_keepsMeterPrecision() {
        assertEquals("8.6 m", formatTrackOffset(8.64))
        assertEquals("1.25 km", formatTrackOffset(1250.0))
        assertEquals("--", formatTrackOffset(null))
    }
}
