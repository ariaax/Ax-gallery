package com.iris.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One shortcut above the photo grid. */
class QuickAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/**
 * Quiet shortcut chips: one slim row, icon + word, flat fill, no rim and no entrance animation. It simply fades out
 * (and takes no space, see the caller) once the grid is scrolled.
 */
@Composable
fun QuickActionRow(actions: List<QuickAction>, modifier: Modifier = Modifier, fold: Float = 0f, enabled: Boolean = true) {
    if (fold > 0.98f) return
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    val ink = MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = (1f - fold * 1.4f).coerceIn(0f, 1f) * (if (enabled) 1f else 0.35f) }
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            Row(
                Modifier
                    .clip(AxRound(14.dp))
                    .background(fill)
                    .clickable(enabled = enabled && fold < 0.5f, onClick = action.onClick)
                    .padding(start = 12.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(action.icon, null, tint = ink.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(action.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ink, maxLines = 1)
            }
        }
    }
}
