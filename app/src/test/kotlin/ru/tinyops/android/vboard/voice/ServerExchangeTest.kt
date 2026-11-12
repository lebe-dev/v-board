package ru.tinyops.android.vboard.voice

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.core.voice.EnhancementOutcome
import ru.tinyops.android.vboard.core.voice.RemoteRecognitionServer
import ru.tinyops.android.vboard.core.voice.SelfHostedServer
import ru.tinyops.android.vboard.core.voice.ServerCheck
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The requests the keyboard sends to the user's servers and how it reads their answers, over real HTTP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ServerExchangeTest {
    private class Received(
        val method: String,
        val path: String,
        val contentType: String?,
        val authorization: String?,
        val body: ByteArray,
    )

    private val received = ArrayList<Received>()
    private var status = 200
    private var answer = ""
    private val release = CountDownLatch(1)
    private var hang = false

    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handle(exchange) }
            start()
        }

    private val endpoint = "http://127.0.0.1:${server.address.port}/v1"

    @After
    fun tearDown() {
        release.countDown()
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes()
        synchronized(received) {
            received +=
                Received(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("Content-Type"),
                    exchange.requestHeaders.getFirst("Authorization"),
                    body,
                )
        }
        if (hang) release.await(10, TimeUnit.SECONDS)
        val bytes = answer.toByteArray()
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun chatAnswer(content: String) =
        JSONObject()
            .put(
                "choices",
                org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", content))),
            ).toString()

    private fun selfHosted(apiKey: String = "secret") =
        SelfHostedServer(endpoint = endpoint, model = "text-model", apiKey = apiKey, prompt = "Fix the text")

    private fun recognition(
        promptEnabled: Boolean = false,
        prompt: String = RemoteRecognitionServer.DEFAULT_PROMPT,
    ) = RemoteRecognitionServer(
        endpoint = endpoint,
        model = "whisper",
        apiKey = "key",
        promptEnabled = promptEnabled,
        prompt = prompt,
    )

    /** A port nothing listens on. */
    private fun closedEndpoint(): String {
        val port = ServerSocket(0).use { it.localPort }
        return "http://127.0.0.1:$port/v1"
    }

    @Test
    fun `the improved text comes from a chat completion with the prompt and the key`() =
        runBlocking {
            answer = chatAnswer("  Привет, мир.  ")

            val result = TextEnhancer().enhance(selfHosted(), "привет мир")

            assertEquals(TextEnhancer.Result("Привет, мир.", EnhancementOutcome.SUCCESS), result)
            val request = received.single()
            assertEquals("POST", request.method)
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer secret", request.authorization)
            val json = JSONObject(String(request.body))
            assertEquals("text-model", json.getString("model"))
            val messages = json.getJSONArray("messages")
            assertEquals("Fix the text", messages.getJSONObject(0).getString("content"))
            assertEquals("привет мир", messages.getJSONObject(1).getString("content"))
        }

    @Test
    fun `no key means no authorization header`() =
        runBlocking {
            answer = chatAnswer("Text")

            TextEnhancer().enhance(selfHosted(apiKey = "  "), "text")

            assertNull(received.single().authorization)
        }

    @Test
    fun `a rejected key keeps the dictated text and says the key is wrong`() =
        runBlocking {
            status = 401
            answer = """{"error":"bad key"}"""

            val result = TextEnhancer().enhance(selfHosted(), "как есть")

            assertEquals(TextEnhancer.Result("как есть", EnhancementOutcome.AUTH_FAILED), result)
        }

    @Test
    fun `a server error keeps the dictated text`() =
        runBlocking {
            status = 500

            assertEquals(
                TextEnhancer.Result("как есть", EnhancementOutcome.FAILED),
                TextEnhancer().enhance(selfHosted(), "как есть"),
            )
        }

    @Test
    fun `an answer without a completion keeps the dictated text`() =
        runBlocking {
            answer = """{"choices":[]}"""

            assertEquals(
                TextEnhancer.Result("как есть", EnhancementOutcome.FAILED),
                TextEnhancer().enhance(selfHosted(), "как есть"),
            )
        }

    @Test
    fun `an unreachable server keeps the dictated text`() =
        runBlocking {
            val server = selfHosted().copy(endpoint = closedEndpoint())

            assertEquals(
                TextEnhancer.Result("как есть", EnhancementOutcome.FAILED),
                TextEnhancer().enhance(server, "как есть"),
            )
        }

    @Test
    fun `blank text is not sent`() =
        runBlocking {
            assertEquals(EnhancementOutcome.UNCHANGED, TextEnhancer().enhance(selfHosted(), "  ").outcome)
            assertTrue(received.isEmpty())
        }

    @Test
    fun `a cancelled request returns at once while the server still thinks`() =
        runBlocking {
            hang = true
            answer = chatAnswer("late")
            val started = System.nanoTime()

            val result = withTimeoutOrNull(300) { TextEnhancer().enhance(selfHosted(), "text") }

            assertNull(result)
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
        }

    @Test
    fun `a phrase goes as a wav file to the transcriptions api`() =
        runBlocking {
            answer = """{"text":"  Привет.  "}"""

            val text = RemoteTranscriber(recognition()).transcribe(ByteArray(3_200))

            assertEquals("Привет.", text)
            val request = received.single()
            assertEquals("/v1/audio/transcriptions", request.path)
            assertEquals("Bearer key", request.authorization)
            assertTrue(request.contentType.orEmpty().startsWith("multipart/form-data"))
            val body = String(request.body, Charsets.ISO_8859_1)
            assertTrue("the model goes with the audio", "whisper" in body)
            assertTrue("the audio is a WAV file", "RIFF" in body && "WAVE" in body)
        }

    @Test
    fun `the prompt read back by the model is not inserted`() =
        runBlocking {
            answer = """{"text":"Только термины."}"""
            val server = recognition(promptEnabled = true, prompt = "Только термины.")

            assertEquals("", RemoteTranscriber(server).transcribe(ByteArray(3_200)))
        }

    @Test
    fun `a failed phrase throws so the recorder can report it`() =
        runBlocking {
            status = 503
            try {
                RemoteTranscriber(recognition()).transcribe(ByteArray(3_200))
                fail("a 503 must not pass as an empty phrase")
            } catch (expected: ServerHttpException) {
                assertEquals(503, expected.status)
            }
        }

    @Test
    fun `silence is not sent`() =
        runBlocking {
            assertEquals("", RemoteTranscriber(recognition()).transcribe(ByteArray(0)))
            assertTrue(received.isEmpty())
        }

    @Test
    fun `the connection check tells a working server from a wrong key and a broken answer`() =
        runBlocking {
            answer = """{"text":""}"""
            assertEquals(ServerCheck.OK, RemoteTranscriber(recognition()).check())

            status = 403
            assertEquals(ServerCheck.AUTH_FAILED, RemoteTranscriber(recognition()).check())

            status = 200
            answer = """{"detail":"no text"}"""
            assertEquals(ServerCheck.FAILED, RemoteTranscriber(recognition()).check())

            val unreachable = recognition().copy(endpoint = closedEndpoint())
            assertEquals(ServerCheck.FAILED, RemoteTranscriber(unreachable).check())
        }
}
