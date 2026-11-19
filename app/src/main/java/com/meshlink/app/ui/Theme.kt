package com.meshlink.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ----------------------------------------------------------------- brand
val BrandViolet = Color(0xFF6C4FD8)
val BrandDeep = Color(0xFF4322A8)
val BrandLight = Color(0xFF9C86F0)
val BrandMint = Color(0xFF00C88A)

private val LightColors = lightColorScheme(
    primary = BrandViolet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E0FF),
    onPrimaryContainer = Color(0xFF1C0B5E),
    secondary = Color(0xFF00A884),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2F5EA),
    onSecondaryContainer = Color(0xFF00382C),
    tertiary = Color(0xFFB4478F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8EF),
    onTertiaryContainer = Color(0xFF3B002D),
    background = Color(0xFFF6F4FB),
    onBackground = Color(0xFF1A1626),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1626),
    surfaceVariant = Color(0xFFE9E4F5),
    onSurfaceVariant = Color(0xFF5C5570),
    outline = Color(0xFF8F89A3),
    outlineVariant = Color(0xFFDCD6EC),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC7B7FF),
    onPrimary = Color(0xFF2A0E78),
    primaryContainer = Color(0xFF4322A8),
    onPrimaryContainer = Color(0xFFE7E0FF),
    secondary = Color(0xFF5CE6C0),
    onSecondary = Color(0xFF00382C),
    secondaryContainer = Color(0xFF00513F),
    onSecondaryContainer = Color(0xFFC9FFEE),
    tertiary = Color(0xFFFFAFE0),
    onTertiary = Color(0xFF5A0046),
    tertiaryContainer = Color(0xFF7C2C64),
    onTertiaryContainer = Color(0xFFFFD8EF),
    background = Color(0xFF0E0C15),
    onBackground = Color(0xFFE9E3F5),
    surface = Color(0xFF16131F),
    onSurface = Color(0xFFE9E3F5),
    surfaceVariant = Color(0xFF2B2638),
    onSurfaceVariant = Color(0xFFC9C2DA),
    outline = Color(0xFF938DA6),
    outlineVariant = Color(0xFF3A3448),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val MeshShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val MeshTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            letterSpacing = 0.1.sp,
        ),
    )
}

@Composable
fun MeshLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = MeshShapes,
        typography = MeshTypography,
        content = content,
    )
}
