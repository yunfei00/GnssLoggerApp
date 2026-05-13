package com.example.gnsslogger.storage

import java.io.File
import java.util.Locale

data class TrackPoint(val latitude: Double, val longitude: Double, val altitude: Double)

object KmlExporter {

    fun exportFromLocationCsv(locationCsv: File, outputKml: File) {
        val points = parseValidPoints(locationCsv)
        outputKml.parentFile?.mkdirs()
        outputKml.bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            out.appendLine("<kml xmlns=\"http://www.opengis.net/kml/2.2\">")
            out.appendLine("  <Document>")
            out.appendLine("    <name>GNSS Track</name>")
            out.appendLine("    <Style id=\"trackStyle\"><LineStyle><color>ff0000ff</color><width>4</width></LineStyle></Style>")

            if (points.size < 2) {
                out.appendLine("    <Placemark><name>No enough valid location points.</name></Placemark>")
            }

            if (points.isNotEmpty()) {
                val start = points.first()
                val end = points.last()
                out.appendLine("    <Placemark><name>Start Point</name><Point><coordinates>${fmt8(start.longitude)},${fmt8(start.latitude)},${fmt2(start.altitude)}</coordinates></Point></Placemark>")
                out.appendLine("    <Placemark><name>End Point</name><Point><coordinates>${fmt8(end.longitude)},${fmt8(end.latitude)},${fmt2(end.altitude)}</coordinates></Point></Placemark>")
            }

            out.appendLine("    <Placemark>")
            out.appendLine("      <name>GNSS Track</name>")
            out.appendLine("      <styleUrl>#trackStyle</styleUrl>")
            out.appendLine("      <LineString><tessellate>1</tessellate><altitudeMode>absolute</altitudeMode><coordinates>")
            points.forEach { p ->
                out.appendLine("        ${fmt8(p.longitude)},${fmt8(p.latitude)},${fmt2(p.altitude)}")
            }
            out.appendLine("      </coordinates></LineString>")
            out.appendLine("    </Placemark>")
            out.appendLine("  </Document>")
            out.appendLine("</kml>")
        }
    }

    private fun parseValidPoints(locationCsv: File): List<TrackPoint> {
        if (!locationCsv.exists() || !locationCsv.isFile) return emptyList()
        return locationCsv.readLines(Charsets.UTF_8).drop(1).mapNotNull { line ->
            val cols = line.split(',')
            if (cols.size < 5) return@mapNotNull null
            val lat = cols[3].toDoubleOrNull() ?: return@mapNotNull null
            val lon = cols[4].toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return@mapNotNull null
            val alt = cols.getOrNull(5)?.toDoubleOrNull() ?: 0.0
            TrackPoint(lat, lon, alt)
        }
    }

    private fun fmt8(v: Double) = String.format(Locale.US, "%.8f", v)
    private fun fmt2(v: Double) = String.format(Locale.US, "%.2f", v)
}
