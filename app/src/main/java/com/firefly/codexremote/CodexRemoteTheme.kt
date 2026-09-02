package com.firefly.codexremote

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object CodexColors {
    val Graphite = Color(0xFF10141A)
    val Charcoal = Color(0xFF161C25)
    val Raised = Color(0xFF1A212B)
    val Composer = Color(0xFF181E27)
    val Border = Color(0xFF2C3440)
    val Indigo = Color(0xFF7888FF)
    val IndigoSoft = Color(0xFF20294B)
    val Cyan = Color(0xFF62D7E8)
    val Green = Color(0xFF55C59B)
    val Amber = Color(0xFFE9B65A)
    val Text = Color(0xFFF2F4F8)
    val TextMuted = Color(0xFFA3AAB8)
    val Error = Color(0xFFFF7E8C)
}

private val CodexDarkScheme = darkColorScheme(
    primary = CodexColors.Indigo,
    onPrimary = Color(0xFF101632),
    primaryContainer = CodexColors.IndigoSoft,
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = CodexColors.Cyan,
    tertiary = CodexColors.Green,
    background = CodexColors.Graphite,
    onBackground = CodexColors.Text,
    surface = CodexColors.Graphite,
    onSurface = CodexColors.Text,
    surfaceVariant = CodexColors.Raised,
    onSurfaceVariant = CodexColors.TextMuted,
    outline = CodexColors.Border,
    outlineVariant = Color(0xFF202631),
    error = CodexColors.Error,
    errorContainer = Color(0xFF3A1C24),
    onErrorContainer = Color(0xFFFFD9DF),
)

private val CodexTypography = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
)

private val CodexShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
internal fun CodexRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CodexDarkScheme,
        shapes = CodexShapes,
        typography = CodexTypography,
        content = content,
    )
}
