package com.iris.gallery.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties

/*
 * Drop-in "liquid glass" versions of the Material sheet / dialog / menu.
 * Files in this package that do NOT import the Material versions pick these up automatically.
 * Same parameters as Material; only the look changes (translucent surface, soft border, background blur).
 * When AxGlass.enabled is false they fall back to the original opaque look.
 */

private const val SHEET_ALPHA = 0.74f
private const val DIALOG_ALPHA = 0.80f
private const val MENU_ALPHA = 0.90f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: androidx.compose.ui.unit.Dp = BottomSheetDefaults.Elevation,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = AxGlass.enabled
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = if (glass) modifier.axGlassSheetEdge() else modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = if (glass) containerColor.copy(alpha = minOf(containerColor.alpha, SHEET_ALPHA)) else containerColor,
        contentColor = contentColor,
        tonalElevation = if (glass) 0.dp else tonalElevation,
        scrimColor = if (glass) Color.Black.copy(alpha = 0.30f) else scrimColor,
        dragHandle = dragHandle,
    ) {
        if (glass) AxWindowBlur(40)
        content()
    }
}

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(28.dp),
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: androidx.compose.ui.unit.Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    val glass = AxGlass.enabled
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            if (glass) AxWindowBlur(30)
            confirmButton()
        },
        modifier = if (glass) modifier.axGlassBorder(shape) else modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = if (glass) containerColor.copy(alpha = minOf(containerColor.alpha, DIALOG_ALPHA)) else containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = if (glass) 0.dp else tonalElevation,
        properties = properties,
    )
}

@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val themed = if (AxGlass.enabled) {
        scheme.copy(surfaceContainer = scheme.surfaceContainer.copy(alpha = MENU_ALPHA))
    } else scheme
    MaterialTheme(colorScheme = themed, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            offset = offset,
            scrollState = scrollState,
            properties = properties,
            shape = RoundedCornerShape(22.dp),
            containerColor = themed.surfaceContainer,
            border = if (AxGlass.enabled) androidx.compose.foundation.BorderStroke(
                1.dp,
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.24f))
                ),
            ) else null,
            content = content,
        )
    }
}

/** Glass hairline along the TOP edge of a sheet only (a full outline showed a stray line at the sheet's bottom). */
@Composable
fun Modifier.axGlassSheetEdge(corner: androidx.compose.ui.unit.Dp = 28.dp): Modifier {
    val c = MaterialTheme.colorScheme.onSurface
    return this.drawWithContent {
        drawContent()
        val r = corner.toPx()
        val w = size.width
        val path = Path().apply {
            moveTo(0f, r)
            arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
            lineTo(w - r, 0f)
            arcTo(Rect(w - 2 * r, 0f, w, 2 * r), 270f, 90f, false)
        }
        drawPath(
            path,
            Brush.horizontalGradient(listOf(c.copy(alpha = 0.28f), c.copy(alpha = 0.46f), c.copy(alpha = 0.28f))),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/** Light glass capsule (same look as the bottom bar): used behind the top-bar title. */
@Composable
fun Modifier.axBarPill(): Modifier {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val fill = if (dark) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.80f)
    val base = if (dark) Color.Black.copy(alpha = 0.40f) else Color.Black.copy(alpha = 0.06f)
    val rim = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (dark) 0.50f else 0.90f),
            Color.White.copy(alpha = if (dark) 0.08f else 0.25f),
            Color.White.copy(alpha = if (dark) 0.30f else 0.70f),
        )
    )
    return this.drawWithContent {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
        drawRoundRect(base, cornerRadius = r)
        drawRoundRect(fill, cornerRadius = r)
        drawRoundRect(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.10f else 0.28f), Color.Transparent), endY = size.height * 0.55f),
            cornerRadius = r,
        )
        drawRoundRect(rim, cornerRadius = r, style = Stroke(width = 1.dp.toPx()))
        drawContent()
    }
}

val LocalAxInBarGroup = androidx.compose.runtime.compositionLocalOf { false }

