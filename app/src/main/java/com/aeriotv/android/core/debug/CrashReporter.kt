package com.aeriotv.android.core.debug

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ALWAYS-ON crash capture, independent of the Debug Logging toggle.
 *
 * WHY: the crash handler inside [DebugLogger] only writes while logging is
 * enabled, so a user whose app dies seconds after launch (GH #112) can never
 * turn logging on in time and has nothing to attach to the issue. This handler
 * is installed from the Application before anything else runs, costs nothing
 * until the process actually dies, and leaves one small file behind:
 *
 *   filesDir/logs/aerio_last_crash.txt
 *
 * The report carries the app version, the device and Android version, the
 * thread, the full stack trace, and the tail of the app's own log ring (plus a
 * best effort logcat tail for this pid). Every line is passed through
 * [LogSanitizer] because the file is meant to be shared on a public issue.
 *
 * On the next launch [publishToDebugLog] copies the report into the debug log
 * file the Settings screens already view and share, so the user finds it under
 * Settings > Developer > Log File with no extra steps.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val CRASH_FILE = "aerio_last_crash.txt"

    /** Lines of the app's own logging kept for the crash report. */
    private const val RING_CAPACITY = 200

    /** Tail of this pid's logcat appended to the report, in lines. */
    private const val LOGCAT_TAIL_LINES = 200

    private val installed = AtomicBoolean(false)
    private val ring = ArrayDeque<String>(RING_CAPACITY)

    private val timestamps = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /**
     * Remember one line for the crash report. Called from [DebugLogger.log]
     * for every routed line whether or not file logging is on, so the ring is
     * warm when the process dies. Cheap: a bounded deque of strings.
     */
    fun record(line: String) {
        synchronized(ring) {
            if (ring.size >= RING_CAPACITY) ring.removeFirst()
            ring.addLast(line)
        }
    }

    private fun ringSnapshot(): List<String> = synchronized(ring) { ring.toList() }

    fun crashFile(context: Context): File {
        val dir = File(context.filesDir, DebugLogger.LOGS_DIR).apply { mkdirs() }
        return File(dir, CRASH_FILE)
    }

    fun hasReport(context: Context): Boolean =
        runCatching { crashFile(context).length() > 0L }.getOrDefault(false)

    fun readReport(context: Context): String? =
        runCatching { crashFile(context).takeIf { it.length() > 0L }?.readText() }.getOrNull()

    fun clearReport(context: Context) {
        runCatching { crashFile(context).delete() }
    }

    /**
     * Install the handler. Idempotent, and it always chains to whatever
     * handler was there before, so the system crash dialog and the process
     * death behave exactly as they did.
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Synchronous write: nothing queued can be trusted to run during death. */
    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val file = crashFile(context)
        val text = buildString {
            appendLine("==== AerioTV crash report ====")
            appendLine("Time: ${timestamps.get()!!.format(Date())}")
            appendLine(
                "App: ${com.aeriotv.android.BuildConfig.VERSION_NAME} " +
                    "(${com.aeriotv.android.BuildConfig.VERSION_CODE}) " +
                    com.aeriotv.android.BuildConfig.FLAVOR,
            )
            appendLine(
                "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                    "(${android.os.Build.DEVICE})",
            )
            appendLine(
                "Android: ${android.os.Build.VERSION.RELEASE} " +
                    "(API ${android.os.Build.VERSION.SDK_INT})",
            )
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine("---- stack trace ----")
            throwable.stackTraceToString().lines().forEach {
                appendLine(LogSanitizer.redact(it))
            }
            val recent = ringSnapshot()
            if (recent.isNotEmpty()) {
                appendLine()
                appendLine("---- last ${recent.size} app log lines ----")
                recent.forEach { appendLine(LogSanitizer.redact(it)) }
            }
            logcatTail()?.let {
                appendLine()
                appendLine("---- logcat tail (this process) ----")
                appendLine(LogSanitizer.redact(it))
            }
        }
        runCatching {
            file.writeText(text)
            // A new crash means the previous report was already published;
            // clear the marker so this one is published on the next launch.
            publishedMarker(context).delete()
        }.onFailure { Log.w(TAG, "could not write crash report: ${it.message}") }
    }

    /** Best effort: some ROMs refuse logcat reads, and that is fine. */
    private fun logcatTail(): String? = runCatching {
        val pid = android.os.Process.myPid()
        val lines = Runtime.getRuntime()
            .exec(arrayOf("logcat", "-d", "-v", "time", "--pid=$pid"))
            .inputStream.bufferedReader().readLines()
        lines.takeLast(LOGCAT_TAIL_LINES).joinToString(System.lineSeparator())
    }.getOrNull()

    /**
     * Next launch: fold a pending report into the debug log file so the
     * existing View Log File / Share Log File / TV QR share rows carry it,
     * even when the user never turned logging on. The report file is kept as
     * well, so a second copy is available if the log is later cleared; it is
     * only overwritten by the next crash.
     */
    fun publishToDebugLog(context: Context, logFile: File) {
        val report = readReport(context) ?: return
        val marker = publishedMarker(context)
        if (marker.exists() && marker.lastModified() >= crashFile(context).lastModified()) return
        runCatching {
            logFile.parentFile?.mkdirs()
            // Appended, never a read-modify-write: the log can be 10 MB and
            // this runs on every launch after a crash.
            logFile.appendText(
                buildString {
                    appendLine(REPORT_MARKER)
                    appendLine(
                        "The app closed unexpectedly during the previous session. " +
                            "Share this file when reporting the problem.",
                    )
                    appendLine()
                    append(report)
                    appendLine()
                    appendLine("==== end of crash report ====")
                    appendLine()
                },
            )
            marker.writeText("published")
        }.onFailure { Log.w(TAG, "could not publish crash report: ${it.message}") }
    }

    private fun publishedMarker(context: Context): File =
        File(File(context.filesDir, DebugLogger.LOGS_DIR), "$CRASH_FILE.published")

    private const val REPORT_MARKER = "==== AerioTV last crash report ===="
}
