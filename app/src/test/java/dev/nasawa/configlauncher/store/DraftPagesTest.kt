package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.AppItem
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.Edits
import dev.nasawa.configlauncher.model.NewPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #329 ux.md §4.2 `+ Page` (Pragma, #322): an empty page is never written; the first drop onto it carries it. */
class DraftPagesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun doc(rev: Int = 1, pages: String = """{"id":"home","items":[{"id":"a","type":"app","package":"p.a","x":0,"y":0}]},{"id":"two","items":[{"id":"b","type":"app","package":"p.b","x":0,"y":0}]}""") =
        """{"schemaVersion":1,"revision":$rev,"grid":{"columns":4,"rows":5},"pages":[$pages],"dock":[]}"""
    private fun cfg(s: String) = (ConfigCodec.decode(s) as ParseResult.Ok).config
    private fun ids(c: dev.nasawa.configlauncher.model.LauncherConfig) = c.pages.map { it.id }

    @Test fun overlayInsertsAfterTheCurrentPageWithTheLowestFreeId() {
        val c = cfg(doc())
        assertEquals("page-1", DraftPages.nextId(c))
        val one = listOf(NewPage("page-1", "home"))
        assertEquals(listOf("home", "page-1", "two"), ids(DraftPages.overlay(c, one)))
        assertEquals("page-2", DraftPages.nextId(DraftPages.overlay(c, one)))
        // A second one added while on page-1 goes after it; a draft whose page went away goes last.
        val two = one + NewPage("page-2", "page-1") + NewPage("page-3", "gone")
        assertEquals(listOf("home", "page-1", "page-2", "two", "page-3"), ids(DraftPages.overlay(c, two)))
        // The file got a page with that id (an agent's): shown once, as the file has it.
        val agent = cfg(doc(pages = """{"id":"home","items":[]},{"id":"page-1","items":[{"id":"z","type":"app","package":"p.z","x":1,"y":1}]}"""))
        assertEquals(listOf("home", "page-1"), ids(DraftPages.overlay(agent, one)))
        assertTrue(DraftPages.prune(agent, one).isEmpty())
    }

    @Test fun noIdAtTwentyPages() {
        val pages = (1..20).joinToString(",") { """{"id":"p$it","items":[]}""" }
        assertNull(DraftPages.nextId(cfg(doc(pages = pages))))
        val nineteen = cfg(doc(pages = (1..19).joinToString(",") { """{"id":"p$it","items":[]}""" }))
        assertNull("a draft counts", DraftPages.nextId(DraftPages.overlay(nineteen, listOf(NewPage("page-1", "p1")))))
    }

    @Test fun carryWrapsOnlyEditsThatLandOnADraftAnchoredToAPageTheFileHas() {
        val c = cfg(doc())
        val drafts = listOf(NewPage("page-1", "home"), NewPage("page-2", "page-1"))
        val other = Edit.MoveToCell("a", "two", 1, 1)
        assertSame(other, DraftPages.carry(c, drafts, other))
        val onto = Edit.MoveToCell("a", "page-2", 1, 1)
        // page-1 is still empty: it isn't written; page-2 goes right after home, where it shows.
        assertEquals(Edit.OnNewPages(listOf(NewPage("page-2", "home")), onto), DraftPages.carry(c, drafts, onto))
        val r = Edits.apply(c, DraftPages.carry(c, drafts, onto))
        assertTrue(r.applied)
        assertEquals(listOf("home", "page-2", "two"), ids(r.config))
        assertEquals(listOf("a"), r.config.pages[1].items.map { it.id })
        // Element moves and widget adds onto a draft are carried too.
        assertTrue(DraftPages.carry(c, drafts, Edit.MoveElement("w", "page-1", 0, 0)) is Edit.OnNewPages)
        assertTrue(DraftPages.carry(c, drafts, Edit.Add(AppItem("n", "p.n"), "page-1")) is Edit.OnNewPages)
    }

    @Test fun onNewPagesIsAllOrNothingAndRespectsTheCap() {
        val c = cfg(doc())
        val refused = Edit.OnNewPages(listOf(NewPage("page-1", "home")), Edit.MoveToCell("missing", "page-1", 0, 0))
        val r = Edits.apply(c, refused)
        assertFalse(r.applied); assertEquals(ids(c), ids(r.config))
        val full = cfg(doc(pages = (1..20).joinToString(",") { """{"id":"p$it","items":[{"id":"i$it","type":"app","package":"p.$it","x":0,"y":0}]}""" }))
        assertFalse(Edits.apply(full, Edit.OnNewPages(listOf(NewPage("page-1", "p1")), Edit.MoveToCell("i1", "page-1", 0, 0))).applied)
    }

    private fun engine(): Pair<ConfigEngine, LocalFileSource> = runBlocking {
        val src = LocalFileSource(tmp.newFolder()); src.file.writeText(doc()); src.file.setLastModified(1000)
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), stabilityMs = 0); e.poll(); e to src
    }
    private fun onDisk(src: LocalFileSource) = cfg(src.file.readText())

    @Test fun theFirstDropOntoANewPageIsTheOneWriteAndIncludesThePage() = runBlocking {
        val (e, src) = engine()
        val before = src.file.readText()
        val drafts = listOf(NewPage(DraftPages.nextId(DraftPages.overlay(e.state.value.config, emptyList()))!!, "home"))
        assertEquals("adding the page writes nothing", before, src.file.readText())
        assertTrue(e.edit(DraftPages.carry(e.state.value.config, drafts, Edit.MoveToCell("b", "page-1", 2, 3))))
        assertTrue(e.flush())
        val c = onDisk(src)
        assertEquals("inserted after the current page, not appended", listOf("home", "page-1", "two"), ids(c))
        assertEquals(listOf("b"), c.pages[1].items.map { it.id })
        assertEquals("one write", 2L, c.revision)
        assertTrue(DraftPages.prune(e.state.value.config, drafts).isEmpty())
    }

    @Test fun aConcurrentAgentWriteIsMergedWithTheNewPage() = runBlocking {
        val (e, src) = engine()
        val drafts = listOf(NewPage("page-1", "home"))
        assertTrue(e.edit(DraftPages.carry(e.state.value.config, drafts, Edit.MoveToCell("b", "page-1", 0, 0))))
        // The agent renames 'a' and adds a dock app before our debounced write.
        src.file.writeText(doc(rev = 7).replace("\"package\":\"p.a\"", "\"package\":\"p.a\",\"label\":\"Ay\"")
            .replace("\"dock\":[]", "\"dock\":[{\"id\":\"c\",\"type\":\"app\",\"package\":\"p.c\"}]"))
        src.file.setLastModified(5000)
        assertTrue(e.flush())
        val c = onDisk(src)
        assertEquals(listOf("home", "page-1", "two"), ids(c))
        assertEquals(listOf("b"), c.pages[1].items.map { it.id })
        assertEquals("Ay", c.pages[0].items.single().label)
        assertEquals(listOf("c"), c.dock!!.map { it.id })
    }
}
