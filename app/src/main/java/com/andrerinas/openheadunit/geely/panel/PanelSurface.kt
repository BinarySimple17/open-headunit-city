package com.andrerinas.openheadunit.geely.panel

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Builds and holds the panel's view tree from a declarative node list (F010) — the renderer
 * half of the shell. What exists today: the round anchor button, the vertical button column
 * (one round button per root node), and one horizontal inline row per INLINE_ROW group,
 * positioned level with the group's own column button. LIST/PAGE groups and [PageNode]
 * resolve to a plain tap callback for now — their containers arrive with the first feature
 * that needs them, and this class is the only place that grows.
 *
 * The tree is generic over the action payload [T]; the HVAC panel instantiates it with
 * CarFunctionSpec. Containers are NOT clickable — the touch-isolation contract: buttons
 * consume their taps, every other touch falls through to OverlayTouchView (the AA phone).
 *
 * Main thread only.
 */
class PanelSurface<T>(
    private val context: Context,
    private val theme: PanelTheme,
    anchorIconRes: Int,
    private val anchorContentDescription: String,
    /** Spoken label of a node — the coordinator resolves the node's label resource. */
    private val label: (PanelNode<T>) -> String,
) {

    private val factory = PanelViewFactory(context, theme, anchorIconRes)
    private val dp = factory::dp

    /** Called per node kind when its button is tapped; set before [build]. */
    var onActionTap: ((ActionNode<T>) -> Unit)? = null
    var onGroupTap: ((GroupNode<T>) -> Unit)? = null
    var onPageTap: ((PageNode<T>) -> Unit)? = null

    var onAnchorTap: (() -> Unit)? = null

    lateinit var root: FrameLayout
        private set
    private var anchorButton: RoundButton? = null
    private var column: LinearLayout? = null
    private val buttonViews = mutableMapOf<String, RoundButton>()
    private val rowViews = mutableMapOf<String, LinearLayout>()

    /** The button of the node with this id, wherever it lives (column or an inline row). */
    fun button(id: String): RoundButton? = buttonViews[id]

    /** The anchor button (theme-only painting; not status-driven). */
    fun anchor(): RoundButton? = anchorButton

    fun build(nodes: List<PanelNode<T>>) {
        root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
            // The elevation shadows fall outside the button/column bounds; the default child
            // clipping would shave them off (bottom of the anchor, right of the column,
            // left of the seat row).
            clipChildren = false
            // Room for the shadows below the last row of buttons: the parent clips root's
            // drawing to root's own bounds, and those ended exactly at the button edge.
            setPadding(0, 0, 0, dp(PanelMetrics.SHADOW_ROOM_DP))
        }
        root.addView(makeAnchor())

        val columnLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(0, dp(PanelMetrics.COLUMN_TOP_DP), dp(PanelMetrics.ANCHOR_MARGIN_END_DP), 0) }
        }
        nodes.forEachIndexed { index, node ->
            columnLayout.addView(makeColumnButton(node))
            if (node is GroupNode<T> && node.display == GroupDisplay.INLINE_ROW) {
                root.addView(makeInlineRow(node, index))
            }
        }
        columnLayout.visibility = View.GONE
        root.addView(columnLayout)
        column = columnLayout
    }

    /** Shows/hides the tree parts the state selects; called after every navigation change. */
    fun applyNavigation(state: PanelNavState) {
        column?.visibility = if (state.expanded) View.VISIBLE else View.GONE
        for ((groupId, row) in rowViews) {
            row.visibility = if (groupId in state.openInlineGroups) View.VISIBLE else View.GONE
        }
    }

    private fun makeAnchor(): View {
        val anchor = factory.makeAnchorButton(anchorContentDescription)
        anchor.view.layoutParams = FrameLayout.LayoutParams(
            dp(PanelMetrics.BUTTON_SIZE_DP), dp(PanelMetrics.BUTTON_SIZE_DP), Gravity.TOP or Gravity.END
        ).apply { setMargins(0, dp(PanelMetrics.ANCHOR_MARGIN_TOP_DP), dp(PanelMetrics.ANCHOR_MARGIN_END_DP), 0) }
        anchor.view.setOnClickListener { onAnchorTap?.invoke() }
        anchorButton = anchor
        return anchor.view
    }

    private fun makeColumnButton(node: PanelNode<T>): View {
        val button = factory.makeIconButton(node.iconRes, label(node))
        button.view.layoutParams = LinearLayout.LayoutParams(
            dp(PanelMetrics.BUTTON_SIZE_DP), dp(PanelMetrics.BUTTON_SIZE_DP)
        ).apply { bottomMargin = dp(PanelMetrics.GAP_DP) }
        wireTaps(button, node)
        buttonViews[node.id] = button
        return button.view
    }

    /**
     * An INLINE_ROW group's children laid out horizontally, level with the group's own button
     * ([buttonIndex] in the column), one standard gap left of it.
     */
    private fun makeInlineRow(group: GroupNode<T>, buttonIndex: Int): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(0, dp(PanelMetrics.inlineRowTopDp(buttonIndex)), dp(PanelMetrics.INLINE_ROW_END_DP), 0) }
        }
        for (child in group.children) {
            val button = factory.makeIconButton(child.iconRes, label(child))
            button.view.layoutParams = LinearLayout.LayoutParams(
                dp(PanelMetrics.BUTTON_SIZE_DP), dp(PanelMetrics.BUTTON_SIZE_DP)
            ).apply { marginEnd = dp(PanelMetrics.GAP_DP) }
            wireTaps(button, child)
            row.addView(button.view)
            buttonViews[child.id] = button
        }
        row.visibility = View.GONE
        rowViews[group.id] = row
        return row
    }

    private fun wireTaps(button: RoundButton, node: PanelNode<T>) {
        when (node) {
            is ActionNode<T> -> button.view.setOnClickListener { onActionTap?.invoke(node) }
            is GroupNode<T> -> button.view.setOnClickListener { onGroupTap?.invoke(node) }
            is PageNode<T> -> button.view.setOnClickListener { onPageTap?.invoke(node) }
        }
    }
}
