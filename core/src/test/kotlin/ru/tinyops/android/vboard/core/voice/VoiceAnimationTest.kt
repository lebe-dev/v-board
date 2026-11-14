package ru.tinyops.android.vboard.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceAnimationTest {
    @Test
    fun `the aurora is the default animation`() {
        assertEquals(VoiceAnimation.AURORA, VoiceAnimation.fromId(null))
        assertEquals(VoiceAnimation.AURORA, VoiceAnimation.fromId("unknown"))
    }

    @Test
    fun `every animation is read back from its id`() {
        for (animation in VoiceAnimation.entries) assertEquals(animation, VoiceAnimation.fromId(animation.id))
    }
}
