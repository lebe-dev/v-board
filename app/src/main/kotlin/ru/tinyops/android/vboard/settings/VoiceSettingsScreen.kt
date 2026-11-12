package ru.tinyops.android.vboard.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.AutoStop
import ru.tinyops.android.vboard.core.voice.GovorunApi
import ru.tinyops.android.vboard.core.voice.PauseLength
import ru.tinyops.android.vboard.core.voice.RecognitionEngine
import ru.tinyops.android.vboard.core.voice.RecordingMode

@Composable
internal fun VoiceSettingsScreen(
    state: VoiceSettingsState,
    microphoneGranted: Boolean,
    recognition: RecognitionStatus,
    onRequestMicrophone: () -> Unit,
    links: SettingsLinks,
    onBack: () -> Unit,
) {
    SettingsPage(title = stringResource(R.string.settings_voice), onBack = onBack) {
        item { Intro(recognition) }
        item { VoiceSwitch(state) }
        if (!microphoneGranted) item { MicrophoneCard(onRequestMicrophone) }
        item { StatusSection(microphoneGranted, recognition, links.connectGovorun) }
        item { RecognitionSection(state, links.remote, links.engineChanged) }
        item { DictationSection(state) }
        item { LongRecordingSection(state) }
        if (state.read { engine } == RecognitionEngine.GOVORUN) item { AboutSection() }
    }
}

@Composable
private fun VoiceSwitch(state: VoiceSettingsState) {
    val enabled = state.read { voiceEnabled }
    val summary =
        if (enabled) R.string.settings_voice_enabled_summary_on else R.string.settings_voice_enabled_summary_off
    SettingsSection(null) {
        item {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_voice_enabled),
                summary = stringResource(summary),
                icon = R.drawable.ic_microphone,
                checked = enabled,
                onCheckedChange = { state.update { voiceEnabled = it } },
            )
        }
        if (enabled) item { IconColorRow(state.read { micColor }) { state.update { micColor = it } } }
    }
}

@Composable
private fun Intro(recognition: RecognitionStatus) {
    val summary =
        when (recognition) {
            RecognitionStatus.REMOTE -> R.string.settings_voice_summary_remote
            RecognitionStatus.SYSTEM, RecognitionStatus.SYSTEM_UNAVAILABLE -> R.string.settings_voice_summary
            else -> R.string.settings_voice_summary_govorun
        }
    Text(
        stringResource(summary),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = SettingsSpacing.Row),
    )
}

/** Voice input cannot work without the microphone: the one action that matters goes first. */
@Composable
private fun MicrophoneCard(onRequestMicrophone: () -> Unit) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
    ) {
        Column(
            modifier = Modifier.padding(SettingsSpacing.Row),
            verticalArrangement = Arrangement.spacedBy(SettingsSpacing.Small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_mic_off), contentDescription = null)
                Spacer(Modifier.width(SettingsSpacing.Gap))
                Text(
                    stringResource(R.string.settings_microphone_needed),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(stringResource(R.string.settings_microphone_why), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().padding(top = SettingsSpacing.Small), horizontalArrangement = Arrangement.End) {
                Button(onClick = onRequestMicrophone) { Text(stringResource(R.string.settings_microphone_allow)) }
            }
        }
    }
}

