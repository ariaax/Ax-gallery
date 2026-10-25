package com.iris.gallery.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape

/** Frosted glass fill + soft edge, shared by cards, chips, search field and bar buttons. */
@Composable
fun Modifier.axGlassSurface(shape: Shape, strong: Boolean = false): Modifier {
    val c = MaterialTheme.colorScheme.onSurface
    val tint = MaterialTheme.colorScheme.primary
    val top = if (strong) 0.24f else 0.16f
    val bottom = if (strong) 0.08f else 0.05f
    return this
        .clip(shape)
        .background(tint.copy(alpha = 0.06f))
        .background(Brush.verticalGradient(listOf(c.copy(alpha = top), c.copy(alpha = bottom))))
        .axGlassBorder(shape)
}

/** Same-package shim: screens in this package get glass cards when liquid glass is on. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!AxGlass.enabled) {
        androidx.compose.material3.Card(modifier, shape, colors, elevation, border, content)
        return
    }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(modifier.axEnter().axGlassSurface(shape), content = content)
    }
}

@Composable
fun Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!AxGlass.enabled) {
        androidx.compose.material3.Card(onClick, modifier, enabled, shape, colors, elevation, border, content = content)
        return
    }
    val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            modifier
                .axEnter()
                .axPressScale(src, 0.96f)
                .axGlassSurface(shape)
                .clickable(
                    interactionSource = src,
                    indication = androidx.compose.foundation.LocalIndication.current,
                    enabled = enabled,
                    onClick = onClick,
                ),
            content = content,
        )
    }
}
