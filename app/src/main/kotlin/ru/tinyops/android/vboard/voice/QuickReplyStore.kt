package ru.tinyops.android.vboard.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.prefs.ParseCache
import ru.tinyops.android.vboard.core.voice.ButtonCommand
import ru.tinyops.android.vboard.core.voice.QuickReply
import ru.tinyops.android.vboard.core.voice.chosen
import java.util.UUID

/**
 * The quick replies the user keeps, in the order they are listed. The first start fills the list with a few
 * examples in the language of the app; a stored list, an empty or a restored one, is left as it is.
 */
class QuickReplyStore(
    private val context: Context,
    private val prefs: SharedPreferences,
) {
    private val parsed = ParseCache(::parse)

    init {
        addExamples()
    }

    val replies: List<QuickReply> get() = parsed(prefs.getString(KEY, null))

    /** The replies a quick replies [command] offers, in the order of the list. */
    fun choice(command: ButtonCommand): List<QuickReply> = replies.choiceOf(command)

    /** A new id for a reply that is about to be created. */
    fun newId(): String = UUID.randomUUID().toString()

    /** Adds the reply to the end of the list, or replaces the one with the same id keeping its place. */
    fun save(reply: QuickReply) {
        val normalized = reply.normalized()
        val list = replies
        val next =
            if (list.any { it.id == reply.id }) {
                list.map { if (it.id == reply.id) normalized else it }
            } else {
                list + normalized
            }
        write(next)
    }

    fun delete(id: String) = write(replies.filterNot { it.id == id })

    /** Moves the reply [id] by [by] places, as far as the list goes. */
    fun move(
        id: String,
        by: Int,
    ) {
        val list = replies.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + by).coerceIn(0, list.lastIndex)
        list.add(to, list.removeAt(from))
        write(list)
    }

    private fun write(replies: List<QuickReply>) {
        val array = JSONArray()
        replies.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text)) }
        prefs.edit { putString(KEY, array.toString()) }
    }

    private fun addExamples() {
        if (prefs.contains(KEY)) return
        write(EXAMPLES.map { QuickReply(newId(), context.getString(it)) })
    }

    private fun parse(stored: String?): List<QuickReply> {
        stored ?: return emptyList()
        return runCatching {
            val array = JSONArray(stored)
            List(array.length()) {
                val item = array.getJSONObject(it)
                QuickReply(item.getString("id"), item.getString("text"))
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY = "quick_replies"
        val EXAMPLES =
            listOf(
                R.string.quick_reply_example_thanks,
                R.string.quick_reply_example_soon,
                R.string.quick_reply_example_call_back,
                R.string.quick_reply_example_agreed,
            )
    }
}

/** The replies of this list that [command] offers: those it chose that still exist and have a text. */
internal fun List<QuickReply>.choiceOf(command: ButtonCommand): List<QuickReply> =
    chosen(command.replyIds).filter { it.valid }
