package com.fongmi.android.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R

/**
 * JetStream Material Design 3 Color Scheme
 * 统一的深色主题配色方案，中性焦点、内容优先的电视端设计
 */
object JetStreamColors {
    // Surface Colors - 表面层级颜色
    val Surface = Color(0xFF17181C)
    val SurfaceContainer = Color(0xFF1D1F23)
    val SurfaceContainerHigh = Color(0xFF27292E)
    val SurfaceContainerHighest = Color(0xFF32343A)
    val OnSurface = Color(0xFFF2F2F2)
    val OnSurfaceVariant = Color(0xFFB9BBC2)

    // Outline Colors - 边框颜色
    val Outline = Color(0xFF898B93)
    val OutlineVariant = Color(0xFF373940)

    // Primary Colors - 主色调
    val Primary = Color(0xFFF2F2F2)
    val OnPrimary = Color(0xFF17181C)
    val PrimaryContainer = Color(0xFF41454E)
    val OnPrimaryContainer = Color(0xFFF2F2F2)

    // Secondary Colors - 次要色调
    val Secondary = Color(0xFFD2D3D8)
    val OnSecondary = Color(0xFF17181C)
    val SecondaryContainer = Color(0xFF35373D)
    val OnSecondaryContainer = Color(0xFFF2F2F2)

    // Tertiary Colors - 第三色调
    val Tertiary = Color(0xFFDEBCDF)
    val OnTertiary = Color(0xFF402843)
    val TertiaryContainer = Color(0xFF583E5A)
    val OnTertiaryContainer = Color(0xFFFBD7FC)

    // Error Colors - 错误色调
    val Error = Color(0xFFFFB4AB)
    val OnError = Color(0xFF690005)
    val ErrorContainer = Color(0xFF93000A)
    val OnErrorContainer = Color(0xFFFFDAD6)

    // Background Colors - 背景颜色
    val Background = Color(0xFF101114)
    val OnBackground = Color(0xFFF2F2F2)

}

object JetStreamThemeController {
    private val refreshToken = mutableIntStateOf(0)

    val version: Int
        get() = refreshToken.intValue

    @JvmStatic
    fun refresh() {
        refreshToken.intValue += 1
    }
}

/**
 * JetStream 品牌字体：MiSans（子集化，常用字覆盖，罕见字回退系统字体）
 */
val JetStreamFontFamily = FontFamily(
    Font(R.font.misans_regular, FontWeight.Normal),
    Font(R.font.misans_medium, FontWeight.Medium),
    Font(R.font.misans_semibold, FontWeight.SemiBold)
)

/**
 * JetStream Typography
 * 统一的文字排版样式
 */
val JetStreamTypography = Typography(
    // Display styles - 大标题
    displayLarge = TextStyle(
        fontFamily = JetStreamFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = JetStreamFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = JetStreamFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),

    // Headline styles - 标题
    headlineLarge = TextStyle(
        fontFamily = JetStreamFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = JetStreamFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = JetStreamFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    // Title styles - 副标题
    titleLarge = TextStyle(
        fontFamily = JetStreamFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = JetStreamFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = JetStreamFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),

    // Body styles - 正文
    bodyLarge = TextStyle(
        fontFamily = JetStreamFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = JetStreamFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = JetStreamFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),

    // Label styles - 标签
    labelLarge = TextStyle(
        fontFamily = JetStreamFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = JetStreamFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = JetStreamFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

/**
 * JetStream Theme Composable
 * 应用主题的组合函数
 */
@Composable
fun JetStreamTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = remember(JetStreamThemeController.version) { JetStreamPalette.colorScheme() }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = JetStreamTypography,
        content = content
    )
}
