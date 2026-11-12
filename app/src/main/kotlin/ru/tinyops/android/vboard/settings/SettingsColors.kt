package ru.tinyops.android.vboard.settings

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import ru.tinyops.android.vboard.ui.ThemeGroup
import ru.tinyops.android.vboard.ui.ThemeMode
import ru.tinyops.android.vboard.ui.ThemeSpec

/**
 * Whether the theme mode forces the settings into the dark ([true]) or light ([false]) mode;
 * null leaves the choice to the system.
 */
internal fun settingsMode(mode: ThemeMode): Boolean? =
    when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> null
    }

/** The colors of a round icon badge: the one place where the theme accent shows. */
internal data class BadgeColors(
    val container: Color,
    val content: Color,
)

/**
 * The badge colors of a keyboard theme from its light or [dark] variant, or null for the default
 * themes, which keep the system colors.
 */
internal fun badgeColors(
    theme: ThemeSpec,
    context: Context,
    dark: Boolean,
): BadgeColors? {
    if (theme.group == ThemeGroup.DEFAULT) return null
    val palette = theme.palette(context, dark)
    return BadgeColors(Color(palette.actionKey).copy(alpha = 1f), Color(palette.actionIcon).copy(alpha = 1f))
}

/** The badge colors in effect; null means the primary container of the system scheme. */
internal val LocalBadgeColors = staticCompositionLocalOf<BadgeColors?> { null }
