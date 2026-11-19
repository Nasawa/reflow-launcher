package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #337 follow-up (Grok Bot's corrected spec): [DragOverlay] sets the page-turn zone to a fixed 24 dp under a large
 * system Back-gesture inset; the inset adds nothing. The fake inset is checked live in the same composition, so the
 * test can't pass because the inset never arrived.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w392dp-h872dp-xhdpi")
class EdgeZoneInsetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theZoneIs24dpUnderALargeGestureInset() {
        val drag = DragController({ error("unused") }) {}
        var view: android.view.View? = null
        var seenLeft = -1; var seenRight = -1; var density = 0f
        rule.setContent {
            view = LocalView.current
            val dens = LocalDensity.current
            density = dens.density
            seenLeft = WindowInsets.systemGestures.getLeft(dens, LayoutDirection.Ltr)
            seenRight = WindowInsets.systemGestures.getRight(dens, LayoutDirection.Ltr)
            DragOverlay(drag)
        }
        rule.runOnIdle {
            val big = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemGestures(), Insets.of(150, 0, 150, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view!!, big)
        }
        rule.waitForIdle()
        assertEquals("the fake Back-gesture inset reached the composition", 150, seenLeft)
        assertEquals(150, seenRight)
        assertEquals("exactly 24 dp, no inset term", EDGE_ZONE_DP * density, drag.edgeZonePx, 0.01f)
        val win = Rect(0f, 0f, 1000f, 2000f)
        val z = drag.edgeZonePx
        assertEquals(-1, EdgeTurn.direction(Offset(0f, 1000f), win, Rect.Zero, false, z, false))
        assertEquals("inside the inset but past 24 dp: no turn", 0, EdgeTurn.direction(Offset(z + 1f, 1000f), win, Rect.Zero, false, z, false))
        assertEquals(0, EdgeTurn.direction(Offset(1000f - z - 1f, 1000f), win, Rect.Zero, false, z, false))
        assertEquals(1, EdgeTurn.direction(Offset(1000f, 1000f), win, Rect.Zero, false, z, false))
    }
}
