package com.example.gnsslogger.util

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object TimeUtils {

    private val isoFormatter: DateTimeFormatter =
        DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
    private val isoLocalFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.systemDefault())

    fun formatIsoUtcUtc(epochMillis: Long): String =
        isoFormatter.format(Instant.ofEpochMilli(epochMillis))

    fun formatIsoLocal(epochMillis: Long): String =
        isoLocalFormatter.format(Instant.ofEpochMilli(epochMillis))
}
