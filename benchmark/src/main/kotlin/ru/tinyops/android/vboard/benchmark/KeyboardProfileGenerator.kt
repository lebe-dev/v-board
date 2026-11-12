package ru.tinyops.android.vboard.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Records the code a keyboard runs in everyday use, so that it is compiled ahead of time. */
@RunWith(AndroidJUnit4::class)
class KeyboardProfileGenerator {
    private val baselineProfile = BaselineProfileRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ActiveKeyboardRule()).around(baselineProfile)

    @Test
    fun generate() =
        baselineProfile.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
            val driver = KeyboardDriver(device)
            driver.close()
            val keys = driver.open()
            driver.type(keys)
            driver.swipe(keys)
            driver.close()
        }
}
