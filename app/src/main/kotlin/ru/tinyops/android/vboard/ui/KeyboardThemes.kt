package ru.tinyops.android.vboard.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.annotation.StringRes
import androidx.core.content.edit
import ru.tinyops.android.vboard.R

/** Groups of the theme picker, in the order they are shown. */
enum class ThemeGroup(
    @param:StringRes val title: Int,
) {
    DEFAULT(R.string.theme_group_default),
    COLORS(R.string.theme_group_colors),
}

/** A keyboard theme: [palette] resolves its colors, possibly depending on the system dark mode. */
class ThemeSpec(
    val id: String,
    val group: ThemeGroup,
    @param:StringRes val name: Int,
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
    const val STRIPE = "stripe"
    const val CORAL = "coral"
    const val PLUM = "plum"
    const val PISTACHIO = "pistachio"
    const val MINT = "mint"
    const val BLUEBERRY = "blueberry"
    const val BROWNIE = "brownie"
    const val DIETER = "dieter"

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
            ThemeSpec(STRIPE, ThemeGroup.COLORS, R.string.theme_stripe) { stripePalette },
            ThemeSpec(CORAL, ThemeGroup.COLORS, R.string.theme_coral) { coralPalette },
            ThemeSpec(PLUM, ThemeGroup.COLORS, R.string.theme_plum) { plumPalette },
            ThemeSpec(PISTACHIO, ThemeGroup.COLORS, R.string.theme_pistachio) { pistachioPalette },
            ThemeSpec(MINT, ThemeGroup.COLORS, R.string.theme_mint) { mintPalette },
            ThemeSpec(BLUEBERRY, ThemeGroup.COLORS, R.string.theme_blueberry) { blueberryPalette },
            ThemeSpec(BROWNIE, ThemeGroup.COLORS, R.string.theme_brownie) { browniePalette },
            ThemeSpec(DIETER, ThemeGroup.COLORS, R.string.theme_dieter) { dieterPalette },
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

    private val stripePalette =
        KeyboardPalette.of(
            background = 0xFF111214.toInt(),
            key = 0xFF24262C.toInt(),
            functionKey = 0xFF353843.toInt(),
            actionKey = 0xFF3B63E0.toInt(),
            actionIcon = Color.WHITE,
            label = 0xFFF2F3F5.toInt(),
            hint = 0xFFF0A040.toInt(),
            trail = 0xFFE8785A.toInt(),
            divider = 0xFF8E2E3A.toInt(),
            stripIcon = 0xFFA9ADB6.toInt(),
        )

    private val coralPalette =
        KeyboardPalette.of(
            background = 0xFFFFD9CC.toInt(),
            key = 0xFFFFF5F1.toInt(),
            functionKey = 0xFFF4AE97.toInt(),
            actionKey = 0xFF1F2C6E.toInt(),
            actionIcon = Color.WHITE,
            label = 0xFF2B1612.toInt(),
            hint = 0xFF8A4A3A.toInt(),
            trail = 0xFF1F2C6E.toInt(),
            shadow = 0xFF7A2C16.toInt(),
        )

    private val plumPalette =
        KeyboardPalette.of(
            background = 0xFF241530.toInt(),
            key = 0xFF3A2550.toInt(),
            functionKey = 0xFF533673.toInt(),
            actionKey = 0xFFFFB547.toInt(),
            actionIcon = 0xFF241530.toInt(),
            label = 0xFFF7EEFF.toInt(),
            hint = 0xFFC9B3E6.toInt(),
            trail = 0xFFFFB547.toInt(),
            shadow = 0xFF0A0312.toInt(),
        )

    private val pistachioPalette =
        KeyboardPalette.of(
            background = 0xFFD7E4C0.toInt(),
            key = 0xFFF6F9EE.toInt(),
            functionKey = 0xFFB3C98E.toInt(),
            actionKey = 0xFF2E4A12.toInt(),
            actionIcon = 0xFFF6F9EE.toInt(),
            label = 0xFF1C2810.toInt(),
            hint = 0xFF56683F.toInt(),
            trail = 0xFFE0532B.toInt(),
            shadow = 0xFF283C0F.toInt(),
        )

    private val mintPalette =
        KeyboardPalette.of(
            background = 0xFFCFEDE3.toInt(),
            key = 0xFFF3FBF8.toInt(),
            functionKey = 0xFFA3D9C6.toInt(),
            actionKey = 0xFF0F5C4A.toInt(),
            actionIcon = Color.WHITE,
            label = 0xFF10241E.toInt(),
            hint = 0xFF3F6B5E.toInt(),
            trail = 0xFFD9486B.toInt(),
            shadow = 0xFF0E4A3A.toInt(),
        )

    private val blueberryPalette =
        KeyboardPalette.of(
            background = 0xFF141A33.toInt(),
            key = 0xFF232C52.toInt(),
            functionKey = 0xFF34407A.toInt(),
            actionKey = 0xFF8FA8FF.toInt(),
            actionIcon = 0xFF141A33.toInt(),
            label = 0xFFEEF1FF.toInt(),
            hint = 0xFFA9B4E8.toInt(),
            trail = 0xFFC58BFF.toInt(),
            shadow = 0xFF05081A.toInt(),
        )

    private val browniePalette =
        KeyboardPalette.of(
            background = 0xFF231915.toInt(),
            key = 0xFF3A2A23.toInt(),
            functionKey = 0xFF523B30.toInt(),
            actionKey = 0xFFD9A066.toInt(),
            actionIcon = 0xFF231915.toInt(),
            label = 0xFFF6ECE4.toInt(),
            hint = 0xFFC9AE9A.toInt(),
            trail = 0xFFD9A066.toInt(),
            shadow = 0xFF0D0705.toInt(),
        )

    /** Warm greys of fifties and sixties household appliances with a single orange control. */
    private val dieterPalette =
        KeyboardPalette.of(
            background = 0xFFE4E2DD.toInt(),
            key = 0xFFF5F4F0.toInt(),
            functionKey = 0xFFC9C6BF.toInt(),
            actionKey = 0xFFE8691E.toInt(),
            actionIcon = 0xFF1D1C1A.toInt(),
            label = 0xFF1D1C1A.toInt(),
            hint = 0xFF66635E.toInt(),
            trail = 0xFFE8691E.toInt(),
            divider = 0xFFE8691E.toInt(),
            shadow = 0xFF3E3D3B.toInt(),
        )
}
