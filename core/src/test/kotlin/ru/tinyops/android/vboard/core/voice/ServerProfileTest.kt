package ru.tinyops.android.vboard.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerProfileTest {
    private val profile =
        ServerProfile(
            id = "a",
            name = "Work",
            endpoint = "https://server.example/v1",
            model = "gpt-4o-mini",
            apiKey = "key",
            effort = EnhancementEffort.HIGH,
        )

    @Test
    fun `a kind that is missing or unknown means a text server`() {
        assertEquals(ServerKind.TEXT, ServerKind.fromId(null))
        assertEquals(ServerKind.TEXT, ServerKind.fromId("nonsense"))
        assertEquals(ServerKind.AUDIO, ServerKind.fromId("audio"))
        assertEquals(ServerKind.TEXT, profile.kind)
    }

    @Test
    fun `a profile needs a usable address and a model`() {
        assertTrue(profile.isComplete)
        assertFalse(profile.copy(endpoint = "server.example").isComplete)
        assertFalse(profile.copy(model = " ").isComplete)
        assertFalse(profile.copy(proxyEnabled = true, proxyUrl = "nonsense").isComplete)
        assertTrue(profile.copy(proxyEnabled = true, proxyUrl = "socks5://host").isComplete)
    }

    @Test
    fun `text improvement gets the profile data and the prompt`() {
        val server = profile.forEnhancement("Fix it")
        assertEquals("https://server.example/v1/chat/completions", server.chatUrl)
        assertEquals("gpt-4o-mini", server.model)
        assertEquals("key", server.apiKey)
        assertEquals("Fix it", server.prompt)
        assertTrue(server.hasConsent, "choosing the server is the consent")
        assertTrue(server.isComplete)
    }

    @Test
    fun `recognition gets the profile data and its own options`() {
        val server = profile.forRecognition(language = "ru", promptEnabled = true, prompt = "Terms")
        assertEquals("https://server.example/v1/audio/transcriptions", server.transcriptionUrl)
        assertEquals("ru", server.language)
        assertEquals("Terms", server.requestPrompt)
        assertTrue(server.hasConsent)
    }

    @Test
    fun `values are trimmed and bounded when stored`() {
        val stored =
            profile
                .copy(
                    name = "  Work  ",
                    endpoint = " https://x/v1 ",
                    model = " m ",
                    apiKey = " k ",
                ).normalized()
        assertEquals("Work", stored.name)
        assertEquals("https://x/v1", stored.endpoint)
        assertEquals("m", stored.model)
        assertEquals("k", stored.apiKey)
        val long = profile.copy(name = "я".repeat(ServerProfile.MAX_NAME_LENGTH + 5)).normalized()
        assertEquals(ServerProfile.MAX_NAME_LENGTH, long.name.length)
    }

    @Test
    fun `a blank name falls back to the host and the model`() {
        assertEquals("server.example · gpt-4o-mini", profile.copy(name = "").displayName)
        assertEquals("Work", profile.displayName)
        assertEquals("gpt-4o-mini", profile.copy(name = "", endpoint = "").displayName)
    }

    @Test
    fun `the list adds, replaces and removes by id`() {
        val other = profile.copy(id = "b", name = "Other")
        var list = emptyList<ServerProfile>().withProfile(profile).withProfile(other)
        assertEquals(listOf("a", "b"), list.map { it.id })
        list = list.withProfile(profile.copy(name = "Renamed"))
        assertEquals(listOf("Renamed", "Other"), list.map { it.name })
        assertEquals("Other", list.find("b")?.name)
        assertNull(list.withoutProfile("b").find("b"))
        assertNull(list.find(null))
    }

    @Test
    fun `a copy keeps the connection under a new id and a marked name`() {
        val copy = profile.copy(proxyEnabled = true, proxyUrl = "socks5://p:1").duplicate("b") { "$it (copy)" }
        assertEquals("b", copy.id)
        assertEquals("Work (copy)", copy.name)
        assertEquals(profile.endpoint, copy.endpoint)
        assertEquals(profile.model, copy.model)
        assertEquals(profile.apiKey, copy.apiKey)
        assertEquals(profile.effort, copy.effort)
        assertTrue(copy.proxyEnabled)
        assertEquals("socks5://p:1", copy.proxyUrl)
    }

    @Test
    fun `a copy of an unnamed server stays unnamed and a long name is cut`() {
        assertEquals("", profile.copy(name = "").duplicate("b") { "$it (copy)" }.name)
        val long = profile.copy(name = "я".repeat(ServerProfile.MAX_NAME_LENGTH)).duplicate("b") { "$it (copy)" }
        assertEquals(ServerProfile.MAX_NAME_LENGTH, long.name.length)
    }
}
