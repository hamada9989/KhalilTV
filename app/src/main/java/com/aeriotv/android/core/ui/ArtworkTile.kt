package com.aeriotv.android.core.ui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

/**
 * TILE or FLOATING? The one question the rounding rule asks about a logo
 * (Logan 2026-09-16).
 *
 * A TILE is opaque artwork that reaches its own corners - the event logos, a
 * square network badge on a solid plate. Those round.
 *
 * A FLOATING logo is transparent around its edges - NBC Sports Now and most
 * tvg-logo PNGs. Rounding those does nothing but nick the glyph, so they stay
 * square.
 *
 * The earlier "does the fitted image fill its slot on both axes" test was the
 * wrong question and, on device, almost never fired: a fitted image only fills
 * both axes when its aspect happens to match the slot exactly.
 *
 * The verdict is read from the DECODED image: the alpha of a 3x3 patch at each
 * of the four corners. All four corners opaque (alpha above [OPAQUE]) means a
 * tile. An image with no alpha channel is a tile by definition.
 *
 * Sampled ONCE per image, on decode, and cached per image model, so scrolling
 * the list or the guide never re-samples. The cache is a snapshot state map, so
 * a verdict landing after the first frame repaints the surfaces showing it.
 */
object ArtworkTile {

    /** Alpha at or above this (of 255) counts as opaque. */
    private const val OPAQUE = 230

    /** Above this many pixels, sample a downscaled copy instead. */
    private const val MAX_SAMPLE_PIXELS = 256 * 256

    /** Edge length of the downscaled copy used for big artwork. */
    private const val SAMPLE_EDGE = 64

    private val verdicts = mutableStateMapOf<Any, Boolean>()

    /** The cached verdict, or null when this image has not been sampled yet. */
    fun verdict(model: Any?): Boolean? = model?.let { verdicts[it] }

    /**
     * Sample [image] once and cache the verdict under [model]. Safe to call on
     * every Coil success callback: a model already sampled returns its cached
     * verdict without touching pixels.
     */
    fun sample(model: Any?, image: ImageBitmap): Boolean =
        sample(model) { image.asAndroidBitmap() }

    /** [sample] for callers that already hold an Android bitmap. */
    fun sample(model: Any?, bitmap: Bitmap): Boolean = sample(model) { bitmap }

    private inline fun sample(model: Any?, source: () -> Bitmap): Boolean {
        if (model != null) verdicts[model]?.let { return it }
        val verdict = try {
            isTile(source())
        } catch (t: Throwable) {
            // Unreadable pixels (a hardware bitmap that will not copy, a
            // recycled bitmap): stay square rather than guess a round tile.
            false
        }
        if (model != null) verdicts[model] = verdict
        return verdict
    }

    /**
     * All four corners opaque = tile. No alpha channel = tile by definition.
     */
    fun isTile(source: Bitmap): Boolean {
        if (!source.hasAlpha()) return true
        val readable = readable(source) ?: return false
        val w = readable.width
        val h = readable.height
        if (w < 3 || h < 3) return false
        // A 3x3 patch AT each corner, so one stray transparent pixel (or an
        // anti-aliased edge) does not flip an otherwise solid tile, and a logo
        // with a small opaque mark near a corner does not pass as one.
        val corners = arrayOf(0 to 0, w - 3 to 0, 0 to h - 3, w - 3 to h - 3)
        for ((cx, cy) in corners) {
            for (dx in 0 until 3) {
                for (dy in 0 until 3) {
                    val alpha = (readable.getPixel(cx + dx, cy + dy) ushr 24) and 0xFF
                    if (alpha < OPAQUE) return false
                }
            }
        }
        return true
    }

    /**
     * A bitmap whose pixels can actually be read: hardware bitmaps are copied
     * to ARGB_8888, and very large artwork is sampled from a small copy (which
     * also averages away single-pixel noise at the corners).
     */
    private fun readable(source: Bitmap): Bitmap? {
        val software = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            source
        }
        if (software.width * software.height <= MAX_SAMPLE_PIXELS) return software
        val ratio = software.width.toFloat() / software.height
        val w = if (ratio >= 1f) SAMPLE_EDGE else (SAMPLE_EDGE * ratio).toInt().coerceAtLeast(3)
        val h = if (ratio >= 1f) (SAMPLE_EDGE / ratio).toInt().coerceAtLeast(3) else SAMPLE_EDGE
        return Bitmap.createScaledBitmap(software, w, h, true)
    }
}
