package com.iris.gallery.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.iris.gallery.data.AccentColor
import com.iris.gallery.data.ThemeMode

private fun createLightPalette(primary: Color, secondary: Color, tertiary: Color) = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = primary.copy(alpha = 0.15f),
    onPrimaryContainer = primary,
    secondary = secondary,
    onSecondary = Color.White,
    secondaryContainer = secondary.copy(alpha = 0.15f),
    onSecondaryContainer = secondary,
    tertiary = tertiary,
    background = Color(0xFFFBF8FE),
    surface = Color(0xFFFBF8FE),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurface = Color(0xFF1D1B20),
    onSurfaceVariant = Color(0xFF49454E),
)

private fun createDarkPalette(primary: Color, secondary: Color, tertiary: Color, amoled: Boolean) = darkColorScheme(
    primary = primary,
    onPrimary = Color(0xFF061638),
    primaryContainer = primary.copy(alpha = 0.25f),
    onPrimaryContainer = primary,
    secondary = secondary,
    onSecondary = Color(0xFF0B1A3A),
    secondaryContainer = secondary.copy(alpha = 0.25f),
    onSecondaryContainer = secondary,
    tertiary = tertiary,
    background = if (amoled) Color.Black else Color(0xFF05060A),
    surface = if (amoled) Color.Black else Color(0xFF05060A),
    surfaceContainer = if (amoled) Color.Black else Color(0xFF0C0F18),
    surfaceContainerLow = if (amoled) Color.Black else Color(0xFF090B12),
    surfaceContainerLowest = if (amoled) Color.Black else Color(0xFF030408),
    surfaceContainerHigh = if (amoled) Color(0xFF161618) else Color(0xFF131826),
    surfaceContainerHighest = if (amoled) Color(0xFF201E26) else Color(0xFF1A2032),
    surfaceVariant = if (amoled) Color(0xFF12141A) else Color(0xFF1E2434),
    onSurface = Color(0xFFE8ECF5),
    onSurfaceVariant = Color(0xFFB5BDCF),
)

private fun createMonochromeLightPalette() = lightColorScheme(
    primary = Color(0xFF18181B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E4E7),
    onPrimaryContainer = Color(0xFF09090B),
    secondary = Color(0xFF3F3F46),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF4F4F5),
    onSecondaryContainer = Color(0xFF18181B),
    tertiary = Color(0xFF71717A),
    onTertiary = Color.White,
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF4F4F5),
    surfaceContainerHigh = Color(0xFFEAEAEA),
    surfaceContainerHighest = Color(0xFFE4E4E7),
    onSurface = Color(0xFF09090B),
    onSurfaceVariant = Color(0xFF52525B),
    outline = Color(0xFFD4D4D8),
    outlineVariant = Color(0xFFE4E4E7),
)

private fun createMonochromeDarkPalette(amoled: Boolean) = darkColorScheme(
    primary = Color(0xFFF4F4F5),
    onPrimary = Color(0xFF09090B),
    primaryContainer = Color(0xFF27272A),
    onPrimaryContainer = Color(0xFFFAFAFA),
    secondary = Color(0xFFD4D4D8),
    onSecondary = Color(0xFF18181B),
    secondaryContainer = if (amoled) Color(0xFF141414) else Color(0xFF202023),
    onSecondaryContainer = Color(0xFFE4E4E7),
    tertiary = Color(0xFFA1A1AA),
    onTertiary = Color(0xFF09090B),
    background = if (amoled) Color.Black else Color(0xFF09090B),
    surface = if (amoled) Color.Black else Color(0xFF09090B),
    surfaceContainer = if (amoled) Color.Black else Color(0xFF121215),
    surfaceContainerLow = if (amoled) Color.Black else Color(0xFF0D0D10),
    surfaceContainerLowest = if (amoled) Color.Black else Color(0xFF060608),
    surfaceContainerHigh = if (amoled) Color(0xFF1C1C1F) else Color(0xFF27272A),
    surfaceContainerHighest = if (amoled) Color(0xFF242428) else Color(0xFF323238),
    surfaceVariant = if (amoled) Color(0xFF141414) else Color(0xFF18181B),
    onSurface = Color(0xFFFAFAFA),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF3F3F46),
    outlineVariant = Color(0xFF27272A),
)

@Composable
fun IrisTheme(
    accentColor: AccentColor = AccentColor.IRIS,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    amoledBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // The app is always black (see the design: sharp chrome on black), whatever the system or old settings say.
    @Suppress("UNUSED_VARIABLE", "NAME_SHADOWING") val themeMode = ThemeMode.DARK
    @Suppress("NAME_SHADOWING") val amoledBlack = true
    val isDark = true

    val colors = if (accentColor == AccentColor.MONOCHROME) {
        if (isDark) createMonochromeDarkPalette(amoledBlack) else createMonochromeLightPalette()
    } else if (accentColor == AccentColor.MATERIAL_YOU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynamicScheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        if (isDark && amoledBlack) {
            dynamicScheme.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceContainer = Color.Black,
                surfaceContainerLow = Color.Black,
                surfaceContainerLowest = Color.Black,
                surfaceVariant = Color(0xFF141414)
            )
        } else dynamicScheme
    } else {
        val (primary, secondary, tertiary) = when (accentColor) {
            AccentColor.IRIS -> Triple(Color(0xFF4E8DFF), Color(0xFF7FA8FF), Color(0xFFCFE0FF))
            AccentColor.LAPIS_MESOPOTAMIA -> Triple(Color(0xFF0A84FF), Color(0xFF5E9CFF), Color(0xFFFF9F0A))
            AccentColor.EMERALD -> Triple(Color(0xFF00897B), Color(0xFF26A69A), Color(0xFF80CBC4))
            AccentColor.ISHTAR_AMBER -> Triple(Color(0xFFF57C00), Color(0xFFFFA726), Color(0xFFFFD54F))
            AccentColor.ROSE -> Triple(Color(0xFFD81B60), Color(0xFFEC407A), Color(0xFFFF80AB))
            AccentColor.MATERIAL_YOU, AccentColor.MONOCHROME -> Triple(Color(0xFF4E8DFF), Color(0xFF7FA8FF), Color(0xFFCFE0FF))
        }

        if (isDark) {
            createDarkPalette(primary, secondary, tertiary, amoledBlack)
        } else {
            createLightPalette(primary, secondary, tertiary)
        }
    }

    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    // sharp, cut-corner shapes for every Material component (buttons, cards, dialogs, menus, fields)
    val shapes = androidx.compose.material3.Shapes(
        extraSmall = androidx.compose.foundation.shape.CutCornerShape(4.dp),
        small = androidx.compose.foundation.shape.CutCornerShape(8.dp),
        medium = androidx.compose.foundation.shape.CutCornerShape(12.dp),
        large = androidx.compose.foundation.shape.CutCornerShape(16.dp),
        extraLarge = androidx.compose.foundation.shape.CutCornerShape(24.dp),
    )
    MaterialTheme(colorScheme = colors, shapes = shapes, content = content)
}
