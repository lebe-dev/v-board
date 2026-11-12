package ru.tinyops.android.vboard.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * All the settings in one JSON file: every preference file of the app, typed value by value, and
 * the language of the app. An import replaces the settings of each file it carries. The API keys of the
 * servers go into the file only when the user agrees; a file without them keeps the keys already here.
 */
internal object SettingsBackup {
    private const val FORMAT = "vboard-settings"
    private const val VERSION = 1

    /** The preference files that hold settings, with the keys that describe this phone rather than a choice. */
    val FILES: Map<String, Set<String>> =
        mapOf(
            "keyboard_prefs" to setOf("neural_model_loading", "log_errors"),
            "keyboard" to emptySet(),
            "voice" to emptySet(),
            "emoji_prefs" to setOf("recents"),
            "clipboard_prefs" to emptySet(),
        )

    private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    /** A settings file is a few kilobytes; anything far larger is not one. */
    const val MAX_BYTES = 1 shl 20

    fun fileName(time: LocalDateTime): String = "vboard-settings-${time.format(FILE_TIME)}.json"

    private const val VOICE = "voice"
    private const val SERVERS = "servers"
    private const val API_KEY = "api_key"

    /**
     * The current settings; [language] is the app language tag, empty when it follows the system, and
     * without [apiKeys] every server is written with an empty key.
     */
    fun read(
        context: Context,
        language: String,
        apiKeys: Boolean,
    ): Backup {
        val preferences =
            FILES
                .mapValues { (file, local) ->
                    context
                        .getSharedPreferences(file, Context.MODE_PRIVATE)
                        .all
                        .filterKeys { it !in local }
                        .filterValues { it != null }
                        .mapValues { it.value!! }
                }.mapValues { (file, values) -> if (file == VOICE && !apiKeys) withoutKeys(values) else values }
        return Backup(preferences, language, apiKeys, appVersion = null, exportedAt = null)
    }

    /** Replaces the settings of every known file the backup carries; the app language is applied by the caller. */
    fun write(
        context: Context,
        backup: Backup,
    ) {
        backup.preferences.forEach { (file, values) ->
            val local = FILES[file] ?: return@forEach
            val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
            val incoming =
                if (file == VOICE &&
                    !backup.apiKeysIncluded
                ) {
                    withKeysOf(prefs.getString(SERVERS, null), values)
                } else {
                    values
                }
            prefs.edit(commit = true) {
                prefs.all.keys
                    .filter { it !in local }
                    .forEach(::remove)
                incoming.filterKeys { it !in local }.forEach { (key, value) -> put(key, value) }
            }
        }
    }

    fun encode(
        backup: Backup,
        appVersion: String,
        exportedAt: OffsetDateTime,
    ): String {
        val preferences = JSONObject()
        backup.preferences.toSortedMap().forEach { (file, values) ->
            val entries = JSONObject()
            values.toSortedMap().forEach { (key, value) -> entries.put(key, encodeValue(value)) }
            preferences.put(file, entries)
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("app_version", appVersion)
            .put("exported_at", exportedAt.truncatedTo(ChronoUnit.SECONDS).toString())
            .put("app_language", backup.language)
            .put("api_keys", backup.apiKeysIncluded)
            .put("preferences", preferences)
            .toString(2)
    }

    /** Reads a settings file; throws [InvalidBackup] for anything that is not one this version understands. */
    fun decode(text: String): Backup =
        try {
            decode(JSONObject(text))
        } catch (e: JSONException) {
            throw InvalidBackup("malformed JSON", e)
        } catch (e: DateTimeParseException) {
            throw InvalidBackup("malformed date", e)
        }

    private fun decode(json: JSONObject): Backup {
        valid(json.optString("format") == FORMAT) { "not a settings file" }
        val version = json.optInt("version", -1)
        valid(version in 1..VERSION) { "unsupported version $version" }
        val preferences = json.getJSONObject("preferences")
        val files =
            preferences.keys().asSequence().associateWith { file ->
                val entries = preferences.getJSONObject(file)
                entries.keys().asSequence().associateWith { decodeValue(entries.getJSONObject(it)) }
            }
        return Backup(
            preferences = files,
            language = if (json.has("app_language")) json.getString("app_language") else null,
            // The first files had no such field and always carried the keys.
            apiKeysIncluded = json.optBoolean("api_keys", true),
            appVersion = json.optString("app_version").ifEmpty { null },
            exportedAt = json.optString("exported_at").ifEmpty { null }?.let(OffsetDateTime::parse),
        )
    }

    private inline fun valid(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) throw InvalidBackup(message())
    }

