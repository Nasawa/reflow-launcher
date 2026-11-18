package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.config.Elements
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** dev1 fixes: a drag back to its own cell opens no menu (c); one buzz per new cell (a). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ElementDragTargetTest {
    @get:Rule val rule = createComposeRule()
    private val density get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
    private var longs = 0; private var ticks = 0
    private val drops = mutableListOf<Pair<Int, Int>>()

    private fun show() = rule.setContent { MaterialTheme { Box(Modifier.size(400.dp, 500.dp).testTag("area")) {
        ElementDragTarget(Elements.Placement("home", 1, 1, 1, 1), 100.dp, 100.dp, 0.dp, 0.dp, rtl = false, selected = false,
            canDrop = { _, _ -> true }, onDrop = { x, y -> drops += x to y }, onLong = { longs++ }, onTick = { ticks++ })
    } } }

    private fun longPressDrag(vararg stepsDp: Pair<Float, Float>) {
        rule.onNodeWithTag("area").performTouchInput {
            val start = Offset(150f * density, 150f * density)
            down(start); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            for ((dx, dy) in stepsDp) repeat(10) { moveBy(Offset(dx * density / 10, dy * density / 10)) }
            up()
        }
        rule.waitForIdle()
    }

    @Test fun aDragBackToItsOwnCellOpensNoMenu() {
        show()
        longPressDrag(160f to 0f, -160f to 0f)     // out 1.6 cells and back
        assertEquals("no long-press menu", 0, longs)
        assertEquals("no write", emptyList<Pair<Int, Int>>(), drops)
        assertEquals("one buzz per new cell: out to +1, +2, back to +1, 0", 4, ticks)
    }

    @Test fun aLongPressWithoutMovingStillOpensTheMenu() {
        show()
        longPressDrag(1f to 1f)                     // within touch slop
        assertEquals(1, longs)
        assertEquals(0, ticks)
    }

    @Test fun aRealMoveDropsOnceWithATickPerCell() {
        show()
        longPressDrag(0f to 210f)
        assertEquals(listOf(1 to 3), drops)
        assertEquals(0, longs)
        assertEquals(2, ticks)
    }
}
