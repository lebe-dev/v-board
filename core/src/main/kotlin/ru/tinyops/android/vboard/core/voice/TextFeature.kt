package ru.tinyops.android.vboard.core.voice

/** A function that sends text to an improvement server; each has its own settings. */
enum class TextFeature(
    val id: String,
) {
    DICTATION("dictation"),
    PROOFREAD("proofread"),
}

/** What a [TextFeature] sends its requests with: the chosen server, the prompt and the level of effort. */
data class FeatureConnection(
    val server: SelfHostedServer,
    val effort: EnhancementEffort,
)
