package com.mateof.kanal.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mateof.kanal.core.formatDayAndClock
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.prefs.BufferProfile
import com.mateof.kanal.data.prefs.StreamFormat
import com.mateof.kanal.data.repo.SyncState
import com.mateof.kanal.ui.components.ButtonTone
import com.mateof.kanal.ui.components.KanalButton
import com.mateof.kanal.ui.components.KanalChip
import com.mateof.kanal.ui.components.KanalTextField
import com.mateof.kanal.ui.components.SectionHeader
import com.mateof.kanal.ui.components.SettingSwitchRow
import com.mateof.kanal.ui.components.StepProgress
import com.mateof.kanal.ui.components.UpdateViewModel
import com.mateof.kanal.ui.contentInset
import com.mateof.kanal.ui.isCompact
import com.mateof.kanal.ui.theme.KanalColors

@Composable
fun SettingsScreen(
    onAddSource: () -> Unit,
    onEditSource: (String) -> Unit,
    onOpenLogs: () -> Unit
) {
    val vm: SettingsViewModel = hiltViewModel()
    val updateVm: UpdateViewModel = hiltViewModel()

    val settings by vm.settings.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val activeId by vm.activeSourceId.collectAsStateWithLifecycle()
    val syncState by vm.syncState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val updateState by updateVm.state.collectAsStateWithLifecycle()

    var userAgentDraft by remember(settings.userAgent) { mutableStateOf(settings.userAgent) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = contentInset,
            end = if (isCompact) contentInset else 80.dp,
            top = 32.dp,
            bottom = 70.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Ajustes", style = MaterialTheme.typography.headlineMedium, color = KanalColors.OnBackground)
            Spacer(Modifier.height(4.dp))
        }

        if (busy) {
            item {
                val label = (syncState as? SyncState.Running)?.step ?: "Trabajando…"
                val progress = (syncState as? SyncState.Running)?.progress ?: -1f
                StepProgress(label, progress)
            }
        }
        if (message.isNotBlank()) {
            item {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = KanalColors.Accent)
            }
        }

        // --- Sources --------------------------------------------------------
        item { SectionHeader("Fuentes") }
        items(sources, key = { it.id }) { source ->
            SourceRow(
                source = source,
                isActive = source.id == activeId,
                onActivate = { vm.setActive(source.id) },
                onEdit = { onEditSource(source.id) },
                onDelete = { vm.deleteSource(source) }
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KanalButton("Añadir fuente", onAddSource, icon = Icons.Outlined.Add, tone = ButtonTone.Primary)
                KanalButton(
                    "Sincronizar todo",
                    { vm.syncNow(epgOnly = false) },
                    icon = Icons.Outlined.Refresh,
                    enabled = !busy
                )
                KanalButton("Actualizar sólo la guía", { vm.syncNow(epgOnly = true) }, enabled = !busy)
            }
        }

        // --- Playback -------------------------------------------------------
        item { Spacer(Modifier.height(10.dp)); SectionHeader("Reproducción") }
        item {
            OptionRow(
                title = "Formato de emisión",
                description = "MPEG-TS va mejor en la mayoría de paneles; HLS es más tolerante con redes lentas.",
                options = StreamFormat.entries.map { it.label },
                selectedIndex = StreamFormat.entries.indexOf(settings.streamFormat),
                onSelect = { vm.setStreamFormat(StreamFormat.entries[it]) }
            )
        }
        item {
            OptionRow(
                title = "Búfer",
                description = "Un búfer bajo cambia de canal antes; uno alto aguanta mejor los cortes.",
                options = BufferProfile.entries.map { it.label },
                selectedIndex = BufferProfile.entries.indexOf(settings.bufferProfile),
                onSelect = { vm.setBufferProfile(BufferProfile.entries[it]) }
            )
        }
        item {
            SettingSwitchRow(
                title = "Vista previa en la lista de canales",
                description = "Reproduce el canal enfocado en el panel de la derecha tras un momento.",
                checked = settings.previewEnabled,
                onCheckedChange = vm::setPreview
            )
        }
        item {
            SettingSwitchRow(
                title = "Aguantar cortes del servidor",
                description = "Si la emisión se corta, reconecta sola varias veces en lugar de dar error, " +
                    "insiste más ante fallos de red y decodifica el audio por software, que tolera mejor " +
                    "los paquetes dañados. Súbelo con el búfer al máximo si tu proveedor va justo.",
                checked = settings.resilientPlayback,
                onCheckedChange = vm::setResilient
            )
        }
        item {
            SettingSwitchRow(
                title = "Recordar el último canal",
                description = "Al salir de un canal con ATRÁS sigue sonando en la vista previa. " +
                    "Desde la lista, ATRÁS vuelve a ponerlo a pantalla completa; dos veces seguidas sale.",
                checked = settings.keepLastChannel,
                onCheckedChange = vm::setKeepLastChannel
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KanalTextField(
                    value = userAgentDraft,
                    onValueChange = { userAgentDraft = it },
                    label = "User-Agent por defecto",
                    supportingText = "Algunos proveedores bloquean agentes desconocidos.",
                    modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier.width(560.dp)
                )
                Spacer(Modifier.width(16.dp))
                KanalButton("Guardar", { vm.setUserAgent(userAgentDraft) })
            }
        }

        // --- Guide and content ----------------------------------------------
        item { Spacer(Modifier.height(10.dp)); SectionHeader("Guía y contenido") }
        item {
            OptionRow(
                title = "Días de guía a descargar",
                description = "Cuantos más días, más tarda la sincronización y más ocupa.",
                options = listOf("1", "2", "3", "5", "7"),
                selectedIndex = listOf(1, 2, 3, 5, 7).indexOf(settings.epgDaysAhead).coerceAtLeast(0),
                onSelect = { vm.setEpgDays(listOf(1, 2, 3, 5, 7)[it]) }
            )
        }
        item {
            OptionRow(
                title = "Sincronización automática",
                description = "Cada cuánto se refresca el catálogo al abrir la aplicación.",
                options = listOf("Nunca", "6 h", "12 h", "24 h", "48 h"),
                selectedIndex = listOf(0, 6, 12, 24, 48).indexOf(settings.autoSyncHours).coerceAtLeast(0),
                onSelect = { vm.setAutoSyncHours(listOf(0, 6, 12, 24, 48)[it]) }
            )
        }
        item {
            SettingSwitchRow(
                title = "Ocultar contenido para adultos",
                description = "Filtra las categorías marcadas como XXX o +18.",
                checked = settings.hideAdult,
                onCheckedChange = vm::setHideAdult
            )
        }

        // --- App ------------------------------------------------------------
        item { Spacer(Modifier.height(10.dp)); SectionHeader("Aplicación") }
        item {
            SettingSwitchRow(
                title = "Buscar actualizaciones automáticamente",
                description = "Comprueba las releases de GitHub al abrir la aplicación.",
                checked = settings.autoUpdate,
                onCheckedChange = vm::setAutoUpdate
            )
        }
        item {
            SettingSwitchRow(
                title = "Registro detallado de red",
                description = "Anota cada petición HTTP. Útil para diagnosticar, ruidoso para el día a día.",
                checked = settings.verboseHttpLog,
                onCheckedChange = vm::setVerboseHttp
            )
        }
        item {
            Column {
                Text(
                    "Versión instalada: ${updateVm.currentVersion}" +
                        updateState.available?.let { " · disponible ${it.versionName}" }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KanalColors.OnSurfaceMuted
                )
                if (updateState.message.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        updateState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = KanalColors.OnSurfaceFaint
                    )
                }
                if (updateState.downloading) {
                    Spacer(Modifier.height(10.dp))
                    StepProgress("Descargando… ${updateState.progress}%", updateState.progress / 100f)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KanalButton(
                        text = if (updateState.checking) "Comprobando…" else "Buscar actualizaciones",
                        onClick = updateVm::check,
                        icon = Icons.Outlined.SystemUpdateAlt,
                        enabled = !updateState.checking && !updateState.downloading
                    )
                    if (updateState.available != null) {
                        KanalButton(
                            text = "Descargar e instalar",
                            onClick = updateVm::downloadAndInstall,
                            tone = ButtonTone.Primary,
                            enabled = !updateState.downloading
                        )
                    }
                    KanalButton("Ver registro", onOpenLogs, icon = Icons.Outlined.Article)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KanalButton("Borrar historial", vm::clearHistory)
                KanalButton("Vaciar caché de contenido", vm::clearCache, tone = ButtonTone.Danger, enabled = !busy)
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: Source,
    isActive: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                buildString {
                    append(source.name)
                    if (isActive) append("  ·  activa")
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (isActive) KanalColors.Accent else KanalColors.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(source.type.name)
                    append(" · ")
                    append(
                        if (source.lastSyncAt > 0) {
                            "última sincronización ${formatDayAndClock(source.lastSyncAt)}"
                        } else {
                            "sin sincronizar"
                        }
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = KanalColors.OnSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isActive) KanalButton("Usar", onActivate)
            KanalButton("Editar", onEdit, icon = Icons.Outlined.Edit)
            KanalButton(
                text = if (confirmDelete) "¿Seguro?" else "Eliminar",
                onClick = { if (confirmDelete) onDelete() else confirmDelete = true },
                icon = Icons.Outlined.Delete,
                tone = ButtonTone.Danger
            )
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    description: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = KanalColors.OnBackground)
        if (description.isNotBlank()) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = KanalColors.OnSurfaceFaint)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEachIndexed { index, label ->
                KanalChip(label = label, selected = index == selectedIndex, onClick = { onSelect(index) })
            }
        }
    }
}
