package com.mateof.kanal.ui.actions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.ui.cast.CastSheet
import com.mateof.kanal.ui.cast.CastTarget
import com.mateof.kanal.ui.cast.CastViewModel

/**
 * The long-press menu of the channel list, for every other screen that shows
 * channels (home rows, favourites, search, guide). [content] receives what to
 * call on a long press with the channel's stream id and name; the menu and the
 * cast sheet it leads to are drawn over it.
 */
@Composable
fun WithChannelActions(
    content: @Composable (onChannelActions: (streamId: String, name: String) -> Unit) -> Unit
) {
    val actionsVm: ItemActionsViewModel = hiltViewModel()
    val castVm: CastViewModel = hiltViewModel()
    val castState by castVm.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        content { streamId, name -> actionsVm.open(ContentKind.LIVE, streamId, name) }
        CastSheet(
            state = castState,
            onSearch = castVm::search,
            onPick = castVm::sendTo,
            onAdd = castVm::addByAddress,
            onStop = castVm::stopSending,
            onClose = castVm::close
        )
        // Last, so it is drawn over the rows rather than behind them.
        ItemActionsSheet(
            vm = actionsVm,
            onCast = { request -> castVm.open(CastTarget.Channel(request.itemId), request.title) }
        )
    }
}
