package com.mateof.kanal.ui.cast

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.mateof.kanal.R
import com.mateof.kanal.cast.CastController
import com.mateof.kanal.cast.CastPhase
import com.mateof.kanal.cast.CastSession
import com.mateof.kanal.core.formatBitrate
import com.mateof.kanal.ui.components.FocusableSurface
import com.mateof.kanal.ui.theme.KanalColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Whether anything is on another device, for the screens that point to the panel. */
@HiltViewModel
class CastBarViewModel @Inject constructor(cast: CastController) : ViewModel() {
    val session: StateFlow<CastSession?> = cast.session
}

/**
 * A strip above the bottom navigation on a phone while something is on the
 * television: who it went to, how fast, and the way into the panel.
 */
@Composable
fun CastBar(session: CastSession, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val (label, color) = when (session.phase) {
        CastPhase.ACTIVE -> stringResource(R.string.cast_phase_active, session.device.name) to KanalColors.Accent
        CastPhase.RECONNECTING -> stringResource(R.string.cast_phase_reconnecting, session.device.name) to KanalColors.Warning
        CastPhase.ENDED -> stringResource(R.string.cast_phase_ended, session.device.name) to KanalColors.Error
    }
    val detail = buildList {
        add(session.request.title)
        if (session.viaPhone && session.phase == CastPhase.ACTIVE) add(formatBitrate(session.rateBytes))
    }.joinToString(" · ")

    FocusableSurface(
        onClick = onOpen,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = KanalColors.SurfaceVariant,
        focusedColor = KanalColors.Surface,
        focusedScale = 1.0f
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (session.phase == CastPhase.ACTIVE) Icons.Outlined.CastConnected else Icons.Outlined.Cast,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = KanalColors.OnSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.cast_panel_open),
                tint = Color(0xFF95A0B5)
            )
        }
    }
}
