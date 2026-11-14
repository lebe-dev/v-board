package ru.tinyops.android.vboard.settings

import android.content.Context
import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.KeyboardPrefs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HideableItemsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun rows(
        hidden: Set<String>,
        editing: Boolean = false,
    ) = HiddenRows(hidden, editing) {}

    @Test
    fun `ids are unique`() {
        val ids = HideableItem.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `a section with every row hidden is not shown`() {
        val typing =
            HideableItem.entries
                .filter { it.section == HideableSection.TYPING }
                .map { it.id }
                .toSet()
        val rows = rows(typing)
        assertFalse(rows.shows(HideableSection.TYPING))
        assertTrue(rows.shows(HideableSection.VOICE))
    }

    @Test
    fun `editing lists a section even when all its rows are hidden`() {
        val all = HideableItem.entries.map { it.id }.toSet()
        assertTrue(rows(all, editing = true).shows(HideableSection.TYPING))
    }

    @Test
    fun `hidden rows survive a backup`() {
        val prefs = KeyboardPrefs(context)
        prefs.hiddenSettings = setOf("theme", "servers")
        val text =
            SettingsBackup.encode(
                SettingsBackup.read(context, "", false),
                "1.0.0",
                java.time.OffsetDateTime.now(),
            )
        context.getSharedPreferences("keyboard_prefs", Context.MODE_PRIVATE).edit(commit = true) { clear() }
        assertTrue(KeyboardPrefs(context).hiddenSettings.isEmpty())
        SettingsBackup.write(context, SettingsBackup.decode(text))
        assertEquals(setOf("theme", "servers"), KeyboardPrefs(context).hiddenSettings)
    }
}
