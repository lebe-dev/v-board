package ru.tinyops.android.vboard.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.PromptPlace
import ru.tinyops.android.vboard.core.voice.ServerKind
import ru.tinyops.android.vboard.core.voice.find

/**
 * "Распознавание на сервере": an OpenAI-compatible `/audio/transcriptions` API (OpenAI, OpenRouter,
 * Groq, speaches…) instead of the engine chosen on the device. Off by default; the server is chosen from
 * the list of servers, and audio goes there only while this is on.
 */
@Composable
internal fun RemoteRecognitionScreen(
    state: VoiceSettingsState,
    onOpenServers: () -> Unit,
    onBack: () -> Unit,
) {
    val chosen = state.read { servers.find(recognitionServerId) }
    val configured = state.read { remoteServer.let { chosen != null && it.isComplete } }
    SettingsPage(title = stringResource(R.string.remote_title), onBack = onBack) {
        item {
            SettingsMainSwitch(
                title = stringResource(R.string.remote_enabled),
                summary =
                    stringResource(
                        if (configured) R.string.remote_enabled_summary else R.string.remote_enabled_unavailable,
                    ),
                checked = state.read { remoteReady },
                enabled = configured,
                onCheckedChange = { state.update { remoteEnabled = it } },
            )
        }
        item {
            SettingsSection(stringResource(R.string.remote_connection)) {
                item { SettingsNote(stringResource(R.string.remote_connection_summary), icon = R.drawable.ic_info) }
                item {
                    ServerPickerRow(
                        state = state,
                        kind = ServerKind.AUDIO,
                        selected = state.read { chosenRecognitionServerId },
                        onSelected = { state.update { recognitionServerId = it } },
                        onOpenServers = onOpenServers,
                    )
                }
                if (chosen?.insecure == true) {
                    item { SettingsNote(stringResource(R.string.remote_http_warning), icon = R.drawable.ic_warning) }
                }
                item { SettingsNote(stringResource(R.string.remote_data_notice), icon = R.drawable.ic_info) }
            }
        }
        item { RemoteOptionsSection(state) }
    }
}

@Composable
private fun RemoteOptionsSection(state: VoiceSettingsState) {
    val server = state.read { remoteServer }
    var language by rememberSaveable { mutableStateOf(state.read { remoteLanguage }) }
    val promptEnabled = state.read { remotePromptEnabled }
    SettingsSection(stringResource(R.string.remote_options)) {
        item {
            SettingsTileContent {
                OutlinedTextField(
                    value = language,
                    onValueChange = {
                        language = it.take(MAX_LANGUAGE_LENGTH)
                        state.update { remoteLanguage = language }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.remote_language)) },
                    supportingText = { Text(stringResource(R.string.remote_language_summary)) },
                    keyboardOptions = plainKeyboard(),
                    isError = !server.languageValid,
                )
            }
        }
        item {
            SettingsChoiceRow(
                title = stringResource(R.string.remote_mode),
                icon = R.drawable.ic_auto_fix_high,
                selected = promptEnabled,
                choices = modeChoices(),
                onSelected = { state.update { remotePromptEnabled = it } },
            )
        }
        if (promptEnabled) {
            item {
                SettingsTileContent {
                    PromptPickerRow(
                        title = stringResource(R.string.remote_prompt),
                        library = state.read { prompts.library },
                        selected = state.read { prompts.ids(PromptPlace.RECOGNITION) },
                        onChange = { ids -> state.update { prompts.setIds(PromptPlace.RECOGNITION, ids) } },
                    )
                    Text(
                        stringResource(
                            if (server.promptValid) R.string.remote_prompt_summary else R.string.remote_prompt_error,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (server.promptValid) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun modeChoices() =
    listOf(
        Choice(false, stringResource(R.string.remote_mode_raw), stringResource(R.string.remote_mode_raw_summary)),
        Choice(true, stringResource(R.string.remote_mode_prompt), stringResource(R.string.remote_mode_prompt_summary)),
    )

private const val MAX_LANGUAGE_LENGTH = 3
