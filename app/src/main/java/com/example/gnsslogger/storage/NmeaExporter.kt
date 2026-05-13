package com.example.gnsslogger.storage

import java.io.File

data class NmeaExportResult(
    val outputFile: File,
    val sentenceCount: Int,
)

class NoValidNmeaSentencesException(message: String) : IllegalStateException(message)

object NmeaExporter {

    fun exportFromNmeaCsv(nmeaCsv: File, outputNmea: File): NmeaExportResult {
        if (!nmeaCsv.exists() || !nmeaCsv.isFile) {
            throw NoValidNmeaSentencesException("NMEA CSV does not exist: ${nmeaCsv.absolutePath}")
        }

        val sentences = mutableListOf<String>()
        nmeaCsv.bufferedReader(Charsets.UTF_8).use { reader ->
            val headerLine = reader.readLine()
                ?: throw NoValidNmeaSentencesException("NMEA CSV is empty: ${nmeaCsv.absolutePath}")
            val headers = parseCsvLine(headerLine).map { it.trim().trimStart('\uFEFF') }
            val messageIndex = headers.indexOf("message")
            if (messageIndex < 0) {
                throw IllegalArgumentException("NMEA CSV missing message column: ${nmeaCsv.absolutePath}")
            }

            reader.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val fields = parseCsvLine(line)
                if (fields.size <= messageIndex) return@forEach
                normalizeNmeaSentence(fields[messageIndex])?.let(sentences::add)
            }
        }

        if (sentences.isEmpty()) {
            outputNmea.delete()
            throw NoValidNmeaSentencesException("No valid NMEA sentences found in ${nmeaCsv.absolutePath}")
        }

        outputNmea.parentFile?.mkdirs()
        outputNmea.bufferedWriter(Charsets.UTF_8).use { writer ->
            sentences.forEach { sentence ->
                writer.write(sentence)
                writer.newLine()
            }
        }
        return NmeaExportResult(outputFile = outputNmea, sentenceCount = sentences.size)
    }

    private fun normalizeNmeaSentence(raw: String): String? {
        var sentence = raw.trim().trimStart('\uFEFF')
        if (sentence.length >= 2 && sentence.first() == '"' && sentence.last() == '"') {
            sentence = sentence.substring(1, sentence.lastIndex).replace("\"\"", "\"").trim()
        }
        if (sentence.isBlank() || !sentence.startsWith("$")) return null
        return sentence
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
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
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }
}
