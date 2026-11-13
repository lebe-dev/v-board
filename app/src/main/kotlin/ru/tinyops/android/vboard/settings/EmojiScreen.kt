package ru.tinyops.android.vboard.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.emoji.Emoji
import ru.tinyops.android.vboard.core.emoji.EmojiCategory
import ru.tinyops.android.vboard.core.emoji.EmojiHidden
import ru.tinyops.android.vboard.emoji.EmojiAssets
import ru.tinyops.android.vboard.emoji.EmojiPrefs
import ru.tinyops.android.vboard.emoji.samples
import ru.tinyops.android.vboard.emoji.subcategoryTitle
import ru.tinyops.android.vboard.emoji.title

/** What the emoji picker offers: whole categories can be switched off, single emoji removed on a sub-page. */
@Composable
internal fun EmojiScreen(
    onOpenGroups: () -> Unit,
    onOpenSingle: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { EmojiPrefs(context) }
    val assets = remember { EmojiAssets(context.assets) }
    val catalog = assets.catalog
    var hidden by remember { mutableStateOf(prefs.hidden) }
    var enabled by remember { mutableStateOf(prefs.enabled) }

    fun change(next: EmojiHidden) {
        hidden = next
        prefs.hidden = next
    }
    SettingsPage(title = stringResource(R.string.settings_emoji), onBack = onBack) {
        item {
            SettingsSection(null) {
                item {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_emoji_enabled),
                        summary = stringResource(R.string.settings_emoji_enabled_summary),
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            prefs.enabled = it
                        },
                    )
                }
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_emoji_categories)) {
                for (category in catalog.categories) {
                    item {
                        val shown = hidden.isShown(catalog, category)
                        // The picker keeps at least one category.
                        val switchable = !shown || hidden.categoriesIn(catalog).size > 1
                        SettingsSwitchRow(
                            title = stringResource(category.title),
                            checked = shown,
                            enabled = switchable,
                            onCheckedChange = { change(hidden.toggleCategory(category, catalog)) },
                            supporting = { EmojiSamples(category, assets, dimmed = !switchable || !shown) },
                        )
                    }
                }
            }
        }
        item {
            EmojiLinksSection(
                hiddenEmoji = hidden.emoji.size,
                anyHidden = hidden.any,
                onOpenGroups = onOpenGroups,
                onOpenSingle = onOpenSingle,
                onRestore = { change(EmojiHidden()) },
            )
        }
        item { SettingsSection(null) { item { SettingsNote(stringResource(R.string.settings_emoji_note)) } } }
    }
}

/** The sub-pages of what is hidden and the way back to everything shown. */
@Composable
private fun EmojiLinksSection(
    hiddenEmoji: Int,
    anyHidden: Boolean,
    onOpenGroups: () -> Unit,
    onOpenSingle: () -> Unit,
    onRestore: () -> Unit,
) {
    SettingsSection(null) {
        item {
            SettingsLinkRow(
                title = stringResource(R.string.settings_emoji_groups),
                summary = stringResource(R.string.settings_emoji_groups_summary),
                prominent = true,
                onClick = onOpenGroups,
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.settings_emoji_single),
                summary =
                    if (hiddenEmoji == 0) {
                        stringResource(R.string.settings_emoji_single_none)
                    } else {
                        stringResource(R.string.settings_emoji_single_count, hiddenEmoji)
                    },
                prominent = true,
                onClick = onOpenSingle,
            )
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.settings_emoji_restore),
                enabled = anyHidden,
                onClick = onRestore,
            )
        }
    }
}

/** A few well-known emoji of a category, so its name says what is in it. */
@Composable
private fun EmojiSamples(
    category: EmojiCategory,
    assets: EmojiAssets,
    dimmed: Boolean,
) {
    val samples = remember(category) { category.samples.mapNotNull { assets.catalog.find(it) } }
    EmojiPictures(samples, assets, dimmed)
}

