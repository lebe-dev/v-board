package ru.tinyops.android.vboard.settings

import ru.tinyops.android.vboard.core.voice.CustomButton
import ru.tinyops.android.vboard.core.voice.membersOf
import ru.tinyops.android.vboard.core.voice.topLevel

/**
 * What the page of the custom buttons offers: a section for the strip of the keyboard and one for the panel
 * of the fullscreen mode. Without the mode its section stays only to reach the buttons already there. Turned
 * off buttons are listed but left off the models, where they would take the places of the others. The
 * members of a group are not in the sections: they are listed on the page of their group.
 */
internal class ButtonSections(
    private val all: List<CustomButton>,
    fullscreenEnabled: Boolean,
) {
    private val buttons = all.topLevel()
    val panel: List<CustomButton> = buttons.filter { it.fullscreenOnly }
    val keyboard: List<CustomButton> = buttons.filterNot { it.fullscreenOnly }
    val keyboardOnModel: List<CustomButton> = keyboard.filter { it.enabled }
    val panelOnModel: List<CustomButton> = panel.filter { it.enabled }
    val keyboardCanAdd: Boolean = keyboard.size < CustomButton.MAX_PER_SECTION
    val keyboardSectionShown: Boolean = keyboard.isNotEmpty() || keyboardCanAdd
    val panelSectionShown: Boolean = fullscreenEnabled || panel.isNotEmpty()
    val panelPreviewShown: Boolean = fullscreenEnabled && panelOnModel.isNotEmpty()
    val panelCanAdd: Boolean = fullscreenEnabled && panel.size < CustomButton.MAX_PER_SECTION
    val panelOffNote: Boolean = !fullscreenEnabled && panel.isNotEmpty()

    /** The members of the group [id]. */
    fun membersOf(id: String): List<CustomButton> = all.membersOf(id)

    /** The groups of the strip, or of the panel of the fullscreen mode. */
    fun groupsOf(panel: Boolean): List<CustomButton> = buttons.filter { it.group && it.fullscreenOnly == panel }
}
