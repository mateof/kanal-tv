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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.mateof.kanal.R
import com.mateof.kanal.data.model.SourceType
import com.mateof.kanal.link.LinkInbox
import com.mateof.kanal.link.PendingOffer
import com.mateof.kanal.ui.components.ButtonTone
import com.mateof.kanal.ui.components.KanalButton
import com.mateof.kanal.ui.isCompact
import com.mateof.kanal.ui.theme.KanalColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import javax.inject.Inject

/** What other devices ask of this one, for the navigation host to act on. */
@HiltViewModel
class IncomingLinkViewModel @Inject constructor(
    private val inbox: LinkInbox
) : ViewModel() {
    val play = inbox.play
    val stops = inbox.stops
    val offer = inbox.offer
    val imported = inbox.imported

    fun consumePlay() = inbox.consumePlay()

    fun consumeImported() = inbox.consumeImported()

    fun answer(offer: PendingOffer, accept: Boolean) = inbox.answer(offer, accept)
}

/**
 * Another device wants to add its sources here. Drawn over whatever is on
 * screen, the player included, and never answered for the person: a source
 * carries an account's password, and anyone on the network can send one.
 */
@Composable
fun IncomingSourcesDialog(
    offer: PendingOffer,
    onAnswer: (Boolean) -> Unit
) {
    val accept = remember(offer.id) { FocusRequester() }

    // On a button, never on the group: asking a group for focus can land on
    // nothing, and then the remote has nowhere to move from.
    LaunchedEffect(offer.id) {
        repeat(12) {
            if (runCatching { accept.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40)
        }
    }
    BackHandler { onAnswer(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE0040609))
            // Tapping outside does nothing: a misplaced tap must not decide this.
            .pointerInput(Unit) { detectTapGestures { } }
            // With a player underneath, BACK never reaches the BackHandler.
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyUp) onAnswer(false)
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (isCompact) 560.dp else 720.dp)
                .fillMaxWidth(if (isCompact) 0.94f else 0.6f)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(0f to Color(0xFF16203A), 1f to Color(0xFF0B1120)))
                .padding(if (isCompact) 22.dp else 30.dp)
                .focusGroup()
        ) {
            Text(
                pluralStringResource(R.plurals.link_offer_title, offer.sources.size, offer.sources.size),
                style = MaterialTheme.typography.titleLarge,
                color = KanalColors.OnBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.link_offer_from, offer.from.name, offer.host),
                style = MaterialTheme.typography.bodyMedium,
                color = KanalColors.OnSurfaceMuted
            )
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                offer.sources.take(8).forEach { source ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(KanalColors.Surface)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            source.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = KanalColors.OnBackground
                        )
                        Text(
                            buildString {
                                append(if (source.type == SourceType.XTREAM) "Xtream" else "M3U")
                                append(" · ")
                                append(hostOf(source.url))
                                if (source.username.isNotBlank()) append(" · ").append(source.username)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = KanalColors.OnSurfaceMuted
                        )
                    }
                }
                if (offer.sources.size > 8) {
                    Text(
                        "+${offer.sources.size - 8}",
                        style = MaterialTheme.typography.labelSmall,
                        color = KanalColors.OnSurfaceMuted
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.link_offer_hint),
                style = MaterialTheme.typography.bodySmall,
                color = KanalColors.OnSurfaceFaint
            )
            Spacer(Modifier.height(18.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KanalButton(
                    stringResource(R.string.link_offer_accept),
                    { onAnswer(true) },
                    tone = ButtonTone.Primary,
                    modifier = Modifier.focusRequester(accept)
                )
                KanalButton(stringResource(R.string.link_offer_reject), { onAnswer(false) })
            }
        }
    }
}

/** Enough of the address to recognise the server, without the path or a query. */
private fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore('/').substringBefore('?').substringAfter('@')
