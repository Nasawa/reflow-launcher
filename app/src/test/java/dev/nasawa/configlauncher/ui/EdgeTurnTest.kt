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

    @Test fun theZonesStayOutOfTheBackGestureStrips() {
        // #333 review (d): 60 px Back-gesture strips on both sides (gesture navigation).
        fun g(x: Float, rtl: Boolean = false) = EdgeTurn.direction(Offset(x, 1000f), window, Rect.Zero, false, 48f, rtl, 60f, 60f)
        assertEquals("inside the left strip: the system's", 0, g(10f)); assertEquals(0, g(59f))
        assertEquals("the zone starts at the strip's inner edge", -1, g(60f)); assertEquals(-1, g(107f))
        assertEquals(0, g(108f))
        assertEquals(1, g(892f)); assertEquals(1, g(939f))
        assertEquals("inside the right strip", 0, g(940f)); assertEquals(0, g(999f))
        assertEquals("RTL flips the zones, not the strips", 1, g(70f, rtl = true))
        assertEquals(-1, g(900f, rtl = true))
        // With the end rail, the strip and the rail both stay out of the zone.
        val rail = Rect(900f, 100f, 940f, 1900f)
        assertEquals(1, EdgeTurn.direction(Offset(870f, 1000f), window, rail, true, 48f, false, 60f, 60f))
        assertEquals(0, EdgeTurn.direction(Offset(950f, 1000f), window, rail, true, 48f, false, 60f, 60f))
    }
}
