package ru.tinyops.android.vboard.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.core.voice.EnhancementEffort
import ru.tinyops.android.vboard.core.voice.EnhancementThreshold
import ru.tinyops.android.vboard.core.voice.PauseLength
import ru.tinyops.android.vboard.core.voice.PromptPlace
import ru.tinyops.android.vboard.core.voice.RecognitionEngine
import ru.tinyops.android.vboard.core.voice.RecordingMode
import ru.tinyops.android.vboard.core.voice.RemoteRecognitionServer
import ru.tinyops.android.vboard.core.voice.SavedPrompt
import ru.tinyops.android.vboard.core.voice.SelfHostedServer
import ru.tinyops.android.vboard.core.voice.ServerKind
import ru.tinyops.android.vboard.core.voice.ServerProfile
import ru.tinyops.android.vboard.core.voice.TextFeature

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceSettingsTest {
    private val prefs = VoicePrefs(RuntimeEnvironment.getApplication())

    @Test
    fun `defaults follow Govorun`() {
        assertTrue(prefs.headsetMicrophone)
        assertEquals(PauseLength.AUTO, prefs.pause)
        assertEquals(RecordingMode.AFTER_PAUSE, prefs.recordingMode)
        assertFalse(prefs.keepScreenOn)
        assertTrue(prefs.voiceEnabled)
        assertTrue(prefs.dictionaryEnabled)
        assertFalse(prefs.enhancementReady)
    }

    @Test
    fun `the system recognizer is used until Govorun is chosen`() {
        assertEquals(RecognitionEngine.SYSTEM, prefs.engine)
        prefs.engine = RecognitionEngine.GOVORUN
        assertEquals(RecognitionEngine.GOVORUN, VoicePrefs(RuntimeEnvironment.getApplication()).engine)
    }

    private fun addServer(
        id: String = "main",
        model: String = "m",
        effort: EnhancementEffort = EnhancementEffort.DEFAULT,
    ): ServerProfile {
        val profile = ServerProfile(id, "Main", "https://server.example/v1", model, "key", effort)
        prefs.saveServer(profile)
        return profile
    }

    @Test
    fun `text improvement needs a chosen complete server`() {
        prefs.enhancementEnabled = true
        assertFalse("no server chosen", prefs.enhancementReady)

        addServer()
        prefs.setFeatureServer(TextFeature.DICTATION, "main")
        assertTrue(prefs.enhancementReady)

        prefs.saveServer(prefs.servers.first().copy(model = ""))
        assertFalse("a server without a model cannot be used", prefs.enhancementReady)
    }

    @Test
    fun `proofreading is off until it is turned on, whatever the dictation does`() {
        addServer()
        prefs.setFeatureServer(TextFeature.DICTATION, "main")
        prefs.setFeatureServer(TextFeature.PROOFREAD, "main")
        prefs.enhancementEnabled = true
        assertTrue(prefs.featureReady(TextFeature.DICTATION))
        assertFalse(prefs.featureReady(TextFeature.PROOFREAD))

        prefs.setFeatureEnabled(TextFeature.PROOFREAD, true)
        assertTrue(prefs.featureReady(TextFeature.PROOFREAD))
    }

    @Test
    fun `a feature sends with the data and the effort of its server`() {
        addServer(model = "gpt", effort = EnhancementEffort.HIGH)
        addServer(id = "cheap", model = "mini", effort = EnhancementEffort.LOW)
        prefs.setFeatureServer(TextFeature.DICTATION, "main")
        prefs.setFeatureServer(TextFeature.PROOFREAD, "cheap")

        val dictation = prefs.connection(TextFeature.DICTATION)
        assertEquals("gpt", dictation.server.model)
        assertEquals(EnhancementEffort.HIGH, dictation.effort)
        assertEquals("key", dictation.server.apiKey)
        assertTrue(dictation.server.hasConsent)
        val proofread = prefs.connection(TextFeature.PROOFREAD)
        assertEquals("mini", proofread.server.model)
        assertEquals(EnhancementEffort.LOW, proofread.effort)
    }

    @Test
    fun `a function uses its own prompt or the default one`() {
        addServer()
        prefs.setFeatureServer(TextFeature.PROOFREAD, "main")
        assertEquals(SelfHostedServer.DEFAULT_PROMPT, prefs.connection(TextFeature.PROOFREAD).server.prompt)

        prefs.prompts.save(SavedPrompt("own", "Свой", "own prompt"))
        prefs.prompts.setIds(PromptPlace.PROOFREAD, listOf("own"))
        assertEquals("own prompt", prefs.connection(TextFeature.PROOFREAD).server.prompt)
    }

    @Test
    fun `the hold of proofreading acts only with a prompt chosen for it`() {
        addServer()
        prefs.setFeatureServer(TextFeature.PROOFREAD, "main")
        assertFalse(prefs.proofreadHoldReady)

        prefs.prompts.save(SavedPrompt("own", "Свой", "hold prompt"))
        prefs.prompts.setIds(PromptPlace.PROOFREAD_HOLD, listOf("own"))
        assertTrue(prefs.proofreadHoldReady)
        assertEquals("hold prompt", prefs.connection(TextFeature.PROOFREAD, held = true).server.prompt)
        assertEquals(SelfHostedServer.DEFAULT_PROMPT, prefs.connection(TextFeature.PROOFREAD).server.prompt)

        prefs.prompts.delete("own")
        assertFalse(prefs.proofreadHoldReady)
    }

    @Test
    fun `the kind of a server is stored and an old entry is a text server`() {
        val prefs = VoicePrefs(RuntimeEnvironment.getApplication())
        prefs.saveServer(ServerProfile("a", "Voice", "https://stt.example/v1", "whisper", kind = ServerKind.AUDIO))
        assertEquals(ServerKind.AUDIO, prefs.servers.single().kind)
        val old = """[{"id":"x","name":"Old","endpoint":"https://server.example/v1","model":"m"}]"""
        assertEquals(ServerKind.TEXT, ServerProfileJson.parse(old).single().kind)
    }

    @Test
    fun `the servers are stored in order`() {
        val first = addServer()
        val second = addServer(id = "second").copy(proxyEnabled = true, proxyUrl = "socks5://p:1080")
        prefs.saveServer(second)
        assertEquals(listOf(first, second), VoicePrefs(RuntimeEnvironment.getApplication()).servers)
    }

    private fun addAudio(
        id: String,
        model: String = "whisper",
    ) = prefs.saveServer(ServerProfile(id, id, "https://stt.example/v1", model, kind = ServerKind.AUDIO))

    @Test
    fun `the first server of a kind is the default until another one is marked`() {
        addServer()
        addServer(id = "other")
        assertEquals("main", prefs.defaultServerId(ServerKind.TEXT))
        assertNull(prefs.defaultServerId(ServerKind.AUDIO))
        prefs.setDefaultServer(ServerKind.TEXT, "other")
        assertEquals("other", prefs.defaultServerId(ServerKind.TEXT))
        prefs.setDefaultServer(ServerKind.AUDIO, "other")
        assertNull("a text server cannot be the audio default", prefs.defaultServerId(ServerKind.AUDIO))
    }

    @Test
    fun `a function without a choice of its own follows the default server`() {
        addServer()
        addServer(id = "other")
        assertEquals("main", prefs.featureServerId(TextFeature.DICTATION))
        prefs.setDefaultServer(ServerKind.TEXT, "other")
        assertEquals("other", prefs.featureServerId(TextFeature.DICTATION))
        prefs.setFeatureServer(TextFeature.DICTATION, "main")
        assertEquals("main", prefs.featureServerId(TextFeature.DICTATION))
    }

    @Test
    fun `deleting a server moves what used it to the default server`() {
        addServer()
        addServer(id = "other")
        addAudio("voice")
        addAudio("voice2")
        prefs.setFeatureServer(TextFeature.DICTATION, "main")
        prefs.enhancementEnabled = true
        prefs.recognitionServerId = "voice"
        prefs.remoteEnabled = true

        assertFalse(prefs.deleteServer("main"))
        assertEquals("other", prefs.featureServerId(TextFeature.DICTATION))
        assertEquals("other", prefs.defaultServerId(ServerKind.TEXT))
        assertTrue(prefs.enhancementEnabled)
        assertFalse(prefs.deleteServer("voice"))
        assertEquals("voice2", prefs.recognitionServerId)
        assertTrue(prefs.remoteEnabled)
    }

    @Test
    fun `deleting the last server of a kind turns off what needs it`() {
        addServer()
        addAudio("voice")
        prefs.enhancementEnabled = true
        prefs.setFeatureEnabled(TextFeature.PROOFREAD, true)
        prefs.recognitionServerId = "voice"
        prefs.remoteEnabled = true

        assertTrue(prefs.deleteServer("main"))
        assertFalse(prefs.enhancementEnabled)
        assertFalse(prefs.featureEnabled(TextFeature.PROOFREAD))
        assertNull(prefs.featureServerId(TextFeature.DICTATION))
        assertTrue("audio is untouched", prefs.remoteEnabled)

        assertTrue(prefs.deleteServer("voice"))
        assertFalse(prefs.remoteEnabled)
        assertNull(prefs.recognitionServerId)
    }

    @Test
    fun `text improvement and the recording mode do not affect each other`() {
        prefs.enhancementEnabled = true
        assertEquals("enabling keeps the mode", RecordingMode.AFTER_PAUSE, prefs.recordingMode)

        prefs.recordingMode = RecordingMode.REPEAT_TAP
        assertTrue(prefs.enhancementEnabled)
        prefs.recordingMode = RecordingMode.AFTER_PAUSE
        assertTrue("choosing a mode keeps it on", prefs.enhancementEnabled)
        assertEquals(RecordingMode.AFTER_PAUSE, prefs.recordingMode)
    }

    @Test
    fun `the improvement threshold is stored`() {
        assertEquals(EnhancementThreshold.DEFAULT, prefs.enhancementThreshold)

        prefs.enhancementThreshold = EnhancementThreshold.ALWAYS
        assertEquals(EnhancementThreshold.ALWAYS, VoicePrefs(RuntimeEnvironment.getApplication()).enhancementThreshold)
    }

    @Test
    fun `dictionary applies only when enabled`() {
        prefs.dictionaryText = "кубер=Kubernetes"
        assertEquals("Kubernetes", prefs.dictionary().apply("кубер"))
        prefs.dictionaryEnabled = false
        assertEquals("кубер", prefs.dictionary().apply("кубер"))
    }

    @Test
    fun `chat completion request and response follow the OpenAI format`() {
        val request = TextEnhancer.buildRequest(" gpt ", " Исправь ", "текст", EnhancementEffort.LOW)
        assertEquals("gpt", request.getString("model"))
        assertEquals("low", request.getString("reasoning_effort"))
        assertFalse(request.getBoolean("stream"))
        val messages = request.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("Исправь", messages.getJSONObject(0).getString("content"))
        assertEquals("текст", messages.getJSONObject(1).getString("content"))

        val answer = """{"choices":[{"message":{"role":"assistant","content":" Текст. "}}]}"""
        assertEquals("Текст.", TextEnhancer.parseResponse(answer))
        assertEquals("", TextEnhancer.parseResponse("""{"error":"nope"}"""))
        assertEquals("", TextEnhancer.parseResponse("not json"))
    }

    @Test
    fun `remote recognition is off by default and needs a chosen complete server`() {
        assertFalse(prefs.remoteEnabled)
        assertFalse(prefs.remoteReady)

        addAudio("main", model = "openai/whisper-1")
        prefs.recognitionServerId = "main"
        prefs.remoteEnabled = true
        assertTrue(prefs.remoteReady)
        assertEquals("openai/whisper-1", prefs.remoteServer.model)
        assertTrue(prefs.remoteServer.hasConsent)
        assertFalse("text improvement is independent", prefs.enhancementReady)

        prefs.saveServer(prefs.servers.first().copy(endpoint = "nonsense"))
        assertFalse("a server that cannot be reached is not used", prefs.remoteReady)
    }

    @Test
    fun `the language and the mode of recognition are stored apart from the server`() {
        assertEquals(RemoteRecognitionServer.DEFAULT_LANGUAGE, prefs.remoteLanguage)
        prefs.remoteLanguage = " EN "
        prefs.remotePromptEnabled = true
        addAudio("main")
        prefs.recognitionServerId = "main"
        val stored = VoicePrefs(RuntimeEnvironment.getApplication()).remoteServer
        assertEquals("en", stored.language)
        assertTrue(stored.promptEnabled)
    }

    @Test
    fun `the recognition prompt comes from the collection`() {
        assertEquals(RemoteRecognitionServer.DEFAULT_PROMPT, prefs.remoteServer.prompt)

        prefs.prompts.save(SavedPrompt("terms", "Термины", "Термины"))
        prefs.prompts.setIds(PromptPlace.RECOGNITION, listOf("terms"))
        assertEquals("Термины", prefs.remoteServer.prompt)
    }

    @Test
    fun `transcription response is the text field`() {
        assertEquals("Привет мир", RemoteTranscriber.parseResponse("""{"text":" Привет мир ","usage":{}}"""))
        assertEquals("", RemoteTranscriber.parseResponse("""{"text":""}"""))
        assertThrows(IllegalStateException::class.java) { RemoteTranscriber.parseResponse("""{"error":"x"}""") }
        assertThrows(IllegalStateException::class.java) { RemoteTranscriber.parseResponse("not json") }
    }
}
