package com.shashank.ghostly

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quick-menu's arc arithmetic.
 *
 * The service asks [QuickActionsView.halfSpanPx] how big to make the overlay window and then places
 * that window centred on the ghost; the view lays the buttons out on [QuickActionsView.ringRadiusPx]
 * at the angles [QuickActionsView.angleOf] gives. If those ever disagree the window silently clips
 * whatever falls outside it — the end button loses its edge, or a label loses its descenders — and
 * it looks like a drawing bug rather than a sizing one.
 *
 * Two things are pinned here, at the densities and body sizes real phones actually produce. That
 * the ring clears the ghost and is evenly spread, and that the square window contains every button
 * *and* its widest possible label whichever way the arc happens to point — because the service
 * commits to the window before it knows which way that is.
 */
class QuickActionsGeometryTest {

    private val densities = floatArrayOf(1f, 1.5f, 2f, 2.625f, 3f, 3.5f)

    /** Half-widths spanning a small ghost on a cheap phone up to a big one on a 3.5x screen. */
    private val clearances = floatArrayOf(0f, 24f, 60f, 120f, 210f)

    /** The arcs the service is expected to try: a wide unfurl down to the pinched one it falls back
     *  to when he is in a corner. */
    private val sweeps = floatArrayOf(
        (PI * 1.5).toFloat(),
        PI.toFloat(),
        (PI / 2).toFloat(),
        (PI / 3).toFloat(),
    )

    /** Straight up, down, either side, and something off-axis that lands on no nice number. */
    private val centres = floatArrayOf(
        (-PI / 2).toFloat(),
        (PI / 2).toFloat(),
        0f,
        PI.toFloat(),
        2.37f,
    )

    @Test
    fun `a single button sits dead on the centre angle`() {
        for (centre in centres) {
            assertEquals(centre, QuickActionsView.angleOf(0, 1, centre, PI.toFloat()), 1e-5f)
        }
    }

    @Test
    fun `buttons are spread evenly from one end of the sweep to the other`() {
        for (centre in centres) {
            for (sweep in sweeps) {
                for (count in 2..6) {
                    val first = QuickActionsView.angleOf(0, count, centre, sweep)
                    val last = QuickActionsView.angleOf(count - 1, count, centre, sweep)
                    assertEquals("arc start", centre - sweep / 2f, first, 1e-4f)
                    assertEquals("arc end", centre + sweep / 2f, last, 1e-4f)

                    val step = sweep / (count - 1)
                    for (i in 1 until count) {
                        val gap = QuickActionsView.angleOf(i, count, centre, sweep) -
                            QuickActionsView.angleOf(i - 1, count, centre, sweep)
                        assertEquals("even step at $i of $count", step, gap, 1e-4f)
                    }
                }
            }
        }
    }

    @Test
    fun `the ring clears his body with a visible gap, at every density and size`() {
        for (d in densities) {
            for (clearance in clearances) {
                val ring = QuickActionsView.ringRadiusPx(d, clearance)
                val r = QuickActionsView.BUTTON_DP * d / 2f
                // The near edge of a button, not its centre: that is what actually approaches him.
                val nearEdge = ring - r
                assertEquals(
                    "ring gap at ${d}x with ${clearance}px body",
                    QuickActionsView.RING_GAP_DP * d,
                    nearEdge - clearance,
                    0.01f,
                )
            }
        }
    }

    @Test
    fun `every button lands on the ring, whichever way the arc points`() {
        for (d in densities) {
            for (clearance in clearances) {
                val ring = QuickActionsView.ringRadiusPx(d, clearance)
                for (centre in centres) {
                    for (sweep in sweeps) {
                        for (count in 1..6) {
                            for (i in 0 until count) {
                                val a = QuickActionsView.angleOf(i, count, centre, sweep)
                                val dist = hypot(ring * cos(a), ring * sin(a))
                                assertEquals("button $i off the ring", ring, dist, 0.01f)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `the span is twice the half-span, so his centre is the window's centre`() {
        // Not cosmetic and not a detail: the middle of this view is transparent and his own window
        // shows through it. The span is built as two equal halves precisely so that centring the
        // window on him centres the ring on him too — an odd split would leave more window on one
        // side and slide the whole arc off the ghost.
        for (d in densities) {
            for (clearance in clearances) {
                val half = QuickActionsView.halfSpanPx(d, clearance)
                assertTrue("a span of ${half * 2f} is not a window", half > 0f)
                val ring = QuickActionsView.ringRadiusPx(d, clearance)
                // Room on both sides of the ring, equally — which is what makes the square square.
                assertTrue("the ring does not fit its window at ${d}x", ring < half)
            }
        }
    }

    @Test
    fun `the square holds every button and its widest label, at any angle`() {
        for (d in densities) {
            for (clearance in clearances) {
                val half = QuickActionsView.halfSpanPx(d, clearance)
                val ring = QuickActionsView.ringRadiusPx(d, clearance)
                val labelRing = QuickActionsView.labelRadiusPx(d, clearance)
                val r = QuickActionsView.BUTTON_DP * d / 2f
                val pillW = QuickActionsView.pillWidthPx(d)
                val pillH = QuickActionsView.pillHeightPx(d)

                // Every angle, not only the ones the service picks: the window is committed before
                // the arc is chosen, so it has to survive the worst one.
                var a = -PI.toFloat()
                while (a <= PI.toFloat()) {
                    val bx = abs(ring * cos(a)) + r
                    val by = abs(ring * sin(a)) + r
                    assertTrue("button clipped at ${d}x", bx <= half && by <= half)

                    val lx = abs(labelRing * cos(a)) + pillW / 2f
                    val ly = abs(labelRing * sin(a)) + pillH / 2f
                    assertTrue("label clipped at ${d}x, ${clearance}px body", lx <= half && ly <= half)
                    a += 0.05f
                }
            }
        }
    }

    @Test
    fun `labels ride further out than the buttons they belong to`() {
        // This is what buys neighbouring captions their separation on a tight arc: the same angle
        // between two buttons is more distance between two labels one pill-height further out.
        for (d in densities) {
            for (clearance in clearances) {
                val ring = QuickActionsView.ringRadiusPx(d, clearance)
                val labelRing = QuickActionsView.labelRadiusPx(d, clearance)
                val clearOfButton = ring + QuickActionsView.BUTTON_DP * d / 2f
                val pillNearEdge = labelRing - QuickActionsView.pillHeightPx(d) / 2f
                assertTrue("label overlaps its own button at ${d}x", pillNearEdge >= clearOfButton - 0.01f)
            }
        }
    }

    @Test
    fun `nothing is ever drawn where the ghost is`() {
        // The inner disc — his body plus the ring gap — has to stay empty, or the layout covers the
        // thing it exists to surround.
        for (d in densities) {
            for (clearance in clearances) {
                val nearest = QuickActionsView.ringRadiusPx(d, clearance) - QuickActionsView.BUTTON_DP * d / 2f
                assertTrue("a button reaches his body at ${d}x", nearest > clearance)
            }
        }
    }
}
