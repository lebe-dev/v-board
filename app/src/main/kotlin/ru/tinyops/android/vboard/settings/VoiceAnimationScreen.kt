package ru.tinyops.android.vboard.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.VoiceAnimation

/** "Анимации": how the strip looks while dictating. */
@Composable
internal fun VoiceAnimationScreen(
    state: VoiceSettingsState,
    onBack: () -> Unit,
) {
    val selected = state.read { voiceAnimation }
    SettingsPage(title = stringResource(R.string.settings_voice_animation), onBack = onBack) {
        item {
            SettingsSection(stringResource(R.string.settings_voice_animation_section)) {
                for (animation in VoiceAnimation.entries) {
                    item {
                        SettingsRadioRow(
                            title = stringResource(animation.label),
                            summary = stringResource(animation.summary),
                            selected = animation == selected,
                            onClick = { state.update { voiceAnimation = animation } },
                        )
                    }
                }
            }
        }
        item { SettingsNote(stringResource(R.string.settings_voice_animation_note), icon = R.drawable.ic_info) }
    }
}

@get:StringRes
internal val VoiceAnimation.label: Int
    get() =
        when (this) {
            VoiceAnimation.AURORA -> R.string.settings_voice_animation_aurora
            VoiceAnimation.DROPS -> R.string.settings_voice_animation_drops
            VoiceAnimation.CLASSIC -> R.string.settings_voice_animation_classic
        }

@get:StringRes
private val VoiceAnimation.summary: Int
    get() =
        when (this) {
            VoiceAnimation.AURORA -> R.string.settings_voice_animation_aurora_summary
            VoiceAnimation.DROPS -> R.string.settings_voice_animation_drops_summary
            VoiceAnimation.CLASSIC -> R.string.settings_voice_animation_classic_summary
        }
