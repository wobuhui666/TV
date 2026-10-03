package com.fongmi.android.tv.ui.theme

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color as AndroidColor
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.fongmi.android.tv.R
import com.fongmi.android.tv.setting.Setting
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class JetStreamAccentPalette(
    val value: Int,
    @param:StringRes val labelRes: Int,
    @param:ColorInt val swatch: Int,
    @param:ColorInt val primary: Int,
    @param:ColorInt val onPrimary: Int,
    @param:ColorInt val primaryContainer: Int,
    @param:ColorInt val onPrimaryContainer: Int,
    @param:ColorInt val secondary: Int,
    @param:ColorInt val onSecondary: Int,
    @param:ColorInt val secondaryContainer: Int,
    @param:ColorInt val onSecondaryContainer: Int,
    @param:ColorInt val tertiary: Int,
    @param:ColorInt val onTertiary: Int,
    @param:ColorInt val tertiaryContainer: Int,
    @param:ColorInt val onTertiaryContainer: Int
)

object JetStreamPalette {

    @ColorInt private val SURFACE = 0xFF17181C.toInt()
    @ColorInt private val SURFACE_CONTAINER = 0xFF1D1F23.toInt()
    @ColorInt private val SURFACE_CONTAINER_HIGH = 0xFF27292E.toInt()
    @ColorInt private val SURFACE_CONTAINER_HIGHEST = 0xFF32343A.toInt()
    @ColorInt private val ON_SURFACE = 0xFFF2F2F2.toInt()
    @ColorInt private val ON_SURFACE_VARIANT = 0xFFB9BBC2.toInt()
    @ColorInt private val OUTLINE = 0xFF898B93.toInt()
    @ColorInt private val OUTLINE_VARIANT = 0xFF373940.toInt()
    @ColorInt private val ERROR = 0xFFFFB4AB.toInt()
    @ColorInt private val ON_ERROR = 0xFF690005.toInt()
    @ColorInt private val ERROR_CONTAINER = 0xFF93000A.toInt()
    @ColorInt private val ON_ERROR_CONTAINER = 0xFFFFDAD6.toInt()
    @ColorInt private val BACKGROUND = 0xFF101114.toInt()
    @ColorInt private val ON_BACKGROUND = 0xFFF2F2F2.toInt()
    @ColorInt private val SCRIM_LIGHT = 0x14FFFFFF
    @ColorInt private val SCRIM_MEDIUM = 0x29FFFFFF
    @ColorInt private val SCRIM_HEAVY = 0x3DFFFFFF
    @ColorInt private val OVERLAY_SURFACE = 0xF017181C.toInt()
    @ColorInt private val OVERLAY_SURFACE_LIGHT = 0xC017181C.toInt()
    @ColorInt private val STAR_BLUE_SEED = 0xFF4870E0.toInt()

    private val controlStates = arrayOf(
        intArrayOf(android.R.attr.state_focused),
        intArrayOf(android.R.attr.state_pressed),
        intArrayOf(android.R.attr.state_activated),
        intArrayOf(android.R.attr.state_checked),
        intArrayOf(android.R.attr.state_selected),
        intArrayOf()
    )

    private val defaultPalette = JetStreamAccentPalette(
        value = Setting.THEME_DEFAULT,
        labelRes = R.string.theme_star_blue,
        swatch = STAR_BLUE_SEED,
        primary = 0xFFAEBEF4.toInt(),
        onPrimary = 0xFF0E2F7A.toInt(),
        primaryContainer = 0xFF2B4690.toInt(),
        onPrimaryContainer = 0xFFD9E2FF.toInt(),
        secondary = 0xFFBEC6DC.toInt(),
        onSecondary = 0xFF283041.toInt(),
        secondaryContainer = 0xFF3F4759.toInt(),
        onSecondaryContainer = 0xFFDAE2F9.toInt(),
        tertiary = 0xFFDEBCDF.toInt(),
        onTertiary = 0xFF402843.toInt(),
        tertiaryContainer = 0xFF583E5A.toInt(),
        onTertiaryContainer = 0xFFFBD7FC.toInt()
    )

