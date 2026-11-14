package hu.bdev.stylusnote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Cursive handwriting typeface shared across AI-formatted text blocks and PDF export.
 */
val HandwritingFontFamily: FontFamily = FontFamily.Cursive

/**
 * Extended palette properties for canvas & StarNote-inspired chrome surfaces that cannot be
 * expressed cleanly through Material 3 container tokens alone (e.g. OLED paper vs. sidebar).
 */
data class StylusSurfacePalette(
    val isOledDeepBlack: Boolean,
    val deskBackground: Color,
    val pagePaperBackground: Color,
    val pageBorder: Color,
    val defaultInkColor: Color,
    val sidebarBackground: Color = Color(0xFFF3F4F6),
    val groupedCardBackground: Color = Color(0xFFECEEF1),
    val activePillBackground: Color = Color(0xFFE3ECF3),
    val eraserAccent: Color = Color(0xFFE86B6B)
)

val LocalStylusSurfacePalette = staticCompositionLocalOf {
    StylusSurfacePalette(
        isOledDeepBlack = false,
        deskBackground = Color(0xFFF7F8FA),
        pagePaperBackground = Color(0xFFFFFFFF),
        pageBorder = Color(0xFFDFE3E9),
        defaultInkColor = Color(0xFF1E232A),
        sidebarBackground = Color(0xFFF3F4F6),
        groupedCardBackground = Color(0xFFECEEF1),
        activePillBackground = Color(0xFFE3ECF3),
        eraserAccent = Color(0xFFE86B6B)
    )
}

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2B689C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3ECF3),
    onPrimaryContainer = Color(0xFF1B4D78),
    secondary = Color(0xFF525B68),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFECEEF1),
    onSecondaryContainer = Color(0xFF1E232A),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1E232A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1E232A),
    surfaceVariant = Color(0xFFECEEF1),
    onSurfaceVariant = Color(0xFF6C7582),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFF3F4F6),
    surfaceContainerHigh = Color(0xFFECEEF1),
    surfaceContainerHighest = Color(0xFFE3E7EC),
    outline = Color(0xFF8E97A4),
    outlineVariant = Color(0xFFDFE3E9)
)

private val StandardDarkColorScheme = darkColorScheme(
    primary = Color(0xFF6FA8D9),
    onPrimary = Color(0xFF0A2742),
    primaryContainer = Color(0xFF1A3A57),
    onPrimaryContainer = Color(0xFFD6E8F8),
    secondary = Color(0xFFB4BFCD),
    onSecondary = Color(0xFF222A35),
    secondaryContainer = Color(0xFF272D38),
    onSecondaryContainer = Color(0xFFE2E7EE),
    background = Color(0xFF111419),
    onBackground = Color(0xFFEAEFF5),
    surface = Color(0xFF181C22),
    onSurface = Color(0xFFEAEFF5),
    surfaceVariant = Color(0xFF232830),
    onSurfaceVariant = Color(0xFF9AA3B0),
    surfaceContainerLowest = Color(0xFF0E1115),
    surfaceContainerLow = Color(0xFF16191F),
    surfaceContainer = Color(0xFF1D2229),
    surfaceContainerHigh = Color(0xFF252B33),
    surfaceContainerHighest = Color(0xFF2E353F),
    outline = Color(0xFF4F5968),
    outlineVariant = Color(0xFF313844)
)

private val OledDeepBlackColorScheme = darkColorScheme(
    primary = Color(0xFF6FA8D9),
    onPrimary = Color(0xFF0A2742),
    primaryContainer = Color(0xFF15314B),
    onPrimaryContainer = Color(0xFFD6E8F8),
    secondary = Color(0xFFB4BFCD),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF141820),
    onSecondaryContainer = Color(0xFFDDE3EE),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF11141B),
    onSurfaceVariant = Color(0xFFA0AEC0),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF080A0D),
    surfaceContainer = Color(0xFF0E1116),
    surfaceContainerHigh = Color(0xFF161B22),
    surfaceContainerHighest = Color(0xFF1F262F),
    outline = Color(0xFF333C4B),
    outlineVariant = Color(0xFF222833)
)

private val StylusTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)

@Composable
fun StylusNoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    oledDeepBlack: Boolean = false,
    content: @Composable () -> Unit
) {
    val useOled = darkTheme && oledDeepBlack
    val colorScheme = when {
        useOled -> OledDeepBlackColorScheme
        darkTheme -> StandardDarkColorScheme
        else -> LightColorScheme
    }

    val surfacePalette = when {
        useOled -> StylusSurfacePalette(
            isOledDeepBlack = true,
            deskBackground = Color(0xFF000000),
            pagePaperBackground = Color(0xFF000000),
            pageBorder = Color(0xFF222833),
            defaultInkColor = Color(0xFFF1F5F9),
            sidebarBackground = Color(0xFF060709),
            groupedCardBackground = Color(0xFF141820),
            activePillBackground = Color(0xFF15314B),
            eraserAccent = Color(0xFFE86B6B)
        )
        darkTheme -> StylusSurfacePalette(
            isOledDeepBlack = false,
            deskBackground = Color(0xFF111419),
            pagePaperBackground = Color(0xFF1B202A),
            pageBorder = Color(0xFF313844),
            defaultInkColor = Color(0xFFEAEFF5),
            sidebarBackground = Color(0xFF16191F),
            groupedCardBackground = Color(0xFF252B33),
            activePillBackground = Color(0xFF1A3A57),
            eraserAccent = Color(0xFFE86B6B)
        )
        else -> StylusSurfacePalette(
            isOledDeepBlack = false,
            deskBackground = Color(0xFFF7F8FA),
            pagePaperBackground = Color(0xFFFFFFFF),
            pageBorder = Color(0xFFDFE3E9),
            defaultInkColor = Color(0xFF1E232A),
            sidebarBackground = Color(0xFFF3F4F6),
            groupedCardBackground = Color(0xFFECEEF1),
            activePillBackground = Color(0xFFE3ECF3),
            eraserAccent = Color(0xFFE86B6B)
        )
    }

    CompositionLocalProvider(LocalStylusSurfacePalette provides surfacePalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = StylusTypography,
            content = content
        )
    }
}

