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
import androidx.core.content.ContextCompat
import com.example.gnsslogger.data.GnssSessionState
import com.example.gnsslogger.data.LoggingUiStatus
import com.example.gnsslogger.gnss.GnssCollector
import com.example.gnsslogger.gnss.GnssStatusFrame
import com.example.gnsslogger.storage.CsvGnssWriter
import com.example.gnsslogger.storage.LogFileManager
import com.example.gnsslogger.util.DeviceInfo
import com.example.gnsslogger.util.NotificationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class GnssLoggerService : Service() {

    private var isLogging = false
    private var gnssThread: HandlerThread? = null
    private var collector: GnssCollector? = null
    private var csvWriter: CsvGnssWriter? = null
    private var sessionId: String? = null
    private var csvPath: String? = null
    private var csvFileName: String? = null
    private var provisionalForeground = false

    private val csvExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gnss-csv-writer").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recordsWritten = AtomicLong(0L)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        NotificationHelper.ensureChannel(this)
        val initialScene = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_SCENE, null)
            ?.takeIf { it.isNotBlank() }
            ?: "unknown_scene"
        synchronized(sceneLock) {
            currentSceneNameInternal = initialScene
        }
        _sessionState.update {
            it.copy(sceneName = initialScene)
        }
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
                            currentSceneName(),
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
            AppActions.ACTION_UPDATE_SCENE -> {
                val name = intent.getStringExtra(AppActions.EXTRA_SCENE_NAME)
                updateSceneName(applicationContext, name)
            }
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
        val prefix = readFilenamePrefix(this)
        val useDate = readUseDateSubdir(this)
        val paths = LogFileManager(this).createNewSession(prefix, useDate)
        sessionId = paths.sessionId
        csvPath = paths.csvAbsolutePath
        csvFileName = paths.csvFileName

        val writer = CsvGnssWriter(
            file = java.io.File(paths.csvAbsolutePath),
            deviceModel = DeviceInfo.model(),
            androidVersion = DeviceInfo.androidRelease(),
            packageVersion = DeviceInfo.packageVersionName(this),
        )
        writer.open()
        csvWriter = writer

        val thread = HandlerThread("gnss-callbacks").apply { start() }
        gnssThread = thread
        val collectorInstance = GnssCollector(this, thread.looper) { frame ->
            onGnssFrame(frame)
        }
        collector = collectorInstance
        collectorInstance.start()

        isLogging = true
        provisionalForeground = false
        recordsWritten.set(0L)
        lastNotifiedRecords = 0L
        val notification = NotificationHelper.buildForegroundNotification(
            this,
            currentSceneName(),
            csvFileName,
            0L,
        )
        startForegroundWithType(notification)

        _sessionState.update {
            it.copy(
                status = LoggingUiStatus.LOGGING,
                sessionId = sessionId,
                csvPath = csvPath,
                sceneName = currentSceneName(),
                recordsWritten = 0L,
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
        val scene = currentSceneName()
        val loc = collector?.lastLocation

        csvExecutor.execute {
            try {
                val lines = if (frame.satellites.isEmpty()) {
                    0L
                } else {
                    writer.writeSatelliteFrame(
                        timestampMs = ts,
                        elapsedRealtimeNanos = elapsedNanos,
                        sessionId = sid,
                        sceneName = scene,
                        location = loc,
                        satelliteCount = frame.visibleCount,
                        usedInFixCount = frame.usedInFixCount,
                        satellites = frame.satellites,
                    )
                }
                if (lines > 0) {
                    val total = recordsWritten.addAndGet(lines)
                    maybeRefreshNotification(total)
                }
                mainHandler.post {
                    _sessionState.update { prev ->
                        prev.copy(
                            visibleSatelliteCount = frame.visibleCount,
                            usedInFixCount = frame.usedInFixCount,
                            satellites = frame.satellites,
                            lastUpdateElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                            recordsWritten = recordsWritten.get(),
                            csvPath = csvPath,
                            sceneName = scene,
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

    private var lastNotifiedRecords = 0L
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
        } finally {
            csvWriter = null
        }

        _sessionState.update {
            it.copy(
                status = LoggingUiStatus.STOPPED,
                satellites = emptyList(),
                visibleSatelliteCount = 0,
                usedInFixCount = 0,
                lastError = reason,
            )
        }
        provisionalForeground = false
        stopForegroundStopCompat()
        if (stopService) {
            stopSelf()
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
            currentSceneName(),
            csvFileName,
            recordsWritten.get(),
        )
        val mgr = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        mgr.notify(NOTIFICATION_ID, notification)
    }

    private fun publishError(msg: String) {
        _sessionState.update { it.copy(lastError = msg) }
    }

    companion object {
        const val NOTIFICATION_ID = 77001
        const val PREFS = "gnss_logger_prefs"
        const val KEY_SCENE = "scene_name"
        const val KEY_PREFIX = "filename_prefix"
        const val KEY_DATE_SUBDIR = "use_date_subdir"
        const val KEY_NMEA = "record_nmea"

        private val sceneLock = Any()
        private var currentSceneNameInternal: String = "unknown_scene"

        private val _sessionState = MutableStateFlow(GnssSessionState())
        val sessionState: StateFlow<GnssSessionState> = _sessionState.asStateFlow()

        @Volatile
        private var runningInstance: GnssLoggerService? = null

        fun updateSceneName(context: Context, scene: String?) {
            val name = if (scene.isNullOrBlank()) "unknown_scene" else scene
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SCENE, name)
                .apply()
            synchronized(sceneLock) {
                currentSceneNameInternal = name
            }
            _sessionState.update { it.copy(sceneName = name) }
            runningInstance?.updateForegroundNotification()
        }

        fun currentSceneName(): String = synchronized(sceneLock) { currentSceneNameInternal }

        fun readFilenamePrefix(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PREFIX, "gnss_log")
                ?.takeIf { it.isNotBlank() }
                ?: "gnss_log"

        fun readUseDateSubdir(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DATE_SUBDIR, true)

        fun writeUiPrefs(
            context: Context,
            prefix: String,
            useDateSubdir: Boolean,
            recordNmea: Boolean,
        ) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PREFIX, prefix.ifBlank { "gnss_log" })
                .putBoolean(KEY_DATE_SUBDIR, useDateSubdir)
                .putBoolean(KEY_NMEA, recordNmea)
                .apply()
        }
    }
}
