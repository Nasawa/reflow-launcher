package dev.nasawa.configlauncher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.ConfigLauncherApp
import dev.nasawa.configlauncher.MainActivity
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.Elements
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.LauncherConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.annotation.Config
import java.io.File

/**
 * #344 ux.md §4.2: a widget, a live card or a consent tile dragged by its span (edit mode) turns the page at the window
 * edge exactly as a lifted app does (#328, [EdgeDragTest]): 600 ms hold in the fixed 24 dp zone, repeating while held,
 * a new page past the last, the landscape rail's inner edge, mirrored in RTL; the drop lands on the page shown, under
 * the usual free-rectangle rule. Folders are items and take the app path; one test pins that too.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xhdpi")
class ElementEdgeDragTest {
    private companion object { const val SETTLE_FRAMES = 120 } // 2 s of frames: a bound, not a wait

    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(Seed()).around(compose)
    private val app get() = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()
    private val file get() = File(app.filesDir, "config/source/launcher.json")

    @Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class Kind(val k: String)
    @Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class Pages(val n: Int)
    /** p2 has an app at (1,1), the cell the element holds on p1. */
    @Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class TakenOnP2

    private class Seed : TestRule {
        override fun apply(base: Statement, d: Description) = object : Statement() {
            override fun evaluate() {
                val app = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()
                app.getSharedPreferences("settings", 0).edit().clear().putBoolean("appOnly", true).putBoolean("homePromptShown", true).commit()
                val f = File(app.filesDir, "config/source/launcher.json"); f.parentFile!!.mkdirs()
                f.writeText(doc(d.getAnnotation(Kind::class.java)?.k ?: "widget", d.getAnnotation(Pages::class.java)?.n ?: 3,
                    takenOnP2 = d.getAnnotation(TakenOnP2::class.java) != null))
                app.store.drafts.value = emptyList()
                kotlinx.coroutines.runBlocking { app.store.engine.poll(force = true) }
                base.evaluate()
            }
        }
    }

    private fun shown(text: String): Boolean { compose.waitForIdle(); return compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    private fun enterEdit() {
        compose.waitForIdle()
        compose.onAllNodes(hasText("QA Settings") or hasContentDescription("QA Settings", substring = true)).onFirst()
            .performTouchInput { longClick() }
        compose.onAllNodes(hasText(app.getString(R.string.action_edit_layout))).onFirst().performClick()
        assertTrue("edit mode", shown(app.getString(R.string.done)))
    }
    private val root get() = compose.onRoot().fetchSemanticsNode().boundsInRoot
    /** The element's span on screen (its visible tile, labelled "Weather" for every kind). */
    private fun element(): Rect = compose.onAllNodes(hasContentDescription("Weather", substring = true)).fetchSemanticsNodes().first().boundsInRoot
    private fun pageShown(n: Int, of: Int) = compose.onAllNodes(hasContentDescription(app.getString(R.string.page_of, n, of)))
        .fetchSemanticsNodes().isNotEmpty()
    private fun frames(ms: Long) { compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }
    private fun awaitPagerSettledOn(n: Int, of: Int) {
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[HomeViewModel::class.java]
        compose.waitUntil("ui state has $of pages", 10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            vm.ui.value.load.config.pages.size == of
        }
        repeat(SETTLE_FRAMES) {
            if (pageShown(n, of)) return
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        }
        assertTrue("pager settled on page $n of $of", pageShown(n, of))
    }
    /** The drop's write runs on the store's IO scope: wait (real time, clock stopped) until the engine has [page]. */
    private fun awaitOn(page: String) = compose.waitUntil("element on $page", 10_000) {
        app.store.engine.state.value.config.elements.orEmpty().any { Elements.placement(it)?.page == page }
    }
    private fun saved(): LauncherConfig {
        kotlinx.coroutines.runBlocking { app.store.engine.retrySave() }
        return (ConfigCodec.decode(file.readText()) as ParseResult.Ok).config
    }
    private fun placed(c: LauncherConfig) = Elements.placement(c.elements!!.single())!!

    /** Where the drag started (root px); every move is relative to it, through the root (the page scrolls away). */
    private var start = Offset.Zero

    /** Long-presses the element's centre and parks the finger at root x [x] (same height). */
    private fun liftTo(x: Float) {
        enterEdit()
        val e = element(); start = e.center
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput {
            down(start); advanceEventTime(1_000); moveBy(Offset(0f, 40f))
            moveTo(Offset(x, start.y + 40f), delayMillis = 16)
        }
        frames(200)
    }
    private fun moveTo(p: Offset) { compose.onRoot().performTouchInput { moveTo(p, delayMillis = 16) }; frames(32) }
    private fun dropAt(p: Offset) {
        compose.onRoot().performTouchInput { moveTo(p, delayMillis = 16); up() }
        frames(500)
        compose.mainClock.autoAdvance = true
    }
    private fun cancel() { compose.onRoot().performTouchInput { cancel() }; frames(500); compose.mainClock.autoAdvance = true }

    /** Hold at the end edge: page 2 at 600 ms (not before); the drop on its cell (1,1) moves the element there. */
    private fun turnsAndDropsOnPage2() {
        assertEquals(1 to 1, placed(saved()).let { it.x to it.y })
        val before = file.readText()
        liftTo(root.right - 6f)
        frames(DragController.EDGE_HOLD_MS - 300)
        assertTrue("not before 600 ms", pageShown(1, 3))
        frames(600)
        assertTrue("next page after the hold", pageShown(2, 3))
        assertEquals("a turn writes nothing", before, file.readText())
        dropAt(start) // the same screen cell, now on page 2
        awaitOn("p2")
        val p = placed(saved())
        assertEquals(Triple("p2", 1, 1), Triple(p.page, p.x, p.y))
        assertEquals(3, saved().pages.size)
    }

    @Kind("widget") @Test fun aWidgetHeldAtTheEdgeTurnsThePageAndLandsThere() = turnsAndDropsOnPage2()
    @Kind("live") @Test fun aLiveCardHeldAtTheEdgeTurnsThePageAndLandsThere() = turnsAndDropsOnPage2()
    @Kind("consent") @Test fun aConsentTileHeldAtTheEdgeTurnsThePageAndLandsThere() = turnsAndDropsOnPage2()

    @Kind("widget") @Test fun theTurnRepeatsWhileHeldAndPastTheLastPageAddsOneThatTheDropWritesOnce() {
        liftTo(root.right - 6f)
        frames(DragController.EDGE_HOLD_MS * 2 + 200) // t = 1600: turns at 600 (p2) and 1200 (p3)
        assertTrue(pageShown(3, 3))
        frames(DragController.EDGE_HOLD_MS)            // t = 2200: past the last at 1800, a new (draft) page
        assertEquals(listOf("p1", "p2", "p3", "page-1"), app.store.shownConfig().pages.map { it.id })
        awaitPagerSettledOn(4, 4)
        frames(DragController.EDGE_HOLD_MS * 3)        // held on the new empty page: no more pages
        assertEquals(4, app.store.shownConfig().pages.size)
        dropAt(start)
        awaitOn("page-1")
        val c = saved()
        assertEquals(listOf("p1", "p2", "p3", "page-1"), c.pages.map { it.id })
        assertEquals(Triple("page-1", 1, 1), placed(c).let { Triple(it.page, it.x, it.y) })
        assertEquals("one write with the page", 2L, c.revision)
    }

    @Kind("live") @Test fun aTakenCellOnTheTurnedPageIsRefusedAndNothingIsWritten() {
        val before = file.readText()
        liftTo(root.right - 6f)
        val cell = element().size
        frames(DragController.EDGE_HOLD_MS + 300)
        assertTrue(pageShown(2, 3))
        // p2's app sits at (0,0): one cell up and left of the element's own cell.
        dropAt(start - Offset(cell.width, cell.height))
        assertEquals(before, file.readText())
        assertEquals("p1", placed(saved()).page)
    }

    @Kind("consent") @Test fun leavingTheEdgeBeforeTheHoldEndsCancelsTheTurn() {
        liftTo(root.right - 6f)
        frames(DragController.EDGE_HOLD_MS - 300)
        moveTo(root.center)
        frames(DragController.EDGE_HOLD_MS * 2)
        assertTrue(pageShown(1, 3))
        cancel()
    }

    @Kind("widget") @Test fun theStartEdgeOfTheFirstPageDoesNothing() {
        val before = file.readText()
        liftTo(root.left + 6f)
        frames(DragController.EDGE_HOLD_MS * 3)
        assertTrue(pageShown(1, 3))
        assertEquals(3, app.store.shownConfig().pages.size)
        cancel()
        assertEquals(before, file.readText())
    }

    @Config(qualifiers = "ar-rEG-ldrtl-w411dp-h891dp-port-xhdpi")
    @Kind("widget") @Test fun inRtlTheLeftEdgeIsTheEnd() {
        liftTo(root.left + 6f)
        frames(DragController.EDGE_HOLD_MS + 300)
        assertTrue("left edge = next page in RTL", pageShown(2, 3))
        cancel()
    }

    @Config(qualifiers = "w891dp-h411dp-land-xhdpi")
    @Kind("live") @Test fun inLandscapeTheZoneIsAtTheRailsInnerEdge() {
        enterEdit()
        val rail = compose.onAllNodes(hasTestTag("dockRail")).fetchSemanticsNodes().first().boundsInRoot
        val e = element(); start = e.center
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput {
            down(start); advanceEventTime(1_000); moveBy(Offset(0f, 20f))
            moveTo(Offset(rail.left - 6f, start.y + 20f), delayMillis = 16)
        }
        frames(200 + DragController.EDGE_HOLD_MS + 300)
        assertTrue("next page from the rail's inner edge", pageShown(2, 3))
        cancel()
    }

    // ---- #345 review follow-ups ----

    /** Nodes the user can scroll (the pager's ScrollBy is there only while user scrolling is on). */
    private fun scrollables() = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
        androidx.compose.ui.semantics.SemanticsActions.ScrollBy)).fetchSemanticsNodes().size

    /** (a) Pager scrolling is off while an element is dragged (held mid-screen, no edge), and back on after the drop. */
    @Kind("widget") @Test fun thePagerDoesNotScrollDuringAnElementDrag() {
        enterEdit()
        compose.waitForIdle()
        val idle = scrollables()
        assertTrue("the pager scrolls in edit mode", idle > 0)
        val e = element(); start = e.center
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { down(start); advanceEventTime(1_000); moveBy(Offset(0f, 40f)) }
        frames(200)
        assertEquals("one scrollable fewer: the pager", idle - 1, scrollables())
        cancel()
        assertEquals(idle, scrollables())
    }

    /**
     * (b) The drop check uses the page showing, not the element's own: (1,1) is free on p1 (its own cell) but taken on
     * p2. A check against p1 would let the drop through to a write that is refused ("Couldn't move…" snackbar).
     */
    @Kind("widget") @TakenOnP2 @Test fun theDropCheckUsesThePageShowing() {
        val before = file.readText()
        liftTo(root.right - 6f)
        frames(DragController.EDGE_HOLD_MS + 300)
        assertTrue(pageShown(2, 3))
        dropAt(start)
        // A wrong check would write on the store's IO scope and post the snackbar from there: give it a bounded real-time
        // window to appear (it does within it under the mutant), and expect it never to.
        val taken = app.getString(R.string.move_element_taken, "Weather")
        val appeared = runCatching { compose.waitUntil("snackbar", 3_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.onAllNodes(hasText(taken)).fetchSemanticsNodes().isNotEmpty()
        } }.isSuccess
        assertFalse("refused before any write: no snackbar", appeared)
        assertEquals(before, file.readText())
        assertEquals("p1", placed(saved()).page)
    }

    /**
     * #350 review nit: the outline on the page showing is checked against that page too. Hovering (not dropping) over
     * (1,1) after the turn: free on p1 (the element's own cell), taken on p2, so the outline is red; a check against
     * the element's own page would draw it white.
     */
    @Kind("widget") @TakenOnP2 @Test fun theOutlineOnThePageShowingIsRedOverItsTakenCell() {
        liftTo(root.right - 6f)
        frames(DragController.EDGE_HOLD_MS + 300)
        assertTrue(pageShown(2, 3))
        moveTo(start) // the same screen cell, now p2's (1,1): "Taken"
        fun tagged(t: String) = compose.onAllNodes(hasTestTag(t), useUnmergedTree = true).fetchSemanticsNodes().size
        assertEquals("red over the taken cell", 1, tagged(ElementOutlineTags.TAKEN))
        assertEquals("no white outline anywhere", 0, tagged(ElementOutlineTags.FREE))
        cancel()
    }

    /** Folders are items: they turn the page through the app path (#328) and land on the page shown. */
    @Kind("folder") @Test fun aFolderHeldAtTheEdgeTurnsThePageAndLandsThere() {
        enterEdit()
        val f = compose.onAllNodes(hasText("Tools")).fetchSemanticsNodes().first().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onAllNodes(hasText("Tools")).onFirst().performTouchInput {
            down(center); advanceEventTime(1_000); moveBy(Offset(0f, 40f))
            moveTo(Offset(root.right - 6f - f.left, center.y + 40f), delayMillis = 16)
        }
        frames(200 + DragController.EDGE_HOLD_MS + 300)
        assertTrue(pageShown(2, 3))
        val target = f.center // the folder's own cell (2,0), now on p2
        val now = compose.onAllNodes(hasText("Tools")).fetchSemanticsNodes().first().boundsInRoot // its page scrolled away
        compose.onAllNodes(hasText("Tools")).onFirst().performTouchInput { moveTo(target - now.topLeft, delayMillis = 16); up() }
        frames(DragController.SETTLE_MS + DragController.LANDING_TIMEOUT_MS + 100)
        compose.mainClock.autoAdvance = true
        compose.waitUntil("folder on p2", 10_000) { app.store.engine.state.value.config.pages.first { it.id == "p2" }.items.any { it.id == "f-tools" } }
        val c = saved()
        assertEquals(listOf("i2", "f-tools"), c.pages.first { it.id == "p2" }.items.map { it.id })
        assertEquals(emptyList<String>(), c.pages.first { it.id == "p1" }.items.filter { it.id == "f-tools" }.map { it.id })
    }
}

