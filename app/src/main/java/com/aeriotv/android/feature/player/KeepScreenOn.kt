package com.aeriotv.android.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * App-wide reference count of composables that currently want the screen kept
 * on. This is the crux of the ref-counting: `Window.addFlags` /
 * `clearFlags(FLAG_KEEP_SCREEN_ON)` set/clear a SINGLE window bit and are NOT
 * ref-counted by the framework (a widely-held misconception). Two overlapping
 * owners -- e.g. the fullscreen player and the multiview grid during the
 * Live TV -> "+" -> Multiview navigation transition -- both set the same bit,
 * so when the LEAVING screen's onDispose ran clearFlags it wiped the flag the
 * ENTERING screen had just set, and the display slept mid-stream (issue #12).
 * Counting here so the bit is cleared only when the LAST owner leaves mirrors
 * iOS `IdleTimerRefCount` (Aerio/Shared/IdleTimerRefCount.swift), which this
 * was always meant to be.
 */
private val keepScreenOnRefCount = AtomicInteger(0)

/**
 * Keeps the host Activity's window awake for the lifetime of the calling
 * composable, ref-counted so concurrent owners (player, VOD player, multiview,
 * mini <-> fullscreen swaps) can't clear each other's request. When the last
 * owner leaves composition the flag is cleared and normal screen-timeout
 * rules resume.
 *
 * Ports iOS `IdleTimerRefCount.increment()` / `decrement()`: don't let the
 * display dim/sleep while video is playing. Without it the system
 * screen-timeout (often 30s-2min on Samsung / OEM defaults) fires mid-stream.
 *
 * Uses the `FLAG_KEEP_SCREEN_ON` window flag rather than a `PowerManager`
 * wake lock: honored without any permission, doesn't touch the audio/CPU
 * subsystems, and the OS still clears it automatically when the Activity
 * backgrounds. The flag is re-asserted on every increment so it survives an
 * Activity window swap while at least one owner is active.
 */
@Composable
fun KeepScreenOnWhilePlaying() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivityCompat()
        keepScreenOnRefCount.incrementAndGet()
        // Re-assert on every entry (idempotent) so a new owner restores the
        // flag even if the window changed since the last one set it.
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            // Clear ONLY when this was the last owner; another mounted player
            // composable keeps the flag alive.
            if (keepScreenOnRefCount.decrementAndGet() <= 0) {
                keepScreenOnRefCount.set(0)
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

/**
 * Unwrap an Activity out of a (possibly themed) Context chain. Mirrors the
 * `findActivity` helper already used by the PiP plumbing -- declared here
 * with a different name to avoid clashing with the core/pip variant when
 * both are imported in the same file (Kotlin's resolution would pick one
 * arbitrarily and confuse the caller). Functionally identical.
 */
private fun Context.findActivityCompat(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
