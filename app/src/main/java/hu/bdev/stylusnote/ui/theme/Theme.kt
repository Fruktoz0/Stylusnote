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
 * Extended palette properties for canvas surfaces that cannot be expressed cleanly
 * through Material 3 container tokens alone (e.g. pure OLED #000000 paper vs. desk backdrop).
 */
data class StylusSurfacePalette(
    val isOledDeepBlack: Boolean,
    val deskBackground: Color,
    val pagePaperBackground: Color,
    val pageBorder: Color,
    val defaultInkColor: Color
)

val LocalStylusSurfacePalette = staticCompositionLocalOf {
    StylusSurfacePalette(
        isOledDeepBlack = false,
        deskBackground = Color(0xFFE7EBF0),
        pagePaperBackground = Color(0xFFFFFFFF),
        pageBorder = Color(0xFFCBD2DC),
        defaultInkColor = Color(0xFF14171C)
    )
}

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A56DB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF001A4D),
    secondary = Color(0xFF4F5D75),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE3EE),
    onSecondaryContainer = Color(0xFF151E2E),
    background = Color(0xFFE7EBF0),
    onBackground = Color(0xFF14171C),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF14171C),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF94A3B8)
)

private val StandardDarkColorScheme = darkColorScheme(
    primary = Color(0xFF8FB3FF),
    onPrimary = Color(0xFF002B75),
    primaryContainer = Color(0xFF0B3EA8),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFB8C4D8),
    onSecondary = Color(0xFF232D3F),
    secondaryContainer = Color(0xFF394458),
    onSecondaryContainer = Color(0xFFDDE3EE),
    background = Color(0xFF12151B),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF1A1E26),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF262C38),
    onSurfaceVariant = Color(0xFFA0AEC0),
    outline = Color(0xFF475569)
)

private val OledDeepBlackColorScheme = darkColorScheme(
    primary = Color(0xFF8FB3FF),
    onPrimary = Color(0xFF002B75),
    primaryContainer = Color(0xFF0B3EA8),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFB8C4D8),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF141820),
    onSecondaryContainer = Color(0xFFDDE3EE),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF0F1218),
    onSurfaceVariant = Color(0xFFA0AEC0),
    outline = Color(0xFF2D3748)
)

private val StylusTypography = Typography(
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
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
            pageBorder = Color(0xFF262B35),
            defaultInkColor = Color(0xFFF1F5F9)
        )
        darkTheme -> StylusSurfacePalette(
            isOledDeepBlack = false,
            deskBackground = Color(0xFF0F1218),
            pagePaperBackground = Color(0xFF1B202A),
            pageBorder = Color(0xFF323A4A),
            defaultInkColor = Color(0xFFE6EAF0)
        )
        else -> StylusSurfacePalette(
            isOledDeepBlack = false,
            deskBackground = Color(0xFFE7EBF0),
            pagePaperBackground = Color(0xFFFFFFFF),
            pageBorder = Color(0xFFCBD2DC),
            defaultInkColor = Color(0xFF14171C)
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

