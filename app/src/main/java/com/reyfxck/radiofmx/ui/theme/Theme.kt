package com.reyfxck.radiofmx.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Orange = Color(0xFFFF7900)
private val OrangeLight = Color(0xFFFFB56B)
private val OrangeDark = Color(0xFF8B4100)
private val NearBlack = Color(0xFF11100F)
private val DarkSurface = Color(0xFF1A1918)
private val DarkSurfaceHigh = Color(0xFF252321)

private val RadioDarkColors = darkColorScheme(
    primary = OrangeLight,
    onPrimary = Color(0xFF301500),
    primaryContainer = OrangeDark,
    onPrimaryContainer = Color(0xFFFFDCC1),
    secondary = Color(0xFFFFB77A),
    onSecondary = Color(0xFF3E1D00),
    secondaryContainer = Color(0xFF5B2B00),
    onSecondaryContainer = Color(0xFFFFDCC4),
    tertiary = Color(0xFFFFC06E),
    onTertiary = Color(0xFF432C00),
    tertiaryContainer = Color(0xFF604100),
    onTertiaryContainer = Color(0xFFFFDEA6),
    background = NearBlack,
    onBackground = Color(0xFFF0EDEA),
    surface = NearBlack,
    onSurface = Color(0xFFF0EDEA),
    surfaceVariant = DarkSurfaceHigh,
    onSurfaceVariant = Color(0xFFD2C5BC),
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceHigh,
    outline = Color(0xFF9D8E84),
)

private val RadioLightColors = lightColorScheme(
    primary = Color(0xFFA94F00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC2),
    onPrimaryContainer = Color(0xFF351000),
    secondary = Color(0xFF8C4F20),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBC2),
    onSecondaryContainer = Color(0xFF321200),
    tertiary = Color(0xFF765900),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDF8C),
    onTertiaryContainer = Color(0xFF251A00),
    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF221A16),
    surface = Color(0xFFFFF8F4),
    onSurface = Color(0xFF221A16),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RadioFmxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) RadioDarkColors else RadioLightColors,
        content = content,
    )
}
