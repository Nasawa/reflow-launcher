package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.model.AppItem
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.Grid
import dev.nasawa.configlauncher.model.ResolvedItem
import dev.nasawa.configlauncher.store.ConfigEngine
import dev.nasawa.configlauncher.store.LastGoodStore
import dev.nasawa.configlauncher.store.LocalFileSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #282 (Pragma's drag-state spec) on [DragController]: one [DragController.targetAt] for the highlight and the drop,
 * hit at the finger (not the tile centre), the lift above the finger, the 120 ms raise, the 150 ms settle before the
 * write, the per-target tick, and the #212 spring-back unchanged. The compose main clock (autoAdvance off) drives
 * every animation; no real clock.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DragStateTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var engine: ConfigEngine
    private lateinit var drag: DragController
    private val submitted = mutableListOf<Edit>()
    private var cancelled = 0
    private var ticks = 0
    private var reduced = false
    private var shown by mutableStateOf(true)
    private val item = ResolvedItem(AppItem("a", "p.a", x = 0, y = 0), null)
    private val tile = Rect(0f, 0f, 100f, 100f) // cell (0,0) of a 4 × 5 block of 100 px cells

    @Before fun setUp() {
        engine = ConfigEngine(LocalFileSource(tmp.newFolder("src")), LastGoodStore(tmp.newFolder("lg")), stabilityMs = 0)
        drag = DragController({ engine }, onCancel = { cancelled++ }) { submitted += it }
        drag.gridRect = Rect(0f, 0f, 400f, 500f)
        drag.dockRect = Rect(0f, 600f, 400f, 700f)
        drag.dockCount = 3
        drag.currentPageId = "home"
        drag.grid = Grid(4, 5)
        drag.tick = { ticks++ }
        drag.reducedMotion = { reduced }
        rule.mainClock.autoAdvance = false
        rule.setContent { if (shown) { drag.scope = rememberCoroutineScope(); Box {} } }
        rule.mainClock.advanceTimeByFrame()
    }

    private fun ms(n: Long) { rule.mainClock.advanceTimeBy(n); rule.waitForIdle() }
    private fun raised() = ms(DragController.RAISE_MS + 32L)

    // --- targetAt: the one hit test ---

    @Test fun dropAndHighlightUseTheFingerNotTheTileCentre() {
        // Picked up near its bottom edge: finger (50, 95). A 10 px move down puts the finger in row 1, the tile centre still in row 0.
        rule.runOnIdle { drag.start(item, tile, touch = Offset(50f, 95f)); drag.move(Offset(0f, 10f)) }
        assertEquals(Offset(50f, 105f), drag.fingerInRoot)
        val t = drag.target
        assertNotNull(t)
        assertEquals("the highlight is the cell under the finger", Edit.MoveToCell("a", "home", 0, 1), t!!.edit)
        assertEquals(Rect(0f, 100f, 100f, 200f), t.rect)
        assertEquals("the highlight is targetAt(finger)", drag.targetAt(drag.fingerInRoot), t)
        reduced = true
        rule.runOnIdle { drag.drop() }
        assertEquals("the drop lands where the highlight was", listOf<Edit>(t.edit), submitted)
    }

    @Test fun rtlHighlightAndDropMirrorTogether() {
        drag.rtl = true
        rule.runOnIdle { drag.start(item, tile, touch = Offset(50f, 95f)); drag.move(Offset(0f, 10f)) }
        val t = drag.target!!
        assertEquals("physical left = the last column in RTL", Edit.MoveToCell("a", "home", 3, 1), t.edit)
        assertEquals(Rect(0f, 100f, 100f, 200f), t.rect) // still drawn under the finger
        reduced = true
        rule.runOnIdle { drag.drop() }
        assertEquals(listOf<Edit>(t.edit), submitted)
    }

    @Test fun dockSlotTargetUsesTheSameBands() {
        rule.runOnIdle { drag.start(item, tile); drag.move(Offset(220f, 600f)) } // finger (270, 650)
        val t = drag.target!!
        assertEquals("4 bands of 100 px: x 270 is slot 2", Edit.MoveToDock("a", 2), t.edit)
        assertEquals(Rect(200f, 600f, 300f, 700f), t.rect)
    }

    @Test fun refusedOrOutsideMeansNoHighlightAndASpringBack() {
        drag.accepts = { it !is Edit.MoveToDock } // e.g. a full dock
        rule.runOnIdle { drag.start(item, tile); drag.move(Offset(220f, 600f)) }
        assertNull("a refused target is no target", drag.target)
        rule.runOnIdle { drag.move(Offset(0f, -650f)) } // finger (270, 0): in the block again
        assertNotNull(drag.target)
        rule.runOnIdle { drag.move(Offset(300f, 0f)) } // finger (570, 0): a margin
        assertNull(drag.target)
        rule.runOnIdle { drag.drop() }
        assertTrue("springs back", drag.returning); assertTrue(submitted.isEmpty()); assertEquals(1, cancelled)
    }

    @Test fun highlightRectsMatchTheHitMathsEverywhere() {
        for (rtl in listOf(false, true)) {
            val block = Rect(13f, 27f, 413f, 527f)
            var y = block.top + 0.5f
            while (y < block.bottom) {
                var x = block.left + 0.5f
                while (x < block.right) {
                    val (cx, cy) = GridHit.cellInBlock(x, y, block.left, block.top, block.width, block.height, 4, 5, rtl)!!
                    assertTrue("cell ($cx,$cy) rtl=$rtl contains ($x,$y)", DropRects.cell(block, 4, 5, cx, cy, rtl).contains(Offset(x, y)))
                    x += 7f
                }
                y += 7f
            }
            for (vertical in listOf(false, true)) {
                val dock = Rect(5f, 600f, 405f, 700f)
                var p = 0.5f
                while (p < (if (vertical) dock.height else dock.width)) {
                    val pt = if (vertical) Offset(dock.center.x, dock.top + p) else Offset(dock.left + p, dock.center.y)
                    val i = GridHit.dockIndex(pt.x, pt.y, dock.left, dock.top, dock.width, dock.height, 3, vertical, rtl)
                    assertTrue("slot $i v=$vertical rtl=$rtl contains $pt", DropRects.dockSlot(dock, 3, i, vertical, rtl).contains(pt))
                    p += 3f
                }
            }
        }
    }

    @Test fun oneTickPerNewTargetAndNoneWhenLeavingToNoTarget() {
        rule.runOnIdle { drag.start(item, tile) } // over its own cell: silent (the start buzz is #281's)
        assertEquals(0, ticks)
        rule.runOnIdle { drag.move(Offset(100f, 0f)) } // (1,0)
        assertEquals(1, ticks)
        rule.runOnIdle { drag.move(Offset(10f, 0f)); drag.move(Offset(10f, 5f)) } // still (1,0)
        assertEquals("same cell: no tick", 1, ticks)
        rule.runOnIdle { drag.move(Offset(0f, 100f)) } // (1,1)
        assertEquals(2, ticks)
        rule.runOnIdle { drag.move(Offset(500f, 0f)) } // a margin
        assertEquals("leaving to no target is silent", 2, ticks)
        rule.runOnIdle { drag.move(Offset(-500f, 0f)) } // back on (1,1)
        assertEquals(3, ticks)
    }

    // --- lift ---

    @Test fun raiseEasesInOver120ms() {
        rule.runOnIdle { drag.start(item, tile) }
        assertEquals(0f, drag.raise, 0f); assertEquals(1f, drag.visualScale, 0f)
        ms(64)
        assertTrue("partway at ~64 ms: ${drag.raise}", drag.raise > 0.1f && drag.raise < 1f)
        raised()
        assertEquals(1f, drag.raise, 0f); assertEquals(DragController.LIFT_SCALE, drag.visualScale, 1e-4f)
    }

    @Test fun reducedMotionRaisesAtOnce() {
        reduced = true
        rule.runOnIdle { drag.start(item, tile) }
        assertEquals(1f, drag.raise, 0f); assertEquals(DragController.LIFT_SCALE, drag.visualScale, 1e-4f)
    }

    @Test fun liftedTileSitsCentredAboveTheFinger() {
        drag.overlayBounds = Rect(0f, 0f, 1000f, 2000f); drag.liftGapPx = 16f; drag.topInsetPx = 50f
        rule.runOnIdle { drag.start(item, tile); drag.move(Offset(300f, 300f)) } // finger (350, 350)
        raised()
        val drawn = drawnRect()
        assertEquals("centred on the finger", 350f, drawn.center.x, 0.01f)
        assertEquals("bottom 16 px above it", 350f - 16f, drawn.bottom, 0.01f)
        assertEquals(115f, drawn.width, 0.01f) // 1.15×
        assertFalse(drawn.contains(drag.fingerInRoot))
    }

    @Test fun topRowGoesBesideTheFingerTowardTheCentre() {
        drag.overlayBounds = Rect(0f, 0f, 1000f, 2000f); drag.liftGapPx = 16f; drag.topInsetPx = 50f
        rule.runOnIdle { drag.start(item, tile); drag.move(Offset(50f, 30f)) } // finger (100, 80): no room above
        raised()
        val left = drawnRect()
        assertEquals("16 px right of the finger (toward the centre)", 116f, left.left, 0.01f)
        assertEquals("centred vertically on it", 80f, left.center.y, 0.01f)
        rule.runOnIdle { drag.move(Offset(800f, 0f)) } // finger (900, 80): right half
        val right = drawnRect()
        assertEquals("16 px left of the finger", 884f, right.right, 0.01f)
        assertFalse(right.contains(drag.fingerInRoot))
    }

    @Test fun placementClampsInsideTheWindowAndIsNeverUnderTheFinger() {
        val b = Rect(0f, 0f, 400f, 800f)
        for (fx in listOf(0f, 5f, 100f, 199f, 201f, 300f, 395f, 400f)) for (fy in listOf(0f, 10f, 60f, 200f, 790f, 800f)) {
            val f = Offset(fx, fy)
            val p = LiftPlacement.place(f, 115f, 115f, b, topInset = 24f, gap = 16f)
            val r = Rect(p.x, p.y, p.x + 115f, p.y + 115f)
            assertTrue("inside the window: $r for $f", r.left >= b.left && r.top >= b.top && r.right <= b.right && r.bottom <= b.bottom)
            assertFalse("never under the finger: $r for $f", r.contains(f) && f.x > r.left && f.y > r.top)
        }
    }

    private fun drawnRect(): Rect {
        val r = drag.liftedOriginInRoot; val k = drag.visualScale; val o = drag.visualOffset
        val c = r.center + o
        return Rect(c.x - r.width * k / 2f, c.y - r.height * k / 2f, c.x + r.width * k / 2f, c.y + r.height * k / 2f)
    }

    // --- drop and cancel ---

    @Test fun acceptedDropSettlesFor150msThenWrites() {
        rule.runOnIdle { drag.start(item, tile) }
        raised()
        rule.runOnIdle { drag.move(Offset(200f, 100f)) } // finger (250, 150): cell (2,1)
        rule.runOnIdle { drag.drop() }
        assertTrue(drag.settling); assertNull("the highlight clears at the drop", drag.target)
        assertTrue("not written yet", submitted.isEmpty()); assertTrue(engine.dragging)
        ms(80)
        assertTrue("still settling at 80 ms", drag.settling); assertTrue(submitted.isEmpty())
        val k = drag.visualScale
        assertTrue("scaling 1.15 → 1.0: $k", k > 1f && k < DragController.LIFT_SCALE)
        ms(DragController.SETTLE_MS.toLong())
        assertEquals(listOf<Edit>(Edit.MoveToCell("a", "home", 2, 1)), submitted)
        assertNull(drag.dragged); assertFalse(drag.settling); assertFalse(engine.dragging); assertEquals(0, cancelled)
    }

    @Test fun settleEndsOverTheTargetCell() {
        rule.runOnIdle { drag.start(item, tile) }
        raised()
        rule.runOnIdle { drag.move(Offset(200f, 100f)); drag.drop() }
        ms(DragController.SETTLE_MS - 20L)
        val near = drag.liftedOriginInRoot.center + drag.visualOffset
        assertTrue("heading for the centre of (2,1) at (250,150): $near", (near - Offset(250f, 150f)).getDistance() < 20f)
    }

    @Test fun reducedMotionWritesAtOnce() {
        reduced = true
        rule.runOnIdle { drag.start(item, tile); drag.move(Offset(200f, 100f)); drag.drop() }
        assertEquals(1, submitted.size); assertNull(drag.dragged); assertFalse(drag.settling)
    }

    @Test fun leavingCompositionMidSettleStillWritesOnce() {
        rule.runOnIdle { drag.start(item, tile) }
        raised()
        rule.runOnIdle { drag.move(Offset(200f, 100f)); drag.drop() }
        ms(48)
        shown = false
        rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        assertEquals("the accepted drop is written", 1, submitted.size)
        rule.runOnIdle { drag.cancelNow() } // a late detach: nothing more
        assertEquals(1, submitted.size); assertNull(drag.dragged); assertEquals(0, cancelled)
    }

    @Test fun refusedDropKeepsThe212SpringBack() {
        rule.runOnIdle { drag.start(item, tile) }
        raised()
        rule.runOnIdle { drag.move(Offset(450f, 0f)); drag.drop() } // finger (500, 50): right of the block
        assertTrue(drag.returning); assertFalse(drag.settling); assertTrue(submitted.isEmpty())
        val from = drag.visualOffset
        ms(100)
        assertTrue("lift partway at ~100 ms: ${drag.lift}", drag.lift > 0.01f && drag.lift < 1f)
        assertEquals("offset and scale return together on the lift", from.x * drag.lift, drag.visualOffset.x, 0.5f)
        ms(120)
        assertNull("back in about 200 ms", drag.dragged); assertFalse(drag.returning)
        assertTrue(submitted.isEmpty()); assertEquals(1, cancelled)
    }
}
