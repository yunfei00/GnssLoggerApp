package com.example.gnsslogger

import android.Manifest
import android.content.ContentValues
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gnsslogger.data.GnssRecordingPhase
import com.example.gnsslogger.data.GnssSessionState
import com.example.gnsslogger.data.LoggingUiStatus
import com.example.gnsslogger.databinding.ActivityMainBinding
import com.example.gnsslogger.storage.KmlExporter
import com.example.gnsslogger.storage.NoValidTrackPointsException
import com.example.gnsslogger.ui.MainViewModel
import com.example.gnsslogger.ui.SatelliteTableAdapter
import com.example.gnsslogger.util.PermissionHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels {
        AndroidViewModelFactory.getInstance(application)
    }
    private val adapter = SatelliteTableAdapter()
    private var pendingSaveToDownloads = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (collectMissingPermissions().isNotEmpty()) {
            Toast.makeText(this, R.string.toast_permissions_required, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        proceedAfterRuntimePermissions()
    }

    private val backgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        startLoggingService()
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingSaveToDownloads) {
            pendingSaveToDownloads = false
            saveCurrentSessionDataToDownloads()
        } else {
            pendingSaveToDownloads = false
            Toast.makeText(this, R.string.toast_storage_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    private fun collectMissingPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionHelper.hasPostNotifications(this)
        ) {
            list.addAll(PermissionHelper.notificationsPermission())
        }
        if (!PermissionHelper.hasAnyLocation(this)) {
            list.addAll(PermissionHelper.baseLocationPermissions())
        }
        return list
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerSatellites.layoutManager = LinearLayoutManager(this)
        binding.recyclerSatellites.adapter = adapter
        binding.recyclerSatellites.isNestedScrollingEnabled = false

        loadFormFromPrefs()

        binding.buttonStart.setOnClickListener {
            persistUiFromForm()
            requestChainOrStart()
        }
        binding.buttonStop.setOnClickListener {
            val intent = Intent(this, GnssLoggerService::class.java).apply {
                action = AppActions.ACTION_STOP_LOGGING
            }
            startService(intent)
        }
        binding.buttonShareCsv.setOnClickListener { shareCurrentSessionData() }
        binding.buttonSaveDownloads.setOnClickListener { saveCurrentSessionDataToDownloads() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun loadFormFromPrefs() {
        val p = getSharedPreferences(GnssLoggerService.PREFS, MODE_PRIVATE)
        binding.inputPrefix.setText(
            p.getString(GnssLoggerService.KEY_PREFIX, "gnss_log") ?: "gnss_log",
        )
        binding.switchDateSubdir.isChecked = p.getBoolean(GnssLoggerService.KEY_DATE_SUBDIR, true)
        binding.switchNmea.isChecked = p.getBoolean(GnssLoggerService.KEY_NMEA, true)
        binding.switchRaw.isChecked = p.getBoolean(GnssLoggerService.KEY_RAW_MEASUREMENTS, true)
    }

    private fun persistUiFromForm() {
        val prefix = binding.inputPrefix.text?.toString()?.trim() ?: "gnss_log"
        viewModel.persistUiPrefs(
            prefix = prefix,
            useDateSubdir = binding.switchDateSubdir.isChecked,
            recordNmea = binding.switchNmea.isChecked,
            recordRawMeasurements = binding.switchRaw.isChecked,
        )
    }

    private fun requestChainOrStart() {
        val missing = collectMissingPermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        proceedAfterRuntimePermissions()
    }

    private fun proceedAfterRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !PermissionHelper.hasBackgroundLocation(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_bg_location_title)
                .setMessage(R.string.dialog_bg_location_message)
                .setPositiveButton(R.string.action_grant) { _, _ ->
                    backgroundPermissionLauncher.launch(PermissionHelper.backgroundPermission())
                }
                .setNegativeButton(R.string.action_skip) { _, _ ->
                    startLoggingService()
                }
                .show()
            return
        }
        startLoggingService()
    }

    private fun startLoggingService() {
        if (!PermissionHelper.hasAnyLocation(this)) {
            Toast.makeText(this, R.string.toast_permissions_required, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, GnssLoggerService::class.java).apply {
            action = AppActions.ACTION_START_LOGGING
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun renderState(state: GnssSessionState) {
        binding.textStatus.text = when (state.status) {
            LoggingUiStatus.IDLE -> getString(R.string.status_idle)
            LoggingUiStatus.LOGGING -> getString(R.string.status_logging)
            LoggingUiStatus.STOPPED -> getString(R.string.status_stopped)
        }
        binding.textCsvPath.text = buildString {
            append(
                getString(
                    R.string.label_file_status,
                    buildFileStatusLines(state).joinToString(separator = "\n"),
                ),
            )
            if (state.sessionId != null) {
                append('\n')
                append(getString(R.string.label_session, state.sessionId))
            }
        }
        val exportStatus = buildExportStatusText(state)
        if (exportStatus.isNullOrBlank()) {
            binding.textExportStatus.visibility = View.GONE
        } else {
            binding.textExportStatus.visibility = View.VISIBLE
            binding.textExportStatus.text = exportStatus
        }
        binding.textGnssStatus.text = buildGnssStatusText(state)
        val sessionStats = buildSessionStatsText(state)
        if (sessionStats.isNullOrBlank()) {
            binding.textSessionStats.visibility = View.GONE
        } else {
            binding.textSessionStats.visibility = View.VISIBLE
            binding.textSessionStats.text = sessionStats
        }
        binding.textSatCounts.text = getString(
            R.string.label_counts,
            state.visibleSatelliteCount,
            state.usedInFixCount,
            state.recordsWritten,
            state.rawRecordsWritten,
            state.nmeaRecordsWritten,
        )
        val last = when {
            state.lastUpdateElapsedRealtimeMs <= 0L -> "--"
            else -> {
                val age = SystemClock.elapsedRealtime() - state.lastUpdateElapsedRealtimeMs
                when {
                    age < 1500L -> "刚刚"
                    age < 60_000L -> "约 ${age / 1000} 秒前"
                    else -> "约 ${age / 60_000} 分钟前"
                }
            }
        }
        binding.textLastUpdate.text = getString(R.string.label_last_update, last)
        val errorMessage = state.lastError?.takeUnless(::isExportInfoMessage)
        if (errorMessage.isNullOrBlank()) {
            binding.textError.visibility = View.GONE
        } else {
            binding.textError.visibility = View.VISIBLE
            binding.textError.text = errorMessage
        }
        if (state.satellites.isEmpty()) {
            binding.textSatelliteTableHint.visibility = View.GONE
        } else {
            binding.textSatelliteTableHint.visibility = View.VISIBLE
            binding.textSatelliteTableHint.text = getString(
                R.string.label_satellite_table_hint,
                state.satellites.size,
            )
        }
        adapter.submit(state.satellites)
        updateSatelliteRecyclerHeight(state.satellites.size)
    }

    private fun buildFileStatusLines(state: GnssSessionState): List<String> = listOf(
        "location.csv：${generatedStatus(state.locationCsvPath)}",
        "satellites.csv：${generatedStatus(state.csvPath)}",
        "track.kml：${trackKmlStatus(state)}",
    )

    private fun buildGnssStatusText(state: GnssSessionState): String =
        buildList {
            add("当前精度：${state.currentAccuracyM?.let { formatMeters(it) } ?: "--"}")
            add("定位质量：${qualityLabel(state.currentAccuracyM)}")
            add("可见卫星：${state.visibleSatelliteCount}")
            add("参与定位：${state.usedInFixCount}")
            add("平均 CN0：${state.averageCn0DbHz?.let { String.format(java.util.Locale.US, "%.1f dB-Hz", it) } ?: "--"}")
            add("采集状态：${recordingPhaseLabel(state)}")
            add("是否可以正式记录：${if (state.canStartFormalRecording || state.recordingPhase != GnssRecordingPhase.WARMING_UP) "是" else "否"}")
            state.gnssFixWarning?.let { add(it) }
            state.nmeaWarning?.let { add(it) }
        }.joinToString(separator = "\n")

    private fun buildSessionStatsText(state: GnssSessionState): String? {
        if (state.status != LoggingUiStatus.STOPPED) return null
        return buildList {
            add("本次统计：")
            add("采集时长：${formatDuration(state.recordingDurationMs)}")
            add("轨迹点数量：${state.trackPointCount}")
            add("平均精度：${state.averageAccuracyM?.let { formatMeters(it) } ?: "--"}")
            add("最好精度：${state.bestAccuracyM?.let { formatMeters(it) } ?: "--"}")
            add("最差精度：${state.worstAccuracyM?.let { formatMeters(it) } ?: "--"}")
            add("KML：${if (state.kmlGenerated) "已生成" else "未生成"}")
        }.joinToString(separator = "\n")
    }

    private fun qualityLabel(accuracyM: Float?): String =
        when {
            accuracyM == null || !accuracyM.isFinite() -> "--"
            accuracyM <= 5f -> "优秀"
            accuracyM <= 10f -> "良好"
            accuracyM <= 20f -> "一般"
            else -> "较差"
        }

    private fun recordingPhaseLabel(state: GnssSessionState): String =
        when (state.recordingPhase) {
            GnssRecordingPhase.WARMING_UP -> "预热中"
            GnssRecordingPhase.RECORDING -> "正式记录中"
            GnssRecordingPhase.POOR_ACCURACY -> "精度较差"
            GnssRecordingPhase.STOPPED -> "已停止"
            GnssRecordingPhase.IDLE -> "未启动"
        }

    private fun formatMeters(value: Float): String =
        String.format(java.util.Locale.US, "%.1f m", value)

    private fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000).coerceAtLeast(0L)
        val minutes = seconds / 60
        val remainSeconds = seconds % 60
        return if (minutes > 0) "${minutes}分${remainSeconds}秒" else "${remainSeconds}秒"
    }

    private fun generatedStatus(path: String?): String =
        if (path?.let(::isGeneratedFile) == true) "已生成" else "未生成"

    private fun trackKmlStatus(state: GnssSessionState): String {
        if (state.trackKmlPath?.let(::isGeneratedFile) == true) return "已生成"
        if (hasNoValidTrackPointMessage(state.lastError)) return "未生成，无有效轨迹点"
        return if (state.status == LoggingUiStatus.STOPPED) "未生成" else "停止采集后生成"
    }

    private fun buildExportStatusText(state: GnssSessionState): String? {
        if (state.status != LoggingUiStatus.STOPPED || state.csvPath == null && state.locationCsvPath == null) {
            return null
        }
        return when {
            state.lastError?.startsWith("KML 已生成") == true ->
                "已生成 CSV 和 KML；有效轨迹点不足 2 个，KML 未生成轨迹线。"

            state.trackKmlPath?.let(::isGeneratedFile) == true ->
                "已生成 CSV 和 KML，KML 可导入 Google Earth Pro。"

            hasNoValidTrackPointMessage(state.lastError) ->
                "已生成 CSV；track.kml：未生成，无有效轨迹点。"

            else -> "已生成 CSV；track.kml 未生成。"
        }
    }

    private fun isGeneratedFile(path: String): Boolean =
        File(path).let { it.exists() && it.isFile && it.length() > 0L }

    private fun hasNoValidTrackPointMessage(message: String?): Boolean =
        message?.contains("无有效", ignoreCase = true) == true

    private fun isExportInfoMessage(message: String): Boolean =
        message.startsWith("已生成 CSV") ||
            message.startsWith("KML 已生成") ||
            message.startsWith("track.kml 已生成") ||
            message.startsWith("track.kml：未生成") ||
            message.contains("没有有效经纬度")

    /**
     * RecyclerView 放在 [Nested]ScrollView 里且高度为 wrap_content 时，系统常把 RV 测成「约一两行」，
     * 与顶部「可见卫星数」不一致。这里按行数 × 估算行高给出总高度，整页滚动即可看到全部卫星。
     */
    private fun updateSatelliteRecyclerHeight(itemCount: Int) {
        val lp = binding.recyclerSatellites.layoutParams
        val rowPx = resources.getDimensionPixelSize(R.dimen.satellite_table_row_height)
        lp.height = if (itemCount <= 0) {
            ViewGroup.LayoutParams.WRAP_CONTENT
        } else {
            rowPx * itemCount
        }
        binding.recyclerSatellites.layoutParams = lp
    }

    private fun shareCurrentSessionData() {
        val state = viewModel.uiState.value
        if (!canExportFinishedSession(state)) return

        lifecycleScope.launch {
            val exportFiles = withContext(Dispatchers.IO) { prepareExportFiles(state) }
            if (exportFiles.files.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.toast_no_csv_to_share, Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                openShareSheet(exportFiles.files)
                Toast.makeText(this@MainActivity, R.string.toast_share_sheet_opened, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open share sheet", e)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_share_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun saveCurrentSessionDataToDownloads() {
        val state = viewModel.uiState.value
        if (!canExportFinishedSession(state)) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingSaveToDownloads = true
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        lifecycleScope.launch {
            try {
                val exportFiles = withContext(Dispatchers.IO) { prepareExportFiles(state) }
                if (exportFiles.files.isEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.toast_no_csv_to_share, Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val relativePath = withContext(Dispatchers.IO) {
                    saveFilesToDownloads(exportFiles.files, exportFiles.sessionName)
                }
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_saved_to_downloads, relativePath),
                    Toast.LENGTH_LONG,
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save GNSS files to Downloads", e)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_save_downloads_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun canExportFinishedSession(state: GnssSessionState): Boolean {
        if (state.status != LoggingUiStatus.STOPPED) {
            Toast.makeText(this, R.string.toast_finish_collection_before_export, Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun openShareSheet(files: List<File>) {
        val uris = ArrayList<Uri>(
            files.map { file ->
                FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file,
                )
            },
        )
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newUri(contentResolver, files.first().name, uris.first()).apply {
                uris.drop(1).forEach { uri -> addItem(ClipData.Item(uri)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.action_share_csv)))
    }

    private fun prepareExportFiles(state: GnssSessionState): ExportFiles {
        val locationFile = state.locationCsvPath?.let(::File)?.takeIf(::isShareableFile)
        val satelliteFile = state.csvPath?.let(::File)?.takeIf(::isShareableFile)
        val kmlFile = ensureTrackKml(state, locationFile)?.takeIf(::isShareableFile)
        val files = listOfNotNull(locationFile, satelliteFile, kmlFile).distinctBy { it.absolutePath }
        return ExportFiles(files = files, sessionName = sessionNameFor(state, files))
    }

    private fun ensureTrackKml(state: GnssSessionState, locationFile: File?): File? {
        val existingKmlFile = state.trackKmlPath?.let(::File)
        if (existingKmlFile?.let(::isShareableFile) == true) return existingKmlFile

        val outputKml = existingKmlFile
            ?: locationFile?.let(::trackKmlFileForLocationCsv)
            ?: return null
        if (locationFile == null || !locationFile.exists() || !locationFile.isFile) return null

        return try {
            KmlExporter.exportFromLocationCsv(locationFile, outputKml).outputFile
        } catch (e: NoValidTrackPointsException) {
            Log.w(TAG, "Cannot export KML: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export KML from ${locationFile.absolutePath}", e)
            null
        }
    }

    private fun saveFilesToDownloads(files: List<File>, sessionName: String): String {
        val relativePath = downloadRelativePath(sessionName)
        files.forEach { file ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveFileToDownloadsWithMediaStore(file, relativePath)
            } else {
                saveFileToDownloadsLegacy(file, sessionName)
            }
        }
        return relativePath
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveFileToDownloadsWithMediaStore(source: File, relativePath: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(source))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建 ${source.name}")

        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("无法写入 ${source.name}")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveFileToDownloadsLegacy(source: File, sessionName: String) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "GnssLogger/$sessionName",
        )
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("无法创建目录 ${dir.absolutePath}")
        }
        source.copyTo(File(dir, source.name), overwrite = true)
    }

    private fun downloadRelativePath(sessionName: String): String =
        "${Environment.DIRECTORY_DOWNLOADS}/GnssLogger/$sessionName/"

    private fun trackKmlFileForLocationCsv(locationFile: File): File {
        val kmlName = if (locationFile.name.endsWith("_location.csv")) {
            locationFile.name.removeSuffix("_location.csv") + "_track.kml"
        } else {
            locationFile.nameWithoutExtension + "_track.kml"
        }
        return locationFile.parentFile?.let { File(it, kmlName) } ?: File(kmlName)
    }

    private fun isShareableFile(file: File): Boolean =
        file.exists() && file.isFile && file.length() > 0L

    private fun sessionNameFor(state: GnssSessionState, files: List<File>): String {
        val fromPath = sequenceOf(state.locationCsvPath, state.csvPath, state.trackKmlPath)
            .filterNotNull()
            .map { File(it) }
            .plus(files.asSequence())
            .mapNotNull { sessionNameFromFileName(it.name) }
            .firstOrNull()
        return sanitizePathSegment(fromPath ?: state.sessionId ?: "session")
    }

    private fun sessionNameFromFileName(name: String): String? {
        val suffixes = listOf("_location.csv", "_satellites.csv", "_track.kml")
        return suffixes.firstNotNullOfOrNull { suffix ->
            name.takeIf { it.endsWith(suffix) }?.removeSuffix(suffix)
        }
    }

    private fun sanitizePathSegment(raw: String): String =
        raw.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_', '.', '-')
            .ifBlank { "session" }

    private fun mimeTypeFor(file: File): String =
        when (file.extension.lowercase()) {
            "csv" -> "text/csv"
            "kml" -> "application/vnd.google-earth.kml+xml"
            else -> "application/octet-stream"
        }

    private data class ExportFiles(
        val files: List<File>,
        val sessionName: String,
    )

    companion object {
        private const val TAG = "MainActivity"
    }

}
