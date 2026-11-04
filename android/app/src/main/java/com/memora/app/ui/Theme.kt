package com.memora.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The geometric accents. They decorate and highlight; they never carry meaning on their own. */
object Accent {
    val Teal = Color(0xFF173F3A)
    val DeepTeal = Color(0xFF0F2B28)
    val Charcoal = Color(0xFF151A19)
    val Ivory = Color(0xFFF7F5EF)
    val Coral = Color(0xFFF04438)
    val Yellow = Color(0xFFFFC928)
    val Sky = Color(0xFF83B8E8)
    val Mint = Color(0xFFB6E4D2)
}

// Warm ivory paper, deep-teal ink and actions. Coral, yellow and sky are rare highlights, not fills.
private val Light = lightColorScheme(
    primary = Color(0xFF1B4B45),
    onPrimary = Accent.Ivory,
    primaryContainer = Color(0xFFD5EEE2),
    onPrimaryContainer = Color(0xFF0F2B28),
    secondary = Color(0xFF2F6A9C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5EEE2),
    onSecondaryContainer = Color(0xFF0F2B28),
    tertiary = Color(0xFFC62D22),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE0DC),
    onTertiaryContainer = Color(0xFF5C100B),
    background = Accent.Ivory,
    onBackground = Color(0xFF16201F),
    surface = Accent.Ivory,
    onSurface = Color(0xFF16201F),
    surfaceVariant = Color(0xFFECEAE0),
    onSurfaceVariant = Color(0xFF55605E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCFBF7),
    surfaceContainer = Color(0xFFF1EFE6),
    surfaceContainerHigh = Color(0xFFE9E7DC),
    outline = Color(0xFF76817F),
    outlineVariant = Color(0xFFDAD8CC),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFBE4E1),
    onErrorContainer = Color(0xFF5C100B),
)

private val Dark = darkColorScheme(
    primary = Accent.Mint,
    onPrimary = Color(0xFF0F2B28),
    primaryContainer = Color(0xFF245249),
    onPrimaryContainer = Color(0xFFD5EEE2),
    secondary = Accent.Sky,
    onSecondary = Color(0xFF0E2A44),
    secondaryContainer = Color(0xFF245249),
    onSecondaryContainer = Color(0xFFD5EEE2),
    tertiary = Color(0xFFFF8A80),
    onTertiary = Color(0xFF4A0B07),
    tertiaryContainer = Color(0xFF7A1C15),
    onTertiaryContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F211F),
    onBackground = Color(0xFFEAF1EE),
    surface = Color(0xFF0F211F),
    onSurface = Color(0xFFEAF1EE),
    surfaceVariant = Color(0xFF1F3A36),
    onSurfaceVariant = Color(0xFFA9BDB8),
    surfaceContainerLowest = Color(0xFF0A1715),
    surfaceContainerLow = Color(0xFF132A27),
    surfaceContainer = Color(0xFF173431),
    surfaceContainerHigh = Color(0xFF1F3F3B),
    outline = Color(0xFF86A09B),
    outlineVariant = Color(0xFF2D4F4A),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5C100B),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Meaning-carrying colours. Used sparingly: ready/offline-available, caution/uncertain, failure. Always paired with words. */
object StatusColors {
    val ok = Color(0xFF2E8B63)
    val warn = Color(0xFFB87A12)
}

/** Spacing scale. Whitespace and type create hierarchy; cards are the exception, not the default. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** Minimum comfortable touch target. */
    val touch = 48.dp
}

private val Display = FontFamily.Serif
private val Body = FontFamily.SansSerif

private val MemoraTypography = Typography(
    // The brand headline: large, heavy serif.
    displayMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp),
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 29.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    // Section headings and card titles.
    titleLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    // Body text and metadata.
    bodyLarge = TextStyle(fontFamily = Body, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Body, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
)

private val MemoraShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Monospace style for citations, extracted evidence and file details. */
val EvidenceStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)

@Composable
fun MemoraTheme(settings: UiSettings? = null, content: @Composable () -> Unit) {
    val dark = when (settings?.theme ?: ThemeChoice.SYSTEM) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    val context = LocalContext.current
    val reduced = (settings?.reduceMotion ?: false) || (settings?.systemReducesMotion() ?: false) ||
        runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    // A fixed brand palette (not the wallpaper's colours) keeps Memora looking like Memora on every phone.
    CompositionLocalProvider(LocalUiSettings provides settings, LocalReducedMotion provides reduced) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = MemoraTypography,
            shapes = MemoraShapes,
            content = content,
        )
    }
}
