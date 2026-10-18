package com.aeriotv.android.feature.livetv

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext

/**
 * Whether Live TV renders the dense channel List or the time-x-channels Guide grid.
 * Mirrors iOS `defaultLiveTVView` @AppStorage. Phase 5 keeps the value
 * rememberSaveable; Phase 8 (Settings sub-screens) will move it to DataStore so
 * the user choice survives cold starts.
 */
enum class LiveTVViewMode { List, Guide }

/**
 * Form-factor decision matching iOS ChannelListView dispatcher
 * (`ChannelListView.swift:348`):
 *  - iPhone (Compact width): List only, no toggle.
 *  - iPad / large screens (Expanded width): Guide default, toggle available.
 *  - Medium (foldable unfolded / smaller tablets): List default, toggle available.
 *  - Google TV / Android TV (UI_MODE_TYPE_TELEVISION): Guide default, toggle available.
 *
 * Android maps the iOS `idiom` enum onto WindowWidthSizeClass + the Configuration's
 * `uiMode` television flag.
 */
@Immutable
data class LiveTvFormFactor(
    val widthClass: WindowWidthSizeClass,
    val isTv: Boolean,
) {
    /** Phone-class layout: List is the default, but the toggle is still offered. */
    val isCompactPhone: Boolean get() = widthClass == WindowWidthSizeClass.Compact && !isTv

    /**
     * Whether the List view exists at all on this form factor. TV drops it
     * entirely (Logan 2026-09-16: unusable with a remote) unless
     * [com.aeriotv.android.core.ui.TvListView.ENABLED] is flipped back on.
     * Phone and tablet always have it.
     */
    val listAvailable: Boolean
        get() = !isTv || com.aeriotv.android.core.ui.TvListView.ENABLED

    /**
     * Whether the user can flip between List and Guide. Always true: compact
     * phones in portrait previously hid the toggle (only landscape, where the
     * width class leaves Compact, showed it), which left no way to reach the
     * Guide in portrait. The Guide is usable in portrait (it horizontally
     * scrolls), so we always offer the toggle; [defaultMode] still picks List
     * for compact phones so nothing changes for users who don't toggle.
     *
     * TV is the exception: with the List view removed there is nothing to
     * toggle to, so this is false there (see [listAvailable]).
     */
    val supportsToggle: Boolean get() = listAvailable

    /** Default view when no user override exists. */
    val defaultMode: LiveTVViewMode
        get() = when {
            !listAvailable -> LiveTVViewMode.Guide
            isCompactPhone -> LiveTVViewMode.List
            isTv -> LiveTVViewMode.Guide
            widthClass == WindowWidthSizeClass.Expanded -> LiveTVViewMode.Guide
            else -> LiveTVViewMode.List
        }
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun rememberLiveTvFormFactor(): LiveTvFormFactor {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val widthClass = if (activity != null) {
        calculateWindowSizeClass(activity).widthSizeClass
    } else {
        WindowWidthSizeClass.Compact
    }
    val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    val isTv = uiMode == Configuration.UI_MODE_TYPE_TELEVISION
    return remember(widthClass, isTv) { LiveTvFormFactor(widthClass, isTv) }
}

@Composable
fun rememberLiveTvViewModeState(formFactor: LiveTvFormFactor): MutableState<LiveTVViewMode> {
    return rememberSaveable(formFactor.defaultMode) {
        mutableStateOf(formFactor.defaultMode)
    }
}

private fun android.content.Context.findActivity(): Activity? {
    var ctx: android.content.Context? = this
    while (ctx != null) {
        if (ctx is Activity) return ctx
        ctx = (ctx as? android.content.ContextWrapper)?.baseContext
    }
    return null
}
