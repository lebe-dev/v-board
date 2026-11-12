package ru.tinyops.android.vboard.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.clipboard.ClipboardPrefs
import ru.tinyops.android.vboard.core.voice.ButtonPlacement

/**
 * Whether the keyboard has a clipboard at all, what it shows of it and whether it keeps a history.
 */
@Composable
internal fun ClipboardScreen(onBack: () -> Unit) {
    val prefs = ClipboardPrefs(LocalContext.current)
    var enabled by remember { mutableStateOf(prefs.enabled) }
    SettingsPage(title = stringResource(R.string.settings_clipboard), onBack = onBack) {
        item {
            SettingsSection(null) {
                item {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_clipboard),
                        summary = if (enabled) null else stringResource(R.string.settings_clipboard_off_summary),
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            prefs.enabled = it
                        },
                    )
                }
            }
        }
        if (enabled) {
            item { ClipboardOptions(prefs) }
            item { SettingsSection(null) { item { SettingsNote(stringResource(R.string.settings_clipboard_note)) } } }
        }
    }
}

/** What there is to choose while the clipboard is on. */
@Composable
private fun ClipboardOptions(prefs: ClipboardPrefs) {
    var strip by remember { mutableStateOf(prefs.showOnStrip) }
    var extracted by remember { mutableStateOf(prefs.showExtracted) }
    var history by remember { mutableStateOf(prefs.keepHistory) }
    var placement by remember { mutableStateOf(prefs.iconPlacement) }
    var color by remember { mutableStateOf(prefs.iconColor) }
    SettingsSection(null) {
        item {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_clipboard_strip),
                checked = strip,
                onCheckedChange = {
                    strip = it
                    prefs.showOnStrip = it
                },
            )
        }
        item {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_clipboard_extracted),
                checked = extracted,
                enabled = strip,
                onCheckedChange = {
                    extracted = it
                    prefs.showExtracted = it
                },
            )
        }
        item {
            SettingsChoiceRow(
                title = stringResource(R.string.settings_clipboard_icon_place),
                selected = placement,
                choices = ButtonPlacement.entries.map { Choice(it, stringResource(it.label)) },
                onSelected = {
                    placement = it
                    prefs.iconPlacement = it
                },
            )
        }
        item {
            IconColorRow(color) {
                color = it
                prefs.iconColor = it
            }
        }
        item {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_clipboard_history),
                checked = history,
                onCheckedChange = {
                    history = it
                    prefs.keepHistory = it
                },
            )
        }
    }
}
