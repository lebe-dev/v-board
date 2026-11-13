package ru.tinyops.android.vboard

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.core.dict.WordPairsRelease
import java.io.File

/** After an update the table of the previous version is replaced by the one of the new version. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WordPairsRefreshTest {
    private val release = WordPairsRelease("https://example.org/ru.bigrams.gz", "a".repeat(64), 3)
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "word-pairs-refresh-test")
        directory.deleteRecursively()
        directory.mkdirs()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `the table of another version is replaced`() {
        File(directory, "ru-bbbbbbbbbbbb.bigrams").writeText("old")

        assertTrue(WordPairs.needsRefresh(release, directory))
    }

    @Test
    fun `the table of this version is kept`() {
        File(directory, release.fileName).writeText("new")

        assertFalse(WordPairs.needsRefresh(release, directory))
    }

    @Test
    fun `nothing is downloaded for a user who never downloaded the table`() {
        assertFalse(WordPairs.needsRefresh(release, directory))
        File(directory, "${release.fileName}.part").writeText("x")
        assertFalse(WordPairs.needsRefresh(release, directory))
    }

    @Test
    fun `a build that cannot download the table does not try`() {
        File(directory, "ru-bbbbbbbbbbbb.bigrams").writeText("old")

        assertFalse(WordPairs.needsRefresh(WordPairsRelease("", "", 0), directory))
    }
}
