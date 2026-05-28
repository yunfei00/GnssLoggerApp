package com.example.gnsslogger.track

import java.util.Locale

object TrackFileParser {

    fun parse(displayName: String, content: String): ParsedTrack {
        val name = displayName.ifBlank { "track" }
        val lowerName = name.lowercase(Locale.US)
        val parsed = when {
            lowerName.endsWith(".kml") -> ParsedTrack(name, "KML", parseKmlCoordinates(content))
            lowerName.endsWith(".csv") -> ParsedTrack(name, "CSV", parseLocationCsv(content))
            else -> {
                val csvPoints = parseLocationCsv(content)
                if (csvPoints.isNotEmpty()) {
                    ParsedTrack(name, "CSV", csvPoints)
                } else {
                    ParsedTrack(name, "KML", parseKmlCoordinates(content))
                }
            }
        }
        return parsed.copy(points = parsed.points.filter(::isValidTrackPoint))
    }

    fun parseLocationCsv(content: String): List<TrackPoint> {
        val lines = content.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .iterator()
        if (!lines.hasNext()) return emptyList()

        val header = parseCsvLine(lines.next())
        val indexes = CsvIndexes.fromHeader(header) ?: return emptyList()
        val points = mutableListOf<TrackPoint>()
        while (lines.hasNext()) {
            indexes.parsePoint(parseCsvLine(lines.next()))?.let(points::add)
        }
        return points
    }

    fun parseKmlCoordinates(content: String): List<TrackPoint> {
        val coordinatesBlocks = Regex(
            "<coordinates[^>]*>(.*?)</coordinates>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(content)

        return coordinatesBlocks.flatMap { match ->
            match.groupValues[1]
                .trim()
                .split(Regex("\\s+"))
                .asSequence()
                .mapNotNull(::parseKmlCoordinate)
        }.toList()
    }

    private data class CsvIndexes(
        val latitude: Int,
        val longitude: Int,
        val altitude: Int?,
        val timestampMs: Int?,
        val timestampText: Int?,
        val accuracy: Int?,
        val quality: Int?,
    ) {
        fun parsePoint(cols: List<String>): TrackPoint? {
            val lat = cols.getOrNull(latitude)?.toDoubleOrNull() ?: return null
            val lon = cols.getOrNull(longitude)?.toDoubleOrNull() ?: return null
            val accuracyM = accuracy?.let { cols.getOrNull(it)?.toDoubleOrNull() }
            if (accuracy != null && (accuracyM == null || !accuracyM.isFinite() || accuracyM > 100.0)) {
                return null
            }
            val point = TrackPoint(
                latitude = lat,
                longitude = lon,
                altitude = altitude?.let { cols.getOrNull(it)?.toDoubleOrNull() },
                timestampMs = timestampMs?.let { cols.getOrNull(it)?.toLongOrNull() },
                timestampText = timestampText?.let { cols.getOrNull(it) }?.takeIf { it.isNotBlank() },
                accuracyM = accuracyM,
                quality = quality?.let { cols.getOrNull(it) }?.takeIf { it.isNotBlank() },
            )
            return point.takeIf(::isValidTrackPoint)
        }

        companion object {
            fun fromHeader(header: List<String>): CsvIndexes? {
                val normalized = header.map { it.trim().lowercase(Locale.US) }
                val lat = normalized.firstIndexOf("latitude", "lat")
                val lon = normalized.firstIndexOf("longitude", "lon", "lng")
                if (lat < 0 || lon < 0) return null
                return CsvIndexes(
                    latitude = lat,
                    longitude = lon,
                    altitude = normalized.firstIndexOf("altitude_m", "altitude", "alt").takeIf { it >= 0 },
                    timestampMs = normalized.firstIndexOf("timestamp_ms").takeIf { it >= 0 },
                    timestampText = normalized.firstIndexOf("timestamp_iso", "timestamp", "time").takeIf { it >= 0 },
                    accuracy = normalized.firstIndexOf("accuracy_m", "accuracy").takeIf { it >= 0 },
                    quality = normalized.firstIndexOf("quality").takeIf { it >= 0 },
                )
            }
        }
    }

    private fun parseKmlCoordinate(raw: String): TrackPoint? {
        val parts = raw.split(',')
        if (parts.size < 2) return null
        val lon = parts[0].trim().toDoubleOrNull() ?: return null
        val lat = parts[1].trim().toDoubleOrNull() ?: return null
        val alt = parts.getOrNull(2)?.trim()?.toDoubleOrNull()
        return TrackPoint(latitude = lat, longitude = lon, altitude = alt).takeIf(::isValidTrackPoint)
    }

    private fun isValidTrackPoint(point: TrackPoint): Boolean {
        val lat = point.latitude
        val lon = point.longitude
        if (!lat.isFinite() || !lon.isFinite()) return false
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return false
        return !(lat == 0.0 && lon == 0.0)
    }

    private fun List<String>.firstIndexOf(vararg names: String): Int =
        indexOfFirst { value -> names.any { it == value } }

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
}
