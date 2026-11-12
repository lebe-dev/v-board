package ru.tinyops.android.vboard.voice

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Blocking `HttpURLConnection` calls to the user's servers, cancellable from a coroutine. */
internal object ServerHttp {
    const val HTTP_OK = 200
    const val HTTP_MULTIPLE_CHOICES = 300

    /**
     * Far more than any answer for the longest text the keyboard sends; a server or a proxy that sends more is
     * broken, and its answer would have to fit into the memory of the keyboard's process.
     */
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

    private val executor =
        Executors.newCachedThreadPool { task -> Thread(task, "server-http").apply { isDaemon = true } }

    /**
     * Opens [url] through [proxy] (`http://` or `socks5://host:port`, null for a direct connection)
     * and runs [exchange] on a worker thread; cancelling the coroutine drops the connection.
     */
    suspend fun <T> call(
        url: String,
        proxy: String?,
        exchange: (HttpURLConnection) -> T,
    ): T =
        suspendCancellableCoroutine { continuation ->
            val connection = URI(url).toURL().openConnection(proxy(proxy)) as HttpURLConnection
            continuation.invokeOnCancellation { connection.disconnect() }
            executor.execute {
                val result =
                    runCatching {
                        try {
                            exchange(connection)
                        } finally {
                            connection.disconnect()
                        }
                    }
                if (continuation.isActive) continuation.resume(result)
            }
        }.getOrThrow()

    /** The response body of a 2xx answer; anything else is a [ServerHttpException]. */
    fun HttpURLConnection.successBody(): String {
        val status = responseCode
        if (status !in HTTP_OK until HTTP_MULTIPLE_CHOICES) throw ServerHttpException(status)
        return inputStream.use { readBody(it, MAX_RESPONSE_BYTES) }
    }

    /** [stream] as UTF-8 text; an [IOException] when it holds more than [limit] bytes. */
    fun readBody(
        stream: InputStream,
        limit: Int,
    ): String {
        val bytes = stream.readNBytes(limit + 1)
        if (bytes.size > limit) throw IOException("The response is larger than $limit bytes")
        return String(bytes, Charsets.UTF_8)
    }

    fun HttpURLConnection.authorize(apiKey: String) {
        apiKey.trim().takeIf(String::isNotEmpty)?.let { setRequestProperty("Authorization", "Bearer $it") }
    }

    private fun proxy(address: String?): Proxy {
        address ?: return Proxy.NO_PROXY
        val uri = URI(address)
        val type = if (uri.scheme == "socks5") Proxy.Type.SOCKS else Proxy.Type.HTTP
        return Proxy(type, InetSocketAddress.createUnresolved(uri.host, uri.port))
    }
}

/** A non-2xx answer of the user's server. */
class ServerHttpException(
    val status: Int,
) : IOException("HTTP $status")
