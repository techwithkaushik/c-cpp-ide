package com.techwithkaushik.cppide.feature.terminal.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val VsCodeBackground = Color(0xFF1E1E1E)
val VsCodeSurface = Color(0xFF333333)
val VsCodeSidebar = Color(0xFF252526)
val VsCodeTerminal = Color(0xFF181818)
val VsCodeBorder = Color(0xFF3C3C3C)
val VsCodeAccent = Color(0xFF007ACC)
val VsCodeText = Color(0xFFCCCCCC)
val VsCodeMutedText = Color(0xFF9D9D9D)

val VsCodeColorScheme: ColorScheme = darkColorScheme(
    primary = VsCodeAccent,
    onPrimary = Color.White,
    secondary = Color(0xFF3794FF),
    background = VsCodeBackground,
    onBackground = VsCodeText,
    surface = VsCodeSurface,
    onSurface = VsCodeText,
    surfaceVariant = VsCodeSidebar,
    onSurfaceVariant = VsCodeMutedText,
    outline = VsCodeBorder,
    error = Color(0xFFF48771)
)

val VsCodeTypography = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
)
