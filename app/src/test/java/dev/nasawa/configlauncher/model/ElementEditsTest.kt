package dev.nasawa.configlauncher.model

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.Elements
import dev.nasawa.configlauncher.config.ParseResult
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** #303 NW-6: grid-element Remove / Undo / Move / Resize (ux.md §16.4, §16.7.1, §16.7.2), each one write that replays by id. */
class ElementEditsTest {
    private fun cfg(s: String) = (ConfigCodec.decode(s) as? ParseResult.Ok)?.config ?: error("invalid: ${(ConfigCodec.decode(s) as ParseResult.Invalid).errors}")
    private fun app(id: String, x: Int, y: Int) = """{"id":"$id","type":"app","package":"p.$id","x":$x,"y":$y}"""
    private fun page(id: String, vararg items: String, extra: String = "") = """{"id":"$id"$extra,"items":[${items.joinToString(",")}]}"""
    private fun aw(id: String, page: String = "home", x: Int = 0, y: Int = 0, w: Int = 2, h: Int = 2, extra: String = "") =
        """{"id":"$id","type":"appWidget","requires":5,"page":"$page","x":$x,"y":$y,"w":$w,"h":$h$extra,"content":{"provider":{"package":"com.example","class":".W"}}}"""
    private fun doc(pages: List<String>, vararg els: String, cols: Int = 4, rows: Int = 4) =
        """{"schemaVersion":1,"grid":{"columns":$cols,"rows":$rows},"pages":[${pages.joinToString(",")}],"elements":[${els.joinToString(",")}]}"""
    private fun LauncherConfig.place(id: String) = Elements.placement(ElementEdits.element(this, id)!!)!!
    private fun LauncherConfig.ids() = elements.orEmpty().map { (it["id"] as JsonPrimitive).content }
    private fun apply(c: LauncherConfig, e: Edit) = Edits.apply(c, e)

    @Test fun removeDropsTheElementAndItsOverridesOnly() {
        val c = cfg(doc(listOf(page("home", extra = ""","overrides":{"w-a":{"enabled":false},"w-b":{"enabled":true}}"""), page("two", extra = ""","overrides":{"w-a":{"enabled":true}}""")),
            aw("w-a"), aw("w-b", x = 2, extra = ""","x-keep":true""")))
        val r = apply(c, Edit.RemoveElement("w-a"))
        assertTrue(r.applied)
        assertEquals(listOf("w-b"), r.config.ids())
        assertEquals(setOf("w-b"), r.config.pages[0].overrides!!.keys)
        assertNull(r.config.pages[1].overrides)
        // Everything else verbatim (incl. the other element's unknown keys).
        assertEquals(c.elements!![1], r.config.elements!![0])
        assertEquals(c.pages[0].items, r.config.pages[0].items)
        // Replay: already gone → skipped, nothing to write.
        assertFalse(apply(r.config, Edit.RemoveElement("w-a")).applied)
    }

    @Test fun undoPutsTheSameObjectBackAtItsIndexInItsOldCells() {
        val c = cfg(doc(listOf(page("home")), aw("w-a", extra = ""","x-mine":{"k":1}"""), aw("w-b", x = 2)))
        val el = ElementEdits.element(c, "w-a")!!
        val gone = apply(c, Edit.RemoveElement("w-a")).config
        assertEquals(ElementEdits.Plan.Same("home"), ElementEdits.restorePlan(gone, el))
        val back = apply(gone, Edit.RestoreElement(el, 0))
        assertTrue(back.applied)
        assertEquals(c.elements, back.config.elements)
    }

    @Test fun undoMovesToTheFirstFreeSpotOnItsPageThenLaterPagesButNeverAddsAPage() {
        val c = cfg(doc(listOf(page("home"), page("two"), page("three")), aw("w-a", x = 2, y = 2)))
        val el = ElementEdits.element(c, "w-a")!!
        val gone = apply(c, Edit.RemoveElement("w-a")).config
        // An app now sits in the old cells; (0,0) on home is free → moved there, w/h unchanged.
        val taken = gone.copy(pages = gone.pages.mapIndexed { i, p -> if (i == 0) p.copy(items = listOf(cfg(doc(listOf(page("home", app("x", 3, 3))))).pages[0].items[0])) else p })
        assertEquals(ElementEdits.Plan.Moved("home", 0, 0), ElementEdits.restorePlan(taken, el))
        val r = apply(taken, Edit.RestoreElement(el, 0)).config
        assertEquals(Elements.Placement("home", 0, 0, 2, 2), r.place("w-a"))
        // Only page/x/y changed; key order kept.
        assertEquals(el.keys.toList(), ElementEdits.element(r, "w-a")!!.keys.toList())
        // Home full → the following page.
        val homeFull = cfg(doc(listOf(page("home", *(0 until 4).flatMap { y -> (0 until 4).map { x -> app("h$x$y", x, y) } }.toTypedArray()), page("two"))))
        assertEquals(ElementEdits.Plan.Moved("two", 0, 0), ElementEdits.restorePlan(homeFull, el))
        // Its page is gone → scan from the first page.
        val noPage = cfg(doc(listOf(page("p1", app("a", 0, 0)), page("p2"))))
        assertEquals(ElementEdits.Plan.Moved("p1", 1, 0), ElementEdits.restorePlan(noPage, el))
        // No room anywhere → nothing written, no page added.
        val full = cfg(doc(listOf(page("home", *(0 until 4).flatMap { y -> (0 until 4).map { x -> app("h$x$y", x, y) } }.toTypedArray()))))
        assertEquals(ElementEdits.Plan.NoRoom, ElementEdits.restorePlan(full, el))
        val nr = apply(full, Edit.RestoreElement(el, 0))
        assertFalse(nr.applied); assertEquals(1, nr.config.pages.size)
    }

