package com.mateof.kanal.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.mateof.kanal.ui.LocalLayoutMode
import com.mateof.kanal.ui.currentLayoutMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A 10-foot palette: near-black backgrounds so the panel does not glow in a
 * dark room, one bright accent that means "this has focus", and a violet
 * secondary reserved for progress and "on air" markers.
 */
object KanalColors {
    val Background = Color(0xFF07090F)
    val BackgroundElevated = Color(0xFF0C111C)
    val Surface = Color(0xFF141A28)
    val SurfaceVariant = Color(0xFF1D2538)
    val Outline = Color(0xFF2C3549)

    val Accent = Color(0xFF35E0C8)
    val AccentDim = Color(0xFF1E8C7E)
    val Secondary = Color(0xFF7C6BFF)
    val Live = Color(0xFFFF5470)

    val OnBackground = Color(0xFFEDF1F8)
    val OnSurfaceMuted = Color(0xFF95A0B5)
    val OnSurfaceFaint = Color(0xFF5D6880)

    val Error = Color(0xFFFF6B6B)
    val Warning = Color(0xFFFFB84D)
    val Scrim = Color(0xCC05070C)
}

private val KanalColorScheme = darkColorScheme(
    primary = KanalColors.Accent,
    onPrimary = Color(0xFF06231F),
    primaryContainer = KanalColors.AccentDim,
    onPrimaryContainer = Color(0xFFDFFFF9),
    secondary = KanalColors.Secondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2A2452),
    onSecondaryContainer = Color(0xFFE4E0FF),
    background = KanalColors.Background,
    onBackground = KanalColors.OnBackground,
    surface = KanalColors.Surface,
    onSurface = KanalColors.OnBackground,
    surfaceVariant = KanalColors.SurfaceVariant,
    onSurfaceVariant = KanalColors.OnSurfaceMuted,
    outline = KanalColors.Outline,
    outlineVariant = Color(0xFF212A3C),
    error = KanalColors.Error,
    onError = Color.White,
    scrim = KanalColors.Scrim
)

/** Text is a step larger than the phone defaults: it is read from the sofa. */
private val KanalTypography = Typography(
    displayLarge = TextStyle(fontSize = 52.sp, lineHeight = 60.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontSize = 42.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 27.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 23.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

/** Subtle glow so a full-screen dark background is not a flat void. */
val AppBackgroundBrush: Brush
    get() = Brush.linearGradient(
        0f to Color(0xFF111C2E),
        0.45f to KanalColors.Background,
        1f to Color(0xFF05070C)
    )

@Composable
fun KanalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KanalColorScheme, typography = KanalTypography) {
        CompositionLocalProvider(LocalLayoutMode provides currentLayoutMode()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(AppBackgroundBrush)
            ) {
                content()
            }
        }
    }
}

/** Standard 10-foot overscan padding. */
object Spacing {
    val screenHorizontal = 48.dp
    val screenVertical = 28.dp
    val section = 26.dp
    val item = 14.dp
}
