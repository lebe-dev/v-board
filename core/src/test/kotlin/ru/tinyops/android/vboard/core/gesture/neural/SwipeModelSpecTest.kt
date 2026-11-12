package ru.tinyops.android.vboard.core.gesture.neural

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SwipeModelSpecTest {
    @Test
    fun `reads the tables and the numbers`() {
        assertEquals('а', TEST_SPEC.decoderTokens[1])
        assertEquals(35, TEST_SPEC.numClasses)
        assertEquals(1031f, TEST_SPEC.anchorRight)
        assertEquals(Normalization(0.1f, 2f), TEST_SPEC.velocityX)
    }

    @Test
    fun `a spec with a missing value is rejected`() {
        val failure =
            assertFailsWith<IllegalArgumentException> { SwipeModelSpec.parse("num_classes=35".byteInputStream()) }
        assertEquals("Missing decoder_tokens in the model spec", failure.message)
    }
}
