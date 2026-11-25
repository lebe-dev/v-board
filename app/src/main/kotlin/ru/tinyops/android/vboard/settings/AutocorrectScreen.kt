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

/** The things the keyboard fixes on its own while the user types. */
@Composable
internal fun AutocorrectScreen(onBack: () -> Unit) {
    val prefs = KeyboardPrefs(LocalContext.current)
    var autoCapitalize by remember { mutableStateOf(prefs.autoCapitalize) }
    var cleanLinks by remember { mutableStateOf(prefs.cleanLinks) }
    var autocorrect by remember { mutableStateOf(prefs.autocorrect) }
    var twoEditCorrections by remember { mutableStateOf(prefs.twoEditCorrections) }
    SettingsPage(title = stringResource(R.string.settings_autocorrect), onBack = onBack) {
        item {
            SettingsSection(null) {
                item {
                    SettingsSwitchRow(
                        title = stringResource(R.string.autocorrect_capitalize),
                        summary = stringResource(R.string.autocorrect_capitalize_summary),
                        checked = autoCapitalize,
                        onCheckedChange = {
                            autoCapitalize = it
                            prefs.autoCapitalize = it
                        },
                    )
                }
                item {
                    SettingsSwitchRow(
                        title = stringResource(R.string.autocorrect_clean_links),
                        summary = stringResource(R.string.autocorrect_clean_links_summary),
                        checked = cleanLinks,
                        onCheckedChange = {
                            cleanLinks = it
                            prefs.cleanLinks = it
                        },
                    )
                }
                item {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_tap_autocorrect),
                        summary = stringResource(R.string.settings_tap_autocorrect_summary),
                        checked = autocorrect,
                        onCheckedChange = {
                            autocorrect = it
                            prefs.autocorrect = it
                        },
                    )
                }
                item {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_two_edit_corrections),
                        summary = stringResource(R.string.settings_two_edit_corrections_summary),
                        checked = twoEditCorrections,
                        onCheckedChange = {
                            twoEditCorrections = it
                            prefs.twoEditCorrections = it
                        },
                    )
                }
            }
        }
    }
}
