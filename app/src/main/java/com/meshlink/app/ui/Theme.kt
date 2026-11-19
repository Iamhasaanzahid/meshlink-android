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

/* MeshLink web palette — mirrored exactly from meshlink-web/styles.css so the
 * native app and the deployed web app feel like the same product. */
val BrandViolet = Color(0xFF7C3AED)   // --accent (light)
val BrandDeep = Color(0xFF6D28D9)     // --accent-2 (light)
val BrandLight = Color(0xFFA78BFA)    // --accent-3
val BrandMint = Color(0xFF4ADE80)     // --mint (dark)
val BrandDanger = Color(0xFFFB7185)   // --danger (dark)

val WebDarkBg = Color(0xFF0A0613)
val WebDarkBgSoft = Color(0xFF0D0819)
val WebDarkPanel = Color(0xFF130D23)
val WebDarkPanel2 = Color(0xFF191130)
val WebDarkSurface = Color(0xFF211740)
val WebDarkSurface2 = Color(0xFF2B1E51)
val WebDarkSurface3 = Color(0xFF362665)
val WebDarkText = Color(0xFFF3EEFF)
val WebDarkText2 = Color(0xFFC6B8E6)
val WebDarkMuted = Color(0xFF9282B4)

val WebLightBg = Color(0xFFEFE9FF)
val WebLightBgSoft = Color(0xFFF7F4FF)
val WebLightPanel = Color(0xFFFFFFFF)
val WebLightPanel2 = Color(0xFFFBF9FF)
val WebLightSurface = Color(0xFFF0EAFF)
val WebLightSurface2 = Color(0xFFE8DFFF)
val WebLightSurface3 = Color(0xFFDDD0FF)
val WebLightText = Color(0xFF1D1135)
val WebLightText2 = Color(0xFF524168)
val WebLightMuted = Color(0xFF7C6B97)

private val LightColors = lightColorScheme(
    primary = BrandViolet,
    onPrimary = Color.White,
    primaryContainer = WebLightSurface2,
    onPrimaryContainer = WebLightText,
    secondary = Color(0xFF059669),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF052E1F),
    tertiary = Color(0xFFA21CAF),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFCE7F3),
    onTertiaryContainer = Color(0xFF701A75),
    background = WebLightBg,
    onBackground = WebLightText,
    surface = WebLightPanel,
    onSurface = WebLightText,
    surfaceVariant = WebLightSurface,
    onSurfaceVariant = WebLightText2,
    outline = WebLightMuted,
    outlineVariant = WebLightSurface3,
    error = Color(0xFFE11D48),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = BrandLight,
    onPrimary = WebDarkPanel,
    primaryContainer = WebDarkSurface3,
    onPrimaryContainer = WebDarkText,
    secondary = BrandMint,
    onSecondary = WebDarkPanel,
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = Color(0xFFD1FAE5),
    tertiary = Color(0xFFF0ABFC),
    onTertiary = WebDarkPanel,
    tertiaryContainer = Color(0xFF701A75),
    onTertiaryContainer = Color(0xFFFCE7F3),
    background = WebDarkBg,
    onBackground = WebDarkText,
    surface = WebDarkPanel,
    onSurface = WebDarkText,
    surfaceVariant = WebDarkSurface,
    onSurfaceVariant = WebDarkText2,
    outline = WebDarkMuted,
    outlineVariant = WebDarkSurface3,
    error = BrandDanger,
    onError = WebDarkPanel,
)

private val MeshShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val MeshTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp),
        labelLarge = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            letterSpacing = 0.2.sp,
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
