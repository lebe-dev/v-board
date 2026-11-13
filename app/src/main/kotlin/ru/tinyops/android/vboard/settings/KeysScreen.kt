package ru.tinyops.android.vboard.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.ui.KeyboardThemes
import ru.tinyops.android.vboard.ui.ThemeChoice

/** The shape of the keys that are pills by default: "?123" and the action key. */
@Composable
internal fun KeysScreen(
    choice: ThemeChoice,
    onChoice: (ThemeChoice) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val shapes = choice.shapes
    SettingsPage(title = stringResource(R.string.keys_title), onBack = onBack) {
        item {
            SettingsSection(null) {
                item {
                    SettingsSwitchRow(
                        title = stringResource(R.string.keys_square_mode),
                        summary = stringResource(R.string.keys_square_mode_summary),
                        checked = shapes.squareMode,
                        onCheckedChange = {
                            KeyboardThemes.setSquareModeKey(context, it)
                            onChoice(KeyboardThemes.selected(context))
                        },
                    )
                }
                item {
                    SettingsSwitchRow(
                        title = stringResource(R.string.keys_square_action),
                        summary = stringResource(R.string.keys_square_action_summary),
                        checked = shapes.squareAction,
                        onCheckedChange = {
                            KeyboardThemes.setSquareActionKey(context, it)
                            onChoice(KeyboardThemes.selected(context))
                        },
                    )
                }
            }
        }
    }
}
