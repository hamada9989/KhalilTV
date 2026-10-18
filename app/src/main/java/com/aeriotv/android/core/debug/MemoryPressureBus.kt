package com.aeriotv.android.core.debug

import android.content.ComponentCallbacks2
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide fan-out of [android.content.ComponentCallbacks2.onTrimMemory]
 * levels to anyone who can shed memory on demand (in-memory EPG, parsed
 * playlists, large bitmap caches, etc).
 *
 * Pattern mirrors [ReminderBannerBus]: a @Singleton Hilt-injected event bus
 * that the Application emits into and ViewModels observe.
 *
 * Why a bus instead of letting Application call into each shedder directly:
 *   1. ViewModels are scoped to the nav graph and may not exist when the
 *      pressure fires; the SharedFlow buffers the most recent level so a VM
 *      created seconds later still sees the signal (replay = 1).
 *   2. Avoids a cycle of Hilt entry points between Application and each
 *      feature module.
 *
 * Levels are passed through verbatim from onTrimMemory so consumers can
 * decide their own threshold (some only care about RUNNING_CRITICAL /
 * COMPLETE, others might shed at MODERATE).
 */
@Singleton
class MemoryPressureBus @Inject constructor() {

    /**
     * Replay=1 + DROP_OLDEST: a late subscriber gets the most recent signal,
     * and a rapid-fire burst (some OEMs fire trim N times in 100ms during
     * a low-memory killer pass) doesn't queue up duplicate work for shedders.
     */
    private val _level = MutableSharedFlow<Int>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val level: SharedFlow<Int> = _level.asSharedFlow()

    fun emit(level: Int) {
        // tryEmit on a buffered SharedFlow with DROP_OLDEST always succeeds;
        // no need to suspend the caller (onTrimMemory is main-thread).
        _level.tryEmit(level)
    }

    companion object {
        /** Shorthand for callers that don't want to import ComponentCallbacks2. */
        fun isCritical(level: Int): Boolean =
            // Exact-match ONLY the genuine low-memory levels. The trim
            // constants are NOT severity-monotonic: TRIM_MEMORY_UI_HIDDEN(20),
            // BACKGROUND(40) and MODERATE(60) are LARGER numbers than
            // RUNNING_CRITICAL(15) yet are routine "app went to background"
            // lifecycle signals, NOT memory pressure. The old `>= 15` cutoff
            // therefore matched UI_HIDDEN on every Home press, so
            // PlaylistViewModel shed the entire in-memory EPG on a normal
            // background and the guide came back BLANK on resume (the disk
            // cache was still "fresh" so the staleness-gated refresh declined
            // to refetch). Mirror MultiviewStore's exact-match guard.
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
    }
}