    private val fixedPresets = listOf(
        JetStreamAccentPalette(
            value = Setting.THEME_BILIBILI_PINK,
            labelRes = R.string.theme_bilibili_pink,
            swatch = Setting.THEME_BILIBILI_PINK,
            primary = 0xFFFFB1C8.toInt(),
            onPrimary = 0xFF650036.toInt(),
            primaryContainer = 0xFF8F004F.toInt(),
            onPrimaryContainer = 0xFFFFD8E7.toInt(),
            secondary = 0xFFE5BDCA.toInt(),
            onSecondary = 0xFF432934.toInt(),
            secondaryContainer = 0xFF5C3F4A.toInt(),
            onSecondaryContainer = 0xFFFFD8E7.toInt(),
            tertiary = 0xFFF2B8A4.toInt(),
            onTertiary = 0xFF4D2617.toInt(),
            tertiaryContainer = 0xFF673B2B.toInt(),
            onTertiaryContainer = 0xFFFFDACC.toInt()
        ),
        JetStreamAccentPalette(
            value = Setting.THEME_EMERALD_GREEN,
            labelRes = R.string.theme_emerald_green,
            swatch = Setting.THEME_EMERALD_GREEN,
            primary = 0xFF7FDBB6.toInt(),
            onPrimary = 0xFF003828.toInt(),
            primaryContainer = 0xFF00513B.toInt(),
            onPrimaryContainer = 0xFF9EF8D1.toInt(),
            secondary = 0xFFB3CCBE.toInt(),
            onSecondary = 0xFF20352B.toInt(),
            secondaryContainer = 0xFF374B40.toInt(),
            onSecondaryContainer = 0xFFCFE9D9.toInt(),
            tertiary = 0xFFA6CDDA.toInt(),
            onTertiary = 0xFF073541.toInt(),
            tertiaryContainer = 0xFF264B57.toInt(),
            onTertiaryContainer = 0xFFC2E9F6.toInt()
        ),
        JetStreamAccentPalette(
            value = Setting.THEME_AMBER_GOLD,
            labelRes = R.string.theme_amber_gold,
            swatch = Setting.THEME_AMBER_GOLD,
            primary = 0xFFF2C46D.toInt(),
            onPrimary = 0xFF432C00.toInt(),
            primaryContainer = 0xFF604100.toInt(),
            onPrimaryContainer = 0xFFFFE0A3.toInt(),
            secondary = 0xFFD7C3A1.toInt(),
            onSecondary = 0xFF3B2F16.toInt(),
            secondaryContainer = 0xFF53452A.toInt(),
            onSecondaryContainer = 0xFFF4DEBC.toInt(),
            tertiary = 0xFFB8CEA0.toInt(),
            onTertiary = 0xFF243515.toInt(),
            tertiaryContainer = 0xFF3A4C2A.toInt(),
            onTertiaryContainer = 0xFFD4EABB.toInt()
        ),
        JetStreamAccentPalette(
            value = Setting.THEME_OBSIDIAN_PURPLE,
            labelRes = R.string.theme_obsidian_purple,
            swatch = Setting.THEME_OBSIDIAN_PURPLE,
            primary = 0xFFD0BCFF.toInt(),
            onPrimary = 0xFF3B006D.toInt(),
            primaryContainer = 0xFF552C86.toInt(),
            onPrimaryContainer = 0xFFEBDDFF.toInt(),
            secondary = 0xFFCEC1DA.toInt(),
            onSecondary = 0xFF352D40.toInt(),
            secondaryContainer = 0xFF4C4358.toInt(),
            onSecondaryContainer = 0xFFEBDEF7.toInt(),
            tertiary = 0xFFF0B6CF.toInt(),
            onTertiary = 0xFF4B2537.toInt(),
            tertiaryContainer = 0xFF653B4E.toInt(),
            onTertiaryContainer = 0xFFFFD8E7.toInt()
        ),
        JetStreamAccentPalette(
            value = Setting.THEME_FLAME_RED,
            labelRes = R.string.theme_flame_red,
            swatch = Setting.THEME_FLAME_RED,
            primary = 0xFFFFB4A8.toInt(),
            onPrimary = 0xFF681000.toInt(),
            primaryContainer = 0xFF902100.toInt(),
            onPrimaryContainer = 0xFFFFDAD4.toInt(),
            secondary = 0xFFE7BDB6.toInt(),
            onSecondary = 0xFF442925.toInt(),
            secondaryContainer = 0xFF5D3F3A.toInt(),
            onSecondaryContainer = 0xFFFFDAD4.toInt(),
            tertiary = 0xFFDCC58F.toInt(),
            onTertiary = 0xFF3E2E04.toInt(),
            tertiaryContainer = 0xFF574419.toInt(),
            onTertiaryContainer = 0xFFF9E1A9.toInt()
        )
    )

