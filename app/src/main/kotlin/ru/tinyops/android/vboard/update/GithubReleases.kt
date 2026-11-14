package ru.tinyops.android.vboard.update

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import ru.tinyops.android.vboard.BuildConfig
import ru.tinyops.android.vboard.core.update.AppVersion
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Reads the newest release of the app from the GitHub API. */
class GithubReleases(
    private val apiUrl: String = RELEASES_URL,
) {
    /**
     * The newest stable release, or the newest of all with [beta] releases; null when there is none. Blocks: call
     * off the main thread.
     */
    @Throws(IOException::class)
    fun latest(beta: Boolean = false): AvailableRelease? {
        if (beta) return get("$apiUrl?per_page=$PAGE_SIZE")?.let { newest(it, beta = true) }
        return get("$apiUrl/latest")?.let(::parse)?.takeUnless { it.beta }
    }

    /** The body of [url], or null when GitHub has nothing there. */
    @Throws(IOException::class)
    private fun get(url: String): String? {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "v-board/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code != HttpURLConnection.HTTP_OK) throw IOException("GitHub answered HTTP $code")
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/lebe-dev/v-board/releases"
        private const val PAGE_SIZE = 20
        private const val TIMEOUT_MS = 15_000
        private const val SHA256_PREFIX = "sha256:"

        /** One release; a draft or a tag that is not a version is none. A pre-release is a beta release. */
        @Throws(IOException::class)
        fun parse(body: String): AvailableRelease? =
            try {
                release(JSONObject(body))
            } catch (bad: JSONException) {
                throw IOException("GitHub sent an unexpected answer", bad)
            }

        /** The newest version of a list of releases; beta releases only when [beta]. */
        @Throws(IOException::class)
        fun newest(
            body: String,
            beta: Boolean,
        ): AvailableRelease? =
            try {
                val list = JSONArray(body)
                List(list.length()) { release(list.getJSONObject(it)) }
                    .filterNotNull()
                    .filter { beta || !it.beta }
                    .maxByOrNull { requireNotNull(AppVersion.parse(it.version)) }
            } catch (bad: JSONException) {
                throw IOException("GitHub sent an unexpected answer", bad)
            }

        private fun release(json: JSONObject): AvailableRelease? {
            val version = AppVersion.parse(json.getString("tag_name")) ?: return null
            if (json.optBoolean("draft")) return null
            return AvailableRelease(
                version = version.toString(),
                notes = json.optString("body").trim(),
                pageUrl = json.getString("html_url"),
                apk = apk(json),
                beta = version.isPreRelease || json.optBoolean("prerelease"),
            )
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
