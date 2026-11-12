package ru.tinyops.android.vboard.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.tinyops.android.vboard.R

internal object SettingsSpacing {
    /** Side margin of the page content (M3 compact window). */
    val Screen = 16.dp

    /** Padding inside a tile, the same as the one of a list item. */
    val Row = 16.dp

    /** Space between the controls of a form. */
    val Gap = 12.dp
    val Small = 8.dp

    /** Space between the tiles of one group. */
    val Tile = 2.dp
}

/**
 * The shapes of a segmented group, as in the M3 Expressive lists and the Android 16 settings: the
 * outer corners of the group are large, the corners between its tiles are small.
 */
private object TileShapes {
    private val outer = 20.dp
    private val inner = 4.dp

    fun of(
        index: Int,
        count: Int,
    ): Shape =
        when {
            count == 1 -> RoundedCornerShape(outer)
            index == 0 -> RoundedCornerShape(outer, outer, inner, inner)
            index == count - 1 -> RoundedCornerShape(inner, inner, outer, outer)
            else -> RoundedCornerShape(inner)
        }
}

/** Collects the tiles of a [SettingsSection]. */
internal class SettingsSectionScope {
    val tiles = mutableListOf<@Composable () -> Unit>()

    fun item(content: @Composable () -> Unit) {
        tiles += content
    }
}

/**
 * A titled group of settings: every [SettingsSectionScope.item] is a separate tile, and the tiles
 * of a group stick together. Hidden rows are simply not added, so the corners stay right.
 */
@Composable
internal fun SettingsSection(
    title: String?,
    modifier: Modifier = Modifier,
    content: SettingsSectionScope.() -> Unit,
) {
    val tiles = SettingsSectionScope().apply(content).tiles
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SettingsSpacing.Tile)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .padding(start = SettingsSpacing.Row, end = SettingsSpacing.Row, bottom = SettingsSpacing.Small)
                        .semantics { heading() },
            )
        }
        tiles.forEachIndexed { index, tile ->
            Surface(
                shape = TileShapes.of(index, tiles.size),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) { tile() }
        }
    }
}

/** A tile holding arbitrary content (a form, a note) with the padding of a list item. */
@Composable
internal fun SettingsTileContent(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(SettingsSpacing.Row),
        verticalArrangement = Arrangement.spacedBy(SettingsSpacing.Gap),
        content = content,
    )
}

/** The controls of a dialog, spaced like those of a form. */
@Composable
internal fun SettingsDialogContent(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SettingsSpacing.Gap),
        content = content,
    )
}

@Composable
internal fun rowColors(
    enabled: Boolean,
    dimTitle: Boolean = false,
) = when {
    enabled && dimTitle ->
        ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DIMMED_ALPHA),
        )
    enabled -> ListItemDefaults.colors(containerColor = Color.Transparent)
    else -> {
        val disabled = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
        ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = disabled,
            supportingColor = disabled,
            leadingIconColor = disabled,
            trailingIconColor = disabled,
        )
    }
}

@Composable
private fun RowIcon(
    @DrawableRes icon: Int?,
    prominent: Boolean = false,
) {
    if (icon == null) return
    if (!prominent) {
        Icon(painterResource(icon), contentDescription = null)
        return
    }
    val themed = LocalBadgeColors.current
    val container = themed?.container ?: MaterialTheme.colorScheme.primaryContainer
    val content = themed?.content ?: MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        modifier = Modifier.size(BADGE_SIZE).background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content)
    }
}

/** The base of every settings row: an M3 list item on a tile. */
@Composable
private fun SettingsRow(
    title: String,
    modifier: Modifier,
    supporting: (@Composable () -> Unit)?,
    @DrawableRes icon: Int?,
    colors: ListItemColors,
    prominent: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        modifier = modifier,
        supportingContent = supporting,
        leadingContent = icon?.let { { RowIcon(it, prominent) } },
        trailingContent = trailing,
        colors = colors,
    )
}

/** A setting turned on and off by a switch; the whole row is the touch target. */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    prominent: Boolean = false,
) {
    SettingsRow(
        title = title,
        modifier = Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        supporting = summary?.let { { Text(it) } },
        icon = icon,
        colors = rowColors(enabled),
        prominent = prominent,
    ) { CheckedSwitch(checked, enabled) }
}

/** A switch row with arbitrary content under the title, such as a few sample pictures. */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: @Composable () -> Unit,
    enabled: Boolean = true,
    dimWhenOff: Boolean = false,
) {
    SettingsRow(
        title = title,
        modifier = Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        supporting = supporting,
        icon = null,
        colors = rowColors(enabled, dimTitle = dimWhenOff && !checked),
    ) { CheckedSwitch(checked, enabled) }
}

/**
 * The switch at the top of a page that turns its whole feature on or off, as in the system
 * settings: a large tile that takes the primary color while the feature is on.
 */
