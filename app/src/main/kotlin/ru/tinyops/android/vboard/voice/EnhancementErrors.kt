package ru.tinyops.android.vboard.voice

import ru.tinyops.android.vboard.R

/** Messages about a failed text improvement; the strip shows them in red. */
val ENHANCEMENT_ERRORS =
    setOf(
        R.string.voice_enhancement_failed,
        R.string.voice_enhancement_auth_failed,
        R.string.voice_enhancement_timed_out,
        R.string.proofread_failed,
        R.string.proofread_auth_failed,
        R.string.proofread_timed_out,
    )
