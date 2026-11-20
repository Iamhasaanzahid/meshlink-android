package com.meshlink.app.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* MeshLink identity: deep charcoal-violet surfaces, electric lilac light,
 * with mint / coral / amber / sky as harmonious supporting accents. */
val BrandViolet = Color(0xFF7C3AED)
val BrandDeep = Color(0xFF5B21B6)
val BrandLight = Color(0xFFA78BFA)
val BrandLilac = Color(0xFFC4B5FD)
val BrandMint = Color(0xFF6EE7B7)
val BrandDanger = Color(0xFFFB7185)
val BrandSky = Color(0xFF7DD3FC)
val BrandAmber = Color(0xFFFCD34D)
val BrandCoral = Color(0xFFFDA4AF)

val WebDarkBg = Color(0xFF0B0716)
val WebDarkBgSoft = Color(0xFF100A20)
val WebDarkPanel = Color(0xFF17102B)
val WebDarkPanel2 = Color(0xFF1E1538)
val WebDarkSurface = Color(0xFF241A44)
val WebDarkSurface2 = Color(0xFF302354)
val WebDarkSurface3 = Color(0xFF3D2C6B)
val WebDarkText = Color(0xFFF7F3FF)
val WebDarkText2 = Color(0xFFCFC1EA)
val WebDarkMuted = Color(0xFF9D8CBF)

val WebLightBg = Color(0xFFF5F1FF)
val WebLightBgSoft = Color(0xFFFBF9FF)
val WebLightPanel = Color(0xFFFFFFFF)
val WebLightPanel2 = Color(0xFFF9F6FF)
val WebLightSurface = Color(0xFFEFE8FF)
val WebLightSurface2 = Color(0xFFE4D9FF)
val WebLightSurface3 = Color(0xFFD5C5FF)
val WebLightText = Color(0xFF221339)
val WebLightText2 = Color(0xFF54446E)
val WebLightMuted = Color(0xFF7E6C99)

/** Extra tokens that Material 3's colour scheme does not model. */
data class MeshPalette(
    val violet: Color,
    val lilac: Color,
    val mint: Color,
    val coral: Color,
    val amber: Color,
    val sky: Color,
    val headerStart: Color,
    val headerMid: Color,
    val headerEnd: Color,
    val canvasStart: Color,
    val canvasEnd: Color,
    val rowStart: Color,
    val rowEnd: Color,
    val pinnedStart: Color,
    val pinnedEnd: Color,
    val outgoingStart: Color,
    val outgoingEnd: Color,
    val incomingStart: Color,
    val incomingEnd: Color,
    val incomingText: Color,
    val dateChip: Color,
    val glass: Color,
    val glassBorder: Color,
    val hairline: Color,
    val glow: Color,
    val navBar: Color,
    val drawer: Color,
    val composerBar: Color,
    val composerPill: Color,
    val statusPill: Color,
)

private val DarkMeshPalette = MeshPalette(
    violet = BrandViolet,
    lilac = BrandLilac,
    mint = BrandMint,
    coral = BrandCoral,
    amber = BrandAmber,
    sky = BrandSky,
    headerStart = Color(0xFF4C1D95),
    headerMid = Color(0xFF6D28D9),
    headerEnd = Color(0xFF8B5CF6),
    canvasStart = WebDarkBg,
    canvasEnd = Color(0xFF1A1130),
    rowStart = Color(0xFF181029),
    rowEnd = Color(0xFF130C22),
    pinnedStart = Color(0xFF2A1B4D),
    pinnedEnd = Color(0xFF1B1436),
    outgoingStart = Color(0xFF7C3AED),
    outgoingEnd = Color(0xFF5B21B6),
    incomingStart = WebDarkSurface,
    incomingEnd = Color(0xFF1D1538),
    incomingText = WebDarkText,
    dateChip = Color(0xFF2B2050),
    glass = Color.White.copy(alpha = 0.08f),
    glassBorder = Color.White.copy(alpha = 0.16f),
    hairline = Color.White.copy(alpha = 0.08f),
    glow = BrandLight,
    navBar = Color(0xFF140D27),
    drawer = Color(0xFF130D24),
    composerBar = Color(0xFF171029),
    composerPill = Color(0xFF211838),
    statusPill = Color(0xFF242046),
)

