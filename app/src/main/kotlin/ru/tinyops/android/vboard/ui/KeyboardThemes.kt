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

/** Which variant of a theme the keyboard shows. */
enum class ThemeMode(
    val id: String,
    @param:StringRes val title: Int,
) {
    SYSTEM("system", R.string.theme_mode_system),
    LIGHT("light", R.string.theme_mode_light),
    DARK("dark", R.string.theme_mode_dark),
}

/** A keyboard theme with a light and a dark variant. */
class ThemeSpec(
    val id: String,
    val group: ThemeGroup,
    @param:StringRes val name: Int,
    private val resolve: (Context, Boolean) -> KeyboardPalette,
) {
    fun palette(
        context: Context,
        night: Boolean,
    ): KeyboardPalette = resolve(context, night)
}

/** Which of the pill-shaped keys, "?123" and the action key (the magnifier among them), are square like the rest. */
data class KeyShapes(
    val squareMode: Boolean = false,
    val squareAction: Boolean = false,
)

/**
 * A theme together with the mode that picks its variant. [whiteIcons] and [darkIcons] paint the
 * icons without a color of their own white in the dark variant and black in the light one.
 */
data class ThemeChoice(
    val spec: ThemeSpec,
    val mode: ThemeMode,
    val whiteIcons: Boolean = false,
    val darkIcons: Boolean = false,
    val shapes: KeyShapes = KeyShapes(),
) {
    /** Changes whenever the keyboard has to be rebuilt with other colors. */
    val key: String get() = "${spec.id}:${mode.id}:$whiteIcons:$darkIcons:$shapes"

    fun night(context: Context): Boolean =
        when (mode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> context.isNight()
        }

    fun palette(context: Context): KeyboardPalette {
        val night = night(context)
        val palette = spec.palette(context, night)
        val icon =
            when {
                night && whiteIcons -> Color.WHITE
                !night && darkIcons -> Color.BLACK
                else -> return palette
            }
        return palette.copy(stripIcon = icon, keyIcon = icon)
    }
}

private fun Context.isNight(): Boolean =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/** The built-in themes and the selected one stored in the preferences. */
object KeyboardThemes {
    const val CLASSIC = "classic"
    const val DYNAMIC = "dynamic"
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
    private const val PREF_MODE = "theme_mode"
    private const val PREF_WHITE_ICONS = "icons_white"
    private const val PREF_DARK_ICONS = "icons_dark"
    private const val PREF_SQUARE_MODE = "keys_square_mode"
    private const val PREF_SQUARE_ACTION = "keys_square_action"
    private const val PREF_COLLAPSED = "theme_collapsed"
    private const val PREF_RECENT = "theme_recent"
    private const val RECENT_LIMIT = 3

    /** Stored theme ids that stand for the classic theme in a fixed mode. */
    private val CLASSIC_MODES = ThemeMode.entries.associateBy { it.id }

    val all: List<ThemeSpec> by lazy {
        listOf(
            ThemeSpec(CLASSIC, ThemeGroup.DEFAULT, R.string.theme_classic) { context, night ->
                KeyboardPalette.fromResources(context, night)
            },
            ThemeSpec(DYNAMIC, ThemeGroup.DEFAULT, R.string.theme_dynamic) { context, night ->
                KeyboardPalette.dynamic(context, night)
            },
            fixed(STRIPE, R.string.theme_stripe, stripeLight, stripeDark),
            fixed(CORAL, R.string.theme_coral, coralLight, coralDark),
            fixed(PLUM, R.string.theme_plum, plumLight, plumDark),
            fixed(PISTACHIO, R.string.theme_pistachio, pistachioLight, pistachioDark),
            fixed(MINT, R.string.theme_mint, mintLight, mintDark),
            fixed(BLUEBERRY, R.string.theme_blueberry, blueberryLight, blueberryDark),
            fixed(BROWNIE, R.string.theme_brownie, brownieLight, brownieDark),
            fixed(DIETER, R.string.theme_dieter, dieterLight, dieterDark),
        )
    }

