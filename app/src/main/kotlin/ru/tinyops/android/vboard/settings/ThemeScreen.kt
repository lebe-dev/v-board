package ru.tinyops.android.vboard.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.ui.KeyboardPalette
import ru.tinyops.android.vboard.ui.KeyboardThemes
import ru.tinyops.android.vboard.ui.ThemeChoice
import ru.tinyops.android.vboard.ui.ThemeGroup
import ru.tinyops.android.vboard.ui.ThemeMode
import ru.tinyops.android.vboard.ui.ThemeSpec

/** The theme picker: the light or dark mode, then groups of tiles, each a miniature of the keyboard colors. */
@Composable
internal fun ThemeScreen(
    choice: MutableState<ThemeChoice>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val selected = choice.value
    val onSelect = { id: String ->
        KeyboardThemes.select(context, id)
        choice.value = KeyboardThemes.selected(context)
    }
    val onSelectMode = { mode: ThemeMode ->
        KeyboardThemes.selectMode(context, mode)
        choice.value = KeyboardThemes.selected(context)
    }
    SettingsPage(title = stringResource(R.string.theme_title), onBack = onBack) {
        item(key = "mode") { ModeChoice(selected.mode, onSelectMode) }
        item(key = "icons") {
            val night = selected.night(context)
            SettingsSection(null) {
                item {
                    SettingsSwitchRow(
                        title = stringResource(if (night) R.string.theme_icons_white else R.string.theme_icons_dark),
                        supporting = { Text(stringResource(R.string.theme_icons_summary)) },
                        dimWhenOff = true,
                        checked = if (night) selected.whiteIcons else selected.darkIcons,
                        onCheckedChange = {
                            KeyboardThemes.setContrastIcons(context, night, it)
                            choice.value = KeyboardThemes.selected(context)
                        },
                    )
                }
            }
        }
        val recent = KeyboardThemes.recent(context)
        if (recent.size > 1) {
            item(key = "recent") {
                ThemeGroupSection("recent", stringResource(R.string.theme_group_recent), recent, selected, onSelect)
            }
        }
        for (group in ThemeGroup.entries) {
            item(key = group.name) {
                val themes = KeyboardThemes.all.filter { it.group == group }
                ThemeGroupSection(group.name, stringResource(group.title), themes, selected, onSelect)
            }
        }
    }
}

@Composable
private fun ModeChoice(
    selected: ThemeMode,
    onSelected: (ThemeMode) -> Unit,
) {
    val modes = ThemeMode.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelected(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                label = { Text(stringResource(mode.title), maxLines = 1) },
            )
        }
    }
}

@Composable
private fun ThemeGroupSection(
    key: String,
    title: String,
    themes: List<ThemeSpec>,
    selected: ThemeChoice,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember(key) { mutableStateOf(!KeyboardThemes.isCollapsed(context, key)) }
    Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.Gap)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = SettingsSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FilledTonalIconButton(
                onClick = {
                    expanded = !expanded
                    KeyboardThemes.setCollapsed(context, key, !expanded)
                },
            ) {
                Icon(
                    painterResource(R.drawable.ic_expand),
                    stringResource(if (expanded) R.string.theme_collapse else R.string.theme_expand),
                    modifier = Modifier.rotate(if (expanded) 0f else COLLAPSED_ROTATION),
                )
            }
        }
        if (expanded) {
            for (chunk in themes.chunked(COLUMNS)) {
                Row(horizontalArrangement = Arrangement.spacedBy(SettingsSpacing.Gap)) {
                    chunk.forEach { theme ->
                        ThemeTile(
                            theme = theme,
                            mode = selected.mode,
                            description = stringResource(theme.name),
                            selected = theme.id == selected.spec.id,
                            onClick = { onSelect(theme.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(COLUMNS - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ThemeTile(
    theme: ThemeSpec,
    mode: ThemeMode,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uiMode = LocalConfiguration.current.uiMode
    val palettes =
        remember(theme.id, mode, uiMode) {
            when (mode) {
                ThemeMode.SYSTEM -> theme.palette(context, false) to theme.palette(context, true)
                ThemeMode.LIGHT -> theme.palette(context, false).let { it to it }
                ThemeMode.DARK -> theme.palette(context, true).let { it to it }
            }
        }
    val selectedText = stringResource(R.string.theme_selected)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val border =
            if (selected) {
                Modifier.border(SELECTED_BORDER, MaterialTheme.colorScheme.onSurface, TileShape)
            } else {
                Modifier.border(TILE_BORDER, MaterialTheme.colorScheme.surfaceContainerHighest, TileShape)
            }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(TILE_ASPECT)
                    .clip(TileShape)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                    .semantics { contentDescription = if (selected) "$description, $selectedText" else description }
                    .then(border),
        ) {
            TileContent(palettes.first)
            // Following the system, a tile is half light, half dark.
            if (palettes.second !== palettes.first) {
                Box(Modifier.fillMaxSize().clip(RightHalf)) { TileContent(palettes.second) }
            }
            if (selected) SelectedMark()
        }
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = SettingsSpacing.Small / 2),
        )
    }
}

/** The tile background with a space bar and the action key. */
@Composable
private fun BoxScope.TileContent(palette: KeyboardPalette) {
    // White keys vanish on a light background: show the darker function keys there.
    val light = ColorUtils.calculateLuminance(palette.background) > LIGHT_LUMINANCE
    val bar = if (light) palette.functionKey else palette.key
    Box(
        Modifier
            .matchParentSize()
            .background(Color(palette.background)),
    )
    Row(
        modifier =
            Modifier
                .align(Alignment.BottomEnd)
                .fillMaxWidth()
                .fillMaxHeight(KEYS_HEIGHT)
                .padding(end = 10.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            Modifier
                .fillMaxWidth(SPACE_WIDTH)
                .height(7.dp)
                .background(Color(bar), RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .width(14.dp)
                .height(9.dp)
                .background(Color(palette.actionKey), CircleShape),
        )
    }
}

@Composable
private fun BoxScope.SelectedMark() {
    Box(
        modifier =
            Modifier
                .align(Alignment.Center)
                .size(32.dp)
                .background(Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = Color.Black,
            modifier = Modifier.size(22.dp),
        )
    }
}

private val TileShape = RoundedCornerShape(16.dp)
private val TILE_BORDER = 4.dp
private val SELECTED_BORDER = 3.dp
private val RightHalf = GenericShape { size, _ -> addRect(Rect(size.width / 2f, 0f, size.width, size.height)) }

private const val COLUMNS = 3
private const val TILE_ASPECT = 1.4f
private const val KEYS_HEIGHT = 0.5f
private const val SPACE_WIDTH = 0.45f
private const val COLLAPSED_ROTATION = 180f
private const val LIGHT_LUMINANCE = 0.5
