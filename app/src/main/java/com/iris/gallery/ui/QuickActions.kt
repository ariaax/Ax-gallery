package com.iris.gallery.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One shortcut above the photo grid: a round glass icon with a short label. */
class QuickAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/**
 * Evenly spaced row: every cell has the same width, so icons and labels line up and the labels
 * are centred under their icons. Same glass look as the rest of the app (dark tint + thin light rim).
 */
@Composable
fun QuickActionRow(actions: List<QuickAction>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        actions.forEach { action ->
            Column(
                Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = action.onClick,
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(54.dp).clip(CircleShape)
                        .background(Color(0xFF12151C).copy(alpha = 0.78f))
                        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(action.icon, action.label, tint = Color.White.copy(alpha = 0.92f), modifier = Modifier.size(24.dp))
                }
                Text(
                    action.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.padding(top = 6.dp).size(width = 64.dp, height = 16.dp),
                )
            }
        }
    }
}
