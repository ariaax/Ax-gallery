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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
// NOTE: the shortcut chips sit INSIDE the glass source (the pager), so they must not use real glass:
// a glass node that samples the layer it lives in crashes hwui (RenderNode prepareTree SIGSEGV).
class QuickAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/**
 * Evenly spaced row: every cell has the same width, so icons and labels line up and the labels
 * are centred under their icons. Same glass look as the rest of the app (dark tint + thin light rim).
 */
@Composable
fun QuickActionRow(actions: List<QuickAction>, modifier: Modifier = Modifier, fold: Float = 0f, enabled: Boolean = true) {
    if (fold > 0.998f) return
    val ink = Color(0xFF0A0E18)
    val rim = Color(0xFFBFD3FF)
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                // the drop: swells while the row is being drawn in, is gone again when fully folded
                val k = kotlin.math.sin(Math.PI.toFloat() * fold.coerceIn(0f, 1f))
                if (k > 0.02f) {
                    val r = 11.dp.toPx() * k
                    val cx = size.width / 2f
                    val y0 = 2.dp.toPx()
                    val path = Path().apply {
                        moveTo(cx, y0)
                        cubicTo(cx + r * 0.12f, y0 + r * 0.9f, cx + r, y0 + r * 1.1f, cx + r, y0 + r * 2.0f)
                        arcTo(androidx.compose.ui.geometry.Rect(cx - r, y0 + r, cx + r, y0 + r * 3f), 0f, 180f, false)
                        cubicTo(cx - r, y0 + r * 1.1f, cx - r * 0.12f, y0 + r * 0.9f, cx, y0)
                        close()
                    }
                    drawPath(path, ink)
                    drawPath(path, rim.copy(alpha = 0.7f * k), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
            },
    ) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 0f)
                val sc = 1f - 0.94f * fold
                scaleX = sc
                scaleY = sc
                translationY = -size.height * 0.10f * fold
                alpha = (1f - (fold - 0.35f).coerceAtLeast(0f) / 0.5f).coerceIn(0f, 1f) * (if (enabled) 1f else 0.35f)
            },
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        actions.forEach { action ->
            Column(
                Modifier
                    .clip(androidx.compose.foundation.shape.CutCornerShape(18.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { if (enabled && fold < 0.5f) action.onClick() },
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
}
