package com.alexi.softface.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.alexi.softface.R

/** Atkinson Hyperlegible: a font made to be easy to read. */
val Readable = FontFamily(
    Font(R.font.atkinson_regular, FontWeight.Normal),
    Font(R.font.atkinson_bold, FontWeight.Bold),
)

val Rose = Color(0xFFF29BB0)
val Panel = Color(0xF01A1A20)
val TextMain = Color(0xFFF4F1F3)
val TextSoft = Color(0xFFC9C3C8)

private val colours = darkColorScheme(
    primary = Rose,
    onPrimary = Color(0xFF2A1020),
    secondary = Color(0xFFB9A3FF),
    background = Color(0xFF101014),
    surface = Color(0xFF1A1A20),
    onSurface = TextMain,
    onBackground = TextMain,
    surfaceVariant = Color(0xFF2A2A32),
    onSurfaceVariant = TextSoft,
)

private fun style(size: Int, bold: Boolean = false) =
    TextStyle(fontFamily = Readable, fontSize = size.sp, lineHeight = (size * 1.4).sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, letterSpacing = 0.3.sp)

@Composable
fun SoftFaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colours,
        typography = Typography(
            titleLarge = style(24, true),
            titleMedium = style(20, true),
            bodyLarge = style(19),
            bodyMedium = style(17),
            labelLarge = style(18, true),
            labelMedium = style(16, true),
        ),
        content = content,
    )
}
