package com.mateof.kanal.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay

/**
 * Puts a list back where it was when the user opened something from it.
 *
 * Two things are lost on the way back and neither comes free. The focus always:
 * the next press of an arrow lands on whatever is first on screen. And on a
 * long catalogue the scroll as well — the list is paged, so coming back it is
 * rebuilt from the first page, the saved index falls outside what is loaded and
 * the whole thing settles at the top, which is what "it went back to the first
 * channel" looks like.
 *
 * So both are remembered — the item and where it sat — in saveable state, which
 * is what survives the trip through the player.
 */
@Stable
class FocusReturn(
    private val state: MutableState<String?>,
    private val indexState: MutableState<Int>,
    private val offsetState: MutableState<Int>
) {
    /** The item to come back to, or null when arriving fresh. */
    val target: String? get() = state.value

    private val requester = FocusRequester()

    /**
     * Called as the user opens an item, before navigating away. [anchorIndex]
     * and [anchorOffset] are the list's own first-visible position, not the
     * item's: putting the row back on screen is not the same as putting the
     * list back where it was, and the second is what the eye notices.
     */
    fun leaveThrough(id: String, anchorIndex: Int = -1, anchorOffset: Int = 0) {
        state.value = id
        indexState.value = anchorIndex
        offsetState.value = anchorOffset
    }

    /** Attach to each item; only the one being returned to gets the requester. */
    fun modifierFor(id: String): Modifier =
        if (id == target) Modifier.focusRequester(requester) else Modifier

    /**
     * Brings the list back to where it was and hands the focus over.
     *
     * Both are retried together for a moment rather than done once. The row is
     * attached in the same frame the screen returns, so asking for the focus
     * before it exists silently does nothing; and a paged list answers the
     * first scroll with whatever it has loaded so far, loads the rest of the
     * way there, and only then can the scroll actually land. Gives up quietly:
     * a channel that is no longer in the catalogue is not worth an error.
     *
     * [scrollTo] is the list's own scroller — a plain list and a grid do not
     * share a type, so the screen passes its own.
     */
    suspend fun restore(scrollTo: (suspend (Int, Int) -> Unit)? = null) {
        if (target == null) return
        val index = indexState.value
        val offset = offsetState.value
        repeat(ATTEMPTS) {
            if (scrollTo != null && index >= 0) {
                runCatching { scrollTo(index, offset) }
            }
            if (runCatching { requester.requestFocus() }.isSuccess) return
            delay(INTERVAL_MS)
        }
    }

    private companion object {
        const val ATTEMPTS = 14
        const val INTERVAL_MS = 45L
    }
}

/**
 * Kept for every kind of input, not just a remote: with a finger the list
 * coming back where it was left is the same want, and it saves having to guess
 * how the screen is being driven.
 */
@Composable
fun rememberFocusReturn(): FocusReturn {
    val stored = rememberSaveable { mutableStateOf<String?>(null) }
    val storedIndex = rememberSaveable { mutableIntStateOf(-1) }
    val storedOffset = rememberSaveable { mutableIntStateOf(0) }
    return remember(stored, storedIndex, storedOffset) {
        FocusReturn(stored, storedIndex, storedOffset)
    }
}