/** A row of small emoji pictures; dimmed while the thing they stand for is switched off. */
@Composable
private fun EmojiPictures(
    emoji: List<Emoji>,
    assets: EmojiAssets,
    dimmed: Boolean,
) {
    Row(
        Modifier.padding(top = SAMPLES_TOP).alpha(if (dimmed) REMOVED_ALPHA else 1f),
        horizontalArrangement = Arrangement.spacedBy(SAMPLES_GAP),
    ) {
        for (sample in emoji) {
            val picture = remember(sample.image) { assets.picture(sample)?.asImageBitmap() }
            if (picture != null) {
                Image(picture, contentDescription = null, modifier = Modifier.size(SAMPLE_SIZE))
            } else {
                Text(sample.text, fontSize = SAMPLE_FALLBACK)
            }
        }
    }
}

/**
 * The subcategories of every category (sports, games...), each switched on or off with a few of
 * its emoji as a hint.
 */
@Composable
internal fun EmojiGroupsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { EmojiPrefs(context) }
    val assets = remember { EmojiAssets(context.assets) }
    val catalog = assets.catalog
    var hidden by remember { mutableStateOf(prefs.hidden) }
    SettingsPage(title = stringResource(R.string.settings_emoji_groups), onBack = onBack) {
        for (category in catalog.categories) {
            val groups = catalog.subcategories(category).filter { it.isNotEmpty() }
            if (groups.isEmpty()) continue
            item {
                SettingsSection(stringResource(category.title)) {
                    for (group in groups) {
                        item {
                            val shown = group !in hidden.subcategories
                            val categoryOn = category !in hidden.categories
                            // The picker keeps at least one group: hiding the last one changes nothing.
                            val enabled = categoryOn && hidden.toggleSubcategory(group, catalog) != hidden
                            SettingsSwitchRow(
                                title =
                                    stringResource(
                                        subcategoryTitle(group) ?: R.string.settings_emoji_group_unknown,
                                    ),
                                checked = shown && categoryOn,
                                enabled = enabled,
                                onCheckedChange = {
                                    hidden = hidden.toggleSubcategory(group, catalog)
                                    prefs.hidden = hidden
                                },
                                supporting = {
                                    val samples = remember(group) { catalog.of(category, group).take(SAMPLES) }
                                    EmojiPictures(samples, assets, dimmed = !enabled || !shown)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Every emoji of the catalog in a grid: a tap removes it from the picker or brings it back. */
@Composable
internal fun EmojiSinglePickScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { EmojiPrefs(context) }
    val assets = remember { EmojiAssets(context.assets) }
    var hidden by remember { mutableStateOf(prefs.hidden) }
    SettingsPage(title = stringResource(R.string.settings_emoji_single), onBack = onBack) {
        item { SettingsSection(null) { item { SettingsNote(stringResource(R.string.settings_emoji_single_hint)) } } }
        for (category in assets.catalog.categories) {
            item {
                Text(
                    stringResource(category.title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = SettingsSpacing.Row),
                )
            }
            val rows = assets.catalog.of(category).chunked(COLUMNS)
            items(rows.size) { index ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    for (emoji in rows[index]) {
                        // A category that is switched off shows its emoji dimmed as well.
                        val removed = emoji.text in hidden.emoji
                        EmojiCell(
                            emoji = emoji,
                            assets = assets,
                            removed = removed,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                hidden = hidden.toggleEmoji(emoji.text)
                                prefs.hidden = hidden
                            },
                        )
                    }
                    repeat(COLUMNS - rows[index].size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun EmojiCell(
    emoji: Emoji,
    assets: EmojiAssets,
    removed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val picture = remember(emoji.image) { assets.picture(emoji)?.asImageBitmap() }
    val label = stringResource(if (removed) R.string.settings_emoji_show else R.string.settings_emoji_hide)
    Box(
        modifier
            .aspectRatio(1f)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics { contentDescription = "$label ${emoji.text}" }
            .padding(CELL_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        val dim = Modifier.alpha(if (removed) REMOVED_ALPHA else 1f)
        if (picture != null) {
            Image(picture, contentDescription = null, modifier = dim)
        } else {
            Text(emoji.text, fontSize = FALLBACK_SIZE, textAlign = TextAlign.Center, modifier = dim)
        }
        if (removed) {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private const val COLUMNS = 7
private const val SAMPLES = 4
private val SAMPLE_SIZE = 28.dp
private val SAMPLE_FALLBACK = 22.sp
private val SAMPLES_TOP = 6.dp
private val SAMPLES_GAP = 8.dp
private const val REMOVED_ALPHA = 0.25f
private val CELL_PADDING = 6.dp
private val FALLBACK_SIZE = 26.sp
