package dev.nasawa.configlauncher.ui

import androidx.compose.ui.geometry.Offset
import dev.nasawa.configlauncher.model.*
import dev.nasawa.configlauncher.ui.LongPress.Step
import org.junit.Assert.*
import org.junit.Test

/** #53: a stationary hold opens the item sheet; hold + move drags; never both, never a launch. */
class LongPressTest {
    private val o = Offset(100f, 100f)

    @Test fun stationaryHoldThenReleaseOpensMenu() {
        val lp = LongPress.track(o, 8f, canDrag = true)
        assertEquals(Step.WAIT, lp.next(Offset(103f, 102f), pressed = true))   // jitter under the slop
        assertEquals(Step.MENU, lp.next(Offset(103f, 102f), pressed = false))
        assertFalse(lp.dragging)
    }

    @Test fun holdThenMoveDragsAndDrops() {
        val lp = LongPress.track(o, 8f, canDrag = true)
        assertEquals(Step.START_DRAG, lp.next(Offset(120f, 100f), pressed = true))
        assertEquals(Step.DRAG, lp.next(Offset(100f, 100f), pressed = true))     // moving back doesn't turn it into a menu
        assertEquals(Step.DROP, lp.next(Offset(100f, 100f), pressed = false))
    }

    @Test fun consumedLiftIsACancelNotADrop() {   // #214 (5): ACTION_CANCEL on a rotate mid-drag
        val lp = LongPress.track(o, 8f, canDrag = true)
        assertEquals(Step.START_DRAG, lp.next(Offset(120f, 100f), pressed = true))
        assertEquals(Step.CANCEL, lp.next(Offset(160f, 140f), pressed = false, consumed = true))
    }

    @Test fun consumedLiftBeforeADragOpensNoMenu() {
        val lp = LongPress.track(o, 8f, canDrag = true)
        assertEquals(Step.CANCEL, lp.next(o, pressed = false, consumed = true))
    }

    @Test fun noDragWhereDragIsOff() {   // folder children / drawer tiles
        val lp = LongPress.track(o, 8f, canDrag = false)
        assertEquals(Step.WAIT, lp.next(Offset(300f, 300f), pressed = true))
        assertEquals(Step.MENU, lp.next(Offset(300f, 300f), pressed = false))
    }
}

/**
 * #280: the timed tile gesture. Down at t=1000; the long-press timeout is 400 ms and the drag hold half of it
 * (200 ms). Times are plain numbers (event uptimes), never a real clock.
 */
class TimedLongPressTest {
    private val o = Offset(100f, 100f)
    private val far = Offset(120f, 100f) // 20 px: past the 8 px slop
    private val near = Offset(104f, 103f) // 5 px: jitter under the slop
    private fun lp(canDrag: Boolean = true) = LongPress.track(o, 8f, canDrag, downTime = 1_000, holdMs = 200, menuMs = 400)

    @Test fun dragHoldIsHalfTheSystemTimeout() {
        assertEquals(200L, LongPress.dragHoldMillis(400))   // Android 12+ default
        assertEquals(250L, LongPress.dragHoldMillis(500))   // older default
        assertEquals(750L, LongPress.dragHoldMillis(1_500)) // a long touch-and-hold delay stays long
    }

    @Test fun holdMayNotExceedTheTimeout() {
        assertThrows(IllegalArgumentException::class.java) { LongPress.track(o, 8f, true, 0, holdMs = 401, menuMs = 400) }
    }

    @Test fun moveBeforeTheHoldIsASwipeNotADrag() {
        val lp = lp()
        assertEquals(Step.PASS, lp.next(near, pressed = true, time = 1_050))   // not ours: the pager may still take it
        assertEquals(Step.SWIPE, lp.next(far, pressed = true, time = 1_199))
        assertFalse(lp.dragging)
    }

    @Test fun moveAtTheHoldStartsADragBeforeTheTimeout() {
        val lp = lp()
        assertEquals(Step.START_DRAG, lp.next(far, pressed = true, time = 1_200))
        assertEquals(Step.DRAG, lp.next(o, pressed = true, time = 1_250))
        assertEquals(Step.DROP, lp.next(o, pressed = false, time = 1_300))
    }

    @Test fun jitterAfterTheHoldIsOursSoThePagerCantStealIt() {
        val lp = lp()
        assertEquals(Step.WAIT, lp.next(near, pressed = true, time = 1_250))
        assertEquals(Step.START_DRAG, lp.next(far, pressed = true, time = 1_300))
    }

    @Test fun stationaryReleaseIsATapUntilTheTimeoutThenTheMenu() {
        assertEquals(Step.TAP, lp().next(near, pressed = false, time = 1_100))
        assertEquals("after the hold but before the timeout: still a tap", Step.TAP, lp().next(near, pressed = false, time = 1_399))
        assertEquals(Step.MENU, lp().next(near, pressed = false, time = 1_400))
    }

    @Test fun aFlingThatLiftsInOneEventIsNotATap() {
        assertEquals(Step.SWIPE, lp().next(far, pressed = false, time = 1_050))
    }

    @Test fun aMoveSomeoneElseConsumedBeforeTheTimeoutEndsIt() {
        assertEquals(Step.CANCEL, lp().next(near, pressed = true, consumed = true, time = 1_100))
        assertEquals(Step.CANCEL, lp().next(near, pressed = false, consumed = true, time = 1_500))
    }

