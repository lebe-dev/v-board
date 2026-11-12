package ru.tinyops.android.vboard.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import ru.tinyops.android.vboard.R

/**
 * Every color the keyboard draws with. Key and strip colors may be translucent: they are painted
 * over the (possibly gradient) background. Previews and popups cover other keys and stay opaque.
 */
data class KeyboardPalette(
    @param:ColorInt val backgroundTop: Int,
    @param:ColorInt val backgroundBottom: Int,
    @param:ColorInt val key: Int,
    @param:ColorInt val keyPressed: Int,
    @param:ColorInt val functionKey: Int,
    @param:ColorInt val functionKeyPressed: Int,
    @param:ColorInt val actionKey: Int,
    @param:ColorInt val actionKeyPressed: Int,
    @param:ColorInt val actionIcon: Int,
    @param:ColorInt val label: Int,
    @param:ColorInt val hint: Int,
    @param:ColorInt val preview: Int,
    @param:ColorInt val popupSelected: Int,
    @param:ColorInt val popupSelectedLabel: Int,
    @param:ColorInt val trail: Int,
    @param:ColorInt val suggestion: Int,
    @param:ColorInt val suggestionDivider: Int,
    @param:ColorInt val suggestionPressed: Int,
    @param:ColorInt val stripIcon: Int,
    @param:ColorInt val voiceActive: Int,
    @param:ColorInt val voiceActiveIcon: Int,
    @param:ColorInt val voiceHalo: Int,
    @param:ColorInt val voiceStatus: Int,
) {
    val isGradient: Boolean get() = backgroundTop != backgroundBottom

    companion object {
        /** The built-in light or dark colors from `res/values(-night)/colors.xml`. */
        fun fromResources(context: Context): KeyboardPalette {
            fun color(id: Int) = context.getColor(id)
            return KeyboardPalette(
                backgroundTop = color(R.color.kb_background),
                backgroundBottom = color(R.color.kb_background),
                key = color(R.color.kb_key),
                keyPressed = color(R.color.kb_key_pressed),
                functionKey = color(R.color.kb_function_key),
                functionKeyPressed = color(R.color.kb_function_key_pressed),
                actionKey = color(R.color.kb_action_key),
                actionKeyPressed = color(R.color.kb_action_key_pressed),
                actionIcon = color(R.color.kb_action_icon),
                label = color(R.color.kb_label),
                hint = color(R.color.kb_hint),
                preview = color(R.color.kb_preview),
                popupSelected = color(R.color.kb_popup_selected),
                popupSelectedLabel = color(R.color.kb_popup_selected_label),
                trail = color(R.color.kb_trail),
                suggestion = color(R.color.kb_suggestion),
                suggestionDivider = color(R.color.kb_suggestion_divider),
                suggestionPressed = color(R.color.kb_suggestion_pressed),
                stripIcon = color(R.color.kb_strip_icon),
                voiceActive = color(R.color.kb_voice_active),
                voiceActiveIcon = color(R.color.kb_voice_active_icon),
                voiceHalo = color(R.color.kb_voice_halo),
                voiceStatus = color(R.color.kb_voice_status),
            )
        }

        /** The built-in colors for a fixed light ([night] = false) or dark mode, whatever the system uses. */
        fun fromResources(
            context: Context,
            night: Boolean,
        ): KeyboardPalette {
            val config = Configuration(context.resources.configuration)
            val mode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            return fromResources(context.createConfigurationContext(config))
        }

        /** Material You: the wallpaper-based system palette of Android 12+, light or dark. */
        fun dynamic(
            context: Context,
            night: Boolean,
        ): KeyboardPalette {
            fun color(id: Int) = context.getColor(id)
            return if (night) {
                val accent = color(android.R.color.system_accent1_200)
                val onAccent = color(android.R.color.system_accent1_800)
                KeyboardPalette(
                    backgroundTop = color(android.R.color.system_neutral1_900),
                    backgroundBottom = color(android.R.color.system_neutral1_900),
                    key = color(android.R.color.system_neutral2_700),
                    keyPressed = color(android.R.color.system_neutral2_600),
                    functionKey = color(android.R.color.system_neutral2_800),
                    functionKeyPressed = color(android.R.color.system_neutral2_700),
                    actionKey = accent,
                    actionKeyPressed = color(android.R.color.system_accent1_300),
                    actionIcon = onAccent,
                    label = color(android.R.color.system_neutral1_50),
                    hint = color(android.R.color.system_neutral2_200),
                    preview = color(android.R.color.system_neutral2_600),
                    popupSelected = accent,
                    popupSelectedLabel = onAccent,
                    trail = accent,
                    suggestion = color(android.R.color.system_neutral1_100),
                    suggestionDivider = color(android.R.color.system_neutral2_700),
                    suggestionPressed = color(android.R.color.system_neutral2_800),
                    stripIcon = color(android.R.color.system_neutral2_200),
                    voiceActive = accent,
                    voiceActiveIcon = onAccent,
                    voiceHalo = withAlpha(accent, HALO_ALPHA),
                    voiceStatus = color(android.R.color.system_neutral2_200),
                )
            } else {
                val accent = color(android.R.color.system_accent1_600)
                val onAccent = color(android.R.color.system_accent1_0)
                KeyboardPalette(
                    backgroundTop = color(android.R.color.system_neutral2_50),
                    backgroundBottom = color(android.R.color.system_neutral2_50),
                    key = color(android.R.color.system_neutral1_10),
                    keyPressed = color(android.R.color.system_neutral2_100),
                    functionKey = color(android.R.color.system_accent2_100),
                    functionKeyPressed = color(android.R.color.system_accent2_200),
                    actionKey = accent,
                    actionKeyPressed = color(android.R.color.system_accent1_700),
                    actionIcon = onAccent,
                    label = color(android.R.color.system_neutral1_900),
                    hint = color(android.R.color.system_neutral2_600),
                    preview = color(android.R.color.system_neutral1_10),
                    popupSelected = accent,
                    popupSelectedLabel = onAccent,
                    trail = color(android.R.color.system_accent1_500),
                    suggestion = color(android.R.color.system_neutral1_900),
                    suggestionDivider = color(android.R.color.system_neutral2_200),
                    suggestionPressed = color(android.R.color.system_neutral2_100),
                    stripIcon = color(android.R.color.system_neutral2_700),
                    voiceActive = accent,
                    voiceActiveIcon = onAccent,
                    voiceHalo = withAlpha(accent, HALO_ALPHA),
                    voiceStatus = color(android.R.color.system_neutral2_600),
                )
            }
        }

        /**
         * A palette built around a background (a flat color when [top] == [bottom], else a vertical
         * gradient) and an accent for the action key:
         * keys are white (on dark backgrounds) or black (on light ones) veils over the background.
         */
        fun tinted(
            @ColorInt top: Int,
            @ColorInt bottom: Int,
            @ColorInt accent: Int,
            dark: Boolean,
        ): KeyboardPalette {
            val ink = if (dark) Color.WHITE else INK_DARK
            val veil = if (dark) Color.WHITE else Color.BLACK
            val middle = ColorUtils.blendARGB(top, bottom, HALF)
            val onAccent = contentColorFor(accent)
            val keyVeil = if (dark) darkKeyVeil(top, bottom) else 0f
            return KeyboardPalette(
                backgroundTop = top,
                backgroundBottom = bottom,
                key = if (dark) withAlpha(veil, keyVeil) else withAlpha(Color.WHITE, KEY_LIGHT),
                keyPressed = if (dark) withAlpha(veil, keyVeil + PRESSED_STEP) else withAlpha(veil, LIGHT_PRESSED),
                functionKey = withAlpha(veil, if (dark) FUNCTION_VEIL_DARK else FUNCTION_VEIL_LIGHT),
                functionKeyPressed =
                    withAlpha(
                        veil,
                        if (dark) FUNCTION_VEIL_DARK + PRESSED_STEP else FUNCTION_PRESSED_LIGHT,
                    ),
                actionKey = accent,
                actionKeyPressed = ColorUtils.blendARGB(accent, Color.BLACK, ACTION_PRESSED_SHADE),
                actionIcon = onAccent,
                label = ink,
                hint = withAlpha(ink, HINT_ALPHA),
                preview =
                    if (dark) {
                        ColorUtils.compositeColors(withAlpha(veil, PREVIEW_VEIL), middle)
                    } else {
                        Color.WHITE
                    },
                popupSelected = accent,
                popupSelectedLabel = onAccent,
                trail = if (dark) ColorUtils.blendARGB(accent, Color.WHITE, TRAIL_LIGHTEN) else accent,
                suggestion = withAlpha(ink, SUGGESTION_ALPHA),
                suggestionDivider = withAlpha(ink, DIVIDER_ALPHA),
                suggestionPressed = withAlpha(veil, STRIP_PRESSED_VEIL),
                stripIcon = withAlpha(ink, ICON_ALPHA),
                voiceActive = accent,
                voiceActiveIcon = onAccent,
                voiceHalo = withAlpha(accent, HALO_ALPHA),
                voiceStatus = withAlpha(ink, HINT_ALPHA),
            )
        }

        /**
         * The strongest white veil for letter keys that keeps white labels readable: bright
         * backgrounds (green, light gradients) get fainter keys.
         */
        private fun darkKeyVeil(
            @ColorInt top: Int,
            @ColorInt bottom: Int,
        ): Float {
            val lighter =
                if (ColorUtils.calculateLuminance(top) >
                    ColorUtils.calculateLuminance(bottom)
                ) {
                    top
                } else {
                    bottom
                }
            return KEY_VEILS.firstOrNull { alpha ->
                val key = ColorUtils.compositeColors(withAlpha(Color.WHITE, alpha), lighter)
                ColorUtils.calculateContrast(Color.WHITE, key) >= MIN_LABEL_CONTRAST
            } ?: 0f
        }

        /** White or near-black, whichever reads better on [background]. */
        private fun contentColorFor(
            @ColorInt background: Int,
        ): Int = if (ColorUtils.calculateLuminance(background) > LIGHT_LUMINANCE) INK_DARK else Color.WHITE

        private fun withAlpha(
            @ColorInt color: Int,
            alpha: Float,
        ): Int = ColorUtils.setAlphaComponent(color, (alpha * MAX_ALPHA).toInt())

        private const val INK_DARK = 0xFF1F1F1F.toInt()
        private const val MAX_ALPHA = 255
        private const val HALF = 0.5f
        private const val LIGHT_LUMINANCE = 0.45
        private const val KEY_VEIL_DARK = 0.22f
        private val KEY_VEILS = listOf(KEY_VEIL_DARK, 0.18f, 0.14f, 0.1f, 0.06f)
        private const val MIN_LABEL_CONTRAST = 3.0
        private const val PRESSED_STEP = 0.14f
        private const val FUNCTION_VEIL_DARK = 0.1f
        private const val KEY_LIGHT = 0.9f
        private const val LIGHT_PRESSED = 0.12f
        private const val FUNCTION_VEIL_LIGHT = 0.08f
        private const val FUNCTION_PRESSED_LIGHT = 0.16f
        private const val ACTION_PRESSED_SHADE = 0.2f
        private const val PREVIEW_VEIL = 0.32f
        private const val TRAIL_LIGHTEN = 0.35f
        private const val HINT_ALPHA = 0.75f
        private const val SUGGESTION_ALPHA = 0.9f
        private const val DIVIDER_ALPHA = 0.2f
        private const val STRIP_PRESSED_VEIL = 0.12f
        private const val ICON_ALPHA = 0.8f
        private const val HALO_ALPHA = 0.33f
    }
}
