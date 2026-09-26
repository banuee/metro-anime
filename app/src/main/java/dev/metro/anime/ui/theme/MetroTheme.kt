package dev.metro.anime.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object MetroDimens {
    val unit: Dp = 84.dp
    val gap: Dp = 8.dp
    val radius: Dp = 10.dp
    val radiusSmall: Dp = 8.dp
    val radiusPanel: Dp = 16.dp
    val strokeWidth: Dp = 1.dp
}

data class MetroScheme(
    val accent: Color = Color(0xFF00ABA9), // Quickshell Teal
    val background: Color = Color(0xFF0A0A0D),
    val surface: Color = Color(0xFF121216),
    val glass: Color = Color.White.copy(alpha = 0.08f),
    val glassHover: Color = Color.White.copy(alpha = 0.14f),
    val glassDeep: Color = Color(0xFF141418).copy(alpha = 0.65f),
    val stroke: Color = Color.White.copy(alpha = 0.10f),
    val strokeStrong: Color = Color.White.copy(alpha = 0.18f),
    val text: Color = Color(0xFFF7F7F7),
    val textDim: Color = Color.White.copy(alpha = 0.60f),
    val red: Color = Color(0xFFE51400), // Windows Phone crimson red
)

val LocalMetroScheme: ProvidableCompositionLocal<MetroScheme> =
    compositionLocalOf { MetroScheme() }

@Composable
fun MetroTheme(
    scheme: MetroScheme = MetroScheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalMetroScheme provides scheme,
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = scheme.accent,
                onPrimary = scheme.text,
                secondary = scheme.accent,
                surface = scheme.surface,
                onSurface = scheme.text,
                surfaceVariant = scheme.glassDeep,
                onSurfaceVariant = scheme.textDim,
                background = scheme.background,
                onBackground = scheme.text,
            ),
            typography = metroTypography(),
            content = content,
        )
    }
}

@Composable
private fun metroTypography(): Typography {
    val f = MetroFonts.text
    val d = Typography()
    return Typography(
        displayLarge = d.displayLarge.copy(fontFamily = f),
        displayMedium = d.displayMedium.copy(fontFamily = f),
        displaySmall = d.displaySmall.copy(fontFamily = f),
        headlineLarge = d.headlineLarge.copy(fontFamily = f),
        headlineMedium = d.headlineMedium.copy(fontFamily = f),
        headlineSmall = d.headlineSmall.copy(fontFamily = f),
        titleLarge = d.titleLarge.copy(fontFamily = f),
        titleMedium = d.titleMedium.copy(fontFamily = f),
        titleSmall = d.titleSmall.copy(fontFamily = f),
        bodyLarge = d.bodyLarge.copy(fontFamily = f),
        bodyMedium = d.bodyMedium.copy(fontFamily = f),
        bodySmall = d.bodySmall.copy(fontFamily = f),
        labelLarge = d.labelLarge.copy(fontFamily = f),
        labelMedium = d.labelMedium.copy(fontFamily = f),
        labelSmall = d.labelSmall.copy(fontFamily = f),
    )
}
