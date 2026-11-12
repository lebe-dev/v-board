package ru.tinyops.android.vboard.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SystemSpeechTest {
    @Test
    fun `the system recognizer is the default engine`() {
        assertEquals(RecognitionEngine.SYSTEM, RecognitionEngine.fromId(null))
        assertEquals(RecognitionEngine.SYSTEM, RecognitionEngine.fromId("unknown"))
        assertEquals(RecognitionEngine.GOVORUN, RecognitionEngine.fromId("govorun"))
    }

    @Test
    fun `silence is not a failure`() {
        assertNull(SystemSpeech.failure(SystemSpeech.ERROR_SPEECH_TIMEOUT))
        assertNull(SystemSpeech.failure(SystemSpeech.ERROR_NO_MATCH))
    }

    @Test
    fun `error codes map to what the user can act on`() {
        assertEquals(DictationFailure.SYSTEM_NETWORK, SystemSpeech.failure(1))
        assertEquals(DictationFailure.SYSTEM_NETWORK, SystemSpeech.failure(2))
        assertEquals(DictationFailure.SYSTEM_NETWORK, SystemSpeech.failure(4))
        assertEquals(DictationFailure.SYSTEM_NETWORK, SystemSpeech.failure(11))
        assertEquals(DictationFailure.SYSTEM_UNAVAILABLE, SystemSpeech.failure(SystemSpeech.ERROR_BUSY))
        assertEquals(DictationFailure.SYSTEM_LANGUAGE, SystemSpeech.failure(12))
        assertEquals(DictationFailure.SYSTEM_LANGUAGE, SystemSpeech.failure(13))
        assertEquals(DictationFailure.SYSTEM_FAILED, SystemSpeech.failure(3))
    }
}
