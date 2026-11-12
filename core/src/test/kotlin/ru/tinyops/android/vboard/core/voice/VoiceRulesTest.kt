package ru.tinyops.android.vboard.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceRulesTest {
    @Test
    fun `auto pause is longer when text is inserted at the end`() {
        assertEquals(1.0f, PauseLength.AUTO.seconds(atEnd = false))
        assertEquals(2.0f, PauseLength.AUTO.seconds(atEnd = true))
        assertEquals(0.5f, PauseLength.SHORT.seconds(atEnd = true))
        assertEquals(PauseLength.AUTO, PauseLength.fromId("unknown"))
        assertEquals(PauseLength.LONG, PauseLength.fromId("long"))
    }

    @Test
    fun `recording mode is what was stored`() {
        assertEquals(RecordingMode.AFTER_PAUSE, RecordingMode.read(null))
        assertEquals(RecordingMode.AFTER_PAUSE, RecordingMode.read("after_pause"))
        assertEquals(RecordingMode.REPEAT_TAP, RecordingMode.read("repeat_tap"))
    }

    @Test
    fun `auto stop applies only while the screen is kept on`() {
        assertEquals(0L, AutoStop.limitMillis(keepScreenOn = false, autoStop = AutoStop.MINUTES_15))
        assertEquals(900_000L, AutoStop.limitMillis(keepScreenOn = true, autoStop = AutoStop.MINUTES_15))
        assertEquals(0L, AutoStop.limitMillis(keepScreenOn = true, autoStop = AutoStop.OFF))
        assertEquals(AutoStop.HOUR_1, AutoStop.fromMinutes(7))
    }

    @Test
    fun `deferred transcript is released on completion`() {
        val transcript = Transcript(deferred = true)
        assertNull(transcript.accept("Привет."))
        assertNull(transcript.accept("  "))
        assertNull(transcript.accept(" Как дела? "))
        assertEquals("Привет. Как дела?", transcript.pending)
        assertEquals("Привет. Как дела?", transcript.complete())
        assertNull(transcript.accept("поздно"))
        assertNull(transcript.complete())
    }

    @Test
    fun `streaming transcript hands out phrases at once`() {
        val transcript = Transcript(deferred = false)
        assertEquals("Привет.", transcript.accept(" Привет. "))
        assertNull(transcript.complete())
        assertEquals(1, transcript.fragmentCount)
    }

    @Test
    fun `discarded transcript releases nothing`() {
        val transcript = Transcript(deferred = true)
        transcript.accept("Текст")
        transcript.discard()
        assertNull(transcript.complete())
    }

    @Test
    fun `dictated text is separated from a preceding word`() {
        assertEquals(" Привет", Transcript.withLeadingSpace("Привет", "слово"))
        assertEquals("Привет", Transcript.withLeadingSpace("Привет", "слово "))
        assertEquals("Привет", Transcript.withLeadingSpace("Привет", ""))
        assertEquals("Привет", Transcript.withLeadingSpace("Привет", null))
        assertEquals("\nПривет", Transcript.withLeadingSpace("\nПривет", "слово"))
    }

    @Test
    fun `server address gets the chat completions path`() {
        assertEquals("https://api.example.com/v1/chat/completions", ServerAddress.chat(" https://api.example.com/v1/ "))
        assertEquals("http://localhost:11434/v1/chat/completions", ServerAddress.chat("http://localhost:11434/v1"))
        assertEquals("https://x.io/v1/chat/completions", ServerAddress.chat("https://x.io/v1/chat"))
        assertEquals("https://x.io/v1/chat/completions", ServerAddress.chat("https://x.io/v1/chat/completions"))
        assertEquals("https://x.io/chat/completions", ServerAddress.chat("https://x.io"))
        assertNull(ServerAddress.chat("ftp://x.io/v1"))
        assertNull(ServerAddress.chat("https://user:pass@x.io/v1"))
        assertNull(ServerAddress.chat("https://x.io/v1#frag"))
        assertNull(ServerAddress.chat("x.io/v1"))
    }

    @Test
    fun `proxy address needs no credentials and gets a default port`() {
        assertEquals("http://proxy.lan:8080", ServerAddress.proxy("http://Proxy.lan"))
        assertEquals("socks5://10.0.0.1:1080", ServerAddress.proxy("socks5://10.0.0.1/"))
        assertEquals("http://p:3128", ServerAddress.proxy("http://p:3128"))
        assertNull(ServerAddress.proxy("https://p:3128"))
        assertNull(ServerAddress.proxy("http://u@p:3128"))
        assertNull(ServerAddress.proxy("http://p:3128/path"))
    }

    @Test
    fun `consent is bound to the server and the proxy`() {
        val server = SelfHostedServer(endpoint = "https://x.io/v1", model = "m")
        val accepted = server.copy(acceptedRecipient = requireNotNull(server.recipient))
        assertTrue(accepted.hasConsent)
        assertTrue(accepted.isComplete)
        assertFalse(accepted.copy(endpoint = "https://y.io/v1").hasConsent)
        assertFalse(accepted.copy(proxyEnabled = true, proxyUrl = "http://p").hasConsent)
        assertFalse(server.hasConsent)
        assertFalse(server.copy(model = " ").isComplete)
        assertFalse(server.copy(proxyEnabled = true, proxyUrl = "nope").isComplete)
        assertTrue(server.copy(endpoint = "http://lan:8000").insecure)
    }

    @Test
    fun `a change of server, model or key needs a new check, a change of prompt does not`() {
        val server = SelfHostedServer(endpoint = "https://x.io/v1", model = "m", apiKey = "k")
        val checked = requireNotNull(server.fingerprint)
        assertEquals(checked, server.copy(prompt = "Другой промпт", model = " m ", apiKey = " k").fingerprint)
        assertNotEquals(checked, server.copy(endpoint = "https://y.io/v1").fingerprint)
        assertNotEquals(checked, server.copy(model = "m2").fingerprint)
        assertNotEquals(checked, server.copy(apiKey = "k2").fingerprint)
        assertNull(server.copy(endpoint = "ftp://x.io").fingerprint)
    }

    @Test
    fun `stored server settings are trimmed and bounded`() {
        val stored =
            SelfHostedServer(
                endpoint = " https://x.io/v1 ",
                model = " " + "m".repeat(200),
                apiKey = " key ",
                prompt = " Исправь. ",
                proxyUrl = " http://p:3128 ",
            ).normalized()
        assertEquals("https://x.io/v1", stored.endpoint)
        assertEquals(SelfHostedServer.MAX_MODEL_LENGTH, stored.model.length)
        assertEquals("key", stored.apiKey)
        assertEquals("Исправь.", stored.prompt)
        assertEquals("http://p:3128", stored.proxyUrl)
    }

    @Test
    fun `a prompt too short to mean anything leaves the server incomplete`() {
        val server = SelfHostedServer(endpoint = "https://x.io/v1", model = "m")
        assertFalse(server.copy(prompt = " ок ").isComplete)
        assertTrue(server.copy(prompt = "Да!").isComplete)
    }

    @Test
    fun `failed or empty improvement keeps the recognized text`() {
        assertEquals("исходный" to EnhancementOutcome.FAILED, EnhancementOutcome.resolve("исходный", "  "))
        assertEquals("Готово." to EnhancementOutcome.SUCCESS, EnhancementOutcome.resolve("готово", " Готово. "))
        assertEquals("готово" to EnhancementOutcome.UNCHANGED, EnhancementOutcome.resolve("готово", "готово"))
        assertEquals(EnhancementOutcome.AUTH_FAILED, EnhancementOutcome.failure(401))
        assertEquals(EnhancementOutcome.FAILED, EnhancementOutcome.failure(500))
    }

    @Test
    fun `effort is medium unless chosen and unknown values fall back to it`() {
        assertEquals(EnhancementEffort.MEDIUM, EnhancementEffort.DEFAULT)
        assertEquals("high", EnhancementEffort.fromId("high").id)
        assertEquals(EnhancementEffort.LOW, EnhancementEffort.fromId("low"))
        assertEquals(EnhancementEffort.DEFAULT, EnhancementEffort.fromId("turbo"))
        assertEquals(EnhancementEffort.DEFAULT, EnhancementEffort.fromId(null))
    }

    @Test
    fun `text is improved from the chosen number of characters`() {
        assertTrue(EnhancementThreshold.CHARS_60.applies("а".repeat(60)))
        assertFalse(EnhancementThreshold.CHARS_60.applies("а".repeat(59)))
    }

    @Test
    fun `spaces count inside the text but not around it`() {
        assertTrue(EnhancementThreshold.CHARS_30.applies("а".repeat(15) + " ".repeat(5) + "б".repeat(10)))
        assertFalse(EnhancementThreshold.CHARS_30.applies(" \n" + "а".repeat(29) + "\t "))
    }

    @Test
    fun `a character outside the basic plane counts once`() {
        assertTrue(EnhancementThreshold.CHARS_30.applies("😀".repeat(30)))
        assertFalse(EnhancementThreshold.CHARS_30.applies("😀".repeat(29)))
    }

    @Test
    fun `every text is improved when there is no threshold`() {
        assertTrue(EnhancementThreshold.ALWAYS.applies("да"))
    }

    @Test
    fun `an unknown threshold falls back to the default`() {
        assertEquals(60, EnhancementThreshold.DEFAULT.characters)
        assertEquals(EnhancementThreshold.CHARS_120, EnhancementThreshold.fromCharacters(120))
        assertEquals(EnhancementThreshold.ALWAYS, EnhancementThreshold.fromCharacters(0))
        assertEquals(EnhancementThreshold.DEFAULT, EnhancementThreshold.fromCharacters(7))
    }
}