    private val byId by lazy { all.associateBy { it.id } }

    fun find(id: String?): ThemeSpec = byId[id] ?: byId.getValue(CLASSIC)

    fun selected(context: Context): ThemeChoice {
        val prefs = prefs(context)
        val id = prefs.getString(PREF_THEME, null)
        val mode =
            ThemeMode.entries.firstOrNull { it.id == prefs.getString(PREF_MODE, null) }
                ?: CLASSIC_MODES[id]
                ?: ThemeMode.SYSTEM
        return ThemeChoice(
            spec = find(id),
            mode = mode,
            whiteIcons = prefs.getBoolean(PREF_WHITE_ICONS, false),
            darkIcons = prefs.getBoolean(PREF_DARK_ICONS, false),
            shapes =
                KeyShapes(
                    squareMode = prefs.getBoolean(PREF_SQUARE_MODE, false),
                    squareAction = prefs.getBoolean(PREF_SQUARE_ACTION, false),
                ),
        )
    }

    fun select(
        context: Context,
        id: String,
    ) {
        val mode = selected(context).mode
        val theme = find(id).id
        val recent = (listOf(theme) + recent(context).map { it.id }).distinct().take(RECENT_LIMIT)
        prefs(context).edit {
            putString(PREF_THEME, theme)
            putString(PREF_MODE, mode.id)
            putString(PREF_RECENT, recent.joinToString(","))
        }
    }

    /** The themes picked last, the newest first. */
    fun recent(context: Context): List<ThemeSpec> =
        prefs(context)
            .getString(PREF_RECENT, null)
            .orEmpty()
            .split(",")
            .mapNotNull { byId[it] }

    fun selectMode(
        context: Context,
        mode: ThemeMode,
    ) {
        prefs(context).edit { putString(PREF_MODE, mode.id) }
    }

    /** Turns the contrast icons of the dark ([night]) or light variant on or off. */
    fun setContrastIcons(
        context: Context,
        night: Boolean,
        on: Boolean,
    ) {
        prefs(context).edit { putBoolean(if (night) PREF_WHITE_ICONS else PREF_DARK_ICONS, on) }
    }

    /** Whether a section of the theme picker, such as "colors", is folded. */
    fun isCollapsed(
        context: Context,
        section: String,
    ): Boolean = section in prefs(context).getStringSet(PREF_COLLAPSED, emptySet()).orEmpty()

    fun setCollapsed(
        context: Context,
        section: String,
        collapsed: Boolean,
    ) {
        val sections = prefs(context).getStringSet(PREF_COLLAPSED, emptySet()).orEmpty()
        prefs(context).edit { putStringSet(PREF_COLLAPSED, if (collapsed) sections + section else sections - section) }
    }

    fun setSquareModeKey(
        context: Context,
        on: Boolean,
    ) {
        prefs(context).edit { putBoolean(PREF_SQUARE_MODE, on) }
    }

