package ru.tinyops.android.vboard.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ru.tinyops.android.vboard.KeyboardPrefs
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.input.DeleteHoldMode

/** What holding the delete key does. */
@Composable
internal fun DeleteKeyScreen(onBack: () -> Unit) {
    val prefs = KeyboardPrefs(LocalContext.current)
    var mode by remember { mutableStateOf(prefs.deleteHold) }
    SettingsPage(title = stringResource(R.string.delete_key_title), onBack = onBack) {
        item {
            SettingsSection(null) {
                item { SettingsNote(stringResource(R.string.delete_key_note), icon = R.drawable.ic_backspace) }
            }
        }
        item {
            SettingsSection(stringResource(R.string.delete_key_hold)) {
                DeleteHoldMode.entries.forEach { option ->
                    item {
                        SettingsRadioRow(
                            title = stringResource(deleteHoldLabel(option)),
                            summary = stringResource(deleteHoldSummary(option)),
                            selected = mode == option,
                            onClick = {
                                mode = option
                                prefs.deleteHold = option
                            },
                        )
                    }
                }
            }
        }
    }
}

@StringRes
internal fun deleteHoldLabel(mode: DeleteHoldMode): Int =
    when (mode) {
        DeleteHoldMode.CHARACTERS -> R.string.delete_hold_characters
        DeleteHoldMode.WORDS -> R.string.delete_hold_words
        DeleteHoldMode.ALL -> R.string.delete_hold_all
        DeleteHoldMode.NONE -> R.string.delete_hold_none
    }

@StringRes
private fun deleteHoldSummary(mode: DeleteHoldMode): Int =
    when (mode) {
        DeleteHoldMode.CHARACTERS -> R.string.delete_hold_characters_summary
        DeleteHoldMode.WORDS -> R.string.delete_hold_words_summary
        DeleteHoldMode.ALL -> R.string.delete_hold_all_summary
        DeleteHoldMode.NONE -> R.string.delete_hold_none_summary
    }
