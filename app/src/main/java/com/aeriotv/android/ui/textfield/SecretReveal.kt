package com.aeriotv.android.ui.textfield

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aeriotv.android.ui.settings.dpadFocusRing

/**
 * Reveal state for one masked field (password, API key, token).
 *
 * [visible] is deliberately a plain `remember`, NOT `rememberSaveable`: a
 * revealed secret must never survive leaving the screen, a process death or a
 * configuration change. Every field starts masked.
 *
 * [controlFocused] exists because of the TV OK-key conflict described in
 * [com.aeriotv.android.ui.tv.tvFormFieldInput]. The eye lives in the text
 * field's own trailing slot, so it is a DESCENDANT focus target of the field.
 * Compose's `onFocusEvent` aggregates descendant state
 * (FocusEventModifierNode.getFocusState returns the focused child's state), so
 * the field's own focus observer reports isFocused = true while the eye holds
 * focus. The field's onPreviewKeyEvent then swallowed D-pad Center and popped
 * the keyboard instead of letting the button click, which is exactly why the
 * eye never revealed anything on the Google TV Streamer. The eye reports its
 * own focus here, and the field passes it back as `okSuppressed` so OK is left
 * alone while the eye is focused.
 */
class SecretRevealState {
    var visible by mutableStateOf(false)
        internal set
    var controlFocused by mutableStateOf(false)
        internal set

    val transformation: VisualTransformation
        get() = if (visible) VisualTransformation.None else PasswordVisualTransformation()

    fun toggle() {
        visible = !visible
    }
}

@Composable
fun rememberSecretRevealState(): SecretRevealState = remember { SecretRevealState() }

/**
 * The eye button for a masked field's `trailingIcon` slot. Pair with
 * `Modifier.tvFormFieldInput(horizontalFocusEscape = true, okSuppressed = { state.controlFocused })`
 * on the field so a remote can reach it (Left/Right) and press it (OK).
 */
@Composable
fun SecretRevealIconButton(
    state: SecretRevealState,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = 20.dp,
    contentLabel: String = "key",
) {
    IconButton(
        onClick = { state.toggle() },
        modifier = Modifier
            .onFocusChanged { state.controlFocused = it.isFocused }
            .onPreviewKeyEvent { ev ->
                // Measured on a Google TV Streamer (2026-09-15): when the
                // leanback IME was open for this field and focus then moved to
                // the eye, the next OK arrived as a KeyUp ONLY - the IME
                // consumed the KeyDown to dismiss itself. Compose's clickable
                // needs the DOWN/UP pair, so that press did nothing and the
                // key looked unrevealable. Own the OK key here: consume both
                // edges and toggle on the release, so a lone release still
                // works. Consuming DOWN also keeps clickable from firing a
                // second toggle.
                val isOk = ev.key == Key.DirectionCenter || ev.key == Key.Enter ||
                    ev.key == Key.NumPadEnter
                when {
                    isOk && ev.type == KeyEventType.KeyDown -> true
                    isOk && ev.type == KeyEventType.KeyUp -> {
                        state.toggle()
                        true
                    }
                    else -> false
                }
            }
            .dpadFocusRing(CircleShape),
    ) {
        Icon(
            imageVector = if (state.visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = if (state.visible) "Hide $contentLabel" else "Show $contentLabel",
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}
