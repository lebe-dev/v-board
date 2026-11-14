package ru.tinyops.android.vboard.voice

import ru.tinyops.android.vboard.core.voice.DictationPlan
import ru.tinyops.android.vboard.core.voice.FeatureConnection
import ru.tinyops.android.vboard.core.voice.Improvement
import ru.tinyops.android.vboard.core.voice.MicChoice
import ru.tinyops.android.vboard.core.voice.MicSettings
import ru.tinyops.android.vboard.core.voice.ServerProfile
import ru.tinyops.android.vboard.core.voice.TextFeature
import ru.tinyops.android.vboard.core.voice.find

/** The settings the modes of the microphone depend on. */
private val VoicePrefs.micSettings: MicSettings
    get() =
        MicSettings(
            recordingMode = recordingMode,
            enhancementOn = featureEnabled(TextFeature.DICTATION),
            dictationReady = featureConfigured(TextFeature.DICTATION),
            serverReady = servers.find(featureServerId(TextFeature.DICTATION))?.isComplete == true,
            library = prompts.library,
        )

/** How a tap on the microphone dictates. */
fun VoicePrefs.micTapPlan(): DictationPlan = micButton.button.tapPlan(micSettings)

/** The modes the menu of a hold on the microphone offers. */
fun VoicePrefs.micHoldChoices(): List<MicChoice> = micButton.button.holdChoices(micSettings)

/** A tap on the microphone improves the text; the microphone then carries a sparkle. */
val VoicePrefs.micEnhanced: Boolean get() = micTapPlan().improvement != Improvement.None

/** The server, prompt and level of effort [improvement] sends text with; null when it sends none. */
fun VoicePrefs.improvementConnection(improvement: Improvement): FeatureConnection? =
    when (improvement) {
        Improvement.None -> null
        Improvement.Dictation -> connection(TextFeature.DICTATION)
        is Improvement.Prompts -> {
            val profile = servers.find(featureServerId(TextFeature.DICTATION)) ?: ServerProfile(id = "")
            FeatureConnection(profile.forEnhancement(prompts.library.compose(improvement.ids)), profile.effort)
        }
    }

/** The prompts [improvement] sends, as the buffer of prompt results remembers them. */
fun VoicePrefs.improvementPromptIds(improvement: Improvement): List<String> =
    when (improvement) {
        Improvement.None -> emptyList()
        Improvement.Dictation -> chosenPrompts(TextFeature.DICTATION)
        is Improvement.Prompts -> improvement.ids
    }
