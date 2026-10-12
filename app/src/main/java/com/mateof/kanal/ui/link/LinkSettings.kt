package com.mateof.kanal.ui.link

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Send
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mateof.kanal.R
import com.mateof.kanal.core.UiText
import com.mateof.kanal.core.resolve
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.link.DeviceIdentity
import com.mateof.kanal.link.KanalPeer
import com.mateof.kanal.link.LinkClient
import com.mateof.kanal.link.LinkServer
import com.mateof.kanal.ui.cast.DeviceRow
import com.mateof.kanal.ui.cast.iconFor
import com.mateof.kanal.ui.cast.peerDetails
import com.mateof.kanal.ui.components.ButtonTone
import com.mateof.kanal.ui.components.KanalButton
import com.mateof.kanal.ui.components.KanalChip
import com.mateof.kanal.ui.components.KanalTextField
import com.mateof.kanal.ui.components.SettingSwitchRow
import com.mateof.kanal.ui.isCompact
import com.mateof.kanal.ui.theme.KanalColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ThisDevice(
    val visible: Boolean = true,
    /** What the user typed; blank means [systemName]. */
    val customName: String = "",
    val systemName: String = "",
    val addresses: List<String> = emptyList(),
    val port: Int = 0
) {
    val shownName: String get() = customName.ifBlank { systemName }
}

data class SendSourcesState(
    val open: Boolean = false,
    val sources: List<Source> = emptyList(),
    val selected: Set<String> = emptySet(),
    val peers: List<KanalPeer> = emptyList(),
    val searching: Boolean = false,
    /** The device being waited on, while someone there decides. */
    val waitingFor: String? = null,
    val message: UiText? = null,
    val messageIsError: Boolean = false
)

@HiltViewModel
class LinkSettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val identity: DeviceIdentity,
    private val server: LinkServer,
    private val client: LinkClient
) : ViewModel() {

    val device: StateFlow<ThisDevice> = prefs.settings
        .map { settings ->
            ThisDevice(
                visible = settings.linkVisible,
                customName = settings.linkDeviceName,
                systemName = identity.systemName(),
                addresses = identity.addresses(),
                port = server.port
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThisDevice())

    private val _send = MutableStateFlow(SendSourcesState())
    val send: StateFlow<SendSourcesState> = _send.asStateFlow()

    fun setVisible(value: Boolean) = viewModelScope.launch { prefs.setLinkVisible(value) }

    fun setName(value: String) = viewModelScope.launch { prefs.setLinkDeviceName(value) }

    fun openSend() {
        viewModelScope.launch {
            val sources = prefs.sources.first()
            _send.value = SendSourcesState(
                open = true,
                sources = sources,
                selected = sources.map { it.id }.toSet(),
                peers = _send.value.peers
            )
            search()
        }
    }

    fun closeSend() {
        _send.value = _send.value.copy(open = false)
    }

    fun toggle(sourceId: String) {
        val current = _send.value.selected
        _send.value = _send.value.copy(
            selected = if (sourceId in current) current - sourceId else current + sourceId
        )
    }

    fun search() {
        if (_send.value.searching) return
        viewModelScope.launch {
            _send.value = _send.value.copy(searching = true)
            val found = client.discover()
            _send.value = _send.value.copy(peers = found, searching = false)
        }
    }

    fun addByAddress(address: String) {
        viewModelScope.launch {
            _send.value = _send.value.copy(searching = true, message = null)
            val peer = client.probe(address)
            if (peer == null) {
                _send.value = _send.value.copy(
                    searching = false,
                    message = UiText(R.string.link_no_kanal_at, address.trim()),
                    messageIsError = true
                )
            } else {
                prefs.rememberLinkAddress(address.trim())
                _send.value = _send.value.copy(
                    searching = false,
                    peers = (_send.value.peers + peer).distinctBy { it.id }
                )
            }
        }
    }

    fun sendTo(peer: KanalPeer) {
        val state = _send.value
        if (state.waitingFor != null) return
        val chosen = state.sources.filter { it.id in state.selected }
        if (chosen.isEmpty()) {
            _send.value = state.copy(message = UiText(R.string.link_pick_sources), messageIsError = true)
            return
        }
        viewModelScope.launch {
            _send.value = _send.value.copy(waitingFor = peer.name, message = null)
            val result = client.sendSources(peer, chosen)
            _send.value = _send.value.copy(
                waitingFor = null,
                message = result.fold(
                    onSuccess = { answer ->
                        when {
                            !answer.accepted -> UiText(R.string.link_sources_refused, peer.name)
                            else -> UiText(R.string.link_sources_done, peer.name, answer.added, answer.updated)
                        }
                    },
                    onFailure = { UiText(R.string.link_send_failed, peer.name) }
                ),
                messageIsError = result.getOrNull()?.accepted != true
            )
        }
    }
}

/**
 * How this device shows up to others: its name, whether it shows up at all,
 * and where. The name is what the person sending will look for in the list.
 */
@Composable
fun ThisDeviceCard(vm: LinkSettingsViewModel, onSendSources: () -> Unit) {
    val device by vm.device.collectAsStateWithLifecycle()
    var nameDraft by remember(device.customName) { mutableStateOf(device.customName) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.link_this_device),
            style = MaterialTheme.typography.titleSmall,
            color = KanalColors.OnBackground
        )
        SettingSwitchRow(
            title = stringResource(R.string.link_visible),
            description = stringResource(R.string.link_visible_desc),
            checked = device.visible,
            onCheckedChange = { vm.setVisible(it) }
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            KanalTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it },
                label = stringResource(R.string.link_device_name),
                supportingText = stringResource(R.string.link_device_name_desc, device.systemName),
                modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier.width(460.dp)
            )
            KanalButton(stringResource(R.string.common_save), { vm.setName(nameDraft) })
        }
        Text(
            if (device.visible && device.addresses.isNotEmpty()) {
                stringResource(
                    R.string.link_seen_as,
                    device.shownName,
                    device.addresses.joinToString(", ")
                )
            } else if (device.visible) {
                stringResource(R.string.link_no_network)
            } else {
                stringResource(R.string.link_hidden)
            },
            style = MaterialTheme.typography.bodySmall,
            color = KanalColors.OnSurfaceMuted
        )
        KanalButton(
            stringResource(R.string.link_send_sources),
            onSendSources,
            icon = Icons.Outlined.Send
        )
    }
}

