package dev.nasawa.configlauncher.hosted

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.Elements
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.hosted.WidgetPlacement.Sizes
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.Edits
import dev.nasawa.configlauncher.model.LauncherConfig
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** #302 NW-5: size-to-cells and the free-rectangle search (ux.md §16.1). */
class WidgetPlacementTest {
    private fun cfg(s: String) = (ConfigCodec.decode(s) as? ParseResult.Ok)?.config ?: error("invalid: $s")
    private fun doc(cols: Int, rows: Int, pages: String, els: String = "") =
        """{"schemaVersion":1,"grid":{"columns":$cols,"rows":$rows},"pages":[$pages]${if (els.isEmpty()) "" else ""","elements":[$els]"""}}"""
    private fun app(id: String, x: Int, y: Int) = """{"id":"$id","type":"app","package":"p.${id.replace("-", "_")}","x":$x,"y":$y}"""
    private fun page(id: String, vararg items: String) = """{"id":"$id","items":[${items.joinToString(",")}]}"""
    private fun fullPage(id: String, cols: Int, rows: Int) =
        page(id, *(0 until rows).flatMap { y -> (0 until cols).map { x -> app("$id-$x-$y", x, y) } }.toTypedArray())
    private val rnd = { 7 }

    @Test fun defaultCellsPreferTargetCellsElseRoundMinUp() {
        // Target cells (API 31+) win.
        assertEquals(3 to 2, WidgetPlacement.defaultCells(Sizes(40f, 40f, 3, 2), 80f, 100f, 4, 5))
        // min 180×110 dp on 80×100 cells → 3×2 (rounded up, 0.5 dp tolerance as in HostedWidgets.cells).
        assertEquals(3 to 2, WidgetPlacement.defaultCells(Sizes(180f, 110f), 80f, 100f, 4, 5))
        assertEquals(2 to 1, WidgetPlacement.defaultCells(Sizes(160.4f, 100.4f), 80f, 100f, 4, 5))
        // Clamped to the grid, never below 1×1.
        assertEquals(4 to 5, WidgetPlacement.defaultCells(Sizes(1000f, 1000f, 0, 9), 80f, 100f, 4, 5))
        assertEquals(1 to 1, WidgetPlacement.defaultCells(Sizes(0f, 0f), 80f, 100f, 4, 5))
    }

    @Test fun freeRectSkipsItemsAndElementCellsRowByRow() {
        val c = cfg(doc(4, 5, page("home", app("a", 0, 0), app("b", 3, 0)),
            """{"id":"w-x","type":"appWidget","requires":5,"page":"home","x":0,"y":1,"w":2,"h":2,"content":{"provider":{"package":"a.b","class":".W"}}}"""))
        val home = c.pages.single()
        assertEquals(1 to 0, WidgetPlacement.freeRect(c, home, 2, 1))
        assertEquals(2 to 1, WidgetPlacement.freeRect(c, home, 2, 2))
        assertEquals(0 to 3, WidgetPlacement.freeRect(c, home, 4, 2))
        assertNull(WidgetPlacement.freeRect(c, home, 4, 3))
    }

    @Test fun placeUsesCurrentPageThenTheNextThenEarlierThenANewPage() {
        val c = cfg(doc(4, 5, fullPage("p1", 4, 5) + "," + page("p2", app("a", 0, 0)) + "," + page("p3")))
        // Current page p3 has room.
        assertEquals("p3", WidgetPlacement.place(c, "p3", "com.x", "com.x.W", 2, 2, "Clock", rnd)!!.element.page())
        // Current p1 is full → the next page, p2.
        val e = WidgetPlacement.place(c, "p1", "com.x", "com.x.W", 2, 2, "Clock", rnd)!!
        assertEquals("p2", e.element.page()); assertEquals(1, e.element.int("x")); assertEquals(0, e.element.int("y"))
        assertNull(e.newPage)
        // A 4×5 widget only fits an empty page: p3, even from p2 (wraps forward).
        assertEquals("p3", WidgetPlacement.place(c, "p2", "com.x", "com.x.W", 4, 5, null, rnd)!!.element.page())
        // Nothing fits anywhere → a new page (lowest unused page-N), in the same write.
        val full = cfg(doc(4, 5, fullPage("p1", 4, 5) + "," + fullPage("page-1", 4, 5)))
        val n = WidgetPlacement.place(full, "p1", "com.x", "com.x.W", 1, 1, null, rnd)!!
        assertEquals("page-2", n.newPage); assertEquals("page-2", n.element.page())
        assertEquals(0, n.element.int("x")); assertEquals(0, n.element.int("y"))
    }

