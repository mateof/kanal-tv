package com.mateof.kanal.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mateof.kanal.R
import com.mateof.kanal.ui.theme.KanalColors

data class NavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

/**
 * Left rail. It stays as a strip of icons and only widens to show labels while
 * something inside it has focus, so the content never has to shift for a menu
 * nobody is using.
 */
@Composable
fun NavRail(
    items: List<NavItem>,
    selectedRoute: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var focusedCount by remember { mutableIntStateOf(0) }
    val expanded = focusedCount > 0
    val width by animateDpAsState(
        targetValue = if (expanded) 248.dp else 92.dp,
        animationSpec = tween(220),
        label = "railWidth"
    )
    val background by animateColorAsState(
        targetValue = if (expanded) KanalColors.BackgroundElevated else Color.Transparent,
        animationSpec = tween(220),
        label = "railBackground"
    )

    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(
                Brush.horizontalGradient(0f to background, 1f to Color.Transparent)
            )
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 26.dp, bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_kanal_mark),
                contentDescription = "Kanal",
                tint = Color.Unspecified,
                modifier = Modifier.size(40.dp)
            )
            if (expanded) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "Kanal",
                    style = MaterialTheme.typography.titleLarge,
                    color = KanalColors.OnBackground,
                    maxLines = 1
                )
            }
        }

        items.forEach { item ->
            NavRailItem(
                item = item,
                selected = item.route == selectedRoute,
                expanded = expanded,
                onClick = { onSelect(item.route) },
                onFocusState = { focused -> focusedCount = (focusedCount + if (focused) 1 else -1).coerceAtLeast(0) }
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun NavRailItem(
    item: NavItem,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onFocusState: (Boolean) -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
    ) {
        FocusableSurface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = if (selected) KanalColors.SurfaceVariant else Color.Transparent,
            focusedColor = KanalColors.Accent,
            restingBorderColor = Color.Transparent,
            focusedScale = 1.0f,
            onFocusState = onFocusState
        ) { isFocused ->
            val tint = when {
                isFocused -> Color(0xFF06231F)
                selected -> KanalColors.Accent
                else -> KanalColors.OnSurfaceMuted
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(26.dp))
                if (expanded) {
                    Spacer(Modifier.width(16.dp))
                    Text(
                        item.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Phone-upright counterpart of [NavRail]. Icons only: seven destinations with
 * labels do not fit across 360 dp, and on a phone the icon plus the highlighted
 * state is enough.
 */
@Composable
fun BottomNav(
    items: List<NavItem>,
    selectedRoute: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(KanalColors.BackgroundElevated)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { item ->
            val selected = item.route == selectedRoute
            FocusableSurface(
                onClick = { onSelect(item.route) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                focusedColor = KanalColors.Accent,
                focusedScale = 1.0f
            ) { isFocused ->
                val tint = when {
                    isFocused -> Color(0xFF06231F)
                    selected -> KanalColors.Accent
                    else -> KanalColors.OnSurfaceFaint
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier.size(22.dp)
                    )
                    if (selected) {
                        Spacer(Modifier.height(3.dp))
                        Box(
                            Modifier
                                .size(width = 14.dp, height = 2.dp)
                                .background(tint, RoundedCornerShape(50))
                        )
                    }
                }
            }
        }
    }
}

/** Left-edge scrim that keeps the rail readable over bright artwork. */
@Composable
fun RailScrim(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(200.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(0.dp))
            .background(
                Brush.horizontalGradient(
                    0f to KanalColors.Background,
                    1f to Color.Transparent
                )
            )
    )
}
