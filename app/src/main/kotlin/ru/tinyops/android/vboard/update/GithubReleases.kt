package ru.tinyops.android.vboard.update

import org.json.JSONException
import org.json.JSONObject
import ru.tinyops.android.vboard.BuildConfig
import ru.tinyops.android.vboard.core.update.AppVersion
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Reads the latest stable release of the app from the GitHub API. */
class GithubReleases(
    private val apiUrl: String = LATEST_URL,
) {
    /** The latest stable release, or null when the project has none. Blocks: call off the main thread. */
    @Throws(IOException::class)
    fun latest(): AvailableRelease? {
        val connection = URI(apiUrl).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "v-board/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code != HttpURLConnection.HTTP_OK) throw IOException("GitHub answered HTTP $code")
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return parse(body)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val LATEST_URL = "https://api.github.com/repos/lebe-dev/v-board/releases/latest"
        private const val TIMEOUT_MS = 15_000
        private const val SHA256_PREFIX = "sha256:"

        /** A draft, a pre-release or a tag that is not a version is not offered. */
        @Throws(IOException::class)
        fun parse(body: String): AvailableRelease? =
            try {
                val json = JSONObject(body)
                val version = AppVersion.parse(json.getString("tag_name"))
                val unpublished = json.optBoolean("draft") || json.optBoolean("prerelease")
                if (version == null || version.isPreRelease || unpublished) {
                    null
                } else {
                    AvailableRelease(
                        version = version.toString(),
                        notes = json.optString("body").trim(),
                        pageUrl = json.getString("html_url"),
                        apk = apk(json),
                    )
                }
            } catch (bad: JSONException) {
                throw IOException("GitHub sent an unexpected answer", bad)
            }

        private fun apk(release: JSONObject): ApkAsset? {
            val assets = release.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (!asset.getString("name").endsWith(".apk")) continue
                val digest = asset.optString("digest").takeIf { it.startsWith(SHA256_PREFIX) }
                return ApkAsset(
                    url = asset.getString("browser_download_url"),
                    size = asset.getLong("size"),
                    sha256 = digest?.removePrefix(SHA256_PREFIX)?.lowercase().orEmpty(),
                )
            }
            return null
        }
    }
}
