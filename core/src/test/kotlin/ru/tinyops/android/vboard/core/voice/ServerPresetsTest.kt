package ru.tinyops.android.vboard.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerPresetsTest {
    @Test
    fun `chat presets give a usable chat address`() {
        ServerPresets.CHAT.forEach { assertNotNull(ServerAddress.chat(it.endpoint), it.name) }
    }

    @Test
    fun `transcription presets give a usable transcription address`() {
        ServerPresets.TRANSCRIPTION.forEach { assertNotNull(ServerAddress.transcriptions(it.endpoint), it.name) }
    }

    @Test
    fun `both lists offer OpenAI and OpenRouter`() {
        listOf(ServerPresets.CHAT, ServerPresets.TRANSCRIPTION).forEach { presets ->
            assertEquals(listOf("OpenAI", "OpenRouter"), presets.map { it.name }.take(2))
        }
    }

    @Test
    fun `a preset is recognized in a typed address`() {
        val openAi = ServerPresets.CHAT.first()
        assertTrue(openAi.matches("https://api.openai.com/v1"))
        assertTrue(openAi.matches(" HTTPS://api.openai.com/v1/ "))
        assertFalse(openAi.matches("https://openrouter.ai/api/v1"))
        assertFalse(openAi.matches(""))
    }
}
