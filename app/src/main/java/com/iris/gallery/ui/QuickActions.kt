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
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One shortcut above the photo grid: a cut-corner chrome-rimmed icon with a short label. */
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
                    .clip(androidx.compose.foundation.shape.CutCornerShape(18.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = action.onClick,
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val chip = CutCornerShape(14.dp)
                Box(
                    Modifier.size(54.dp).clip(chip)
                        .background(Brush.verticalGradient(listOf(Color(0xFF151B2B).copy(alpha = 0.86f), Color(0xFF05070D).copy(alpha = 0.92f))))
                        .border(
                            BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color(0xFF4E8DFF).copy(alpha = 0.18f)))),
                            chip,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(action.icon, action.label, tint = Color(0xFFEAF1FF), modifier = Modifier.size(24.dp))
                }
                Text(
                    action.label.uppercase(),
                    fontSize = 10.5.sp,
                    letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                    color = Color(0xFFCBD6EE).copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 6.dp).size(width = 64.dp, height = 16.dp),
                )
            }
        }
    }
}
