package com.droplocal.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

// PRD §8 "Night Transfer"
val Background = Color(0xFF08090D)
val Surface = Color(0xFF11131A)
val Surface2 = Color(0xFF171A23)
val Primary = Color(0xFF8B7CFF)
val Secondary = Color(0xFF46E6C8)
val Accent = Color(0xFF74B9FF)
val Warning = Color(0xFFFFC970)
val Error = Color(0xFFFF6978)
val TextMain = Color(0xFFF8F9FC)
val Muted = Color(0xFF8991A3)

private val Scheme = darkColorScheme(
    primary = Primary,
    secondary = Secondary,
    tertiary = Accent,
    background = Background,
    surface = Surface,
    surfaceVariant = Surface2,
    error = Error,
    onPrimary = Color(0xFF100B2A),
    onSecondary = Color(0xFF06231D),
    onBackground = TextMain,
    onSurface = TextMain,
    onSurfaceVariant = Muted,
    onError = Color(0xFF28050B),
)

@Composable
fun DropLocalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, shapes = Shapes(
        small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp))) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxSize(),
            color = Background,
            contentColor = TextMain,
            content = content,
        )
    }
}
