package com.mateof.kanal.core

import com.mateof.kanal.core.log.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notices when nobody has touched the remote for an hour and asks whether
 * anyone is still watching. Left unanswered, the app closes: a channel left
 * running overnight costs the household bandwidth and the panel its hours.
 */
@Singleton
class InactivityWatcher @Inject constructor(
    private val logger: FileLogger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    @Volatile
    private var lastInteraction = System.currentTimeMillis()

    @Volatile
    private var enabled = true

    @Volatile
    private var answered = false

    private val _secondsToClose = MutableStateFlow<Int?>(null)

    /** Seconds left to answer, or null when nothing is being asked. */
    val secondsToClose: StateFlow<Int?> = _secondsToClose.asStateFlow()

    private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires when the question went unanswered. */
    val expired: SharedFlow<Unit> = _expired.asSharedFlow()

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) dismiss()
    }

    /** Every touch and every key press on the remote lands here. */
    fun onInteraction() {
        lastInteraction = System.currentTimeMillis()
        // A key press while the question is up *is* the answer.
        if (_secondsToClose.value != null) dismiss()
    }

    /** "Yes, I am still here." */
    fun confirm() {
        logger.i("Inactivity", "Sigue ahí, se reinicia la cuenta")
        dismiss()
    }

    private fun dismiss() {
        answered = true
        _secondsToClose.value = null
        lastInteraction = System.currentTimeMillis()
    }

    /**
     * Runs only while something is on screen. Without the [stop] half the loop
     * outlives the activity it closed and goes on asking an empty room whether
     * it is still watching.
     */
    fun start() {
        lastInteraction = System.currentTimeMillis()
        if (loop != null) return
        loop = scope.launch {
            while (true) {
                delay(CHECK_EVERY_MS)
                if (!enabled || _secondsToClose.value != null) continue
                if (System.currentTimeMillis() - lastInteraction < IDLE_LIMIT_MS) continue

                logger.i("Inactivity", "Una hora sin actividad, preguntando")
                ask()
            }
        }
    }

    private suspend fun ask() {
        answered = false
        val deadline = System.currentTimeMillis() + GRACE_MS
        while (!answered) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) {
                _secondsToClose.value = null
                logger.i("Inactivity", "Sin respuesta, cerrando para ahorrar datos y consumo")
                _expired.emit(Unit)
                return
            }
            _secondsToClose.value = ((left + 999) / 1_000).toInt()
            delay(250)
        }
        _secondsToClose.value = null
    }

    fun stop() {
        loop?.cancel()
        loop = null
        answered = true
        _secondsToClose.value = null
    }

    private companion object {
        const val IDLE_LIMIT_MS = 60L * 60 * 1_000
        const val GRACE_MS = 60L * 1_000
        const val CHECK_EVERY_MS = 15L * 1_000
    }
}
