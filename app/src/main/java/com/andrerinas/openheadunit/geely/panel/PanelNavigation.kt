package com.andrerinas.openheadunit.geely.panel

/**
 * The panel's navigation state machine (F010) — pure Kotlin, JVM-testable. The coordinator
 * applies a [PanelNavState] to the views ([PanelSurface.applyNavigation]) and drives the
 * autocollapse timer around the transitions; the future LIST/PAGE group flavors extend this
 * state (a pushed screen or an open window id) without touching the rendering of what
 * already exists.
 *
 * Semantics preserved from the pre-F010 controller: collapsing the panel closes every open
 * inline group; a group tap toggles only that group's row.
 */
data class PanelNavState(
    /** Whether the dropdown column under the anchor is shown. */
    val expanded: Boolean = false,
    /** Inline-row groups ([GroupNode.id]s) whose expansion row is shown. */
    val openInlineGroups: Set<String> = emptySet(),
)

object PanelNavigation {

    /** The anchor opened the panel; open groups stay as they were (mirrors the old expand()). */
    fun expand(state: PanelNavState): PanelNavState = state.copy(expanded = true)

    /** The panel folded — everything it opened folds with it (the old collapse()). */
    fun collapse(state: PanelNavState): PanelNavState = PanelNavState(expanded = false, openInlineGroups = emptySet())

    /** A group button was tapped: its inline row shows/hides. */
    fun toggleInlineGroup(state: PanelNavState, groupId: String): PanelNavState =
        if (groupId in state.openInlineGroups) {
            state.copy(openInlineGroups = state.openInlineGroups - groupId)
        } else {
            state.copy(openInlineGroups = state.openInlineGroups + groupId)
        }
}
