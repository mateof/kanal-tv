package com.mateof.kanal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mateof.kanal.ui.theme.KanalColors

enum class ButtonTone { Primary, Neutral, Danger }

@Composable
fun KanalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: ButtonTone = ButtonTone.Neutral,
    enabled: Boolean = true
) {
    val resting = when (tone) {
        ButtonTone.Primary -> KanalColors.AccentDim
        ButtonTone.Neutral -> KanalColors.SurfaceVariant
        ButtonTone.Danger -> Color(0xFF4A1E27)
    }
    val focusedBackground = when (tone) {
        ButtonTone.Primary -> KanalColors.Accent
        ButtonTone.Neutral -> KanalColors.Accent
        ButtonTone.Danger -> KanalColors.Error
    }

    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (enabled) resting else KanalColors.Surface,
        focusedColor = focusedBackground,
        focusedScale = 1.04f,
        restingBorderColor = KanalColors.Outline,
        enabled = enabled
    ) { focused ->
        val content = when {
            !enabled -> KanalColors.OnSurfaceFaint
            focused && tone == ButtonTone.Danger -> Color.White
            focused -> Color(0xFF06231F)
            else -> KanalColors.OnBackground
        }
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 22.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}
