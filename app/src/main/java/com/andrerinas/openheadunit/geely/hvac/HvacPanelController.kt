package com.andrerinas.openheadunit.geely.hvac

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.geely.panel.ActionNode
import com.andrerinas.openheadunit.geely.panel.GroupNode
import com.andrerinas.openheadunit.geely.panel.PanelNavState
import com.andrerinas.openheadunit.geely.panel.PanelNavigation
import com.andrerinas.openheadunit.geely.panel.PanelNode
import com.andrerinas.openheadunit.geely.panel.PanelSurface
import com.andrerinas.openheadunit.geely.panel.PanelTheme
import java.util.concurrent.Executors

/**
 * The on-screen climate panel over the AA projection (F005) — the coordinator (F010): owns
 * the lifecycle and wires the parts; the parts own the behavior. The button tree is a
 * declarative spec ([HvacPanelSpec.root] over the [GeelyCarFunctions] registry) rendered by
 * the domain-agnostic shell (geely/panel); [PanelStatusStore] drives the 2-second status
 * cycle and optimistic writes; [CarCommandRunner] executes taps/long-presses by function
 * kind. Look and behavior match the panel's established design, unchanged by F010.
 *
 * Built after OverlayTouchView in R.id.container (the renderer-confirm-banner pattern):
 * buttons consume their own taps so nothing leaks into the phone, while every other touch
 * still reaches the overlay (the panel containers are not clickable). The panel re-raises
 * the loading overlay after inserting itself. All car traffic runs on a single background
 * executor and lands back on the main thread; an unavailable car service means the panel
 * never appears, with the reason in [HvacLog].
 */
