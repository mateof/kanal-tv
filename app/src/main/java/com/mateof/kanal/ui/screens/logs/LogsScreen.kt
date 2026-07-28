package com.mateof.kanal.ui.screens.logs

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.core.log.LogLevel
import com.mateof.kanal.core.log.LogLine
import com.mateof.kanal.ui.components.ButtonTone
import com.mateof.kanal.ui.components.KanalButton
import com.mateof.kanal.ui.components.KanalChip
import com.mateof.kanal.ui.screenPadding
import com.mateof.kanal.ui.theme.KanalColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val logger: FileLogger
) : ViewModel() {

    val lines: StateFlow<List<LogLine>> = logger.recent

    private val _minLevel = MutableStateFlow(LogLevel.DEBUG)
    val minLevel: StateFlow<LogLevel> = _minLevel.asStateFlow()

    private val _exported = MutableStateFlow<File?>(null)
    val exported: StateFlow<File?> = _exported.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    val header: String get() = logger.header()

    fun setMinLevel(level: LogLevel) {
        _minLevel.value = level
    }

    fun export(onReady: (File) -> Unit = {}) = viewModelScope.launch {
        _status.value = "Exportando…"
        val file = logger.export()
        _exported.value = file
        _status.value = if (file != null) {
            onReady(file)
            "Guardado en ${file.absolutePath}"
        } else {
            "No se pudo exportar el registro."
        }
    }

    fun clear() = viewModelScope.launch {
        logger.clear()
        _status.value = "Registro borrado."
    }

    fun reloadFromDisk() = viewModelScope.launch {
        val text = logger.readAll(maxChars = 40_000)
        _status.value = "El fichero ocupa ${text.length} caracteres."
    }
}

@Composable
fun LogsScreen(onBack: () -> Unit) {
    val vm: LogsViewModel = hiltViewModel()
    val context = LocalContext.current
    val lines by vm.lines.collectAsStateWithLifecycle()
    val minLevel by vm.minLevel.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    BackHandler { onBack() }

    val visible = lines.filter { it.level.ordinal >= minLevel.ordinal }
    val listState = rememberLazyListState()

    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) listState.scrollToItem(visible.lastIndex)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = screenPadding, end = screenPadding, top = 32.dp, bottom = 28.dp)
    ) {
        Text("Registro", style = MaterialTheme.typography.headlineMedium, color = KanalColors.OnBackground)
        Spacer(Modifier.height(6.dp))
        Text(
            vm.header.trim().replace("\n", "   ·   "),
            style = MaterialTheme.typography.labelSmall,
            color = KanalColors.OnSurfaceFaint
        )

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LogLevel.entries.forEach { level ->
                KanalChip(
                    label = when (level) {
                        LogLevel.DEBUG -> "Todo"
                        LogLevel.INFO -> "Info"
                        LogLevel.WARN -> "Avisos"
                        LogLevel.ERROR -> "Errores"
                    },
                    selected = level == minLevel,
                    onClick = { vm.setMinLevel(level) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(KanalColors.BackgroundElevated)
                .padding(horizontal = 18.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (visible.isEmpty()) {
                item {
                    Text(
                        "No hay nada registrado todavía.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = KanalColors.OnSurfaceFaint
                    )
                }
            }
            items(visible) { line ->
                Text(
                    text = line.render(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = when (line.level) {
                        LogLevel.ERROR -> KanalColors.Error
                        LogLevel.WARN -> KanalColors.Warning
                        LogLevel.INFO -> KanalColors.OnSurfaceMuted
                        LogLevel.DEBUG -> KanalColors.OnSurfaceFaint
                    }
                )
            }
        }

        if (status.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(status, style = MaterialTheme.typography.labelMedium, color = KanalColors.Accent)
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KanalButton("Exportar a fichero", { vm.export() }, icon = Icons.Outlined.Download, tone = ButtonTone.Primary)
            KanalButton("Compartir", { vm.export { file -> shareLog(context, file) } }, icon = Icons.Outlined.Share)
            KanalButton("Recargar", vm::reloadFromDisk, icon = Icons.Outlined.Refresh)
            KanalButton("Borrar", vm::clear, icon = Icons.Outlined.Delete, tone = ButtonTone.Danger)
            KanalButton("Volver", onBack)
        }
    }
}

/**
 * On a TV there is often nothing to share to, so the export path is shown on
 * screen as well; the chooser is a convenience for phones and tablets.
 */
private fun shareLog(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Registro de Kanal")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir registro").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
