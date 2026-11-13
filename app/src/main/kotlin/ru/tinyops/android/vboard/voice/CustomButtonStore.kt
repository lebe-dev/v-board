package ru.tinyops.android.vboard.voice

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import ru.tinyops.android.vboard.core.prefs.ParseCache
import ru.tinyops.android.vboard.core.voice.ButtonAction
import ru.tinyops.android.vboard.core.voice.ButtonColor
import ru.tinyops.android.vboard.core.voice.ButtonCommand
import ru.tinyops.android.vboard.core.voice.ButtonPlacement
import ru.tinyops.android.vboard.core.voice.ButtonVisibility
import ru.tinyops.android.vboard.core.voice.CustomButton
import ru.tinyops.android.vboard.core.voice.InsertMode
import ru.tinyops.android.vboard.core.voice.withButton
import ru.tinyops.android.vboard.core.voice.withoutButton
import ru.tinyops.android.vboard.diagnostics.AppLog
import ru.tinyops.android.vboard.diagnostics.ErrorReporting
import java.util.UUID

/** The custom buttons of the strip, in the order they are shown. */
class CustomButtonStore(
    private val prefs: SharedPreferences,
) {
    private val parsed = ParseCache(::parse)

    var buttons: List<CustomButton>
        get() = parsed(prefs.getString(KEY, null))
        private set(value) = prefs.edit { putString(KEY, serialize(value)) }

    /** A new button, stored at once; it is on its row even before it is given something to do. */
    fun add(
        visibility: ButtonVisibility = ButtonVisibility.WITH_TEXT,
        groupId: String? = null,
    ): CustomButton =
        CustomButton(UUID.randomUUID().toString(), visibility = visibility, groupId = groupId)
            .also { buttons = buttons + it }

    /** A new group, stored at once like a button. */
    fun addGroup(visibility: ButtonVisibility = ButtonVisibility.WITH_TEXT): CustomButton =
        CustomButton(UUID.randomUUID().toString(), visibility = visibility, group = true)
            .also { buttons = buttons + it }

    fun save(button: CustomButton) {
        buttons = buttons.withButton(button)
    }

    /** Stores the buttons in a new order and in new places. */
    fun arrange(arranged: List<CustomButton>) {
        buttons = arranged
    }

    fun delete(id: String) {
        buttons = buttons.withoutButton(id)
    }

    private companion object {
        const val TAG = "CustomButtonStore"
        const val KEY = "custom_buttons"

        fun serialize(buttons: List<CustomButton>): String {
            val array = JSONArray()
            buttons.forEach {
                array.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("tap", it.tap?.let(::serializeCommand) ?: JSONObject.NULL)
                        .put("hold", it.hold?.let(::serializeCommand) ?: JSONObject.NULL)
                        .put("icon", it.icon ?: JSONObject.NULL)
                        .put("color", it.color?.id ?: JSONObject.NULL)
                        .put("placement", it.placement.id)
                        .put("visibility", it.visibility.id)
                        .put("name", it.name)
                        .put("enabled", it.enabled)
                        .put("group", it.group)
                        .put("groupId", it.groupId ?: JSONObject.NULL)
                        .put("secondRow", it.secondRow),
                )
            }
            return array.toString()
        }

        fun serializeCommand(command: ButtonCommand): JSONObject =
            JSONObject()
                .put("action", command.action.id)
                .put("server", command.serverId ?: JSONObject.NULL)
                .put("prompts", JSONArray(command.promptIds))
                .put("insert", command.insert.id)
                .put("pick", command.pickPrompt)
                .put("replies", JSONArray(command.replyIds))

        fun parse(stored: String?): List<CustomButton> {
            stored ?: return emptyList()
            return try {
                val array = JSONArray(stored)
                List(array.length()) { parseButton(array.getJSONObject(it)) }
            } catch (failure: JSONException) {
                AppLog.error(TAG, "Stored custom buttons are unreadable", failure)
                ErrorReporting.capture(failure)
                emptyList()
            }
        }

        fun parseButton(item: JSONObject) =
            CustomButton(
                id = item.getString("id"),
                tap = item.optJSONObject("tap")?.let(::parseCommand),
                hold = item.optJSONObject("hold")?.let(::parseCommand),
                icon = item.optString("icon").takeUnless { item.isNull("icon") || it.isEmpty() },
                color = ButtonColor.fromId(item.optString("color")),
                placement = ButtonPlacement.fromId(item.optString("placement")),
                visibility = ButtonVisibility.fromId(item.optString("visibility")),
                name = item.optString("name"),
                enabled = item.optBoolean("enabled", true),
                group = item.optBoolean("group", false),
                groupId = item.optString("groupId").takeUnless { item.isNull("groupId") || it.isEmpty() },
                secondRow = item.optBoolean("secondRow", false),
            )

        /** A command of an action this version does not know counts as no command. */
        fun parseCommand(item: JSONObject): ButtonCommand? {
            val action = ButtonAction.fromId(item.optString("action")) ?: return null
            val prompts = item.optJSONArray("prompts") ?: JSONArray()
            val replies = item.optJSONArray("replies") ?: JSONArray()
            return ButtonCommand(
                action = action,
                serverId = item.optString("server").takeUnless { item.isNull("server") || it.isEmpty() },
                promptIds = List(prompts.length()) { prompts.getString(it) },
                insert = InsertMode.fromId(item.optString("insert")),
                pickPrompt = item.optBoolean("pick", false),
                replyIds = List(replies.length()) { replies.getString(it) },
            )
        }
    }
}
