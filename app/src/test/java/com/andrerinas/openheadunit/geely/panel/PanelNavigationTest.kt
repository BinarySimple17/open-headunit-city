package com.andrerinas.openheadunit.geely.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The navigation state machine (F010) — pure transitions, no Android. */
class PanelNavigationTest {

    @Test
    fun `anchor expands the panel and keeps open groups`() {
        val state = PanelNavigation.expand(PanelNavState(openInlineGroups = setOf("seat")))
        assertTrue(state.expanded)
        assertEquals(setOf("seat"), state.openInlineGroups)
    }

    @Test
    fun `collapse folds the panel and everything it opened`() {
        val state = PanelNavigation.collapse(
            PanelNavState(expanded = true, openInlineGroups = setOf("seat", "future"))
        )
        assertFalse(state.expanded)
        assertTrue(state.openInlineGroups.isEmpty())
    }

    @Test
    fun `group tap toggles only that group's row`() {
        val open = PanelNavState(expanded = true, openInlineGroups = setOf("seat"))
        val afterClose = PanelNavigation.toggleInlineGroup(open, "seat")
        assertTrue(afterClose.openInlineGroups.isEmpty())
        val afterOpen = PanelNavigation.toggleInlineGroup(afterClose, "seat")
        assertEquals(setOf("seat"), afterOpen.openInlineGroups)
        // A second group's row does not disturb the first.
        val two = PanelNavigation.toggleInlineGroup(afterOpen, "future")
        assertEquals(setOf("seat", "future"), two.openInlineGroups)
    }

    @Test
    fun `collapse from collapsed is a no-op state`() {
        assertEquals(PanelNavState(), PanelNavigation.collapse(PanelNavState()))
    }
}
