package com.aeriotv.android.ui.scale

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties

/**
 * App-wide Text Size (Appearance > Text Size, [AppPreferences.textScale]).
 *
 * MainActivity provides the user's multiplier through [LocalAppTextScale] and
 * wraps the whole tree in [ProvideAppTextScale], which multiplies
 * [LocalDensity]'s fontScale (same technique as [WithDisplayScale], so the
 * Display Scale sliders still stack on top for their surfaces).
 *
 * WHY THE WINDOW SHIMS BELOW: every Dialog / Popup / ModalBottomSheet window
 * owns its own AndroidComposeView, and that view RE-PROVIDES LocalDensity
 * from its own Configuration (ProvideCommonCompositionLocals). A root
 * LocalDensity override therefore stops at the window boundary. Plain
 * CompositionLocals (like [LocalAppTextScale]) DO cross it, so each shim
 * re-applies the scale inside its window. Call sites import these instead of
 * the androidx originals; signatures mirror Material3 1.4.0 / compose-ui.
 */
val LocalAppTextScale = compositionLocalOf { 1f }

/** Density produced by the nearest [ProvideAppTextScale]; guards double scaling. */
private val LocalAppTextScaledDensity = compositionLocalOf<Density?> { null }

@Composable
fun ProvideAppTextScale(content: @Composable () -> Unit) {
    val outer = LocalDensity.current
    val scale = LocalAppTextScale.current
    // Nested provider inside the same window: outer is already our scaled
    // density, so pass it through instead of multiplying twice. The provider
    // is ALWAYS emitted (even at 100%) so the group structure never changes
    // while the slider moves, which would remount the whole tree.
    val alreadyScaled = LocalAppTextScaledDensity.current === outer
    val scaled = remember(outer, scale, alreadyScaled) {
        if (alreadyScaled || scale == 1f) outer
        else Density(density = outer.density, fontScale = outer.fontScale * scale)
    }
    CompositionLocalProvider(
        LocalDensity provides scaled,
        LocalAppTextScaledDensity provides scaled,
    ) {
        content()
    }
}

/** [androidx.compose.ui.window.Dialog] that keeps the app Text Size. */
@Composable
fun Dialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) = androidx.compose.ui.window.Dialog(onDismissRequest, properties) {
    ProvideAppTextScale(content)
}

/** Slot [androidx.compose.material3.AlertDialog] that keeps the app Text Size. */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) = androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismissRequest,
    confirmButton = { ProvideAppTextScale(confirmButton) },
    modifier = modifier,
    dismissButton = dismissButton?.let { slot -> { ProvideAppTextScale(slot) } },
    icon = icon?.let { slot -> { ProvideAppTextScale(slot) } },
    title = title?.let { slot -> { ProvideAppTextScale(slot) } },
    text = text?.let { slot -> { ProvideAppTextScale(slot) } },
    shape = shape,
    containerColor = containerColor,
    iconContentColor = iconContentColor,
    titleContentColor = titleContentColor,
    textContentColor = textContentColor,
    tonalElevation = tonalElevation,
    properties = properties,
)

/** [androidx.compose.material3.ModalBottomSheet] that keeps the app Text Size. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    sheetState = sheetState,
    sheetMaxWidth = sheetMaxWidth,
    sheetGesturesEnabled = sheetGesturesEnabled,
    shape = shape,
    containerColor = containerColor,
    contentColor = contentColor,
    tonalElevation = tonalElevation,
    scrimColor = scrimColor,
    dragHandle = dragHandle?.let { slot -> { ProvideAppTextScale(slot) } },
    contentWindowInsets = contentWindowInsets,
    properties = properties,
) {
    val scope = this
    ProvideAppTextScale { scope.content() }
}

/** [androidx.compose.material3.DropdownMenu] that keeps the app Text Size. */
@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.DropdownMenu(
    expanded = expanded,
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    offset = offset,
    scrollState = scrollState,
    properties = properties,
    shape = shape,
    containerColor = containerColor,
    tonalElevation = tonalElevation,
    shadowElevation = shadowElevation,
    border = border,
) {
    val scope = this
    ProvideAppTextScale { scope.content() }
}
