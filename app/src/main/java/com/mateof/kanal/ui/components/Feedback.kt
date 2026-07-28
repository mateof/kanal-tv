package com.mateof.kanal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mateof.kanal.ui.theme.KanalColors

@Composable
fun LoadingState(message: String = "Cargando…", modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = KanalColors.Accent, strokeWidth = 3.dp)
        Spacer(Modifier.height(20.dp))
        Text(message, style = MaterialTheme.typography.titleMedium, color = KanalColors.OnSurfaceMuted)
    }
}

@Composable
fun MessageState(
    title: String,
    description: String = "",
    icon: ImageVector = Icons.Outlined.SearchOff,
    tint: androidx.compose.ui.graphics.Color = KanalColors.OnSurfaceFaint,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(18.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = KanalColors.OnBackground,
            textAlign = TextAlign.Center
        )
        if (description.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = KanalColors.OnSurfaceMuted,
                textAlign = TextAlign.Center
            )
        }
        if (action != null) {
            Spacer(Modifier.height(28.dp))
            action()
        }
    }
}

@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) = MessageState(
    title = "Algo ha fallado",
    description = message,
    icon = Icons.Outlined.ErrorOutline,
    tint = KanalColors.Error,
    modifier = modifier,
    action = action
)

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String = ""
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 4.dp, height = 22.dp)
                    .padding(end = 0.dp)
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(KanalColors.Accent, RoundedCornerShape(50))
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = KanalColors.OnBackground)
        }
        if (trailing.isNotBlank()) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = KanalColors.OnSurfaceFaint)
        }
    }
}

/** Determinate when [progress] is 0..1, indeterminate when negative. */
@Composable
fun StepProgress(label: String, progress: Float, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = KanalColors.OnSurfaceMuted)
        Spacer(Modifier.height(10.dp))
        if (progress in 0f..1f) {
            LinearProgressIndicator(
                progress = { progress },
                color = KanalColors.Accent,
                trackColor = KanalColors.SurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            )
        } else {
            LinearProgressIndicator(
                color = KanalColors.Accent,
                trackColor = KanalColors.SurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            )
        }
    }
}
