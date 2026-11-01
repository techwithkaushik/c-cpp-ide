package com.techwithkaushik.cppide.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared visual design tokens. Feature modules should consume these values
 * instead of defining local color, spacing, radius, or typography literals.
 */
object IdeDesignTokens {
    object Color {
        val Background = androidx.compose.ui.graphics.Color(0xFF101820)
        val Surface = androidx.compose.ui.graphics.Color(0xFF192733)
        val Accent = androidx.compose.ui.graphics.Color(0xFF659AD2)
        val AccentLight = androidx.compose.ui.graphics.Color(0xFF83B7FF)
        val AccentDark = androidx.compose.ui.graphics.Color(0xFF00599C)
        val Navy = androidx.compose.ui.graphics.Color(0xFF003F70)
        val TextPrimary = androidx.compose.ui.graphics.Color(0xFFE8EDF5)
        val TextSecondary = androidx.compose.ui.graphics.Color(0xFF9AA8BA)
        val Error = androidx.compose.ui.graphics.Color(0xFFFF6B6B)
        val Success = androidx.compose.ui.graphics.Color(0xFF66C98A)
        val Divider = androidx.compose.ui.graphics.Color(0xFF334454)
    }

    object Spacing {
        val xxs = 2.dp
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 24.dp
        val xxl = 32.dp
    }

    object Shape {
        val small = 6.dp
        val medium = 10.dp
        val large = 16.dp
        val pill = 999.dp
    }

    object Typography {
        val caption = 12.sp
        val body = 14.sp
        val title = 18.sp
        val headline = 24.sp
    }

    object Preference {
        const val FILE = "ide_settings"
        const val EDITOR_FONT_SIZE = "editor_font_size"
        const val EDITOR_WORD_WRAP = "editor_word_wrap"
        const val EDITOR_THEME = "editor_theme"
        const val TERMINAL_FONT_SIZE = "terminal_font_size"
        const val TERMINAL_AUTO_KEYBOARD = "terminal_auto_keyboard"
        const val UI_FONT_SCALE = "ui_font_scale"
    }

    object Metrics {
        const val DEFAULT_EDITOR_FONT_SIZE = 14f
        const val MIN_EDITOR_FONT_SIZE = 10f
        const val MAX_EDITOR_FONT_SIZE = 28f
        const val DEFAULT_TERMINAL_FONT_SIZE = 13
        const val MIN_TERMINAL_FONT_SIZE = 9
        const val MAX_TERMINAL_FONT_SIZE = 24
        const val DEFAULT_UI_FONT_SCALE = 1.0f
        const val MIN_UI_FONT_SCALE = 0.85f
        const val MAX_UI_FONT_SCALE = 1.25f
        const val SETTINGS_PREFERENCE_FILE = "ide_settings"
    }
}