@Composable
fun SendSourcesSheet(vm: LinkSettingsViewModel = hiltViewModel()) {
    val state by vm.send.collectAsStateWithLifecycle()
    if (!state.open) return

    var address by remember { mutableStateOf("") }
    val anchor = remember { FocusRequester() }
    BackHandler { vm.closeSend() }
    LaunchedEffect(Unit) {
        repeat(12) {
            if (runCatching { anchor.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE0040609))
            .pointerInput(Unit) { detectTapGestures { vm.closeSend() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (isCompact) 560.dp else 780.dp)
                .fillMaxWidth(if (isCompact) 0.94f else 0.7f)
                .heightIn(max = if (isCompact) 680.dp else 600.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(0f to Color(0xFF16203A), 1f to Color(0xFF0B1120)))
                // Taps inside must not reach the scrim and close the sheet.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(if (isCompact) 22.dp else 30.dp)
                .focusGroup()
        ) {
            Text(
                stringResource(R.string.link_send_sources),
                style = MaterialTheme.typography.titleLarge,
                color = KanalColors.OnBackground
            )
            Text(
                stringResource(R.string.link_send_sources_desc),
                style = MaterialTheme.typography.bodySmall,
                color = KanalColors.OnSurfaceMuted
            )
            Spacer(Modifier.height(14.dp))

            if (state.sources.isEmpty()) {
                Text(
                    stringResource(R.string.link_no_sources),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KanalColors.OnSurfaceMuted
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.sources.forEach { source ->
                        KanalChip(
                            label = source.name,
                            selected = source.id in state.selected,
                            onClick = { vm.toggle(source.id) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            state.waitingFor?.let { name ->
                Text(
                    stringResource(R.string.link_waiting_answer, name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KanalColors.Accent
                )
                Spacer(Modifier.height(10.dp))
            }
            state.message?.let { message ->
                Text(
                    message.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.messageIsError) KanalColors.Warning else KanalColors.Accent
                )
                Spacer(Modifier.height(10.dp))
            }

            Box(Modifier.weight(1f, fill = false)) {
                if (state.peers.isEmpty()) {
                    Column {
                        Text(
                            stringResource(if (state.searching) R.string.cast_searching else R.string.link_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = KanalColors.OnSurfaceMuted
                        )
                        if (!state.searching) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.link_none_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = KanalColors.OnSurfaceFaint
                            )
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.peers, key = { it.id }) { peer ->
                            DeviceRow(
                                icon = iconFor(peer.info.formFactor),
                                title = peer.name,
                                details = peerDetails(peer),
                                extra = null,
                                onClick = { vm.sendTo(peer) }
                            )
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
                    supportingText = stringResource(R.string.link_address_hint),
                    modifier = Modifier.width(if (isCompact) 260.dp else 380.dp)
                )
                KanalButton(
                    stringResource(R.string.cast_add),
                    { vm.addByAddress(address) },
                    enabled = address.isNotBlank() && !state.searching
                )
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KanalButton(
                    stringResource(R.string.cast_search_again),
                    vm::search,
                    enabled = !state.searching
                )
                // The anchor: always enabled, unlike the search button, which
                // is off for the first two seconds while the search runs.
                KanalButton(
                    stringResource(R.string.common_close),
                    vm::closeSend,
                    modifier = Modifier.focusRequester(anchor),
                    tone = ButtonTone.Primary
                )
            }
        }
    }
}
