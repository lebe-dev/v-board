package ru.tinyops.android.vboard.ui

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.core.content.edit
import ru.tinyops.android.vboard.R

/** Groups of the theme picker, in the order they are shown. */
enum class ThemeGroup(
    @param:StringRes val title: Int,
) {
    DEFAULT(R.string.theme_group_default),
    COLORS(R.string.theme_group_colors),
    DARK_GRADIENT(R.string.theme_group_dark_gradient),
}

/** A keyboard theme: [palette] resolves its colors, possibly depending on the system dark mode. */
class ThemeSpec(
    val id: String,
    val group: ThemeGroup,
    @param:StringRes val name: Int? = null,
    private val resolve: (Context) -> KeyboardPalette,
) {
    fun palette(context: Context): KeyboardPalette = resolve(context)
}

/** The built-in themes and the selected one stored in the preferences. */
object KeyboardThemes {
    const val SYSTEM = "system"
    const val DYNAMIC = "dynamic"
    const val LIGHT = "light"
    const val DARK = "dark"

    private const val PREFS = "keyboard"
    private const val PREF_THEME = "theme"

    val all: List<ThemeSpec> =
        listOf(
            ThemeSpec(
                DYNAMIC,
                ThemeGroup.DEFAULT,
                R.string.theme_dynamic,
            ) { KeyboardPalette.dynamic(it, it.isNight()) },
            ThemeSpec(SYSTEM, ThemeGroup.DEFAULT, R.string.theme_system) { KeyboardPalette.fromResources(it) },
            ThemeSpec(LIGHT, ThemeGroup.DEFAULT, R.string.theme_light) { KeyboardPalette.fromResources(it, false) },
            ThemeSpec(DARK, ThemeGroup.DEFAULT, R.string.theme_dark) { KeyboardPalette.fromResources(it, true) },
            flat("color_light_grey", 0xFFECEFF1, 0xFF4DB6AC, dark = false),
            flat("color_blue_grey", 0xFF263238, 0xFF80CBC4, dark = true),
            flat("color_white", 0xFFF8F9FA, 0xFF1A73E8, dark = false),
            flat("color_dark_grey", 0xFF35393E, 0xFF669DF6, dark = true),
            flat("color_red", 0xFFC62828, 0xFFF44336, dark = true),
            flat("color_green", 0xFF43A047, 0xFF2E7D32, dark = true),
            flat("color_teal", 0xFF00695C, 0xFF4DB6AC, dark = true),
            flat("color_blue", 0xFF1565C0, 0xFF2196F3, dark = true),
            flat("color_cyan", 0xFF80DEEA, 0xFF00ACC1, dark = false),
            gradient("gradient_purple", 0xFF1A1272, 0xFFA9529F, 0xFFBA68C8),
            gradient("gradient_charcoal", 0xFF6B6E72, 0xFF0A0A0A, 0xFF8AB4F8),
            gradient("gradient_brown", 0xFF604A44, 0xFF452A26, 0xFF9E7C72),
            gradient("gradient_slate", 0xFF57656D, 0xFF2A363C, 0xFF78909C),
            gradient("gradient_teal", 0xFF00806E, 0xFF005A52, 0xFF4DB6AC),
            gradient("gradient_blue", 0xFF1976E2, 0xFF0D4EAA, 0xFF2196F3),
            gradient("gradient_navy", 0xFF33628F, 0xFF0F1B2B, 0xFF0288D1),
            gradient("gradient_graphite", 0xFF5D6064, 0xFF33373B, 0xFF8AB4F8),
            gradient("gradient_mocha", 0xFF625853, 0xFF1E1414, 0xFFA1887F),
        )

    private val byId = all.associateBy { it.id }

    fun find(id: String?): ThemeSpec = byId[id] ?: byId.getValue(SYSTEM)

    fun selected(context: Context): ThemeSpec = find(prefs(context).getString(PREF_THEME, null))

    fun select(
        context: Context,
        id: String,
    ) {
        prefs(context).edit { putString(PREF_THEME, find(id).id) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Context.isNight(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun flat(
        id: String,
        color: Long,
        accent: Long,
        dark: Boolean,
    ): ThemeSpec {
        val palette = KeyboardPalette.tinted(color.toInt(), color.toInt(), accent.toInt(), dark)
        return ThemeSpec(id, ThemeGroup.COLORS) { palette }
    }

    private fun gradient(
        id: String,
        top: Long,
        bottom: Long,
        accent: Long,
    ): ThemeSpec {
        val palette = KeyboardPalette.tinted(top.toInt(), bottom.toInt(), accent.toInt(), dark = true)
        return ThemeSpec(id, ThemeGroup.DARK_GRADIENT) { palette }
    }
}
