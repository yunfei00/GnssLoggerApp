package com.example.gnsslogger.data

enum class LoggingUiStatus {
    IDLE,
    LOGGING,
    STOPPED,
}

enum class GnssRecordingPhase {
    IDLE,
    WARMING_UP,
    RECORDING,
    POOR_ACCURACY,
    STOPPED,
}

data class GnssSessionState(
    val status: LoggingUiStatus = LoggingUiStatus.IDLE,
    val recordingPhase: GnssRecordingPhase = GnssRecordingPhase.IDLE,
    val csvPath: String? = null,
    val rawCsvPath: String? = null,
    val nmeaCsvPath: String? = null,
    val nmeaTextPath: String? = null,
    val locationCsvPath: String? = null,
    val trackKmlPath: String? = null,
    val sessionDirectoryPath: String? = null,
    val visibleSatelliteCount: Int = 0,
    val usedInFixCount: Int = 0,
    val averageCn0DbHz: Float? = null,
    val currentAccuracyM: Float? = null,
    val canStartFormalRecording: Boolean = false,
    val gnssFixWarning: String? = null,
    val nmeaWarning: String? = null,
    val hasNmeaGga: Boolean = false,
    val hasNmeaRmc: Boolean = false,
    val hasNmeaGsa: Boolean = false,
    val hasNmeaGsv: Boolean = false,
    val recordingDurationMs: Long = 0L,
    val trackPointCount: Long = 0L,
    val averageAccuracyM: Float? = null,
    val bestAccuracyM: Float? = null,
    val worstAccuracyM: Float? = null,
    val kmlGenerated: Boolean = false,
    val lastUpdateElapsedRealtimeMs: Long = 0L,
    val satellites: List<GnssSatelliteRecord> = emptyList(),
    val sessionId: String? = null,
    val recordsWritten: Long = 0L,
    val rawRecordsWritten: Long = 0L,
    val nmeaRecordsWritten: Long = 0L,
    val lastError: String? = null,
    val gpsEnabled: Boolean = true,
)
