package ru.tinyops.android.vboard.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.TraceSectionMetric.Mode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Measures the keyboard's hot paths through their trace sections. Running without a profile next
 * to running with it shows what the Baseline Profile buys on a freshly installed keyboard.
 */
@RunWith(Parameterized::class)
@OptIn(ExperimentalMetricApi::class)
class KeyboardBenchmark(
    private val compilation: String,
) {
    private val benchmark = MacrobenchmarkRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ActiveKeyboardRule()).around(benchmark)

    private fun measure(
        metrics: List<Metric>,
        action: KeyboardDriver.(android.graphics.Rect) -> Unit,
    ) {
        benchmark.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = metrics,
            compilationMode = compilationMode(),
            iterations = ITERATIONS,
            setupBlock = {
                val driver = KeyboardDriver(device)
                driver.close()
            },
            measureBlock = {
                val driver = KeyboardDriver(device)
                val keys = driver.open()
                driver.action(keys)
            },
        )
    }

    private fun compilationMode(): CompilationMode =
        if (compilation == WITH_PROFILE) CompilationMode.Partial() else CompilationMode.None()

    @Test
    fun typing() =
        measure(
            listOf(FrameTimingMetric()) + sections("kbd.key", "kbd.draw"),
        ) { keys -> type(keys) }

    @Test
    fun swiping() =
        measure(
            listOf(FrameTimingMetric()) + sections("kbd.decode.live", "kbd.decode.final", "kbd.draw"),
        ) { keys -> swipe(keys) }

    @Test
    fun coldOpen() {
        benchmark.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = sections("kbd.dict.load", "kbd.createView", "kbd.startInput"),
            compilationMode = compilationMode(),
            iterations = ITERATIONS,
            setupBlock = {
                KeyboardDriver(device).close()
                killProcess()
            },
            measureBlock = {
                KeyboardDriver(device).open()
                Thread.sleep(DICTIONARY_WAIT_MILLIS)
            },
        )
    }

    private fun sections(vararg names: String): List<Metric> =
        names.flatMap { name ->
            listOf(
                TraceSectionMetric(name, Mode.Average, label = "$name.average"),
                TraceSectionMetric(name, Mode.Max, label = "$name.max"),
            )
        }

    companion object {
        private const val WITH_PROFILE = "profile"
        private const val ITERATIONS = 5
        private const val DICTIONARY_WAIT_MILLIS = 1_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun compilations(): List<String> = listOf(WITH_PROFILE, "noProfile")
    }
}
