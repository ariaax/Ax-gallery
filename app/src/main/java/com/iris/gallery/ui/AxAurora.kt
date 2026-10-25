package com.iris.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Screen background. Plain colour when liquid glass is off; otherwise a static "collage" made of
 * cut-paper shapes (accent band, halftone dots, orbit lines, ring, star, ink circle, bars).
 * Nothing moves, so it costs nothing while scrolling. Kept faint so text stays easy to read.
 */
@Composable
fun Modifier.axAurora(): Modifier {
    val cs = MaterialTheme.colorScheme
    val bg = cs.background
    if (!AxGlass.enabled) return this.background(bg)
    val dark = bg.luminance() < 0.5f
    val blue = cs.primary
    val ink = cs.onBackground
    val red = cs.tertiary
    val bandA = if (dark) 0.70f else 0.85f
    val soft = if (dark) 0.95f else 1f

    return this
        .background(bg)
        .drawBehind {
            val w = size.width
            val h = size.height
            val u = 1.dp.toPx()

            // big pale disc + ring, bottom right
            drawCircle(blue.copy(alpha = 0.07f * soft), radius = w * 0.50f, center = Offset(w * 0.88f, h * 0.88f))
            drawCircle(blue.copy(alpha = 0.30f * soft), radius = w * 0.50f, center = Offset(w * 0.88f, h * 0.88f), style = Stroke(10 * u))
            drawCircle(blue.copy(alpha = 0.22f * soft), radius = w * 0.36f, center = Offset(w * 0.88f, h * 0.88f), style = Stroke(1.5f * u))

            // thin orbit lines
            rotate(-20f, Offset(w * 0.5f, h * 0.30f)) {
                drawOval(blue.copy(alpha = 0.34f * soft), Offset(w * -0.05f, h * 0.23f), Size(w * 1.1f, h * 0.14f), style = Stroke(1.2f * u))
                drawOval(ink.copy(alpha = 0.16f), Offset(w * 0.02f, h * 0.25f), Size(w * 0.96f, h * 0.10f), style = Stroke(1f * u))
            }

            // halftone patches
            fun dots(x0: Float, y0: Float, cols: Int, rows: Int, pitch: Float, diam: Float, color: androidx.compose.ui.graphics.Color) {
                val pts = ArrayList<Offset>(cols * rows)
                for (r in 0 until rows) for (c in 0 until cols) {
                    pts.add(Offset(x0 + c * pitch + if (r % 2 == 1) pitch / 2 else 0f, y0 + r * pitch * 0.87f))
                }
                drawPoints(pts, PointMode.Points, color, diam, StrokeCap.Round)
            }
            dots(w - 150 * u, 72 * u, 12, 9, 11 * u, 4 * u, blue.copy(alpha = 0.42f * soft))
            dots(34 * u, h * 0.80f, 8, 6, 10 * u, 3.2f * u, ink.copy(alpha = 0.20f))

            // ink circle and bars
            drawCircle(ink.copy(alpha = 0.15f), radius = 20 * u, center = Offset(w * 0.17f, h * 0.60f))
            drawRect(ink.copy(alpha = 0.10f), Offset(34 * u, h * 0.72f), Size(w * 0.42f, 9 * u))
            drawRect(blue.copy(alpha = 0.55f * soft), Offset(34 * u, h * 0.72f + 14 * u), Size(w * 0.16f, 5 * u))

            // four-point star (cut-out sticker)
            fun star(cx: Float, cy: Float, r: Float, color: androidx.compose.ui.graphics.Color) {
                val k = r * 0.22f
                val p = Path().apply {
                    moveTo(cx, cy - r); lineTo(cx + k, cy - k); lineTo(cx + r, cy); lineTo(cx + k, cy + k)
                    lineTo(cx, cy + r); lineTo(cx - k, cy + k); lineTo(cx - r, cy); lineTo(cx - k, cy - k); close()
                }
                drawPath(p, color)
            }
            star(w * 0.80f, h * 0.215f, 16 * u, ink.copy(alpha = 0.42f))
            star(w * 0.31f, h * 0.43f, 7 * u, red.copy(alpha = 0.55f * soft))

            // accent band on the left edge, with a thin ink rule
            drawRect(blue.copy(alpha = bandA), Offset(0f, 0f), Size(14 * u, h))
            drawRect(ink.copy(alpha = 0.45f), Offset(14 * u, 0f), Size(1.5f * u, h))
        }
        // the screen content sits in its own layer
        .graphicsLayer { }
}
