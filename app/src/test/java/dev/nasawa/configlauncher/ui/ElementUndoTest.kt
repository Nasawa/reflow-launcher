package dev.nasawa.configlauncher.ui

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.hosted.HostedWidgets
import dev.nasawa.configlauncher.model.Edits
import dev.nasawa.configlauncher.model.LauncherConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** #303 NW-6 §16.7.1: hold before the Remove write, give back on a successful Undo, release only at the window's end. */
class ElementUndoTest {
    private fun cfg(s: String) = (ConfigCodec.decode(s) as? ParseResult.Ok)?.config ?: error("invalid")
    private fun aw(id: String, x: Int = 0, y: Int = 0, w: Int = 2, h: Int = 2) =
        """{"id":"$id","type":"appWidget","requires":5,"page":"home","x":$x,"y":$y,"w":$w,"h":$h,"content":{"provider":{"package":"com.example","class":".W"}}}"""
    private fun doc(vararg els: String, items: String = "") =
        """{"schemaVersion":1,"grid":{"columns":4,"rows":4},"pages":[{"id":"home","items":[$items]}],"elements":[${els.joinToString(",")}]}"""

    private class Harness(var c: LauncherConfig) {
        val log = mutableListOf<String>()
        val hold = object : ElementUndo.Hold {
            override fun hold(id: String): Boolean { log += "hold $id"; return true }
            override fun restore(id: String) { log += "restore $id" }
            override fun release(id: String) { log += "release $id" }
        }
        val undo = ElementUndo({ c }, { e -> val r = Edits.apply(c, e); log += "edit ${e::class.simpleName} ${r.applied}"; if (r.applied) c = r.config; r.applied }, listOf(hold, hold))
    }

    @Test fun holdsGoOnBeforeTheRemoveWrite() = runBlocking {
        val h = Harness(cfg(doc(aw("w-a"))))
        val r = h.undo.remove("w-a")!!
        assertEquals(listOf("hold w-a", "hold w-a", "edit RemoveElement true"), h.log)
        assertEquals(0, r.index)
        assertTrue(h.c.elements.orEmpty().isEmpty())
    }

    @Test fun aRemoveThatDoesNotApplyGivesTheHoldsBack() = runBlocking {
        val h = Harness(cfg(doc(aw("w-a"))))
        assertNull(h.undo.remove("w-none"))
        assertTrue(h.log.isEmpty())
        // Gone between the read and the write (e.g. an agent edit): the edit is skipped → holds restored at once.
        val c0 = h.c
        val gone = Harness(c0).apply { c = cfg(doc()) }
        val undo = ElementUndo({ c0 }, { e -> Edits.apply(gone.c, e).applied.also { gone.log += "edit $it" } }, listOf(gone.hold))
        assertNull(undo.remove("w-a"))
        assertEquals(listOf("hold w-a", "edit false", "restore w-a"), gone.log)
    }

    @Test fun successfulUndoRestoresHoldsAfterItsEdit() = runBlocking {
        val h = Harness(cfg(doc(aw("w-a"), aw("w-b", x = 2))))
        val r = h.undo.remove("w-a")!!
        h.log.clear()
        assertEquals(ElementUndo.Undone.Same, h.undo.undo(r))
        assertEquals(listOf("edit RestoreElement true", "restore w-a", "restore w-a"), h.log)
        assertEquals(listOf("w-a", "w-b"), h.c.elements!!.map { it["id"].toString().trim('"') })
    }

    @Test fun undoLandsElsewhereWhenTheOldCellsAreTaken() = runBlocking {
        val h = Harness(cfg(doc(aw("w-a"))))
        val r = h.undo.remove("w-a")!!
        h.c = cfg(doc(items = """{"id":"x","type":"app","package":"p.x","x":0,"y":0}"""))
        assertEquals(ElementUndo.Undone.Moved("home"), h.undo.undo(r))
    }

