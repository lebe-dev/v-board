package ru.tinyops.android.vboard.voice

import android.content.Context
import androidx.annotation.DrawableRes
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.MicEnding
import ru.tinyops.android.vboard.core.voice.MicMode
import ru.tinyops.android.vboard.core.voice.MicProcessing
import ru.tinyops.android.vboard.core.voice.PromptLibrary

/** The name of the mode in the menu of the microphone and in the settings: its processing, then its own ending. */
fun MicMode.label(
    context: Context,
    library: PromptLibrary,
): String {
    val base =
        when (processing) {
            MicProcessing.DEFAULT -> context.getString(R.string.mic_mode_default)
            MicProcessing.NONE -> context.getString(R.string.mic_mode_none)
            MicProcessing.ENHANCE -> context.getString(R.string.mic_mode_enhance)
            MicProcessing.PROMPTS ->
                library.titles(promptIds).joinToString(", ").ifEmpty { context.getString(R.string.mic_mode_no_prompts) }
        }
    return when (ending) {
        MicEnding.DEFAULT -> base
        MicEnding.AFTER_PAUSE -> context.getString(R.string.mic_mode_after_pause, base)
        MicEnding.REPEAT_TAP -> context.getString(R.string.mic_mode_repeat_tap, base)
    }
}

/** The icon of the mode: a plain microphone, the microphone with a sparkle for improvement, sparkles for prompts. */
@get:DrawableRes
val MicMode.icon: Int
    get() =
        when (processing) {
            MicProcessing.DEFAULT, MicProcessing.NONE -> R.drawable.ic_mic
            MicProcessing.ENHANCE -> R.drawable.ic_mic_spark
            MicProcessing.PROMPTS -> R.drawable.ic_auto_awesome
        }
