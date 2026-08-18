package com.mateof.kanal.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.mateof.kanal.R
import com.mateof.kanal.ui.theme.KanalColors

/**
 * Holds a field's two states: selected and quiet, or being typed into.
 *
 * A Compose text field summons the on-screen keyboard the moment it takes
 * focus. On a television that is backwards. The D-pad has to walk *through* the
 * fields to reach anything below them, and every one it passed threw a keyboard
 * over the form; on Fire OS that keyboard covers the screen completely, so
 * filling in a source meant typing into fields you could no longer see and
 * moving between them blind.
 *
 * So the field stays read-only, and therefore silent, until OK is pressed on
 * it. Anything that ends the typing — the keyboard's own done key, BACK, the
 * system dismissing it, or focus moving away — puts it back to sleep with the
 * focus still on the field, so the form is there to look at again.
 */
private class Typing(
    val editing: Boolean,
    val begin: () -> Unit,
    val end: () -> Unit,
    val onFocusChanged: (Boolean) -> Unit
)

@Composable
private fun rememberTyping(): Typing {
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }

    // Asked for after the recomposition that lifts read-only, never before: a
    // field that is still read-only gets no keyboard on some devices.
    LaunchedEffect(editing) { if (editing) keyboard?.show() }

    // The keyboard can also be dismissed by the system back gesture, which the
    // app never sees. Following its visibility keeps the two in step.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { if (!imeVisible) editing = false }

    return Typing(
        editing = editing,
        begin = { editing = true },
        end = {
            editing = false
            keyboard?.hide()
        },
        onFocusChanged = { focused ->
            if (!focused) {
                editing = false
                keyboard?.hide()
            }
        }
    )
}

/**
 * OK starts the typing; BACK ends it. The arrows only move focus while the
 * field is quiet — once the keyboard is up they belong to it.
 */
private fun Modifier.typingKeys(
    typing: Typing,
    moveFocus: (FocusDirection) -> Unit
): Modifier = onPreviewKeyEvent { event ->
    val centre = event.key == Key.DirectionCenter || event.key == Key.Enter
    val up = event.type == KeyEventType.KeyUp
    val down = event.type == KeyEventType.KeyDown
    when {
        !typing.editing && centre -> {
            // On the key going up, so the press that opened the keyboard is not
            // also delivered to whatever the keyboard puts under the cursor.
            if (up) typing.begin()
            true
        }

        typing.editing && event.key == Key.Back -> {
            if (up) typing.end()
            true
        }

        !typing.editing && down && event.key == Key.DirectionUp -> {
            moveFocus(FocusDirection.Up); true
        }

        !typing.editing && down && event.key == Key.DirectionDown -> {
            moveFocus(FocusDirection.Down); true
        }

        else -> false
    }
}

@Composable
fun KanalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    /**
     * Done rather than Next by default: moving to the next field from inside a
     * full-screen keyboard is exactly the blind chain this is meant to break.
     */
    imeAction: ImeAction = ImeAction.Done,
    supportingText: String = ""
) {
    val focusManager = LocalFocusManager.current
    val typing = rememberTyping()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = !typing.editing,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onDone = { typing.end() },
            onGo = { typing.end() },
            onNext = { typing.end(); focusManager.moveFocus(FocusDirection.Down) }
        ),
        supportingText = if (supportingText.isBlank()) null else {
            { Text(supportingText, style = MaterialTheme.typography.labelSmall) }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = KanalColors.Accent,
            unfocusedBorderColor = KanalColors.Outline,
            focusedContainerColor = KanalColors.Surface,
            unfocusedContainerColor = KanalColors.BackgroundElevated,
            focusedLabelColor = KanalColors.Accent,
            cursorColor = KanalColors.Accent
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { typing.onFocusChanged(it.isFocused) }
            .typingKeys(typing) { direction -> focusManager.moveFocus(direction) }
    )
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.common_search_hint)
) {
    val focusManager = LocalFocusManager.current
    val typing = rememberTyping()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = !typing.editing,
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = KanalColors.OnSurfaceMuted)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { typing.end() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = KanalColors.Accent,
            unfocusedBorderColor = KanalColors.Outline,
            focusedContainerColor = KanalColors.Surface,
            unfocusedContainerColor = KanalColors.BackgroundElevated,
            cursorColor = KanalColors.Accent
        ),
        shape = RoundedCornerShape(50),
        modifier = modifier
            .onFocusChanged { typing.onFocusChanged(it.isFocused) }
            .typingKeys(typing) { direction -> focusManager.moveFocus(direction) }
    )
}

/** Settings row with a switch, focusable as a whole so the D-pad can toggle it. */
@Composable
fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    FocusableSurface(
        onClick = { onCheckedChange(!checked) },
        modifier = modifier.fillMaxWidth(),
        focusedScale = 1.01f,
        color = KanalColors.Surface,
        focusedColor = KanalColors.SurfaceVariant
    ) { focused ->
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (focused) KanalColors.Accent else KanalColors.OnBackground
                )
                if (description.isNotBlank()) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = KanalColors.OnSurfaceFaint
                    )
                }
            }
            Box(Modifier.padding(start = 16.dp)) {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = KanalColors.Background,
                        checkedTrackColor = KanalColors.Accent,
                        uncheckedThumbColor = KanalColors.OnSurfaceFaint,
                        uncheckedTrackColor = KanalColors.SurfaceVariant
                    )
                )
            }
        }
    }
}
