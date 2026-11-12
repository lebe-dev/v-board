package ru.tinyops.android.vboard.stt.decode

import kotlin.test.Test
import kotlin.test.assertFailsWith

class DecodeConfigTest {
    @Test
    fun `rejects invalid values`() {
        assertFailsWith<IllegalArgumentException> { DecodeConfig(beamWidth = 0) }
        assertFailsWith<IllegalArgumentException> { DecodeConfig(maxResults = 0) }
        assertFailsWith<IllegalArgumentException> { DecodeConfig(maxWordLength = 0) }
        assertFailsWith<IllegalArgumentException> { DecodeConfig(sigmaFraction = 0.0) }
        assertFailsWith<IllegalArgumentException> { DecodeConfig(unexplainedPointCost = -1.0) }
    }
}
