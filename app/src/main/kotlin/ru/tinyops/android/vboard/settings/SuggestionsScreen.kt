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

/** The word suggestions and manual-typing corrections on the strip. */
@Composable
internal fun SuggestionsScreen(onBack: () -> Unit) {
    val prefs = KeyboardPrefs(LocalContext.current)
    var tapCorrections by remember { mutableStateOf(prefs.tapCorrections) }
    SettingsPage(title = stringResource(R.string.settings_suggestions), onBack = onBack) {
        item {
            SettingsSection(null) {
                item { SuggestionTimeoutRow() }
                item { SuggestAlwaysRow() }
                item { SuggestNextWordRow() }
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_tap_corrections_section)) {
                item {
                    TapCorrectionsRow(
                        checked = tapCorrections,
                        onCheckedChange = {
                            tapCorrections = it
                            prefs.tapCorrections = it
                        },
                    )
                }
                if (tapCorrections) {
                    item { FuzzyPrefixRow() }
                    item { SpatialTapModelRow() }
                }
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

/** Whether the words that usually follow are suggested after a space. */
@Composable
internal fun SuggestNextWordRow() {
    val prefs = KeyboardPrefs(LocalContext.current)
    var nextWord by remember { mutableStateOf(prefs.suggestNextWord) }
    SettingsSwitchRow(
        title = stringResource(R.string.settings_suggest_next_word),
        summary = stringResource(R.string.settings_suggest_next_word_summary),
        icon = R.drawable.ic_auto_awesome,
        prominent = true,
        checked = nextWord,
        onCheckedChange = {
            nextWord = it
            prefs.suggestNextWord = it
        },
    )
}

/** Whether typo corrections are suggested on the strip while typing manually. */
@Composable
internal fun TapCorrectionsRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsSwitchRow(
        title = stringResource(R.string.settings_tap_corrections),
        summary = stringResource(R.string.settings_tap_corrections_summary),
        icon = R.drawable.ic_auto_fix_high,
        prominent = true,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

/** Whether completions are suggested even when an unfinished word contains a typo. */
@Composable
internal fun FuzzyPrefixRow() {
    val prefs = KeyboardPrefs(LocalContext.current)
    var enabled by remember { mutableStateOf(prefs.fuzzyPrefix) }
    SettingsSwitchRow(
        title = stringResource(R.string.settings_fuzzy_prefix),
        summary = stringResource(R.string.settings_fuzzy_prefix_summary),
        icon = R.drawable.ic_text_fields,
        prominent = true,
        checked = enabled,
        onCheckedChange = {
            enabled = it
            prefs.fuzzyPrefix = it
        },
    )
}

/** Whether exact touch coordinates on keys weigh typing corrections. */
@Composable
internal fun SpatialTapModelRow() {
    val prefs = KeyboardPrefs(LocalContext.current)
    var enabled by remember { mutableStateOf(prefs.spatialTapModel) }
    SettingsSwitchRow(
        title = stringResource(R.string.settings_spatial_tap_model),
        summary = stringResource(R.string.settings_spatial_tap_model_summary),
        icon = R.drawable.ic_auto_awesome,
        prominent = true,
        checked = enabled,
        onCheckedChange = {
            enabled = it
            prefs.spatialTapModel = it
        },
    )
}
