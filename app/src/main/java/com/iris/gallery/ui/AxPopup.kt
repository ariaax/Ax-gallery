package com.iris.gallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width as w2
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

class AxMenuEntry(
    val icon: ImageVector,
    val text: String,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Telegram-style popup menu (ActionBarPopupWindow behaviour):
 *  - grows from its top-right corner: the background opens vertically 0→1 while fading in,
 *  - items cascade in (fade + 6dp rise), total 150 + 16·N ms,
 *  - dismiss: scale to 0.8 + fade out in 150 ms.
 * Place it inside the Box that holds the anchor button; it docks under that button's right edge.
 */
@Composable
fun AxPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    entries: List<AxMenuEntry>,
) {
    val open = remember { Animatable(0f) }
    val closing = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    val n = entries.size
    LaunchedEffect(expanded) {
        if (expanded) {
            shown = true
            closing.snapTo(0f)
            open.snapTo(0f)
            open.animateTo(1f, tween(150 + 16 * n, easing = TgAccelDecel))
        } else if (shown) {
            closing.animateTo(1f, tween(150, easing = TgDefault))
            shown = false
            open.snapTo(0f)
        }
    }
    if (!shown) return

    val gap = 4
    val provider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize,
            ): IntOffset {
                val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = (anchorBounds.bottom + gap).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
                return IntOffset(x, y)
            }
        }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        val bg = AxMenuGlass.color
        val shape = AxMenuGlass.shape
        Column(
            Modifier
                .padding(8.dp) // room so scaling/shadow never clips
                .widthIn(min = 210.dp, max = 300.dp)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(1f, 0f)
                    val c = closing.value
                    val s = 1f - 0.2f * c
                    scaleX = s
                    scaleY = s
                    alpha = 1f - c
                }
                .drawBehind {
                    val g = open.value.coerceIn(0f, 1f)
                    val h = size.height * g
                    val r = 22.dp.toPx()
                    drawRoundRect(bg.copy(alpha = bg.alpha * g), Offset.Zero, Size(size.width, h), CornerRadius(r, r))
                    drawRoundRect(
                        Color.White.copy(alpha = 0.16f * g), Offset.Zero, Size(size.width, h), CornerRadius(r, r),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                    )
                }
                .padding(vertical = 8.dp),
        ) {
            entries.forEachIndexed { i, e ->
                val tint = if (e.danger) Color(0xFFFF6B6B) else Color.White
                Row(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            val k = tgCascade(open.value, i, n)
                            alpha = k
                            translationY = -6.dp.toPx() * (1f - k)
                        }
                        .clickable { e.onClick() }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(e.icon, null, tint = tint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.w2(14.dp))
                    Text(e.text, color = tint, fontSize = 16.sp, maxLines = 1)
                }
            }
        }
    }
}