/** p1 has "QA Settings" (0,0) and the element of [kind] at (1,1), 1×1, labelled "Weather"; p2.. have an app at (0,0). */
private fun doc(kind: String, pages: Int, takenOnP2: Boolean = false): String {
    val folder = """,{"id":"f-tools","type":"folder","label":"Tools","x":2,"y":0,"items":[{"id":"a-in","type":"app","package":"com.android.settings"}]}"""
    val pageList = (1..pages).joinToString(",") { n ->
        val items = """{"id":"i$n","type":"app","package":"com.android.settings","label":"QA Settings","x":0,"y":0}""" +
            (if (n == 1 && kind == "folder") folder else "") +
            (if (n == 2 && takenOnP2) """,{"id":"t2","type":"app","package":"com.android.settings","label":"Taken","x":1,"y":1}""" else "")
        """{"id":"p$n","items":[$items]}"""
    }
    val el = when (kind) {
        "widget" -> """{"id":"w-1","type":"appWidget","requires":5,"page":"p1","x":1,"y":1,"w":1,"h":1,
            "content":{"provider":{"package":"com.example.weather","class":".WeatherWidget"},"profile":"personal","label":"Weather"},"enabled":true}"""
        "live" -> """{"id":"w-1","type":"card","requires":4,"page":"p1","x":1,"y":1,"w":1,"h":1,"title":"Weather",
            "capabilities":[{"kind":"data.file"}],"layouts":{"1x1":[{"type":"text","text":"hi"}]}}"""
        "consent" -> """{"id":"w-1","type":"card","requires":4,"page":"p1","x":1,"y":1,"w":1,"h":1,"title":"Weather","source":"blog.anigeek.com",
            "capabilities":[{"kind":"data.file"},{"kind":"ui.openUrl"}],"layouts":{"1x1":[{"type":"text","text":"hi"}]}}"""
        else -> null
    }
    return """{"schemaVersion":1,"revision":1,"grid":{"columns":4,"rows":5},"pages":[$pageList],"dock":[]""" +
        (el?.let { ""","elements":[$it]""" } ?: "") + "}"
}
