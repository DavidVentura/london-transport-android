package dev.davidv.londontransport.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Tonal palette of the cyan seed, for phones without wallpaper colours. */
private val LightBrand = lightColorScheme(
  primary = Color(0xFF00687A),
  onPrimary = Color(0xFFFFFFFF),
  primaryContainer = Color(0xFFACEDFF),
  onPrimaryContainer = Color(0xFF001F26),
  secondary = Color(0xFF4B6269),
  onSecondary = Color(0xFFFFFFFF),
  secondaryContainer = Color(0xFFCEE7EF),
  onSecondaryContainer = Color(0xFF061F25),
  tertiary = Color(0xFF565D7E),
  onTertiary = Color(0xFFFFFFFF),
  tertiaryContainer = Color(0xFFDDE1FF),
  onTertiaryContainer = Color(0xFF121A37),
  background = Color(0xFFF5FAFC),
  onBackground = Color(0xFF171D1E),
  surface = Color(0xFFF5FAFC),
  onSurface = Color(0xFF171D1E),
  surfaceVariant = Color(0xFFDBE4E7),
  onSurfaceVariant = Color(0xFF3F484B),
  outline = Color(0xFF6F797B),
  outlineVariant = Color(0xFFBFC8CB),
  surfaceContainerLowest = Color(0xFFFFFFFF),
  surfaceContainerLow = Color(0xFFEFF5F6),
  surfaceContainer = Color(0xFFE9EFF0),
  surfaceContainerHigh = Color(0xFFE3E9EB),
  surfaceContainerHighest = Color(0xFFDEE3E5),
)

private val DarkBrand = darkColorScheme(
  primary = Color(0xFF85D2E7),
  onPrimary = Color(0xFF003640),
  primaryContainer = Color(0xFF004E5C),
  onPrimaryContainer = Color(0xFFACEDFF),
  secondary = Color(0xFFB2CBD3),
  onSecondary = Color(0xFF1D343A),
  secondaryContainer = Color(0xFF344A51),
  onSecondaryContainer = Color(0xFFCEE7EF),
  tertiary = Color(0xFFBEC4EB),
  onTertiary = Color(0xFF282F4D),
  tertiaryContainer = Color(0xFF3E4565),
  onTertiaryContainer = Color(0xFFDDE1FF),
  background = Color(0xFF0F1416),
  onBackground = Color(0xFFDEE3E5),
  surface = Color(0xFF0F1416),
  onSurface = Color(0xFFDEE3E5),
  surfaceVariant = Color(0xFF3F484B),
  onSurfaceVariant = Color(0xFFBFC8CB),
  outline = Color(0xFF899295),
  outlineVariant = Color(0xFF3F484B),
  surfaceContainerLowest = Color(0xFF0A0F11),
  surfaceContainerLow = Color(0xFF171D1E),
  surfaceContainer = Color(0xFF1B2122),
  surfaceContainerHigh = Color(0xFF252B2C),
  surfaceContainerHighest = Color(0xFF303637),
)

@Composable
fun LondonTransportTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  val context = LocalContext.current
  val colorScheme =
    when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      darkTheme -> DarkBrand
      else -> LightBrand
    }

  MaterialTheme(colorScheme = colorScheme, content = content)
}
