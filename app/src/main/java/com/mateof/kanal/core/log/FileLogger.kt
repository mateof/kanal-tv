package com.mateof.kanal.core.log

import android.content.Context
import android.os.Build
import android.util.Log
import com.mateof.kanal.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

enum class LogLevel(val tag: Char) {
    DEBUG('D'), INFO('I'), WARN('W'), ERROR('E');
}

data class LogLine(
    val time: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    fun render(): String =
        "${TIME_FORMAT.get()!!.format(Date(time))} ${level.tag}/$tag: $message"

    companion object {
        val TIME_FORMAT: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }
    }
}

/**
 * Diagnostic log kept both on disk (so a user can export it after something
 * fails) and in a small in-memory ring buffer (so the Logs screen can show it
 * live without re-reading the file).
 *
 * Writes are funnelled through a single-threaded dispatcher: logging from a
 * player callback or an OkHttp thread must never block the caller.
 */
@Singleton
class FileLogger @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dir = File(context.filesDir, "logs")
    private val current = File(dir, "kanal.log")
    private val previous = File(dir, "kanal.1.log")

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kanal-log").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val queue = Channel<LogLine>(capacity = 512)

    private val _recent = MutableStateFlow<List<LogLine>>(emptyList())

    /** Last [RING_SIZE] lines, for the on-screen log viewer. */
    val recent: StateFlow<List<LogLine>> = _recent.asStateFlow()

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    init {
        dir.mkdirs()
        scope.launch {
            for (line in queue) appendToFile(line)
        }
    }

    fun d(tag: String, message: String) = write(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = write(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, t: Throwable? = null) = write(LogLevel.WARN, tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = write(LogLevel.ERROR, tag, message, t)

    private fun write(level: LogLevel, tag: String, message: String, t: Throwable?) {
        val text = if (t == null) message else "$message\n${t.stackTraceToStringCompat()}"
        val line = LogLine(System.currentTimeMillis(), level, tag, text)

        when (level) {
            LogLevel.DEBUG -> if (BuildConfig.DEBUG) Log.d(tag, text)
            LogLevel.INFO -> Log.i(tag, text)
            LogLevel.WARN -> Log.w(tag, text)
            LogLevel.ERROR -> Log.e(tag, text)
        }

        _recent.value = (_recent.value + line).let {
            if (it.size > RING_SIZE) it.subList(it.size - RING_SIZE, it.size) else it
        }
        queue.trySend(line)
    }

    private fun appendToFile(line: LogLine) {
        try {
            if (current.length() > MAX_BYTES) {
                if (previous.exists()) previous.delete()
                current.renameTo(previous)
            }
            current.appendText("${stamp.format(Date(line.time))} ${line.level.tag}/${line.tag}: ${line.message}\n")
        } catch (_: Exception) {
            // A failing logger must never take the app down.
        }
    }

    /** Whole log, oldest rotation first, capped so the viewer stays responsive. */
    suspend fun readAll(maxChars: Int = 400_000): String = withContext(io) {
        val text = buildString {
            if (previous.exists()) append(runCatching { previous.readText() }.getOrDefault(""))
            if (current.exists()) append(runCatching { current.readText() }.getOrDefault(""))
        }
        if (text.length > maxChars) text.takeLast(maxChars) else text
    }

    suspend fun clear() = withContext(io) {
        runCatching { current.delete(); previous.delete() }
        _recent.value = emptyList()
        Unit
    }

    /**
     * Copies the log (plus a device header) into the app's external files dir,
     * from where it can be shared out with a FileProvider uri.
     */
    suspend fun export(): File? = withContext(io) {
        try {
            val out = File(context.getExternalFilesDir(null), "exported-logs").apply { mkdirs() }
            val name = "kanal-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.log"
            val file = File(out, name)
            file.writeText(header() + "\n" + readAll())
            file
        } catch (e: Exception) {
            e("Logger", "No se pudo exportar el log", e)
            null
        }
    }

    fun header(): String = buildString {
        appendLine("=== Kanal ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ===")
        appendLine("Fecha:      ${stamp.format(Date())}")
        appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android:    ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("ABI:        ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    /** Installs a handler that records crashes before the process dies. */
    fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val line = LogLine(
                    System.currentTimeMillis(),
                    LogLevel.ERROR,
                    "Crash",
                    "Excepción no capturada en '${thread.name}'\n${error.stackTraceToStringCompat()}"
                )
                appendToFile(line)
            } catch (_: Throwable) {
                // Nothing sensible left to do while the process is going down.
            }
            previousHandler?.uncaughtException(thread, error)
        }
    }

    private companion object {
        const val MAX_BYTES = 512L * 1024
        const val RING_SIZE = 600
    }
}

private fun Throwable.stackTraceToStringCompat(): String {
    val sw = StringWriter()
    PrintWriter(sw).use { printStackTrace(it) }
    return sw.toString()
}

/**
 * Static hook so code that is not part of the Hilt graph (parsers, the player
 * error listener, OkHttp callbacks) can still log. Set once from [KanalApp].
 */
object Klog {
    @Volatile
    private var delegate: FileLogger? = null

    fun bind(logger: FileLogger) {
        delegate = logger
    }

    fun d(tag: String, message: String) = delegate?.d(tag, message) ?: Unit
    fun i(tag: String, message: String) = delegate?.i(tag, message) ?: Unit
    fun w(tag: String, message: String, t: Throwable? = null) = delegate?.w(tag, message, t) ?: Unit
    fun e(tag: String, message: String, t: Throwable? = null) = delegate?.e(tag, message, t) ?: Unit
}
