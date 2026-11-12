package ru.tinyops.android.vboard.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import kotlinx.coroutines.launch
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.VoiceDictionary

/** "Словарь и автозамены": Govorun's text editor of replacement rules. */
@Composable
internal fun DictionaryScreen(
    state: VoiceSettingsState,
    onBack: () -> Unit,
) {
    val saved = state.read { dictionaryText }
    var text by rememberSaveable { mutableStateOf(saved) }
    var help by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val snackbar = rememberSnackbar()
    val scope = rememberCoroutineScope()
    val savedMessage = stringResource(R.string.dictionary_saved)
    val dirty = text != saved
    // Unsaved rules are not lost silently: leaving the page asks first.
    val leave = { if (dirty) discard = true else onBack() }
    BackHandler(enabled = dirty) { discard = true }

    SettingsPage(
        title = stringResource(R.string.dictionary_title),
        onBack = leave,
        actions = {
            TooltipIconButton(R.drawable.ic_help, stringResource(R.string.dictionary_help)) { help = true }
        },
        snackbar = snackbar,
    ) {
        item {
            SettingsSection(stringResource(R.string.dictionary_after_recognition)) {
                item {
                    SettingsSwitchRow(
                        title = stringResource(R.string.dictionary_enabled),
                        summary = stringResource(R.string.dictionary_enabled_summary),
                        checked = state.read { dictionaryEnabled },
                        onCheckedChange = { state.update { dictionaryEnabled = it } },
                    )
                }
            }
        }
        item {
            SettingsSection(stringResource(R.string.dictionary_rules)) {
                item {
                    SettingsTileContent {
                        val rules = VoiceDictionary.parse(text).size
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 6,
                            maxLines = 14,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            label = { Text(stringResource(R.string.dictionary_editor)) },
                            placeholder = { Text(stringResource(R.string.dictionary_placeholder)) },
                            supportingText = {
                                Text(pluralStringResource(R.plurals.settings_dictionary_rules, rules, rules))
                            },
                            keyboardOptions =
                                KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                ),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(
                                onClick = {
                                    state.update { dictionaryText = text }
                                    scope.launch { snackbar.showSnackbar(savedMessage) }
                                },
                                enabled = dirty,
                            ) { Text(stringResource(R.string.dictionary_save)) }
                        }
                    }
                }
            }
        }
    }
    if (help) HelpDialog { help = false }
    if (discard) {
        DiscardDialog(
            onDiscard = {
                discard = false
                onBack()
            },
            onDismiss = { discard = false },
        )
    }
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dictionary_help)) },
        text = {
            Text(stringResource(R.string.dictionary_help_body), Modifier.verticalScroll(rememberScrollState()))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dictionary_help_close)) }
        },
    )
}

@Composable
private fun DiscardDialog(
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dictionary_discard_title)) },
        text = { Text(stringResource(R.string.dictionary_discard_text)) },
        confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.dictionary_discard)) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dictionary_keep_editing)) }
        },
    )
}
