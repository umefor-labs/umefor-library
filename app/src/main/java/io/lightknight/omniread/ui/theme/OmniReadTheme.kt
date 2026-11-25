package io.lightknight.omniread.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.lightknight.omniread.data.model.AppThemeMode

// Umefor Brand Tokens: Deep Forest Teal (#102E2B) + Electric Lime (#D4EF79)
val UmeforTealDeep = Color(0xFF102E2B)
val UmeforTealRich = Color(0xFF1E4D45)
val UmeforLime = Color(0xFFD4EF79)
val UmeforMint = Color(0xFF88C9A1)

private val LightColors = lightColorScheme(
    primary = UmeforTealDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EDC2),
    onPrimaryContainer = UmeforTealDeep,
    secondary = Color(0xFF3E685C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0ECE7),
    onSecondaryContainer = UmeforTealDeep,
    background = Color(0xFFF4F7F2),
    onBackground = Color(0xFF111C1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111C1A),
    surfaceVariant = Color(0xFFE6EFE4),
    onSurfaceVariant = Color(0xFF43534E),
    outline = Color(0xFFB5C4BD)
)

private val SepiaColors = lightColorScheme(
    primary = Color(0xFF6E472B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDD9C5),
    onPrimaryContainer = Color(0xFF2D1605),
    secondary = Color(0xFF7D6855),
    onSecondary = Color.White,
    background = Color(0xFFF4ECE0),
    onBackground = Color(0xFF3B2F24),
    surface = Color(0xFFF9F3E9),
    onSurface = Color(0xFF3B2F24),
    surfaceVariant = Color(0xFFE6DAC4),
    onSurfaceVariant = Color(0xFF5A4B3C),
    outline = Color(0xFFCBBBA2)
)

private val DarkColors = darkColorScheme(
    primary = UmeforLime,
    onPrimary = UmeforTealDeep,
    primaryContainer = Color(0xFF1C443E),
    onPrimaryContainer = Color(0xFFE4F6A6),
    secondary = UmeforMint,
    onSecondary = Color(0xFF0A241E),
    secondaryContainer = Color(0xFF1B3430),
    onSecondaryContainer = Color(0xFFCDE9D9),
    background = Color(0xFF0C1413),
    onBackground = Color(0xFFE8EFEA),
    surface = Color(0xFF13201E),
    onSurface = Color(0xFFE8EFEA),
    surfaceVariant = Color(0xFF1B2D2A),
    onSurfaceVariant = Color(0xFFB6C6BF),
    outline = Color(0xFF2C433F)
)

private val AmoledColors = darkColorScheme(
    primary = UmeforLime,
    onPrimary = UmeforTealDeep,
    primaryContainer = Color(0xFF173832),
    onPrimaryContainer = Color(0xFFE4F6A6),
    secondary = UmeforMint,
    onSecondary = Color(0xFF081B16),
    secondaryContainer = Color(0xFF142724),
    onSecondaryContainer = Color(0xFFCDE9D9),
    background = Color(0xFF000000),
    onBackground = Color(0xFFEBF2EE),
    surface = Color(0xFF0B1211),
    onSurface = Color(0xFFEBF2EE),
    surfaceVariant = Color(0xFF13211E),
    onSurfaceVariant = Color(0xFFAABBB4),
    outline = Color(0xFF243733)
)

private val UmeforTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 23.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontSize = 12.5.sp,
        lineHeight = 17.sp
    )
)

@Composable
fun OmniReadTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeMode) {
        AppThemeMode.LIGHT -> LightColors
        AppThemeMode.SEPIA -> SepiaColors
        AppThemeMode.DARK -> DarkColors
        AppThemeMode.AMOLED -> AmoledColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = UmeforTypography,
        content = content
    )
}
