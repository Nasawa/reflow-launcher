package dev.nasawa.configlauncher.ui

import dev.nasawa.configlauncher.ui.ResizeMath.Handle
import dev.nasawa.configlauncher.ui.ResizeMath.Rect
import org.junit.Assert.*
import org.junit.Test

/** #304 NW-8: whole-cell snapping, min/max, taken cells and the grid edge for the drag-resize handles. */
class ResizeMathTest {
    private val any = ElementInfo.Limits(1, 4, 1, 5)
    private val allFree = { _: Int, _: Int, _: Int, _: Int -> true }
    /** Taken cells on a 4×5 grid. */
    private fun freeExcept(vararg taken: Pair<Int, Int>) = { x: Int, y: Int, w: Int, h: Int ->
        taken.none { (tx, ty) -> tx in x until x + w && ty in y until y + h }
    }
    private fun rs(p: Rect, h: Handle, dc: Int, dr: Int, b: ElementInfo.Limits = any, free: (Int, Int, Int, Int) -> Boolean = allFree) =
        ResizeMath.resize(p, h, dc, dr, b, 4, 5, free)

    @Test fun handlesFollowTheAxesThatCanResize() {
        assertEquals(8, ResizeMath.handles(any).size)
        assertEquals(listOf(Handle.START, Handle.END), ResizeMath.handles(ElementInfo.Limits(1, 4, 2, 2)))
        assertEquals(listOf(Handle.TOP, Handle.BOTTOM), ResizeMath.handles(ElementInfo.Limits(2, 2, 1, 3)))
        assertTrue(ResizeMath.handles(ElementInfo.Limits(2, 2, 1, 1)).isEmpty())
    }

    @Test fun snapsToWholeCellsAndKeepsTheOppositeEdge() {
        assertEquals(1 to 0, ResizeMath.cells(51f, 10f, 100f, 120f, rtl = false))
        assertEquals(0 to 0, ResizeMath.cells(49f, 59f, 100f, 120f, rtl = false))
        // End grows to the right; start grows to the left and moves x; bottom down; top up and moves y.
        assertEquals(Rect(1, 1, 3, 2), rs(Rect(1, 1, 2, 2), Handle.END, 1, 0).rect)
        assertEquals(Rect(0, 1, 3, 2), rs(Rect(1, 1, 2, 2), Handle.START, -1, 0).rect)
        assertEquals(Rect(1, 1, 2, 3), rs(Rect(1, 1, 2, 2), Handle.BOTTOM, 0, 1).rect)
        assertEquals(Rect(1, 0, 2, 3), rs(Rect(1, 1, 2, 2), Handle.TOP, 0, -1).rect)
        // Corner: both axes at once.
        assertEquals(Rect(0, 0, 3, 3), rs(Rect(1, 1, 2, 2), Handle.TOP_START, -1, -1).rect)
        // Shrinking from the start keeps the end edge where it was.
        assertEquals(Rect(2, 1, 1, 2), rs(Rect(1, 1, 2, 2), Handle.START, 1, 0).rect)
        // A horizontal handle ignores vertical movement.
        assertEquals(Rect(1, 1, 3, 2), rs(Rect(1, 1, 2, 2), Handle.END, 1, 3).rect)
    }

    @Test fun neverCrossesMinMaxOrTheGrid() {
        val provider = ElementInfo.Limits(2, 3, 1, 2)
        assertEquals(Rect(0, 0, 2, 1), rs(Rect(0, 0, 2, 1), Handle.END, -5, 0, provider).rect)    // min 2
        assertEquals(Rect(0, 0, 3, 1), rs(Rect(0, 0, 2, 1), Handle.END, 5, 0, provider).rect)     // max 3
        assertEquals(Rect(0, 0, 3, 2), rs(Rect(0, 0, 2, 1), Handle.BOTTOM_END, 9, 9, provider).rect)
        // Grid edges, both directions.
        assertEquals(Rect(2, 0, 2, 1), rs(Rect(2, 0, 1, 1), Handle.END, 9, 0).rect)
        assertEquals(Rect(0, 0, 3, 1), rs(Rect(2, 0, 1, 1), Handle.START, -9, 0).rect)
        assertEquals(Rect(0, 0, 1, 4), rs(Rect(0, 3, 1, 1), Handle.TOP, 0, -9).rect)
        assertEquals(Rect(0, 3, 1, 2), rs(Rect(0, 3, 1, 1), Handle.BOTTOM, 0, 9).rect)
        // An out-of-range size from the file is the starting point, never forced.
        assertEquals(Rect(0, 0, 1, 1), rs(Rect(0, 0, 1, 1), Handle.END, 0, 0, provider).rect)
    }

    @Test fun stopsAtTheLastFreeCellAndFlagsThatEdge() {
        // An app at (3,1): growing right from 1×1 at (0,1) stops at w = 3.
        val r = rs(Rect(0, 1, 1, 1), Handle.END, 3, 0, free = freeExcept(3 to 1))
        assertEquals(Rect(0, 1, 3, 1), r.rect); assertTrue(r.blockedH); assertFalse(r.blockedV)
        // Growing down from a 2-wide at (0,0) past an app at (1,2): stops at h = 2.
        val d = rs(Rect(0, 0, 2, 1), Handle.BOTTOM, 0, 4, free = freeExcept(1 to 2))
        assertEquals(Rect(0, 0, 2, 2), d.rect); assertTrue(d.blockedV)
        // Corner: width first, then height with the new width; each axis stops on its own.
        val c = rs(Rect(0, 0, 1, 1), Handle.BOTTOM_END, 3, 3, free = freeExcept(2 to 2))
        assertEquals(Rect(0, 0, 4, 2), c.rect); assertFalse(c.blockedH); assertTrue(c.blockedV)
        // Nothing free next to it: unchanged.
        assertEquals(Rect(1, 1, 1, 1), rs(Rect(1, 1, 1, 1), Handle.START, -1, 0, free = freeExcept(0 to 1)).rect)
    }

    @Test fun rtlMirrorsTheDrag() {
        // In RTL the start handle is on the right: dragging right (positive px) is toward the start.
        val (dc, _) = ResizeMath.cells(100f, 0f, 100f, 120f, rtl = true)
        assertEquals(-1, dc)
        assertEquals(Rect(0, 0, 2, 1), rs(Rect(1, 0, 1, 1), Handle.START, dc, 0).rect)
    }

    @Test fun hitBoxesStayWhole48dpInsideThePageArea() {
        // A handle on the screen's left edge (x = 0) or above the dock (y = area bottom): the 48 dp box moves inside.
        assertEquals(0f to 76f, ResizeMath.hitBox(0f, 100f, 400f, 600f))
        assertEquals(176f to 552f, ResizeMath.hitBox(200f, 600f, 400f, 600f))
        assertEquals(352f to 0f, ResizeMath.hitBox(400f, 0f, 400f, 600f))
        // Away from the edges it's centred on the handle point.
        assertEquals(76f to 76f, ResizeMath.hitBox(100f, 100f, 400f, 600f))
    }

    @Test fun idleTimerPausesWhileAnythingIsOpen() {
        assertFalse(EditIdle.paused(false, false, false))
        assertTrue(EditIdle.paused(false, true, false))
    }
}
