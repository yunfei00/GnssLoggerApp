package com.example.gnsslogger.data

enum class LoggingUiStatus {
    IDLE,
    LOGGING,
    STOPPED,
}

data class GnssSessionState(
    val status: LoggingUiStatus = LoggingUiStatus.IDLE,
    val csvPath: String? = null,
    val rawCsvPath: String? = null,
    val nmeaCsvPath: String? = null,
    val locationCsvPath: String? = null,
    val trackKmlPath: String? = null,
    val sessionDirectoryPath: String? = null,
    val visibleSatelliteCount: Int = 0,
    val usedInFixCount: Int = 0,
    val lastUpdateElapsedRealtimeMs: Long = 0L,
    val satellites: List<GnssSatelliteRecord> = emptyList(),
    val sessionId: String? = null,
    val recordsWritten: Long = 0L,
    val rawRecordsWritten: Long = 0L,
    val nmeaRecordsWritten: Long = 0L,
    val lastError: String? = null,
    val gpsEnabled: Boolean = true,
)
