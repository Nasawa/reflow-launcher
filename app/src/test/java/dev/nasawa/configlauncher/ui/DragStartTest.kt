package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.apps.InstalledApps
import dev.nasawa.configlauncher.model.AppItem
import dev.nasawa.configlauncher.model.InstalledApp
import dev.nasawa.configlauncher.model.ResolvedItem
import dev.nasawa.configlauncher.store.ConfigEngine
import dev.nasawa.configlauncher.store.LastGoodStore
import dev.nasawa.configlauncher.store.LocalFileSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #280 / #281 through real touch input on a `Modifier.tile` inside a HorizontalPager (gated like HomeScreen's):
 * a move after the short drag hold (half the long-press timeout) drags, a move before it pages, a stationary
 * release stays a tap or the item sheet, and every drag start fires exactly one LongPress haptic through
 * [LocalHapticFeedback]. Event times come from the test's injected clock (`advanceEventTime`); no real clock.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DragStartTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var drag: DragController
    private lateinit var pager: PagerState
    private val item = ResolvedItem(AppItem(id = "a", packageName = "p.a"), InstalledApp("p.a", "p.a.Main", "Alpha"))
    private var launches = 0
    private var sheets = 0
    private val haptics = mutableListOf<HapticFeedbackType>()
    private val fakeHaptics = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
    }

    /** [touchSlop]: overrides the pager's (and every detector's) `ViewConfiguration.touchSlop` in px. */
    private fun show(touchSlop: Float? = null) {
        val engine = ConfigEngine(LocalFileSource(tmp.newFolder("src")), LastGoodStore(tmp.newFolder("lg")), stabilityMs = 0)
        drag = DragController({ engine }) { } // no rects: every drop is refused and nothing is written
        drag.reducedMotion = { true } // the refused drop clears at once
        val apps = InstalledApps(ctx)
        rule.setContent {
            val base = LocalViewConfiguration.current
            val config = if (touchSlop == null) base else object : ViewConfiguration by base { override val touchSlop: Float get() = touchSlop }
            CompositionLocalProvider(LocalHapticFeedback provides fakeHaptics, LocalViewConfiguration provides config) {
                WireDragHaptics(drag)
                drag.scope = rememberCoroutineScope()
                pager = rememberPagerState { 3 }
                HorizontalPager(pager, Modifier.size(400.dp, 400.dp), userScrollEnabled = drag.dragged == null) { page ->
                    Box(Modifier.size(400.dp, 400.dp)) {
                        if (page == 0) with(drag) { Tile(item, apps, Modifier.tile(item, true, { launches++ }, { sheets++ })) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** Down on the tile, wait [holdMs] of event time, then move left in 10 ms steps of 30 px (well past 8 dp). */
    private fun holdThenMove(holdMs: (Long) -> Long, steps: Int = 6) {
        rule.onNodeWithText("Alpha").performTouchInput {
            down(center)
            advanceEventTime(holdMs(viewConfiguration.longPressTimeoutMillis))
            repeat(steps) { moveBy(Offset(-30f, 0f)); advanceEventTime(10) }
        }
        rule.waitForIdle()
    }

    private fun lift() { rule.onNodeWithText("Alpha").performTouchInput { up() }; rule.waitForIdle() }

    @Test fun moveAfterTheShortHoldStartsADragBeforeTheLongPressTimeout() {
        show()
        holdThenMove({ LongPress.dragHoldMillis(it) + 10 })
        assertNotNull("dragging after half the timeout, not the full timeout", drag.dragged)
        assertEquals(0, pager.currentPage); assertEquals(0f, pager.currentPageOffsetFraction, 0f)
        lift()
        assertEquals(0, launches); assertEquals(0, sheets)
    }

    @Test fun moveBeforeTheHoldPagesAndNeverDrags() {
        show()
        holdThenMove({ LongPress.dragHoldMillis(it) / 2 }, steps = 10)
        assertNull(drag.dragged)
        lift()
        assertNull(drag.dragged)
        assertEquals("the pager took it", 1, pager.currentPage)
        assertEquals(0, launches); assertEquals(0, sheets); assertEquals(emptyList<HapticFeedbackType>(), haptics)
    }

    @Test fun releaseBetweenTheHoldAndTheTimeoutIsStillATap() {
        show()
        rule.onNodeWithText("Alpha").performTouchInput {
            down(center); advanceEventTime(viewConfiguration.longPressTimeoutMillis - 1); up()
        }
        rule.waitForIdle()
        assertEquals(1, launches); assertEquals(0, sheets); assertNull(drag.dragged)
        assertEquals(emptyList<HapticFeedbackType>(), haptics)
    }

    @Test fun stationaryHoldPastTheTimeoutStillOpensTheSheet() {
        show()
        rule.onNodeWithText("Alpha").performTouchInput {
            down(center); advanceEventTime(viewConfiguration.longPressTimeoutMillis); moveBy(Offset(3f, 2f)); up()
        }
        rule.waitForIdle()
        assertEquals(0, launches); assertEquals(1, sheets); assertNull(drag.dragged)
        assertEquals("no haptic without a drag (#281 is drag start only)", emptyList<HapticFeedbackType>(), haptics)
    }

    @Test fun oneLongPressHapticPerDragStart() {
        show()
        holdThenMove({ LongPress.dragHoldMillis(it) + 10 })
        assertEquals("once at the start, not per move", listOf(HapticFeedbackType.LongPress), haptics)
        rule.onNodeWithText("Alpha").performTouchInput { repeat(5) { moveBy(Offset(10f, 10f)); advanceEventTime(10) } }
        lift()
        assertEquals(listOf(HapticFeedbackType.LongPress), haptics)
        holdThenMove({ LongPress.dragHoldMillis(it) + 10 })
        lift()
        assertEquals("a second drag ticks once more", listOf(HapticFeedbackType.LongPress, HapticFeedbackType.LongPress), haptics)
    }

    /**
     * Grace's FinalPassProbeTest (#287 review): with a pager slop (1 px) under the tile's 8 dp, the pager claims a slow
     * move before the hold while the tile still sees it as near (PASS). Only the tile's Final-pass check sees that the
     * pager consumed it; without that check (or reading Main instead) the lift would launch the app.
     */
    @Test fun pagerClaimingASubSlopMoveBeforeTheHoldEndsTheTileGesture() {
        show(touchSlop = 1f)
        val slopPx = with(rule.density) { 8.dp.toPx() }
        rule.onNodeWithText("Alpha").performTouchInput {
            down(center); repeat(4) { advanceEventTime(10); moveBy(Offset(-slopPx / 5f, 0f)) }; advanceEventTime(10); up()
        }
        rule.waitForIdle()
        assertNull(drag.dragged)
        assertEquals("the pager took the move, so the release is not a tap", 0, launches); assertEquals(0, sheets)
    }
}
