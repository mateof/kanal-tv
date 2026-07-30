package com.mateof.kanal.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.mateof.kanal.R
import com.mateof.kanal.ui.theme.KanalColors

/**
 * A focused Compose text field eats the D-pad up/down keys to move the caret,
 * which traps focus inside the field: from the sofa the button underneath looks
 * disabled. This intercepts those keys and moves focus by hand.
 */
fun Modifier.dpadEscape(
    moveFocus: (FocusDirection) -> Unit
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (event.key) {
        Key.DirectionUp -> {
            moveFocus(FocusDirection.Up); true
        }

        Key.DirectionDown -> {
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
    imeAction: ImeAction = ImeAction.Next,
    supportingText: String = ""
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
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
            .dpadEscape { direction -> focusManager.moveFocus(direction) }
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
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = KanalColors.OnSurfaceMuted)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = KanalColors.Accent,
            unfocusedBorderColor = KanalColors.Outline,
            focusedContainerColor = KanalColors.Surface,
            unfocusedContainerColor = KanalColors.BackgroundElevated,
            cursorColor = KanalColors.Accent
        ),
        shape = RoundedCornerShape(50),
        modifier = modifier.dpadEscape { direction -> focusManager.moveFocus(direction) }
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
