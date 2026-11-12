package ru.tinyops.android.vboard.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ru.tinyops.android.vboard.KeyboardPrefs
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.ButtonPlacement

/** Whether the strip offers the fullscreen text editing, and where and in which color its button stands. */
@Composable
internal fun FullscreenScreen(onBack: () -> Unit) {
    val prefs = KeyboardPrefs(LocalContext.current)
    var enabled by remember { mutableStateOf(prefs.fullscreenEnabled) }
    var placement by remember { mutableStateOf(prefs.fullscreenPlacement) }
    var color by remember { mutableStateOf(prefs.fullscreenColor) }
    SettingsPage(title = stringResource(R.string.settings_fullscreen), onBack = onBack) {
        item {
            SettingsSection(null) {
                item {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_fullscreen_enabled),
                        summary = stringResource(R.string.settings_fullscreen_summary),
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            prefs.fullscreenEnabled = it
                        },
                    )
                }
                if (enabled) {
                    item {
                        SettingsChoiceRow(
                            title = stringResource(R.string.settings_fullscreen_icon_place),
                            selected = placement,
                            choices = ButtonPlacement.entries.map { Choice(it, stringResource(it.label)) },
                            onSelected = {
                                placement = it
                                prefs.fullscreenPlacement = it
                            },
                        )
                    }
                    item {
                        IconColorRow(color) {
                            color = it
                            prefs.fullscreenColor = it
                        }
                    }
                }
            }
        }
    }
}
