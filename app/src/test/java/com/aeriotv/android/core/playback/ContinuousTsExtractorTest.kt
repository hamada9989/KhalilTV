package com.aeriotv.android.core.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timestamp rebasing across a Dispatcharr source switch on a kept connection.
 * Times are microseconds, as TrackOutput.sampleMetadata takes them.
 */
@UnstableApi
class ContinuousTsExtractorTest {

    // ---- harness ----

    private class FakeTrackOutput : TrackOutput {
        val times = ArrayList<Long>()
        override fun format(format: Format) = Unit
        override fun sampleData(
            input: androidx.media3.common.DataReader,
            length: Int,
            allowEndOfInput: Boolean,
        ): Int = length

        override fun sampleData(
            input: androidx.media3.common.DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int = length

        override fun sampleData(data: androidx.media3.common.util.ParsableByteArray, length: Int) = Unit

        override fun sampleData(
            data: androidx.media3.common.util.ParsableByteArray,
            length: Int,
            sampleDataPart: Int,
        ) = Unit

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            times.add(timeUs)
        }
    }

    private class FakeExtractorOutput : ExtractorOutput {
        val tracks = HashMap<Int, FakeTrackOutput>()
        var seekMaps = 0
        var endTracksCalls = 0
        override fun track(id: Int, type: Int): TrackOutput =
            tracks.getOrPut(id) { FakeTrackOutput() }

        override fun endTracks() { endTracksCalls++ }
        override fun seekMap(seekMap: SeekMap) { seekMaps++ }
    }

    /** Stands in for TsExtractor: records the (wrapped) output it is inited with. */
    private class FakeInnerExtractor : Extractor {
        var output: ExtractorOutput? = null
        var seeks = 0
        override fun sniff(input: ExtractorInput): Boolean = true
        override fun init(output: ExtractorOutput) { this.output = output }
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
            Extractor.RESULT_CONTINUE

        override fun seek(position: Long, timeUs: Long) { seeks++ }
        override fun release() = Unit
    }

    private class Rig {
        val downstream = FakeExtractorOutput()
        val inner = FakeInnerExtractor()
        val logs = ArrayList<String>()
        val extractor = ContinuousTsExtractor(inner) { logs.add(it) }
        val wrapped: ExtractorOutput

        init {
            extractor.init(downstream)
            wrapped = inner.output!!
        }

        fun video(): TrackOutput = wrapped.track(VIDEO_ID, C.TRACK_TYPE_VIDEO)
        fun audio(): TrackOutput = wrapped.track(AUDIO_ID, C.TRACK_TYPE_AUDIO)
        fun videoOut(): List<Long> = downstream.tracks[VIDEO_ID]!!.times
        fun audioOut(): List<Long> = downstream.tracks[AUDIO_ID]!!.times

        fun feed(track: TrackOutput, vararg timesUs: Long) {
            timesUs.forEach { track.sampleMetadata(it, C.BUFFER_FLAG_KEY_FRAME, 1, 0, null) }
        }
    }

    private companion object {
        const val VIDEO_ID = 0
        const val AUDIO_ID = 1
        const val FRAME = 33_367L
        const val AAC = 21_333L
    }

    private fun assertMonotonic(times: List<Long>) {
        for (i in 1 until times.size) {
            assertTrue("not monotonic at $i: ${times[i - 1]} -> ${times[i]}", times[i] > times[i - 1])
        }
    }

    // ---- tests ----

    @Test
    fun `steady stream is passed straight through`() {
        val r = Rig()
        val v = r.video()
        val raws = (0 until 30).map { 10_000_000L + it * FRAME }
        r.feed(v, *raws.toLongArray())
        assertEquals(raws, r.videoOut())
        assertTrue(r.logs.isEmpty())
    }

    @Test
    fun `33 bit wrap sized continuous sequence is not rebased`() {
        // TimestampAdjuster has already unwrapped the roll before sampleMetadata,
        // so times keep climbing straight past the wrap point (2^33 / 90 kHz =
        // 95443.7 s). Nothing here may treat that as a source change.
        val r = Rig()
        val v = r.video()
        val wrapUs = 95_443_717_688L
        val raws = (0 until 40).map { wrapUs - 20 * FRAME + it * FRAME }
        r.feed(v, *raws.toLongArray())
        assertEquals(raws, r.videoOut())
        assertTrue(r.logs.isEmpty())
    }

    @Test
    fun `small jitter under the thresholds is not rebased`() {
        val r = Rig()
        val v = r.video()
        // +4.9 s forward and -1.9 s backward both stay inside Apple's window
        // (B-frame reordering and upstream jitter).
        val raws = listOf(
            1_000_000L,
            1_033_367L,
            5_900_000L,
            5_933_367L,
            4_100_000L,
            5_966_734L,
        )
        r.feed(v, *raws.toLongArray())
        assertEquals(raws, r.videoOut())
        assertTrue(r.logs.isEmpty())
    }

    @Test
    fun `forward jump of 31 minutes is rebased to a continuous timeline`() {
        val r = Rig()
        val v = r.video()
        r.feed(v, 10_000_000L, 10_000_000L + FRAME, 10_000_000L + 2 * FRAME)
        val jump = 10_000_000L + 2 * FRAME + 31 * 60_000_000L
        r.feed(v, jump, jump + FRAME, jump + 2 * FRAME)
        val out = r.videoOut()
        assertEquals(6, out.size)
        // The seam is one nominal frame, not 31 minutes.
        assertEquals(FRAME, out[3] - out[2])
        assertEquals(FRAME, out[4] - out[3])
        assertMonotonic(out)
        assertEquals(1, r.logs.size)
        assertTrue(r.logs[0], r.logs[0].startsWith("[SWITCH] rebased PTS by -1859966ms (video jump 1860000ms)"))
    }

