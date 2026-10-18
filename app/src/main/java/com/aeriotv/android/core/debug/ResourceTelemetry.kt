package com.aeriotv.android.core.debug

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import com.aeriotv.android.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Periodic resource snapshots (PSS + native memory + system available memory +
 * FD count + thermal status) plus onTrimMemory tracking. Audit task #37: this
 * is the diagnostic the "AerioTV crashes on the Google TV Streamer" reports
 * need - by the time a crash lands we have a logcat trail of the memory and
 * thermal trajectory leading up to it, plus any TRIM_MEMORY_RUNNING_CRITICAL /
 * TRIM_MEMORY_COMPLETE signals the system fired just before the kill.
 *
 * The periodic snapshot is debug-only (BuildConfig.DEBUG) to keep release
 * builds quiet. [onTrimMemory] is always-on though - the trim signal IS the
 * diagnostic, and a future phase will hook in proactive cache-shedding when
 * RUNNING_CRITICAL fires (drop EPG in-memory cache, drop multiview tiles past
 * the soft limit, etc.). Bound from [com.aeriotv.android.AerioTVApplication.onCreate].
 */
@Singleton
class ResourceTelemetry @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * Start the periodic snapshot loop. Idempotent. Debug-only: in release
     * builds this is a no-op so we don't waste cycles in production.
     */
    fun start() {
        if (!BuildConfig.DEBUG) return
        if (job?.isActive == true) return
        job = scope.launch {
            // First snapshot a few seconds in so we don't compete with init.
            delay(5_000L)
            while (isActive) {
                snapshot()
                delay(SNAPSHOT_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun snapshot() {
        try {
            // PSS (Proportional Set Size) - what THIS process actually pins
            // in RAM, accounting for shared pages. Closer to "real" memory
            // use than RSS for diagnosis. Debug.getMemoryInfo is the canonical
            // way to read PSS for the current process.
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            val totalPssKb = memInfo.totalPss.toLong()
            val nativePssKb = memInfo.nativePss.toLong()
            val dalvikPssKb = memInfo.dalvikPss.toLong()

            // Java heap inside this VM (separate from PSS - PSS includes
            // native, shared, mmaps; this is the GC heap only).
            val rt = Runtime.getRuntime()
            val javaUsedKb = (rt.totalMemory() - rt.freeMemory()) / 1024L
            val javaMaxKb = rt.maxMemory() / 1024L

            // System-wide memory - what the OS sees available; the
            // `lowMemory` flag is when the system thinks it is going to start
            // killing apps to reclaim memory.
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val sysInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(sysInfo)
            val sysAvailMb = sysInfo.availMem / 1024L / 1024L
            val sysTotalMb = sysInfo.totalMem / 1024L / 1024L
            val lowMem = sysInfo.lowMemory

            // FD count from /proc/self/fd. Sockets, files, pipes, anon
            // inodes all count. mpv + Coil + Ktor can each open dozens; a
            // runaway leak (e.g. an unclosed AsyncImage cache stream)
            // surfaces here long before EMFILE crashes the process.
            val fdCount = runCatching { File("/proc/self/fd").list()?.size ?: -1 }
                .getOrDefault(-1)

            // Thermal status (Android 10+; not available on the Z Fold 5
            // running Android 16 - wait that IS available since API 29).
            // Returns higher levels as the device gets hotter; SEVERE +
            // CRITICAL frequently precede video-decoder unavailability.
            val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.currentThermalStatus?.let(::thermalName) ?: "?"
            } else {
                "n/a"
            }

            Log.i(
                TAG,
                "snapshot pss=${totalPssKb}KB (native=${nativePssKb} dalvik=${dalvikPssKb}) " +
                    "java=${javaUsedKb}/${javaMaxKb}KB " +
                    "sys_avail=${sysAvailMb}/${sysTotalMb}MB lowMem=$lowMem " +
                    "fds=$fdCount thermal=$thermal",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "snapshot failed", t)
        }
    }

    /**
     * Forward an Application.onTrimMemory call. We log every level so the
     * trail before a process kill is in the bug report. Higher levels =
     * more severe pressure; RUNNING_CRITICAL means the system is about to
     * kill us. Future phases can react proactively here (clear caches,
     * drop multiview tiles past the soft limit).
     */
    fun onTrimMemory(level: Int) {
        Log.w(TAG, "onTrimMemory level=${trimName(level)} ($level)")
    }

    private fun thermalName(s: Int): String = when (s) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN($s)"
    }

    private fun trimName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        else -> "UNKNOWN($level)"
    }

    private companion object {
        const val TAG = "ResourceTelemetry"

        /** 60 seconds. Coarse enough to not flood logcat; fine enough to
         *  catch the trajectory before a crash. */
        const val SNAPSHOT_INTERVAL_MS = 60_000L
    }
}
