package com.mateof.kanal.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.kanal.core.AppLanguage
import com.mateof.kanal.core.InactivityWatcher
import com.mateof.kanal.core.SleepTimer
import com.mateof.kanal.data.prefs.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** State for [AppShell]: the chosen language and the two app-wide countdowns. */
@HiltViewModel
class ShellViewModel @Inject constructor(
    prefs: AppPreferences,
    private val sleepTimer: SleepTimer,
    private val inactivity: InactivityWatcher
) : ViewModel() {

    /** null while the stored choice is still being read. */
    val language: StateFlow<AppLanguage?> =
        prefs.language.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val sleepRemaining: StateFlow<Long?> = sleepTimer.remainingMs

    val stillWatchingSeconds: StateFlow<Int?> = inactivity.secondsToClose

    fun cancelSleep() = sleepTimer.cancel()

    fun confirmStillWatching() = inactivity.confirm()
}
