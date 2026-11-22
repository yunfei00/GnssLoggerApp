package com.example.gnsslogger

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import android.util.Base64
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal class LoggerRun(private val context: Context, val id: String) {
    val directory = File(context.filesDir, "gnss-test/$id")
    private val metadata = AtomicFile(File(directory, "session.json"))
    val events = File(directory, "events.jsonl")

    fun read(): JSONObject? = try {
        JSONObject(metadata.readFully().decodeToString())
    } catch (_: FileNotFoundException) {
        null
    }

    fun write(state: JSONObject) {
        check(directory.isDirectory || directory.mkdirs())
        val stream = metadata.startWrite()
        try {
            stream.write(state.toString().encodeToByteArray())
            metadata.finishWrite(stream)
        } catch (error: Exception) {
            metadata.failWrite(stream)
            throw error
        }
    }

    fun transition(state: String, session: String? = null, reason: String? = null) {
        val record = read() ?: clock(context).put("test_run_id", id).put("schema_version", 1)
        record.put("state", state)
        if (session != null) record.put("logger_session_id", session)
        if (reason != null) record.put("reason", reason)
        write(record)
    }

    fun status(): JSONObject {
        val record = read() ?: return errorReply("UNKNOWN_RUN")
        if (record.getString("state") in setOf("STARTED", "STOPPING") &&
            (record.getInt("boot_count") != bootCount(context) ||
                GnssLoggerService.activeTestRun() != id)
        ) {
            record.put("state", "INTERRUPTED").put("reason", "SESSION_LOST")
        }
        if (record.optString("state") == "STARTED") {
            val live = GnssLoggerService.sessionState.value
            record.put("satellite_rows", live.recordsWritten)
            record.put("last_status_elapsed_ms", live.lastUpdateElapsedRealtimeMs)
        }
        return record
    }

    fun mark(scene: String, eventId: String, type: String): JSONObject {
        val record = status()
        if (record.optString("state") != "STARTED") return errorReply("LOGGER_NOT_STARTED")
        if (events.isFile) {
            events.useLines { lines ->
                val existing = lines.map { JSONObject(it) }.firstOrNull { it.getString("event_id") == eventId }
                if (existing != null) {
                    if (existing.getString("scene_instance_id") != scene || existing.getString("event_type") != type) {
                        return errorReply("EVENT_ID_CONFLICT")
                    }
                    return existing
                }
            }
        }
        val event = clock(context).put("protocol_version", 1)
            .put("test_run_id", id).put("logger_session_id", record.getString("logger_session_id"))
            .put("scene_instance_id", scene).put("event_id", eventId).put("event_type", type)
            .put("ack", "persisted").put("source", "logger_provider")
        FileOutputStream(events, true).use {
            it.write((event.toString() + "\n").encodeToByteArray())
            it.fd.sync()
        }
        return event
    }

    fun export(): JSONObject {
        val record = read() ?: return errorReply("UNKNOWN_RUN")
        if (record.optString("state") != "STOPPED") return errorReply("LOGGER_NOT_STOPPED")
        val files = FILES.mapNotNull { name ->
            val file = File(directory, name)
            if (!file.isFile) return@mapNotNull null
            val digest = MessageDigest.getInstance("SHA-256")
            var lines = 0L
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val length = stream.read(buffer)
                    if (length < 0) break
                    digest.update(buffer, 0, length)
                    for (index in 0 until length) if (buffer[index] == 10.toByte()) lines++
                }
            }
            JSONObject().put("name", name).put("size", file.length()).put("lines", lines)
                .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
        }
        return record.put("files", JSONArray(files))
    }

    companion object {
        val FILES = setOf("satellites.csv", "raw.csv", "nmea.csv", "standard.nmea", "events.jsonl")
    }
}

private fun errorReply(reason: String) = JSONObject().put("state", "ERROR").put("reason", reason)

private fun bootCount(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)

private fun clock(context: Context) = JSONObject()
    .put("elapsed_realtime_nanos", SystemClock.elapsedRealtimeNanos())
    .put("timestamp_ms", System.currentTimeMillis())
    .put("boot_count", bootCount(context))

internal fun validId(value: String) = runCatching { UUID.fromString(value).toString() }.getOrNull() == value

class LoggerControlProvider : ContentProvider() {
    override fun onCreate() = true

    private fun requireShell() {
        if (Binder.getCallingUid() != 2000) throw SecurityException("Android shell required")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        requireShell()
        val app = requireNotNull(context)
        val value = arg.orEmpty()
        val response = try {
            when (method) {
                "capabilities" -> JSONObject().put("protocol_version", 1).put("schema_version", 1)
                    .put("app_version", app.packageManager.getPackageInfo(app.packageName, 0).versionName)
                    .put("operations", JSONArray(listOf("start", "status", "stop", "mark", "clock", "export")))
                "clock" -> clock(app)
                "export" -> if (validId(value)) LoggerRun(app, value).export() else errorReply("INVALID_RUN_ID")
                else -> {
                    val task = FutureTask { control(app, method, value) }
                    Handler(Looper.getMainLooper()).post(task)
                    task.get(12, TimeUnit.SECONDS)
                }
            }
        } catch (error: Exception) {
            errorReply("LOGGER_" + error.javaClass.simpleName)
        }
        return Bundle().apply {
            putString("reply", Base64.encodeToString(response.toString().encodeToByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        }
    }

    private fun control(app: Context, method: String, value: String): JSONObject {
        val args = value.split(':', limit = 5)
        val id = args[0]
        if (!validId(id)) return errorReply("INVALID_RUN_ID")
        val run = LoggerRun(app, id)
        when (method) {
            "start" -> {
                val options = args.drop(1)
                if (options.size != options.toSet().size || options.any { it !in setOf("nmea", "status_only") }) {
                    return errorReply("INVALID_OPTIONS")
                }
                if (run.read() == null) {
                    run.transition("STARTING")
                    check(run.events.createNewFile())
                    try {
                        ContextCompat.startForegroundService(app, Intent(app, GnssLoggerService::class.java)
                            .setAction(AppActions.ACTION_START_LOGGING).putExtra("test_run_id", id)
                            .putExtra("test_collect_nmea", "nmea" in options)
                            .putExtra("test_collect_raw", "status_only" !in options))
                    } catch (error: RuntimeException) {
                        run.transition("ERROR", reason = "FOREGROUND_START_DENIED")
                    }
                }
                return run.status()
            }
            "status" -> return run.status()
            "stop" -> {
                val state = run.status()
                if (state.optString("state") == "STARTED") {
                    GnssLoggerService.stopTestRun(id)
                    return run.status()
                }
                return state
            }
            "mark" -> {
                if (args.size != 4 || !validId(args[1]) || !validId(args[2]) || args[3] !in EVENTS) {
                    return errorReply("INVALID_EVENT")
                }
                return run.mark(args[1], args[2], args[3])
            }
            else -> return errorReply("UNKNOWN_METHOD")
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        requireShell()
        val parts = uri.pathSegments
        if (mode != "r" || parts.size != 3 || parts[0] != "files" ||
            !validId(parts[1]) || parts[2] !in LoggerRun.FILES
        ) throw FileNotFoundException()
        val run = LoggerRun(requireNotNull(context), parts[1])
        if (run.read()?.optString("state") != "STOPPED") throw FileNotFoundException()
        return ParcelFileDescriptor.open(File(run.directory, parts[2]), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private val EVENTS = setOf("SCENE_READY", "MANUAL_CONFIRMED", "STABILIZATION_START",
            "MEASUREMENT_START", "MEASUREMENT_END", "SCENE_STOPPED", "ERROR")
    }
}
