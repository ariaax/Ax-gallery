package com.iris.gallery.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** True when the user turned system animations off ("Remove animations"); loops and shimmer then stay still. */
@Composable
private fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching {
            android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/**
 * Empty state shared by every screen: a soft rounded tile with an icon, then a short message.
 * Colours come from the theme, so it follows the chosen background.
 */
@Composable
fun AxEmptyState(
    message: String,
    padding: PaddingValues,
    icon: ImageVector = AxIcons.Photos,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier
                    .size(76.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, AxRound(26.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = ink.copy(alpha = 0.55f), modifier = Modifier.size(32.dp))
            }
            Text(
                message,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Placeholder grid with a slow shimmer, shown instead of a bare spinner while the first scan has nothing to show yet. */
@Composable
fun AxSkeletonGrid(padding: PaddingValues, columns: Int = 3) {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val hi = MaterialTheme.colorScheme.surfaceContainerHighest
    val still = rememberReducedMotion()
    val phase = if (still) 0f else {
        val t = rememberInfiniteTransition(label = "skeleton")
        val v by t.animateFloat(
            0f, 1f,
            infiniteRepeatable(axTween(1300, easing = LinearEasing), RepeatMode.Reverse),
            label = "skeleton phase",
        )
        v
    }
    val fill = androidx.compose.ui.graphics.lerp(base, hi, phase)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 2.dp, end = 2.dp,
            top = padding.calculateTopPadding() + 2.dp,
            bottom = padding.calculateBottomPadding() + 2.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        userScrollEnabled = false,
    ) {
        items(List(columns * 12) { it }) {
            Box(Modifier.aspectRatio(1f).background(fill, AxRound(6.dp)))
        }
    }
}