    /** Whether any server has an API key, so that the export has something to ask about. */
    fun hasApiKeys(context: Context): Boolean {
        val servers = context.getSharedPreferences(VOICE, Context.MODE_PRIVATE).getString(SERVERS, null)
        return servers
            ?.let(::serverKeys)
            .orEmpty()
            .values
            .any(String::isNotBlank)
    }

    /** The voice settings with every server's key emptied and the keys of the old single servers left out. */
    private fun withoutKeys(values: Map<String, Any>): Map<String, Any> =
        values
            .filterKeys { !it.endsWith(API_KEY) }
            .mapValues { (key, value) -> if (key == SERVERS && value is String) mapServers(value) { "" } else value }

    /** The imported voice settings, where a server without a key takes the key of the stored server with its id. */
    private fun withKeysOf(
        stored: String?,
        values: Map<String, Any>,
    ): Map<String, Any> {
        val keys = stored?.let(::serverKeys).orEmpty()
        return values.mapValues { (key, value) ->
            if (key != SERVERS || value !is String) return@mapValues value
            mapServers(value) { server ->
                server.optString(API_KEY).ifEmpty { keys[server.optString("id")].orEmpty() }
            }
        }
    }

    private fun serverKeys(servers: String): Map<String, String> =
        runCatching {
            val array = JSONArray(servers)
            (0 until array.length())
                .map(array::getJSONObject)
                .associate { it.optString("id") to it.optString(API_KEY) }
        }.getOrDefault(emptyMap())

    /** The server list with each key replaced; text that is not a list stays as it is. */
    private fun mapServers(
        servers: String,
        key: (JSONObject) -> String,
    ): String =
        runCatching {
            val array = JSONArray(servers)
            for (i in 0 until array.length()) {
                val server = array.getJSONObject(i)
                server.put(API_KEY, key(server))
            }
            array.toString()
        }.getOrDefault(servers)

    private fun encodeValue(value: Any): JSONObject =
        when (value) {
            is Boolean -> typed("boolean", value)
            is Int -> typed("int", value)
            is Long -> typed("long", value)
            is Float -> typed("float", value.toDouble())
            is String -> typed("string", value)
            is Set<*> -> typed("string_set", JSONArray(value.map { it.toString() }.sorted()))
            else -> throw IllegalArgumentException("Unsupported preference type ${value::class.java.name}")
        }

    private fun typed(
        type: String,
        value: Any,
    ) = JSONObject().put("type", type).put("value", value)

    private fun decodeValue(entry: JSONObject): Any {
        val type = entry.getString("type")
        val value = entry.get("value")
        val whole = (value as? Number)?.takeIf { it is Int || it is Long }?.toLong()
        val decoded =
            when (type) {
                "boolean" -> value as? Boolean
                "int" -> whole?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                "long" -> whole
                "float" -> (value as? Number)?.toFloat()
                "string" -> value as? String
                "string_set" ->
                    (value as? JSONArray)?.let { array ->
                        (0 until array.length()).map(array::getString).toSet()
                    }
                else -> null
            }
        valid(decoded != null) { "value does not match type $type" }
        return decoded!!
    }

    private fun SharedPreferences.Editor.put(
        key: String,
        value: Any,
    ) {
        @Suppress("UNCHECKED_CAST")
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
            is Set<*> -> putStringSet(key, value as Set<String>)
        }
    }

    /** The settings of a file or of this phone; [appVersion] and [exportedAt] come only from a file. */
    class Backup(
        val preferences: Map<String, Map<String, Any>>,
        val language: String?,
        val apiKeysIncluded: Boolean,
        val appVersion: String?,
        val exportedAt: OffsetDateTime?,
    )

    class InvalidBackup(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)
}
