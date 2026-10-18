package com.aeriotv.android.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One Movies or TV Shows title in the persisted VOD catalog (GH #109).
 *
 * The catalog used to live in memory (OnDemandViewModel's movie and series
 * lists) and in one serialized snapshot file, which capped a library at
 * 40,000 rows and still ran a 155 MB TV box out of heap at ~16k series on a
 * 101k movie / 30k series account. Rows now land here page by page as the
 * sweep walks the provider, and the grids read windows of them, so memory no
 * longer grows with the catalog.
 *
 * [playlistKey] is VodLibrarySnapshotStore.identity (playlist id, URL,
 * account), so another source never serves this library. [kind] is "m" or
 * "s". [itemKey] is the movie uuid or the series id as text; the MediaItem key
 * the watchlist, hidden titles and watch progress use is kind + ":" + itemKey,
 * so none of those keys change.
 *
 * [generation] is the sweep that last confirmed the row. A finished sweep
 * deletes rows from older generations; a failed or interrupted sweep never
 * deletes anything.
 *
 * The id is AUTOINCREMENT so a deleted row's id is never reused: the grid's
 * window cache is keyed by it.
 */
@Entity(
    tableName = "vod_title",
    indices = [
        Index(value = ["playlistKey", "kind", "itemKey"], unique = true),
        Index(value = ["playlistKey", "kind", "sortKey"]),
        Index(value = ["playlistKey", "kind", "generation"]),
        Index(value = ["playlistKey", "kind", "tmdbId"]),
        Index(value = ["playlistKey", "kind", "normTitle"]),
        Index(value = ["playlistKey", "kind", "cleanTitle"]),
    ],
)
data class VodTitleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistKey: String,
    val kind: String,
    val itemKey: String,
    /** MediaItem.title: the provider name with language, quality and year tags removed. */
    val title: String,
    /** MediaItem.sortKey, so SQL sorts exactly like the old in-memory sort. */
    val sortKey: String,
    /** MediaItem.bucket as a one-character string (alphabet rail). */
    val bucket: String,
    val year: Int?,
    val rating: String?,
    /** [rating] parsed, for the Rating sort. */
    val ratingValue: Double?,
    val posterUrl: String?,
    /** The stamped group name (Manage Groups filter, genre pills). */
    val category: String?,
    val tmdbId: String?,
    /** OnDemandViewModel's normalizeVodTitle(displayName): cast search, Known For. */
    val normTitle: String,
    /** cleanArtTitle(displayName): the Related strip matcher. */
    val cleanTitle: String,
    /** tmdbArtKey of [title]: the background TMDB art pass. */
    val artKey: String,
    /** The full DispatcharrVODMovie / DispatcharrVODSeries as JSON, for detail screens. */
    val payload: String,
    val generation: Long,
)

/**
 * Sweep bookkeeping for one playlist + kind. [open] is true from the moment a
 * sweep starts until it finishes cleanly, so a sweep killed mid-walk (app
 * update, force stop, a playlist switch) resumes the SAME generation next
 * time instead of starting over, and the launch gate treats that kind as
 * stale however recent [completedAtMs] is.
 */
@Entity(tableName = "vod_sweep_state", primaryKeys = ["playlistKey", "kind"])
data class VodSweepStateEntity(
    val playlistKey: String,
    val kind: String,
    val generation: Long,
    val open: Boolean,
    val startedAtMs: Long,
    /** When a sweep of this kind last finished. 0 = never. */
    val completedAtMs: Long,
)

/**
 * One category walk ("lane") inside a sweep. Dispatcharr categories are
 * walked round-robin a page at a time, and each lane's position is saved
 * after every page, so a failed page resumes from exactly that page on the
 * next sweep. [lane] is the category name, or "" for the unfiltered walk.
 */
@Entity(tableName = "vod_sweep_lane", primaryKeys = ["playlistKey", "kind", "lane"])
data class VodSweepLaneEntity(
    val playlistKey: String,
    val kind: String,
    val lane: String,
    val generation: Long,
    /** Host-free query string of the next page to fetch ("page_size=100&..."),
     *  rebuilt against the CURRENT base URL so a LAN/WAN switch between runs
     *  never sends the key to a stale host. Null once the lane is walked. */
    val nextQuery: String?,
    /** Consecutive sweeps whose page fetch failed on this lane. */
    val failures: Int,
    /** The lane's own first page reported content for this account. */
    val hasContent: Boolean,
)
