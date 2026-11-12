package ru.tinyops.android.vboard.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.prefs.ParseCache
import ru.tinyops.android.vboard.core.voice.ButtonColor
import ru.tinyops.android.vboard.core.voice.PromptLibrary
import ru.tinyops.android.vboard.core.voice.PromptPlace
import ru.tinyops.android.vboard.core.voice.PromptRemoval
import ru.tinyops.android.vboard.core.voice.RemoteRecognitionServer
import ru.tinyops.android.vboard.core.voice.SavedPrompt
import ru.tinyops.android.vboard.core.voice.SelfHostedServer
import ru.tinyops.android.vboard.core.voice.placesUsing
import java.util.UUID

/**
 * The collection of prompts and what every place that sends a prompt selected from it, kept in the
 * preferences of the voice settings. A place stores the ids of its prompts; the text it sends is
 * composed when asked for, so editing a prompt reaches every place that uses it.
 */
class PromptStore(
    private val context: Context,
    private val prefs: SharedPreferences,
) {
    // Declared before the first start fills the collection, which already reads it.
    private val parsedLibrary = ParseCache(::parseLibrary)

    init {
        addBuiltIns()
    }

    /** The prompts the user keeps. */
    val library: PromptLibrary get() = parsedLibrary(prefs.getString(KEY_PROMPTS, null))

    /** The ids a place selected; null means a function follows the shared server. */
    fun ids(place: PromptPlace): List<String>? =
        prefs.getString(idsKey(place), null)?.split(ID_SEPARATOR)?.filter(String::isNotEmpty)

    fun setIds(
        place: PromptPlace,
        ids: List<String>?,
    ) = prefs.edit { putIds(place, ids) }

    /** The text a place sends: its selected prompts joined into one. */
    fun text(place: PromptPlace): String = library.compose(ids(place).orEmpty())

    /** A new id for a prompt that is about to be created. */
    fun newId(): String = UUID.randomUUID().toString()

    /** Adds the prompt, or replaces the one with the same id. */
    fun save(prompt: SavedPrompt) = prefs.edit { writeLibrary(library.with(prompt)) }

    /** The places that use the prompt, shown before it is deleted. */
    fun usage(id: String): List<PromptPlace> = selections().placesUsing(id)

    /** Deletes the prompt; a place left without one goes back to its built-in prompt or to the shared server. */
    fun delete(id: String) {
        val removal = PromptRemoval.of(library, selections(), id, ::builtIn)
        prefs.edit {
            writeLibrary(removal.library)
            removal.selections.forEach { (place, ids) -> putIds(place, ids) }
        }
    }

    /** A place goes back to its built-in prompt, which comes back into the collection if it was deleted. */
    fun reset(place: PromptPlace) {
        val id = place.fallbackId ?: return
        prefs.edit {
            builtIn(id)?.let { writeLibrary(library.withDefault(it)) }
            putIds(place, listOf(id))
        }
    }

    private fun selections(): Map<PromptPlace, List<String>?> = PromptPlace.entries.associateWith(::ids)

    private fun builtIn(id: String): SavedPrompt? =
        when (id) {
            PromptLibrary.RECOGNITION_ID ->
                SavedPrompt(
                    id,
                    context.getString(R.string.prompt_default_recognition),
                    RemoteRecognitionServer.DEFAULT_PROMPT,
                )
            PromptLibrary.ENHANCEMENT_ID ->
                SavedPrompt(id, context.getString(R.string.prompt_default_enhancement), SelfHostedServer.DEFAULT_PROMPT)
            else -> null
        }

    private fun parseLibrary(stored: String?): PromptLibrary {
        stored ?: return PromptLibrary()
        return runCatching {
            val array = JSONArray(stored)
            PromptLibrary(
                List(array.length()) {
                    val item = array.getJSONObject(it)
                    SavedPrompt(
                        item.getString("id"),
                        item.getString("title"),
                        item.getString("text"),
                        ButtonColor.fromId(item.optString("color")),
                    )
                },
            )
        }.getOrDefault(PromptLibrary())
    }

    private fun SharedPreferences.Editor.writeLibrary(library: PromptLibrary) {
        val array = JSONArray()
        library.prompts.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("text", it.text)
                    .put("color", it.color?.id ?: JSONObject.NULL),
            )
        }
        putString(KEY_PROMPTS, array.toString())
    }

    private fun SharedPreferences.Editor.putIds(
        place: PromptPlace,
        ids: List<String>?,
    ) {
        if (ids == null) remove(idsKey(place)) else putString(idsKey(place), ids.joinToString(ID_SEPARATOR))
    }

    private fun idsKey(place: PromptPlace) = "prompt_ids_${place.id}"

    /**
     * The first start puts the built-in prompts into the collection and selects them where a place has one.
     * A collection that is already stored, including a restored one, is left as it is.
     */
    private fun addBuiltIns() {
        if (prefs.contains(KEY_PROMPTS)) return
        val library =
            PromptLibrary()
                .withDefault(requireNotNull(builtIn(PromptLibrary.RECOGNITION_ID)))
                .withDefault(requireNotNull(builtIn(PromptLibrary.ENHANCEMENT_ID)))
        prefs.edit {
            writeLibrary(library)
            PromptPlace.entries.forEach { place -> putIds(place, place.fallbackId?.let(::listOf)) }
        }
    }

    private companion object {
        const val KEY_PROMPTS = "prompts"
        const val ID_SEPARATOR = ","
    }
}