    @Test
    fun `backward reset to zero is rebased forward`() {
        val r = Rig()
        val v = r.video()
        val last = 50_000_000L
        r.feed(v, last - FRAME, last)
        r.feed(v, 0L, FRAME, 2 * FRAME)
        val out = r.videoOut()
        assertEquals(last + FRAME, out[2])
        assertEquals(last + 2 * FRAME, out[3])
        assertMonotonic(out)
        assertEquals(1, r.logs.size)
        assertTrue(r.logs[0], r.logs[0].contains("rebased PTS by 50033ms"))
    }

    @Test
    fun `audio arriving before video after a jump is still rebased`() {
        val r = Rig()
        val v = r.video()
        val a = r.audio()
        // Old timeline, roughly aligned.
        r.feed(v, 20_000_000L, 20_000_000L + FRAME)
        r.feed(a, 20_000_000L, 20_000_000L + AAC, 20_000_000L + 2 * AAC)
        // New source: audio crosses first (it usually does, PES interleave).
        val newBase = 300_000_000L
        r.feed(a, newBase, newBase + AAC)
        // Audio must NOT have gone out on the foreign timeline.
        r.audioOut().forEach { assertTrue("audio emitted un-rebased: $it", it < 100_000_000L) }
        assertMonotonic(r.audioOut())
        // Video crosses with the same delta, so it reuses the latched offset
        // and lands within tolerance of audio instead of re-latching.
        r.feed(v, newBase, newBase + FRAME)
        val vOut = r.videoOut()
        assertMonotonic(vOut)
        assertTrue("A/V drifted apart: ${vOut.last()} vs ${r.audioOut().last()}",
            Math.abs(vOut[2] - r.audioOut()[3]) < 500_000L)
        // One rebase logged (audio latched); video agreed silently.
        assertEquals(1, r.logs.count { it.contains("rebased PTS by") })
    }

    @Test
    fun `video re-latches when a lagging track disagrees beyond tolerance`() {
        val r = Rig()
        val v = r.video()
        val a = r.audio()
        r.feed(v, 20_000_000L, 20_000_000L + FRAME)
        r.feed(a, 20_000_000L, 20_000_000L + AAC)
        // Audio jumps to one timeline, video to a wholly different one.
        r.feed(a, 300_000_000L)
        r.feed(v, 900_000_000L, 900_000_000L + FRAME)
        assertMonotonic(r.videoOut())
        assertTrue(r.logs.any { it.contains("re-latched from video") })
        // Video stayed continuous with its own old output.
        assertEquals(FRAME, r.videoOut()[2] - r.videoOut()[1])
    }

    @Test
    fun `two consecutive switches accumulate offsets`() {
        val r = Rig()
        val v = r.video()
        r.feed(v, 10_000_000L, 10_000_000L + FRAME)
        val second = 500_000_000L
        r.feed(v, second, second + FRAME)
        val third = 5_000_000L
        r.feed(v, third, third + FRAME)
        val out = r.videoOut()
        assertEquals(6, out.size)
        assertMonotonic(out)
        assertEquals(FRAME, out[2] - out[1])
        assertEquals(FRAME, out[4] - out[3])
        assertEquals(2, r.logs.count { it.contains("rebased PTS by") })
    }

    @Test
    fun `output never goes backwards on a track across a rebase`() {
        val r = Rig()
        val v = r.video()
        r.feed(v, 10_000_000L, 10_000_000L + FRAME)
        // A jump whose new timeline is only barely behind: without the clamp
        // this would emit a time at or before the previous one.
        r.feed(v, 10_000_000L - 3_000_000L)
        r.feed(v, 10_000_000L - 3_000_000L + 1L)
        assertMonotonic(r.videoOut())
    }

    @Test
    fun `seek clears the rebase state`() {
        val r = Rig()
        val v = r.video()
        r.feed(v, 10_000_000L, 10_000_000L + FRAME)
        r.feed(v, 400_000_000L)
        assertTrue(r.logs.isNotEmpty())
        // A genuine re-open (hard re-tune, Live Rewind buffer entry) starts a
        // brand new timeline; the offset must not survive it.
        r.extractor.seek(0L, 0L)
        assertEquals(1, r.inner.seeks)
        r.feed(v, 777_000_000L, 777_000_000L + FRAME)
        val out = r.videoOut()
        assertEquals(777_000_000L, out[3])
        assertEquals(777_000_000L + FRAME, out[4])
    }

    @Test
    fun `seekMap and endTracks pass through`() {
        val r = Rig()
        r.wrapped.endTracks()
        r.wrapped.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
        assertEquals(1, r.downstream.endTracksCalls)
        assertEquals(1, r.downstream.seekMaps)
    }

    @Test
    fun `unset sample times are passed through untouched`() {
        val r = Rig()
        val v = r.video()
        r.feed(v, C.TIME_UNSET, 10_000_000L)
        assertEquals(C.TIME_UNSET, r.videoOut()[0])
        assertEquals(10_000_000L, r.videoOut()[1])
    }
}
