package com.aeriotv.android.core.cast.hlsproxy

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Audio frame census across a whole remux, added 2026-09-13 after a live
 * cast to the Google TV Streamer was measured on the device: the media
 * clock ratio read 1.0000 and Chromium decoded 60 fps with zero drops,
 * yet SurfaceFlinger presented about 47 fps and Chromium logged audio
 * DEMUXER_UNDERFLOW. The proxy's per-segment census was the tell: ESPNU
 * 4.00 s segments carried 179 to 189 AAC frames where 187.5 belong, so up
 * to one PES worth of frames (8 frames, 170 ms) went missing at some
 * segment cuts, the audio renderer starved, and the video renderer held
 * frames waiting on it.
 *
 * Two defects produced that, and both are measured here:
 *
 *  1. The carried partial frame was re-stamped. When a PES payload ends
 *     mid-frame the tail is carried into the next PES, and the first
 *     frame COMPLETED in the next call is that carried frame. A PES PTS
 *     describes the first access unit that COMMENCES in its payload, so
 *     the carried frame's true time is the running clock, not that PTS.
 *     Stamping it with the PES PTS put it, and every follower in the PES,
 *     one frame (21.33 ms) late: a hole in the audio timeline at every
 *     straddling PES, which is what Chromium's audio splicer trims or
 *     drops. `audio frames survive PES packing that splits every frame`
 *     covers this with a transport stream whose audio PES packets are cut
 *     at byte offsets that ignore frame boundaries.
 *  2. Audio that arrived after the cut keyframe was lost to the splicer.
 *     Provider audio trails its video in the mux, so the last ~170 ms of
 *     a segment's audio was still unparsed when the cut keyframe arrived;
 *     those frames landed in the NEXT segment with a pts below its own
 *     start, which is a backwards audio append. The remuxer now holds the
 *     cut until the audio has reached it. `a mux whose audio trails its
 *     video keeps every frame` covers this.
 *
 * The census that cannot be faked: count ADTS syncwords in the raw ADTS
 * demux of the SAME transport stream, then count what the remuxer
 * emitted, and require that nothing was lost beyond the audio that
 * legitimately precedes the first kept video keyframe. The primary
 * fixture is a real capture of the live Dispatcharr AAC output profile
 * (see [realFixture]); the ffmpeg-built ones cover the muxer's own
 * packing variants. All of those SKIP themselves when their input is
 * absent so CI stays green; the synthetic tests always run.
 */
class CastAudioFrameCensusTest {

    private val ffmpeg = File("/opt/homebrew/bin/ffmpeg")

    /** A real ESPNU capture off the live Dispatcharr AAC output profile
     *  (H.264 1920x1080 59.94 fps, AAC-LC 48 kHz stereo, 66 s, 3130 ADTS
     *  frames). Too large to check in, so the path is overridable and the
     *  test skips when the capture is not on this machine. */
    private val realFixture: File =
        File(System.getenv("CAST_TS_FIXTURE") ?: "/private/tmp/claude-501/-Users-loganjones-Documents-xcode-iOSDev/4567b156-2e97-48d3-a8d7-44b0e9a3fd9a/scratchpad/espnu-aac-60s.ts")

    private val workDir: File by lazy {
        File("build/tmp/castHlsAudioCensus").apply { mkdirs() }
    }

    private class Capture : TsToFmp4Remuxer.Listener {
        var init: ByteArray? = null
        val segments = ArrayList<ByteArray>()
        val durations = ArrayList<Long>()
        val audioCounts = ArrayList<Int>()
        val logs = ArrayList<String>()
        override fun onInitSegments(video: ByteArray, audio: ByteArray?) { init = video }

