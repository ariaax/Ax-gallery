package com.iris.gallery.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
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
    androidx.compose.material3.IconButton(
        onClick = onClick,
        modifier = modifier
            .padding(horizontal = 5.dp)
            .size(36.dp)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(c.copy(alpha = 0.18f), c.copy(alpha = 0.07f))))
            .axGlassBorder(CircleShape),
        enabled = enabled,
        content = content,
    )
}

/** Top-bar title: light, airy and slightly tracked with an icy gradient when liquid glass is on. */
@Composable
fun AxBarTitle(text: String) {
    if (!AxGlass.enabled) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val c = MaterialTheme.colorScheme.onSurface
    val tint = MaterialTheme.colorScheme.primary
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Light,
            fontSize = 30.sp,
            letterSpacing = 1.2.sp,
            brush = Brush.verticalGradient(listOf(c, c.copy(alpha = 0.78f), tint.copy(alpha = 0.9f))),
        ),
    )
}
