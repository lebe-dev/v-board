package ru.tinyops.android.vboard.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GovorunApiTest {
    @Test
    fun `only the short pause has its own profile`() {
        assertEquals(GovorunApi.PROFILE_TASK_FLOW, GovorunApi.profile(PauseLength.SHORT))
        assertEquals(GovorunApi.PROFILE_DEFAULT, GovorunApi.profile(PauseLength.AUTO))
        assertEquals(GovorunApi.PROFILE_DEFAULT, GovorunApi.profile(PauseLength.MEDIUM))
        assertEquals(GovorunApi.PROFILE_DEFAULT, GovorunApi.profile(PauseLength.LONG))
    }

    @Test
    fun `stored pauses collapse to the two choices Govorun offers`() {
        assertEquals(PauseLength.SHORT, GovorunApi.pauseChoice(PauseLength.SHORT))
        assertEquals(PauseLength.AUTO, GovorunApi.pauseChoice(PauseLength.AUTO))
        assertEquals(PauseLength.AUTO, GovorunApi.pauseChoice(PauseLength.MEDIUM))
        assertEquals(PauseLength.AUTO, GovorunApi.pauseChoice(PauseLength.LONG))
    }

    @Test
    fun `error codes map to what the user can act on`() {
        assertEquals(DictationFailure.UNAUTHORIZED, GovorunApi.failure(1))
        assertEquals(DictationFailure.BUSY, GovorunApi.failure(2))
        assertEquals(DictationFailure.MODEL_NOT_READY, GovorunApi.failure(3))
        assertEquals(DictationFailure.BUSY, GovorunApi.failure(9))
        assertEquals(DictationFailure.RECOGNITION, GovorunApi.failure(5))
        assertEquals(DictationFailure.RECOGNITION, GovorunApi.failure(1000))
    }

    @Test
    fun `a canceled session is not a failure`() {
        assertNull(GovorunApi.failure(GovorunApi.ERROR_CANCELED))
    }

    @Test
    fun `continuous recognition needs protocol one and the profiles capability`() {
        assertTrue(GovorunApi.supports(protocol = 1, capabilities = 1))
        assertTrue(GovorunApi.supports(protocol = 1, capabilities = 127))
        assertFalse(GovorunApi.supports(protocol = 2, capabilities = 127))
        assertFalse(GovorunApi.supports(protocol = 1, capabilities = 0))
    }
}