private val LightMeshPalette = MeshPalette(
    violet = BrandDeep,
    lilac = Color(0xFF8B5CF6),
    mint = Color(0xFF059669),
    coral = Color(0xFFE11D48),
    amber = Color(0xFFB45309),
    sky = Color(0xFF0284C7),
    headerStart = Color(0xFF5B21B6),
    headerMid = Color(0xFF7C3AED),
    headerEnd = Color(0xFFA78BFA),
    canvasStart = Color(0xFFFBF9FF),
    canvasEnd = Color(0xFFEDE4FF),
    rowStart = Color.White,
    rowEnd = Color(0xFFFBF8FF),
    pinnedStart = Color(0xFFF1E8FF),
    pinnedEnd = Color(0xFFFFFBFF),
    outgoingStart = Color(0xFF7C3AED),
    outgoingEnd = Color(0xFF5B21B6),
    incomingStart = Color.White,
    incomingEnd = Color(0xFFF9F6FF),
    incomingText = WebLightText,
    dateChip = Color.White,
    glass = Color.White.copy(alpha = 0.18f),
    glassBorder = Color.White.copy(alpha = 0.42f),
    hairline = Color(0xFFE6DCFF),
    glow = BrandViolet,
    navBar = Color.White,
    drawer = Color(0xFFFAF7FF),
    composerBar = Color(0xFFFBF9FF),
    composerPill = Color.White,
    statusPill = Color(0xFFF0EAFF),
)

private val LocalMeshPalette = compositionLocalOf { DarkMeshPalette }

object MeshTokens {
    val palette: MeshPalette
        @Composable get() = LocalMeshPalette.current
}

/* ------------------------------------------------------------ theme choice */

enum class AppThemeMode { DARK, LIGHT, SYSTEM }

/**
 * Tiny persisted appearance store. It lives here so the theme can be changed
 * from Settings without touching MainActivity (which owns no UI state of its
 * own). The value is read synchronously before the first Compose frame.
 */
object ThemeController {
    private const val PREFS = "meshlink_appearance"
    private const val KEY = "theme_mode"

    private var initialized = false
    val loaded: Boolean get() = initialized

    var mode by mutableStateOf(AppThemeMode.DARK)
        private set

    fun load(context: Context) {
        if (initialized) return
        initialized = true
        val stored = runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
        }.getOrNull()
        mode = runCatching { stored?.let { AppThemeMode.valueOf(it) } }.getOrNull()
            ?: AppThemeMode.DARK
    }

    fun set(context: Context, value: AppThemeMode) {
        mode = value
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, value.name)
                .apply()
        }
    }
}

private val LightColors = lightColorScheme(
    primary = BrandViolet,
    onPrimary = Color.White,
    primaryContainer = WebLightSurface2,
    onPrimaryContainer = WebLightText,
    secondary = Color(0xFF0F766E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF042F2E),
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
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val MeshTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.3).sp,
        ),
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.2).sp,
        ),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(lineHeight = 22.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.3.sp,
        ),
        labelMedium = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 0.2.sp,
        ),
    )
}

@Composable
fun MeshLinkTheme(
    // Kept for source compatibility; the persisted Dark / Light / System choice
    // takes precedence as soon as it has been loaded from SharedPreferences.
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    ThemeController.load(context)
    val mode = ThemeController.mode
    val systemDark = isSystemInDarkTheme()
    val useDark = when (mode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> if (ThemeController.loaded) systemDark else darkTheme
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalMeshPalette provides (if (useDark) DarkMeshPalette else LightMeshPalette),
    ) {
        MaterialTheme(
            colorScheme = if (useDark) DarkColors else LightColors,
            shapes = MeshShapes,
            typography = MeshTypography,
            content = content,
        )
    }
}
