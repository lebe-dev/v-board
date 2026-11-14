package ru.tinyops.android.vboard.core.voice

/** What a dictation started by the microphone does with the recognized text. */
enum class MicProcessing(
    val id: String,
) {
    /** As the text improvement switch says. */
    DEFAULT("default"),

    /** Straight to the editor. */
    NONE("none"),

    /** Through text improvement with the prompt of dictation, whatever the switch says. */
    ENHANCE("enhance"),

    /** Through text improvement with the prompts of the mode. */
    PROMPTS("prompts"),
    ;

    companion object {
        fun fromId(id: String?): MicProcessing = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** How a dictation started by the microphone ends. */
enum class MicEnding(
    val id: String,
) {
    /** As "Завершение диктовки" says. */
    DEFAULT("default"),
    AFTER_PAUSE("after_pause"),
    REPEAT_TAP("repeat_tap"),
    ;

    fun resolve(general: RecordingMode): RecordingMode =
        when (this) {
            DEFAULT -> general
            AFTER_PAUSE -> RecordingMode.AFTER_PAUSE
            REPEAT_TAP -> RecordingMode.REPEAT_TAP
        }

    companion object {
        fun fromId(id: String?): MicEnding = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Where the recognized text of a dictation goes before the editor. */
sealed interface Improvement {
    data object None : Improvement

    /** The prompt of dictation. */
    data object Dictation : Improvement

    /** The prompts [ids] of the collection, joined. */
    data class Prompts(
        val ids: List<String>,
    ) : Improvement
}

/** How one dictation runs. */
data class DictationPlan(
    val mode: RecordingMode,
    val improvement: Improvement,
)

/** The settings a [MicMode] depends on. */
data class MicSettings(
    val recordingMode: RecordingMode,
    /** The text improvement switch of dictation. */
    val enhancementOn: Boolean,
    /** The server of dictation and its prompt are complete. */
    val dictationReady: Boolean,
    /** The server of dictation is complete, whatever its prompt. */
    val serverReady: Boolean,
    val library: PromptLibrary,
)

/** A way to dictate: a tap on the microphone or an entry of the menu its hold opens. */
data class MicMode(
    val id: String,
    val processing: MicProcessing = MicProcessing.DEFAULT,
    /** The prompts of [MicProcessing.PROMPTS]. */
    val promptIds: List<String> = emptyList(),
    val ending: MicEnding = MicEnding.DEFAULT,
) {
    /** How the dictation runs under [settings]; null when the mode cannot work now. */
    fun plan(settings: MicSettings): DictationPlan? {
        val mode = ending.resolve(settings.recordingMode)
        val improvement =
            when (processing) {
                MicProcessing.DEFAULT ->
                    if (settings.enhancementOn && settings.dictationReady) Improvement.Dictation else Improvement.None
                MicProcessing.NONE -> Improvement.None
                MicProcessing.ENHANCE -> if (settings.dictationReady) Improvement.Dictation else return null
                MicProcessing.PROMPTS -> {
                    if (!settings.serverReady) return null
                    val ids = promptIds.filter { settings.library.compose(listOf(it)).isNotBlank() }
                    if (ids.isEmpty()) return null
                    Improvement.Prompts(ids)
                }
            }
        return DictationPlan(mode, improvement)
    }
}

/** A [mode] of the hold menu that can work, with its [plan]. */
data class MicChoice(
    val mode: MicMode,
    val plan: DictationPlan,
)

/** What the microphone does: [tap] on a tap, a menu of [hold] modes on a hold. */
data class MicButton(
    val tap: MicMode = MicMode(TAP_ID),
    val hold: List<MicMode> = DEFAULT_HOLD,
) {
    /** A tap always dictates: a mode that cannot work dictates without improvement. */
    fun tapPlan(settings: MicSettings): DictationPlan =
        tap.plan(settings) ?: DictationPlan(tap.ending.resolve(settings.recordingMode), Improvement.None)

    /** The hold modes that can work now, in their order. */
    fun holdChoices(settings: MicSettings): List<MicChoice> =
        hold.mapNotNull { mode -> mode.plan(settings)?.let { MicChoice(mode, it) } }

    val holdFull: Boolean get() = hold.size >= MAX_HOLD

    /** Replaces the hold mode with the id of [mode] in place, or adds it at the end while there is room. */
    fun withHold(mode: MicMode): MicButton =
        when {
            hold.any { it.id == mode.id } -> copy(hold = hold.map { if (it.id == mode.id) mode else it })
            holdFull -> this
            else -> copy(hold = hold + mode)
        }

    fun withoutHold(id: String): MicButton = copy(hold = hold.filterNot { it.id == id })

    /** Moves the hold mode [id] by [offset] places, as far as the list goes. */
    fun movedHold(
        id: String,
        offset: Int,
    ): MicButton {
        val from = hold.indexOfFirst { it.id == id }
        if (from < 0) return this
        val to = (from + offset).coerceIn(0, hold.lastIndex)
        if (to == from) return this
        val next = hold.toMutableList()
        next.add(to, next.removeAt(from))
        return copy(hold = next)
    }

    companion object {
        const val TAP_ID = "tap"
        const val MAX_HOLD = 8
        private val DEFAULT_HOLD =
            listOf(
                MicMode("plain", MicProcessing.NONE),
                MicMode("improved", MicProcessing.ENHANCE),
            )
        val DEFAULT = MicButton()
    }
}
