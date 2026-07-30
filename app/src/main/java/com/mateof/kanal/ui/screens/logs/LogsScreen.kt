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
import androidx.compose.ui.res.stringResource
import com.mateof.kanal.R
import com.mateof.kanal.core.UiText
import com.mateof.kanal.core.resolve
import com.mateof.kanal.core.resolveOrEmpty
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

    private val _status = MutableStateFlow<UiText?>(null)
    val status: StateFlow<UiText?> = _status.asStateFlow()

    val header: String get() = logger.header()

    fun setMinLevel(level: LogLevel) {
        _minLevel.value = level
    }

    fun export(onReady: (File) -> Unit = {}) = viewModelScope.launch {
        _status.value = UiText(R.string.logs_exporting)
        val file = logger.export()
        _exported.value = file
        _status.value = if (file != null) {
            onReady(file)
            UiText(R.string.logs_saved, file.absolutePath)
        } else {
            UiText(R.string.logs_export_failed)
        }
    }

    fun clear() = viewModelScope.launch {
        logger.clear()
        _status.value = UiText(R.string.logs_cleared)
    }

    fun reloadFromDisk() = viewModelScope.launch {
        val text = logger.readAll(maxChars = 40_000)
        _status.value = UiText(R.string.logs_size, text.length)
    }
}

@Composable
fun LogsScreen(onBack: () -> Unit) {
    val vm: LogsViewModel = hiltViewModel()
    val context = LocalContext.current
    val lines by vm.lines.collectAsStateWithLifecycle()
    val minLevel by vm.minLevel.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val shareTitle = stringResource(R.string.logs_share_title)

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
        Text(stringResource(R.string.logs_title), style = MaterialTheme.typography.headlineMedium, color = KanalColors.OnBackground)
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
                        LogLevel.DEBUG -> stringResource(R.string.logs_all)
                        LogLevel.INFO -> stringResource(R.string.logs_info)
                        LogLevel.WARN -> stringResource(R.string.logs_warnings)
                        LogLevel.ERROR -> stringResource(R.string.logs_errors)
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
                        stringResource(R.string.logs_empty),
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

        status?.let { statusText ->
            Spacer(Modifier.height(10.dp))
            Text(statusText.resolve(), style = MaterialTheme.typography.labelMedium, color = KanalColors.Accent)
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KanalButton(stringResource(R.string.logs_export), { vm.export() }, icon = Icons.Outlined.Download, tone = ButtonTone.Primary)
            KanalButton(stringResource(R.string.logs_share), { vm.export { file -> shareLog(context, file, shareTitle, shareTitle) } }, icon = Icons.Outlined.Share)
            KanalButton(stringResource(R.string.logs_reload), vm::reloadFromDisk, icon = Icons.Outlined.Refresh)
            KanalButton(stringResource(R.string.logs_clear), vm::clear, icon = Icons.Outlined.Delete, tone = ButtonTone.Danger)
            KanalButton(stringResource(R.string.common_back), onBack)
        }
    }
}

/**
 * On a TV there is often nothing to share to, so the export path is shown on
 * screen as well; the chooser is a convenience for phones and tablets.
 */
private fun shareLog(context: Context, file: File, chooserTitle: String, subject: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