    @JvmStatic
    fun presets(): List<JetStreamAccentPalette> {
        return listOf(defaultPalette, wallpaperPalette()) + fixedPresets
    }

    @JvmStatic
    fun current(): JetStreamAccentPalette {
        val value = Setting.getThemeColor()
        return when (value) {
            Setting.THEME_DEFAULT -> defaultPalette
            Setting.THEME_FOLLOW_WALLPAPER -> wallpaperPalette()
            else -> fixedPresets.firstOrNull { it.value == value } ?: fromSeed(value, R.string.setting_custom, value)
        }
    }

    @JvmStatic
    fun currentLabelRes(): Int {
        return current().labelRes
    }

    // Focus is deliberately neutral across presets. The chosen theme remains an
    // accent for supplementary information, never a tint over the artwork.
    private fun presentationPalette(): JetStreamAccentPalette {
        val accent = current()
        return accent.copy(
            primary = 0xFFF2F2F2.toInt(), onPrimary = 0xFF17181C.toInt(),
            primaryContainer = 0xFF41454E.toInt(), onPrimaryContainer = 0xFFF2F2F2.toInt(),
            secondary = 0xFFD2D3D8.toInt(), onSecondary = 0xFF17181C.toInt(),
            secondaryContainer = 0xFF35373D.toInt(), onSecondaryContainer = 0xFFF2F2F2.toInt(),
            tertiary = accent.primary, onTertiary = accent.onPrimary,
            tertiaryContainer = accent.primaryContainer, onTertiaryContainer = accent.onPrimaryContainer
        )
    }

    @JvmStatic
    fun colorScheme(): ColorScheme {
        val palette = presentationPalette()
        return darkColorScheme(
            primary = composeColor(palette.primary),
            onPrimary = composeColor(palette.onPrimary),
            primaryContainer = composeColor(palette.primaryContainer),
            onPrimaryContainer = composeColor(palette.onPrimaryContainer),
            secondary = composeColor(palette.secondary),
            onSecondary = composeColor(palette.onSecondary),
            secondaryContainer = composeColor(palette.secondaryContainer),
            onSecondaryContainer = composeColor(palette.onSecondaryContainer),
            tertiary = composeColor(palette.tertiary),
            onTertiary = composeColor(palette.onTertiary),
            tertiaryContainer = composeColor(palette.tertiaryContainer),
            onTertiaryContainer = composeColor(palette.onTertiaryContainer),
            error = JetStreamColors.Error,
            onError = JetStreamColors.OnError,
            errorContainer = JetStreamColors.ErrorContainer,
            onErrorContainer = JetStreamColors.OnErrorContainer,
            background = JetStreamColors.Background,
            onBackground = JetStreamColors.OnBackground,
            surface = JetStreamColors.Surface,
            onSurface = JetStreamColors.OnSurface,
            onSurfaceVariant = JetStreamColors.OnSurfaceVariant,
            surfaceVariant = JetStreamColors.SurfaceContainer,
            outline = JetStreamColors.Outline,
            outlineVariant = JetStreamColors.OutlineVariant
        )
    }

    @JvmStatic
    @ColorInt
    fun primaryInt(): Int = presentationPalette().primary

    @JvmStatic
    @ColorInt
    fun onPrimaryInt(): Int = presentationPalette().onPrimary

    @JvmStatic
    @ColorInt
    fun primaryContainerInt(): Int = presentationPalette().primaryContainer

    @JvmStatic
    @ColorInt
    fun onPrimaryContainerInt(): Int = presentationPalette().onPrimaryContainer

