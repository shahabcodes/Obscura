package com.shahaabapps.obscura.ui.gallery

import com.shahaabapps.obscura.export.ExportManager
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionLogicTest {

    private val ids = listOf("a", "b", "c", "d", "e", "f")

    @Test
    fun draggingForwardSelectsTheRange() {
        assertEquals(setOf("b", "c", "d"), SelectionLogic.dragRange(ids, emptySet(), anchor = 1, current = 3, adding = true))
    }

    @Test
    fun draggingBackwardsFromTheAnchorWorksToo() {
        assertEquals(setOf("c", "d", "e"), SelectionLogic.dragRange(ids, emptySet(), anchor = 4, current = 2, adding = true))
    }

    @Test
    fun movingBackTowardsTheAnchorGivesTilesBack() {
        val far = SelectionLogic.dragRange(ids, emptySet(), 0, 4, true)
        val back = SelectionLogic.dragRange(ids, emptySet(), 0, 1, true)
        assertEquals(5, far.size)
        assertEquals(setOf("a", "b"), back)
    }

    @Test
    fun addingKeepsWhatWasAlreadySelected() {
        assertEquals(setOf("a", "f", "c", "d"), SelectionLogic.dragRange(ids, setOf("a", "f"), 2, 3, true))
    }

    @Test
    fun startingOnASelectedTileDeselectsTheRange() {
        val before = ids.toSet()
        assertEquals(setOf("a", "e", "f"), SelectionLogic.dragRange(ids, before, 1, 3, adding = false))
    }

    @Test
    fun tapTogglesOneTile() {
        assertEquals(setOf("a", "b"), SelectionLogic.toggle(setOf("a"), "b"))
        assertEquals(setOf("a"), SelectionLogic.toggle(setOf("a", "b"), "b"))
    }

    @Test
    fun exportsNeverOverwrite() {
        assertEquals("beach.jpg", ExportManager.uniqueName("beach.jpg", emptySet()))
        assertEquals("beach (2).jpg", ExportManager.uniqueName("beach.jpg", setOf("beach.jpg")))
        assertEquals("beach (3).jpg", ExportManager.uniqueName("beach.jpg", setOf("beach.jpg", "beach (2).jpg")))
        assertEquals("Beach (2).JPG", ExportManager.uniqueName("Beach.JPG", setOf("beach.jpg")))
        assertEquals("notes (2)", ExportManager.uniqueName("notes", setOf("notes")))
        assertEquals(".hidden (2)", ExportManager.uniqueName(".hidden", setOf(".hidden")))
    }

    @Test
    fun exportNamesCantEscapeTheFolder() {
        assertEquals(".._.._etc_passwd", ExportManager.uniqueName("../../etc/passwd", emptySet()))
        assertEquals("a_b_c.txt", ExportManager.uniqueName("a:b*c.txt", emptySet()))
        assertEquals("file", ExportManager.uniqueName("   ", emptySet()))
    }
}
