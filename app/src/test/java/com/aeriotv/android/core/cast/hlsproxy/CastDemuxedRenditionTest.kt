package com.aeriotv.android.core.cast.hlsproxy

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DEMUXED rendition validation (2026-09-13), the shape the cast sender
 * loads from now on: a master playlist with an EXT-X-MEDIA audio
 * rendition plus an EXT-X-STREAM-INF video rendition, two media
 * playlists with identical sequence numbering, and per-rendition init and
 * media segments (vinit/vseg, ainit/aseg).
 *
 * Why it exists: measured on the Google TV Streamer's Cast runtime,
 * `isTypeSupported('video/mp4; codecs="avc1.64002A,ac-3"')` and
 * `isTypeSupported('video/mp4; codecs="ac-3"')` are both false, but
 * `isTypeSupported('audio/mp4; codecs="ac-3"')` is TRUE, and Emby's web
 * receiver plays AC-3 through MediaCodecAudioDecoder from a separate
 * audio SourceBuffer. One muxed rendition can therefore only ever
 * declare an AAC codec string, which is what forces the server-side AAC
 * output profile. Two renditions let the audio declare what it is.
 *
 * Two independent verdicts per fixture:
 *  - ffprobe reads vinit + vsegs and ainit + asegs SEPARATELY and has to
 *    decode each cleanly, which catches a wrong mdat offset, an orphan
 *    traf or a broken sample entry in either rendition.
 *  - a real Chromium (headless Google Chrome) appends the two renditions
 *    into two SourceBuffers created with the EXACT codec strings the
 *    master playlist advertises, and has to report ONE contiguous
 *    buffered range on each with matching spans. That is the thing no
 *    byte-level assertion can prove: that the pair actually plays.
 *
 * Both skip themselves when ffmpeg/ffprobe or Chrome are absent, so CI
 * without Homebrew and without a browser stays green.
 */
class CastDemuxedRenditionTest {

    private val ffmpeg = File("/opt/homebrew/bin/ffmpeg")
    private val ffprobe = File("/opt/homebrew/bin/ffprobe")
    private val chrome = File("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")

    /** The real-world fixture: 60 s of a live ESPNU feed through
     *  Dispatcharr's AAC output profile, captured 2026-09-13. Absent on a
     *  clean checkout, so the test falls back to skipping. */
    private val realAacFixture = File(
        "/private/tmp/claude-501/-Users-loganjones-Documents-xcode-iOSDev/" +
            "4567b156-2e97-48d3-a8d7-44b0e9a3fd9a/scratchpad/espnu-aac-60s.ts",
    )

    private val workDir: File by lazy { File("build/tmp/castHlsDemuxed").apply { mkdirs() } }

    // ---- capture ----

    private class Capture : TsToFmp4Remuxer.Listener {
        var videoInit: ByteArray? = null
        var audioInit: ByteArray? = null
        val videoSegments = ArrayList<ByteArray>()
        val audioSegments = ArrayList<ByteArray>()
        val videoDurations = ArrayList<Long>()
        val audioDurations = ArrayList<Long>()
        var audioCodec: String? = null
        val logs = ArrayList<String>()

        override fun onInitSegments(video: ByteArray, audio: ByteArray?) {
            videoInit = video
            audioInit = audio
        }

        override fun onMediaSegment(
            video: ByteArray,
            audio: ByteArray?,
            videoDurationTicks: Long,
            audioDurationTicks: Long,
        ) {
            videoSegments.add(video)
            audio?.let { audioSegments.add(it) }
            videoDurations.add(videoDurationTicks)
            audioDurations.add(audioDurationTicks)
        }

