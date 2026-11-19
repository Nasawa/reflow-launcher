package dev.nasawa.configlauncher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.ConfigLauncherApp
import dev.nasawa.configlauncher.MainActivity
import dev.nasawa.configlauncher.R
import org.junit.Assert.assertEquals
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

/** #328 ux.md §4.2: holding a dragged app at the window edge turns the page; past the last page it adds one (a draft). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xhdpi")
class EdgeDragTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(Seed()).around(compose)
    private val app get() = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()
    private val file get() = File(app.filesDir, "config/source/launcher.json")

    private class Seed : TestRule {
        override fun apply(base: Statement, d: Description) = object : Statement() {
            override fun evaluate() {
                val app = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()
                app.getSharedPreferences("settings", 0).edit().clear().putBoolean("appOnly", true).putBoolean("homePromptShown", true).commit()
                val f = File(app.filesDir, "config/source/launcher.json"); f.parentFile!!.mkdirs()
                val pages = d.getAnnotation(Pages::class.java)?.n
                f.writeText(if (pages == null) javaClass.getResource("/pager/pages3.json")!!.readText() else
                    """{"schemaVersion":1,"grid":{"columns":4,"rows":5},"pages":[""" +
                        (1..pages).joinToString(",") { """{"id":"p$it","items":[{"id":"i$it","type":"app","package":"com.android.settings","label":"QA Settings","x":0,"y":0}]}""" } +
                        """],"dock":[]}""")
                app.store.drafts.value = emptyList()
                kotlinx.coroutines.runBlocking { app.store.engine.poll(force = true) }
                base.evaluate()
            }
        }
    }
    @Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class Pages(val n: Int)

    private fun shown(text: String): Boolean { compose.waitForIdle(); return compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    private fun click(text: String) = compose.onAllNodes(hasText(text)).onFirst().performClick()
    private fun enterEdit() {
        compose.waitForIdle()
        // Landscape tiles may drop their label text, so match TalkBack's description too.
        compose.onAllNodes(hasText("QA Settings") or androidx.compose.ui.test.hasContentDescription("QA Settings", substring = true)).onFirst()
            .performTouchInput { longClick() }
        click(app.getString(R.string.action_edit_layout))
        assertTrue("edit mode", shown(app.getString(R.string.done)))
    }

    private val root get() = compose.onRoot().fetchSemanticsNode().boundsInRoot
    private fun bounds(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().first().boundsInRoot
    private fun pageShown(n: Int, of: Int) = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(app.getString(R.string.page_of, n, of)))
        .fetchSemanticsNodes().isNotEmpty()
    private fun frames(ms: Long) { compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }
    private fun saved(): dev.nasawa.configlauncher.model.LauncherConfig {
        kotlinx.coroutines.runBlocking { app.store.engine.retrySave() }
        return (dev.nasawa.configlauncher.config.ConfigCodec.decode(file.readText()) as dev.nasawa.configlauncher.config.ParseResult.Ok).config
    }

    /** Lifts "Row two" (home, 0,1) and parks the finger [edgeX] px from the window's left or right edge. */
    private fun liftToEdge(right: Boolean) {
        enterEdit()
        val tile = bounds("Row two")
        val r = root
        compose.mainClock.autoAdvance = false
        compose.onAllNodes(hasText("Row two")).onFirst().performTouchInput {
            down(center); advanceEventTime(1_000); moveBy(Offset(0f, 40f))
            val x = if (right) r.right - 6f else r.left + 6f
            moveTo(Offset(x - tile.left, center.y + 40f), delayMillis = 16)
        }
        frames(200)
    }
    /** Moves the held finger to the root point [p] and lifts it. */
    private fun dropAt(p: Offset) {
        val tile = bounds("Row two")
        compose.onAllNodes(hasText("Row two")).onFirst().performTouchInput { moveTo(p - tile.topLeft, delayMillis = 16); up() }
        frames(DragController.SETTLE_MS + DragController.LANDING_TIMEOUT_MS + 100)
        compose.mainClock.autoAdvance = true
    }

    @Test fun holdingAtTheEndEdgeTurnsToTheNextPageAndTheDropLandsThere() {
        val before = file.readText()
        liftToEdge(right = true)
        frames(DragController.EDGE_HOLD_MS - 300) // liftToEdge already ran 200 ms of the hold
        assertTrue("not before 600 ms", pageShown(1, 3))
        frames(600)
        assertTrue("next page after the hold", pageShown(2, 3))
        assertEquals("a turn writes nothing", before, file.readText())
        // Drop one cell right of "Second tile" (0,0 on page 2).
        val s = bounds("Second tile")
        dropAt(Offset(s.center.x + s.width, s.center.y))
        val c = saved()
        assertEquals(listOf("a-second", "a-set3"), c.pages.first { it.id == "second" }.items.map { it.id })
        val moved = c.pages.first { it.id == "second" }.items.first { it.id == "a-set3" }
        assertEquals(1 to 0, moved.x to moved.y)
        assertEquals(3, c.pages.size)
    }

    @Test fun pastTheLastPageItAddsOneEmptyPageAndTheDropWritesItOnce() {
        liftToEdge(right = true)
        frames(DragController.EDGE_HOLD_MS * 2 + 200) // t = 1600: turns at 600 (second) and 1200 (third)
        assertTrue(pageShown(3, 3))
        frames(DragController.EDGE_HOLD_MS * 2)  // t = 2800: past the last at 1800, a new page, then the scroll there
        assertEquals(listOf("home", "second", "third", "page-1"), app.store.shownConfig().pages.map { it.id })
        assertTrue("on the new page after third", pageShown(4, 4))
        frames(DragController.EDGE_HOLD_MS * 3)     // held on the new empty page: no more pages
        assertEquals(4, app.store.shownConfig().pages.size)
        val tile = bounds("Row two")                // out of the zone and back: still no second new page
        compose.onAllNodes(hasText("Row two")).onFirst().performTouchInput {
            moveTo(root.center - tile.topLeft, delayMillis = 16); moveTo(Offset(root.right - 6f, root.center.y) - tile.topLeft, delayMillis = 16)
        }
        frames(DragController.EDGE_HOLD_MS * 3)
        assertEquals(4, app.store.shownConfig().pages.size)
        dropAt(root.center)
        val c = saved()
        assertEquals(listOf("home", "second", "third", "page-1"), c.pages.map { it.id })
        assertEquals(listOf("a-set3"), c.pages[3].items.map { it.id })
        assertEquals("one write with the page", 2L, c.revision)
    }

    @Test fun holdingAtTheStartEdgeOfTheFirstPageDoesNothing() {
        val before = file.readText()
        liftToEdge(right = false)
        frames(DragController.EDGE_HOLD_MS * 3)
        assertTrue(pageShown(1, 3))
        assertEquals(3, app.store.shownConfig().pages.size)
        dropAt(root.center.copy(x = root.left + 6f)) // a drop in the edge zone, outside the grid: springs back
        assertEquals(before, file.readText())
    }

    @Test fun leavingTheEdgeBeforeTheHoldEndsCancelsTheTurn() {
        liftToEdge(right = true)
        frames(DragController.EDGE_HOLD_MS - 300)
        val tile = bounds("Row two")
        compose.onAllNodes(hasText("Row two")).onFirst().performTouchInput { moveTo(root.center - tile.topLeft, delayMillis = 16) }
        frames(DragController.EDGE_HOLD_MS * 2)
        assertTrue(pageShown(1, 3))
        compose.onAllNodes(hasText("Row two")).onFirst().performTouchInput { cancel() }
        frames(500); compose.mainClock.autoAdvance = true
    }

    @Config(qualifiers = "ar-rEG-ldrtl-w411dp-h891dp-port-xhdpi")
    @Test fun inRtlTheLeftEdgeIsTheEnd() {
        liftToEdge(right = false)
        frames(DragController.EDGE_HOLD_MS + 300)
        assertTrue("left edge = next page in RTL", pageShown(2, 3))
        compose.onAllNodes(hasText("Row two")).onFirst().performTouchInput { cancel() }
        frames(500); compose.mainClock.autoAdvance = true
    }
}
