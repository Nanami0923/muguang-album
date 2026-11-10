package com.muguang.album.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val AlbumColors = darkColorScheme(
    primary = Color(0xFFD4BFFF),
    onPrimary = Color(0xFF35205A),
    primaryContainer = Color(0xFF493466),
    onPrimaryContainer = Color(0xFFECDDFF),
    secondary = Color(0xFFC8C4D5),
    onSecondary = Color(0xFF30303C),
    secondaryContainer = Color(0xFF3B3949),
    onSecondaryContainer = Color(0xFFE7E1F5),
    tertiary = Color(0xFFE3BFC1),
    onTertiary = Color(0xFF44282D),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE8E6EE),
    surface = Color(0xFF17181E),
    onSurface = Color(0xFFE8E6EE),
    surfaceVariant = Color(0xFF45434F),
    onSurfaceVariant = Color(0xFFC8C3D2),
    surfaceContainerLowest = Color(0xFF101116),
    surfaceContainerLow = Color(0xFF1B1C23),
    surfaceContainer = Color(0xFF202129),
    surfaceContainerHigh = Color(0xFF292A33),
    surfaceContainerHighest = Color(0xFF34343E),
    outline = Color(0xFF918B9B),
    outlineVariant = Color(0xFF46434F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun MuguangTheme(dynamicColor: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= 31) {
        dynamicDarkColorScheme(context)
    } else AlbumColors
    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
