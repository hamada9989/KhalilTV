package com.aeriotv.android.core.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Wraps a [DataSource] so it always reports [C.LENGTH_UNSET] from open(),
 * hiding the upstream Content-Length from ExoPlayer while delegating all reads
 * verbatim.
 *
 * Purpose (catch-up, task #133): Dispatcharr's /timeshift/ archive is served
 * with a big estimated Content-Length (bitrate x duration), and every new
 * connection first hits a 301 that mints a fresh ?session_id. When ExoPlayer's
 * TS path trusts that length it seeks to the claimed end to read the last PCR
 * for a duration/seek-map; that end-seek opens a new connection, re-redirects
 * to a fresh session, and the far-offset read fails -> EOFException before a
 * single frame plays (verified on device). Reporting unknown length makes
 * ExoPlayer treat the stream as unbounded (exactly how it plays live
 * /proxy/ts/): one connection, one session, streamed start-to-finish, no
 * end-seek. Backward scrubbing within the buffered region still works.
 *
 * Applied only to catch-up URLs; VOD/recordings keep their real length so
 * whole-file seeking is unaffected.
 */
@UnstableApi
class UnboundedLengthDataSource(private val upstream: DataSource) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        // Force the actual byte read to start at the spec's position but let
        // the length stay unknown so no end-seek is ever attempted.
        upstream.open(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri() = upstream.uri

    override fun getResponseHeaders() = upstream.responseHeaders

    override fun close() = upstream.close()

    @UnstableApi
    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            UnboundedLengthDataSource(upstream.createDataSource())
    }
}
