package com.mateof.kanal.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.reminders.Reminder
import com.mateof.kanal.reminders.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The reminders, for the screens that let one be set from the guide. */
@HiltViewModel
class RemindersViewModel @Inject constructor(
    prefs: AppPreferences,
    private val scheduler: ReminderScheduler
) : ViewModel() {

    val reminders: StateFlow<List<Reminder>> = prefs.reminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun isSet(channelId: String, title: String, startMillis: Long): Boolean =
        reminders.value.any {
            it.channelId == channelId && it.title == title && it.startMillis == startMillis
        }

    /** Sets it, or takes it away when it was already set. */
    fun toggle(channelId: String, channelName: String, title: String, startMillis: Long) {
        val reminder = Reminder(channelId, channelName, title, startMillis)
        viewModelScope.launch {
            if (isSet(channelId, title, startMillis)) scheduler.remove(reminder)
            else scheduler.add(reminder)
        }
    }
}
