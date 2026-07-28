package com.mateof.kanal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mateof.kanal.ui.theme.KanalColors

/** Poster tile for films and series. */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    rating: Double = 0.0,
    progress: Float = 0f,
    width: Dp = 168.dp,
    onClick: () -> Unit,
    onFocusState: (Boolean) -> Unit = {}
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.width(width),
        color = KanalColors.BackgroundElevated,
        focusedColor = KanalColors.BackgroundElevated,
        onFocusState = onFocusState
    ) { focused ->
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
            ) {
                ArtworkImage(imageUrl, title, Icons.Outlined.Movie)

                if (rating > 0) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xCC05070C))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = KanalColors.Warning,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = String.format(java.util.Locale.getDefault(), "%.1f", rating),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }

                if (progress > 0f) {
                    ThinProgress(
                        progress = progress,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                    )
                }
            }

            Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (focused) KanalColors.Accent else KanalColors.OnBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = KanalColors.OnSurfaceFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Landscape tile for a live channel, with what is on air right now. */
@Composable
fun ChannelCard(
    name: String,
    logoUrl: String,
    modifier: Modifier = Modifier,
    number: Int = 0,
    nowTitle: String = "",
    nowProgress: Float = 0f,
    isFavorite: Boolean = false,
    width: Dp = 230.dp,
    onClick: () -> Unit,
    onFocusState: (Boolean) -> Unit = {}
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.width(width),
        color = KanalColors.Surface,
        focusedColor = KanalColors.SurfaceVariant,
        onFocusState = onFocusState
    ) { focused ->
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(KanalColors.BackgroundElevated)
            ) {
                ArtworkImage(logoUrl, name, Icons.Outlined.LiveTv, ContentScale.Fit, padding = 18.dp)

                if (number > 0) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = KanalColors.OnSurfaceFaint,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    )
                }
                if (isFavorite) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Favorito",
                        tint = KanalColors.Warning,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(16.dp)
                    )
                }
                if (nowProgress > 0f) {
                    ThinProgress(
                        progress = nowProgress,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                    )
                }
            }

            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (focused) KanalColors.Accent else KanalColors.OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = nowTitle.ifBlank { "Sin guía" },
                    style = MaterialTheme.typography.labelSmall,
                    color = KanalColors.OnSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Wide row used by "continuar viendo". */
@Composable
fun ContinueCard(
    title: String,
    subtitle: String,
    imageUrl: String,
    progress: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onFocusState: (Boolean) -> Unit = {}
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.width(300.dp),
        color = KanalColors.Surface,
        focusedColor = KanalColors.SurfaceVariant,
        onFocusState = onFocusState
    ) { focused ->
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(KanalColors.BackgroundElevated)
            ) {
                ArtworkImage(imageUrl, title, Icons.Outlined.Movie)
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.5f to Color.Transparent,
                                1f to Color(0xE60A0E17)
                            )
                        )
                )
                ThinProgress(
                    progress = progress,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                )
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (focused) KanalColors.Accent else KanalColors.OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = KanalColors.OnSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ArtworkImage(
    url: String,
    label: String,
    fallbackIcon: ImageVector,
    contentScale: ContentScale = ContentScale.Crop,
    padding: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    if (url.isBlank()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(KanalColors.SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    fallbackIcon,
                    contentDescription = null,
                    tint = KanalColors.OnSurfaceFaint,
                    modifier = Modifier.size(32.dp)
                )
                if (label.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = label.take(2).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = KanalColors.OnSurfaceFaint,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = label,
            contentScale = contentScale,
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

@Composable
fun ThinProgress(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(4.dp)
            .background(Color(0x66000000))
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .background(KanalColors.Accent)
        )
    }
}
