package com.iris.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One shortcut above the photo grid: a round icon with a short label. */
class QuickAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

@Composable
fun QuickActionRow(actions: List<QuickAction>, modifier: Modifier = Modifier) {
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(actions, key = { it.label }) { action ->
            Column(
                Modifier.clickable(onClick = action.onClick),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(action.icon, action.label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                }
                Text(
                    action.label,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp).size(width = 64.dp, height = 16.dp),
                )
            }
        }
    }
}
