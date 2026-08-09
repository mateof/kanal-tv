package com.mateof.kanal.ui.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mateof.kanal.R
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.data.model.favoriteKey
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.ui.components.ActionMenu
import com.mateof.kanal.ui.components.MenuAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What a long press was made on. */
data class ItemActionsRequest(
    val kind: ContentKind,
    val itemId: String,
    val title: String
)

/**
 * The menu behind a long press on a row of a list.
 *
 * Films and series can be made favourites from their own page, but a channel
 * has none — the list plays it straight away — so until now there was nowhere
 * to mark one. Rather than adding a star to every row, which would put a second
 * focusable target beside each channel and slow the remote down, the actions
 * live behind the press the list already recognised.
 */
@HiltViewModel
class ItemActionsViewModel @Inject constructor(
    private val prefs: AppPreferences
) : ViewModel() {

    private val _request = MutableStateFlow<ItemActionsRequest?>(null)
    val request: StateFlow<ItemActionsRequest?> = _request.asStateFlow()

    /**
     * Read from the stored set rather than kept as a local flag, so the entry
     * flips the moment the write lands and every list showing the same item
     * agrees with the menu.
     */
    val isFavorite: StateFlow<Boolean> =
        combine(_request, prefs.favorites, prefs.activeSource) { request, favorites, source ->
            if (request == null || source == null) {
                false
            } else {
                favorites.contains(favoriteKey(request.kind, source.id, request.itemId))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun open(kind: ContentKind, itemId: String, title: String) {
        _request.value = ItemActionsRequest(kind, itemId, title)
    }

    fun close() {
        _request.value = null
    }

    fun toggleFavorite() {
        val request = _request.value ?: return
        viewModelScope.launch {
            val source = prefs.activeSource.first() ?: return@launch
            prefs.toggleFavorite(request.kind, source.id, request.itemId)
        }
    }
}

/**
 * Drawn by whichever screen owns the list. Casting is handed back out because
 * it belongs to the cast sheet, which the same screen already hosts.
 */
@Composable
fun ItemActionsSheet(
    vm: ItemActionsViewModel,
    onCast: (ItemActionsRequest) -> Unit
) {
    val request by vm.request.collectAsStateWithLifecycle()
    val isFavorite by vm.isFavorite.collectAsStateWithLifecycle()
    val open = request ?: return

    ActionMenu(
        title = open.title,
        actions = listOf(
            MenuAction(
                label = if (isFavorite) {
                    stringResource(R.string.live_remove_favorite)
                } else {
                    stringResource(R.string.live_add_favorite)
                },
                icon = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                active = isFavorite,
                // Stays open: the entry changing under the finger is the only
                // confirmation that the press did anything.
                onClick = vm::toggleFavorite
            ),
            MenuAction(
                label = stringResource(R.string.cast_send),
                icon = Icons.Outlined.Cast,
                onClick = {
                    onCast(open)
                    vm.close()
                }
            )
        ),
        onDismiss = vm::close
    )
}
