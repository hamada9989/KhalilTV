package com.aeriotv.android.feature.multiview

import com.aeriotv.android.core.data.M3UChannel

/**
 * What KIND of content a multiview tile carries. Mirrors iOS
 * `TilePlaybackKind` (Aerio/Features/Multiview/MultiviewTile.swift): a tile is
 * a live channel, an on-demand title (movie OR series episode), or an
 * (in-progress) DVR recording. Only [Vod] tiles participate in resume /
 * periodic-save / Finished-overlay; [Live] and [Dvr] tiles do not.
 */
enum class TileKind { Live, Vod, Dvr }

/**
 * One cell in the multiview grid. Replaces the prior `List<M3UChannel>`
 * backing so the grid can mix live channels with VOD / Series / DVR content
 * (iOS parity: MultiviewTile). The store keys everything on [id] (swap / move /
 * remove / audio-focus all match by id), so for live tiles [id] == channel.id
 * to keep RecentChannels / isSelected(channel) / inMultiview matching exactly.
 *
 * Per-tile [httpHeaders] carry the Dispatcharr auth headers for VOD / DVR
 * tiles (the live grid passes its own headers via MultiviewScreen(httpHeaders);
 * live tiles therefore carry none). [resumePositionMs] is pre-resolved by the
 * picker (Phase 2) so ExoTile stays synchronous; null means "no resume".
 */
data class MultiviewTile(
    val id: String,
    val kind: TileKind,
    val displayName: String,
    val resolvedUrl: String,
    val httpHeaders: Map<String, String> = emptyMap(),
    val logoUrl: String = "",
    val channelNumber: String? = null,
    val tvgID: String = "",
    val vodId: String? = null, // null = no continue-watching (DVR/recordings)
    val vodType: String = "movie", // movie | episode
    val seriesId: String? = null,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val posterUrl: String? = null,
    val resumePositionMs: Long? = null, // null = no saved resume position
) {
    companion object {
        /** Wrap a live channel as a tile. Live tile id == channel.id (see
         *  class doc) and live tiles carry NO per-tile headers. */
        fun live(channel: M3UChannel) = MultiviewTile(
            id = channel.id,
            kind = TileKind.Live,
            displayName = channel.name,
            resolvedUrl = channel.url,
            logoUrl = channel.tvgLogo,
            channelNumber = channel.channelNumber,
            tvgID = channel.tvgID,
        )
    }
}