    @Test fun exactlyTheSlopIsStillNear() {   // ux.md: "more than 8 dp"
        val at = Offset(108f, 100f) // exactly 8 px
        assertEquals(Step.PASS, lp().next(at, pressed = true, time = 1_100))
        assertEquals(Step.WAIT, lp().next(at, pressed = true, time = 1_250))
        assertEquals(Step.TAP, lp().next(at, pressed = false, time = 1_100))
    }

    @Test fun aConsumedMoveAtTheTimeoutIsNoLongerACancel() {   // `t < menuMs`: from the timeout on, the old post-timeout rules
        assertEquals(Step.WAIT, lp().next(near, pressed = true, consumed = true, time = 1_400))
        assertEquals(Step.CANCEL, lp().next(near, pressed = true, consumed = true, time = 1_399))
    }

    @Test fun nonDraggableTilesKeepTheOldTimeout() {   // folder children / drawer tiles: no drag, pager free until the timeout
        assertEquals(Step.PASS, lp(canDrag = false).next(near, pressed = true, time = 1_300))
        assertEquals(Step.SWIPE, lp(canDrag = false).next(far, pressed = true, time = 1_300))
        val late = lp(canDrag = false)
        assertEquals(Step.WAIT, late.next(far, pressed = true, time = 1_400))
        assertEquals(Step.MENU, late.next(far, pressed = false, time = 1_500))
    }
}

class ItemSheetModelTest {
    private val maps = InstalledApp("com.google.android.apps.maps", "com.google.android.maps.MapsActivity", "Maps")

    @Test fun showsRealNameAndPackageEvenWithLabelOverride() {
        val m = ItemSheetModel.of(ResolvedItem(AppItem("a", maps.packageName, label = "Bank"), maps))
        assertEquals("Bank", m.title)
        assertEquals("Maps", m.realName)
        assertEquals("com.google.android.apps.maps", m.packageName)
        assertEquals(listOf(ItemAction.EDIT_LAYOUT, ItemAction.APP_INFO, ItemAction.REMOVE), m.actions)
        assertTrue("label differs from the real name: always shown", m.showRealName)
    }

    @Test fun realNameShownOnceWhenLabelIsTheSame() {   // #103
        assertFalse(ItemSheetModel.of(ResolvedItem(AppItem("a", maps.packageName), maps)).showRealName)
        assertFalse(ItemSheetModel.of(ResolvedItem(AppItem("a", maps.packageName, label = "Maps"), maps)).showRealName)
        // Any difference, even case or a hidden character stripped at render, is compared after cleaning.
        assertTrue(ItemSheetModel.of(ResolvedItem(AppItem("a", maps.packageName, label = "maps"), maps)).showRealName)
        assertFalse(ItemSheetModel.of(ResolvedItem(AppItem("a", maps.packageName, label = "Ma\u200Bps"), maps)).showRealName)
    }

    @Test fun notInstalledHasNoAppInfo() {
        val m = ItemSheetModel.of(ResolvedItem(AppItem("a", "gone.app"), null))
        assertNull(m.realName); assertEquals("gone.app", m.packageName)
        assertEquals(listOf(ItemAction.EDIT_LAYOUT, ItemAction.REMOVE), m.actions)
    }

    @Test fun folderShowsCountAndNoAppInfo() {
        val m = ItemSheetModel.of(ResolvedItem(FolderItem("f", "Work", listOf(AppItem("x", "p.x"), AppItem("y", "p.y"))), null))
        assertEquals(2, m.folderSize); assertNull(m.packageName)
        assertEquals(listOf(ItemAction.EDIT_LAYOUT, ItemAction.REMOVE), m.actions)
    }

    @Test fun shortcutShowsItsPackage() {
        val m = ItemSheetModel.of(ResolvedItem(ShortcutItem("s", maps.packageName, "nav_home", label = "Home"), maps))
        assertEquals(maps.packageName, m.packageName)
        assertTrue(ItemAction.APP_INFO in m.actions)
    }
}

class HomeMenuTest {
    @Test fun emptySpaceMenuMatchesUx() {
        assertEquals(listOf(HomeAction.EDIT_LAYOUT, HomeAction.WIDGETS, HomeAction.RELOAD, HomeAction.SETTINGS, HomeAction.WALLPAPER), HomeMenu.actions(1))
        // #99: `Pages` right after `Edit layout`, only with 2 or more pages.
        assertEquals(listOf(HomeAction.EDIT_LAYOUT, HomeAction.PAGES, HomeAction.WIDGETS, HomeAction.RELOAD, HomeAction.SETTINGS, HomeAction.WALLPAPER), HomeMenu.actions(2))
        assertEquals(HomeMenu.actions(1), HomeMenu.actions(0))
    }
}

class DrawerMenuTest {
    private val personal = InstalledApp("p.a", "p.a.M", "A")
    private val work = InstalledApp("p.a", "p.a.M", "A", Profile.WORK)

    @Test fun actionsPerUx() {
        assertEquals(listOf(DrawerAction.ADD_TO_HOME, DrawerAction.HIDE, DrawerAction.APP_INFO), DrawerMenu.actions(personal, includeWork = false))
        assertEquals("work apps: Add to home only when included", listOf(DrawerAction.HIDE, DrawerAction.APP_INFO), DrawerMenu.actions(work, includeWork = false))
        assertTrue(DrawerAction.ADD_TO_HOME in DrawerMenu.actions(work, includeWork = true))
    }
}
