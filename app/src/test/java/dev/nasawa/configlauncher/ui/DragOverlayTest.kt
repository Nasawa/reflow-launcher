package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.model.AppItem
import dev.nasawa.configlauncher.model.InstalledApp
import dev.nasawa.configlauncher.model.ResolvedItem
import dev.nasawa.configlauncher.store.ConfigEngine
import dev.nasawa.configlauncher.store.LastGoodStore
import dev.nasawa.configlauncher.store.LocalFileSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #228 in pixels: the source tile sits in a clipping box (as in the pager viewport) and a "dock" is drawn after it,
 * as HomeScreen's dock / rail / fold halves are. While lifted, the source draws a 30 % ghost (#282) and the root
 * [DragOverlay] paints the tile over the dock, unclipped; after the spring-back the source draws again and the
 * overlay is gone. Density 1 (1 dp = 1 px); the compose main clock drives the return; no real clock.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DragOverlayTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val tile = Color(0xFFFF0000)
    private val dock = Color(0xFF0000FF)
    private val bg = Color(0xFFFFFFFF)
    private val item = ResolvedItem(AppItem(id = "a", packageName = "p.a"), InstalledApp("p.a", "p.a.Main", "Alpha"))
    private lateinit var drag: DragController
    private var cancelled = 0

    private fun show(reduced: Boolean = false) {
        val engine = ConfigEngine(LocalFileSource(tmp.newFolder("src")), LastGoodStore(tmp.newFolder("lg")), stabilityMs = 0)
        // No grid or dock rects: every drop is refused and springs back.
        drag = DragController({ engine }, onCancel = { cancelled++ }) { error("nothing is written: $it") }
        drag.reducedMotion = { reduced }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                drag.scope = rememberCoroutineScope()
                Box(Modifier.size(300.dp).background(bg).testTag("root")) {
                    Box(Modifier.size(100.dp).clipToBounds()) { // the pager viewport
                        with(drag) {
                            Box(Modifier.size(60.dp).tile(item, true, {}, {}, visual = { Box(Modifier.fillMaxSize().background(tile)) }).background(tile))
                        }
                    }
                    Box(Modifier.offset(150.dp, 150.dp).size(100.dp).background(dock)) // drawn after the grid
                    DragOverlay(drag)
                }
            }
        }
        frames(2)
    }

    private fun frames(n: Int) = repeat(n) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
    private fun px(x: Int, y: Int) = rule.onNodeWithTag("root").captureToImage().toPixelMap()[x, y]
    /** #282: the lifted tile draws at 0.92 alpha on its plate, so "red" is near-red, not exact. */
    private fun isLifted(c: Color) = c.red > 0.85f && c.green < 0.2f && c.blue < 0.2f
    /** #282: the source's 30 % ghost over white. */
    private fun isGhost(c: Color) = c.red > 0.95f && kotlin.math.abs(c.green - 0.7f) < 0.05f && kotlin.math.abs(c.blue - 0.7f) < 0.05f

    @Test fun liftedTileIsPaintedOverTheDockAndTheSourceIsAGhost() {
        show()
        assertEquals("at rest the source draws", tile, px(30, 30))
        assertEquals(dock, px(200, 200))
        rule.runOnIdle { drag.start(item, Rect(0f, 0f, 60f, 60f)); drag.move(Offset(170f, 170f)) }
        frames(30)
        // #282: the finger is at (200, 200); the 69 px lifted tile sits centred above it, bottom 16 px up: y 115..184.
        assertTrue("over the dock, the lifted tile is on top", isLifted(px(200, 160)))
        assertTrue("outside the pager viewport too (no clip)", isLifted(px(200, 120)))
        assertEquals("never under the finger", dock, px(200, 200))
        assertTrue("the source shows a 30 % ghost: ${px(30, 30)}", isGhost(px(30, 30)))
        rule.runOnIdle { drag.drop() } // refused: the #218 spring-back
        frames(3)
        assertTrue("springing back", drag.returning)
        frames(60)
        assertEquals("back: the source draws again", tile, px(30, 30))
        assertEquals("the overlay is gone", dock, px(200, 200))
        assertEquals(1, cancelled)
    }

    /**
     * Pragma (#231): with reduced motion no frame may show no tile. Every frame from the release on (including the
     * one drawn before any recomposition), the tile is painted either by the overlay (still at the dock) or by the
     * source at its origin, which comes back no later than the overlay clears.
     */
    @Test fun reducedMotionNeverShowsAFrameWithNoTile() {
        show(reduced = true)
        rule.runOnIdle { drag.start(item, Rect(0f, 0f, 60f, 60f)); drag.move(Offset(170f, 170f)) }
        frames(3)
        assertTrue("held over the dock, raised at once", isLifted(px(200, 160))); assertTrue(isGhost(px(30, 30)))
        rule.runOnIdle { drag.drop() } // refused, reduced motion: an instant return
        for (i in 0 until 6) {
            val img = rule.onNodeWithTag("root").captureToImage().toPixelMap()
            val overlay = isLifted(img[200, 160]); val source = img[30, 30] == tile
            assertTrue("frame $i after release: overlay drawn ($overlay) or source back ($source)", overlay || source)
            rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        }
        assertEquals("back at its origin", tile, px(30, 30)); assertEquals("the overlay is gone", dock, px(200, 160))
        assertEquals(1, cancelled)
    }

    /** Every frame of the animated #218 return paints the tile somewhere along its path (the overlay or the source). */
    @Test fun springBackNeverShowsAFrameWithNoTile() {
        show()
        rule.runOnIdle { drag.start(item, Rect(0f, 0f, 60f, 60f)); drag.move(Offset(170f, 170f)) }
        frames(30)
        rule.runOnIdle { drag.drop() }
        for (i in 0 until 40) {
            val img = rule.onNodeWithTag("root").captureToImage().toPixelMap()
            val o = drag.visualOffset // the overlay's centre tracks the return; once it ends, the source at (30, 30)
            val cx = (30 + o.x).toInt().coerceIn(0, 299); val cy = (30 + o.y).toInt().coerceIn(0, 299)
            assertTrue("frame $i: tile at ($cx, $cy) or at its origin", isLifted(img[cx, cy]) || img[cx, cy] == tile || img[30, 30] == tile)
            rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        }
        assertEquals(tile, px(30, 30))
    }

    /**
     * #282: the target highlight is drawn under the finger's cell (here (2,2) of a 3 × 3 block of 100 px cells), inset
     * 2 px: a 1 px 60 % black ring, then the 2 px white stroke, then a 20 % primary fill. Below the lifted tile.
     */
    @Test fun targetHighlightStyleUnderTheFinger() {
        show()
        drag.gridRect = Rect(0f, 0f, 300f, 300f); drag.grid = dev.nasawa.configlauncher.model.Grid(3, 3); drag.currentPageId = "home"
        rule.runOnIdle { drag.start(item, Rect(0f, 0f, 60f, 60f)); drag.move(Offset(170f, 170f)) } // finger (200, 200)
        frames(30)
        assertEquals(Rect(200f, 200f, 300f, 300f), drag.target!!.rect)
        val ring = px(201, 280); val stroke = px(203, 280); val fill = px(280, 280)
        assertTrue("dark ring outside the stroke: $ring", ring.red < 0.5f && ring.green < 0.5f && ring.blue < 0.5f)
        assertEquals("2 px white stroke", Color.White, stroke)
        val primary = androidx.compose.material3.lightColorScheme().primary
        assertEquals("20 % primary fill over white", 0.8f + 0.2f * primary.red, fill.red, 0.02f)
        assertEquals(0.8f + 0.2f * primary.green, fill.green, 0.02f)
        rule.runOnIdle { drag.move(Offset(200f, 0f)) } // finger (400, 200): outside the root's block
        frames(2)
        assertEquals("no target, no highlight", bg, px(280, 280))
    }
}
