package com.aeriotv.android.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Settings > App Behaviors > Skip Intervals (Logan 2026-09-14). One
 * process-wide pair (seeded from
 * [com.aeriotv.android.core.preferences.AppPreferences.skipBackSeconds] /
 * [com.aeriotv.android.core.preferences.AppPreferences.skipForwardSeconds] by
 * the Application) so every skip control agrees: live rewind and catch-up
 * buttons, VOD and DVR buttons, a single remote LEFT/RIGHT tap (the first
 * scrub step), the Cast / companion remote sheet, and the media session
 * seek commands. Holding LEFT/RIGHT keeps the accelerating [HOLD_STEP_MS]
 * scrub. Multiview is not covered.
 *
 * Apple twin: `SkipIntervals` in Shared/SkipIntervals.swift.
 */
object SkipIntervals {
    /** The choices offered in Settings, in seconds. */
    val CHOICES = listOf(5, 10, 15, 30, 60)
    const val DEFAULT_BACK_SECONDS = 10
    const val DEFAULT_FORWARD_SECONDS = 30

    /** Base step of a HELD LEFT/RIGHT scrub; unchanged by the setting. */
    const val HOLD_STEP_MS = 10_000L

    val backSeconds = MutableStateFlow(DEFAULT_BACK_SECONDS)
    val forwardSeconds = MutableStateFlow(DEFAULT_FORWARD_SECONDS)

    val backMs: Long get() = backSeconds.value * 1_000L
    val forwardMs: Long get() = forwardSeconds.value * 1_000L

    /** A stored or synced value outside [CHOICES] reads as [fallback]. */
    fun sanitize(seconds: Int?, fallback: Int): Int =
        if (seconds != null && seconds in CHOICES) seconds else fallback

    /**
     * Signed scrub delta for one D-pad step. A fresh press (not a key
     * repeat) moves by the Skip Intervals setting; key repeats keep the
     * accelerating 10 s x [mult] hold scrub.
     */
    fun scrubDeltaMs(dir: Int, isRepeat: Boolean, mult: Int): Long = when {
        isRepeat -> dir * HOLD_STEP_MS * mult
        dir < 0 -> -backMs
        else -> forwardMs
    }

    fun backLabel(seconds: Int = backSeconds.value): String = "Back $seconds seconds"
    fun forwardLabel(seconds: Int = forwardSeconds.value): String = "Forward $seconds seconds"

    /** Material's numbered replay glyph where one exists, else a drawn one. */
    fun backIcon(seconds: Int = backSeconds.value): ImageVector = when (seconds) {
        5 -> Icons.Filled.Replay5
        10 -> Icons.Filled.Replay10
        30 -> Icons.Filled.Replay30
        else -> numbered(back = true, seconds = seconds)
    }

    fun forwardIcon(seconds: Int = forwardSeconds.value): ImageVector = when (seconds) {
        5 -> Icons.Filled.Forward5
        10 -> Icons.Filled.Forward10
        30 -> Icons.Filled.Forward30
        else -> numbered(back = false, seconds = seconds)
    }

    private val numberedCache = HashMap<Pair<Boolean, Int>, ImageVector>()

    /**
     * Material has no Replay15 / Replay60 (or Forward) glyphs. Draw the same
     * circular arrow the numbered icons use with the digits stroked inside it,
     * on the same 24 x 24 grid, so it sizes and tints like its siblings.
     */
    private fun numbered(back: Boolean, seconds: Int): ImageVector = synchronized(numberedCache) {
        numberedCache.getOrPut(back to seconds) {
            val arrow = if (back) {
                "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4" +
                    "c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z"
            } else {
                "M18,13c0,3.31 -2.69,6 -6,6s-6,-2.69 -6,-6s2.69,-6 6,-6v4l5,-5l-5,-5v4" +
                    "c-4.42,0 -8,3.58 -8,8c0,4.42 3.58,8 8,8s8,-3.58 8,-8H18z"
            }
            val digits = seconds.toString()
            val cellW = 2.2f
            val cellH = 4.2f
            val gap = 1.2f
            val totalW = digits.length * cellW + (digits.length - 1) * gap
            val top = 13f - cellH / 2
            val path = StringBuilder()
            digits.forEachIndexed { i, ch ->
                val left = 12f - totalW / 2 + i * (cellW + gap)
                path.append(digitPath(ch, left, top, cellW, cellH))
            }
            ImageVector.Builder(
                name = if (back) "Replay$seconds" else "Forward$seconds",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            )
                .addPath(pathData = addPathNodes(arrow), fill = SolidColor(Color.Black))
                .addPath(
                    pathData = addPathNodes(path.toString()),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 0.9f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                .build()
        }
    }

    /** Segment-style digit strokes inside the cell at ([x], [y]). */
    private fun digitPath(ch: Char, x: Float, y: Float, w: Float, h: Float): String {
        val r = x + w
        val m = y + h / 2
        val b = y + h
        return when (ch) {
            '0' -> "M$x,${y}L$r,${y}L$r,${b}L$x,${b}Z"
            '1' -> {
                val c = x + w * 0.6f
                "M${c - 1f},${y + 1f}L$c,${y}L$c,$b"
            }
            '2' -> "M$x,${y}L$r,${y}L$r,${m}L$x,${m}L$x,${b}L$r,$b"
            '3' -> "M$x,${y}L$r,${y}L$r,${b}L$x,${b}M$x,${m}L$r,$m"
            '4' -> "M$x,${y}L$x,${m}L$r,${m}M$r,${y}L$r,$b"
            '5' -> "M$r,${y}L$x,${y}L$x,${m}L$r,${m}L$r,${b}L$x,$b"
            '6' -> "M$r,${y}L$x,${y}L$x,${b}L$r,${b}L$r,${m}L$x,$m"
            '7' -> "M$x,${y}L$r,${y}L$r,$b"
            '8' -> "M$x,${y}L$r,${y}L$r,${b}L$x,${b}ZM$x,${m}L$r,$m"
            else -> "M$r,${m}L$x,${m}L$x,${y}L$r,${y}L$r,${b}L$x,$b"
        }
    }
}

/** The live back interval, so composables re-render when the setting changes. */
@Composable
fun rememberSkipBackSeconds(): Int = SkipIntervals.backSeconds.collectAsState().value

/** The live forward interval, so composables re-render when the setting changes. */
@Composable
fun rememberSkipForwardSeconds(): Int = SkipIntervals.forwardSeconds.collectAsState().value
