package com.mateof.kanal.ui

import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mateof.kanal.R
import com.mateof.kanal.core.AppLanguage
import com.mateof.kanal.core.locale
import com.mateof.kanal.core.setFormatLocale
import com.mateof.kanal.ui.components.ButtonTone
import com.mateof.kanal.ui.components.KanalButton
import com.mateof.kanal.ui.theme.KanalColors
import kotlinx.coroutines.delay

/**
 * Wraps the whole interface: settles which language everything renders in, and
 * hosts the two things that must outlive any single screen — the sleep timer's
 * last-minute warning and the "are you still watching?" question.
 */
@Composable
fun AppShell(content: @Composable () -> Unit) {
    val vm: ShellViewModel = hiltViewModel()
    val language by vm.language.collectAsStateWithLifecycle()

    // Painting in the wrong language for one frame and correcting it looks like
    // a glitch, so hold the background until the stored choice has been read.
    val chosen = language
    if (chosen == null) {
        Box(Modifier.fillMaxSize().background(KanalColors.Background))
        return
    }

    WithLanguage(chosen) {
        Box(Modifier.fillMaxSize()) {
            content()

            val sleepRemaining by vm.sleepRemaining.collectAsStateWithLifecycle()
            sleepRemaining?.takeIf { it <= SLEEP_WARNING_MS }?.let { left ->
                SleepWarning(left, vm::cancelSleep, Modifier.align(Alignment.TopCenter))
            }

            val seconds by vm.stillWatchingSeconds.collectAsStateWithLifecycle()
            seconds?.let { StillWatching(it, vm::confirmStillWatching) }
        }
    }
}

/**
 * Re-resolves every string against [language] by handing the composition a
 * context configured for it. Cheaper and less jarring than recreating the
 * activity, and it means the change lands the instant the setting is touched.
 */
@Composable
private fun WithLanguage(language: AppLanguage, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val locale = remember(language) { language.locale() }

    // Derived from LocalConfiguration, and keyed on it, so turning the phone
    // rebuilds the context. Reading base.resources.configuration once instead
    // would freeze the screen size at whatever it was on first composition, and
    // the upright phone layout would never come back.
    val baseConfig = LocalConfiguration.current
    val localizedConfig = remember(locale, baseConfig) {
        Configuration(baseConfig).apply { setLocale(locale) }
    }

    // A ContextWrapper around the activity, *not* the context that
    // createConfigurationContext returns on its own. That one is a fresh
    // ContextImpl with no activity behind it, and hiltViewModel() walks the
    // wrapper chain looking for the activity — handing it a bare ContextImpl
    // crashes the app on the first screen that asks for a view model.
    val localized = remember(localizedConfig, base) {
        val localizedResources = base.createConfigurationContext(localizedConfig).resources
        object : ContextWrapper(base) {
            override fun getResources(): Resources = localizedResources
        }
    }

    // Dates are formatted outside composition too, so the locale has to be
    // published somewhere the plain functions in Format.kt can see it.
    SideEffect { setFormatLocale(locale) }

    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localizedConfig,
        content = content
    )
}

/** Discreet notice that the app is about to close, with a way out. */
@Composable
private fun SleepWarning(remainingMs: Long, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val seconds = ((remainingMs + 999) / 1_000).toInt()
    Row(
        modifier = modifier
            .padding(top = 24.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xE0161F33))
            .padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.sleep_closing_in, seconds),
            style = MaterialTheme.typography.labelLarge,
            color = KanalColors.OnBackground
        )
        KanalButton(
            text = stringResource(R.string.sleep_keep_watching),
            onClick = onCancel,
            modifier = Modifier.padding(start = 14.dp)
        )
    }
}

/**
 * Asks whether anyone is still there. Any key on the remote already counts as an
 * answer — the watcher sees it — so this only has to offer the obvious button
 * and show how long is left.
 */
@Composable
private fun StillWatching(secondsLeft: Int, onConfirm: () -> Unit) {
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        repeat(12) {
            if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE0040609))
            // pointerInput, never clickable: a full-screen clickable becomes a
            // focus target and swallows the arrows the button needs.
            .pointerInput(Unit) { detectTapGestures { onConfirm() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (isCompact) 460.dp else 560.dp)
                .fillMaxWidth(if (isCompact) 0.92f else 0.5f)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xFF16203A),
                        1f to Color(0xFF0B1120)
                    )
                )
                .padding(if (isCompact) 24.dp else 34.dp)
                .focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.still_watching_title),
                style = MaterialTheme.typography.headlineSmall,
                color = KanalColors.OnBackground
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.still_watching_body, secondsLeft),
                style = MaterialTheme.typography.bodyMedium,
                color = KanalColors.OnSurfaceMuted
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KanalButton(
                    text = stringResource(R.string.still_watching_yes),
                    onClick = onConfirm,
                    icon = Icons.Outlined.Check,
                    tone = ButtonTone.Primary,
                    modifier = Modifier.focusRequester(focus)
                )
            }
        }
    }
}

private const val SLEEP_WARNING_MS = 60_000L
