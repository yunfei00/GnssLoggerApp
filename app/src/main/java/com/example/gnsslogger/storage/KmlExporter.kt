package com.example.gnsslogger.storage

import java.io.File
import java.util.Locale

data class KmlExportResult(
    val outputFile: File,
    val pointCount: Int,
)

class NoValidTrackPointsException(message: String) : IllegalStateException(message)

private data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val timestamp: String?,
)

object KmlExporter {

    fun exportFromLocationCsv(locationCsv: File, outputKml: File): KmlExportResult {
        val points = parseValidPoints(locationCsv)
        if (points.isEmpty()) {
            throw NoValidTrackPointsException("location.csv 没有有效经纬度数据")
        }

        outputKml.parentFile?.mkdirs()
        outputKml.bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            out.appendLine("<kml xmlns=\"http://www.opengis.net/kml/2.2\">")
            out.appendLine("  <Document>")
            out.appendLine("    <name>${escapeXml(outputKml.nameWithoutExtension)}</name>")
            out.appendLine("    <Style id=\"trackStyle\">")
            out.appendLine("      <LineStyle>")
            out.appendLine("        <color>ff0000ff</color>")
            out.appendLine("        <width>4</width>")
            out.appendLine("      </LineStyle>")
            out.appendLine("    </Style>")

            writePointPlacemark(out, "Start Point", points.first())
            writePointPlacemark(out, "End Point", points.last())

            out.appendLine("    <Placemark>")
            out.appendLine("      <name>GNSS Track</name>")
            out.appendLine("      <styleUrl>#trackStyle</styleUrl>")
            out.appendLine("      <LineString>")
            out.appendLine("        <tessellate>1</tessellate>")
            out.appendLine("        <altitudeMode>clampToGround</altitudeMode>")
            out.appendLine("        <coordinates>")
            points.forEach { p ->
                out.appendLine("          ${fmt8(p.longitude)},${fmt8(p.latitude)},${fmt2(p.altitude)}")
            }
            out.appendLine("        </coordinates>")
            out.appendLine("      </LineString>")
            out.appendLine("    </Placemark>")
            out.appendLine("  </Document>")
            out.appendLine("</kml>")
        }

        return KmlExportResult(outputFile = outputKml, pointCount = points.size)
    }

    private fun parseValidPoints(locationCsv: File): List<TrackPoint> {
        if (!locationCsv.exists() || !locationCsv.isFile) return emptyList()
        locationCsv.bufferedReader(Charsets.UTF_8).useLines { lines ->
            val iterator = lines.iterator()
            if (!iterator.hasNext()) return emptyList()

            val header = parseCsvLine(iterator.next())
            val indexes = CsvIndexes.fromHeader(header)
            val points = mutableListOf<TrackPoint>()
            while (iterator.hasNext()) {
                val cols = parseCsvLine(iterator.next())
                val point = indexes.parsePoint(cols) ?: continue
                points.add(point)
            }
            return points
        }
    }

    private fun writePointPlacemark(out: Appendable, name: String, point: TrackPoint) {
        out.appendLine("    <Placemark>")
        out.appendLine("      <name>${escapeXml(name)}</name>")
        point.timestamp?.takeIf { it.isNotBlank() }?.let {
            out.appendLine("      <description>${escapeXml(it)}</description>")
        }
        out.appendLine("      <Point>")
        out.appendLine("        <coordinates>${fmt8(point.longitude)},${fmt8(point.latitude)},${fmt2(point.altitude)}</coordinates>")
        out.appendLine("      </Point>")
        out.appendLine("    </Placemark>")
    }

    private data class CsvIndexes(
        val latitude: Int,
        val longitude: Int,
        val altitude: Int?,
        val timestamp: Int?,
    ) {
        fun parsePoint(cols: List<String>): TrackPoint? {
            val lat = cols.getOrNull(latitude)?.toDoubleOrNull() ?: return null
            val lon = cols.getOrNull(longitude)?.toDoubleOrNull() ?: return null
            if (!isValidCoordinate(lat, lon)) return null

            val alt = altitude?.let { cols.getOrNull(it)?.toDoubleOrNull() } ?: 0.0
            val safeAlt = if (alt.isNaN() || alt.isInfinite()) 0.0 else alt
            return TrackPoint(
                latitude = lat,
                longitude = lon,
                altitude = safeAlt,
                timestamp = timestamp?.let { cols.getOrNull(it) }?.takeIf { it.isNotBlank() },
            )
        }

        companion object {
            fun fromHeader(header: List<String>): CsvIndexes {
                val normalized = header.map { it.trim().lowercase(Locale.US) }
                return CsvIndexes(
                    latitude = normalized.firstIndexOf("latitude", "lat").takeIf { it >= 0 } ?: 3,
                    longitude = normalized.firstIndexOf("longitude", "lon", "lng").takeIf { it >= 0 } ?: 4,
                    altitude = normalized.firstIndexOf("altitude_m", "altitude", "alt").takeIf { it >= 0 } ?: 5,
                    timestamp = normalized.firstIndexOf("timestamp_iso", "timestamp", "time", "timestamp_ms")
                        .takeIf { it >= 0 },
                )
            }
        }
    }

    private fun List<String>.firstIndexOf(vararg names: String): Int =
        indexOfFirst { value -> names.any { it == value } }

    private fun isValidCoordinate(latitude: Double, longitude: Double): Boolean {
        if (latitude.isNaN() || latitude.isInfinite() || longitude.isNaN() || longitude.isInfinite()) return false
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
        return !(latitude == 0.0 && longitude == 0.0)
    }

    private fun parseCsvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }

                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    values.add(current.toString().trim())
                    current.clear()
                }

                else -> current.append(c)
            }
            i++
        }
        values.add(current.toString().trim())
        return values
    }

    private fun escapeXml(raw: String): String =
        raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun fmt8(v: Double) = String.format(Locale.US, "%.8f", v)
    private fun fmt2(v: Double) = String.format(Locale.US, "%.2f", v)
}
