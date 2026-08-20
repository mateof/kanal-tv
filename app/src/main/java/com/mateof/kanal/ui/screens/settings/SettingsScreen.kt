package com.mateof.kanal.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Bedtime
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mateof.kanal.R
import com.mateof.kanal.core.AppLanguage
import com.mateof.kanal.core.SleepTimer
import com.mateof.kanal.core.formatDayAndClock
import com.mateof.kanal.core.formatDuration
import com.mateof.kanal.core.resolve
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.prefs.BufferProfile
import com.mateof.kanal.data.prefs.StreamFormat
import com.mateof.kanal.data.repo.SyncState
import com.mateof.kanal.data.prefs.ChannelSort
import com.mateof.kanal.data.prefs.SubtitleLook
import com.mateof.kanal.data.prefs.SubtitleSize
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
    val sleepRemaining by vm.sleepRemaining.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val liveCategories by vm.liveCategories.collectAsStateWithLifecycle()
    val hiddenChannels by vm.hiddenChannels.collectAsStateWithLifecycle()
    val hiddenCategories by vm.hiddenCategories.collectAsStateWithLifecycle()

    var userAgentDraft by remember(settings.userAgent) { mutableStateOf(settings.userAgent) }
    var sleepDraft by remember(settings.sleepTimerMinutes) {
        mutableStateOf(settings.sleepTimerMinutes.toString())
    }

    // Six screens' worth of settings in one column meant walking past all of
    // them to reach the last. They were already grouped; the groups are now the
    // way in, with the heading and whatever is running kept in sight above.
    val sections = listOf(
        stringResource(R.string.language),
        stringResource(R.string.settings_sources),
        stringResource(R.string.settings_playback),
        stringResource(R.string.settings_guide_content),
        stringResource(R.string.settings_saving),
        stringResource(R.string.settings_app)
    )
    var section by rememberSaveable { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(
                start = contentInset,
                end = if (isCompact) contentInset else 80.dp,
                top = 32.dp
            )
        ) {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = KanalColors.OnBackground
            )
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(sections) { index, label ->
                    KanalChip(
                        label = label,
                        selected = index == section,
                        onClick = { section = index }
                    )
                }
            }
            if (busy) {
                Spacer(Modifier.height(12.dp))
                val running = syncState as? SyncState.Running
                val label = running?.step?.resolve() ?: stringResource(R.string.common_working)
                StepProgress(label, running?.progress ?: -1f)
            }
            message?.let { text ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KanalColors.Accent
                )
            }
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = contentInset,
            end = if (isCompact) contentInset else 80.dp,
            top = 20.dp,
            bottom = 70.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        if (section == 0) {
        // --- Language -------------------------------------------------------
        // First on the list on purpose: somebody who opened Kanal in a language
        // they cannot read has to find this without reading anything else.
        item {
            OptionRow(
                title = stringResource(R.string.language),
                description = stringResource(R.string.language_description),
                options = AppLanguage.entries.map { stringResource(it.labelRes) },
                selectedIndex = AppLanguage.entries.indexOf(settings.language),
                onSelect = { vm.setLanguage(AppLanguage.entries[it]) }
            )
        }

        }

        if (section == 1) {
        // --- Sources --------------------------------------------------------
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KanalButton(
                    stringResource(R.string.settings_add_source),
                    onAddSource,
                    icon = Icons.Outlined.Add,
                    tone = ButtonTone.Primary
                )
                KanalButton(
                    stringResource(R.string.settings_sync_all),
                    { vm.syncNow(epgOnly = false) },
                    icon = Icons.Outlined.Refresh,
                    enabled = !busy
                )
                KanalButton(
                    stringResource(R.string.settings_sync_guide_only),
                    { vm.syncNow(epgOnly = true) },
                    enabled = !busy
                )
            }
        }

        item {
            Column {
                Text(
                    stringResource(R.string.settings_account),
                    style = MaterialTheme.typography.titleSmall,
                    color = KanalColors.OnBackground
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    account?.let {
                        stringResource(
                            R.string.settings_account_slots,
                            it.activeConnections,
                            it.maxConnections
                        )
                    } ?: stringResource(R.string.settings_account_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (account?.full == true) KanalColors.Warning else KanalColors.OnSurfaceMuted
                )
                Spacer(Modifier.height(12.dp))
                KanalButton(stringResource(R.string.settings_account_check), vm::checkAccount)
            }
        }
        }

        if (section == 2) {
        // --- Playback -------------------------------------------------------
        item {
            OptionRow(
                title = stringResource(R.string.settings_stream_format),
                description = stringResource(R.string.settings_stream_format_desc),
                options = StreamFormat.entries.map { stringResource(it.labelRes) },
                selectedIndex = StreamFormat.entries.indexOf(settings.streamFormat),
                onSelect = { vm.setStreamFormat(StreamFormat.entries[it]) }
            )
        }
        item {
            OptionRow(
                title = stringResource(R.string.settings_buffer),
                description = stringResource(R.string.settings_buffer_desc),
                options = BufferProfile.entries.map { stringResource(it.labelRes) },
                selectedIndex = BufferProfile.entries.indexOf(settings.bufferProfile),
                onSelect = { vm.setBufferProfile(BufferProfile.entries[it]) }
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_preview),
                description = stringResource(R.string.settings_preview_desc),
                checked = settings.previewEnabled,
                onCheckedChange = vm::setPreview
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_resilient),
                description = stringResource(R.string.settings_resilient_desc),
                checked = settings.resilientPlayback,
                onCheckedChange = vm::setResilient
            )
        }
        item {
            OptionRow(
                title = stringResource(R.string.settings_subtitle_size),
                description = stringResource(R.string.settings_subtitle_size_desc),
                options = SubtitleSize.entries.map { stringResource(it.labelRes) },
                selectedIndex = SubtitleSize.entries.indexOf(settings.subtitleSize),
                onSelect = { vm.setSubtitleSize(SubtitleSize.entries[it]) }
            )
        }
        item {
            OptionRow(
                title = stringResource(R.string.settings_subtitle_look),
                description = stringResource(R.string.settings_subtitle_look_desc),
                options = SubtitleLook.entries.map { stringResource(it.labelRes) },
                selectedIndex = SubtitleLook.entries.indexOf(settings.subtitleLook),
                onSelect = { vm.setSubtitleLook(SubtitleLook.entries[it]) }
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_keep_channel),
                description = stringResource(R.string.settings_keep_channel_desc),
                checked = settings.keepLastChannel,
                onCheckedChange = vm::setKeepLastChannel
            )
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KanalTextField(
                    value = userAgentDraft,
                    onValueChange = { userAgentDraft = it },
                    label = stringResource(R.string.settings_user_agent),
                    supportingText = stringResource(R.string.settings_user_agent_desc),
                    modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier.width(560.dp)
                )
                KanalButton(stringResource(R.string.common_save), { vm.setUserAgent(userAgentDraft) })
            }
        }

        }

        if (section == 3) {
        // --- Guide and content ----------------------------------------------
        item {
            OptionRow(
                title = stringResource(R.string.settings_epg_days),
                description = stringResource(R.string.settings_epg_days_desc),
                options = EPG_DAYS.map { it.toString() },
                selectedIndex = EPG_DAYS.indexOf(settings.epgDaysAhead).coerceAtLeast(0),
                onSelect = { vm.setEpgDays(EPG_DAYS[it]) }
            )
        }
        item {
            val never = stringResource(R.string.settings_never)
            OptionRow(
                title = stringResource(R.string.settings_auto_sync),
                description = stringResource(R.string.settings_auto_sync_desc),
                options = SYNC_HOURS.map { hours ->
                    if (hours == 0) never else stringResource(R.string.settings_hours, hours)
                },
                selectedIndex = SYNC_HOURS.indexOf(settings.autoSyncHours).coerceAtLeast(0),
                onSelect = { vm.setAutoSyncHours(SYNC_HOURS[it]) }
            )
        }
        item {
            OptionRow(
                title = stringResource(R.string.settings_channel_sort),
                description = stringResource(R.string.settings_channel_sort_desc),
                options = ChannelSort.entries.map { stringResource(it.labelRes) },
                selectedIndex = ChannelSort.entries.indexOf(settings.channelSort),
                onSelect = { vm.setChannelSort(ChannelSort.entries[it]) }
            )
        }
        item {
            Column {
                Text(
                    stringResource(R.string.settings_categories_hide),
                    style = MaterialTheme.typography.titleSmall,
                    color = KanalColors.OnBackground
                )
                Text(
                    stringResource(R.string.settings_categories_hide_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = KanalColors.OnSurfaceFaint
                )
                Spacer(Modifier.height(10.dp))
                val prefix = activeId.orEmpty() + ":"
                liveCategories.forEach { category ->
                    SettingSwitchRow(
                        title = category.name,
                        description = "",
                        checked = !hiddenCategories.contains(prefix + category.categoryId),
                        onCheckedChange = { vm.toggleCategory(category.categoryId) }
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        R.string.settings_hidden_count,
                        hiddenChannels.size,
                        hiddenCategories.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = KanalColors.OnSurfaceMuted
                )
                Spacer(Modifier.height(10.dp))
                KanalButton(stringResource(R.string.settings_show_all), vm::showEverything)
            }
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_fill_logos),
                description = stringResource(R.string.settings_fill_logos_desc),
                checked = settings.fillMissingLogos,
                onCheckedChange = vm::setFillMissingLogos
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_hide_adult),
                description = stringResource(R.string.settings_hide_adult_desc),
                checked = settings.hideAdult,
                onCheckedChange = vm::setHideAdult
            )
        }

        }

        if (section == 4) {
        // --- Shutdown and saving --------------------------------------------
        item {
            Column {
                OptionRow(
                    title = stringResource(R.string.settings_sleep_timer),
                    description = stringResource(R.string.settings_sleep_timer_desc),
                    options = SleepTimer.PRESETS.map { stringResource(R.string.settings_sleep_minutes, it) },
                    selectedIndex = SleepTimer.PRESETS.indexOf(settings.sleepTimerMinutes),
                    onSelect = {
                        val minutes = SleepTimer.PRESETS[it]
                        sleepDraft = minutes.toString()
                        vm.setSleepMinutes(minutes)
                    }
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KanalTextField(
                        value = sleepDraft,
                        onValueChange = { typed ->
                            sleepDraft = typed.filter { it.isDigit() }.take(3)
                            sleepDraft.toIntOrNull()?.let(vm::setSleepMinutes)
                        },
                        label = stringResource(R.string.settings_sleep_custom),
                        supportingText = stringResource(R.string.settings_sleep_custom_hint),
                        keyboardType = KeyboardType.Number,
                        modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier.width(280.dp)
                    )
                    if (sleepRemaining == null) {
                        KanalButton(
                            text = stringResource(R.string.settings_sleep_start),
                            onClick = vm::startSleepTimer,
                            icon = Icons.Outlined.Bedtime,
                            tone = ButtonTone.Primary
                        )
                    } else {
                        KanalButton(
                            text = stringResource(R.string.settings_sleep_cancel),
                            onClick = vm::cancelSleepTimer,
                            tone = ButtonTone.Danger
                        )
                    }
                }
                sleepRemaining?.let { left ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_sleep_running, formatDuration(left)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = KanalColors.Accent
                    )
                }
            }
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_still_watching),
                description = stringResource(R.string.settings_still_watching_desc),
                checked = settings.stillWatching,
                onCheckedChange = vm::setStillWatching
            )
        }

        }

        if (section == 5) {
        // --- App ------------------------------------------------------------
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_auto_update),
                description = stringResource(R.string.settings_auto_update_desc),
                checked = settings.autoUpdate,
                onCheckedChange = vm::setAutoUpdate
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.settings_verbose_http),
                description = stringResource(R.string.settings_verbose_http_desc),
                checked = settings.verboseHttpLog,
                onCheckedChange = vm::setVerboseHttp
            )
        }
        item {
            Column {
                Text(
                    stringResource(R.string.settings_version, updateVm.currentVersion) +
                        updateState.available
                            ?.let { " · " + stringResource(R.string.settings_version_available, it.versionName) }
                            .orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KanalColors.OnSurfaceMuted
                )
                updateState.message?.let { text ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text.resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = KanalColors.OnSurfaceFaint
                    )
                }
                if (updateState.downloading) {
                    Spacer(Modifier.height(10.dp))
                    StepProgress(
                        stringResource(R.string.settings_downloading, updateState.progress),
                        updateState.progress / 100f
                    )
                }
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KanalButton(
                        text = if (updateState.checking) {
                            stringResource(R.string.settings_checking)
                        } else {
                            stringResource(R.string.settings_check_updates)
                        },
                        onClick = updateVm::check,
                        icon = Icons.Outlined.SystemUpdateAlt,
                        enabled = !updateState.checking && !updateState.downloading
                    )
                    if (updateState.available != null) {
                        KanalButton(
                            text = stringResource(R.string.settings_download_install),
                            onClick = updateVm::downloadAndInstall,
                            tone = ButtonTone.Primary,
                            enabled = !updateState.downloading
                        )
                    }
                    KanalButton(stringResource(R.string.settings_view_log), onOpenLogs, icon = Icons.Outlined.Article)
                }
            }
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KanalButton(stringResource(R.string.settings_clear_history), vm::clearHistory)
                KanalButton(
                    stringResource(R.string.settings_clear_cache),
                    vm::clearCache,
                    tone = ButtonTone.Danger,
                    enabled = !busy
                )
            }
        }
        }
    }
    }
}

