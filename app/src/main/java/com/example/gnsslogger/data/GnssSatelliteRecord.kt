package com.example.gnsslogger.data

data class GnssSatelliteRecord(
    val constellationType: Int,
    val constellationName: String,
    val svid: Int,
    val cn0DbHz: Float,
    val elevationDegrees: Float,
    val azimuthDegrees: Float,
    val usedInFix: Boolean,
    val carrierFrequencyHz: Float?,
    val basebandCn0DbHz: Float?,
    val hasAlmanac: Boolean,
    val hasEphemeris: Boolean,
)
