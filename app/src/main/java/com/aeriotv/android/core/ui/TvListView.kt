package com.aeriotv.android.core.ui

/**
 * Master switch for the Live TV LIST view on TV form factors (Android TV /
 * Google TV, `LiveTvFormFactor.isTv`).
 *
 * Logan's decision 2026-09-16: the dense channel List is unusable with a
 * remote, so the Guide is the ONLY Live TV presentation on TV. The List code
 * is kept intact and every TV entry point is gated on this flag, so flipping
 * it back to `true` restores the previous behavior exactly, including a user
 * whose stored "Default Live TV View" is "list" (the stored value is never
 * rewritten while the flag is off; it is only resolved to Guide at runtime).
 *
 * Phone and tablet are untouched: they keep the List view, the List / Guide
 * header toggle, and the Settings default.
 *
 * Apple ships the identical gate.
 */
object TvListView {
    const val ENABLED = false
}
