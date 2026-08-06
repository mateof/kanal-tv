package com.mateof.kanal.ui.cast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mateof.kanal.R
import com.mateof.kanal.cast.CastDevice
import com.mateof.kanal.core.resolve
import com.mateof.kanal.ui.components.ButtonTone
import com.mateof.kanal.ui.components.FocusableSurface
import com.mateof.kanal.ui.components.KanalButton
import com.mateof.kanal.ui.components.KanalTextField
import com.mateof.kanal.ui.isCompact
import com.mateof.kanal.ui.theme.KanalColors
import kotlinx.coroutines.delay

/**
 * The devices found on the network, and what was sent where.
 *
 * Drawn as an overlay inside the screen rather than a platform dialog, so it
 * plays by the same focus rules as everything else.
 */
@Composable
fun CastSheet(
    state: CastUiState,
    onSearch: () -> Unit,
    onPick: (CastDevice) -> Unit,
    onAdd: (String) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit
) {
    if (!state.open) return

    var address by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    BackHandler { onClose() }

    // The anchor is a button, never the address field: landing on a text field
    // throws the on-screen keyboard up over the list before it can be read.
    LaunchedEffect(Unit) {
        repeat(12) {
            if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE0040609))
            .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (isCompact) 560.dp else 780.dp)
                .fillMaxWidth(if (isCompact) 0.94f else 0.7f)
                .heightIn(max = if (isCompact) 640.dp else 560.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(0f to Color(0xFF16203A), 1f to Color(0xFF0B1120))
                )
                .padding(if (isCompact) 22.dp else 30.dp)
                .focusGroup()
        ) {
            Text(
                stringResource(R.string.cast_title),
                style = MaterialTheme.typography.titleLarge,
                color = KanalColors.OnBackground
            )
            if (state.title.isNotBlank()) {
                Text(
                    state.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KanalColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(14.dp))

            state.sentTo?.let { name ->
                Text(
                    stringResource(R.string.cast_playing_on, name),
                    style = MaterialTheme.typography.titleSmall,
                    color = KanalColors.Accent
                )
                Spacer(Modifier.height(12.dp))
            }

            state.error?.let { reason ->
                Text(
                    stringResource(R.string.cast_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KanalColors.Error
                )
                state.hint?.let { hint ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        hint.resolve(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = KanalColors.OnSurfaceMuted
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = KanalColors.OnSurfaceFaint
                )
                Spacer(Modifier.height(12.dp))
            }

            Box(Modifier.weight(1f)) {
                when {
                    state.searching -> Text(
                        stringResource(R.string.cast_searching),
                        style = MaterialTheme.typography.bodyMedium,
                        color = KanalColors.OnSurfaceMuted
                    )

                    state.devices.isEmpty() -> Column {
                        Text(
                            stringResource(R.string.cast_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = KanalColors.OnSurfaceMuted
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.cast_none_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = KanalColors.OnSurfaceFaint
                        )
                    }

                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.devices, key = { it.id }) { device ->
                            FocusableSurface(
                                onClick = { onPick(device) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = KanalColors.Surface,
                                focusedColor = KanalColors.Accent
                            ) { focused ->
                                Text(
                                    device.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (focused) Color(0xFF06231F) else KanalColors.OnBackground,
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .padding(horizontal = 16.dp, vertical = 14.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KanalTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = stringResource(R.string.cast_address),
                    supportingText = stringResource(R.string.cast_address_hint),
                    modifier = Modifier.width(if (isCompact) 260.dp else 420.dp)
                )
                KanalButton(
                    stringResource(R.string.cast_add),
                    { onAdd(address) },
                    enabled = address.isNotBlank() && !state.searching
                )
            }

            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.sentTo != null) {
                    KanalButton(stringResource(R.string.cast_bring_back), onStop)
                }
                KanalButton(
                    stringResource(R.string.cast_search_again),
                    onSearch,
                    modifier = Modifier.focusRequester(focus),
                    enabled = !state.searching
                )
                KanalButton(stringResource(R.string.common_close), onClose, tone = ButtonTone.Primary)
            }
        }
    }
}
