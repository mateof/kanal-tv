package com.mateof.kanal.player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shrinking the picture into a floating window without interrupting it.
 *
 * Only the activity can enter picture-in-picture, and only the player screen
 * knows there is a picture worth shrinking. This sits between the two: the
 * player publishes what it is showing, the activity reads it when the user
 * leaves or asks, and the result comes back so the screen can strip away
 * everything that has no business in a window that small.
 *
 * Nothing here touches the player itself, which is the point: the activity
 * stays alive throughout, so the stream is never reopened.
 */
@Singleton
class PipController @Inject constructor() {

    /** Video shape while something is playing, null when there is nothing to shrink. */
    private val _videoSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoSize: StateFlow<Pair<Int, Int>?> = _videoSize.asStateFlow()

    private val _active = MutableStateFlow(false)

    /** True while the app is the little window. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emitted when the user asks for it from the player controls. */
    val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    fun setVideoSize(width: Int, height: Int) {
        _videoSize.value = if (width > 0 && height > 0) width to height else null
    }

    fun clearVideo() {
        _videoSize.value = null
    }

    fun request() {
        _requests.tryEmit(Unit)
    }

    fun setActive(value: Boolean) {
        _active.value = value
    }
}
