package ru.tinyops.android.vboard.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSpeechRecognizer
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.RecognitionEngine
import ru.tinyops.android.vboard.core.voice.RecordingMode
import java.util.Locale

/** Dictation through the phone's recognizer, from the microphone key to the text in the field. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceInputTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private class Host : VoiceInput.Host {
        val field = StringBuilder()
        val messages = ArrayList<Int>()
        val settingsOpened = ArrayList<Boolean>()
        var state = VoiceState()

        override fun textBeforeDictation(): CharSequence = field

        override fun dictationLocale(): Locale = Locale.forLanguageTag("ru-RU")

        override fun insertText(text: String) {
            field.append(text)
        }

        override fun onVoiceState(state: VoiceState) {
            this.state = state
        }

        override fun setKeepScreenOn(keep: Boolean) = Unit

        override fun openSettings(requestPermission: Boolean) {
            settingsOpened += requestPermission
        }

        override fun showMessage(message: Int) {
            messages += message
        }
    }

    private val host = Host()
    private lateinit var voice: VoiceInput

    @Before
    fun setUp() {
        context
            .getSharedPreferences("voice", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        VoicePrefs(context).engine = RecognitionEngine.SYSTEM
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        installRecognizer()
        voice = VoiceInput(context, host)
    }

    @After
    fun tearDown() {
        voice.destroy()
        ShadowSpeechRecognizer.reset()
    }

    /** A recognition service on the phone, so that the system recognizer counts as available. */
    private fun installRecognizer() {
        val info =
            ResolveInfo().apply {
                serviceInfo =
                    ServiceInfo().apply {
                        packageName = "com.example.speech"
                        name = "com.example.speech.Recognizer"
                    }
            }
        val packages = shadowOf(context.packageManager)
        packages.addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), info)
        packages.addServiceIfNotPresent(ComponentName("com.example.speech", "com.example.speech.Recognizer"))
    }

    private val recognizer get() = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())

    private fun heard(text: String) {
        recognizer.triggerOnResults(
            Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) },
        )
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `without the microphone permission the key asks for it in the settings`() {
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)

        voice.toggle()

        assertEquals(listOf(true), host.settingsOpened)
        assertEquals(VoiceState.Phase.IDLE, host.state.phase)
    }

    @Test
    fun `a phrase goes into the field through the dictionary while listening goes on`() {
        VoicePrefs(context).dictionaryText = "кубер=Kubernetes"
        host.field.append("Ставим")

        voice.toggle()
        assertEquals(VoiceState.Phase.LISTENING, host.state.phase)
        assertEquals("ru-RU", recognizer.lastRecognizerIntent.getStringExtra("android.speech.extra.LANGUAGE"))

        heard("кубер в облаке")

        assertEquals("Ставим Kubernetes в облаке", host.field.toString())
        assertEquals(VoiceState.Phase.LISTENING, host.state.phase)
    }

    @Test
    fun `the second tap recognizes the last phrase and ends the dictation`() {
        voice.toggle()
        voice.toggle()
        assertEquals(VoiceState.Phase.RECOGNIZING, host.state.phase)

        heard("последняя фраза")

        assertEquals("последняя фраза", host.field.toString())
        assertEquals(VoiceState.Phase.IDLE, host.state.phase)
    }

    @Test
    fun `cancel drops the dictation and releases the recognizer`() {
        voice.toggle()
        val shadow = recognizer

        voice.cancel()
        ShadowLooper.idleMainLooper()

        assertEquals(VoiceState.Phase.IDLE, host.state.phase)
        assertTrue(shadow.isDestroyed)
        assertEquals("", host.field.toString())
    }

    @Test
    fun `in the repeat tap mode the text waits for the second tap`() {
        VoicePrefs(context).recordingMode = RecordingMode.REPEAT_TAP

        voice.toggle()
        heard("первая")
        heard("вторая")
        assertEquals("", host.field.toString())
        assertTrue(host.state.preview.contains("первая"))

        voice.toggle()
        heard("третья")

        assertEquals(listOf("первая", "вторая", "третья"), host.field.split(Regex("\\s+")))
        assertEquals(VoiceState.Phase.IDLE, host.state.phase)
    }

    @Test
    fun `a network failure of the recognizer is reported and ends the dictation`() {
        voice.toggle()

        recognizer.triggerOnError(SpeechRecognizer.ERROR_NETWORK)
        ShadowLooper.idleMainLooper()

        assertEquals(listOf(R.string.voice_system_network), host.messages)
        assertEquals(VoiceState.Phase.IDLE, host.state.phase)
    }

    @Test
    fun `silence is not a failure and listening goes on`() {
        voice.toggle()

        recognizer.triggerOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        ShadowLooper.idleMainLooper()

        assertTrue(host.messages.isEmpty())
        assertEquals(VoiceState.Phase.LISTENING, host.state.phase)
    }

    @Test
    fun `a phone without a recognizer says so instead of listening`() {
        shadowOf(context.packageManager).removeResolveInfosForIntent(
            Intent(RecognitionService.SERVICE_INTERFACE),
            "com.example.speech",
        )

        voice.toggle()

        assertEquals(listOf(R.string.voice_system_unavailable), host.messages)
        assertFalse(host.state.active)
    }
}