        /** Only the AUDIO rendition is kept: every assertion in this file
         *  reads the audio traf, and since 2026-09-13 that traf ships in
         *  its own rendition instead of beside the video one. */
        override fun onMediaSegment(
            video: ByteArray,
            audio: ByteArray?,
            videoDurationTicks: Long,
            audioDurationTicks: Long,
        ) {
            segments.add(audio ?: video); durations.add(videoDurationTicks)
        }
        override fun onSegmentComposition(
            videoSamples: Int,
            audioSamples: Int,
            firstVideoDtsSeconds: Double,
            firstVideoPtsSeconds: Double,
            firstAudioPtsSeconds: Double,
            segmentStartSeconds: Double,
        ) {
            audioCounts.add(audioSamples)
        }
    }

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        return Pair(p.waitFor(), text)
    }

    // ---- fixtures ----

    /**
     * 59.94 fps H.264 plus ADTS AAC-LC 48 kHz stereo in MPEG-TS with the
     * muxer's NATURAL PES packing, so several ADTS frames ride one PES and
     * frames straddle TS packet boundaries. [patPmtAtFrames] builds the
     * variant with a PAT/PMT at every frame and a small max_delay, which
     * packs the audio differently again.
     */
    private fun buildTs(name: String, patPmtAtFrames: Boolean): File {
        val out = File(workDir, "$name.ts")
        if (out.isFile && out.length() > 0) return out
        val cmd = arrayListOf(
            ffmpeg.path, "-y", "-v", "error",
            "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=60000/1001:duration=60",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=60",
            "-c:v", "libx264", "-preset", "ultrafast", "-bf", "2", "-g", "120", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k", "-ac", "2", "-ar", "48000",
        )
        if (patPmtAtFrames) {
            cmd += listOf("-mpegts_flags", "+pat_pmt_at_frames", "-max_delay", "100000")
        }
        cmd += listOf("-f", "mpegts", out.path)
        val (code, log) = run(*cmd.toTypedArray())
        assertEquals("ffmpeg built $name: $log", 0, code)
        return out
    }

    /** ADTS frames in the input, counted out of the raw ADTS demux of the
     *  same transport stream (`-c copy -f adts`), which is the provider's
     *  frame count by definition. */
    private fun inputFrameCount(ts: File): Int {
        val adts = File(workDir, ts.nameWithoutExtension + ".aac")
        if (!adts.isFile || adts.length() == 0L) {
            val (code, log) = run(
                ffmpeg.path, "-y", "-v", "error", "-i", ts.path,
                "-map", "0:a:0", "-c", "copy", "-f", "adts", adts.path,
            )
            assertEquals("ffmpeg demuxed ADTS: $log", 0, code)
        }
        return countAdtsFrames(adts.readBytes())
    }

    private fun countAdtsFrames(b: ByteArray): Int {
        var p = 0
        var n = 0
        while (p + 7 <= b.size) {
            if (b[p].toInt() and 0xFF != 0xFF || b[p + 1].toInt() and 0xF0 != 0xF0) { p++; continue }
            val len = ((b[p + 3].toInt() and 0x03) shl 11) or
                ((b[p + 4].toInt() and 0xFF) shl 3) or
                ((b[p + 5].toInt() shr 5) and 0x07)
            if (len <= 7 || p + len > b.size) { p++; continue }
            n++
            p += len
        }
        return n
    }

    private fun remux(bytes: ByteArray): Capture {
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(
            listener = cap,
            log = { cap.logs.add(it) },
            allowAc3Passthrough = false,
        )
        var off = 0
        while (off < bytes.size) {
            val n = minOf(64 * 1024, bytes.size - off)
            remuxer.feed(bytes, off, n)
            off += n
        }
        remuxer.release() // the generation's tail segment counts too
        return cap
    }

    // ---- emitted-fMP4 readers ----

    /** Every audio sample's (pts, duration) in order, read back out of an
     *  emitted segment: the audio tfdt plus the trun's own durations. */
    private fun audioPtsRuns(segment: ByteArray): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        forEachBox(segment, 0, segment.size) { type, start, end ->
            if (type != "moof") return@forEachBox
            forEachBox(segment, start, end) { trafType, trafStart, trafEnd ->
                if (trafType != "traf") return@forEachBox
                var trackId = -1
                var tfdt = -1L
                var trunStart = -1
                forEachBox(segment, trafStart, trafEnd) { box, bs, _ ->
                    when (box) {
                        "tfhd" -> trackId = readU32(segment, bs + 4)
                        "tfdt" -> tfdt = if (segment[bs].toInt() == 1) {
                            readU64(segment, bs + 4)
                        } else {
                            readU32(segment, bs + 4).toLong()
                        }
                        "trun" -> trunStart = bs
                    }
                }
                if (trackId != 2 || trunStart < 0) return@forEachBox
                val flags = readU32(segment, trunStart) and 0x00FFFFFF
                var p = trunStart + 4
                val count = readU32(segment, p); p += 4
                if (flags and 0x000001 != 0) p += 4 // data-offset
                if (flags and 0x000004 != 0) p += 4 // first-sample-flags
                var pts = tfdt
                repeat(count) {
                    var duration = 0L
                    if (flags and 0x000100 != 0) { duration = readU32(segment, p).toLong(); p += 4 }
                    if (flags and 0x000200 != 0) p += 4 // size
                    if (flags and 0x000400 != 0) p += 4 // flags
                    if (flags and 0x000800 != 0) p += 4 // composition offset
                    out.add(Pair(pts, duration))
                    pts += duration
                }
            }
        }
        return out
    }

    private fun forEachBox(b: ByteArray, from: Int, to: Int, body: (String, Int, Int) -> Unit) {
        var p = from
        while (p + 8 <= to) {
            val size = readU32(b, p)
            if (size < 8 || p + size > to) return
            body(String(b, p + 4, 4, Charsets.US_ASCII), p + 8, p + size)
            p += size
        }
    }

    private fun readU32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    // ---- assertions ----

    private val frameTicks = 1024L * TsToFmp4Remuxer.TICKS_PER_SECOND / 48_000L

    /** Audio pts continuity across every PES boundary AND every segment
     *  boundary: one frame duration per frame, one tick of tolerance.
     *  Returns the frames counted. */
    private fun assertContiguousAudio(cap: Capture, label: String): Int {
        var previous = -1L
        var index = 0
        cap.segments.forEachIndexed { s, segment ->
            for ((pts, duration) in audioPtsRuns(segment)) {
                assertEquals(
                    "$label: frame $index in segment $s has the wrong duration",
                    frameTicks, duration,
                )
                if (previous >= 0) {
                    val delta = pts - previous
                    assertTrue(
                        "$label: audio discontinuity at frame $index (segment $s): pts $pts " +
                            "follows $previous, delta $delta, expected $frameTicks",
                        delta in (frameTicks - 1)..(frameTicks + 1),
                    )
                }
                previous = pts
                index++
            }
        }
        return index
    }

    /**
     * The whole-stream census: input frames versus output frames, pts
     * continuity, and the per-segment shortfall.
     *
     * [lead] is how many frames may legitimately go missing, and it is
     * only ever the audio that precedes the first kept video keyframe: the
     * unsigned tfdt cannot express a negative start, and an audio sample
     * below a sequence-mode append's anchor costs the load a decoder swap.
     * One GOP of 59.94 fps video is 2.002 s, i.e. 94 AAC frames, plus the
     * handful parsed before the init segment existed.
     */
    /** The emitted media is fully covered by audio: the frames shipped
     *  account for every segment's declared duration, to within the one
     *  frame a boundary can quantize away. A lost frame shows up here as
     *  a shortfall even when the fixture's own head and tail are ragged,
     *  which is what makes this the assertion to read first. */
    private fun assertAudioCoversDuration(cap: Capture, label: String) {
        val declared = cap.durations.sum()
        val covered = cap.audioCounts.sum() * frameTicks
        assertTrue(
            "$label: audio covers ${"%.3f".format(covered / 90_000.0)} s of the " +
                "${"%.3f".format(declared / 90_000.0)} s the playlist declares",
            covered >= declared - 2 * frameTicks,
        )
    }

    private fun census(name: String, ts: File, lead: Int, minSegments: Int) {
        val input = inputFrameCount(ts)
        val cap = remux(ts.readBytes())
        val output = cap.audioCounts.sum()
        println("[census $name] input=$input output=$output segments=${cap.segments.size}")
        println("[census $name] per segment=${cap.audioCounts}")
        cap.logs.filter { it.startsWith("audio census") }.forEach { println("[census $name] $it") }
        assertTrue("$name: at least $minSegments segments, got ${cap.segments.size}", cap.segments.size >= minSegments)
        assertEquals("$name: every counted frame reached a segment", output, assertContiguousAudio(cap, name))
        assertAudioCoversDuration(cap, name)
        assertTrue(
            "$name: audio frames lost: input $input, output $output (lead allowance $lead)",
            output >= input - lead,
        )
        assertTrue("$name: output cannot exceed input: $output > $input", output <= input)

        // Per-segment census: every interior segment carries what its own
        // duration calls for, to within the one frame a boundary can
        // quantize away. This is the aexp=N the device log now prints.
        cap.durations.forEachIndexed { i, durationTicks ->
            if (i == 0 || i == cap.durations.size - 1) return@forEachIndexed
            val expected = durationTicks.toDouble() / frameTicks
            val actual = cap.audioCounts[i]
            assertTrue(
                "$name: segment $i carries $actual audio frames, expected about " +
                    "${"%.1f".format(expected)}",
                actual >= expected - 1.5 && actual <= expected + 1.5,
            )
        }
    }

    @Test
    fun `a real ESPNU capture loses no audio frame`() {
        assumeTrue("ffmpeg present", ffmpeg.canExecute())
        assumeTrue("ESPNU capture present at ${realFixture.path}", realFixture.isFile)
        census("espnu", realFixture, lead = 100, minSegments = 12)
    }

    @Test
    fun `no audio frame is lost with the muxer's natural PES packing`() {
        assumeTrue("ffmpeg present", ffmpeg.canExecute())
        census("natural5994", buildTs("natural5994", patPmtAtFrames = false), lead = 100, minSegments = 10)
    }

    @Test
    fun `no audio frame is lost with a PAT PMT at every frame`() {
        assumeTrue("ffmpeg present", ffmpeg.canExecute())
        census("patpmt5994", buildTs("patpmt5994", patPmtAtFrames = true), lead = 100, minSegments = 10)
    }

    // ---- synthetic transport streams ----
    //
    // ffmpeg's own TS muxer starts each audio PES on a frame boundary, so
    // it cannot exercise the carry at all. These build the packing a
    // provider's mux really produces: PES payloads cut at arbitrary byte
    // offsets, so most PES packets both begin and end mid-frame, and the
    // PES PTS names the first frame that COMMENCES in the payload
    // (ISO/IEC 13818-1 2.4.3.7), not the carried one.

    private class TsWriter {
        private val out = ByteArrayOutputStream(1 shl 20)
        private val continuity = HashMap<Int, Int>()

        fun bytes(): ByteArray = out.toByteArray()

        fun psi(pid: Int, table: ByteArray) {
            val section = ByteArray(184)
            section[0] = 0 // pointer_field
            System.arraycopy(table, 0, section, 1, table.size)
            for (i in table.size + 1 until 184) section[i] = 0xFF.toByte()
            packetRaw(pid, section, pusi = true, adaptation = false)
        }

        fun pes(pid: Int, payload: ByteArray) {
            var off = 0
            var pusi = true
            while (off < payload.size) {
                val n = minOf(184, payload.size - off)
                val body: ByteArray
                if (n == 184) {
                    body = payload.copyOfRange(off, off + n)
                } else {
                    // Stuff the short final packet with an adaptation
                    // field, the way a real mux does.
                    val stuffing = 184 - n
                    body = ByteArray(184)
                    body[0] = (stuffing - 1).toByte()
                    if (stuffing >= 2) {
                        body[1] = 0
                        for (i in 2 until stuffing) body[i] = 0xFF.toByte()
                    }
                    System.arraycopy(payload, off, body, stuffing, n)
                }
                packetRaw(pid, body, pusi, adaptation = n != 184)
                pusi = false
                off += n
            }
        }

        private fun packetRaw(pid: Int, body: ByteArray, pusi: Boolean, adaptation: Boolean) {
            val cc = continuity[pid] ?: 0
            continuity[pid] = (cc + 1) and 0x0F
            val p = ByteArray(188)
            p[0] = 0x47
            p[1] = ((if (pusi) 0x40 else 0) or ((pid shr 8) and 0x1F)).toByte()
            p[2] = (pid and 0xFF).toByte()
            p[3] = ((if (adaptation) 0x30 else 0x10) or cc).toByte()
            System.arraycopy(body, 0, p, 4, 184)
            out.write(p)
        }
    }

    private fun ptsBytes(marker: Int, ts: Long): ByteArray = byteArrayOf(
        ((marker shl 4) or ((((ts shr 30) and 0x07).toInt()) shl 1) or 1).toByte(),
        ((ts shr 22) and 0xFF).toByte(),
        (((((ts shr 15) and 0x7F).toInt()) shl 1) or 1).toByte(),
        ((ts shr 7) and 0xFF).toByte(),
        ((((ts and 0x7F).toInt()) shl 1) or 1).toByte(),
    )

    private fun pesPacket(streamId: Int, payload: ByteArray, pts: Long, dts: Long?): ByteArray {
        val stamps = if (dts == null) ptsBytes(2, pts) else ptsBytes(3, pts) + ptsBytes(1, dts)
        val header = byteArrayOf(
            0, 0, 1, streamId.toByte(), 0, 0,
            0x80.toByte(),
            (if (dts == null) 0x80 else 0xC0).toByte(),
            stamps.size.toByte(),
        )
        val body = header + stamps + payload
        val length = body.size - 6
        body[4] = ((length shr 8) and 0xFF).toByte()
        body[5] = (length and 0xFF).toByte()
        return body
    }

    private fun patTable(): ByteArray {
        val body = byteArrayOf(0x00, 0x01, 0xC1.toByte(), 0, 0, 0, 0x01, 0xF0.toByte(), 0x00) // PMT on pid 0x1000
        return byteArrayOf(0x00, 0xB0.toByte(), (body.size + 4).toByte()) + body + ByteArray(4)
    }

    private fun pmtTable(): ByteArray {
        val body = byteArrayOf(
            0x00, 0x01, 0xC1.toByte(), 0, 0,
            0xE1.toByte(), 0x00, // PCR pid 0x100
            0xF0.toByte(), 0x00, // program_info_length
            0x1B, 0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00, // H.264 on 0x100
            0x0F, 0xE1.toByte(), 0x01, 0xF0.toByte(), 0x00, // ADTS AAC on 0x101
        )
        return byteArrayOf(0x02, 0xB0.toByte(), (body.size + 4).toByte()) + body + ByteArray(4)
    }

    /** One ADTS AAC-LC 48 kHz stereo frame of [frameLen] bytes. The
     *  payload is constant filler that can never be mistaken for a
     *  syncword, so the remuxer's false-sync guard has nothing to trip on
     *  and the census counts frames, not guesses. */
    private fun adtsFrame(frameLen: Int): ByteArray {
        val f = ByteArray(frameLen) { 0x21 }
        f[0] = 0xFF.toByte()
        f[1] = 0xF1.toByte() // MPEG-4, layer 00, no CRC
        f[2] = ((1 shl 6) or (3 shl 2)).toByte() // AAC-LC, 48 kHz, chan cfg high bit 0
        f[3] = ((1 shl 6) or ((frameLen shr 11) and 0x03)).toByte() // chan cfg 2
        f[4] = ((frameLen shr 3) and 0xFF).toByte()
        f[5] = (((frameLen and 0x07) shl 5) or 0x1F).toByte()
        f[6] = 0xFC.toByte()
        return f
    }

    private fun videoAu(keyframe: Boolean): ByteArray {
        // Parameter sets in band on every access unit, plus an IDR or a
        // non-IDR slice, which is all the remuxer's AVCC conversion reads.
        val sps = byteArrayOf(
            0x67, 0x42, 0xC0.toByte(), 0x1E, 0xD9.toByte(), 0x00, 0xF0.toByte(),
            0x11, 0x7E.toByte(), 0xF0.toByte(), 0x3C, 0x80.toByte(),
        )
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val slice = ByteArray(400) { 0x10 }
        slice[0] = if (keyframe) 0x65 else 0x41
        val start = byteArrayOf(0, 0, 0, 1)
        return start + sps + start + pps + start + slice
    }

    /**
     * A transport stream whose audio PES packets are cut at byte offsets
     * that have nothing to do with frame boundaries, so the remuxer's
     * carry runs on nearly every PES.
     *
     * [audioLagTicks] delays the audio PES in MUX ORDER (not in time), so
     * the audio that belongs in a segment arrives after the keyframe that
     * cuts it, which is what the measured providers do.
     *
     * Returns the stream and the ADTS frame count it carries.
     */
    private fun straddlingTs(
        videoFrames: Int,
        videoFrameTicks: Long,
        gop: Int,
        frameLen: Int,
        audioLagTicks: Long,
    ): Pair<ByteArray, Int> {
        val base = 10_000L
        val ts = TsWriter()
        val audioFrames = ((videoFrames * videoFrameTicks) / frameTicks).toInt() + 4
        val es = ByteArrayOutputStream(audioFrames * frameLen)
        for (i in 0 until audioFrames) es.write(adtsFrame(frameLen))
        val esBytes = es.toByteArray()
        // PES payload sizes that never line up with frameLen.
        val cuts = intArrayOf(frameLen * 2 + 57, frameLen * 3 - 31, frameLen + 7, frameLen * 4 + 13)
        class AudioPes(val start: Int, val end: Int, val pts: Long)
        val audioPesList = ArrayList<AudioPes>()
        var off = 0
        var cut = 0
        while (off < esBytes.size) {
            var end = minOf(off + cuts[cut % cuts.size], esBytes.size)
            cut++
            val firstFrame = (off + frameLen - 1) / frameLen
            // A payload in which no frame COMMENCES would be sent without
            // a PTS and the remuxer drops unstamped PES, so grow this one
            // until a frame starts in it instead of building a fixture
            // that tests the wrong thing.
            while (firstFrame * frameLen >= end && end < esBytes.size) {
                end = minOf(end + cuts[cut % cuts.size], esBytes.size)
                cut++
            }
            if (firstFrame * frameLen >= end) break
            audioPesList.add(AudioPes(off, end, base + firstFrame * frameTicks))
            off = end
        }
        ts.psi(0, patTable())
        ts.psi(0x1000, pmtTable())
        var nextAudio = 0
        for (i in 0 until videoFrames) {
            val dts = base + i * videoFrameTicks
            ts.pes(0x100, pesPacket(0xE0, videoAu(i % gop == 0), pts = dts, dts = dts))
            while (nextAudio < audioPesList.size && audioPesList[nextAudio].pts <= dts - audioLagTicks) {
                val a = audioPesList[nextAudio]
                ts.pes(0x101, pesPacket(0xC0, esBytes.copyOfRange(a.start, a.end), pts = a.pts, dts = null))
                nextAudio++
            }
        }
        while (nextAudio < audioPesList.size) {
            val a = audioPesList[nextAudio]
            ts.pes(0x101, pesPacket(0xC0, esBytes.copyOfRange(a.start, a.end), pts = a.pts, dts = null))
            nextAudio++
        }
        return Pair(ts.bytes(), countAdtsFrames(esBytes))
    }

    @Test
    fun `audio frames survive PES packing that splits every frame`() {
        // 12 s of 30 fps video, a keyframe every second, 400-byte AAC
        // frames, audio in step with the video in mux order.
        val (bytes, input) = straddlingTs(
            videoFrames = 360,
            videoFrameTicks = 3_000L,
            gop = 30,
            frameLen = 400,
            audioLagTicks = 0L,
        )
        val cap = remux(bytes)
        val output = cap.audioCounts.sum()
        println("[straddle] input=$input output=$output segments=${cap.segments.size} per=${cap.audioCounts}")
        assertTrue("at least 3 segments, got ${cap.segments.size}", cap.segments.size >= 3)
        assertEquals("every counted frame reached a segment", output, assertContiguousAudio(cap, "straddle"))
        assertAudioCoversDuration(cap, "straddle")
        // The fixture's ragged ends are the head frames below the first
        // video presentation time and the tail video that outlives the
        // audio (one GOP here, 48 frames); nothing between them may go.
        assertTrue(
            "audio frames lost to the PES carry: input $input, output $output",
            output >= input - 52,
        )
    }

    @Test
    fun `a mux whose audio trails its video keeps every frame`() {
        // The measured provider shape: the audio arrives about 170 ms
        // behind the video it belongs with, so the cut keyframe of every
        // segment lands before that segment's last 8 audio frames parse.
        val (bytes, input) = straddlingTs(
            videoFrames = 360,
            videoFrameTicks = 3_000L,
            gop = 30,
            frameLen = 400,
            audioLagTicks = 15_300L, // 170 ms
        )
        val cap = remux(bytes)
        val output = cap.audioCounts.sum()
        println("[trailing] input=$input output=$output segments=${cap.segments.size} per=${cap.audioCounts}")
        assertTrue("at least 3 segments, got ${cap.segments.size}", cap.segments.size >= 3)
        assertEquals("every counted frame reached a segment", output, assertContiguousAudio(cap, "trailing"))
        assertAudioCoversDuration(cap, "trailing")
        assertTrue(
            "audio frames lost to a trailing audio mux: input $input, output $output",
            output >= input - 52,
        )
        // The point of the cut hold: no interior segment may come up short
        // by more than the one frame a boundary quantizes away.
        cap.durations.forEachIndexed { i, durationTicks ->
            if (i == 0 || i == cap.durations.size - 1) return@forEachIndexed
            val expected = durationTicks.toDouble() / frameTicks
            assertTrue(
                "segment $i carries ${cap.audioCounts[i]} audio frames, expected about " +
                    "${"%.1f".format(expected)}",
                cap.audioCounts[i] >= expected - 1.5 && cap.audioCounts[i] <= expected + 1.5,
            )
        }
    }
}
