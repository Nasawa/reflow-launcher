package dev.nasawa.configlauncher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/** #328 ux.md §4.2 / §1.5: the window-edge page-turn zones, pure (no clock, no Compose). */
class EdgeTurnTest {
    private val window = Rect(0f, 0f, 1000f, 2000f)
    private val bottomDock = Rect(16f, 1800f, 984f, 1950f)
    private fun dir(x: Float, y: Float = 1000f, rtl: Boolean = false, dock: Rect = bottomDock, vertical: Boolean = false) =
        EdgeTurn.direction(Offset(x, y), window, dock, vertical, 48f, rtl)

    @Test fun ltrLeftIsPreviousRightIsNext() {
        assertEquals(-1, dir(0f)); assertEquals(-1, dir(47f))
        assertEquals(0, dir(48f)); assertEquals(0, dir(500f)); assertEquals(0, dir(951f))
        assertEquals(1, dir(952f)); assertEquals(1, dir(999f))
    }

    @Test fun rtlFlipsTheEdges() {
        assertEquals("right edge = start = previous in RTL", -1, dir(990f, rtl = true))
        assertEquals("left edge = end = next in RTL", 1, dir(10f, rtl = true))
        assertEquals(0, dir(500f, rtl = true))
    }

    @Test fun theWindowEdgeNotTheGrid() {
        // A grid block centred with wide §1.5 margins: the zone is still the window's outer 48 px.
        assertEquals(0, dir(100f)); assertEquals(1, dir(990f))
    }

    @Test fun theDockStaysADropTarget() {
        assertEquals("bottom dock at the edge: drop into the dock, no turn", 0, dir(970f, y = 1900f))
        assertEquals("beside the dock, at the window edge", 1, dir(995f, y = 1900f))
    }

    @Test fun withTheEndRailTheEndZoneIsOnItsInnerSide() {
        val rail = Rect(900f, 100f, 1000f, 1900f)
        assertEquals("over the rail: a dock slot", 0, dir(950f, dock = rail, vertical = true))
        assertEquals("just inside the rail", 1, dir(870f, dock = rail, vertical = true))
        assertEquals(0, dir(840f, dock = rail, vertical = true))
        assertEquals("start edge unchanged", -1, dir(10f, dock = rail, vertical = true))
        val rtlRail = Rect(0f, 100f, 100f, 1900f)
        assertEquals("RTL: the rail is on the left, the end", 1, dir(130f, dock = rtlRail, vertical = true, rtl = true))
        assertEquals(0, dir(50f, dock = rtlRail, vertical = true, rtl = true))
        assertEquals(-1, dir(990f, dock = rtlRail, vertical = true, rtl = true))
    }

    @Test fun nothingBeforeLayoutOrOutsideTheWindow() {
        assertEquals(0, EdgeTurn.direction(Offset(1f, 1f), Rect.Zero, Rect.Zero, false, 48f, false))
        assertEquals(0, EdgeTurn.direction(Offset(1f, 1f), window, Rect.Zero, false, 0f, false))
        assertEquals(0, dir(5f, y = 2100f))
    }

    /** #337 follow-up: a phone at 2.75 density, 1080 px wide; the zone is exactly 24 dp = 66 px from each physical edge. */
    private val d = 2.75f
    private val z = EDGE_ZONE_DP * d
    private val phone = Rect(0f, 0f, 1080f, 2400f)
    private fun p(x: Float, rtl: Boolean = false, dock: Rect = Rect.Zero, vertical: Boolean = false) =
        EdgeTurn.direction(Offset(x, 1200f), phone, dock, vertical, z, rtl)

    @Test fun theZoneIsExactly24dpFromThePhysicalEdge() {
        assertEquals(24, EDGE_ZONE_DP); assertEquals(66f, z, 0f)
        assertEquals("pinned at the very left edge", -1, p(0f)); assertEquals("a pixel past the left edge", -1, p(-1f))
        assertEquals(-1, p(65.9f)); assertEquals("24 dp is the boundary", 0, p(66f)); assertEquals("24 dp + 1 dp", 0, p(66f + d))
        assertEquals("24 dp + 1 px", 0, p(67f))
        assertEquals("24 dp + 1 dp from the right", 0, p(1080f - 66f - d)); assertEquals(0, p(1013.9f)); assertEquals(1, p(1014f))
        assertEquals("pinned at the very right edge", 1, p(1079.5f)); assertEquals("past the right edge", 1, p(1080f))
        assertEquals(1, p(1081f))
    }

    @Test fun theFixedZoneFlipsInRtl() {
        assertEquals("RTL: left edge = end = next", 1, p(0f, rtl = true)); assertEquals(1, p(-1f, rtl = true))
        assertEquals(0, p(66f + d, rtl = true)); assertEquals(0, p(1080f - 66f - d, rtl = true))
        assertEquals("RTL: right edge = start = previous", -1, p(1079.5f, rtl = true)); assertEquals(-1, p(1080f, rtl = true))
    }

    @Test fun theRailKeeps24dpOnItsInnerSide() {
        val rail = Rect(860f, 100f, 1080f, 2300f)
        assertEquals("over the rail: a dock slot", 0, p(1000f, dock = rail, vertical = true))
        assertEquals(1, p(860f - 66f, dock = rail, vertical = true)); assertEquals(1, p(859f, dock = rail, vertical = true))
        assertEquals(0, p(860f - 66f - d, dock = rail, vertical = true))
        assertEquals("start edge: 24 dp from the physical edge", -1, p(0f, dock = rail, vertical = true))
        assertEquals(0, p(66f + d, dock = rail, vertical = true))
        val rtlRail = Rect(0f, 100f, 220f, 2300f)
        assertEquals(1, p(220f + 65f, dock = rtlRail, vertical = true, rtl = true))
        assertEquals(0, p(220f + 66f + d, dock = rtlRail, vertical = true, rtl = true))
        assertEquals(-1, p(1080f, dock = rtlRail, vertical = true, rtl = true))
    }

    @Test fun aFiveColumnOuterCellCentreDoesNotTurn() {
        // The narrowest common phone, 360 dp at density 3 (1080 px), Compact, 5 columns: 72 dp cells, outer centres at
        // 36 dp from each edge. The old strip + 24 dp zone (48–60 dp) covered them; 24 dp must not.
        for ((wDp, dens) in listOf(360f to 3f, 393f to 2.75f, 411f to 2.625f)) {
            val pitch = HomeGeometry.pitch(wDp, 640f, 5, 6, HomeGeometry.WidthClass.Compact)
            val wPx = wDp * dens
            val win = Rect(0f, 0f, wPx, 2400f)
            val centre = pitch.pitchW / 2f * dens
            for (rtl in listOf(false, true)) {
                assertEquals("$wDp dp start-column centre", 0, EdgeTurn.direction(Offset(centre, 1200f), win, Rect.Zero, false, EDGE_ZONE_DP * dens, rtl))
                assertEquals("$wDp dp end-column centre", 0, EdgeTurn.direction(Offset(wPx - centre, 1200f), win, Rect.Zero, false, EDGE_ZONE_DP * dens, rtl))
            }
        }
    }
}