private val EPG_DAYS = listOf(1, 2, 3, 5, 7)
private val SYNC_HOURS = listOf(0, 6, 12, 24, 48)

@Composable
private fun SourceRow(
    source: Source,
    isActive: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val activeSuffix = stringResource(R.string.settings_active)
    val syncedLabel = if (source.lastSyncAt > 0) {
        stringResource(R.string.settings_last_sync, formatDayAndClock(source.lastSyncAt))
    } else {
        stringResource(R.string.settings_never_synced)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (isActive) "${source.name}  ·  $activeSuffix" else source.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (isActive) KanalColors.Accent else KanalColors.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${source.type.name} · $syncedLabel",
                style = MaterialTheme.typography.labelSmall,
                color = KanalColors.OnSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!isActive) KanalButton(stringResource(R.string.settings_use), onActivate)
            KanalButton(stringResource(R.string.settings_edit), onEdit, icon = Icons.Outlined.Edit)
            KanalButton(
                text = if (confirmDelete) {
                    stringResource(R.string.common_sure)
                } else {
                    stringResource(R.string.settings_delete)
                },
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
        // Wrapping, not a plain Row: six sleep-timer presets do not fit across a
        // phone and the ones past the edge cannot be reached at all.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            options.forEachIndexed { index, label ->
                KanalChip(label = label, selected = index == selectedIndex, onClick = { onSelect(index) })
            }
        }
    }
}
