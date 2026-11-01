package sbphone.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import sbphone.app.R

private val Dark: ColorScheme = darkColorScheme(
    primary = Color(0xFFE2C27A),
    onPrimary = Color(0xFF2A2005),
    secondary = Color(0xFF9DB8D9),
    secondaryContainer = Color(0xFF3A3424),
    onSecondaryContainer = Color(0xFFE2C27A),
    background = Color(0xFF15171C),
    onBackground = Color(0xFFE6E2D9),
    surface = Color(0xFF15171C),
    onSurface = Color(0xFFE6E2D9),
    surfaceVariant = Color(0xFF242833),
    onSurfaceVariant = Color(0xFFB9B4A8),
    surfaceContainer = Color(0xFF1D2028),
    surfaceContainerHigh = Color(0xFF242833),
    surfaceContainerLow = Color(0xFF1A1C22),
    outline = Color(0xFF5B606C),
)

private val Light: ColorScheme = lightColorScheme(
    primary = Color(0xFF7A5A12),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF34557A),
    secondaryContainer = Color(0xFFEFE2C0),
    onSecondaryContainer = Color(0xFF4A3608),
    background = Color(0xFFFBF8F1),
    onBackground = Color(0xFF1E1C18),
    surface = Color(0xFFFBF8F1),
    onSurface = Color(0xFF1E1C18),
    surfaceVariant = Color(0xFFEDE7DA),
    onSurfaceVariant = Color(0xFF55504A),
    surfaceContainer = Color(0xFFF3EEE3),
    surfaceContainerHigh = Color(0xFFEDE7DA),
    surfaceContainerLow = Color(0xFFF7F3EA),
    outline = Color(0xFF8E887D),
)

/** Highlight colours, chosen to read in both themes. Meaning is never colour alone: each has a name. */
val highlightNames = listOf("Gold", "Green", "Blue", "Rose")
fun highlightColor(index: Int, dark: Boolean): Color = when (index) {
    0 -> if (dark) Color(0x55D8B34A) else Color(0x66F2D27A)
    1 -> if (dark) Color(0x5543A36B) else Color(0x6696D7AE)
    2 -> if (dark) Color(0x554F86C6) else Color(0x669DC3EC)
    else -> if (dark) Color(0x55C65F7E) else Color(0x66EFA9BD)
}

/** Ezra SIL: Hebrew with full pointing and cantillation, independent of the phone's own fonts. */
val HebrewFont = FontFamily(Font(R.font.ezra_sil))

enum class ThemeChoice { System, Dark, Light }

@Composable
fun isDark(choice: ThemeChoice): Boolean = when (choice) {
    ThemeChoice.System -> isSystemInDarkTheme()
    ThemeChoice.Dark -> true
    ThemeChoice.Light -> false
}

@Composable
fun SbTheme(choice: ThemeChoice, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isDark(choice)) Dark else Light, content = content)
}