@Composable
private fun StatusSection(
    microphoneGranted: Boolean,
    recognition: RecognitionStatus,
    onConnectGovorun: () -> Unit,
) {
    val uri = LocalUriHandler.current
    SettingsSection(stringResource(R.string.settings_voice_status)) {
        item {
            when (recognition) {
                RecognitionStatus.SYSTEM ->
                    SettingsStatusRow(
                        stringResource(R.string.settings_system_ready),
                        StatusTone.SUCCESS,
                        R.drawable.ic_check_circle,
                    )
                RecognitionStatus.SYSTEM_UNAVAILABLE ->
                    SettingsStatusRow(
                        stringResource(R.string.settings_system_unavailable),
                        StatusTone.ERROR,
                        R.drawable.ic_error,
                    )
                RecognitionStatus.CHECKING ->
                    SettingsStatusRow(stringResource(R.string.settings_govorun_checking), StatusTone.PROGRESS)
                RecognitionStatus.READY ->
                    SettingsStatusRow(
                        stringResource(R.string.settings_govorun_ready),
                        StatusTone.SUCCESS,
                        R.drawable.ic_check_circle,
                    )
                RecognitionStatus.REMOTE ->
                    SettingsStatusRow(
                        stringResource(R.string.settings_model_remote),
                        StatusTone.NEUTRAL,
                        R.drawable.ic_cloud,
                    )
                RecognitionStatus.NOT_INSTALLED ->
                    SettingsLinkRow(
                        title = stringResource(R.string.settings_govorun_install),
                        summary = stringResource(R.string.settings_govorun_install_summary),
                        icon = R.drawable.ic_error,
                        external = true,
                        onClick = { runCatching { uri.openUri(GOVORUN_STORE_URL) } },
                    )
                RecognitionStatus.NEEDS_ACCESS ->
                    SettingsLinkRow(
                        title = stringResource(R.string.settings_govorun_connect),
                        summary = stringResource(R.string.settings_govorun_connect_summary),
                        icon = R.drawable.ic_microphone,
                        onClick = onConnectGovorun,
                    )
                RecognitionStatus.UNAVAILABLE ->
                    SettingsStatusRow(
                        stringResource(R.string.settings_govorun_unavailable),
                        StatusTone.ERROR,
                        R.drawable.ic_error,
                    )
            }
        }
        if (microphoneGranted) {
            item {
                SettingsStatusRow(
                    stringResource(R.string.settings_microphone_granted),
                    StatusTone.SUCCESS,
                    R.drawable.ic_microphone,
                )
            }
        }
    }
}

@Composable
private fun RecognitionSection(
    state: VoiceSettingsState,
    onOpenRemote: () -> Unit,
    onEngineChanged: () -> Unit,
) {
    val remote = state.read { remoteReady }
    val engine = state.read { engine }
    val remoteModel = state.read { remoteServer.model }
    val headset = state.read { headsetMicrophone }
    val pause = state.read { pause }
    val remoteSummary =
        if (remote) {
            stringResource(R.string.settings_remote_on, remoteModel)
        } else {
            stringResource(R.string.settings_remote_off)
        }
    val microphones =
        listOf(
            Choice(false, stringResource(R.string.settings_microphone_phone)),
            Choice(true, stringResource(R.string.settings_microphone_headset)),
        )
    SettingsSection(stringResource(R.string.settings_recognition)) {
        if (!remote) {
            item {
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_engine),
                    icon = R.drawable.ic_microphone,
                    selected = engine,
                    choices = engineChoices(),
                    onSelected = {
                        state.update { this.engine = it }
                        onEngineChanged()
                    },
                )
            }
        }
        item {
            SettingsLinkRow(
                title = stringResource(R.string.remote_title),
                summary = remoteSummary,
                icon = R.drawable.ic_cloud,
                onClick = onOpenRemote,
            )
        }
        // The system recognizer picks its own microphone and decides where a phrase ends.
        if (remote || engine == RecognitionEngine.GOVORUN) {
            item {
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_microphone),
                    description = stringResource(R.string.settings_microphone_summary),
                    icon = R.drawable.ic_microphone,
                    selected = headset,
                    choices = microphones,
                    onSelected = { state.update { headsetMicrophone = it } },
                )
            }
            item {
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_pause),
                    description = stringResource(R.string.settings_pause_summary),
                    icon = R.drawable.ic_timer,
                    selected = if (remote) pause else GovorunApi.pauseChoice(pause),
                    choices = pauseChoices(remote),
                    onSelected = { state.update { this.pause = it } },
                )
            }
        }
    }
}

@Composable
private fun engineChoices() =
    listOf(
        Choice(
            RecognitionEngine.SYSTEM,
            stringResource(R.string.settings_engine_system),
            stringResource(R.string.settings_engine_system_summary),
        ),
        Choice(
            RecognitionEngine.GOVORUN,
            stringResource(R.string.settings_engine_govorun),
            stringResource(R.string.settings_engine_govorun_summary),
        ),
    )

