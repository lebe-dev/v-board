package ru.tinyops.android.vboard.settings

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import ru.tinyops.android.vboard.ui.KeyboardThemes
import ru.tinyops.android.vboard.ui.ThemeGroup
import ru.tinyops.android.vboard.ui.ThemeSpec

/**
 * Whether the keyboard theme forces the settings into the dark ([true]) or light ([false]) mode;
 * null leaves the choice to the system.
 */
internal fun settingsMode(theme: ThemeSpec): Boolean? =
    when (theme.id) {
        KeyboardThemes.LIGHT -> false
        KeyboardThemes.DARK -> true
        else -> null
    }

/** The colors of a round icon badge: the one place where the theme accent shows. */
internal data class BadgeColors(
    val container: Color,
    val content: Color,
)

/**
 * The badge colors of a keyboard theme built from its own palette, or null for the default
 * themes, which keep the system colors (see [settingsMode]).
 */
internal fun badgeColors(
    theme: ThemeSpec,
    context: Context,
): BadgeColors? {
    if (theme.group == ThemeGroup.DEFAULT) return null
    val palette = theme.palette(context)
    return BadgeColors(Color(palette.actionKey).copy(alpha = 1f), Color(palette.actionIcon).copy(alpha = 1f))
}

/** The badge colors in effect; null means the primary container of the system scheme. */
internal val LocalBadgeColors = staticCompositionLocalOf<BadgeColors?> { null }