    @Test fun twentyFullPagesIsHomeFull() {
        val c = cfg(doc(4, 5, (1..Edits.MAX_PAGES).joinToString(",") { fullPage("p$it", 4, 5) }))
        assertNull(WidgetPlacement.place(c, "p1", "com.x", "com.x.W", 1, 1, null, rnd))
    }

    @Test fun pickingWritesExactlyOneElementWithProviderPageAndCells() {
        val c = cfg(doc(4, 5, page("home", app("a", 0, 0))))
        val e = WidgetPlacement.place(c, "home", "com.example.clock", "com.example.clock.Digital", 2, 1, "Digital clock", rnd)!!
        val r = Edits.apply(c, e)
        assertTrue(r.applied)
        assertEquals(c.pages, r.config.pages)
        assertEquals(c.dock, r.config.dock)
        val el = r.config.elements!!.single()
        assertEquals(listOf("id", "type", "requires", "page", "x", "y", "w", "h", "content"), el.keys.toList())
        assertTrue(el["id"]!!.jsonPrimitive.content.startsWith("w-digital-clock-"))
        assertEquals("""{"provider":{"package":"com.example.clock","class":"com.example.clock.Digital"}}""", el["content"].toString())
        assertEquals(Elements.Placement("home", 1, 0, 2, 1), Elements.placement(el))
        // It round-trips through the codec and validator as a valid level-5 appWidget.
        assertTrue(ConfigCodec.decode(ConfigCodec.encode(r.config)) is ParseResult.Ok)
        // New page: that page and the element, nothing else.
        val full = cfg(doc(4, 5, fullPage("home", 4, 5)))
        val n = Edits.apply(full, WidgetPlacement.place(full, "home", "com.x", "com.x.W", 2, 2, null, rnd)!!)
        assertEquals(listOf("home", "page-1"), n.config.pages.map { it.id })
        assertTrue(n.config.pages[1].items.isEmpty())
        assertEquals(1, n.config.elements!!.size)
    }

    @Test fun addElementSkipsWhenTheCellsAreNoLongerFree() {
        val c = cfg(doc(4, 5, page("home")))
        val e = WidgetPlacement.place(c, "home", "com.x", "com.x.W", 2, 2, null, rnd)!!
        // A merge put an app into the rectangle before the edit replayed: skipped, not an overlap (B2).
        val moved = cfg(doc(4, 5, page("home", app("z", 1, 1))))
        assertFalse(Edits.apply(moved, e).applied)
        // Outside the grid is skipped too.
        assertFalse(Edits.apply(c, Edit.AddElement(WidgetPlacement.element("w-o", "home", 3, 0, 2, 1, "a.b", "a.b.W"))).applied)
    }

    /** The screen-matrix fixtures (the grids Grace's harness runs): every default size places, and never overlaps. */
    @Test fun screenMatrixGridsPlaceWithoutOverlap() {
        val dir = listOf(File("../scripts/screen-matrix"), File("scripts/screen-matrix")).first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue(files.size >= 5)
        for (f in files) {
            var c: LauncherConfig = cfg(f.readText())
            for ((w, h) in listOf(1 to 1, 2 to 1, 2 to 2, 4 to 1, 4 to 2, 3 to 3)) {
                val e = WidgetPlacement.place(c, c.pages.first().id, "com.x", "com.x.W$w$h", w, h, null, { w * 10 + h }) ?: continue
                val r = Edits.apply(c, e)
                assertTrue("${f.name} ${w}x$h", r.applied)
                c = r.config
                assertTrue("${f.name} ${w}x$h valid", ConfigCodec.decode(ConfigCodec.encode(c)) is ParseResult.Ok)
            }
        }
    }

    private fun JsonObject.page() = this["page"]!!.jsonPrimitive.content
    private fun JsonObject.int(k: String) = this[k]!!.jsonPrimitive.int
}
