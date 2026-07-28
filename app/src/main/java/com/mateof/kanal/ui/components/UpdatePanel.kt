package com.mateof.kanal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.kanal.core.formatBytes
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.update.AppUpdater
import com.mateof.kanal.update.UpdateCheck
import com.mateof.kanal.update.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import com.mateof.kanal.ui.theme.KanalColors

data class UpdateUiState(
    val available: UpdateInfo? = null,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val message: String = "",
    val needsPermission: Boolean = false
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updater: AppUpdater,
    private val prefs: AppPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    val currentVersion: String get() = updater.currentVersion

    /** Called on the home screen; respects the setting and throttles to 6 h. */
    fun checkAutomatically() {
        viewModelScope.launch {
            if (!prefs.settings.first().autoUpdate) return@launch
            val last = prefs.lastUpdateCheck.first()
            if (System.currentTimeMillis() - last < 6 * 3_600_000L) return@launch
            prefs.setLastUpdateCheck(System.currentTimeMillis())
            runCheck(silent = true)
        }
    }

    fun check() = viewModelScope.launch { runCheck(silent = false) }

    private suspend fun runCheck(silent: Boolean) {
        _state.value = _state.value.copy(checking = true, message = "")
        when (val result = updater.check()) {
            is UpdateCheck.Available -> _state.value = UpdateUiState(available = result.info)
            UpdateCheck.UpToDate -> _state.value = UpdateUiState(
                message = if (silent) "" else "Ya tienes la última versión (${updater.currentVersion})."
            )

            is UpdateCheck.Error -> _state.value = UpdateUiState(
                message = if (silent) "" else result.message
            )
        }
    }

    fun downloadAndInstall() {
        val info = _state.value.available ?: return
        viewModelScope.launch {
            if (!updater.canInstall()) {
                _state.value = _state.value.copy(
                    needsPermission = true,
                    message = "Autoriza a Kanal a instalar aplicaciones y vuelve a intentarlo."
                )
                updater.requestInstallPermission()
                return@launch
            }
            _state.value = _state.value.copy(downloading = true, progress = 0, message = "")
            val file = updater.download(info) { percent ->
                _state.value = _state.value.copy(progress = percent.coerceAtLeast(0))
            }
            if (file == null) {
                _state.value = _state.value.copy(
                    downloading = false,
                    message = "No se pudo descargar la actualización."
                )
            } else {
                _state.value = _state.value.copy(downloading = false, message = "Instalando…")
                updater.install(file)
            }
        }
    }

    fun dismiss() {
        _state.value = _state.value.copy(available = null, message = "")
    }
}

/** Compact banner shown at the top of the home screen when there is a new release. */
@Composable
fun UpdateBanner(
    state: UpdateUiState,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val info = state.available ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF14243A))
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.SystemUpdateAlt,
            contentDescription = null,
            tint = KanalColors.Accent,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Kanal ${info.versionName} disponible",
                style = MaterialTheme.typography.titleSmall,
                color = KanalColors.OnBackground
            )
            Text(
                if (state.downloading) "Descargando… ${state.progress}%"
                else "APK de ${formatBytes(info.apkSize)}",
                style = MaterialTheme.typography.bodySmall,
                color = KanalColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KanalButton(
                text = if (state.downloading) "Descargando…" else "Actualizar",
                onClick = onUpdate,
                tone = ButtonTone.Primary,
                enabled = !state.downloading
            )
            KanalButton(text = "Ahora no", onClick = onDismiss, enabled = !state.downloading)
        }
    }
    Spacer(Modifier.height(20.dp))
}