class HvacPanelController private constructor(
    private val context: Context,
    private val settings: HvacSettings,
) {

    private val theme = PanelTheme()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val portExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hvac-port").apply { isDaemon = true }
    }

    // The effective backend for this session: the adaptapi port, the standard android.car
    // port or, in debug builds without either, the standalone mock-car app (F006/D-007).
    private var port: CarFunctionPort = AndroidCarPort
    private var createdMockPort: AidlMockCarPort? = null

    private var surface: PanelSurface<CarFunctionSpec>? = null
    private var specRoot: List<PanelNode<CarFunctionSpec>> = emptyList()
    private var store: PanelStatusStore? = null
    private var navState = PanelNavState()
    private var detached = false
    private var pipHidden = false

    private val autoCollapseRunnable = Runnable { collapse() }

    /** Day/night of the projection: initial guess at [beginAttach], then the live broadcast. */
    private val nightMode = PanelNightMode(context, theme) { repaint() }


    /**
     * Resolves the car backend off the main thread (HvacPortFactory: adaptapi first, then
     * android.car, mock-car app in debug builds) and reads the first status snapshot before
     * anything is shown, so buttons are painted from real state at first frame. On failure
     * nothing is shown and the reason is in [HvacLog]. Safe to call from onCreate.
     */
    fun beginAttach(container: FrameLayout) {
        HvacLog.i("panel: attach requested (mode=${settings.panelMode}, model=${settings.carModel})")
        nightMode.start()
        portExecutor.execute {
            val resolved = HvacPortFactory.resolve(context)
            val initialStatus = resolved?.let { Hvac.readStatus(it, settings.carModel) }
            mainHandler.post {
                if (detached) return@post
                if (resolved != null && initialStatus != null) {
                    port = resolved
                    if (resolved is AidlMockCarPort) createdMockPort = resolved
                    HvacLog.i("panel: backend ready (${resolved.javaClass.simpleName}), showing panel")
                    buildPanel(container, initialStatus)
                } else {
                    HvacLog.i("panel: no car backend, panel stays hidden")
                }
            }
        }
    }

    fun detach() {
        detached = true
        mainHandler.removeCallbacksAndMessages(null)
        nightMode.stop()
        portExecutor.shutdownNow()
        createdMockPort?.disconnect()
        createdMockPort = null
        surface?.let { (it.root.parent as? ViewGroup)?.removeView(it.root) }
        surface = null
        store = null
        HvacLog.i("panel: detached")
    }

    /**
     * Wires the parts and shows the panel: status store (poll + optimistic writes), command
     * runner (kind dispatch), the spec tree rendered by the shell surface. Main thread, after
     * the backend resolved and the first snapshot was read.
     */
    private fun buildPanel(container: FrameLayout, initialStatus: Map<String, Int?>) {
        val statusStore = PanelStatusStore(port, settings.carModel, portExecutor, mainHandler) { detached }
        val commandRunner = CarCommandRunner(
            context, port, portExecutor, mainHandler, { detached }, statusStore,
        ) { spec ->
            if (spec.id == Hvac.KEY_WHEEL) settings.wheelAutoLevel else settings.seatAutoLevel
        }
        store = statusStore

        specRoot = HvacPanelSpec.root(settings.carModel)
        val panelSurface = PanelSurface<CarFunctionSpec>(
            context, theme,
            anchorIconRes = R.drawable.hvac_ic_car,
            anchorContentDescription = context.getString(R.string.hvac_handle),
            label = { node -> context.getString(node.labelRes) },
        )
        panelSurface.onAnchorTap = { if (navState.expanded) collapse() else expand(log = true) }
        panelSurface.onActionTap = { node ->
            renewAutoCollapseIfCollapsed()
            if (!commandRunner.isPending(node.id)) commandRunner.tap(node)
        }
        panelSurface.onActionLongPress = { node ->
            if (!commandRunner.isPending(node.id)) commandRunner.longPress(node)
        }
        // A zone with an auto verdict in flight: dimmed and tap-dead until the car answers
        // (confirmation or timeout) — the runner owns the state, the surface just shows it.
        commandRunner.onPendingChanged = { id, busy ->
            surface?.button(id)?.view?.alpha = if (busy) BUSY_BUTTON_ALPHA else 1f
        }
        panelSurface.actionLongPressEnabled = { it.function.auto != null }
        panelSurface.onGroupTap = { group -> onGroupTap(group) }
        panelSurface.build(specRoot)
        container.addView(panelSurface.root)
        surface = panelSurface

        // The panel must not cover the loading spinner or the perf HUD: re-raise both after
        // inserting ourselves (they use bringToFront/elevation, not child order).
        container.findViewById<View>(R.id.loading_overlay)?.bringToFront()

        statusStore.onSnapshot = { repaint() }
        statusStore.applyInitial(initialStatus)
        applyMode(initial = true)
        statusStore.startPolling()
    }

    /** PiP hides all chrome; restoring re-applies the configured panel mode. */
    fun setPipHidden(hidden: Boolean) {
        pipHidden = hidden
        surface?.let { it.root.visibility = if (hidden) View.GONE else View.VISIBLE }
        if (!hidden) {
            applyMode(initial = false)
            repaint()
        }
    }

    private fun applyMode(initial: Boolean) {
        if (initial && settings.panelMode == Hvac.PanelMode.ALWAYS) expand(log = true)
        else if (!initial) {
            if (settings.panelMode == Hvac.PanelMode.ALWAYS) expand(log = true) else collapse()
        }
    }



    private fun expand(log: Boolean) {
        if (detached || pipHidden) return
        if (log) HvacLog.i("panel: expand")
        navState = PanelNavigation.expand(navState)
        surface?.applyNavigation(navState)
        // Only the collapsed mode self-hides; "always visible" stays expanded until the
        // anchor is tapped again (the established start-behavior order).
        if (settings.panelMode == Hvac.PanelMode.COLLAPSED) renewAutoCollapse()
    }

    private fun collapse() {
        if (detached) return
        HvacLog.i("panel: collapse")
        navState = PanelNavigation.collapse(navState)
        surface?.applyNavigation(navState)
        mainHandler.removeCallbacks(autoCollapseRunnable)
    }

    private fun renewAutoCollapseIfCollapsed() {
        if (settings.panelMode == Hvac.PanelMode.COLLAPSED) renewAutoCollapse()
    }

    private fun renewAutoCollapse() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        mainHandler.postDelayed(autoCollapseRunnable, settings.hideTimeoutSec * 1000L)
    }

    private fun onGroupTap(group: GroupNode<CarFunctionSpec>) {
        renewAutoCollapseIfCollapsed()
        navState = PanelNavigation.toggleInlineGroup(navState, group.id)
        HvacLog.i("panel: ${group.id} row ${if (group.id in navState.openInlineGroups) "shown" else "hidden"}")
        surface?.applyNavigation(navState)
    }



    /** Recolors every button from the latest snapshot; main thread only. */
    private fun repaint() {
        val status = store?.lastStatus ?: return
        for (node in specRoot) paintNode(node, status)
        surface?.anchor()?.let { theme.paintAnchor(it) }
        theme.finishPaintCycle()
    }

    private fun paintNode(node: PanelNode<CarFunctionSpec>, status: Map<String, Int?>) {
        when (node) {
            is GroupNode -> {
                // The group button shows the strongest of its zones (level) and any auto.
                var level = 0
                var anyAuto = false
                for (child in node.children) {
                    val f = child.function
                    val autoSpec = f.auto
                    val manual = f.levelOf(status[f.id])
                    val autoLevel = autoSpec?.levelOf(status[autoSpec.id]) ?: 0
                    level = maxOf(level, Hvac.displayLevel(manual, autoLevel))
                    if (f.autoIndicatorOn(autoSpec?.let { status[it.id] })) anyAuto = true
                    paintAction(child, status)
                }
                surface?.button(node.id)?.let { theme.paintButton(it, level, level > 0, anyAuto) }
            }
            is ActionNode -> paintAction(node, status)
            else -> surface?.button(node.id)?.let { theme.paintButton(it, 0, active = false, auto = false) }
        }
    }

    /**
     * Effective level (the display scheme): while a zone runs in auto, the color follows
     * the AUTO level — the main property answers the auto read code then, which must never
     * fold into a fake manual L3 (the "configured 1, shown 3" report). The "A" mark follows
     * the auto indicator, which for a switch-style auto (the wheel) is the switch's on-value.
     */
    private fun paintAction(node: ActionNode<CarFunctionSpec>, status: Map<String, Int?>) {
        val f = node.function
        val manualRaw = status[f.id]
        val autoRaw = f.auto?.let { status[it.id] }
        val level = f.displayLevel(manualRaw, autoRaw)
        val active = level > 0 || f.isActive(manualRaw)
        val autoOn = f.autoIndicatorOn(autoRaw)
        surface?.button(node.id)?.let { theme.paintButton(it, level, active, autoOn) }
    }


    companion object {
        /** A zone button with an auto verdict in flight: dimmed until the car answers. */
        private const val BUSY_BUTTON_ALPHA = 0.4f

        /**
         * Entry point for the projection activity. Always returns a controller (so the caller
         * can detach it); whether any view appears is decided by the car bridge availability
         * and is logged either way.
         */
        fun attach(context: Context, container: FrameLayout): HvacPanelController {
            val settings = HvacSettings(context)
            if (settings.debugLog && !HvacLog.isEnabled) HvacLog.start(context, settings.carModel)
            val controller = HvacPanelController(context, settings)
            controller.beginAttach(container)
            return controller
        }
    }
}
