package com.meshlink.app.ui

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

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

val WebDarkBg = Color(0xFF12111A)
val WebDarkBgSoft = Color(0xFF171522)
val WebDarkPanel = Color(0xFF1B1826)
val WebDarkPanel2 = Color(0xFF231F31)
val WebDarkSurface = Color(0xFF282338)
val WebDarkSurface2 = Color(0xFF332D45)
val WebDarkSurface3 = Color(0xFF403852)
val WebDarkText = Color(0xFFF2EFF8)
val WebDarkText2 = Color(0xFFC9C2D8)
val WebDarkMuted = Color(0xFFA79EBB)

val WebLightBg = Color(0xFFF0EEF5)
val WebLightBgSoft = Color(0xFFF6F4F9)
val WebLightPanel = Color(0xFFF8F6FB)
val WebLightPanel2 = Color(0xFFF2EFF7)
val WebLightSurface = Color(0xFFE9E5F1)
val WebLightSurface2 = Color(0xFFDED8EB)
val WebLightSurface3 = Color(0xFFCFC7E1)
val WebLightText = Color(0xFF1F1A2E)
val WebLightText2 = Color(0xFF4B4361)
val WebLightMuted = Color(0xFF776E8B)

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
    canvasEnd = Color(0xFF1B1829),
    rowStart = Color(0xFF1F1B2C),
    rowEnd = Color(0xFF191624),
    pinnedStart = Color(0xFF2E2742),
    pinnedEnd = Color(0xFF221E33),
    outgoingStart = Color(0xFF7C3AED),
    outgoingEnd = Color(0xFF5B21B6),
    incomingStart = WebDarkSurface,
    incomingEnd = Color(0xFF241F36),
    incomingText = WebDarkText,
    dateChip = Color(0xFF322B48),
    glass = Color.White.copy(alpha = 0.08f),
    glassBorder = Color.White.copy(alpha = 0.16f),
    hairline = Color.White.copy(alpha = 0.08f),
    glow = BrandLight,
    navBar = Color(0xFF191624),
    drawer = Color(0xFF171421),
    composerBar = Color(0xFF1D1A2A),
    composerPill = Color(0xFF282338),
    statusPill = Color(0xFF2D2742),
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
    canvasStart = Color(0xFFF6F4F9),
    canvasEnd = Color(0xFFE9E4F2),
    rowStart = Color(0xFFF8F6FB),
    rowEnd = Color(0xFFF1EEF7),
    pinnedStart = Color(0xFFEDE6FA),
    pinnedEnd = Color(0xFFF8F5FD),
    outgoingStart = Color(0xFF7C3AED),
    outgoingEnd = Color(0xFF5B21B6),
    incomingStart = Color(0xFFFBF9FE),
    incomingEnd = Color(0xFFF2EFF7),
    incomingText = WebLightText,
    dateChip = Color(0xFFF2EEF9),
    glass = Color.White.copy(alpha = 0.18f),
    glassBorder = Color.White.copy(alpha = 0.42f),
    hairline = Color(0xFFDED7EA),
    glow = BrandViolet,
    navBar = Color(0xFFF6F4F9),
    drawer = Color(0xFFF2EFF7),
    composerBar = Color(0xFFF6F4F9),
    composerPill = Color(0xFFFBF9FE),
    statusPill = Color(0xFFE8E1F5),
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
fun MhzalyTheme(
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

    // Keep Android's status/navigation bars in sync with the selected theme.
    // Without this, switching Light/Dark/System can leave a stale white or
    // black system bar behind the newly themed Compose content.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !useDark
                isAppearanceLightNavigationBars = !useDark
            }
        }
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