    @JvmStatic
    @ColorInt
    fun secondaryInt(): Int = presentationPalette().secondary

    @JvmStatic
    @ColorInt
    fun secondaryContainerInt(): Int = presentationPalette().secondaryContainer

    @JvmStatic
    @ColorInt
    fun surfaceInt(): Int = SURFACE

    @JvmStatic
    @ColorInt
    fun shadowColor(): Int = 0x40000000

    @JvmStatic
    fun controlText(): ColorStateList {
        val palette = presentationPalette()
        return ColorStateList(
            controlStates,
            intArrayOf(
                palette.onPrimaryContainer,
                palette.onPrimaryContainer,
                palette.onSecondaryContainer,
                palette.onSecondaryContainer,
                palette.onSecondaryContainer,
                ON_SURFACE_VARIANT
            )
        )
    }

    @JvmStatic
    fun controlContainer(): ColorStateList {
        val palette = presentationPalette()
        return ColorStateList(
            controlStates,
            intArrayOf(
                0xFF41454E.toInt(),
                0xFF505560.toInt(),
                0xFF35383F.toInt(),
                0xFF35383F.toInt(),
                0xFF35383F.toInt(),
                0xFF24262B.toInt()
            )
        )
    }

    @JvmStatic
    fun controlOutline(): ColorStateList {
        val palette = presentationPalette()
        return ColorStateList(
            controlStates,
            intArrayOf(
                0xFFC5C8D0.toInt(),
                0xFFC5C8D0.toInt(),
                OUTLINE,
                OUTLINE,
                OUTLINE,
                OUTLINE_VARIANT
            )
        )
    }

    @JvmStatic
    @ColorInt
    fun resolveColor(context: Context, @ColorRes colorRes: Int): Int {
        if (isDynamicSelector(colorRes)) return resolveColorStateList(context, colorRes).defaultColor
        val palette = presentationPalette()
        return when (colorRes) {
            R.color.jetstream_primary -> palette.primary
            R.color.jetstream_on_primary -> palette.onPrimary
            R.color.jetstream_primary_container -> palette.primaryContainer
            R.color.jetstream_on_primary_container -> palette.onPrimaryContainer
            R.color.jetstream_secondary -> palette.secondary
            R.color.jetstream_on_secondary -> palette.onSecondary
            R.color.jetstream_secondary_container -> palette.secondaryContainer
            R.color.jetstream_on_secondary_container -> palette.onSecondaryContainer
            R.color.jetstream_tertiary -> palette.tertiary
            R.color.jetstream_on_tertiary -> palette.onTertiary
            R.color.jetstream_tertiary_container -> palette.tertiaryContainer
            R.color.jetstream_on_tertiary_container -> palette.onTertiaryContainer
            R.color.jetstream_surface -> SURFACE
            R.color.jetstream_surface_container -> SURFACE_CONTAINER
            R.color.jetstream_surface_container_high -> SURFACE_CONTAINER_HIGH
            R.color.jetstream_surface_container_highest -> SURFACE_CONTAINER_HIGHEST
            R.color.jetstream_on_surface -> ON_SURFACE
            R.color.jetstream_on_surface_variant -> ON_SURFACE_VARIANT
            R.color.jetstream_outline -> OUTLINE
            R.color.jetstream_outline_variant -> OUTLINE_VARIANT
            R.color.jetstream_error -> ERROR
            R.color.jetstream_on_error -> ON_ERROR
            R.color.jetstream_error_container -> ERROR_CONTAINER
            R.color.jetstream_on_error_container -> ON_ERROR_CONTAINER
            R.color.jetstream_background -> BACKGROUND
            R.color.jetstream_on_background -> ON_BACKGROUND
            R.color.jetstream_scrim_light -> SCRIM_LIGHT
            R.color.jetstream_scrim_medium -> SCRIM_MEDIUM
            R.color.jetstream_scrim_heavy -> SCRIM_HEAVY
            R.color.jetstream_overlay_surface -> OVERLAY_SURFACE
            R.color.jetstream_overlay_surface_light -> OVERLAY_SURFACE_LIGHT
            else -> ContextCompat.getColor(context, colorRes)
        }
    }

