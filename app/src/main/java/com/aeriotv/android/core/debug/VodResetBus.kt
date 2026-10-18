package com.aeriotv.android.core.debug

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped fan-out for the "Refresh Everything" nuclear data reset.
 *
 * OnDemandViewModel already runs a full VOD reset (resetVodState() + refresh()
 * + refreshSeries()) when the ACTIVE PLAYLIST ID changes. "Refresh Everything"
 * must trigger that same reset while the id is UNCHANGED, which the existing
 * observeActiveId().drop(1) collector never sees. PlaylistViewModel.refreshEverything()
 * emits here; OnDemandViewModel.init collects and runs its reset.
 *
 * Pattern mirrors [MemoryPressureBus] / [com.aeriotv.android.feature.reminders.ReminderBannerBus]:
 * a @Singleton Hilt event bus, constructor-injected, no DI module required.
 */
@Singleton
class VodResetBus @Inject constructor() {
    // No replay: a reset is a one-shot command, not a state. extraBufferCapacity=1
    // + DROP_OLDEST so tryEmit from the main thread always succeeds and a
    // double-tap coalesces instead of queueing two resets.
    private val _resets = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val resets: SharedFlow<Unit> = _resets.asSharedFlow()

    /** The two halves of the On Demand library, rebuilt independently. */
    enum class Kind { Movies, Series }

    /**
     * How far the rebuild kicked off by the LAST [requestReset] has got.
     *
     * A reset is a command, but Edit Playlist's "Saving Changes" screen needs
     * the state behind it: its "Loading movies..." / "Loading TV shows..." rows
     * follow this. [token] identifies the reset, so a screen that started
     * watching a beat late can tell a fresh rebuild from the previous one's
     * leftovers.
     *
     * A kind is marked the moment it has something to show - its first stored
     * page, or a terminal answer (nothing to fetch, denied, failed). The sweep
     * itself carries on in the background for as long as it takes; nothing
     * here waits for it to finish.
     */
    data class Rebuild(
        val token: Long,
        val moviesSettled: Boolean = false,
        val seriesSettled: Boolean = false,
    ) {
        fun settled(kind: Kind): Boolean =
            if (kind == Kind.Movies) moviesSettled else seriesSettled
    }

    private val _rebuild = MutableStateFlow<Rebuild?>(null)
    val rebuild: StateFlow<Rebuild?> = _rebuild.asStateFlow()

    private var nextToken = 1L

    /**
     * Wipe + rebuild the active playlist's On Demand catalog. Returns the token
     * of this rebuild so the caller can follow it in [rebuild].
     */
    fun requestReset(): Long {
        val token = nextToken++
        _rebuild.value = Rebuild(token)
        _resets.tryEmit(Unit)
        return token
    }

    /** Called by OnDemandViewModel when [kind] has something to show. */
    fun markSettled(kind: Kind) {
        _rebuild.value = _rebuild.value?.let {
            when (kind) {
                Kind.Movies -> it.copy(moviesSettled = true)
                Kind.Series -> it.copy(seriesSettled = true)
            }
        }
    }
}
