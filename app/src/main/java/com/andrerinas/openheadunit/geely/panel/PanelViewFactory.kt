package com.andrerinas.openheadunit.geely.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Builds the panel's round buttons (F010), moved verbatim from the old HvacPanelController:
 * a white/dark circle carrying a tinted icon and an optional badge ("A" auto mark), Material
 * elevation with a press lift. Every button consumes its own tap — anything that slipped
 * through would reach OverlayTouchView and become a touch on the phone screen; the panel
 * CONTAINERS stay non-clickable on purpose (the touch-isolation contract).
 */
class RoundButton(val view: FrameLayout, val icon: ImageView?, val badge: TextView)

/** One shared [RoundButton] factory bound to a context, theme and anchor mark drawable. */
class PanelViewFactory(
    private val context: Context,
    private val theme: PanelTheme,
    private val anchorIconRes: Int,
) {

    fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    @SuppressLint("SetTextI18n")
    fun makeIconButton(iconRes: Int, contentDescription: String): RoundButton {
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(PanelMetrics.COLOR_OFF)
            layoutParams = FrameLayout.LayoutParams(dp(PanelMetrics.ICON_SIZE_DP), dp(PanelMetrics.ICON_SIZE_DP), Gravity.CENTER)
        }
        val badge = TextView(context).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(PanelMetrics.COLOR_TEXT)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(2) }
        }
        val box = FrameLayout(context).apply {
            this.contentDescription = contentDescription
            background = theme.circleBackground()
            // Material shadow: the oval background supplies the circular outline, so plain
            // elevation casts a round drop shadow; pressing lifts the button like a FAB.
            elevation = dp(PanelMetrics.REST_ELEVATION_DP).toFloat()
            setOnTouchListener { v, event ->
                v.elevation = dp(
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) PanelMetrics.PRESSED_ELEVATION_DP
                    else PanelMetrics.REST_ELEVATION_DP
                ).toFloat()
                false // visual only; the click listeners still handle the tap
            }
            // Consume the tap here; anything that slips through would reach OverlayTouchView
            // and become a touch on the phone screen.
            isClickable = true
            isFocusable = true
            isLongClickable = true
        }
        box.addView(icon)
        box.addView(badge)
        return RoundButton(box, icon, badge)
    }

    fun makeAnchorButton(contentDescription: String): RoundButton {
        // The anchor mark (a neutral car silhouette); tinted with the text color so
        // it follows the day/night theme like everything else.
        val icon = ImageView(context).apply {
            setImageResource(anchorIconRes)
            setColorFilter(if (theme.isNight) PanelMetrics.COLOR_TEXT_ON_DARK else PanelMetrics.COLOR_TEXT)
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(31), Gravity.CENTER)
        }
        val badge = TextView(context).apply { visibility = View.GONE }
        val box = FrameLayout(context).apply {
            this.contentDescription = contentDescription
            background = theme.circleBackground()
            elevation = dp(PanelMetrics.REST_ELEVATION_DP).toFloat()
            setOnTouchListener { v, event ->
                v.elevation = dp(
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) PanelMetrics.PRESSED_ELEVATION_DP
                    else PanelMetrics.REST_ELEVATION_DP
                ).toFloat()
                false
            }
            isClickable = true
            isFocusable = true
        }
        box.addView(icon)
        return RoundButton(box, icon, badge)
    }
}
