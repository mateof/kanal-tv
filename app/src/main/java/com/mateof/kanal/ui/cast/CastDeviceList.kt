package com.mateof.kanal.ui.cast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mateof.kanal.R
import com.mateof.kanal.cast.CastDevice
import com.mateof.kanal.link.DeviceIdentity
import com.mateof.kanal.link.KanalPeer
import com.mateof.kanal.ui.components.FocusableSurface
import com.mateof.kanal.ui.theme.KanalColors

/**
 * Everything a stream can be sent to: other devices with Kanal first, since
 * they play it themselves and need nothing switched on, then DLNA renderers.
 */
@Composable
fun CastDeviceList(
    peers: List<KanalPeer>,
    devices: List<CastDevice>,
    searching: Boolean,
    onPickPeer: (KanalPeer) -> Unit,
    onPickDevice: (CastDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    if (peers.isEmpty() && devices.isEmpty()) {
        Column(modifier) {
            Text(
                stringResource(if (searching) R.string.cast_searching else R.string.cast_none),
                style = MaterialTheme.typography.bodyMedium,
                color = KanalColors.OnSurfaceMuted
            )
            if (!searching) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.link_none_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = KanalColors.OnSurfaceFaint
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.cast_none_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = KanalColors.OnSurfaceFaint
                )
            }
        }
        return
    }

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (peers.isNotEmpty()) {
            item(key = "peers") { GroupLabel(stringResource(R.string.link_group_kanal)) }
            items(peers, key = { "peer:" + it.id }) { peer ->
                DeviceRow(
                    icon = iconFor(peer.info.formFactor),
                    title = peer.name,
                    details = peerDetails(peer),
                    extra = peer.info.nowPlaying.takeIf { it.isNotBlank() }
                        ?.let { stringResource(R.string.link_now_playing, it) },
                    onClick = { onPickPeer(peer) }
                )
            }
        }
        if (devices.isNotEmpty()) {
            if (peers.isNotEmpty()) {
                item(key = "dlna") { GroupLabel(stringResource(R.string.link_group_dlna)) }
            }
            items(devices, key = { "dlna:" + it.id }) { device ->
                DeviceRow(
                    icon = Icons.Outlined.Cast,
                    title = device.name,
                    details = null,
                    extra = null,
                    onClick = { onPickDevice(device) }
                )
            }
        }
        if (searching) {
            item(key = "searching") {
                Text(
                    stringResource(R.string.cast_searching),
                    style = MaterialTheme.typography.bodySmall,
                    color = KanalColors.OnSurfaceFaint
                )
            }
        }
    }
}

/** "Android TV · Xiaomi MiTV-AXSO0 · 192.168.1.40 · Kanal 0.31.0" */
@Composable
fun peerDetails(peer: KanalPeer): String {
    val info = peer.info
    val kind = when (info.formFactor) {
        DeviceIdentity.FORM_TV -> stringResource(R.string.link_form_tv)
        DeviceIdentity.FORM_TABLET -> stringResource(R.string.link_form_tablet)
        else -> stringResource(R.string.link_form_phone)
    }
    val maker = info.manufacturer.takeUnless { it.equals("unknown", ignoreCase = true) }.orEmpty()
    val model = listOf(maker.replaceFirstChar { it.uppercase() }, info.model)
        .filter { it.isNotBlank() }
        .let { parts ->
            if (parts.size == 2 && parts[1].startsWith(parts[0], ignoreCase = true)) parts[1]
            else parts.joinToString(" ")
        }
    return listOf(kind, model, peer.host, "Kanal ${info.app}".takeIf { info.app.isNotBlank() })
        .filterNot { it.isNullOrBlank() }
        .joinToString(" · ")
}

fun iconFor(formFactor: String): ImageVector = when (formFactor) {
    DeviceIdentity.FORM_TV -> Icons.Outlined.Tv
    DeviceIdentity.FORM_TABLET -> Icons.Outlined.TabletAndroid
    else -> Icons.Outlined.PhoneAndroid
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = KanalColors.OnSurfaceMuted,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
    )
}

@Composable
fun DeviceRow(
    icon: ImageVector,
    title: String,
    details: String?,
    extra: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = KanalColors.Surface,
        focusedColor = KanalColors.Accent
    ) { focused ->
        val main = if (focused) Color(0xFF06231F) else KanalColors.OnBackground
        val muted = if (focused) Color(0xFF0B3A33) else KanalColors.OnSurfaceMuted
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = main, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = main,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                details?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                extra?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (focused) main else KanalColors.Accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