    @JvmStatic
    fun resolveColorStateList(context: Context, @ColorRes colorRes: Int): ColorStateList {
        return when (colorRes) {
            R.color.jetstream_control_text,
            R.color.jetstream_list_supporting_text -> controlText()
            R.color.jetstream_list_title_text,
            R.color.text -> titleControlText(ON_SURFACE)
            R.color.jetstream_setting_title_text,
            R.color.jetstream_slider_title_text -> primarySelectedText(ON_SURFACE)
            R.color.jetstream_setting_value_text,
            R.color.jetstream_slider_value_text -> primarySelectedText(ON_SURFACE_VARIANT)
            R.color.jetstream_file_icon_tint -> fileIconTint()
            R.color.jetstream_control_container -> controlContainer()
            R.color.jetstream_control_outline -> controlOutline()
            R.color.jetstream_switch_thumb -> switchThumb()
            R.color.jetstream_switch_track -> switchTrack()
            R.color.jetstream_primary,
            R.color.jetstream_on_primary,
            R.color.jetstream_primary_container,
            R.color.jetstream_on_primary_container,
            R.color.jetstream_secondary,
            R.color.jetstream_on_secondary,
            R.color.jetstream_secondary_container,
            R.color.jetstream_on_secondary_container,
            R.color.jetstream_tertiary,
            R.color.jetstream_on_tertiary,
            R.color.jetstream_tertiary_container,
            R.color.jetstream_on_tertiary_container -> ColorStateList.valueOf(resolveColor(context, colorRes))
            else -> ContextCompat.getColorStateList(context, colorRes) ?: ColorStateList.valueOf(resolveColor(context, colorRes))
        }
    }

    private fun wallpaperPalette(): JetStreamAccentPalette {
        val seed = Setting.getWallColor().takeIf { it != 0 } ?: STAR_BLUE_SEED
        return fromSeed(Setting.THEME_FOLLOW_WALLPAPER, R.string.theme_follow_wallpaper, seed)
    }

