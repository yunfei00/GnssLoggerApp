package com.example.gnsslogger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gnsslogger.GnssLoggerService
import com.example.gnsslogger.data.GnssSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(GnssSessionState())
    val uiState: StateFlow<GnssSessionState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            GnssLoggerService.sessionState.collect { svc ->
                _ui.update { prev ->
                    prev.copy(
                        status = svc.status,
                        recordingPhase = svc.recordingPhase,
                        csvPath = svc.csvPath,
                        rawCsvPath = svc.rawCsvPath,
                        nmeaCsvPath = svc.nmeaCsvPath,
                        nmeaTextPath = svc.nmeaTextPath,
                        locationCsvPath = svc.locationCsvPath,
                        trackKmlPath = svc.trackKmlPath,
                        sessionDirectoryPath = svc.sessionDirectoryPath,
                        visibleSatelliteCount = svc.visibleSatelliteCount,
                        usedInFixCount = svc.usedInFixCount,
                        averageCn0DbHz = svc.averageCn0DbHz,
                        currentAccuracyM = svc.currentAccuracyM,
                        canStartFormalRecording = svc.canStartFormalRecording,
                        gnssFixWarning = svc.gnssFixWarning,
                        nmeaWarning = svc.nmeaWarning,
                        hasNmeaGga = svc.hasNmeaGga,
                        hasNmeaRmc = svc.hasNmeaRmc,
                        hasNmeaGsa = svc.hasNmeaGsa,
                        hasNmeaGsv = svc.hasNmeaGsv,
                        recordingDurationMs = svc.recordingDurationMs,
                        trackPointCount = svc.trackPointCount,
                        averageAccuracyM = svc.averageAccuracyM,
                        bestAccuracyM = svc.bestAccuracyM,
                        worstAccuracyM = svc.worstAccuracyM,
                        kmlGenerated = svc.kmlGenerated,
                        lastUpdateElapsedRealtimeMs = svc.lastUpdateElapsedRealtimeMs,
                        satellites = svc.satellites,
                        sessionId = svc.sessionId,
                        recordsWritten = svc.recordsWritten,
                        rawRecordsWritten = svc.rawRecordsWritten,
                        nmeaRecordsWritten = svc.nmeaRecordsWritten,
                        lastError = svc.lastError,
                        gpsEnabled = svc.gpsEnabled,
                    )
                }
            }
        }
    }

    fun persistUiPrefs(
        prefix: String,
        useDateSubdir: Boolean,
        recordNmea: Boolean,
        recordRawMeasurements: Boolean,
    ) {
        GnssLoggerService.writeUiPrefs(
            context = getApplication(),
            prefix = prefix,
            useDateSubdir = useDateSubdir,
            recordNmea = recordNmea,
            recordRawMeasurements = recordRawMeasurements,
        )
    }
}
