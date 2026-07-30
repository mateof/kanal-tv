package com.mateof.kanal.ui.screens.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.kanal.R
import com.mateof.kanal.core.UiText
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.model.SourceType
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.repo.SyncRepository
import com.mateof.kanal.data.repo.SyncState
import com.mateof.kanal.data.xtream.XtreamClient
import com.mateof.kanal.data.xtream.XtreamUrls
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class SetupState(
    val isEditing: Boolean = false,
    val id: String = "",
    val type: SourceType = SourceType.XTREAM,
    val name: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val epgUrl: String = "",
    val userAgent: String = "",
    val busy: Boolean = false,
    val busyLabel: UiText? = null,
    val progress: Float = -1f,
    val message: UiText? = null,
    val messageIsError: Boolean = false,
    val finished: Boolean = false
) {
    val canSave: Boolean
        get() = url.isNotBlank() && name.isNotBlank() &&
            (type == SourceType.M3U || (username.isNotBlank() && password.isNotBlank()))
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val sync: SyncRepository,
    private val xtream: XtreamClient,
    private val logger: FileLogger
) : ViewModel() {

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private var loaded = false

    fun load(sourceId: String) {
        if (loaded) return
        loaded = true
        if (sourceId.isBlank()) return
        viewModelScope.launch {
            val source = prefs.sources.first().firstOrNull { it.id == sourceId } ?: return@launch
            _state.value = _state.value.copy(
                isEditing = true,
                id = source.id,
                type = source.type,
                name = source.name,
                url = source.url,
                username = source.username,
                password = source.password,
                epgUrl = source.epgUrl,
                userAgent = source.userAgent
            )
        }
    }

    fun setType(value: SourceType) = update { it.copy(type = value, message = null) }
    fun setName(value: String) = update { it.copy(name = value) }
    fun setUrl(value: String) = update { it.copy(url = value, message = null) }
    fun setUsername(value: String) = update { it.copy(username = value) }
    fun setPassword(value: String) = update { it.copy(password = value) }
    fun setEpgUrl(value: String) = update { it.copy(epgUrl = value) }
    fun setUserAgent(value: String) = update { it.copy(userAgent = value) }

    fun test() {
        val current = _state.value
        viewModelScope.launch {
            _state.value = current.copy(busy = true, busyLabel = UiText(R.string.setup_connecting), message = null)
            val source = current.toSource()
            val result = runCatching {
                if (source.type == SourceType.XTREAM) {
                    val account = xtream.authenticate(source)
                    if (account.maxConnections > 0) {
                        UiText(
                            R.string.setup_connected_full,
                            account.username,
                            account.status,
                            account.activeConnections,
                            account.maxConnections
                        )
                    } else {
                        UiText(R.string.setup_connected, account.username, account.status)
                    }
                } else {
                    UiText(R.string.setup_list_ok)
                }
            }
            _state.value = _state.value.copy(
                busy = false,
                message = result.getOrElse { failure ->
                    failure.message?.let { UiText(R.string.setup_connect_failed_detail, it) }
                        ?: UiText(R.string.setup_connect_failed)
                },
                messageIsError = result.isFailure
            )
        }
    }

    fun save() {
        val current = _state.value
        viewModelScope.launch {
            _state.value = current.copy(busy = true, busyLabel = UiText(R.string.setup_saving), message = null)
            val source = current.toSource()
            prefs.upsertSource(source)
            prefs.setActiveSource(source.id)
            logger.i("Setup", "Fuente '${source.name}' guardada (${source.type})")

            val result = sync.syncAll(source)
            _state.value = when (result) {
                is SyncState.Failed -> _state.value.copy(
                    busy = false,
                    message = result.message,
                    messageIsError = true
                )

                else -> _state.value.copy(busy = false, finished = true)
            }
        }
    }

    init {
        viewModelScope.launch {
            sync.state.collect { syncState ->
                if (syncState is SyncState.Running) {
                    _state.value = _state.value.copy(
                        busyLabel = syncState.step,
                        progress = syncState.progress
                    )
                }
            }
        }
    }

    private fun SetupState.toSource() = Source(
        id = id.ifBlank { UUID.randomUUID().toString() },
        name = name.trim(),
        type = type,
        url = if (type == SourceType.XTREAM) XtreamUrls.normalizeBase(url) else url.trim(),
        username = username.trim(),
        password = password.trim(),
        epgUrl = epgUrl.trim(),
        userAgent = userAgent.trim(),
        createdAt = if (isEditing) 0L else System.currentTimeMillis()
    )

    private fun update(block: (SetupState) -> SetupState) {
        _state.value = block(_state.value)
    }
}
