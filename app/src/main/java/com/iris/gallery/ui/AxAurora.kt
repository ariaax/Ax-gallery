package com.iris.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Screen background. Plain colour when liquid glass is off; otherwise a very quiet vertical wash
 * (the theme colour fading into the base colour), like an iOS wallpaper behind frosted glass.
 * Static, so it costs nothing while scrolling, and it has no shapes that could distract from photos.
 */
@Composable
fun Modifier.axAurora(): Modifier {
    val cs = MaterialTheme.colorScheme
    val bg = cs.background
    if (!AxGlass.enabled) return this.background(bg)
    val dark = bg.luminance() < 0.5f
    val top = lerp(bg, cs.primary, if (dark) 0.10f else 0.07f)
    val mid = lerp(bg, cs.primary, if (dark) 0.03f else 0.02f)
    return this
        .background(Brush.verticalGradient(listOf(top, mid, bg)))
        .graphicsLayer { }
}
