package ru.tinyops.android.vboard.settings

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ru.tinyops.android.vboard.KeyboardPrefs
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.input.DeleteHoldMode
import kotlin.math.roundToInt

/** What holding the delete key does. */
@Composable
internal fun DeleteKeyScreen(onBack: () -> Unit) {
    val prefs = KeyboardPrefs(LocalContext.current)
    var mode by remember { mutableStateOf(prefs.deleteHold) }
    var interval by remember { mutableIntStateOf(prefs.wordDeleteInterval) }
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
        if (mode == DeleteHoldMode.WORDS) {
            item {
                SettingsSection(stringResource(R.string.delete_words_speed)) {
                    item {
                        WordSpeedRow(interval) {
                            interval = it
                            prefs.wordDeleteInterval = it
                        }
                    }
                }
            }
        }
    }
}

/** Words per second while delete is held; the slider's right end is the fastest. */
@Composable
private fun WordSpeedRow(
    interval: Int,
    onChange: (Int) -> Unit,
) {
    val min = KeyboardPrefs.MIN_WORD_DELETE_INTERVAL
    val max = KeyboardPrefs.MAX_WORD_DELETE_INTERVAL
    val label = stringResource(R.string.delete_words_speed)
    val perSecond = MILLIS_PER_SECOND / interval
    SettingsTileContent {
        Text(
            stringResource(R.string.delete_words_speed_value, label, perSecond),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.delete_words_speed_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = (min + max - interval).toFloat(),
            onValueChange = { onChange(min + max - it.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min) / KeyboardPrefs.WORD_DELETE_INTERVAL_STEP - 1,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

private const val MILLIS_PER_SECOND = 1000f

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
