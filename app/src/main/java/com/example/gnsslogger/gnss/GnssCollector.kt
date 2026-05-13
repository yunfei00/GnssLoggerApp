package com.example.gnsslogger.gnss

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.gnsslogger.data.GnssSatelliteRecord
import java.util.concurrent.Executor

data class GnssStatusFrame(
    val satellites: List<GnssSatelliteRecord>,
    val visibleCount: Int,
    val usedInFixCount: Int,
)

data class NmeaMessageFrame(
    val nmeaTimestampMs: Long,
    val message: String,
)

data class RawGnssMeasurementsFrame(
    val clock: GnssClock,
    val measurements: List<GnssMeasurement>,
)

class GnssCollector(
    private val context: Context,
    private val callbackLooper: Looper,
    private val collectNmea: Boolean,
    private val collectRawMeasurements: Boolean,
    private val onStatusFrame: (GnssStatusFrame) -> Unit,
    private val onNmeaMessage: (NmeaMessageFrame) -> Unit,
    private val onRawMeasurements: (RawGnssMeasurementsFrame) -> Unit,
    private val onLocationUpdate: (Location) -> Unit,
) {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val callbackHandler = Handler(callbackLooper)
    private val callbackExecutor = Executor { command -> callbackHandler.post(command) }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            onStatusFrame(buildFrame(status))
        }
    }

    private val nmeaListener = OnNmeaMessageListener { message, timestamp ->
        onNmeaMessage(NmeaMessageFrame(timestamp, message))
    }

    private val rawMeasurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
            onRawMeasurements(
                RawGnssMeasurementsFrame(
                    clock = eventArgs.clock,
                    measurements = eventArgs.measurements.toList(),
                ),
            )
        }
    }

    private val locationListener = LocationListener { location ->
        lastLocation = location
        onLocationUpdate(location)
    }

    @Volatile
    var lastLocation: Location? = null
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        lastLocation = try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: SecurityException) {
            null
        }
        try {
            locationManager.registerGnssStatusCallback(gnssCallback, callbackHandler)
        } catch (_: SecurityException) {
            return
        }
        try {
            requestHighAccuracyLocationUpdates()
        } catch (_: SecurityException) {
        }
        if (collectNmea) {
            try {
                locationManager.addNmeaListener(nmeaListener, callbackHandler)
            } catch (_: Exception) {
            }
        }
        if (collectRawMeasurements) {
            try {
                locationManager.registerGnssMeasurementsCallback(rawMeasurementsCallback, callbackHandler)
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        try {
            locationManager.unregisterGnssStatusCallback(gnssCallback)
        } catch (_: Exception) {
        }
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
        try {
            locationManager.removeNmeaListener(nmeaListener)
        } catch (_: Exception) {
        }
        try {
            locationManager.unregisterGnssMeasurementsCallback(rawMeasurementsCallback)
        } catch (_: Exception) {
        }
    }

    fun isGpsProviderEnabled(): Boolean =
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission")
    private fun requestHighAccuracyLocationUpdates() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val request = LocationRequest.Builder(LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL_MS)
                .setMinUpdateDistanceMeters(0f)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .build()
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                request,
                callbackExecutor,
                locationListener,
            )
        } else {
            @Suppress("DEPRECATION")
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_INTERVAL_MS,
                0f,
                locationListener,
                callbackLooper,
            )
        }
    }

    private fun buildFrame(status: GnssStatus): GnssStatusFrame {
        val n = status.satelliteCount
        if (n <= 0) {
            return GnssStatusFrame(emptyList(), 0, 0)
        }
        val list = ArrayList<GnssSatelliteRecord>(n)
        var used = 0
        for (i in 0 until n) {
            val inFix = status.usedInFix(i)
            if (inFix) used++
            val cType = status.getConstellationType(i)
            val carrier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val v = status.getCarrierFrequencyHz(i)
                if (v.isFinite()) v else null
            } else {
                null
            }
            val baseband = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val v = status.getBasebandCn0DbHz(i)
                if (v.isFinite()) v else null
            } else {
                null
            }
            list.add(
                GnssSatelliteRecord(
                    constellationType = cType,
                    constellationName = ConstellationMapper.toName(cType),
                    svid = status.getSvid(i),
                    cn0DbHz = status.getCn0DbHz(i),
                    elevationDegrees = status.getElevationDegrees(i),
                    azimuthDegrees = status.getAzimuthDegrees(i),
                    usedInFix = inFix,
                    carrierFrequencyHz = carrier,
                    basebandCn0DbHz = baseband,
                    hasAlmanac = status.hasAlmanacData(i),
                    hasEphemeris = status.hasEphemerisData(i),
                ),
            )
        }
        return GnssStatusFrame(list, n, used)
    }

    companion object {
        private const val LOCATION_INTERVAL_MS = 1_000L
        private const val LOCATION_FASTEST_INTERVAL_MS = 1_000L
    }
}
