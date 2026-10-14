package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.utils.SystemOptimizer.DisplaySizePreset

/**
 * Android Auto's design guide says a head unit MUST offer at least 748x450 dp; a canvas below it
 * gets a reduced layout. This decides the density that goes on the wire.
 */
object AnnouncedDensityPolicy {

    const val HUIG_MIN_WIDTH_DP = 748
    const val HUIG_MIN_HEIGHT_DP = 450
    /** Android Auto uses its widescreen layout from a 1240 dp wide canvas. */
    const val WIDESCREEN_MIN_WIDTH_DP = 1240
    private const val FALLBACK_DPI = 160

    enum class Reason { AS_STORED, AUTOMATIC, WIDTH_FLOOR, HEIGHT_FLOOR, PORTRAIT }

    data class Decision(val announced: Int, val requested: Int, val reason: Reason)

    /** The highest density that keeps the visible area at or above the floor. */
    fun huigMaxDpi(w: Int, h: Int): Int = minOf(w * 160 / HUIG_MIN_WIDTH_DP, h * 160 / HUIG_MIN_HEIGHT_DP)

    /** The highest density that keeps a codec of this width at or above the widescreen breakpoint. */
    fun widescreenMaxDpi(codecWidth: Int): Int =
        if (codecWidth <= 0) 0 else codecWidth * 160 / WIDESCREEN_MIN_WIDTH_DP

    /** The widescreen layout is a landscape layout, so a portrait or unknown codec gets 0. */
    fun widescreenHintDpi(codecWidth: Int, codecHeight: Int): Int =
        if (codecWidth <= 0 || codecHeight <= 0 || codecHeight > codecWidth) 0 else widescreenMaxDpi(codecWidth)

    fun canvasDp(px: Int, dpi: Int): Int = if (dpi <= 0) 0 else px * 160 / dpi

    /** The panel as the projection holds it: a settings screen can be held the other way round. */
    fun orientPanel(w: Int, h: Int, portrait: Boolean): Pair<Int, Int> = if ((h > w) == portrait) w to h else h to w

    /** Only an explicit portrait setting projects in portrait; every other choice previews landscape. */
    fun projectsPortrait(o: Settings.ScreenOrientation): Boolean =
        o == Settings.ScreenOrientation.PORTRAIT || o == Settings.ScreenOrientation.PORTRAIT_REVERSE

    /**
     * The area Android Auto draws in for a resolution on this panel: turned to the panel's
     * orientation, less the margins connect announces, with the same math as HeadUnitScreenConfig.
     */
    fun visibleSize(mode: Settings.VideoFitMode, panelW: Int, panelH: Int, resW: Int, resH: Int): Pair<Int, Int> {
        val (w, h) = if ((panelH > panelW) == (resH > resW)) resW to resH else resH to resW
        if (panelW <= 0 || panelH <= 0 || w <= 0 || h <= 0) return w to h
        if (MarginStrategyPolicy.select(mode, panelW, panelH, w, h) == MarginStrategyPolicy.Strategy.PAR) return w to h
        val scale = ProjectionGeometryPolicy.fit(panelW, panelH, w, h).scaleFactor
        return (w - ProjectionGeometryPolicy.widthMargin(w, panelW, scale)) to
            (h - ProjectionGeometryPolicy.heightMargin(h, panelH, scale))
    }

    fun decide(stored: Int, preset: DisplaySizePreset, visibleW: Int, visibleH: Int): Decision {
        if (visibleW <= 0 || visibleH <= 0) {
            return if (stored > 0) Decision(stored, stored, Reason.AS_STORED)
            else Decision(FALLBACK_DPI, 0, Reason.AUTOMATIC)
        }
        if (visibleH > visibleW) {
            val dpi = if (stored > 0) stored
            else OnboardingDisplayPolicy.recommendedDpi(preset, visibleW, visibleH, true)
            return Decision(dpi, stored, Reason.PORTRAIT)
        }
        if (stored <= 0) {
            return Decision(
                OnboardingDisplayPolicy.recommendedDpi(preset, visibleW, visibleH, false), 0, Reason.AUTOMATIC
            )
        }
        val max = huigMaxDpi(visibleW, visibleH)
        if (stored <= max) return Decision(stored, stored, Reason.AS_STORED)
        val heightBound = visibleH * 160 / HUIG_MIN_HEIGHT_DP < visibleW * 160 / HUIG_MIN_WIDTH_DP
        return Decision(max, stored, if (heightBound) Reason.HEIGHT_FLOOR else Reason.WIDTH_FLOOR)
    }

    /** The fallback keeps the primary's dp canvas: its margins scale by the same width ratio. */
    fun fallbackDensity(primaryDensity: Int, primaryWidth: Int, fallbackWidth: Int): Int =
        if (primaryWidth <= 0) primaryDensity
        else Math.round(primaryDensity.toFloat() * fallbackWidth / primaryWidth)

    /** The `Density is:` log text; the rig greps these exact strings. */
    fun describe(decision: Decision, visibleW: Int, visibleH: Int): String {
        val requested = if (decision.requested > 0) decision.requested.toString() else "automatic"
        val why = when (decision.reason) {
            Reason.AS_STORED -> "within the HUIG floor"
            Reason.AUTOMATIC -> "codec-based"
            Reason.WIDTH_FLOOR -> "clamped to the $HUIG_MIN_WIDTH_DP dp width floor"
            Reason.HEIGHT_FLOOR -> "clamped to the $HUIG_MIN_HEIGHT_DP dp height floor"
            Reason.PORTRAIT -> "portrait, not clamped"
        }
        return "${decision.announced} (dp canvas ${canvasDp(visibleW, decision.announced)}x" +
            "${canvasDp(visibleH, decision.announced)}, requested $requested, $why)"
    }
}
