package com.aeriotv.android.feature.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Double-Back gate for the TV live player (Logan 2026-09-14).
 *
 * Back model on Android TV fullscreen live/DVR playback:
 *   - FIRST Back  -> schedule the minimize-to-mini [WINDOW_MS] later. Nothing
 *                    visible happens yet; the fullscreen player stays up, so
 *                    the mini is never shown for a "Back twice".
 *   - SECOND Back inside the window -> [consumePending] cancels the scheduled
 *                    minimize and the caller runs the full stop (the same
 *                    teardown the chrome's X does), so playback ends with no
 *                    mini player ever appearing.
 *   - Timer fires  -> minimize exactly as before.
 *
 * The delay lives here, in a process-level holder with its own main-thread
 * scope, rather than in a composable: the minimize tears the player
 * composition down, and keeping the timer out of the composition also keeps
 * VODPlayerScreen's top-level composable free of new locals / lambdas.
 */
object PlayerDoubleBack {
    /** Logan's spec: a second Back inside 300 ms is "Back twice". */
    const val WINDOW_MS = 300L

    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    private var pending: Job? = null

    /**
     * Arm the first Back: run [onMinimize] after [WINDOW_MS] unless a second
     * Back consumes it first. Any previously armed press is replaced.
     */
    fun schedule(onMinimize: () -> Unit) {
        pending?.cancel()
        pending = scope.launch {
            delay(WINDOW_MS)
            pending = null
            onMinimize()
        }
    }

    /**
     * True when a scheduled minimize was still waiting, i.e. this Back is the
     * second of a "Back twice". Cancels the minimize so the caller can run the
     * full stop instead.
     */
    fun consumePending(): Boolean {
        val job = pending ?: return false
        pending = null
        job.cancel()
        return true
    }

    /** Drop any armed press (playback ended or left by some other route). */
    fun clear() {
        pending?.cancel()
        pending = null
    }
}
