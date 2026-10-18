// SettingsTextField.kt
//
// Settings redesign Phase 3, item 2: ONE text field style for every field in
// Settings.
//
// Before this, each screen assembled its own OutlinedTextField: some put the
// caption in `label`, some in `placeholder`, some added a separate helper Text
// below, some wired the TV reveal eye and the TV IME modifiers and some forgot
// to. The shape was decided per call site, which is exactly how the Edit
// Playlist form and the TMDB key row ended up looking like two different apps.
//
// The contract here, matching Apple's Settings field:
//   - LABEL above the box (never floating inside it, so the value is never
//     pushed around as focus moves)
//   - the VALUE inside the box
//   - optional HELPER text below, in the footnote style
//   - a SUBTLE focus treatment: the theme accent outline at Material's own
//     focused width. Never white, never a thick ring (Logan's standing rule).
//   - secure fields get the reveal eye that works with a remote (key-UP
//     toggle; see SecretRevealIconButton) and the TV horizontal focus escape
//     that lets the D-pad actually reach it.

package com.aeriotv.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aeriotv.android.ui.scale.subtext
import com.aeriotv.android.ui.textfield.SecretRevealIconButton
import com.aeriotv.android.ui.textfield.aerioTextFieldKeyboardOptions
import com.aeriotv.android.ui.textfield.rememberSecretRevealState
import com.aeriotv.android.ui.tv.tvFormFieldInput

/**
 * The single Settings text field. See the file header for the contract.
 *
 * [secure] masks the value and adds the reveal eye; pass [secureLabel] so the
 * accessibility strings read "Show password" / "Show API key" rather than the
 * generic default.
 */
@Composable
fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    helper: String? = null,
    secure: Boolean = false,
    secureLabel: String = "value",
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = aerioTextFieldKeyboardOptions(),
) {
    // Owned here rather than by the caller: every secure field in Settings
    // wants exactly this behavior, and the reveal state must never outlive the
    // screen (see SecretRevealState's own note on why it is a plain remember).
    val reveal = rememberSecretRevealState()
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = settingsFootnoteStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = enabled,
            textStyle = LocalTextStyle.current.merge(settingsRowTitleStyle()),
            visualTransformation = if (secure) {
                reveal.transformation
            } else {
                VisualTransformation.None
            },
            placeholder = placeholder?.let {
                {
                    Text(
                        text = it,
                        style = settingsRowTitleStyle().subtext(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingIcon = if (secure) {
                { SecretRevealIconButton(state = reveal, contentLabel = secureLabel) }
            } else {
                null
            },
            keyboardOptions = keyboardOptions,
            colors = settingsTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .tvFormFieldInput(
                    horizontalFocusEscape = secure,
                    okSuppressed = { secure && reveal.controlFocused },
                ),
        )
        if (helper != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = helper,
                style = settingsFootnoteStyle().subtext(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp),
            )
        }
    }
}

/**
 * The field's own chrome. Focused is the THEME ACCENT at Material's standard
 * focused outline width; unfocused is the same faint accent hairline
 * [settingsRowCard] paints at rest, so a form of fields and a page of rows
 * read as one surface. Nothing here is ever white or oversized.
 */
@Composable
fun settingsTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
    disabledBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
    focusedTextColor = MaterialTheme.colorScheme.onBackground,
    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
    cursorColor = MaterialTheme.colorScheme.primary,
    // Phase 3b: the field is an INSET well inside the lighter grouped card,
    // so it takes the page background rather than the card fill.
    focusedContainerColor = MaterialTheme.colorScheme.background,
    unfocusedContainerColor = MaterialTheme.colorScheme.background,
    disabledContainerColor = MaterialTheme.colorScheme.background,
)
