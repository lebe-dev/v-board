package ru.tinyops.android.vboard.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.EnhancementThreshold
import ru.tinyops.android.vboard.core.voice.PromptPlace
import ru.tinyops.android.vboard.core.voice.ServerKind
import ru.tinyops.android.vboard.core.voice.TextFeature
import ru.tinyops.android.vboard.core.voice.find
import ru.tinyops.android.vboard.core.voice.promptPlace

/**
 * The page of one function of text improvement: the switch, the server it sends text to and its prompt.
 * Servers are not set up here but chosen from the list.
 */
@Composable
internal fun TextFeatureScreen(
    feature: TextFeature,
    state: VoiceSettingsState,
    onOpenServers: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(title = stringResource(featureTitle(feature)), onBack = onBack) {
        item { FeatureSwitch(feature, state) }
        if (feature == TextFeature.PROOFREAD) item { ProofreadLookSection(state) }
        if (feature == TextFeature.DICTATION) item { ThresholdSection(state) }
        item {
            SettingsSection(stringResource(R.string.feature_server)) {
                item {
                    ServerPickerRow(
                        state = state,
                        kind = ServerKind.TEXT,
                        selected = state.read { chosenServerId(feature) },
                        onSelected = { state.update { setFeatureServer(feature, it) } },
                        onOpenServers = onOpenServers,
                    )
                }
                if (state.read { servers.find(featureServerId(feature))?.insecure } == true) {
                    item { SettingsNote(stringResource(R.string.server_http_warning), icon = R.drawable.ic_warning) }
                }
                item { SettingsNote(stringResource(R.string.server_notice), icon = R.drawable.ic_info) }
            }
        }
        item {
            val proofread = feature == TextFeature.PROOFREAD
            val promptTitle = if (proofread) R.string.feature_proofread_tap_prompt else R.string.server_prompt
            SettingsSection(stringResource(promptTitle)) {
                item {
                    PromptPickerRow(
                        title = stringResource(promptTitle),
                        library = state.read { prompts.library },
                        selected = state.read { chosenPrompts(feature) },
                        onChange = { ids -> state.update { prompts.setIds(feature.promptPlace, ids) } },
                    )
                }
                if (state.read { chosenPrompts(feature).size } > 1) {
                    item { SettingsNote(stringResource(R.string.prompt_picker_merge), icon = R.drawable.ic_info) }
                }
            }
        }
        if (feature == TextFeature.PROOFREAD) item { HoldPromptSection(state) }
    }
}

/** The color of the proofread icon on the strip. */
@Composable
private fun ProofreadLookSection(state: VoiceSettingsState) {
    SettingsSection(null) {
        item { IconColorRow(state.read { proofreadColor }) { state.update { proofreadColor = it } } }
    }
}

/** The prompt of a hold on the proofread button; without one the hold does nothing. */
@Composable
private fun HoldPromptSection(state: VoiceSettingsState) {
    SettingsSection(stringResource(R.string.feature_proofread_hold_prompt)) {
        item {
            PromptPickerRow(
                title = stringResource(R.string.feature_proofread_hold_prompt),
                library = state.read { prompts.library },
                selected = state.read { prompts.ids(PromptPlace.PROOFREAD_HOLD) },
                onChange = { ids -> state.update { prompts.setIds(PromptPlace.PROOFREAD_HOLD, ids.ifEmpty { null }) } },
                optional = true,
            )
        }
        item { SettingsNote(stringResource(R.string.feature_proofread_hold_note), icon = R.drawable.ic_info) }
    }
}

@Composable
private fun FeatureSwitch(
    feature: TextFeature,
    state: VoiceSettingsState,
) {
    val configured = state.read { featureConfigured(feature) }
    val title = if (feature == TextFeature.DICTATION) R.string.server_enabled else R.string.feature_proofread_enabled
    val summary =
        when {
            !configured -> R.string.feature_unavailable_server
            feature == TextFeature.DICTATION -> R.string.feature_dictation_summary
            else -> R.string.feature_proofread_summary
        }
    SettingsMainSwitch(
        title = stringResource(title),
        summary = stringResource(summary),
        checked = state.read { featureReady(feature) },
        enabled = configured,
        onCheckedChange = { state.update { setFeatureEnabled(feature, it) } },
    )
}

@Composable
private fun ThresholdSection(state: VoiceSettingsState) {
    val threshold = state.read { enhancementThreshold }
    val choices =
        listOf(
            Choice(EnhancementThreshold.ALWAYS, stringResource(R.string.server_threshold_always)),
            Choice(EnhancementThreshold.CHARS_30, stringResource(R.string.server_threshold_30)),
            Choice(EnhancementThreshold.CHARS_60, stringResource(R.string.server_threshold_60)),
            Choice(EnhancementThreshold.CHARS_120, stringResource(R.string.server_threshold_120)),
            Choice(EnhancementThreshold.CHARS_250, stringResource(R.string.server_threshold_250)),
            Choice(EnhancementThreshold.CHARS_500, stringResource(R.string.server_threshold_500)),
        )
    SettingsSection(stringResource(R.string.server_when)) {
        item {
            SettingsChoiceRow(
                title = stringResource(R.string.server_threshold),
                description = stringResource(R.string.server_threshold_description),
                selected = threshold,
                choices = choices,
                onSelected = { state.update { enhancementThreshold = it } },
            )
        }
    }
}