    private fun fromSeed(value: Int, @StringRes labelRes: Int, @ColorInt rawSeed: Int): JetStreamAccentPalette {
        val seed = opaque(rawSeed)
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(seed, hsv)
        if (hsv[1] < 0.12f) return neutralFromSeed(value, labelRes, seed, hsv[2])

        val hue = hsv[0]
        val saturation = hsv[1].coerceIn(0.35f, 0.82f)
        val tertiaryHue = normalizeHue(hue + 52f)
        val primary = hsvColor(hue, saturation, 0.86f)
        val primaryContainer = hsvColor(hue, saturation * 0.82f, 0.34f)
        val secondary = hsvColor(hue, max(0.22f, saturation * 0.34f), 0.78f)
        val secondaryContainer = hsvColor(hue, max(0.18f, saturation * 0.28f), 0.31f)
        val tertiary = hsvColor(tertiaryHue, max(0.28f, saturation * 0.58f), 0.82f)
        val tertiaryContainer = hsvColor(tertiaryHue, max(0.24f, saturation * 0.48f), 0.34f)

        return JetStreamAccentPalette(
            value = value,
            labelRes = labelRes,
            swatch = seed,
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = onColor(primaryContainer),
            secondary = secondary,
            onSecondary = onColor(secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onColor(secondaryContainer),
            tertiary = tertiary,
            onTertiary = onColor(tertiary),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onColor(tertiaryContainer)
        )
    }

    private fun neutralFromSeed(value: Int, @StringRes labelRes: Int, @ColorInt seed: Int, seedValue: Float): JetStreamAccentPalette {
        val containerValue = min(0.38f, max(0.24f, seedValue * 0.88f))
        val primaryContainer = hsvColor(0f, 0f, containerValue)
        val secondaryContainer = hsvColor(0f, 0f, max(0.28f, containerValue * 0.92f))
        val tertiaryContainer = hsvColor(0f, 0f, max(0.30f, containerValue * 0.86f))
        val primary = hsvColor(0f, 0f, 0.78f)
        val secondary = hsvColor(0f, 0.02f, 0.76f)
        val tertiary = hsvColor(0f, 0.04f, 0.74f)
        return JetStreamAccentPalette(
            value = value,
            labelRes = labelRes,
            swatch = seed,
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = onColor(primaryContainer),
            secondary = secondary,
            onSecondary = onColor(secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onColor(secondaryContainer),
            tertiary = tertiary,
            onTertiary = onColor(tertiary),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onColor(tertiaryContainer)
        )
    }

    private fun primarySelectedText(@ColorInt defaultColor: Int): ColorStateList {
        val palette = presentationPalette()
        return ColorStateList(
            controlStates,
            intArrayOf(
                palette.onPrimaryContainer,
                palette.onPrimaryContainer,
                palette.onPrimaryContainer,
                palette.onPrimaryContainer,
                palette.onPrimaryContainer,
                defaultColor
            )
        )
    }

    private fun titleControlText(@ColorInt defaultColor: Int): ColorStateList {
        val palette = presentationPalette()
        return ColorStateList(
            controlStates,
            intArrayOf(
                palette.onPrimaryContainer,
                palette.onPrimaryContainer,
                palette.onSecondaryContainer,
                palette.onSecondaryContainer,
                palette.onSecondaryContainer,
                defaultColor
            )
        )
    }

    private fun fileIconTint(): ColorStateList {
        val palette = presentationPalette()
        return ColorStateList(
            controlStates,
            intArrayOf(
                palette.onPrimaryContainer,
                palette.onPrimaryContainer,
                palette.onPrimaryContainer,
                palette.onPrimaryContainer,
                palette.onPrimaryContainer,
                palette.primary
            )
        )
    }

    private fun switchThumb(): ColorStateList {
        val palette = presentationPalette()
        return ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_focused),
                intArrayOf()
            ),
            intArrayOf(OUTLINE, palette.onPrimary, palette.primary, ON_SURFACE_VARIANT)
        )
    }

    private fun switchTrack(): ColorStateList {
        val palette = presentationPalette()
        return ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_focused),
                intArrayOf()
            ),
            intArrayOf(OUTLINE_VARIANT, palette.primaryContainer, palette.secondaryContainer, OUTLINE_VARIANT)
        )
    }

    private fun isDynamicSelector(@ColorRes colorRes: Int): Boolean {
        return when (colorRes) {
            R.color.jetstream_control_text,
            R.color.jetstream_list_supporting_text,
            R.color.jetstream_list_title_text,
            R.color.text,
            R.color.jetstream_setting_title_text,
            R.color.jetstream_setting_value_text,
            R.color.jetstream_slider_title_text,
            R.color.jetstream_slider_value_text,
            R.color.jetstream_file_icon_tint,
            R.color.jetstream_control_container,
            R.color.jetstream_control_outline,
            R.color.jetstream_switch_thumb,
            R.color.jetstream_switch_track -> true
            else -> false
        }
    }

    @ColorInt
    private fun hsvColor(hue: Float, saturation: Float, value: Float): Int {
        return AndroidColor.HSVToColor(floatArrayOf(normalizeHue(hue), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f)))
    }

    private fun normalizeHue(hue: Float): Float {
        var normalized = hue % 360f
        if (normalized < 0f) normalized += 360f
        return normalized
    }

    @ColorInt
    private fun opaque(@ColorInt color: Int): Int {
        return if (AndroidColor.alpha(color) == 0) color or AndroidColor.BLACK else color
    }

    @ColorInt
    private fun onColor(@ColorInt color: Int): Int {
        return if (luminance(color) > 0.52) 0xFF101318.toInt() else 0xFFFFFFFF.toInt()
    }

    private fun luminance(@ColorInt color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) normalized / 12.92 else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(AndroidColor.red(color)) + 0.7152 * channel(AndroidColor.green(color)) + 0.0722 * channel(AndroidColor.blue(color))
    }

    @ColorInt
    private fun withAlpha(@ColorInt color: Int, alpha: Float): Int {
        return ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (color and 0x00FFFFFF)
    }

    private fun composeColor(@ColorInt color: Int): Color {
        return Color(color.toLong() and 0xFFFFFFFF)
    }
}
