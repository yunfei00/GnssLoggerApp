package com.example.gnsslogger

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.gnsslogger.data.GnssRecordingPhase
import com.example.gnsslogger.data.GnssSessionState
import com.example.gnsslogger.data.LoggingUiStatus
import com.example.gnsslogger.gnss.GnssCollector
import com.example.gnsslogger.gnss.GnssStatusFrame
import com.example.gnsslogger.gnss.NmeaMessageFrame
import com.example.gnsslogger.gnss.RawGnssMeasurementsFrame
import com.example.gnsslogger.storage.CsvGnssWriter
import com.example.gnsslogger.storage.KmlExporter
import com.example.gnsslogger.storage.LocationCsvLogger
import com.example.gnsslogger.storage.LogFileManager
import com.example.gnsslogger.storage.NoValidTrackPointsException
import com.example.gnsslogger.util.DeviceInfo
import com.example.gnsslogger.util.NotificationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class GnssLoggerService : Service() {

    private var isLogging = false
    private var gnssThread: HandlerThread? = null
    private var collector: GnssCollector? = null
    private var csvWriter: CsvGnssWriter? = null
    private var locationCsvLogger: LocationCsvLogger? = null
    private var sessionId: String? = null
    private var csvPath: String? = null
    private var rawCsvPath: String? = null
    private var nmeaCsvPath: String? = null
    private var sessionDirectoryPath: String? = null
    private var locationCsvPath: String? = null
    private var trackKmlPath: String? = null
    private var csvFileName: String? = null
    private var provisionalForeground = false
    private var recordNmea = true
    private var recordRawMeasurements = true
    private var recordingPhase = GnssRecordingPhase.IDLE
    private var warmupStartedElapsedMs = 0L
    private var recordingStartedElapsedMs = 0L
    private var currentAccuracyM: Float? = null
    private var latestAverageCn0DbHz: Float? = null
    private var latestVisibleSatelliteCount = 0
    private var latestUsedInFixCount = 0
    private var zeroUsedInFixSinceElapsedMs: Long? = null
    private var zeroFixWarningLogged = false
    private var hasNmeaGga = false
    private var hasNmeaRmc = false
    private var hasNmeaGsa = false
    private var hasNmeaGsv = false
    private var nmeaIncompleteWarningLogged = false
    private var trackPointCount = 0L
    private var accuracySum = 0.0
    private var bestAccuracyM: Float? = null
    private var worstAccuracyM: Float? = null
    private var kmlGenerated = false

    private var csvExecutor = newCsvExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recordsWritten = AtomicLong(0L)
    private val rawRecordsWritten = AtomicLong(0L)
    private val nmeaRecordsWritten = AtomicLong(0L)

    override fun onBind(intent: Intent?): IBinder? = null

    private fun newCsvExecutor() = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gnss-csv-writer").apply { isDaemon = true }
    }

    private fun ensureCsvExecutor() {
        if (csvExecutor.isShutdown || csvExecutor.isTerminated) {
            csvExecutor = newCsvExecutor()
        }
    }

    private fun enqueueCsvWrite(block: () -> Unit) {
        if (csvExecutor.isShutdown || csvExecutor.isTerminated) return
        try {
            csvExecutor.execute(block)
        } catch (_: RejectedExecutionException) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        NotificationHelper.ensureChannel(this)
    }

    override fun onDestroy() {
        if (isLogging) {
            stopLoggingInternal(reason = "服务销毁", stopService = false)
        } else {
            silentReleaseResources()
        }
        runningInstance = null
        super.onDestroy()
    }

    private fun silentReleaseResources() {
        try {
            collector?.stop()
        } catch (_: Exception) {
        }
        collector = null
        try {
            gnssThread?.quitSafely()
        } catch (_: Exception) {
        }
        gnssThread = null
        try {
            if (!csvExecutor.isShutdown) {
                csvExecutor.shutdownNow()
                csvExecutor.awaitTermination(1, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
        }
        try {
            csvWriter?.closeSafely()
            locationCsvLogger?.closeSafely()
        } catch (_: Exception) {
        }
        csvWriter = null
        locationCsvLogger = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AppActions.ACTION_START_LOGGING -> {
                if (!isLogging) {
                    NotificationHelper.ensureChannel(this)
                    startForegroundWithType(
                        NotificationHelper.buildForegroundNotification(
                            this,
                            "正在启动…",
                            0L,
                        ),
                    )
                    provisionalForeground = true
                }
                startLoggingFromIntent()
                if (!isLogging && provisionalForeground) {
                    provisionalForeground = false
                    stopForegroundStopCompat()
                    stopSelf()
                }
            }

            AppActions.ACTION_STOP_LOGGING -> stopLoggingFromIntent()
        }
        return if (isLogging) START_STICKY else START_NOT_STICKY
    }

    private fun startLoggingFromIntent() {
        if (isLogging) return
        if (!hasLocationPermission()) {
            publishError("缺少定位权限，请在应用中授权")
            return
        }
        if (!isGpsEnabled()) {
            publishError("GPS 未开启，请在系统设置中打开位置信息")
            return
        }
        try {
            startLoggingInternal()
        } catch (e: Exception) {
            silentReleaseResources()
            isLogging = false
            provisionalForeground = false
            stopForegroundStopCompat()
            publishError("启动失败: ${e.message}")
            stopSelf()
        }
    }

    private fun stopLoggingFromIntent() {
        stopLoggingInternal(reason = null, stopService = true)
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun isGpsEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    private fun startLoggingInternal() {
        ensureCsvExecutor()
        val prefix = readFilenamePrefix(this)
        val useDate = readUseDateSubdir(this)
        recordNmea = readRecordNmea(this)
        recordRawMeasurements = readRecordRawMeasurements(this)
        val paths = LogFileManager(this).createNewSession(prefix, useDate)
        sessionId = paths.sessionId
        csvPath = paths.satelliteCsvAbsolutePath
        rawCsvPath = if (recordRawMeasurements) paths.rawCsvAbsolutePath else null
        nmeaCsvPath = if (recordNmea) paths.nmeaCsvAbsolutePath else null
        locationCsvPath = paths.locationCsvAbsolutePath
        trackKmlPath = paths.trackKmlAbsolutePath
        sessionDirectoryPath = paths.directoryAbsolutePath
        csvFileName = paths.satelliteCsvFileName

        val writer = CsvGnssWriter(
            satelliteFile = java.io.File(paths.satelliteCsvAbsolutePath),
            rawMeasurementsFile = rawCsvPath?.let { java.io.File(it) },
            nmeaFile = nmeaCsvPath?.let { java.io.File(it) },
            deviceModel = DeviceInfo.model(),
            androidVersion = DeviceInfo.androidRelease(),
            packageVersion = DeviceInfo.packageVersionName(this),
        )
        writer.open()
        csvWriter = writer
        locationCsvLogger = LocationCsvLogger(java.io.File(paths.locationCsvAbsolutePath)).also { it.open() }
        resetQualityState()
        recordingPhase = GnssRecordingPhase.WARMING_UP
        warmupStartedElapsedMs = SystemClock.elapsedRealtime()
        recordsWritten.set(0L)
        rawRecordsWritten.set(0L)
        nmeaRecordsWritten.set(0L)
        lastNotifiedRecords = 0L
        isLogging = true

        val thread = HandlerThread("gnss-callbacks").apply { start() }
        gnssThread = thread
        val collectorInstance = GnssCollector(
            context = this,
            callbackLooper = thread.looper,
            collectNmea = recordNmea,
            collectRawMeasurements = recordRawMeasurements,
            onStatusFrame = { frame -> onGnssFrame(frame) },
            onNmeaMessage = { frame -> onNmeaFrame(frame) },
            onRawMeasurements = { frame -> onRawMeasurementsFrame(frame) },
            onLocationUpdate = { location -> onLocationUpdate(location) },
        )
        collector = collectorInstance
        collectorInstance.start()

        provisionalForeground = false
        val notification = NotificationHelper.buildForegroundNotification(
            this,
            csvFileName,
            0L,
        )
        startForegroundWithType(notification)

        _sessionState.update {
            it.copy(
                status = LoggingUiStatus.LOGGING,
                recordingPhase = recordingPhase,
                sessionId = sessionId,
                csvPath = csvPath,
                rawCsvPath = rawCsvPath,
                nmeaCsvPath = nmeaCsvPath,
                sessionDirectoryPath = sessionDirectoryPath,
                locationCsvPath = locationCsvPath,
                trackKmlPath = trackKmlPath,
                recordsWritten = 0L,
                rawRecordsWritten = 0L,
                nmeaRecordsWritten = 0L,
                lastError = null,
                gpsEnabled = true,
                currentAccuracyM = null,
                canStartFormalRecording = false,
                averageCn0DbHz = null,
                gnssFixWarning = null,
                nmeaWarning = null,
                hasNmeaGga = false,
                hasNmeaRmc = false,
                hasNmeaGsa = false,
                hasNmeaGsv = false,
                recordingDurationMs = 0L,
                trackPointCount = 0L,
                averageAccuracyM = null,
                bestAccuracyM = null,
                worstAccuracyM = null,
                kmlGenerated = false,
            )
        }
    }

    private fun resetQualityState() {
        recordingPhase = GnssRecordingPhase.IDLE
        warmupStartedElapsedMs = 0L
        recordingStartedElapsedMs = 0L
        currentAccuracyM = null
        latestAverageCn0DbHz = null
        latestVisibleSatelliteCount = 0
        latestUsedInFixCount = 0
        zeroUsedInFixSinceElapsedMs = null
        zeroFixWarningLogged = false
        hasNmeaGga = false
        hasNmeaRmc = false
        hasNmeaGsa = false
        hasNmeaGsv = false
        nmeaIncompleteWarningLogged = false
        trackPointCount = 0L
        accuracySum = 0.0
        bestAccuracyM = null
        worstAccuracyM = null
        kmlGenerated = false
    }

    private fun onGnssFrame(frame: GnssStatusFrame) {
        val writer = csvWriter ?: return
        val sid = sessionId ?: return
        val ts = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()
        val loc = collector?.lastLocation
        latestVisibleSatelliteCount = frame.visibleCount
        latestUsedInFixCount = frame.usedInFixCount
        latestAverageCn0DbHz = averageCn0(frame)
        updateZeroFixWarning(frame.usedInFixCount)
        maybeAllowPoorAccuracyRecording()
        val shouldWrite = isFormalRecording()

        enqueueCsvWrite {
            try {
                val lines = if (!shouldWrite || frame.satellites.isEmpty()) {
                    0L
                } else {
                    writer.writeSatelliteFrame(
                        timestampMs = ts,
                        elapsedRealtimeNanos = elapsedNanos,
                        sessionId = sid,
                        location = loc,
                        satelliteCount = frame.visibleCount,
                        usedInFixCount = frame.usedInFixCount,
                        satellites = frame.satellites,
                    )
                }
                if (lines > 0) {
                    recordsWritten.addAndGet(lines)
                    maybeRefreshNotification(totalRecordsWritten())
                }
                mainHandler.post {
                    _sessionState.update { prev ->
                        prev.copy(
                            recordingPhase = recordingPhase,
                            visibleSatelliteCount = frame.visibleCount,
                            usedInFixCount = frame.usedInFixCount,
                            averageCn0DbHz = latestAverageCn0DbHz,
                            currentAccuracyM = currentAccuracyM,
                            canStartFormalRecording = canStartFormalRecording(),
                            gnssFixWarning = gnssFixWarning(),
                            nmeaWarning = nmeaWarning(),
                            hasNmeaGga = hasNmeaGga,
                            hasNmeaRmc = hasNmeaRmc,
                            hasNmeaGsa = hasNmeaGsa,
                            hasNmeaGsv = hasNmeaGsv,
                            satellites = frame.satellites,
                            lastUpdateElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                            recordsWritten = recordsWritten.get(),
                            rawRecordsWritten = rawRecordsWritten.get(),
                            nmeaRecordsWritten = nmeaRecordsWritten.get(),
                            recordingDurationMs = currentRecordingDurationMs(),
                            trackPointCount = trackPointCount,
                            averageAccuracyM = averageAccuracyM(),
                            bestAccuracyM = bestAccuracyM,
                            worstAccuracyM = worstAccuracyM,
                            kmlGenerated = kmlGenerated,
                            csvPath = csvPath,
                            rawCsvPath = rawCsvPath,
                            nmeaCsvPath = nmeaCsvPath,
                            sessionDirectoryPath = sessionDirectoryPath,
                            locationCsvPath = locationCsvPath,
                            trackKmlPath = trackKmlPath,
                            )
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    publishError("写入 CSV 失败: ${e.message}")
                }
            }
        }
    }

    private fun averageCn0(frame: GnssStatusFrame): Float? {
        val values = frame.satellites
            .map { it.cn0DbHz }
            .filter { it.isFinite() && it > 0f }
        if (values.isEmpty()) return null
        return values.sum() / values.size
    }

    private fun updateZeroFixWarning(usedInFixCount: Int) {
        val now = SystemClock.elapsedRealtime()
        zeroUsedInFixSinceElapsedMs = if (usedInFixCount > 0) {
            zeroFixWarningLogged = false
            null
        } else {
            zeroUsedInFixSinceElapsedMs ?: now
        }
    }

    private fun gnssFixWarning(): String? {
        val zeroSince = zeroUsedInFixSinceElapsedMs ?: return null
        val now = SystemClock.elapsedRealtime()
        return if (now - zeroSince >= FIX_WARNING_AFTER_MS && isLogging) {
            if (!zeroFixWarningLogged) {
                zeroFixWarningLogged = true
                Log.w(TAG, "used_in_fix_count remained 0; GNSS fix is not stable")
            }
            "当前未形成稳定 GNSS Fix，轨迹精度可能较差。"
        } else {
            null
        }
    }

    private fun maybeAllowPoorAccuracyRecording() {
        if (recordingPhase != GnssRecordingPhase.WARMING_UP) return
        val started = warmupStartedElapsedMs.takeIf { it > 0L } ?: return
        if (SystemClock.elapsedRealtime() - started >= WARMUP_TIMEOUT_MS) {
            beginFormalRecording(poorAccuracy = true)
        }
    }

    private fun beginFormalRecording(poorAccuracy: Boolean) {
        if (isFormalRecording()) return
        recordingPhase = if (poorAccuracy) GnssRecordingPhase.POOR_ACCURACY else GnssRecordingPhase.RECORDING
        recordingStartedElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun isFormalRecording(): Boolean =
        recordingPhase == GnssRecordingPhase.RECORDING ||
            recordingPhase == GnssRecordingPhase.POOR_ACCURACY

    private fun canStartFormalRecording(): Boolean =
        currentAccuracyM?.let { it.isFinite() && it <= FORMAL_RECORDING_ACCURACY_M } == true


    private fun onLocationUpdate(location: android.location.Location) {
        currentAccuracyM = if (location.hasAccuracy() && location.accuracy.isFinite()) {
            location.accuracy
        } else {
            null
        }
        if (recordingPhase == GnssRecordingPhase.WARMING_UP && canStartFormalRecording()) {
            beginFormalRecording(poorAccuracy = false)
        } else {
            maybeAllowPoorAccuracyRecording()
        }
        refreshRecordingPhaseForCurrentAccuracy()
        postSessionQualityState()

        if (!isFormalRecording()) return
        if (!isValidLocationForCsv(location)) return
        val logger = locationCsvLogger ?: return
        val accuracy = currentAccuracyM ?: return
        val quality = qualityForAccuracy(accuracy)
        enqueueCsvWrite {
            try {
                logger.writeLocation(
                    location = location,
                    quality = quality,
                    satelliteCount = latestVisibleSatelliteCount,
                    usedInFixCount = latestUsedInFixCount,
                    averageCn0DbHz = latestAverageCn0DbHz,
                    scene = "default",
                )
                updateLocationStats(accuracy)
                postWriteStats()
            } catch (e: Exception) {
                mainHandler.post { publishError("写入 location.csv 失败: ${e.message}") }
            }
        }
    }

    private fun isValidLocationForCsv(location: android.location.Location): Boolean {
        val lat = location.latitude
        val lon = location.longitude
        val acc = currentAccuracyM
        if (!lat.isFinite() || !lon.isFinite()) return false
        if (lat == 0.0 && lon == 0.0) return false
        if (acc == null || !acc.isFinite()) return false
        return acc <= MAX_RECORDING_ACCURACY_M
    }

    private fun qualityForAccuracy(accuracyM: Float): String =
        when {
            accuracyM <= 5f -> "excellent"
            accuracyM <= 10f -> "good"
            accuracyM <= 20f -> "fair"
            else -> "poor"
        }

    private fun refreshRecordingPhaseForCurrentAccuracy() {
        if (!isFormalRecording()) return
        val accuracy = currentAccuracyM ?: return
        recordingPhase = if (accuracy > FORMAL_RECORDING_ACCURACY_M) {
            GnssRecordingPhase.POOR_ACCURACY
        } else {
            GnssRecordingPhase.RECORDING
        }
    }

    private fun updateLocationStats(accuracyM: Float) {
        trackPointCount++
        accuracySum += accuracyM.toDouble()
        bestAccuracyM = bestAccuracyM?.let { minOf(it, accuracyM) } ?: accuracyM
        worstAccuracyM = worstAccuracyM?.let { maxOf(it, accuracyM) } ?: accuracyM
    }

    private fun onRawMeasurementsFrame(frame: RawGnssMeasurementsFrame) {
        val writer = csvWriter ?: return
        val sid = sessionId ?: return
        if (frame.measurements.isEmpty()) return
        maybeAllowPoorAccuracyRecording()
        if (!isFormalRecording()) return
        val ts = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()

        enqueueCsvWrite {
            try {
                val lines = writer.writeRawMeasurementsFrame(
                    timestampMs = ts,
                    elapsedRealtimeNanos = elapsedNanos,
                    sessionId = sid,
                    frame = frame,
                )
                if (lines > 0) {
                    rawRecordsWritten.addAndGet(lines)
                    maybeRefreshNotification(totalRecordsWritten())
                }
                postWriteStats()
            } catch (e: Exception) {
                mainHandler.post {
                    publishError("写入 Raw GNSS CSV 失败: ${e.message}")
                }
            }
        }
    }

    private fun onNmeaFrame(frame: NmeaMessageFrame) {
        val writer = csvWriter ?: return
        val sid = sessionId ?: return
        updateNmeaState(frame.message)
        maybeAllowPoorAccuracyRecording()
        postSessionQualityState()
        if (!isFormalRecording()) return
        val ts = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()

        enqueueCsvWrite {
            try {
                val lines = writer.writeNmeaMessage(
                    timestampMs = ts,
                    elapsedRealtimeNanos = elapsedNanos,
                    sessionId = sid,
                    nmeaTimestampMs = frame.nmeaTimestampMs,
                    message = frame.message,
                )
                if (lines > 0) {
                    nmeaRecordsWritten.addAndGet(lines)
                    maybeRefreshNotification(totalRecordsWritten())
                }
                postWriteStats()
            } catch (e: Exception) {
                mainHandler.post {
                    publishError("写入 NMEA CSV 失败: ${e.message}")
                }
            }
        }
    }

    private fun updateNmeaState(message: String) {
        when (parseNmeaType(message)) {
            "GGA" -> hasNmeaGga = true
            "RMC" -> hasNmeaRmc = true
            "GSA" -> hasNmeaGsa = true
            "GSV" -> hasNmeaGsv = true
        }
    }

    private fun parseNmeaType(message: String): String? {
        val sentence = message.trim()
        if (!sentence.startsWith("\$") || sentence.length < 6) return null
        return sentence.substring(3, 6).uppercase()
    }

    private fun nmeaWarning(): String? =
        if (hasNmeaGsv && !hasNmeaGga && !hasNmeaRmc && !hasNmeaGsa) {
            if (!nmeaIncompleteWarningLogged) {
                nmeaIncompleteWarningLogged = true
                Log.w(TAG, "Only GSV NMEA received; GGA/RMC/GSA positioning sentences are missing")
            }
            "当前仅收到卫星可见信息，缺少完整定位解算 NMEA。"
        } else {
            nmeaIncompleteWarningLogged = false
            null
        }

    private fun postSessionQualityState() {
        mainHandler.post {
            _sessionState.update { prev ->
                prev.copy(
                    recordingPhase = recordingPhase,
                    visibleSatelliteCount = latestVisibleSatelliteCount,
                    usedInFixCount = latestUsedInFixCount,
                    averageCn0DbHz = latestAverageCn0DbHz,
                    currentAccuracyM = currentAccuracyM,
                    canStartFormalRecording = canStartFormalRecording(),
                    gnssFixWarning = gnssFixWarning(),
                    nmeaWarning = nmeaWarning(),
                    hasNmeaGga = hasNmeaGga,
                    hasNmeaRmc = hasNmeaRmc,
                    hasNmeaGsa = hasNmeaGsa,
                    hasNmeaGsv = hasNmeaGsv,
                    recordingDurationMs = currentRecordingDurationMs(),
                    trackPointCount = trackPointCount,
                    averageAccuracyM = averageAccuracyM(),
                    bestAccuracyM = bestAccuracyM,
                    worstAccuracyM = worstAccuracyM,
                    kmlGenerated = kmlGenerated,
                    lastUpdateElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                )
            }
        }
    }

    private fun postWriteStats() {
        mainHandler.post {
            _sessionState.update { prev ->
                prev.copy(
                    recordingPhase = recordingPhase,
                    currentAccuracyM = currentAccuracyM,
                    visibleSatelliteCount = latestVisibleSatelliteCount,
                    usedInFixCount = latestUsedInFixCount,
                    averageCn0DbHz = latestAverageCn0DbHz,
                    canStartFormalRecording = canStartFormalRecording(),
                    gnssFixWarning = gnssFixWarning(),
                    nmeaWarning = nmeaWarning(),
                    hasNmeaGga = hasNmeaGga,
                    hasNmeaRmc = hasNmeaRmc,
                    hasNmeaGsa = hasNmeaGsa,
                    hasNmeaGsv = hasNmeaGsv,
                    lastUpdateElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    recordsWritten = recordsWritten.get(),
                    rawRecordsWritten = rawRecordsWritten.get(),
                    nmeaRecordsWritten = nmeaRecordsWritten.get(),
                    recordingDurationMs = currentRecordingDurationMs(),
                    trackPointCount = trackPointCount,
                    averageAccuracyM = averageAccuracyM(),
                    bestAccuracyM = bestAccuracyM,
                    worstAccuracyM = worstAccuracyM,
                    kmlGenerated = kmlGenerated,
                    csvPath = csvPath,
                    rawCsvPath = rawCsvPath,
                    nmeaCsvPath = nmeaCsvPath,
                    locationCsvPath = locationCsvPath,
                    trackKmlPath = trackKmlPath,
                    sessionDirectoryPath = sessionDirectoryPath,
                )
            }
        }
    }

    private var lastNotifiedRecords = 0L
    private fun totalRecordsWritten(): Long =
        recordsWritten.get() + rawRecordsWritten.get() + nmeaRecordsWritten.get()

    private fun currentRecordingDurationMs(): Long {
        val started = recordingStartedElapsedMs
        if (started <= 0L) return 0L
        return (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
    }

    private fun averageAccuracyM(): Float? =
        if (trackPointCount > 0L) (accuracySum / trackPointCount).toFloat() else null

    private fun maybeRefreshNotification(total: Long) {
        if (total <= 0L) return
        if (lastNotifiedRecords != 0L && total - lastNotifiedRecords < 25) return
        lastNotifiedRecords = total
        mainHandler.post { updateForegroundNotification() }
    }

    private fun stopLoggingInternal(reason: String?, stopService: Boolean) {
        if (!isLogging) {
            return
        }
        isLogging = false
        collector?.stop()
        collector = null
        gnssThread?.quitSafely()
        gnssThread = null

        csvExecutor.shutdown()
        try {
            if (!csvExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                csvExecutor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            csvExecutor.shutdownNow()
        }

        try {
            csvWriter?.closeSafely()
            locationCsvLogger?.closeSafely()
        } finally {
            csvWriter = null
            locationCsvLogger = null
        }

        val kmlMsg = exportTrackKmlOnStop()
        recordingPhase = GnssRecordingPhase.STOPPED

        _sessionState.update {
            it.copy(
                status = LoggingUiStatus.STOPPED,
                recordingPhase = recordingPhase,
                satellites = emptyList(),
                visibleSatelliteCount = latestVisibleSatelliteCount,
                usedInFixCount = latestUsedInFixCount,
                averageCn0DbHz = latestAverageCn0DbHz,
                currentAccuracyM = currentAccuracyM,
                canStartFormalRecording = false,
                gnssFixWarning = gnssFixWarning(),
                nmeaWarning = nmeaWarning(),
                hasNmeaGga = hasNmeaGga,
                hasNmeaRmc = hasNmeaRmc,
                hasNmeaGsa = hasNmeaGsa,
                hasNmeaGsv = hasNmeaGsv,
                lastError = kmlMsg ?: reason,
                recordsWritten = recordsWritten.get(),
                rawRecordsWritten = rawRecordsWritten.get(),
                nmeaRecordsWritten = nmeaRecordsWritten.get(),
                recordingDurationMs = currentRecordingDurationMs(),
                trackPointCount = trackPointCount,
                averageAccuracyM = averageAccuracyM(),
                bestAccuracyM = bestAccuracyM,
                worstAccuracyM = worstAccuracyM,
                kmlGenerated = kmlGenerated,
                csvPath = csvPath,
                rawCsvPath = rawCsvPath,
                nmeaCsvPath = nmeaCsvPath,
                sessionDirectoryPath = sessionDirectoryPath,
                locationCsvPath = locationCsvPath,
                trackKmlPath = trackKmlPath,
            )
        }
        provisionalForeground = false
        stopForegroundStopCompat()
        if (stopService) {
            stopSelf()
        }
    }

    private fun exportTrackKmlOnStop(): String? {
        val locationFile = locationCsvPath?.let { java.io.File(it) }
        val kmlFile = trackKmlPath?.let { java.io.File(it) }
        if (locationFile == null || kmlFile == null) return null

        return try {
            val result = KmlExporter.exportFromLocationCsv(locationFile, kmlFile)
            kmlGenerated = true
            if (result.lineGenerated) {
                "已生成 CSV 和 KML，KML 可导入 Google Earth Pro。track.kml 已生成（${result.pointCount} 个轨迹点）"
            } else {
                "KML 已生成，但有效轨迹点不足 2 个，未生成轨迹线"
            }
        } catch (e: NoValidTrackPointsException) {
            Log.w(TAG, "Skip KML export: ${e.message}")
            kmlGenerated = false
            "track.kml：未生成，无有效轨迹点"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export KML from ${locationFile.absolutePath}", e)
            kmlGenerated = false
            "KML 生成失败: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun stopForegroundStopCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    @SuppressLint("NewApi")
    private fun startForegroundWithType(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun updateForegroundNotification() {
        if (!isLogging) return
        val notification = NotificationHelper.buildForegroundNotification(
            this,
            csvFileName,
            totalRecordsWritten(),
        )
        val mgr = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        mgr.notify(NOTIFICATION_ID, notification)
    }

    private fun publishError(msg: String) {
        _sessionState.update { it.copy(lastError = msg) }
    }

    companion object {
        private const val TAG = "GnssLoggerService"
        private const val FORMAL_RECORDING_ACCURACY_M = 20f
        private const val MAX_RECORDING_ACCURACY_M = 100f
        private const val WARMUP_TIMEOUT_MS = 30_000L
        private const val FIX_WARNING_AFTER_MS = 10_000L
        const val NOTIFICATION_ID = 77001
        const val PREFS = "gnss_logger_prefs"
        const val KEY_PREFIX = "filename_prefix"
        const val KEY_DATE_SUBDIR = "use_date_subdir"
        const val KEY_NMEA = "record_nmea"
        const val KEY_RAW_MEASUREMENTS = "record_raw_measurements"


        private val _sessionState = MutableStateFlow(GnssSessionState())
        val sessionState: StateFlow<GnssSessionState> = _sessionState.asStateFlow()

        @Volatile
        private var runningInstance: GnssLoggerService? = null


        fun readFilenamePrefix(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PREFIX, "gnss_log")
                ?.takeIf { it.isNotBlank() }
                ?: "gnss_log"

        fun readUseDateSubdir(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DATE_SUBDIR, true)

        fun readRecordNmea(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_NMEA, true)

        fun readRecordRawMeasurements(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_RAW_MEASUREMENTS, true)

        fun writeUiPrefs(
            context: Context,
            prefix: String,
            useDateSubdir: Boolean,
            recordNmea: Boolean,
            recordRawMeasurements: Boolean,
        ) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PREFIX, prefix.ifBlank { "gnss_log" })
                .putBoolean(KEY_DATE_SUBDIR, useDateSubdir)
                .putBoolean(KEY_NMEA, recordNmea)
                .putBoolean(KEY_RAW_MEASUREMENTS, recordRawMeasurements)
                .apply()
        }
    }
}
