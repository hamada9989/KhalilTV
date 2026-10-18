package com.aeriotv.android.ui.scale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified

/**
 * Subtext Size (Appearance > Subtext Size, [AppPreferences.subtextScale]).
 *
 * A second multiplier that applies ONLY to secondary copy: row descriptions,
 * footers, program subtitles / descriptions, metadata lines, plots, captions,
 * timestamps, empty-state explanations. It stacks on top of the app Text Size
 * (which rides LocalDensity.fontScale), because it multiplies the sp values
 * themselves.
 *
 * MainActivity provides the value through [LocalSubtextScale]. A plain
 * CompositionLocal crosses Dialog / Popup / ModalBottomSheet window
 * boundaries on its own, so no window shim is needed for this one.
 *
 * Call sites opt in: `style = MaterialTheme.typography.bodySmall.subtext()`,
 * `fontSize = 12.sp.subtext()`. Primary text (titles, names, buttons, tabs,
 * pills, headers) never calls these.
 */
val LocalSubtextScale = compositionLocalOf { 1f }

/** [TextStyle] with fontSize and lineHeight multiplied by the Subtext Size. */
@Composable
@ReadOnlyComposable
fun TextStyle.subtext(): TextStyle = subtext(LocalSubtextScale.current)

/** Non-composable form for draw / measure code that already read the scale. */
fun TextStyle.subtext(scale: Float): TextStyle =
    if (scale == 1f) this
    else copy(
        fontSize = if (fontSize.isSpecified) fontSize * scale else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
    )

/** sp value multiplied by the Subtext Size (Unspecified stays Unspecified). */
@Composable
@ReadOnlyComposable
fun TextUnit.subtext(): TextUnit {
    val scale = LocalSubtextScale.current
    return if (scale == 1f || !isSpecified) this else this * scale
}