/** Govorun's API offers only 0.5 s or the pause chosen in Govorun itself. */
@Composable
private fun pauseChoices(remote: Boolean) =
    if (remote) {
        listOf(
            Choice(PauseLength.AUTO, stringResource(R.string.settings_pause_auto)),
            Choice(PauseLength.SHORT, stringResource(R.string.settings_pause_short)),
            Choice(PauseLength.MEDIUM, stringResource(R.string.settings_pause_medium)),
            Choice(PauseLength.LONG, stringResource(R.string.settings_pause_long)),
        )
    } else {
        listOf(
            Choice(PauseLength.AUTO, stringResource(R.string.settings_pause_govorun)),
            Choice(PauseLength.SHORT, stringResource(R.string.settings_pause_short)),
        )
    }

@Composable
private fun DictationSection(state: VoiceSettingsState) {
    val mode = state.read { recordingMode }
    val modes =
        listOf(
            Choice(
                RecordingMode.AFTER_PAUSE,
                stringResource(R.string.settings_mode_after_pause),
                stringResource(R.string.settings_mode_after_pause_summary),
            ),
            Choice(
                RecordingMode.REPEAT_TAP,
                stringResource(R.string.settings_mode_repeat_tap),
                stringResource(R.string.settings_mode_repeat_tap_summary),
            ),
        )
    SettingsSection(stringResource(R.string.settings_dictation)) {
        item {
            SettingsChoiceRow(
                title = stringResource(R.string.settings_mode),
                icon = R.drawable.ic_stop_circle,
                selected = mode,
                choices = modes,
                onSelected = { state.update { recordingMode = it } },
            )
        }
    }
}

@Composable
private fun LongRecordingSection(state: VoiceSettingsState) {
    val keepScreenOn = state.read { keepScreenOn }
    val autoStop = state.read { autoStop }
    val limits =
        listOf(
            Choice(AutoStop.MINUTES_15, stringResource(R.string.settings_auto_stop_15m)),
            Choice(AutoStop.HOUR_1, stringResource(R.string.settings_auto_stop_1h)),
            Choice(AutoStop.HOURS_3, stringResource(R.string.settings_auto_stop_3h)),
            Choice(AutoStop.OFF, stringResource(R.string.settings_auto_stop_off)),
        )
    val keepScreenOnSummary =
        stringResource(
            if (keepScreenOn) {
                R.string.settings_keep_screen_on_summary_on
            } else {
                R.string.settings_keep_screen_on_summary_off
            },
        )
    SettingsSection(stringResource(R.string.settings_long_recording)) {
        item {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_keep_screen_on),
                summary = keepScreenOnSummary,
                icon = R.drawable.ic_light_mode,
                checked = keepScreenOn,
                onCheckedChange = { state.update { this.keepScreenOn = it } },
            )
        }
        if (keepScreenOn) {
            item {
                SettingsChoiceRow(
                    title = stringResource(R.string.settings_auto_stop),
                    icon = R.drawable.ic_timer_off,
                    selected = autoStop,
                    choices = limits,
                    onSelected = { state.update { this.autoStop = it } },
                )
            }
        }
    }
}

@Composable
private fun AboutSection() {
    val uri = LocalUriHandler.current
    SettingsSection(stringResource(R.string.settings_about)) {
        item {
            SettingsLinkRow(
                title = stringResource(R.string.settings_powered_by),
                summary = GOVORUN_URL.removePrefix("https://"),
                icon = R.drawable.ic_code,
                external = true,
                onClick = { runCatching { uri.openUri(GOVORUN_URL) } },
            )
        }
    }
}

/** The app that recognizes speech for the keyboard. */
private const val GOVORUN_URL = "https://github.com/amidexe/govorun-lite"
private const val GOVORUN_STORE_URL = "https://www.rustore.ru/catalog/app/com.govorun.lite"
