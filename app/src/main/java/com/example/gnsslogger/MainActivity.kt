package com.example.gnsslogger

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gnsslogger.data.GnssSessionState
import com.example.gnsslogger.data.LoggingUiStatus
import com.example.gnsslogger.databinding.ActivityMainBinding
import com.example.gnsslogger.storage.KmlExporter
import com.example.gnsslogger.storage.LogFileManager
import com.example.gnsslogger.storage.NoValidTrackPointsException
import com.example.gnsslogger.ui.MainViewModel
import com.example.gnsslogger.ui.SatelliteTableAdapter
import com.example.gnsslogger.util.PermissionHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels {
        AndroidViewModelFactory.getInstance(application)
    }
    private val adapter = SatelliteTableAdapter()

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
        refreshSaveDirLabel()

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
        binding.buttonOpenDir.setOnClickListener { openSaveDirectory() }
        binding.buttonShareCsv.setOnClickListener { shareCurrentCsvFiles() }

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

    private fun refreshSaveDirLabel() {
        val dir = LogFileManager(this).gnssRootDir()
        binding.textSaveDir.text = dir.absolutePath
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
                    R.string.label_csv,
                    buildList {
                        add("卫星=${state.csvPath ?: "-"}")
                        add("Raw=${state.rawCsvPath ?: "-"}")
                        add("NMEA=${state.nmeaCsvPath ?: "-"}")
                        add("Location=${state.locationCsvPath ?: "-"}")
                        add("Track KML=${state.trackKmlPath ?: "-"}")
                    }.joinToString(separator = "\n"),
                ),
            )
            if (state.sessionId != null) {
                append('\n')
                append(getString(R.string.label_session, state.sessionId))
            }
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
        if (state.lastError.isNullOrBlank()) {
            binding.textError.visibility = View.GONE
        } else {
            binding.textError.visibility = View.VISIBLE
            binding.textError.text = state.lastError
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

    private fun openSaveDirectory() {
        refreshSaveDirLabel()
        val dir = LogFileManager(this).gnssRootDir()
        if (!dir.exists()) dir.mkdirs()
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            dir,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "resource/folder")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val alt = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.action_open_dir)))
        } catch (_: Exception) {
            try {
                startActivity(Intent.createChooser(alt, getString(R.string.action_open_dir)))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.toast_open_dir_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shareCurrentCsvFiles() {
        val state = viewModel.uiState.value
        val kmlFile = ensureTrackKmlForShare(state)
        val files = (
            listOfNotNull(
                state.csvPath,
                state.rawCsvPath,
                state.nmeaCsvPath,
                state.locationCsvPath,
            ).map(::File) + listOfNotNull(kmlFile)
            )
            .filter { it.exists() && it.isFile && it.length() > 0L }
            .distinctBy { it.absolutePath }

        if (files.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_csv_to_share, Toast.LENGTH_SHORT).show()
            return
        }

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

    private fun ensureTrackKmlForShare(state: GnssSessionState): File? {
        val locationFile = state.locationCsvPath?.let(::File)
        val kmlFile = state.trackKmlPath?.let(::File)
            ?: locationFile?.let(::trackKmlFileForLocationCsv)
            ?: return null

        if (kmlFile.exists() && kmlFile.isFile && kmlFile.length() > 0L) {
            return kmlFile
        }
        if (locationFile == null || !locationFile.exists() || !locationFile.isFile) {
            return null
        }

        return try {
            KmlExporter.exportFromLocationCsv(locationFile, kmlFile).outputFile
        } catch (e: NoValidTrackPointsException) {
            Log.w(TAG, "Cannot export KML: ${e.message}")
            Toast.makeText(this, R.string.toast_no_valid_location_for_kml, Toast.LENGTH_LONG).show()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export KML from ${locationFile.absolutePath}", e)
            Toast.makeText(
                this,
                getString(R.string.toast_kml_export_failed, e.message ?: e.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
            null
        }
    }

    private fun trackKmlFileForLocationCsv(locationFile: File): File {
        val kmlName = if (locationFile.name.endsWith("_location.csv")) {
            locationFile.name.removeSuffix("_location.csv") + "_track.kml"
        } else {
            locationFile.nameWithoutExtension + "_track.kml"
        }
        return locationFile.parentFile?.let { File(it, kmlName) } ?: File(kmlName)
    }

    companion object {
        private const val TAG = "MainActivity"
    }

}
