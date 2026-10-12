package com.andrerinas.openheadunit.geely.panel

/**
 * The declarative node model of the panel (F010): the UI shell renders whatever tree it is
 * given — an ACTION button bound to a car function, a GROUP that opens its children (today
 * as an inline expansion row next to the column; LIST/PAGE variants are the future
 * "anchor -> categories -> windows" navigation), or a PAGE placeholder that opens a custom
 * window (the seam: the shell resolves [PageNode.pageId] through a page factory when the
 * first real window exists — nothing renders pages yet).
 *
 * The shell stays domain-agnostic by being generic over the action payload [T]; the HVAC
 * panel instantiates it with CarFunctionSpec (HvacPanelSpec). Node files carry only resource
 * ids (ints), so the model stays unit-testable on the JVM.
 */
sealed class PanelNode<out T> {
    abstract val id: String
    abstract val iconRes: Int
    abstract val labelRes: Int
}

/** A leaf button bound to a car function of type [T]. */
class ActionNode<T>(
    override val id: String,
    override val iconRes: Int,
    override val labelRes: Int,
    val function: T,
) : PanelNode<T>()

/**
 * A button that opens its [children]. How they open is [display]: INLINE_ROW lays them out
 * beside the column at the group button's height (the seat zones today); LIST replaces the
 * column with the child list; PAGE opens them as a window — the two latter are future
 * navigation flavors the shell already models but does not render yet.
 */
class GroupNode<T>(
    override val id: String,
    override val iconRes: Int,
    override val labelRes: Int,
    val children: List<ActionNode<T>>,
    val display: GroupDisplay = GroupDisplay.INLINE_ROW,
) : PanelNode<T>()

/** How a [GroupNode] presents its children. */
enum class GroupDisplay { INLINE_ROW, LIST, PAGE }

/**
 * A button that opens a custom window identified by [pageId] — the extension seam: a future
 * feature registers a page factory for the id and the shell opens the window; until then the
 * node type exists so trees can already carry it.
 */
class PageNode<T>(
    override val id: String,
    override val iconRes: Int,
    override val labelRes: Int,
    val pageId: String,
) : PanelNode<T>()