    @Test fun undoWritesNothingWhenTheAgentReAddedTheId() {
        val c = cfg(doc(listOf(page("home")), aw("w-a")))
        val el = ElementEdits.element(c, "w-a")!!
        val gone = apply(c, Edit.RemoveElement("w-a")).config
        // The agent adds a new element with the same id during the window (another spot, another provider).
        val agent = cfg(doc(listOf(page("home")), aw("w-a", x = 2, y = 2, w = 1, h = 1)))
        assertTrue(gone.elements.orEmpty().isEmpty())
        assertEquals(ElementEdits.Plan.Back, ElementEdits.restorePlan(agent, el))
        val r = apply(agent, Edit.RestoreElement(el, 0))
        assertFalse(r.applied)
        assertEquals(Elements.Placement("home", 2, 2, 1, 1), r.config.place("w-a"))
        // Replay of a queued Undo against a file where the agent re-added it: skipped too.
        assertFalse(Edits.apply(agent, Edit.RestoreElement(el, 5)).applied)
    }

    @Test fun moveWritesOnlyPageXYAndRefusesCollisionsAtWriteTime() {
        val c = cfg(doc(listOf(page("home", app("a", 0, 3)), page("two")), aw("w-a"), aw("w-b", x = 2, w = 2, h = 1)))
        val r = apply(c, Edit.MoveElement("w-a", "two", 1, 1))
        assertTrue(r.applied)
        assertEquals(Elements.Placement("two", 1, 1, 2, 2), r.config.place("w-a"))
        assertEquals(ElementEdits.element(c, "w-a")!!.keys.toList(), ElementEdits.element(r.config, "w-a")!!.keys.toList())
        assertEquals(c.elements!![1], r.config.elements!![1])
        // Overlapping itself is fine (one cell down on the same page).
        assertTrue(apply(c, Edit.MoveElement("w-a", "home", 0, 1)).applied)
        // Another element, an app, the grid edge or a missing page → skipped (move_element_taken, no write).
        assertFalse(apply(c, Edit.MoveElement("w-a", "home", 1, 0)).applied)
        assertFalse(apply(c, Edit.MoveElement("w-a", "home", 0, 2)).applied)
        assertFalse(apply(c, Edit.MoveElement("w-a", "home", 3, 0)).applied)
        assertFalse(apply(c, Edit.MoveElement("w-a", "gone", 0, 0)).applied)
        assertFalse(apply(c, Edit.MoveElement("w-none", "two", 0, 0)).applied)
        // Same place → no-op.
        assertFalse(apply(c, Edit.MoveElement("w-a", "home", 0, 0)).applied)
    }

    @Test fun moveReplaysAgainstTheFileAtWriteTime() {
        // Queued while the target was free; an agent edit puts an app there before the write → the replay skips it.
        val c = cfg(doc(listOf(page("home")), aw("w-a")))
        val move = Edit.MoveElement("w-a", "home", 2, 2)
        assertTrue(apply(c, move).applied)
        val agent = cfg(doc(listOf(page("home", app("z", 3, 3))), aw("w-a")))
        assertFalse(apply(agent, move).applied)
    }

    @Test fun resizeWritesOnlyWAndHAndStopsAtTakenCells() {
        val c = cfg(doc(listOf(page("home", app("a", 3, 0))), aw("w-a", w = 1, h = 1), aw("w-b", y = 2, w = 1, h = 1)))
        val r = apply(c, Edit.ResizeElement("w-a", 3, 2))
        assertTrue(r.applied)
        assertEquals(Elements.Placement("home", 0, 0, 3, 2), r.config.place("w-a"))
        assertFalse(apply(c, Edit.ResizeElement("w-a", 4, 1)).applied)   // the app at (3,0)
        assertFalse(apply(c, Edit.ResizeElement("w-a", 1, 3)).applied)   // w-b at (0,2)
        assertFalse(apply(c, Edit.ResizeElement("w-a", 1, 1)).applied)   // unchanged
        // #304: a start/top handle moves the anchor in the same write; only the keys that changed.
        val c2 = cfg(doc(listOf(page("home")), aw("w-m", x = 2, y = 2, w = 1, h = 1)))
        val grown = apply(c2, Edit.ResizeElement("w-m", 3, 2, x = 0, y = 1))
        assertTrue(grown.applied)
        assertEquals(Elements.Placement("home", 0, 1, 3, 2), grown.config.place("w-m"))
        assertEquals(ElementEdits.element(c2, "w-m")!!.keys.toList(), ElementEdits.element(grown.config, "w-m")!!.keys.toList())
        assertFalse(apply(c2, Edit.ResizeElement("w-m", 1, 1, x = 2, y = 2)).applied)   // unchanged
        assertFalse(apply(c, Edit.ResizeElement("w-a", 2, 1, x = 3)).applied)            // the app at (3,0)
        assertEquals(3, ElementEdits.maxFree(c, "w-a", horizontal = true, other = 1))
        assertEquals(2, ElementEdits.maxFree(c, "w-a", horizontal = false, other = 1))
    }
}