/** Top-bar action buttons share ONE light glass pill (same look as the bottom bar). */
@Composable
fun AxBarActions(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    if (!AxGlass.enabled) {
        androidx.compose.foundation.layout.Row(content = content)
        return
    }
    // Every action is its own round glass bubble (no shared pill).
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.padding(end = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        content = content,
    )
}

/** Top-bar icon button: a small frosted glass circle when liquid glass is on, a normal icon button otherwise. */
@Composable
fun AxBarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!AxGlass.enabled) {
        androidx.compose.material3.IconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
        return
    }
    val c = MaterialTheme.colorScheme.onSurface
    val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val grouped = LocalAxInBarGroup.current
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .padding(horizontal = if (grouped) 0.dp else 4.dp)
            .size(42.dp)
            .axPressScale(src, 0.86f)
            // flat dark-grey disc, no rim (clean, like a modern gallery header)
            .then(if (grouped) Modifier.clip(CircleShape) else Modifier.clip(CircleShape).background(c.copy(alpha = 0.12f), CircleShape))
            .clickable(
                interactionSource = src,
                indication = androidx.compose.foundation.LocalIndication.current,
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
            ),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides c,
        ) { content() }
    }
}

/**
 * Top-bar title: light, airy and slightly tracked with an icy gradient when liquid glass is on.
 * Inside an album that belongs to an app, that app's icon floats faded and glassy BEHIND the title.
 */
@Composable
fun AxBarTitle(text: String, album: MediaAlbum? = null) {
    if (!AxGlass.enabled) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val c = MaterialTheme.colorScheme.onSurface
    val tint = MaterialTheme.colorScheme.primary
    val badge = rememberAlbumBadge(album)
    val appear by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (badge != null) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(900),
        label = "titleBadge",
    )
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        if (badge != null && album != null) AlbumAppBadge(album, Modifier.size(30.dp))
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                color = c,
            ),
        )
    }
}

/** Icy-blue frosted glass, same look as the video control bar (works over video, where blur is impossible). */
fun Modifier.axIcyPanel(corner: androidx.compose.ui.unit.Dp): Modifier {
    val shape = RoundedCornerShape(corner)
    return this
        .clip(shape)
        .background(Color(0xFFCFE0FF).copy(alpha = 0.20f))
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.05f))))
        .border(
            1.2.dp,
            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.80f), Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.50f))),
            shape,
        )
}

/** Glass chip (filter / sort). Selected chips get a brighter, tinted fill. Falls back to a Material chip. */
@Composable
fun AxGlassChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    label: @Composable () -> Unit,
) {
    if (!AxGlass.enabled) {
        androidx.compose.material3.FilterChip(
            selected = selected, onClick = onClick, modifier = modifier, label = label,
            leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        )
        return
    }
    val c = MaterialTheme.colorScheme.onSurface
    val tint = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(50)
    val top = if (selected) tint.copy(alpha = 0.38f) else c.copy(alpha = 0.16f)
    val bottom = if (selected) tint.copy(alpha = 0.16f) else c.copy(alpha = 0.06f)
    val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .height(36.dp)
            .axPressScale(src, 0.92f)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .axGlassSurface(shape, strong = selected)
            .clickable(
                interactionSource = src,
                indication = androidx.compose.foundation.LocalIndication.current,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides c,
            androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.labelLarge,
        ) {
            leadingIcon?.invoke()
            label()
            trailingIcon?.invoke()
        }
    }
}

/** Search field with a glass pill background. */
@Composable
fun AxGlassSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: @Composable () -> Unit,
    leadingIcon: @Composable () -> Unit,
    trailingIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(26.dp)
    val glass = AxGlass.enabled
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = if (glass) {
            modifier.axGlassSurface(shape, strong = true)
        } else modifier,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        singleLine = true,
        shape = shape,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = if (glass) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            focusedContainerColor = if (glass) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = if (glass) Color.Transparent else MaterialTheme.colorScheme.primary,
        ),
    )
}