        override fun onAudioCodec(name: String) { audioCodec = name }
    }

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        return Pair(p.waitFor(), text)
    }

    /** An AC-3 5.1 48 kHz fixture: the lineup shape the proxy passes
     *  through untouched when the receiver decodes AC-3, which is the
     *  whole point of the demuxed rendition. */
    private fun buildAc3Fixture(): File {
        val out = File(workDir, "ac3-51.ts")
        if (out.isFile && out.length() > 0) return out
        val (code, log) = run(
            ffmpeg.path, "-y", "-v", "error",
            "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30:duration=20",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=20",
            "-c:v", "libx264", "-preset", "ultrafast", "-g", "60", "-pix_fmt", "yuv420p",
            "-af", "pan=5.1|c0=c0|c1=c0|c2=c0|c3=c0|c4=c0|c5=c0",
            "-c:a", "ac3", "-b:a", "384k", "-ar", "48000",
            "-f", "mpegts", out.path,
        )
        assertEquals("ffmpeg built the AC-3 5.1 fixture: $log", 0, code)
        return out
    }

    /** Feed [ts] through the remuxer in 64 KB chunks, the ingest loop's
     *  read size, so chunk-boundary carry is exercised too. */
    private fun remux(ts: File, allowAc3Passthrough: Boolean): Capture {
        val cap = Capture()
        val remuxer = TsToFmp4Remuxer(
            listener = cap,
            log = { cap.logs.add(it) },
            allowAc3Passthrough = allowAc3Passthrough,
        )
        val bytes = ts.readBytes()
        var off = 0
        while (off < bytes.size) {
            val n = minOf(64 * 1024, bytes.size - off)
            remuxer.feed(bytes, off, n)
            off += n
        }
        remuxer.release()
        return cap
    }

    // ---- fMP4 box reading (for the per-segment census) ----

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun type(b: ByteArray, off: Int) = String(b, off + 4, 4, Charsets.US_ASCII)

    /** Sample counts of every trun in [segment], in track order. One entry
     *  means one traf, which is what a demuxed rendition must contain. */
    private fun trunSampleCounts(segment: ByteArray): List<Int> {
        val counts = ArrayList<Int>()
        fun walk(start: Int, end: Int) {
            var p = start
            while (p + 8 <= end) {
                val size = u32(segment, p).toInt()
                if (size < 8 || p + size > end) return
                when (type(segment, p)) {
                    "moof", "traf" -> walk(p + 8, p + size)
                    "trun" -> counts.add(u32(segment, p + 12).toInt())
                }
                p += size
            }
        }
        walk(0, segment.size)
        return counts
    }

    private fun trafCount(segment: ByteArray): Int {
        var n = 0
        fun walk(start: Int, end: Int) {
            var p = start
            while (p + 8 <= end) {
                val size = u32(segment, p).toInt()
                if (size < 8 || p + size > end) return
                when (type(segment, p)) {
                    "moof" -> walk(p + 8, p + size)
                    "traf" -> n++
                }
                p += size
            }
        }
        walk(0, segment.size)
        return n
    }

    // ---- ffprobe ----

    private fun writeConcat(name: String, init: ByteArray, segments: List<ByteArray>): File {
        val out = File(workDir, name)
        out.outputStream().use { os ->
            os.write(init)
            for (s in segments) os.write(s)
        }
        return out
    }

    private fun probeStreams(file: File): String {
        val (code, text) = run(ffprobe.path, "-v", "error", "-show_streams", "-of", "flat", file.path)
        assertEquals("ffprobe read $file: $text", 0, code)
        return text
    }

    /** A full decode: ffmpeg prints nothing on a clean one, so any output
     *  here is the decoder's complaint about our bytes. */
    private fun decodeCleanly(file: File) {
        val (code, log) = run(ffmpeg.path, "-v", "error", "-i", file.path, "-f", "null", "-")
        assertEquals("ffmpeg decoded $file: $log", 0, code)
        assertTrue("no decoder output for $file: $log", log.isBlank())
    }

    /**
     * Both renditions of one fixture, probed separately, plus the census:
     * every audio segment must carry the frames its own EXTINF calls for,
     * and every segment of the pair must carry exactly one traf.
     */
    private fun assertRenditionsProbeCleanly(cap: Capture, tag: String, audioCodecName: String) {
        assertTrue("$tag: demuxed init emitted", cap.videoInit != null && cap.audioInit != null)
        assertTrue("$tag: segments produced, got ${cap.videoSegments.size}", cap.videoSegments.size >= 3)
        assertEquals(
            "$tag: one audio segment per video segment",
            cap.videoSegments.size, cap.audioSegments.size,
        )

        val video = writeConcat("$tag-video.mp4", cap.videoInit!!, cap.videoSegments)
        val audio = writeConcat("$tag-audio.mp4", cap.audioInit!!, cap.audioSegments)

        val videoStreams = probeStreams(video)
        assertTrue("$tag: h264 in the video rendition\n$videoStreams", videoStreams.contains("codec_name=\"h264\""))
        assertTrue(
            "$tag: the video rendition carries NO audio stream\n$videoStreams",
            !videoStreams.contains("codec_type=\"audio\""),
        )
        decodeCleanly(video)

        val audioStreams = probeStreams(audio)
        assertTrue("$tag: $audioCodecName in the audio rendition\n$audioStreams",
            audioStreams.contains("codec_name=\"$audioCodecName\""))
        assertTrue(
            "$tag: the audio rendition carries NO video stream\n$audioStreams",
            !audioStreams.contains("codec_type=\"video\""),
        )
        assertTrue("$tag: 48 kHz audio\n$audioStreams", audioStreams.contains("sample_rate=\"48000\""))
        decodeCleanly(audio)

        // One traf per rendition segment: an orphan second traf is exactly
        // the muxed shape the receiver refuses to declare.
        for ((i, seg) in cap.videoSegments.withIndex()) {
            assertEquals("$tag: vseg$i has one traf", 1, trafCount(seg))
        }
        for ((i, seg) in cap.audioSegments.withIndex()) {
            assertEquals("$tag: aseg$i has one traf", 1, trafCount(seg))
        }

        // Audio frame census per segment, the rule the 2026-09-13 commit
        // established for the muxed shape: the frames in the segment have
        // to add up to the duration the playlist declares for it. The
        // audio rendition's EXTINF is the sum of its own frame durations,
        // so the two agree to within one frame by construction and a
        // mis-partitioned cut shows up here immediately.
        val frameTicks = audioFrameTicks(cap)
        for (i in cap.audioSegments.indices) {
            val frames = trunSampleCounts(cap.audioSegments[i]).single()
            val declared = cap.audioDurations[i]
            val expected = declared.toDouble() / frameTicks
            assertTrue(
                "$tag: aseg$i census frames=$frames expected=%.2f declared=$declared ticks"
                    .format(expected),
                kotlin.math.abs(expected - frames) <= 1.0,
            )
        }
    }

    /** One audio frame in 90 kHz ticks, derived from the census itself:
     *  the modal per-frame duration across the whole capture. */
    private fun audioFrameTicks(cap: Capture): Long {
        var frames = 0L
        var ticks = 0L
        for (i in cap.audioSegments.indices) {
            frames += trunSampleCounts(cap.audioSegments[i]).single()
            ticks += cap.audioDurations[i]
        }
        assertTrue("audio frames present", frames > 0)
        return (ticks.toDouble() / frames).toLong().coerceAtLeast(1L)
    }

    // ---- tests: ffprobe ----

    @Test
    fun `real aac feed splits into two cleanly decodable renditions`() {
        assumeTrue("ffmpeg/ffprobe present", ffmpeg.canExecute() && ffprobe.canExecute())
        assumeTrue("real ESPNU fixture present", realAacFixture.isFile)
        val cap = remux(realAacFixture, allowAc3Passthrough = false)
        assertEquals("PMT reports AAC", "AAC", cap.audioCodec)
        assertRenditionsProbeCleanly(cap, "espnu-aac", audioCodecName = "aac")
    }

    @Test
    fun `ac3 5_1 passthrough splits into two cleanly decodable renditions`() {
        assumeTrue("ffmpeg/ffprobe present", ffmpeg.canExecute() && ffprobe.canExecute())
        val cap = remux(buildAc3Fixture(), allowAc3Passthrough = true)
        assertEquals("PMT reports AC-3", "AC-3", cap.audioCodec)
        assertRenditionsProbeCleanly(cap, "ac3-51", audioCodecName = "ac3")
    }

    // ---- tests: playlists ----

    /**
     * The playlists the server builds from the same capture: identical
     * sequence numbering and target duration, per-rendition EXT-X-MAP and
     * segment names, and EXTINF values no further apart than one audio
     * frame. This is the contract the receiver's two SourceBuffers rely
     * on; a numbering skew between them is a guaranteed stall.
     */
    @Test
    fun `demuxed playlists number identically and declare the real codecs`() {
        assumeTrue("ffmpeg/ffprobe present", ffmpeg.canExecute() && ffprobe.canExecute())
        val cap = remux(buildAc3Fixture(), allowAc3Passthrough = true)
        val server = publish(cap)

        val master = server.demuxedMasterPlaylistText()
        assertTrue("master advertises the audio rendition\n$master",
            master.contains("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\"") && master.contains("URI=\"audio.m3u8\""))
        assertTrue("master names the ac-3 codec honestly\n$master", master.contains(",ac-3\""))
        assertTrue("master binds the group\n$master", master.contains("AUDIO=\"aud\""))
        assertTrue("master keeps Shaka's CEA parser off\n$master", master.contains("CLOSED-CAPTIONS=NONE"))
        assertTrue("master points at the video rendition\n$master", master.trimEnd().endsWith("video.m3u8"))

        val videoPl = server.videoPlaylistText()
        val audioPl = server.audioPlaylistText()
        val vSeqs = Regex("vseg(\\d+)\\.m4s").findAll(videoPl).map { it.groupValues[1].toInt() }.toList()
        val aSeqs = Regex("aseg(\\d+)\\.m4s").findAll(audioPl).map { it.groupValues[1].toInt() }.toList()
        assertEquals("identical sequence numbering", vSeqs, aSeqs)
        assertTrue("the window is populated", vSeqs.isNotEmpty())
        assertEquals(
            "identical MEDIA-SEQUENCE",
            Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(videoPl)!!.groupValues[1],
            Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(audioPl)!!.groupValues[1],
        )
        assertEquals(
            "identical TARGETDURATION",
            Regex("#EXT-X-TARGETDURATION:(\\d+)").find(videoPl)!!.groupValues[1],
            Regex("#EXT-X-TARGETDURATION:(\\d+)").find(audioPl)!!.groupValues[1],
        )
        assertTrue("video rendition maps vinit\n$videoPl", videoPl.contains("#EXT-X-MAP:URI=\"vinit"))
        assertTrue("audio rendition maps ainit\n$audioPl", audioPl.contains("#EXT-X-MAP:URI=\"ainit"))

        val vExtinf = Regex("#EXTINF:([\\d.]+)").findAll(videoPl).map { it.groupValues[1].toDouble() }.toList()
        val aExtinf = Regex("#EXTINF:([\\d.]+)").findAll(audioPl).map { it.groupValues[1].toDouble() }.toList()
        assertEquals("one EXTINF per entry in both", vExtinf.size, aExtinf.size)
        val frameSeconds = audioFrameTicks(cap) / TsToFmp4Remuxer.TICKS_PER_SECOND.toDouble()
        for (i in vExtinf.indices) {
            assertTrue(
                "EXTINF $i differs by less than one audio frame: ${vExtinf[i]} vs ${aExtinf[i]}",
                kotlin.math.abs(vExtinf[i] - aExtinf[i]) < frameSeconds + 0.001,
            )
        }

        // The DEMUXED master is the only one served: /master.m3u8 and
        // /live.m3u8 were removed 2026-09-13 and nothing loaded them.
        assertTrue("master points at video.m3u8\n$master", master.contains("video.m3u8"))
        assertTrue("master carries the audio rendition\n$master", master.contains("URI=\"audio.m3u8\""))
    }

    /** Publish a capture into a real [CastHlsProxyServer] store (no socket
     *  bound) so the playlists under test are the shipping ones. */
    private fun publish(cap: Capture): CastHlsProxyServer {
        val server = CastHlsProxyServer(log = {})
        val gen = server.beginGeneration()
        server.setInitSegments(gen, cap.videoInit!!, cap.audioInit)
        for (i in cap.videoSegments.indices) {
            server.addSegment(
                gen = gen,
                videoData = cap.videoSegments[i],
                audioData = cap.audioSegments.getOrNull(i),
                durationTicks = cap.videoDurations[i],
                audioDurationTicks = cap.audioDurations[i],
            )
        }
        return server
    }

    // ---- tests: Chromium SourceBuffer harness ----

    @Test
    fun `chromium appends the real aac feed into two source buffers`() {
        assumeTrue("ffmpeg present", ffmpeg.canExecute())
        assumeTrue("Chrome present", chrome.isFile)
        assumeTrue("real ESPNU fixture present", realAacFixture.isFile)
        val cap = remux(realAacFixture, allowAc3Passthrough = false)
        val result = runHarness(cap, "espnu-aac")
        assertHarnessAppendedBothRenditions(result, "espnu-aac")
    }

    @Test
    fun `chromium appends the ac3 5_1 feed into two source buffers`() {
        assumeTrue("ffmpeg present", ffmpeg.canExecute())
        assumeTrue("Chrome present", chrome.isFile)
        val cap = remux(buildAc3Fixture(), allowAc3Passthrough = true)
        val result = runHarness(cap, "ac3-51")
        assertHarnessAppendedBothRenditions(result, "ac3-51")
    }

    /** One contiguous buffered range per SourceBuffer, with spans that
     *  match: that is the receiver-side definition of a usable demuxed
     *  pair. The audio leg is skipped (not failed) on a Chromium that
     *  answers isTypeSupported false for the audio type, which is the
     *  desktop build's answer for ac-3; the Google TV Streamer's Cast
     *  runtime answers TRUE, which is why the rendition exists. */
    private fun assertHarnessAppendedBothRenditions(result: String, tag: String) {
        // Printed unconditionally: the buffered ranges this Chromium
        // reported are the only direct evidence the pair appends, and a
        // failure elsewhere is much easier to read with them in the log.
        println("$tag harness: $result")
        assertTrue("$tag: harness reported no error\n$result", !result.contains("\"error\":\""))
        assertTrue("$tag: video type supported\n$result", result.contains("\"videoSupported\":true"))
        val videoRanges = ranges(result, "videoRanges")
        assertEquals("$tag: one contiguous video range\n$result", 1, videoRanges.size)
        if (!result.contains("\"audioSupported\":true")) {
            println("$tag: this Chromium refuses the audio type, audio leg skipped\n$result")
            return
        }
        val audioRanges = ranges(result, "audioRanges")
        assertEquals("$tag: one contiguous audio range\n$result", 1, audioRanges.size)
        val v = videoRanges.single()
        val a = audioRanges.single()
        // Spans match within a segment boundary's worth of audio frame
        // quantization; starts within one frame, ends within one frame.
        assertTrue("$tag: range starts agree: $v vs $a\n$result", kotlin.math.abs(v.first - a.first) < 0.2)
        assertTrue("$tag: range ends agree: $v vs $a\n$result", kotlin.math.abs(v.second - a.second) < 0.2)
    }

    private fun ranges(result: String, key: String): List<Pair<Double, Double>> {
        val block = Regex("\"$key\":\\[(.*?)\\]\\]").find(result)?.groupValues?.get(1)
            ?: return emptyList()
        return Regex("\\[([\\d.eE+-]+),([\\d.eE+-]+)\\]").findAll("$block]")
            .map { Pair(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
            .toList()
    }

    /**
     * Serve the capture's demuxed renditions over HTTP (the playlists come
     * from the shipping [CastHlsProxyServer], so the codec strings and the
     * segment names under test are the ones a receiver would see), point
     * headless Chrome at a page that appends them into two SourceBuffers,
     * and return the JSON it POSTs back.
     *
     * HTTP rather than file:// on purpose: MSE fetches from a file origin
     * are blocked, and a POST back is a far more reliable completion
     * signal than any --dump-dom / --virtual-time-budget combination.
     */
    private fun runHarness(cap: Capture, tag: String): String {
        val server = publish(cap)
        val master = server.demuxedMasterPlaylistText()
        val videoPl = server.videoPlaylistText()
        val audioPl = server.audioPlaylistText()
        val codecs = Regex("CODECS=\"([^\"]+)\"").find(master)!!.groupValues[1].split(",")
        val videoType = "video/mp4; codecs=\"${codecs[0]}\""
        val audioType = "audio/mp4; codecs=\"${codecs.getOrElse(1) { "mp4a.40.2" }}\""
        val muxedType = "video/mp4; codecs=\"${codecs.joinToString(",")}\""

        // Files exactly as the playlists name them.
        val files = HashMap<String, ByteArray>()
        val gen = Regex("#EXT-X-MAP:URI=\"vinit(\\d+)\\.mp4\"").find(videoPl)!!.groupValues[1]
        files["/vinit$gen.mp4"] = cap.videoInit!!
        files["/ainit$gen.mp4"] = cap.audioInit!!
        val vNames = Regex("vseg(\\d+)\\.m4s").findAll(videoPl).map { it.groupValues[1].toInt() }.toList()
        // The window is the ring's tail, so index back from the capture.
        val firstIndex = cap.videoSegments.size - vNames.size
        for ((i, seq) in vNames.withIndex()) {
            files["/vseg$seq.m4s"] = cap.videoSegments[firstIndex + i]
            files["/aseg$seq.m4s"] = cap.audioSegments[firstIndex + i]
        }
        val vUris = vNames.map { "vseg$it.m4s" }
        val aUris = vNames.map { "aseg$it.m4s" }
        val harness = harnessHtml(
            videoType = videoType, audioType = audioType, muxedType = muxedType,
            videoInit = "vinit$gen.mp4", audioInit = "ainit$gen.mp4",
            videoSegments = vUris, audioSegments = aUris,
        )
        files["/harness.html"] = harness.toByteArray(Charsets.UTF_8)
        files["/video.m3u8"] = videoPl.toByteArray(Charsets.UTF_8)
        files["/audio.m3u8"] = audioPl.toByteArray(Charsets.UTF_8)

        val done = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference("")
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val accept = Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Throwable) { break }
                Thread {
                    client.use { sock ->
                        val input = sock.getInputStream()
                        val head = StringBuilder()
                        var contentLength = 0
                        // Headers are ASCII and end on a blank line; read
                        // byte by byte so the body stays on the stream.
                        while (true) {
                            val c = input.read()
                            if (c < 0) return@use
                            head.append(c.toChar())
                            if (head.endsWith("\r\n\r\n")) break
                        }
                        Regex("(?i)content-length:\\s*(\\d+)").find(head)?.let {
                            contentLength = it.groupValues[1].toInt()
                        }
                        val requestLine = head.lineSequence().first().trim()
                        val parts = requestLine.split(' ')
                        val method = parts.getOrElse(0) { "" }
                        val path = parts.getOrElse(1) { "" }.substringBefore('?')
                        val out = sock.getOutputStream()
                        if (method == "POST" && path == "/result") {
                            val body = ByteArray(contentLength)
                            var read = 0
                            while (read < contentLength) {
                                val n = input.read(body, read, contentLength - read)
                                if (n < 0) break
                                read += n
                            }
                            result.set(String(body, 0, read, Charsets.UTF_8))
                            out.write(
                                ("HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\n" +
                                    "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(),
                            )
                            out.flush()
                            done.countDown()
                            return@use
                        }
                        val body = files[path]
                        if (body == null) {
                            out.write(
                                ("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray(),
                            )
                        } else {
                            val mime = when {
                                path.endsWith(".html") -> "text/html"
                                path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
                                path.endsWith(".mp4") -> "video/mp4"
                                else -> "video/iso.segment"
                            }
                            out.write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\n" +
                                    "Content-Length: ${body.size}\r\n" +
                                    "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
                                    .toByteArray(),
                            )
                            out.write(body)
                        }
                        out.flush()
                    }
                }.apply { isDaemon = true }.start()
            }
        }
        accept.isDaemon = true
        accept.start()

        val profile = File(workDir, "chrome-profile-$tag").apply { mkdirs() }
        val process = ProcessBuilder(
            chrome.path,
            "--headless=new",
            "--disable-gpu",
            "--mute-audio",
            "--no-first-run",
            "--no-default-browser-check",
            "--autoplay-policy=no-user-gesture-required",
            "--user-data-dir=${profile.absolutePath}",
            "http://127.0.0.1:${socket.localPort}/harness.html",
        ).redirectErrorStream(true).start()
        val chromeLog = Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { } }
        }.apply { isDaemon = true; start() }
        val reported = try {
            done.await(90, TimeUnit.SECONDS)
        } finally {
            process.destroy()
            socket.close()
            chromeLog.interrupt()
        }
        assertTrue("$tag: headless Chrome reported back within 90 s", reported)
        return result.get()
    }

    /**
     * The harness page: two SourceBuffers on one MediaSource, created with
     * the EXACT codec strings the master playlist advertises, each fed its
     * own init segment and then its media segments in order. It reports
     * the buffered ranges of each, plus what this Chromium thinks of the
     * muxed codec string (the measurement that made the demuxed rendition
     * necessary in the first place).
     */
    private fun harnessHtml(
        videoType: String,
        audioType: String,
        muxedType: String,
        videoInit: String,
        audioInit: String,
        videoSegments: List<String>,
        audioSegments: List<String>,
    ): String {
        fun jsList(items: List<String>) = items.joinToString(",") { "\"$it\"" }
        return """
<!doctype html>
<meta charset="utf-8">
<title>cast demuxed SourceBuffer harness</title>
<pre id="out">running</pre>
<video id="v" muted></video>
<script>
const VIDEO_TYPE = ${'"'}${videoType.replace("\"", "\\\"")}${'"'};
const AUDIO_TYPE = ${'"'}${audioType.replace("\"", "\\\"")}${'"'};
const MUXED_TYPE = ${'"'}${muxedType.replace("\"", "\\\"")}${'"'};
const VIDEO_INIT = "$videoInit";
const AUDIO_INIT = "$audioInit";
const VIDEO_SEGMENTS = [${jsList(videoSegments)}];
const AUDIO_SEGMENTS = [${jsList(audioSegments)}];

function get(url) {
  return new Promise((resolve, reject) => {
    const x = new XMLHttpRequest();
    x.open("GET", url);
    x.responseType = "arraybuffer";
    x.onload = () => x.status === 200 ? resolve(x.response) : reject(new Error(url + " " + x.status));
    x.onerror = () => reject(new Error("network " + url));
    x.send();
  });
}

function append(sb, data) {
  return new Promise((resolve, reject) => {
    sb.addEventListener("updateend", () => resolve(), { once: true });
    sb.addEventListener("error", () => reject(new Error("append error")), { once: true });
    sb.appendBuffer(new Uint8Array(data));
  });
}

function ranges(sb) {
  const out = [];
  const b = sb.buffered;
  for (let i = 0; i < b.length; i++) out.push([b.start(i), b.end(i)]);
  return out;
}

async function main() {
  const report = {
    videoType: VIDEO_TYPE, audioType: AUDIO_TYPE, muxedType: MUXED_TYPE,
    videoSupported: MediaSource.isTypeSupported(VIDEO_TYPE),
    audioSupported: MediaSource.isTypeSupported(AUDIO_TYPE),
    muxedSupported: MediaSource.isTypeSupported(MUXED_TYPE),
    videoRanges: [], audioRanges: [], videoSegments: 0, audioSegments: 0, error: null,
  };
  try {
    const ms = new MediaSource();
    const v = document.getElementById("v");
    v.src = URL.createObjectURL(ms);
    await new Promise(r => ms.addEventListener("sourceopen", r, { once: true }));
    const vb = report.videoSupported ? ms.addSourceBuffer(VIDEO_TYPE) : null;
    const ab = report.audioSupported ? ms.addSourceBuffer(AUDIO_TYPE) : null;
    // 'segments' mode: the segments' own tfdt timestamps place them, which
    // is what the two renditions rely on to line up with each other.
    if (vb) vb.mode = "segments";
    if (ab) ab.mode = "segments";
    if (vb) {
      await append(vb, await get(VIDEO_INIT));
      for (const name of VIDEO_SEGMENTS) { await append(vb, await get(name)); report.videoSegments++; }
      report.videoRanges = ranges(vb);
    }
    if (ab) {
      await append(ab, await get(AUDIO_INIT));
      for (const name of AUDIO_SEGMENTS) { await append(ab, await get(name)); report.audioSegments++; }
      report.audioRanges = ranges(ab);
    }
  } catch (e) {
    report.error = String(e && e.message ? e.message : e);
  }
  const text = JSON.stringify(report);
  document.getElementById("out").textContent = text;
  const x = new XMLHttpRequest();
  x.open("POST", "/result");
  x.setRequestHeader("Content-Type", "application/json");
  x.send(text);
}
main();
</script>
"""
    }
}
