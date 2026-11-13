package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
  primary = SapphirePrimaryLight,
  onPrimary = SapphireOnPrimaryLight,
  primaryContainer = SapphireContainerLight,
  onPrimaryContainer = SapphireOnContainerLight,
  secondary = SeafoamSecondaryLight,
  onSecondary = SeafoamOnSecondaryLight,
  secondaryContainer = SeafoamContainerLight,
  onSecondaryContainer = SeafoamOnContainerLight,
  tertiary = AmberTertiaryLight,
  onTertiary = AmberOnTertiaryLight,
  tertiaryContainer = AmberContainerLight,
  onTertiaryContainer = AmberOnContainerLight,
  error = ErrorLight,
  errorContainer = ErrorContainerLight,
  onErrorContainer = OnErrorContainerLight,
  background = BackgroundLight,
  onBackground = OnSurfaceLight,
  surface = SurfaceLight,
  onSurface = OnSurfaceLight,
  surfaceVariant = SurfaceVariantLight,
  onSurfaceVariant = OnSurfaceVariantLight,
  outline = OutlineLight,
)

private val DarkColorScheme = darkColorScheme(
  primary = SapphirePrimaryDark,
  onPrimary = SapphireOnPrimaryDark,
  primaryContainer = SapphireContainerDark,
  onPrimaryContainer = SapphireOnContainerDark,
  secondary = SeafoamSecondaryDark,
  onSecondary = SeafoamOnSecondaryDark,
  secondaryContainer = SeafoamContainerDark,
  onSecondaryContainer = SeafoamOnContainerDark,
  tertiary = AmberTertiaryDark,
  onTertiary = AmberOnTertiaryDark,
  tertiaryContainer = AmberContainerDark,
  onTertiaryContainer = AmberOnContainerDark,
  error = ErrorDark,
  errorContainer = ErrorContainerDark,
  onErrorContainer = OnErrorContainerDark,
  background = BackgroundDark,
  onBackground = OnSurfaceDark,
  surface = SurfaceDark,
  onSurface = OnSurfaceDark,
  surfaceVariant = SurfaceVariantDark,
  onSurfaceVariant = OnSurfaceVariantDark,
  outline = OutlineDark,
)

val SafeNestShapes = Shapes(
  small = RoundedCornerShape(10.dp),
  medium = RoundedCornerShape(16.dp),
  large = RoundedCornerShape(22.dp),
  extraLarge = RoundedCornerShape(28.dp),
)

enum class ThemePreference(val label: String) {
  SYSTEM("System Default"),
  LIGHT("Daylight"),
  DARK("Nightwatch"),
}

@Composable
fun SafeNestTheme(
  themePreference: ThemePreference = ThemePreference.SYSTEM,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val darkTheme = when (themePreference) {
    ThemePreference.SYSTEM -> isSystemInDarkTheme()
    ThemePreference.LIGHT -> false
    ThemePreference.DARK -> true
  }

  val colorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      val context = LocalContext.current
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
  }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = SafeNestTypography,
    shapes = SafeNestShapes,
    content = content,
  )
}
