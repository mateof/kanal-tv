package com.mateof.kanal.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay

/**
 * Sends the remote back to the item the user opened.
 *
 * A list keeps its scroll across the player on its own, but not its focus: the
 * next press of an arrow lands on whatever happens to be first on screen, which
 * on a long list reads as "it went back to the top". This remembers the item
 * that was opened — in saveable state, so it survives the trip through the
 * player — and puts the focus back on it when the list returns.
 */
@Stable
class FocusReturn(
    /** Saveable, so the trip through the player survives the back stack. */
    private val state: MutableState<String?>
) {
    /** The item to come back to, or null when arriving fresh. */
    val target: String? get() = state.value

    private val requester = FocusRequester()

    /** Called as the user opens an item, before navigating away. */
    fun leaveThrough(id: String) {
        state.value = id
    }

    /** Attach to each item; only the one being returned to gets the requester. */
    fun modifierFor(id: String): Modifier =
        if (id == target) Modifier.focusRequester(requester) else Modifier

    /**
     * Asks for the focus back once the list is on screen.
     *
     * Retried rather than tried once: the row is attached in the same frame the
     * screen returns, and asking before it exists silently does nothing. Gives
     * up quietly if the item is no longer in the list — a channel that vanished
     * from the catalogue is not worth an error.
     */
    suspend fun restore() {
        if (target == null) return
        repeat(ATTEMPTS) {
            if (runCatching { requester.requestFocus() }.isSuccess) return
            delay(INTERVAL_MS)
        }
    }

    private companion object {
        const val ATTEMPTS = 12
        const val INTERVAL_MS = 40L
    }
}

/**
 * Kept for every kind of input, not just a remote: with a finger the mark on
 * the last thing opened reads as "you were here", which is the same thing the
 * remote needs, and it saves having to guess how the screen is being driven.
 */
@Composable
fun rememberFocusReturn(): FocusReturn {
    val stored = rememberSaveable { mutableStateOf<String?>(null) }
    return remember(stored) { FocusReturn(stored) }
}
