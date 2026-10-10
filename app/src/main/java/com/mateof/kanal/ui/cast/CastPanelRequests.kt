package com.mateof.kanal.ui.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The transmission notification asking for the panel. Held until the
 * navigation is ready to take it: on a cold start the app still has to pass
 * the gate before there is anywhere to go from.
 */
object CastPanelRequests {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() {
        _pending.value = true
    }

    fun consume() {
        _pending.value = false
    }
}
