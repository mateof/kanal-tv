package com.mateof.kanal.ui.screens.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.mateof.kanal.R
import com.mateof.kanal.core.resolve
import com.mateof.kanal.data.model.SourceType
import com.mateof.kanal.ui.components.ButtonTone
import com.mateof.kanal.ui.components.KanalButton
import com.mateof.kanal.ui.components.KanalChip
import com.mateof.kanal.ui.components.KanalTextField
import com.mateof.kanal.ui.components.StepProgress
import com.mateof.kanal.ui.isCompact
import com.mateof.kanal.ui.theme.KanalColors
import com.mateof.kanal.ui.theme.Spacing

@Composable
fun SetupScreen(
    sourceId: String,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val vm: SetupViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val compact = isCompact

    LaunchedEffect(sourceId) { vm.load(sourceId) }
    LaunchedEffect(state.finished) { if (state.finished) onDone() }

    if (compact) {
        // Upright the two panes stack into a single scrolling column.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            SetupIntro(isEditing = state.isEditing, compact = true)
            SetupForm(state = state, vm = vm, onCancel = onCancel, compact = true)
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(360.dp)
                    .fillMaxHeight()
                    .padding(start = Spacing.screenHorizontal, top = 56.dp, end = 24.dp)
            ) {
                SetupIntro(isEditing = state.isEditing, compact = false)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 40.dp, vertical = 56.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                SetupForm(state = state, vm = vm, onCancel = onCancel, compact = false)
            }
        }
    }
}

@Composable
private fun SetupIntro(isEditing: Boolean, compact: Boolean) {
    Icon(
        painter = painterResource(R.drawable.ic_kanal_mark),
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = Modifier.size(if (compact) 52.dp else 72.dp)
    )
    Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
    Text(
        if (isEditing) stringResource(R.string.setup_title_edit) else stringResource(R.string.setup_title_new),
        style = if (compact) {
            MaterialTheme.typography.headlineSmall
        } else {
            MaterialTheme.typography.headlineMedium
        },
        color = KanalColors.OnBackground
    )
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.setup_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = KanalColors.OnSurfaceMuted
    )
    if (!compact) {
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.setup_tip),
            style = MaterialTheme.typography.bodySmall,
            color = KanalColors.OnSurfaceFaint
        )
    }
}

@Composable
private fun SetupForm(
    state: SetupState,
    vm: SetupViewModel,
    onCancel: () -> Unit,
    compact: Boolean
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KanalChip(
            label = stringResource(R.string.setup_xtream),
            selected = state.type == SourceType.XTREAM,
            onClick = { vm.setType(SourceType.XTREAM) }
        )
        KanalChip(
            label = stringResource(R.string.setup_m3u),
            selected = state.type == SourceType.M3U,
            onClick = { vm.setType(SourceType.M3U) }
        )
    }

    KanalTextField(
        value = state.name,
        onValueChange = vm::setName,
        label = stringResource(R.string.setup_name),
        placeholder = stringResource(R.string.setup_name_placeholder)
    )

    if (state.type == SourceType.XTREAM) {
        KanalTextField(
            value = state.url,
            onValueChange = vm::setUrl,
            label = stringResource(R.string.setup_server_url),
            placeholder = stringResource(R.string.setup_server_placeholder),
            supportingText = if (compact) {
                stringResource(R.string.setup_server_url_hint)
            } else {
                ""
            }
        )
        KanalTextField(value = state.username, onValueChange = vm::setUsername, label = stringResource(R.string.setup_user))
        KanalTextField(
            value = state.password,
            onValueChange = vm::setPassword,
            label = stringResource(R.string.setup_password),
            isPassword = true
        )
        KanalTextField(
            value = state.epgUrl,
            onValueChange = vm::setEpgUrl,
            label = stringResource(R.string.setup_epg_url),
            supportingText = stringResource(R.string.setup_epg_url_hint)
        )
    } else {
        KanalTextField(
            value = state.url,
            onValueChange = vm::setUrl,
            label = stringResource(R.string.setup_m3u_url),
            placeholder = stringResource(R.string.setup_m3u_placeholder)
        )
        KanalTextField(
            value = state.epgUrl,
            onValueChange = vm::setEpgUrl,
            label = stringResource(R.string.setup_epg_url_m3u),
            supportingText = stringResource(R.string.setup_epg_url_m3u_hint)
        )
    }

    KanalTextField(
        value = state.userAgent,
        onValueChange = vm::setUserAgent,
        label = stringResource(R.string.setup_user_agent),
        supportingText = stringResource(R.string.setup_user_agent_hint)
    )

    state.message?.let { messageText ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (state.messageIsError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = if (state.messageIsError) KanalColors.Error else KanalColors.Accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                messageText.resolve(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.messageIsError) KanalColors.Error else KanalColors.Accent
            )
        }
    }

    if (state.busy) {
        StepProgress(state.busyLabel?.resolve().orEmpty(), state.progress)
    }

    val saveLabel = if (state.isEditing) stringResource(R.string.setup_save_sync) else stringResource(R.string.setup_save_start)
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            KanalButton(
                text = saveLabel,
                onClick = vm::save,
                icon = Icons.Outlined.Save,
                tone = ButtonTone.Primary,
                enabled = !state.busy && state.canSave,
                modifier = Modifier.fillMaxWidth()
            )
            KanalButton(
                text = stringResource(R.string.setup_test),
                onClick = vm::test,
                enabled = !state.busy && state.canSave,
                modifier = Modifier.fillMaxWidth()
            )
            KanalButton(
                text = stringResource(R.string.common_cancel),
                onClick = onCancel,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            )
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            KanalButton(
                text = stringResource(R.string.setup_test),
                onClick = vm::test,
                enabled = !state.busy && state.canSave
            )
            KanalButton(
                text = saveLabel,
                onClick = vm::save,
                icon = Icons.Outlined.Save,
                tone = ButtonTone.Primary,
                enabled = !state.busy && state.canSave
            )
            KanalButton(text = stringResource(R.string.common_cancel), onClick = onCancel, enabled = !state.busy)
        }
    }
}
