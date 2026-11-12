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
import ru.tinyops.android.vboard.core.input.SuggestionTimeout

/** The word suggestions on the strip: when they go away and where they are shown. */
@Composable
internal fun SuggestionsScreen(onBack: () -> Unit) {
    SettingsPage(title = stringResource(R.string.settings_suggestions), onBack = onBack) {
        item {
            SettingsSection(null) {
                item { SuggestionTimeoutRow() }
                item { SuggestAlwaysRow() }
            }
        }
    }
}

/** How long word suggestions stay on the strip, covering the buttons there. */
@Composable
internal fun SuggestionTimeoutRow() {
    val prefs = KeyboardPrefs(LocalContext.current)
    var timeout by remember { mutableStateOf(prefs.suggestionTimeout) }
    SettingsChoiceRow(
        title = stringResource(R.string.settings_suggestion_timeout),
        description = stringResource(R.string.settings_suggestion_timeout_description),
        icon = R.drawable.ic_timer,
        prominent = true,
        selected = timeout,
        choices =
            listOf(
                Choice(SuggestionTimeout.SECONDS_3, stringResource(R.string.settings_suggestion_timeout_3)),
                Choice(SuggestionTimeout.SECONDS_5, stringResource(R.string.settings_suggestion_timeout_5)),
                Choice(SuggestionTimeout.SECONDS_10, stringResource(R.string.settings_suggestion_timeout_10)),
                Choice(SuggestionTimeout.OFF, stringResource(R.string.settings_suggestion_timeout_off)),
            ),
        onSelected = {
            timeout = it
            prefs.suggestionTimeout = it
        },
    )
}

/** Whether words are suggested also in the fields whose apps ask for no suggestions. */
@Composable
internal fun SuggestAlwaysRow() {
    val prefs = KeyboardPrefs(LocalContext.current)
    var always by remember { mutableStateOf(prefs.suggestAlways) }
    SettingsSwitchRow(
        title = stringResource(R.string.settings_suggest_always),
        summary = stringResource(R.string.settings_suggest_always_summary),
        icon = R.drawable.ic_text_fields,
        prominent = true,
        checked = always,
        onCheckedChange = {
            always = it
            prefs.suggestAlways = it
        },
    )
}