    fun setSquareActionKey(
        context: Context,
        on: Boolean,
    ) {
        prefs(context).edit { putBoolean(PREF_SQUARE_ACTION, on) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun fixed(
        id: String,
        @StringRes name: Int,
        light: KeyboardPalette,
        dark: KeyboardPalette,
    ) = ThemeSpec(id, ThemeGroup.COLORS, name) { _, night -> if (night) dark else light }

    private val stripeLight =
        KeyboardPalette.of(
            background = 0xFFEEF0F4.toInt(),
            key = Color.WHITE,
            functionKey = 0xFFD3D8E2.toInt(),
            actionKey = 0xFF3B63E0.toInt(),
            actionIcon = Color.WHITE,
            label = 0xFF111214.toInt(),
            hint = 0xFFB25E0A.toInt(),
            trail = 0xFFE8785A.toInt(),
            divider = 0xFF8E2E3A.toInt(),
            stripIcon = 0xFF4A4F5C.toInt(),
            shadow = 0xFF111214.toInt(),
        )

    private val stripeDark =
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

    private val coralLight =
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

    private val coralDark =
        KeyboardPalette.of(
            background = 0xFF2A1512.toInt(),
            key = 0xFF42221D.toInt(),
            functionKey = 0xFF5C2F27.toInt(),
            actionKey = 0xFFFF8A6B.toInt(),
            actionIcon = 0xFF2A1512.toInt(),
            label = 0xFFFFEDE7.toInt(),
            hint = 0xFFF0B4A3.toInt(),
            trail = 0xFFFF8A6B.toInt(),
            shadow = 0xFF0C0403.toInt(),
        )

    private val plumLight =
        KeyboardPalette.of(
            background = 0xFFEADFF3.toInt(),
            key = 0xFFFBF7FE.toInt(),
            functionKey = 0xFFCDB6E2.toInt(),
            actionKey = 0xFF5B2E86.toInt(),
            actionIcon = Color.WHITE,
            label = 0xFF241530.toInt(),
            hint = 0xFF6B4F86.toInt(),
            trail = 0xFFE0901C.toInt(),
            shadow = 0xFF3C195A.toInt(),
        )

    private val plumDark =
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

    private val pistachioLight =
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

    private val pistachioDark =
        KeyboardPalette.of(
            background = 0xFF1A2210.toInt(),
            key = 0xFF2A3519.toInt(),
            functionKey = 0xFF3D4C24.toInt(),
            actionKey = 0xFFB5D98A.toInt(),
            actionIcon = 0xFF1A2210.toInt(),
            label = 0xFFEEF5E2.toInt(),
            hint = 0xFFAFC294.toInt(),
            trail = 0xFFFF7A52.toInt(),
            shadow = 0xFF060A02.toInt(),
        )

    private val mintLight =
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

    private val mintDark =
        KeyboardPalette.of(
            background = 0xFF0F2620.toInt(),
            key = 0xFF1A3A31.toInt(),
            functionKey = 0xFF275245.toInt(),
            actionKey = 0xFF6FD6B4.toInt(),
            actionIcon = 0xFF0F2620.toInt(),
            label = 0xFFE6FAF3.toInt(),
            hint = 0xFF9CCFBF.toInt(),
            trail = 0xFFFF7A98.toInt(),
            shadow = 0xFF030C09.toInt(),
        )

    private val blueberryLight =
        KeyboardPalette.of(
            background = 0xFFDDE3FA.toInt(),
            key = 0xFFF7F8FF.toInt(),
            functionKey = 0xFFB9C4F0.toInt(),
            actionKey = 0xFF2F3F9E.toInt(),
            actionIcon = Color.WHITE,
            label = 0xFF141A33.toInt(),
            hint = 0xFF4A5590.toInt(),
            trail = 0xFF8A4FD6.toInt(),
            shadow = 0xFF141E5A.toInt(),
        )

    private val blueberryDark =
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

    private val brownieLight =
        KeyboardPalette.of(
            background = 0xFFEFE4D8.toInt(),
            key = 0xFFFBF6F0.toInt(),
            functionKey = 0xFFD9C4AE.toInt(),
            actionKey = 0xFF6B4226.toInt(),
            actionIcon = 0xFFFBF6F0.toInt(),
            label = 0xFF2A1D16.toInt(),
            hint = 0xFF7A5A44.toInt(),
            trail = 0xFFC07A35.toInt(),
            shadow = 0xFF462814.toInt(),
        )

    private val brownieDark =
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
    private val dieterLight =
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

    private val dieterDark =
        KeyboardPalette.of(
            background = 0xFF2B2A28.toInt(),
            key = 0xFF3D3B38.toInt(),
            functionKey = 0xFF55524D.toInt(),
            actionKey = 0xFFE8691E.toInt(),
            actionIcon = 0xFF1D1C1A.toInt(),
            label = 0xFFF2F0EB.toInt(),
            hint = 0xFFB5B1AA.toInt(),
            trail = 0xFFE8691E.toInt(),
            divider = 0xFFE8691E.toInt(),
        )
}
