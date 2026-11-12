package ru.tinyops.android.vboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AssetFilesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val directory = File(context.noBackupFilesDir, "asset-files-test")
    private val asset = context.assets.open(ASSET).use { it.readBytes() }

    @Before
    fun setUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `the asset is copied whole and nothing temporary is left`() {
        val file = AssetFiles.extract(context, ASSET, directory, "notice", "txt")

        assertArrayEquals(asset, file.readBytes())
        assertEquals(listOf(file.name), directory.list().orEmpty().toList())
    }

    @Test
    fun `a copy cut short by a killed process is replaced`() {
        val file = AssetFiles.extract(context, ASSET, directory, "notice", "txt")
        // AtomicFile writes into `name.new` and renames it only once the copy is complete.
        File(directory, "${file.name}.new").writeBytes(asset.copyOf(asset.size / 2))
        file.delete()

        val again = AssetFiles.extract(context, ASSET, directory, "notice", "txt")

        assertArrayEquals(asset, again.readBytes())
    }

    @Test
    fun `a copy left by an earlier version of the app is deleted`() {
        directory.mkdirs()
        val stale = File(directory, "notice-1.txt").apply { writeText("old") }
        val unrelated = File(directory, "other.bin").apply { writeText("keep") }

        val file = AssetFiles.extract(context, ASSET, directory, "notice", "txt")

        assertTrue(file.isFile)
        assertTrue(!stale.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun `an existing copy of this version is reused`() {
        val file = AssetFiles.extract(context, ASSET, directory, "notice", "txt")
        file.setLastModified(0)

        AssetFiles.extract(context, ASSET, directory, "notice", "txt")

        assertEquals(0, file.lastModified())
    }

    private companion object {
        const val ASSET = "emoji/NOTICE.txt"
    }
}
