package com.example.gnsslogger.gnss

import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.gnsslogger.data.GnssSatelliteRecord

data class GnssStatusFrame(
    val satellites: List<GnssSatelliteRecord>,
    val visibleCount: Int,
    val usedInFixCount: Int,
)

class GnssCollector(
    private val context: Context,
    private val callbackLooper: Looper,
    private val onFrame: (GnssStatusFrame) -> Unit,
) {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val callbackHandler = Handler(callbackLooper)

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            onFrame(buildFrame(status))
        }
    }

    private val locationListener = LocationListener { location ->
        lastLocation = location
    }

    @Volatile
    var lastLocation: Location? = null
        private set

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
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                locationListener,
                callbackLooper,
            )
        } catch (_: SecurityException) {
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
    }

    fun isGpsProviderEnabled(): Boolean =
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

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
}
