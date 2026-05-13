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
        } catch (_: Exception) {
        }
        csvWriter = null
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

        isLogging = true
        provisionalForeground = false
        recordsWritten.set(0L)
        rawRecordsWritten.set(0L)
        nmeaRecordsWritten.set(0L)
        lastNotifiedRecords = 0L
        val notification = NotificationHelper.buildForegroundNotification(
            this,
            csvFileName,
            0L,
        )
        startForegroundWithType(notification)

        _sessionState.update {
            it.copy(
                status = LoggingUiStatus.LOGGING,
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
            )
        }
    }

    private fun onGnssFrame(frame: GnssStatusFrame) {
        val writer = csvWriter ?: return
        val sid = sessionId ?: return
        val ts = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()
        val loc = collector?.lastLocation

        enqueueCsvWrite {
            try {
                val lines = if (frame.satellites.isEmpty()) {
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
                            visibleSatelliteCount = frame.visibleCount,
                            usedInFixCount = frame.usedInFixCount,
                            satellites = frame.satellites,
                            lastUpdateElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                            recordsWritten = recordsWritten.get(),
                            rawRecordsWritten = rawRecordsWritten.get(),
                            nmeaRecordsWritten = nmeaRecordsWritten.get(),
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


    private fun onLocationUpdate(location: android.location.Location) {
        val logger = locationCsvLogger ?: return
        enqueueCsvWrite {
            try {
                logger.writeLocation(location, scene = "default")
            } catch (e: Exception) {
                mainHandler.post { publishError("写入 location.csv 失败: ${e.message}") }
            }
        }
    }

    private fun onRawMeasurementsFrame(frame: RawGnssMeasurementsFrame) {
        val writer = csvWriter ?: return
        val sid = sessionId ?: return
        if (frame.measurements.isEmpty()) return
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

    private fun postWriteStats() {
        mainHandler.post {
            _sessionState.update { prev ->
                prev.copy(
                    lastUpdateElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    recordsWritten = recordsWritten.get(),
                    rawRecordsWritten = rawRecordsWritten.get(),
                    nmeaRecordsWritten = nmeaRecordsWritten.get(),
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

        _sessionState.update {
            it.copy(
                status = LoggingUiStatus.STOPPED,
                satellites = emptyList(),
                visibleSatelliteCount = 0,
                usedInFixCount = 0,
                lastError = kmlMsg ?: reason,
                recordsWritten = recordsWritten.get(),
                rawRecordsWritten = rawRecordsWritten.get(),
                nmeaRecordsWritten = nmeaRecordsWritten.get(),
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
            "track.kml 已生成（${result.pointCount} 个轨迹点），可导入 Google Earth Pro"
        } catch (e: NoValidTrackPointsException) {
            Log.w(TAG, "Skip KML export: ${e.message}")
            "location.csv 没有有效经纬度数据，未生成 track.kml"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export KML from ${locationFile.absolutePath}", e)
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