    @Test fun undoThatWritesNothingReleasesNothingEarly() = runBlocking {
        val h = Harness(cfg(doc(aw("w-a"))))
        val r = h.undo.remove("w-a")!!
        h.log.clear()
        // The agent re-added the id → Back; no edit, no restore, no release.
        h.c = cfg(doc(aw("w-a", x = 2, y = 2, w = 1, h = 1)))
        assertEquals(ElementUndo.Undone.Back, h.undo.undo(r))
        assertTrue(h.log.isEmpty())
        // No room → NoRoom, same.
        h.c = cfg(doc(aw("w-z", w = 4, h = 4)))
        assertEquals(ElementUndo.Undone.NoRoom, h.undo.undo(r))
        assertTrue(h.log.isEmpty())
        // The window ends → released.
        h.undo.release(r)
        assertEquals(listOf("release w-a", "release w-a"), h.log)
    }

    @Test fun talkBackActionsFollowTheTable() {
        assertEquals(listOf(ElementAction.RESIZE, ElementAction.MOVE_TO, ElementAction.REMOVE), ElementActionList.of(ElementKind.APP_WIDGET))
        assertEquals(listOf(ElementAction.RESIZE, ElementAction.MOVE_TO, ElementAction.REMOVE), ElementActionList.of(ElementKind.LIVE_CARD))
        assertEquals(listOf(ElementAction.MOVE_TO, ElementAction.REMOVE), ElementActionList.of(ElementKind.CONSENT_TILE))
    }

    @Test fun dragSnapsToWholeCellsAndMirrorsInRtl() {
        assertEquals(1 to 2, ElementActionList.cellDelta(110f, 260f, 100f, 120f, rtl = false))
        assertEquals(-1 to 0, ElementActionList.cellDelta(110f, 40f, 100f, 120f, rtl = true))
        assertEquals(0 to 0, ElementActionList.cellDelta(49f, -59f, 100f, 120f, rtl = false))
    }

    @Test fun resizeLimitsUseProviderMinMaxCardMinSpanAndTheGrid() {
        val c = cfg(doc(aw("w-a", x = 1, y = 1, w = 2, h = 2),
            """{"id":"w-c","type":"card","requires":4,"page":"home","x":0,"y":3,"w":3,"h":1,"minSpan":{"w":2,"h":1},"capabilities":[{"kind":"data.file"}],"layouts":{"3x1":[{"type":"text","text":"hi"}]}}"""))
        // Provider min 2×1, max 3×5 → grid caps the max at 3×3 from (1,1).
        assertEquals(ElementInfo.Limits(2, 3, 1, 3), ElementInfo.limits(c, "w-a", HostedWidgets.Facts("W", 2, 1, 3, 5)))
        // A provider that can't resize horizontally keeps w.
        assertEquals(ElementInfo.Limits(2, 2, 1, 3), ElementInfo.limits(c, "w-a", HostedWidgets.Facts("W", 1, 1, null, null, resizeW = false)))
        // No facts (provider missing): grid only.
        assertEquals(ElementInfo.Limits(1, 3, 1, 3), ElementInfo.limits(c, "w-a"))
        // Card: minSpan.
        assertEquals(ElementInfo.Limits(2, 4, 1, 1), ElementInfo.limits(c, "w-c"))
        // An out-of-range size from the file stays reachable.
        assertEquals(ElementInfo.Limits(2, 3, 1, 3), ElementInfo.limits(c, "w-a", HostedWidgets.Facts("W", 3, 1, 3, 3)))
        assertEquals("w-c", ElementInfo.name(c, "w-c"))
        assertEquals("Weather", ElementInfo.name(c, "w-a", "Weather"))
    }

    @Test fun moveToListsOnlyPagesWithRoom() {
        val c = cfg("""{"schemaVersion":1,"grid":{"columns":4,"rows":4},"pages":[{"id":"home","items":[]},{"id":"two","items":[{"id":"x","type":"app","package":"p.x","x":1,"y":1}]}],"elements":[${aw("w-a", w = 3, h = 3)}]}""")
        assertEquals(setOf("home"), ElementInfo.pagesWithRoom(c, "w-a"))
        assertEquals(emptySet<String>(), ElementInfo.pagesWithRoom(cfg(doc(aw("w-a", w = 4, h = 4))).copy(pages = emptyList()), "w-a"))
    }
}
