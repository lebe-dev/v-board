package ru.tinyops.android.vboard.voice

import org.json.JSONArray
import org.json.JSONObject
import ru.tinyops.android.vboard.core.voice.EnhancementEffort
import ru.tinyops.android.vboard.core.voice.ServerKind
import ru.tinyops.android.vboard.core.voice.ServerProfile

/** The stored form of the server list. */
internal object ServerProfileJson {
    fun serialize(profiles: List<ServerProfile>): String {
        val array = JSONArray()
        profiles.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("kind", it.kind.id)
                    .put("endpoint", it.endpoint)
                    .put("model", it.model)
                    .put("api_key", it.apiKey)
                    .put("effort", it.effort.id)
                    .put("proxy_enabled", it.proxyEnabled)
                    .put("proxy_url", it.proxyUrl),
            )
        }
        return array.toString()
    }

    /** The stored servers; nothing, or something unreadable, is an empty list. */
    fun parse(stored: String?): List<ServerProfile> {
        if (stored == null) return emptyList()
        return runCatching {
            val array = JSONArray(stored)
            List(array.length()) {
                val item = array.getJSONObject(it)
                ServerProfile(
                    id = item.getString("id"),
                    name = item.optString("name"),
                    kind = ServerKind.fromId(item.optString("kind")),
                    endpoint = item.optString("endpoint"),
                    model = item.optString("model"),
                    apiKey = item.optString("api_key"),
                    effort = EnhancementEffort.fromId(item.optString("effort")),
                    proxyEnabled = item.optBoolean("proxy_enabled"),
                    proxyUrl = item.optString("proxy_url"),
                )
            }
        }.getOrDefault(emptyList())
    }
}
