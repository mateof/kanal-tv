package com.mateof.kanal.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Kanal is a TV app first. [Compact] is a second layer for a phone held
 * upright: every screen keeps its wide layout untouched and only *adds* a
 * narrow variant, so nothing done for the phone can regress the television.
 */
enum class LayoutMode { Compact, Wide }

val LocalLayoutMode = staticCompositionLocalOf { LayoutMode.Wide }

/**
 * 700 dp splits a phone (360-430 upright, ~800 on its side) from a tablet in
 * landscape and a television (960+). A phone turned sideways lands in [Wide],
 * which is what we want: the TV layout fits there.
 */
@Composable
@ReadOnlyComposable
fun currentLayoutMode(): LayoutMode =
    if (LocalConfiguration.current.screenWidthDp < 700) LayoutMode.Compact else LayoutMode.Wide

val isCompact: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalLayoutMode.current == LayoutMode.Compact

/**
 * Whether this is a television rather than something held in the hands.
 *
 * Not the same question as [isCompact], which is about how much width there is:
 * a tablet in landscape is as wide as a television and needs the wide layout,
 * but it can still be turned over and has no fixed orientation to respect. Only
 * this tells the two apart.
 */
@Composable
fun isTelevision(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val leanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val uiMode = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
            ?.currentModeType
        leanback || uiMode == Configuration.UI_MODE_TYPE_TELEVISION
    }
}

/** Screen padding: 10-foot overscan on a TV, thumb margins on a phone. */
val screenPadding: Dp
    @Composable
    @ReadOnlyComposable
    get() = if (isCompact) 16.dp else 48.dp

/** Left inset for content that sits next to the rail on a TV. */
val contentInset: Dp
    @Composable
    @ReadOnlyComposable
    get() = if (isCompact) 16.dp else 40.dp
