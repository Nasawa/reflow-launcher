package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.config.Elements
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #304 NW-8: one write per handle release, none on cancel; whole 48 dp handles at the area's edges. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ResizeFrameTest {
    @get:Rule val rule = createComposeRule()
    private val density get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density

    private val writes = mutableListOf<ResizeMath.Rect>()
    private val active = mutableListOf<Boolean>()
    private var ticks = 0

    private fun show(p: Elements.Placement, free: (Int, Int, Int, Int) -> Boolean = { _, _, _, _ -> true }) {
        rule.setContent { MaterialTheme { Box(Modifier.size(400.dp, 500.dp)) {
            ResizeFrame(p, ElementInfo.Limits(1, 4, 1, 5), 4, 5, 100.dp, 100.dp, 0.dp, 0.dp, 400.dp, 500.dp, rtl = false, reducedMotion = true,
                free = free, onActive = { active += it }, onResize = { writes += it }, onTick = { ticks++ })
        } } }
    }

    private fun dragBy(tag: String, dxDp: Float, dyDp: Float, release: Boolean = true) {
        rule.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(dxDp * density / 10, dyDp * density / 10)) }
            if (release) up() else cancel()
        }
        rule.waitForIdle()
    }

    @Test fun releaseWritesOnceWithTheSnappedSize() {
        show(Elements.Placement("home", 1, 1, 1, 1))
        dragBy("resize-END", 120f, 0f)          // 1.2 cells → 1
        assertEquals(listOf(ResizeMath.Rect(1, 1, 2, 1)), writes)
        assertEquals(listOf(true, false), active)
    }

    @Test fun oneBuzzPerCellStep() {
        // dev1 fix: 2.5 cells → +1, +2 (no buzz between cells or on release).
        show(Elements.Placement("home", 0, 0, 1, 1))
        dragBy("resize-END", 250f, 0f)
        assertEquals(2, ticks)
        assertEquals(listOf(ResizeMath.Rect(0, 0, 3, 1)), writes)
    }

    @Test fun cornerResizesBothAxesAndStopsAtTakenCells() {
        // (3,1) is taken: from x = 1 the width stops at 2; the height still grows (2 cells down).
        show(Elements.Placement("home", 1, 1, 1, 1)) { x, y, w, h -> !(3 in x until x + w && 1 in y until y + h) }
        dragBy("resize-BOTTOM_END", 300f, 160f)
        assertEquals(listOf(ResizeMath.Rect(1, 1, 2, 3)), writes)
    }

    @Test fun cancelWritesNothing() {
        show(Elements.Placement("home", 1, 1, 1, 1))
        dragBy("resize-END", 150f, 0f, release = false)
        assertTrue(writes.isEmpty())
        assertEquals(listOf(true, false), active)
        // A drag back to the same size writes nothing either.
        dragBy("resize-END", 20f, 0f)
        assertTrue(writes.isEmpty())
    }

    @Test fun handlesAreWhole48dpTargetsAtTheEdges() {
        // Element in the top-start corner and down to the bottom edge of the area (as above the dock).
        show(Elements.Placement("home", 0, 0, 2, 5))
        for (tag in listOf("resize-START", "resize-TOP", "resize-BOTTOM", "resize-TOP_START", "resize-BOTTOM_START")) {
            val b = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag width ${b.width / density}", b.width / density >= 47.5f)
            assertTrue("$tag height ${b.height / density}", b.height / density >= 47.5f)
            assertTrue("$tag inside", b.left >= -0.5f && b.top >= -0.5f && b.bottom <= 500 * density + 0.5f)
        }
    }

    @Test fun explanationNamesTheWidgetWithoutAnArticle() {
        val s = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.bind_explain_body, "Analog clock")
        assertTrue(s, s.contains("add the widget \"Analog clock\""))
        assertFalse(s.contains(" a Analog"))
    }

}
