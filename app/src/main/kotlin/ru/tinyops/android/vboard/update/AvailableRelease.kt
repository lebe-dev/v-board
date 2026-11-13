package ru.tinyops.android.vboard.update

import org.json.JSONException
import org.json.JSONObject
import ru.tinyops.android.vboard.core.update.AppVersion

/** The APK attached to a release; [sha256] is empty when GitHub does not publish its digest. */
data class ApkAsset(
    val url: String,
    val size: Long,
    val sha256: String,
)

/** A published release of the app: its version, its notes and the page it is on. */
data class AvailableRelease(
    val version: String,
    val notes: String,
    val pageUrl: String,
    val apk: ApkAsset?,
) {
    /** Whether this release is newer than the version [current] of the running app. */
    fun isNewerThan(current: String): Boolean {
        val mine = AppVersion.parse(current) ?: return false
        val theirs = AppVersion.parse(version) ?: return false
        return theirs > mine
    }

    fun encode(): String =
        JSONObject()
            .put("version", version)
            .put("notes", notes)
            .put("page", pageUrl)
            .apply {
                if (apk != null) {
                    put("apk", JSONObject().put("url", apk.url).put("size", apk.size).put("sha256", apk.sha256))
                }
            }.toString()

    companion object {
        fun decode(text: String): AvailableRelease? =
            try {
                val json = JSONObject(text)
                val apk =
                    json.optJSONObject("apk")?.let {
                        ApkAsset(it.getString("url"), it.getLong("size"), it.optString("sha256"))
                    }
                AvailableRelease(json.getString("version"), json.optString("notes"), json.getString("page"), apk)
            } catch (_: JSONException) {
                null
            }
    }
}
