package ru.tinyops.android.vboard.core.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteRecognitionTest {
    @Test
    fun `server address gets the audio transcriptions path`() {
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            ServerAddress.transcriptions(" https://api.openai.com/v1/ "),
        )
        assertEquals(
            "https://openrouter.ai/api/v1/audio/transcriptions",
            ServerAddress.transcriptions("https://openrouter.ai/api/v1"),
        )
        assertEquals("https://x.io/v1/audio/transcriptions", ServerAddress.transcriptions("https://x.io/v1/audio"))
        assertEquals(
            "https://x.io/v1/audio/transcriptions",
            ServerAddress.transcriptions("https://x.io/v1/audio/transcriptions"),
        )
        assertNull(ServerAddress.transcriptions("ftp://x.io/v1"))
        assertNull(ServerAddress.transcriptions("https://user:pass@x.io/v1"))
    }

    @Test
    fun `a complete server needs an address, a model and a valid language`() {
        val server = RemoteRecognitionServer(endpoint = "https://api.openai.com/v1", model = "whisper-1")
        assertTrue(server.isComplete)
        assertTrue(server.copy(language = "").isComplete, "empty language means auto detection")
        assertFalse(server.copy(language = "russian").isComplete)
        assertFalse(server.copy(model = " ").isComplete)
        assertFalse(server.copy(proxyEnabled = true, proxyUrl = "https://p").isComplete)
        assertFalse(server.insecure)
        assertTrue(server.copy(endpoint = "http://10.0.0.2:8000/v1").insecure)
    }

    @Test
    fun `consent is bound to the server and the proxy`() {
        val server = RemoteRecognitionServer(endpoint = "https://stt.example/v1", model = "m")
        val agreed = server.copy(acceptedRecipient = server.recipient!!)
        assertTrue(agreed.hasConsent)
        assertFalse(agreed.copy(endpoint = "https://other.example/v1").hasConsent)
        assertFalse(agreed.copy(proxyEnabled = true, proxyUrl = "socks5://p").hasConsent)
    }

    @Test
    fun `a new model or key needs a new check`() {
        val server = RemoteRecognitionServer(endpoint = "https://stt.example/v1", model = "m", apiKey = "k")
        assertNotEquals(server.fingerprint, server.copy(model = "n").fingerprint)
        assertNotEquals(server.fingerprint, server.copy(apiKey = "j").fingerprint)
        assertEquals(server.fingerprint, server.copy(language = "en").fingerprint)
        assertEquals("en", server.copy(language = " EN ").normalized().language)
    }

    @Test
    fun `voice goes without a prompt by default`() {
        val server = RemoteRecognitionServer(endpoint = "https://stt.example/v1", model = "m")
        assertFalse(server.promptEnabled)
        assertEquals("", server.requestPrompt)
        assertEquals(RemoteRecognitionServer.DEFAULT_PROMPT, server.prompt, "the field is prefilled")
        assertTrue(server.copy(prompt = "").isComplete, "an unused prompt may be empty")
    }

    @Test
    fun `the prompt mode sends the trimmed prompt and needs one`() {
        val server = RemoteRecognitionServer(endpoint = "https://stt.example/v1", model = "m", promptEnabled = true)
        assertEquals(RemoteRecognitionServer.DEFAULT_PROMPT, server.requestPrompt)
        assertEquals("Термины", server.copy(prompt = " Термины ").requestPrompt)
        assertTrue(server.isComplete)
        assertFalse(server.copy(prompt = "  ").isComplete)
    }

    @Test
    fun `a stored prompt is trimmed and bounded`() {
        val long = "я".repeat(RemoteRecognitionServer.MAX_PROMPT_LENGTH + 1)
        val stored = RemoteRecognitionServer(prompt = "  $long").normalized().prompt
        assertEquals(RemoteRecognitionServer.MAX_PROMPT_LENGTH, stored.length)
    }

    @Test
    fun `changing the prompt does not need a new check`() {
        val server = RemoteRecognitionServer(endpoint = "https://stt.example/v1", model = "m")
        assertEquals(server.fingerprint, server.copy(promptEnabled = true, prompt = "Другой").fingerprint)
    }

    @Test
    fun `a model repeating the prompt recognized nothing`() {
        val server = RemoteRecognitionServer(promptEnabled = true, prompt = "Имена: Анна, Олег.")
        assertEquals("", server.withoutPromptEcho(" имена: анна, олег. "))
        assertEquals("Позвони Анне", server.withoutPromptEcho("Позвони Анне"))
        assertEquals(
            "Имена: Анна, Олег.",
            server.copy(promptEnabled = false).withoutPromptEcho("Имена: Анна, Олег."),
            "without a prompt this is what the user said",
        )
    }

    @Test
    fun `server check classifies auth failures`() {
        assertEquals(ServerCheck.AUTH_FAILED, ServerCheck.failure(401))
        assertEquals(ServerCheck.AUTH_FAILED, ServerCheck.failure(403))
        assertEquals(ServerCheck.FAILED, ServerCheck.failure(500))
    }

    @Test
    fun `wav header describes 16 kHz mono 16-bit pcm`() {
        val pcm = ByteArray(320) { it.toByte() }
        val wav = Wav.encode(pcm, 16_000)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + pcm.size, header.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals(1, header.getShort(20).toInt(), "PCM")
        assertEquals(1, header.getShort(22).toInt(), "mono")
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28), "byte rate")
        assertEquals(16, header.getShort(34).toInt(), "bits per sample")
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(pcm.size, header.getInt(40))
        assertEquals(44 + pcm.size, wav.size)
        assertEquals(44 + 32_000, Wav.silence(1_000, 16_000).size)
    }

    @Test
    fun `multipart body carries the file, the model and the language`() {
        val wav = Wav.encode(ByteArray(4), 16_000)
        val request = TranscriptionRequest(" whisper-1 ", "RU", wav, boundary = "b")
        assertEquals("multipart/form-data; boundary=b", request.contentType)
        val body = String(request.body(), Charsets.ISO_8859_1)
        assertTrue(body.startsWith("--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"))
        assertTrue("Content-Type: audio/wav\r\n\r\nRIFF" in body)
        assertTrue("name=\"model\"\r\n\r\nwhisper-1\r\n" in body)
        assertTrue("name=\"language\"\r\n\r\nru\r\n" in body)
        assertTrue("name=\"response_format\"\r\n\r\njson\r\n" in body)
        assertTrue(body.endsWith("--b--\r\n"))

        val auto = String(TranscriptionRequest("m", "", wav, boundary = "b").body(), Charsets.ISO_8859_1)
        assertFalse("name=\"language\"" in auto, "no language field for auto detection")
        assertFalse("name=\"prompt\"" in auto, "no prompt field without a prompt")
    }

    @Test
    fun `multipart body carries the prompt in UTF-8`() {
        val wav = Wav.encode(ByteArray(4), 16_000)
        val request = TranscriptionRequest("m", "ru", wav, prompt = "Термины", boundary = "b")
        val body = String(request.body(), Charsets.UTF_8)
        assertTrue("name=\"prompt\"\r\n\r\nТермины\r\n" in body)
    }
}
