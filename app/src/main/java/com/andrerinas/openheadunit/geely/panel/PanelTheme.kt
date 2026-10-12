package com.andrerinas.openheadunit.geely.panel

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View

/**
 * Day/night theming and status painting of the panel (F010), moved verbatim from the old
 * HvacPanelController: the circle surfaces flip with the projection's night mode (the same
 * signal that switches Android Auto's top bar), the status palette itself stays readable on
 * both. The night flip is latched per paint cycle — [finishPaintCycle] records it once every
 * button has been (re)painted, so the background re-issue happens exactly once per flip.
 */
class PanelTheme {

    /** Current projection theme; set by the coordinator before/while painting. */
    var isNight: Boolean = false

    private var paintedNight: Boolean? = null

    /** The circle surface: white by day, the AA top-bar dark by night, ripple masked to the oval. */
    fun circleBackground(): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(PanelMetrics.RIPPLE_COLOR),
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isNight) PanelMetrics.COLOR_CIRCLE_DARK else PanelMetrics.COLOR_CIRCLE)
        },
        // Oval mask: the ripple fills the circle from the touch point, never a square.
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
    )

    /** The status palette: gray off, green/orange/red for levels 1/2/3. */
    fun stateColor(level: Int, active: Boolean): Int = when {
        level >= 3 -> PanelMetrics.COLOR_LEVEL3
        level == 2 -> PanelMetrics.COLOR_LEVEL2
        level == 1 || active -> PanelMetrics.COLOR_LEVEL1
        else -> PanelMetrics.COLOR_OFF
    }

    /** Recolors one button from its display state; main thread only. */
    fun paintButton(button: RoundButton, level: Int, active: Boolean, auto: Boolean) {
        // Follow the theme: the circle surface and the badge ink flip with day/night, the
        // status palette itself stays readable on both.
        if (paintedNight != isNight) button.view.background = circleBackground()
        button.icon?.setColorFilter(stateColor(level, active))
        button.badge.visibility = if (auto) View.VISIBLE else View.GONE
        button.badge.text = PanelMetrics.AUTO_BADGE
        button.badge.setTextColor(if (isNight) PanelMetrics.COLOR_TEXT_ON_DARK else PanelMetrics.COLOR_TEXT)
    }

    /** The anchor is not status-driven: only its theme surface (and the mark's tint) follow. */
    fun paintAnchor(anchor: RoundButton) {
        if (paintedNight != isNight) anchor.view.background = circleBackground()
        anchor.icon?.setColorFilter(if (isNight) PanelMetrics.COLOR_TEXT_ON_DARK else PanelMetrics.COLOR_TEXT)
    }

    /** Latches the theme flip after a paint cycle, so backgrounds re-issue only once. */
    fun finishPaintCycle() {
        paintedNight = isNight
    }
}
