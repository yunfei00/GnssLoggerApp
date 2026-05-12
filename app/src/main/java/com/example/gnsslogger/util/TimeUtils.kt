package com.example.gnsslogger.util

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object TimeUtils {

    private val isoFormatter: DateTimeFormatter =
        DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

    fun formatIsoUtcUtc(epochMillis: Long): String =
        isoFormatter.format(Instant.ofEpochMilli(epochMillis))
}
