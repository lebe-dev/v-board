package ru.tinyops.android.vboard.voice

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import ru.tinyops.android.vboard.core.prefs.ParseCache
import ru.tinyops.android.vboard.core.voice.MicButton
import ru.tinyops.android.vboard.core.voice.MicEnding
import ru.tinyops.android.vboard.core.voice.MicMode
import ru.tinyops.android.vboard.core.voice.MicProcessing
import ru.tinyops.android.vboard.diagnostics.AppLog
import ru.tinyops.android.vboard.diagnostics.ErrorReporting
import java.util.UUID

/** What a tap and a hold on the microphone do. */
class MicButtonStore(
    private val prefs: SharedPreferences,
) {
    private val parsed = ParseCache(::parse)

    var button: MicButton
        get() = parsed(prefs.getString(KEY, null))
        set(value) = prefs.edit { putString(KEY, serialize(value)) }

    /** An id for a hold mode not stored yet. */
    fun newHoldId(): String = UUID.randomUUID().toString()

    companion object {
        private const val TAG = "MicButtonStore"
        const val KEY = "mic_button"

        private fun serialize(button: MicButton): String =
            JSONObject()
                .put("tap", serializeMode(button.tap))
                .put("hold", JSONArray(button.hold.map(::serializeMode)))
                .toString()

        private fun serializeMode(mode: MicMode): JSONObject =
            JSONObject()
                .put("id", mode.id)
                .put("processing", mode.processing.id)
                .put("prompts", JSONArray(mode.promptIds))
                .put("ending", mode.ending.id)

        private fun parse(stored: String?): MicButton {
            stored ?: return MicButton.DEFAULT
            return try {
                val item = JSONObject(stored)
                val hold = item.optJSONArray("hold") ?: JSONArray()
                MicButton(
                    tap = item.optJSONObject("tap")?.let(::parseMode) ?: MicButton.DEFAULT.tap,
                    hold = List(hold.length()) { hold.optJSONObject(it)?.let(::parseMode) }.filterNotNull(),
                ).distinct()
            } catch (failure: JSONException) {
                AppLog.error(TAG, "Stored microphone button settings are unreadable", failure)
                ErrorReporting.capture(failure)
                MicButton.DEFAULT
            }
        }

        /** A mode without an id counts as no mode. */
        private fun parseMode(item: JSONObject): MicMode? {
            val id = item.optString("id").takeIf(String::isNotEmpty) ?: return null
            val prompts = item.optJSONArray("prompts") ?: JSONArray()
            return MicMode(
                id = id,
                processing = MicProcessing.fromId(item.optString("processing")),
                promptIds = List(prompts.length()) { prompts.getString(it) },
                ending = MicEnding.fromId(item.optString("ending")),
            )
        }
    }
}
