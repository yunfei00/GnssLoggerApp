package com.example.gnsslogger.storage

import java.io.File

object GoogleEarthKmlExporter {

    fun exportFromSatelliteCsv(inputCsv: File, outputKml: File) {
        if (!inputCsv.exists() || !inputCsv.isFile) {
            throw IllegalArgumentException("输入 CSV 不存在: ${inputCsv.absolutePath}")
        }

        val points = linkedMapOf<String, Triple<Double, Double, Double?>>()
        inputCsv.bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                if (line.isBlank()) return@forEach
                val cols = line.split(',')
                if (cols.size < 13) return@forEach
                val tsIso = cols[1].trim()
                val lat = cols[9].toDoubleOrNull() ?: return@forEach
                val lon = cols[10].toDoubleOrNull() ?: return@forEach
                val alt = cols[11].toDoubleOrNull()
                if (tsIso.isBlank()) return@forEach
                points.putIfAbsent(tsIso, Triple(lon, lat, alt))
            }
        }

        if (points.isEmpty()) {
            throw IllegalStateException("CSV 内没有可导出的轨迹点")
        }

        outputKml.parentFile?.mkdirs()
        outputKml.bufferedWriter().use { out ->
            out.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            out.appendLine("<kml xmlns=\"http://www.opengis.net/kml/2.2\">")
            out.appendLine("  <Document>")
            out.appendLine("    <name>${escapeXml(inputCsv.nameWithoutExtension)}.kml</name>")
            out.appendLine("    <Placemark>")
            out.appendLine("      <name>${escapeXml(inputCsv.nameWithoutExtension)}</name>")
            out.appendLine("      <Style>")
            out.appendLine("        <LineStyle><color>ff00a5ff</color><width>4</width></LineStyle>")
            out.appendLine("      </Style>")
            out.appendLine("      <LineString>")
            out.appendLine("        <tessellate>1</tessellate>")
            out.appendLine("        <coordinates>")
            points.values.forEach { (lon, lat, alt) ->
                val z = alt ?: 0.0
                out.appendLine("          ${lon},${lat},${z}")
            }
            out.appendLine("        </coordinates>")
            out.appendLine("      </LineString>")
            out.appendLine("    </Placemark>")
            out.appendLine("  </Document>")
            out.appendLine("</kml>")
        }
    }

    private fun escapeXml(raw: String): String {
        return raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
