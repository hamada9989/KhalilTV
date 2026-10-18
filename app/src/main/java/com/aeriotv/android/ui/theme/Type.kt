package com.aeriotv.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified

/**
 * Material 3 type scale. Phone / tablet / foldable use the stock Material
 * defaults. Android TV keeps the SAME default scale (factor 1.0) - and this is
 * deliberate, derived from measured canvas geometry rather than copied from the
 * tvOS point sizes.
 *
 * THE CANVAS MATH (why TV must NOT scale type up):
 *  - tvOS lays out on a 1920x1080 POINT canvas. Its bodyMedium is 24pt, i.e.
 *    24/1080 = 2.2% of screen height.
 *  - Android TV (Google TV Streamer + the typical Android-TV convention)
 *    presents apps a 1920x1080px framebuffer at 320dpi = density 2.0, so the
 *    Compose layout canvas is 960x540 dp - exactly HALF the linear size of the
 *    tvOS point canvas.
 *  - On a 540dp-tall canvas, Material's DEFAULT bodyMedium (14sp) is already
 *    14/540 = 2.6% of height - i.e. already a touch LARGER than tvOS's 24pt.
 *  - A 14sp glyph at density 2.0 renders to 28px on the 1920x1080 framebuffer;
 *    tvOS's 24pt renders to 24px on its 1920x1080 framebuffer filling the same
 *    panel. So Material default is ~17% bigger than tvOS on-screen, NOT smaller.
 *
 * An earlier 1.5x multiplier here made bodyMedium 21sp = 3.9% of height (~1.75x
 * tvOS) - the "TERRIBLY scaled / 350x zoom" regression. Material's own default
 * (factor 1.0) is already proportionally on par with (slightly larger than)
 * tvOS once the half-size dp canvas is accounted for; the measured tvOS-EXACT
 * ratio is ~0.85 (Material bodyMedium 14sp * 0.857 = 12sp = tvOS 24pt/1080). We
 * sit at 0.9 - a deliberate small step DOWN from Material default toward tvOS so
 * 10-ft text is a touch tighter (Archie: "decrease the font size a bit
 * everywhere"), without going all the way to the 0.85 tvOS floor. Per-surface
 * sizing (guide rows / posters) is handled with explicit tvOS-proportion dp
 * values at each call site, not via this multiplier.
 */
private const val TV_TYPE_SCALE = 0.9f

private val PhoneTypography = Typography()

private fun TextStyle.scaledForTv(factor: Float): TextStyle = copy(
    fontSize = if (fontSize.isSpecified) fontSize * factor else fontSize,
    lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight,
)

private val TvTypography = with(PhoneTypography) {
    Typography(
        displayLarge = displayLarge.scaledForTv(TV_TYPE_SCALE),
        displayMedium = displayMedium.scaledForTv(TV_TYPE_SCALE),
        displaySmall = displaySmall.scaledForTv(TV_TYPE_SCALE),
        headlineLarge = headlineLarge.scaledForTv(TV_TYPE_SCALE),
        headlineMedium = headlineMedium.scaledForTv(TV_TYPE_SCALE),
        headlineSmall = headlineSmall.scaledForTv(TV_TYPE_SCALE),
        titleLarge = titleLarge.scaledForTv(TV_TYPE_SCALE),
        titleMedium = titleMedium.scaledForTv(TV_TYPE_SCALE),
        titleSmall = titleSmall.scaledForTv(TV_TYPE_SCALE),
        bodyLarge = bodyLarge.scaledForTv(TV_TYPE_SCALE),
        bodyMedium = bodyMedium.scaledForTv(TV_TYPE_SCALE),
        bodySmall = bodySmall.scaledForTv(TV_TYPE_SCALE),
        labelLarge = labelLarge.scaledForTv(TV_TYPE_SCALE),
        labelMedium = labelMedium.scaledForTv(TV_TYPE_SCALE),
        labelSmall = labelSmall.scaledForTv(TV_TYPE_SCALE),
    )
}

/** Material type scale for the active form factor (TV gets the scaled table). */
fun aerioTypography(isTv: Boolean): Typography = if (isTv) TvTypography else PhoneTypography

/** Back-compat alias for existing references; equals the phone (default) scale. */
val Typography: Typography = PhoneTypography
