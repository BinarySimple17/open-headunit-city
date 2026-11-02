package com.andrerinas.openheadunit.geely.panel

/**
 * The panel's geometry and palette (F010), moved verbatim from the old HvacPanelController
 * companion so the shell files share one source. Metrics are matched to the phone's
 * projection top bar (measured off the emulator frame, 320dpi): the standard circles are
 * 79dp with a 21dp gap, their row centers at 73dp — so the anchor sits in that row, one
 * standard gap right of the bar's last button, and the dropdown uses the same size/spacing.
 */
object PanelMetrics {

    const val BUTTON_SIZE_DP = 79
    const val ICON_SIZE_DP = 44
    const val GAP_DP = 21
    const val ANCHOR_MARGIN_TOP_DP = 34
    const val ANCHOR_MARGIN_END_DP = 34

    /** The dropdown column starts one button+gap below the anchor. */
    const val COLUMN_TOP_DP = ANCHOR_MARGIN_TOP_DP + BUTTON_SIZE_DP + GAP_DP

    /** An inline group row sits left of the column, one button+gap away. */
    const val INLINE_ROW_END_DP = ANCHOR_MARGIN_END_DP + BUTTON_SIZE_DP + GAP_DP

    /**
     * Room below the last row of buttons for the elevation shadow to fade out in. The parent
     * clips root's drawing to root's own bounds; the column's last button also carries the
     * 21dp gap margin, so the anchor (no margin below it) needs this padding to keep its
     * shadow — at pressed 14dp the smear reaches well past 12dp.
     */
    const val SHADOW_ROOM_DP = 24

    const val REST_ELEVATION_DP = 8
    const val PRESSED_ELEVATION_DP = 14


    /** The status palette: standard text, off, and one color per level. */
    const val COLOR_TEXT = 0xFF212121.toInt()
    const val COLOR_OFF = 0xFF8B8C8B.toInt()
    const val COLOR_LEVEL1 = 0xFF37AB2C.toInt()
    const val COLOR_LEVEL2 = 0xFFD9A025.toInt()
    const val COLOR_LEVEL3 = 0xFFD94025.toInt()
    const val COLOR_CIRCLE = 0xFFFFFFFF.toInt()

    /** Dark theme: the standard AA top-bar circle (#131214, measured off the projection frame). */
    const val COLOR_CIRCLE_DARK = 0xFF131214.toInt()
    const val COLOR_TEXT_ON_DARK = 0xFFE8EAED.toInt()
    const val RIPPLE_COLOR = 0x30000000

    /** Top offset of an inline group row whose button sits at [buttonIndex] in the column. */
    fun inlineRowTopDp(buttonIndex: Int): Int = COLUMN_TOP_DP + buttonIndex * (BUTTON_SIZE_DP + GAP_DP)
}
