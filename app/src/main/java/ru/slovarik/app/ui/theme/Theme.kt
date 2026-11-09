package ru.slovarik.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import ru.slovarik.app.data.AppSettings

private val LightColors = lightColorScheme(
    primary = Color(0xFF4255FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE1FF),
    onPrimaryContainer = Color(0xFF000B62),
    secondary = Color(0xFF5B5D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E0F9),
    onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF00696E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF9CF1F6),
    onTertiaryContainer = Color(0xFF002022),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceVariant = Color(0xFFE3E1EC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBDC2FF),
    onPrimary = Color(0xFF00159B),
    primaryContainer = Color(0xFF2338D8),
    onPrimaryContainer = Color(0xFFDFE1FF),
    secondary = Color(0xFFC4C5DD),
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434559),
    onSecondaryContainer = Color(0xFFE0E0F9),
    tertiary = Color(0xFF80D4DA),
    onTertiary = Color(0xFF00363A),
    tertiaryContainer = Color(0xFF004F53),
    onTertiaryContainer = Color(0xFF9CF1F6),
    background = Color(0xFF131318),
    surface = Color(0xFF131318),
    surfaceVariant = Color(0xFF46464F),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

/** Цвета для отметок «верно/неверно» — одинаковые в обеих темах. */
object StatusColors {
    val correct = Color(0xFF2E9E5B)
    val wrong = Color(0xFFD64545)
    val warning = Color(0xFFE08A00)
}

@Composable
fun SlovarikTheme(
    themeMode: Int = AppSettings.THEME_SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        AppSettings.THEME_LIGHT -> false
        AppSettings.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }

    // цвет значков в строке состояния должен соответствовать теме приложения, а не системы
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(colorScheme = colors, shapes = AppShapes, content = content)
}