@Composable
internal fun SettingsMainSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val container = if (checked) colors.primaryContainer else colors.surfaceContainerHighest
    val content = if (checked) colors.onPrimaryContainer else colors.onSurface
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Surface(shape = RoundedCornerShape(MAIN_SWITCH_CORNER), color = container, contentColor = content) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                    .padding(horizontal = MAIN_SWITCH_PADDING, vertical = SettingsSpacing.Row),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = content.copy(alpha = alpha))
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = content.copy(alpha = alpha * SUMMARY_ALPHA),
                    )
                }
            }
            Spacer(Modifier.width(SettingsSpacing.Row))
            CheckedSwitch(checked, enabled)
        }
    }
}

@Composable
private fun CheckedSwitch(
    checked: Boolean,
    enabled: Boolean,
) {
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled,
        thumbContent = {
            if (checked) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            }
        },
    )
}

/**
 * A row that opens a page, a dialog or, with [external], a link outside the app. A [prominent] row
 * puts its icon on a round badge and ends with a chevron.
 */
@Composable
internal fun SettingsLinkRow(
    title: String,
    onClick: () -> Unit,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    external: Boolean = false,
    prominent: Boolean = false,
) {
    SettingsRow(
        title = title,
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        supporting = summary?.let { { Text(it) } },
        icon = icon,
        colors = rowColors(enabled),
        prominent = prominent,
        trailing =
            when {
                external -> ({ RowIcon(R.drawable.ic_open_in_new) })
                prominent -> ({ RowIcon(R.drawable.ic_chevron_right) })
                else -> null
            },
    )
}

/** One option of a choice shown directly on a page; the whole row is the touch target. */
@Composable
internal fun SettingsRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        modifier = Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        supporting = summary?.let { { Text(it) } },
        icon = null,
        colors = rowColors(enabled),
        trailing = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
    )
}

/** A labeled option of a [SettingsChoiceRow]; [description] is shown under the label in the dialog. */
internal data class Choice<T>(
    val value: T,
    val label: String,
    val description: String? = null,
)

/**
 * A single choice setting: the row shows the current option, a tap opens a dialog with all of them.
 * Picking an option applies it at once and closes the dialog, as in the system settings.
 */
@Composable
internal fun <T> SettingsChoiceRow(
    title: String,
    selected: T,
    choices: List<Choice<T>>,
    onSelected: (T) -> Unit,
    @DrawableRes icon: Int? = null,
    description: String? = null,
    prominent: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val current = choices.firstOrNull { it.value == selected } ?: choices.first()
    SettingsLinkRow(
        title = title,
        summary = current.label,
        icon = icon,
        prominent = prominent,
        onClick = { open = true },
    )
    if (open) {
        ChoiceDialog(title, description, choices, current, onDismiss = { open = false }) {
            open = false
            onSelected(it)
        }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    description: String?,
    choices: List<Choice<T>>,
    current: Choice<T>,
    onDismiss: () -> Unit,
    onSelected: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (description != null) {
                    Text(description, modifier = Modifier.padding(bottom = SettingsSpacing.Gap))
                }
                Column(Modifier.selectableGroup()) {
                    for (choice in choices) {
                        ChoiceOption(choice, selected = choice == current) { onSelected(choice.value) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun <T> ChoiceOption(
    choice: Choice<T>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MIN_TOUCH)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(vertical = SettingsSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(SettingsSpacing.Row))
        Column {
            Text(choice.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (choice.description != null) {
                Text(
                    choice.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** How a [SettingsStatusRow] colors its icon. */
internal enum class StatusTone {
    NEUTRAL,
    SUCCESS,
    PROGRESS,
    ERROR,
}

@Composable
private fun StatusIcon(
    tone: StatusTone,
    @DrawableRes icon: Int?,
) {
    when (tone) {
        StatusTone.PROGRESS ->
            CircularProgressIndicator(
                modifier = Modifier.size(STATUS_PROGRESS),
                strokeWidth = 2.5.dp,
            )
        else -> if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = tone.color())
    }
}

@Composable
private fun StatusTone.color(): Color =
    when (this) {
        StatusTone.SUCCESS -> MaterialTheme.colorScheme.primary
        StatusTone.ERROR -> MaterialTheme.colorScheme.error
        else -> LocalContentColor.current
    }

/** A read-only state: what is ready, what is in progress, what is wrong. */
@Composable
internal fun SettingsStatusRow(
    title: String,
    tone: StatusTone,
    @DrawableRes icon: Int? = null,
    summary: String? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = { StatusIcon(tone, icon) },
        colors =
            ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = if (tone == StatusTone.ERROR) MaterialTheme.colorScheme.error else Color.Unspecified,
            ),
    )
}

/** An explanation on its own tile, with an optional icon in the column of the row icons. */
@Composable
internal fun SettingsNote(
    text: String,
    @DrawableRes icon: Int? = null,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(SettingsSpacing.Row),
        verticalAlignment = Alignment.Top,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = color)
            Spacer(Modifier.width(SettingsSpacing.Row))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

private val MIN_TOUCH = 48.dp
private val BADGE_SIZE = 44.dp
private val MAIN_SWITCH_CORNER = 28.dp
private val MAIN_SWITCH_PADDING = 20.dp
private const val SUMMARY_ALPHA = 0.8f
private val STATUS_PROGRESS = 22.dp

/** The M3 opacity of disabled content. */
internal const val DISABLED_ALPHA = 0.38f
private const val DIMMED_ALPHA = 0.7f
