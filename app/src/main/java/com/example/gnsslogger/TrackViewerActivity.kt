package com.example.gnsslogger

import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gnsslogger.databinding.ActivityTrackViewerBinding
import com.example.gnsslogger.track.ParsedTrack
import com.example.gnsslogger.track.TrackCanvasView
import com.example.gnsslogger.track.TrackFileParser
import com.example.gnsslogger.track.TrackPoint
import com.example.gnsslogger.track.formatTrackAccuracy
import com.example.gnsslogger.track.formatTrackDistance
import com.example.gnsslogger.track.trackStats
import com.example.gnsslogger.util.applySystemBarPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

class TrackViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrackViewerBinding
    private val layers = mutableListOf<TrackLayer>()
    private var displayMode = DisplayMode.CANVAS

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) {
            Toast.makeText(this, R.string.toast_no_track_file_selected, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        loadUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureOsmDroid()
        binding = ActivityTrackViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding()
        setupMap()

        binding.buttonLoadTrackFiles.setOnClickListener { openFilePicker() }
        binding.buttonResetTrackView.setOnClickListener {
            if (displayMode == DisplayMode.MAP) {
                resetMapViewport()
            } else {
                binding.trackCanvas.resetViewport()
            }
            Toast.makeText(this, R.string.toast_track_view_reset, Toast.LENGTH_SHORT).show()
        }
        binding.displayModeGroup.setOnCheckedChangeListener { _, checkedId ->
            displayMode = if (checkedId == R.id.radioTrackMap) {
                DisplayMode.MAP
            } else {
                DisplayMode.CANVAS
            }
            renderDisplayMode()
            if (displayMode == DisplayMode.MAP) {
                resetMapViewport()
            }
        }

        render()

        val initialPaths = intent.getStringArrayListExtra(EXTRA_FILE_PATHS)
            ?: intent.getStringArrayExtra(EXTRA_FILE_PATHS)?.toCollection(ArrayList())
            ?: intent.getStringExtra(EXTRA_FILE_PATHS)?.let { arrayListOf(it) }
            ?: arrayListOf()
        if (initialPaths.isNotEmpty()) {
            loadPaths(initialPaths)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_PICKER, false)) {
            openFilePicker()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            binding.osmMap.onResume()
        }
    }

    override fun onPause() {
        if (::binding.isInitialized) {
            binding.osmMap.onPause()
        }
        super.onPause()
    }

    private fun openFilePicker() {
        filePicker.launch(arrayOf("text/*", "application/vnd.google-earth.kml+xml", "application/octet-stream"))
    }

    private fun loadPaths(paths: List<String>) {
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                paths.mapNotNull { path ->
                    val file = File(path)
                    runCatching {
                        TrackFileParser.parse(file.name, file.readText(Charsets.UTF_8))
                    }.onFailure {
                        Log.w(TAG, "Failed to parse track file $path", it)
                    }.getOrNull()
                }
            }
            addParsedTracks(parsed)
        }
    }

    private fun loadUris(uris: List<Uri>) {
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        val name = displayNameFor(uri)
                        val content = contentResolver.openInputStream(uri)?.use { input ->
                            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        } ?: return@mapNotNull null
                        TrackFileParser.parse(name, content)
                    }.onFailure {
                        Log.w(TAG, "Failed to parse selected track file $uri", it)
                    }.getOrNull()
                }
            }
            addParsedTracks(parsed)
        }
    }

    private fun addParsedTracks(parsedTracks: List<ParsedTrack>) {
        val validTracks = parsedTracks.filter { it.points.isNotEmpty() }
        if (validTracks.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_valid_track_points, Toast.LENGTH_LONG).show()
            render()
            return
        }

        validTracks.forEach { track ->
            layers.add(
                TrackLayer(
                    name = track.name,
                    sourceType = track.sourceType,
                    points = track.points,
                    color = TRACK_COLORS[layers.size % TRACK_COLORS.size],
                    visible = true,
                ),
            )
        }
        binding.trackCanvas.resetViewport()
        render()
        if (displayMode == DisplayMode.MAP) {
            resetMapViewport()
        }
        Toast.makeText(
            this,
            getString(R.string.toast_track_files_loaded, validTracks.size),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun render() {
        binding.trackCanvas.layers = layers.map {
            TrackCanvasView.Layer(
                name = it.name,
                points = it.points,
                color = it.color,
                visible = it.visible,
            )
        }
        renderMapLayers()
        renderDisplayMode()
        renderSummary()
        renderLayerList()
    }

    private fun renderDisplayMode() {
        val showMap = displayMode == DisplayMode.MAP
        binding.osmMap.visibility = if (showMap) View.VISIBLE else View.GONE
        binding.trackCanvas.visibility = if (showMap) View.GONE else View.VISIBLE
    }

    private fun configureOsmDroid() {
        val osmdroidDir = File(cacheDir, "osmdroid").apply { mkdirs() }
        val tileDir = File(osmdroidDir, "tiles").apply { mkdirs() }
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = osmdroidDir
            osmdroidTileCache = tileDir
        }
    }

    private fun setupMap() {
        binding.osmMap.setTileSource(TileSourceFactory.MAPNIK)
        binding.osmMap.setMultiTouchControls(true)
        binding.osmMap.minZoomLevel = 2.0
        binding.osmMap.maxZoomLevel = 22.0
        binding.osmMap.controller.setZoom(3.0)
        binding.osmMap.controller.setCenter(GeoPoint(0.0, 0.0))
    }

    private fun renderMapLayers() {
        val map = binding.osmMap
        map.overlays.clear()
        layers.filter { it.visible && it.points.isNotEmpty() }.forEach { layer ->
            val geoPoints = layer.points.map { GeoPoint(it.latitude, it.longitude) }
            if (geoPoints.size >= 2) {
                val line = Polyline(map).apply {
                    title = layer.name
                    outlinePaint.color = layer.color
                    outlinePaint.strokeWidth = dp(4).toFloat()
                    setPoints(geoPoints)
                }
                map.overlays.add(line)
            }
            geoPoints.firstOrNull()?.let { point ->
                map.overlays.add(createMarker(point, "${layer.name} Start"))
            }
            geoPoints.lastOrNull()?.let { point ->
                map.overlays.add(createMarker(point, "${layer.name} End"))
            }
        }
        map.invalidate()
    }

    private fun createMarker(point: GeoPoint, title: String): Marker =
        Marker(binding.osmMap).apply {
            position = point
            this.title = title
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }

    private fun resetMapViewport() {
        val geoPoints = layers.filter { it.visible }.flatMap { layer ->
            layer.points.map { GeoPoint(it.latitude, it.longitude) }
        }
        if (geoPoints.isEmpty()) {
            return
        }
        binding.osmMap.post {
            if (geoPoints.size == 1) {
                binding.osmMap.controller.setZoom(18.0)
                binding.osmMap.controller.setCenter(geoPoints.first())
            } else {
                binding.osmMap.zoomToBoundingBox(
                    BoundingBox.fromGeoPointsSafe(geoPoints),
                    false,
                    dp(48),
                )
            }
        }
    }

    private fun renderSummary() {
        val visibleLayers = layers.filter { it.visible }
        val totalPoints = visibleLayers.sumOf { it.points.size }
        val totalDistance = visibleLayers.sumOf { it.points.trackStats().distanceMeters }
        binding.textTrackSummary.text = getString(
            R.string.track_summary,
            layers.size,
            visibleLayers.size,
            totalPoints,
            formatTrackDistance(totalDistance),
        )
        binding.textTrackEmpty.visibility = if (layers.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderLayerList() {
        binding.trackLayerContainer.removeAllViews()
        layers.forEachIndexed { index, layer ->
            binding.trackLayerContainer.addView(createLayerRow(index, layer))
        }
    }

    private fun createLayerRow(index: Int, layer: TrackLayer): View {
        val stats = layer.points.trackStats()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val checkBox = CheckBox(this).apply {
            text = layer.name
            isChecked = layer.visible
            setTextColor(layer.color)
            setOnCheckedChangeListener { _, isChecked ->
                layers[index] = layer.copy(visible = isChecked)
                render()
            }
        }
        val details = TextView(this).apply {
            text = getString(
                R.string.track_layer_details,
                layer.sourceType,
                stats.pointCount,
                formatTrackDistance(stats.distanceMeters),
                formatTrackAccuracy(stats.averageAccuracyM),
            )
            textSize = 13f
        }
        row.addView(checkBox)
        row.addView(details)
        return row
    }

    private fun displayNameFor(uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(0) ?: uri.lastPathSegment ?: "track"
            } else {
                uri.lastPathSegment ?: "track"
            }
        } finally {
            cursor?.close()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class TrackLayer(
        val name: String,
        val sourceType: String,
        val points: List<TrackPoint>,
        val color: Int,
        val visible: Boolean,
    )

    private enum class DisplayMode {
        CANVAS,
        MAP,
    }

    companion object {
        const val EXTRA_FILE_PATHS = "com.example.gnsslogger.extra.FILE_PATHS"
        const val EXTRA_OPEN_PICKER = "com.example.gnsslogger.extra.OPEN_PICKER"
        private const val TAG = "TrackViewerActivity"
        private val TRACK_COLORS = intArrayOf(
            Color.rgb(220, 38, 38),
            Color.rgb(37, 99, 235),
            Color.rgb(22, 163, 74),
            Color.rgb(217, 119, 6),
            Color.rgb(147, 51, 234),
            Color.rgb(8, 145, 178),
            Color.rgb(219, 39, 119),
        )
    }
}
