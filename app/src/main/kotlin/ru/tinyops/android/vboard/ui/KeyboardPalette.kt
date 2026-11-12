package ru.tinyops.android.vboard.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import ru.tinyops.android.vboard.R

/** Every color the keyboard draws with. Backgrounds, keys and previews are opaque. */
data class KeyboardPalette(
    @param:ColorInt val background: Int,
    @param:ColorInt val key: Int,
    @param:ColorInt val keyPressed: Int,
    @param:ColorInt val keyShadow: Int,
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
    @param:ColorInt val keyIcon: Int = label,
) {
    companion object {
        /** The built-in light or dark colors from `res/values(-night)/colors.xml`. */
        fun fromResources(context: Context): KeyboardPalette {
            fun color(id: Int) = context.getColor(id)
            return KeyboardPalette(
                background = color(R.color.kb_background),
                key = color(R.color.kb_key),
                keyPressed = color(R.color.kb_key_pressed),
                keyShadow = color(R.color.kb_key_shadow),
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
                    background = color(android.R.color.system_neutral1_900),
                    key = color(android.R.color.system_neutral2_700),
                    keyPressed = color(android.R.color.system_neutral2_600),
                    keyShadow = withAlpha(Color.BLACK, SHADOW_DARK),
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
                    background = color(android.R.color.system_neutral2_50),
                    key = color(android.R.color.system_neutral1_10),
                    keyPressed = color(android.R.color.system_neutral2_100),
                    keyShadow = withAlpha(Color.BLACK, SHADOW_LIGHT),
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
         * A palette from a few base colors; pressed states, previews and the strip are derived.
         * [shadow] is the opaque tint of the key shadow, applied with a dark- or light-theme alpha.
         */
        @Suppress("LongParameterList")
        fun of(
            @ColorInt background: Int,
            @ColorInt key: Int,
            @ColorInt functionKey: Int,
            @ColorInt actionKey: Int,
            @ColorInt actionIcon: Int,
            @ColorInt label: Int,
            @ColorInt hint: Int,
            @ColorInt trail: Int,
            @ColorInt divider: Int = functionKey,
            @ColorInt stripIcon: Int = hint,
            @ColorInt shadow: Int = Color.BLACK,
        ): KeyboardPalette {
            val dark = ColorUtils.calculateLuminance(background) < DARK_LUMINANCE
            return KeyboardPalette(
                background = background,
                key = key,
                keyPressed = ColorUtils.blendARGB(key, label, PRESSED_BLEND),
                keyShadow = withAlpha(shadow, if (dark) SHADOW_DARK else SHADOW_LIGHT),
                functionKey = functionKey,
                functionKeyPressed = ColorUtils.blendARGB(functionKey, label, PRESSED_BLEND),
                actionKey = actionKey,
                actionKeyPressed = ColorUtils.blendARGB(actionKey, actionIcon, PRESSED_BLEND),
                actionIcon = actionIcon,
                label = label,
                hint = hint,
                preview = if (dark) ColorUtils.blendARGB(key, label, PREVIEW_BLEND) else key,
                popupSelected = actionKey,
                popupSelectedLabel = actionIcon,
                trail = trail,
                suggestion = label,
                suggestionDivider = divider,
                suggestionPressed = ColorUtils.blendARGB(background, label, STRIP_PRESSED_BLEND),
                stripIcon = stripIcon,
                voiceActive = actionKey,
                voiceActiveIcon = actionIcon,
                voiceHalo = withAlpha(actionKey, HALO_ALPHA),
                voiceStatus = hint,
            )
        }

        private fun withAlpha(
            @ColorInt color: Int,
            alpha: Float,
        ): Int = ColorUtils.setAlphaComponent(color, (alpha * MAX_ALPHA).toInt())

        private const val MAX_ALPHA = 255
        private const val DARK_LUMINANCE = 0.2
        private const val PRESSED_BLEND = 0.16f
        private const val PREVIEW_BLEND = 0.08f
        private const val STRIP_PRESSED_BLEND = 0.1f
        private const val SHADOW_LIGHT = 0.18f
        private const val SHADOW_DARK = 0.5f
        private const val HALO_ALPHA = 0.33f
    }
}
