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
 * Counts down to closing the app, for falling asleep in front of a film.
 *
 * Deliberately not persisted: a sleep timer is about tonight, and finding one
 * still armed tomorrow would be a nasty surprise.
 */
@Singleton
class SleepTimer @Inject constructor(
    private val logger: FileLogger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _remainingMs = MutableStateFlow<Long?>(null)

    /** Milliseconds left, or null when no timer is armed. */
    val remainingMs: StateFlow<Long?> = _remainingMs.asStateFlow()

    private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires once when the countdown reaches zero. */
    val expired: SharedFlow<Unit> = _expired.asSharedFlow()

    fun start(minutes: Int) {
        val total = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES) * 60_000L
        job?.cancel()
        logger.i("SleepTimer", "Temporizador armado: $minutes min")
        job = scope.launch {
            // Counting against a deadline rather than adding up delays, so a
            // busy device cannot stretch a 30-minute timer into 35.
            val deadline = System.currentTimeMillis() + total
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                _remainingMs.value = left
                delay(left.coerceAtMost(1_000L))
            }
            _remainingMs.value = null
            logger.i("SleepTimer", "Temporizador cumplido, cerrando")
            _expired.emit(Unit)
        }
    }

    fun cancel() {
        if (job != null) logger.i("SleepTimer", "Temporizador cancelado")
        job?.cancel()
        job = null
        _remainingMs.value = null
    }

    companion object {
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 12 * 60

        /** Offered as chips; anything else goes in the custom field. */
        val PRESETS = listOf(15, 30, 45, 60, 90, 120)
        const val DEFAULT_MINUTES = 60
    }
}
