package ru.tinyops.android.vboard.core.voice

/**
 * One prompt of the collection: a short [title] to find it by and the [text] that goes to a model.
 * The optional [color] tints the title wherever prompts are listed.
 */
data class SavedPrompt(
    val id: String,
    val title: String,
    val text: String,
    val color: ButtonColor? = null,
) {
    /** A title is given and the text is long enough for a model to take as a prompt. */
    val valid: Boolean get() = title.isNotBlank() && text.trim().length >= SelfHostedServer.MIN_PROMPT_LENGTH

    /** Values as they are stored: trimmed and bounded. */
    fun normalized(): SavedPrompt =
        copy(title = title.trim().take(MAX_TITLE_LENGTH), text = text.trim().take(MAX_TEXT_LENGTH))

    companion object {
        const val MAX_TITLE_LENGTH = 40
        const val MAX_TEXT_LENGTH = 4_000
    }
}

/** The places that take prompts from the collection. */
enum class PromptPlace(
    val id: String,
) {
    /** The hint of the recognition server. */
    RECOGNITION("recognition"),

    /** The instruction of the shared improvement server. */
    ENHANCEMENT("enhancement"),

    /** "Диктовка": its own choice, or the one of the shared server. */
    DICTATION("dictation"),

    /** "Вычитка": its own choice, or the one of the shared server. */
    PROOFREAD("proofread"),

    /** A hold on the proofread button: its own choice; without one the hold does nothing. */
    PROOFREAD_HOLD("proofread_hold"),
    ;

    /** The built-in prompt a place goes back to; a function follows the shared server instead. */
    val fallbackId: String?
        get() =
            when (this) {
                RECOGNITION -> PromptLibrary.RECOGNITION_ID
                ENHANCEMENT -> PromptLibrary.ENHANCEMENT_ID
                DICTATION, PROOFREAD, PROOFREAD_HOLD -> null
            }
}

/**
 * The collection of prompts the user keeps. A place uses the prompts it selects by id; several of them
 * are joined into one text in the order of the collection, so the result does not depend on the order of
 * ticking.
 */
data class PromptLibrary(
    val prompts: List<SavedPrompt> = emptyList(),
) {
    fun find(id: String): SavedPrompt? = prompts.firstOrNull { it.id == id }

    /** The prompts a button may offer to choose from: those with a title and a text a model takes. */
    val pickable: List<SavedPrompt> get() = prompts.filter { it.valid }

    /** The texts of the selected prompts that still exist, one after another. */
    fun compose(ids: Collection<String>): String =
        prompts
            .filter { it.id in ids }
            .map { it.text.trim() }
            .filter(String::isNotEmpty)
            .joinToString(SEPARATOR)

    /** The titles of the selected prompts that still exist. */
    fun titles(ids: Collection<String>): List<String> = prompts.filter { it.id in ids }.map { it.title }

    /** The selected prompts that still exist, as a kept result remembers them. */
    fun tags(ids: Collection<String>): List<PromptTag> =
        prompts.filter { it.id in ids }.map { PromptTag(it.id, it.title) }

    /** Adds the prompt, or replaces the one with the same id keeping its position. */
    fun with(prompt: SavedPrompt): PromptLibrary {
        val stored = prompt.normalized()
        return if (find(stored.id) == null) {
            copy(prompts = prompts + stored)
        } else {
            copy(prompts = prompts.map { if (it.id == stored.id) stored else it })
        }
    }

    fun without(id: String): PromptLibrary = copy(prompts = prompts.filterNot { it.id == id })

    /** Adds [prompt] only when the collection has none with its id. */
    fun withDefault(prompt: SavedPrompt): PromptLibrary = if (find(prompt.id) == null) with(prompt) else this

    companion object {
        const val RECOGNITION_ID = "default-recognition"
        const val ENHANCEMENT_ID = "default-enhancement"
        private const val SEPARATOR = "\n\n"
    }
}

/**
 * What a deletion changes: the places that used the prompt and where they go next. A place left without
 * a prompt goes back to its built-in one, and a function with no choice of its own follows the shared
 * server again.
 */
data class PromptRemoval(
    val library: PromptLibrary,
    val selections: Map<PromptPlace, List<String>?>,
) {
    companion object {
        /**
         * Removes [id] from the [library] and from every place in [selections]. A built-in prompt that was
         * deleted on purpose is not brought back where it was the one chosen.
         */
        fun of(
            library: PromptLibrary,
            selections: Map<PromptPlace, List<String>?>,
            id: String,
            builtIn: (String) -> SavedPrompt?,
        ): PromptRemoval {
            var next = library.without(id)
            val updated =
                selections.mapValues { (place, ids) ->
                    if (ids == null || id !in ids) return@mapValues ids
                    val rest = ids - id
                    if (rest.isNotEmpty()) return@mapValues rest
                    val fallback = place.fallbackId
                    if (fallback == null) return@mapValues null
                    if (fallback == id) return@mapValues emptyList()
                    builtIn(fallback)?.let { next = next.withDefault(it) }
                    listOf(fallback)
                }
            return PromptRemoval(next, updated)
        }
    }
}

/** The places that use the prompt with the given [id]. */
fun Map<PromptPlace, List<String>?>.placesUsing(id: String): List<PromptPlace> =
    PromptPlace.entries.filter { this[it]?.contains(id) == true }

/** The place where a text function takes its own prompts from. */
val TextFeature.promptPlace: PromptPlace
    get() = if (this == TextFeature.DICTATION) PromptPlace.DICTATION else PromptPlace.PROOFREAD
