package com.iris.gallery.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The ONE place that decides what shapes look like. The whole app used angular cut corners before; every shape
 * call now goes through here (the old call name was replaced everywhere), so the look can be tuned in one file.
 *
 *  - percent form  (buttons, chips, capsules, bars): a perfect circle / pill. Very large surfaces stay softly rounded.
 *  - Dp form (tiles, cards, sheets, dialogs): plain soft rounded corners of that size.
 */
private val LargeSurfaceRadius = 28.dp

/** Pill / circle: radius is half of the shorter side, capped so big panels don't turn into giant ovals. */
object AxCircleShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = minOf(size.minDimension / 2f, with(density) { LargeSurfaceRadius.toPx() })
        return Outline.Rounded(RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(r, r)))
    }
}

/** Replacement for the former angular shape: percent form = circle/pill. */
@Suppress("UNUSED_PARAMETER")
fun AxRound(percent: Int): Shape = AxCircleShape

/** Dp form = soft rounded corners. */
fun AxRound(size: Dp): RoundedCornerShape = RoundedCornerShape(size)

fun AxRound(topStart: Dp = 0.dp, topEnd: Dp = 0.dp, bottomEnd: Dp = 0.dp, bottomStart: Dp = 0.dp): RoundedCornerShape =
    RoundedCornerShape(topStart = topStart, topEnd = topEnd, bottomEnd = bottomEnd, bottomStart = bottomStart)
