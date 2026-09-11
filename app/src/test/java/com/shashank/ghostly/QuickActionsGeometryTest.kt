package com.shashank.ghostly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quick-menu's sizing arithmetic.
 *
 * The service asks [QuickActionsView.widthPx]/[QuickActionsView.heightPx] how big to make the
 * overlay window, and the view then lays the row out with [QuickActionsView.centreXPx]. If those
 * two ever disagree the window silently clips whatever falls outside it — the last button loses its
 * right-hand edge, or a label loses its descenders — and it looks like a drawing bug rather than a
 * sizing one. So the row is pinned to the window here, at the densities real phones actually use.
 */
class QuickActionsGeometryTest {

    private val densities = floatArrayOf(1f, 1.5f, 2f, 2.625f, 3f, 3.5f)

    @Test
    fun `an empty row asks for no window at all`() {
        for (d in densities) assertEquals(0, QuickActionsView.widthPx(0, d))
    }

    @Test
    fun `the row fits its window exactly, at every density`() {
        for (d in densities) {
            for (count in 1..6) {
                val width = QuickActionsView.widthPx(count, d)
                val r = QuickActionsView.BUTTON_DP * d / 2f
                val pad = QuickActionsView.PAD_DP * d

                val firstEdge = QuickActionsView.centreXPx(0, d) - r
                assertEquals("left inset at ${d}x", pad, firstEdge, 0.01f)

                // A pixel of slack is the floor() in widthPx, not a layout mistake; two would be.
                val lastEdge = QuickActionsView.centreXPx(count - 1, d) + r
                assertTrue("$count buttons overrun the window at ${d}x", lastEdge <= width - pad + 1f)
                assertTrue("$count buttons rattle in the window at ${d}x", lastEdge >= width - pad - 1f)
            }
        }
    }

    @Test
    fun `neighbouring buttons keep their gap and never touch`() {
        for (d in densities) {
            val r = QuickActionsView.BUTTON_DP * d / 2f
            val gap = QuickActionsView.centreXPx(1, d) - QuickActionsView.centreXPx(0, d) - r * 2f
            assertEquals("gap at ${d}x", QuickActionsView.GAP_DP * d, gap, 0.01f)
        }
    }

    @Test
    fun `the window is tall enough for the label under the buttons`() {
        for (d in densities) {
            val height = QuickActionsView.heightPx(d)
            val stack = (QuickActionsView.PAD_DP + QuickActionsView.BUTTON_DP +
                QuickActionsView.LABEL_GAP_DP + QuickActionsView.LABEL_LINE_DP +
                QuickActionsView.PAD_DP) * d
            assertTrue("the label is clipped at ${d}x", height >= stack - 1f)
        }
    }

    @Test
    fun `a row of buttons is wider than it is tall`() {
        // Not cosmetic: the service anchors this beside the ghost, and a row that came out taller
        // than wide would be a column, which is a different menu than the one this draws.
        for (d in densities) {
            assertTrue(QuickActionsView.widthPx(3, d) > QuickActionsView.heightPx(d))
        }
    }
}
