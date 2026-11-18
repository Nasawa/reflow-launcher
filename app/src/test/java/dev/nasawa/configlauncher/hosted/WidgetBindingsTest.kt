package dev.nasawa.configlauncher.hosted

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.hosted.WidgetBindings.Binding
import dev.nasawa.configlauncher.hosted.WidgetBindings.Provider
import dev.nasawa.configlauncher.hosted.WidgetBindings.State
import dev.nasawa.configlauncher.store.ConfigEngine
import dev.nasawa.configlauncher.store.LastGoodStore
import dev.nasawa.configlauncher.store.LocalFileSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #299 NW-2: device-local appWidget bindings (native-widgets.md §2.5). */
class WidgetBindingsTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Our host's allocated IDs; [deleted] records frees in order. */
    private class FakeHost(vararg ids: Int) : WidgetBindings.Host {
        val alive = ids.toMutableSet()
        val deleted = mutableListOf<Int>()
        override fun ids() = alive.toIntArray()
        override fun delete(appWidgetId: Int) { alive -= appWidgetId; deleted += appWidgetId }
    }

    private val weather = Provider("com.example.weather", ".WeatherWidget")
    private fun bound(id: Int, p: Provider = weather, profile: String = "personal") = Binding(id, p, profile, State.BOUND)

    private fun aw(id: String, cls: String = ".WeatherWidget", x: Int = 0, extra: String = "") =
        """{"id":"$id","type":"appWidget","requires":5$extra,"page":"home","x":$x,"y":1,"w":1,"h":1,"content":{"provider":{"package":"com.example.weather","class":"$cls"}}}"""
    private fun doc(vararg els: String) =
        """{"schemaVersion":1,"pages":[{"id":"home","items":[]}],"elements":[${els.joinToString(",")}]}"""
    private fun cfg(s: String) = (ConfigCodec.decode(s) as ParseResult.Ok).config

    @Test fun saveAndLoadSurviveANewInstance() {
        val dir = tmp.newFolder()
        val host = FakeHost(7)
        WidgetBindings(dir, host).put("w-1", bound(7))
        assertTrue(File(dir, WidgetBindings.FILE).isFile)
        assertFalse(File(dir, "${WidgetBindings.FILE}.tmp").exists())
        // Process death: a new store reads the same binding back.
        assertEquals(bound(7), WidgetBindings(dir, host).binding("w-1"))
    }

    @Test fun corruptFileStartsEmptyAndFreesOrphansOnTheNextSettle() {
        val dir = tmp.newFolder()
        File(dir, WidgetBindings.FILE).writeText("{not json")
        val host = FakeHost(7, 8)
        val b = WidgetBindings(dir, host)
        assertTrue(b.all().isEmpty())
        b.settle(cfg(doc(aw("w-1"))))
        assertEquals(listOf(7, 8), host.deleted.sorted())
    }

    @Test fun elementThatLeftTheFileIsFreedOnSettle() {
        val host = FakeHost(7, 8)
        val b = WidgetBindings(tmp.newFolder(), host).apply { put("w-1", bound(7)); put("w-2", bound(8)) }
        b.settle(cfg(doc(aw("w-1"))))
        assertEquals(listOf(8), host.deleted)
        assertEquals(setOf("w-1"), b.all().keys)
    }

    @Test fun providerOrProfileChangeFreesTheIdForSetup() {
        val host = FakeHost(7, 8, 9)
        val b = WidgetBindings(tmp.newFolder(), host)
        b.put("w-1", bound(7)); b.put("w-2", bound(8)); b.put("w-3", bound(9, Provider("com.example.weather", "com.example.weather.WeatherWidget")))
        b.settle(cfg(doc(aw("w-1", cls = ".Other"), aw("w-2", x = 1).replace("\"}}}", "\"},\"profile\":\"work\"}}"), aw("w-3", x = 2))))
        assertEquals(listOf(7, 8), host.deleted)   // w-3: the same class written in full is not a change
        assertEquals(setOf("w-3"), b.all().keys)
    }

    @Test fun skippedElementKeepsItsBinding() {
        val host = FakeHost(7)
        val b = WidgetBindings(tmp.newFolder(), host).apply { put("w-1", bound(7)) }
        b.settle(cfg(doc(aw("w-1", cls = ".Other").replace("\"requires\":5", "\"requires\":9"))))
        assertTrue(host.deleted.isEmpty())
        assertEquals(bound(7), b.binding("w-1"))
    }

    @Test fun holdRestoreRelease() {
        val host = FakeHost(7, 8)
        val b = WidgetBindings(tmp.newFolder(), host).apply { put("w-1", bound(7)); put("w-2", bound(8)) }
        // #303: held before the Remove write; the settle inside that write sets them aside (IDs kept, not orphans).
        assertTrue(b.hold("w-1")); assertTrue(b.hold("w-2"))
        assertFalse(b.hold("w-none"))
        b.settle(cfg(doc()))
        assertTrue(host.deleted.isEmpty())
        assertTrue(b.isAside("w-1")); assertTrue(b.all().isEmpty())
        // Undo for w-1: restored right after the Undo edit (before its write), then settled with the element back.
        b.restore("w-1")
        assertEquals(bound(7), b.binding("w-1"))
        b.settle(cfg(doc(aw("w-1"))))
        assertEquals(bound(7), b.binding("w-1"))
        assertFalse(b.isHeld("w-1"))
        // The window for w-2 ends: its ID is freed (deleteAppWidgetId).
        b.release("w-2")
        assertEquals(listOf(8), host.deleted)
        assertEquals(setOf("w-1"), b.all().keys)
        // Released after a successful Undo: nothing freed.
        b.release("w-1")
        assertEquals(listOf(8), host.deleted)
    }

    @Test fun heldBindingNeverAppliesToAnElementTheAgentReAdds() {
        val host = FakeHost(7)
        val b = WidgetBindings(tmp.newFolder(), host).apply { put("w-1", bound(7)) }
        b.hold("w-1")
        b.settle(cfg(doc()))                 // the Remove write
        b.settle(cfg(doc(aw("w-1"))))        // the agent adds a widget with the same id during the window
        assertNull(b.binding("w-1"))         // → Tap to set up, not the held ID
        assertTrue(host.deleted.isEmpty())   // still held (Undo would write nothing: undo_element_back)
        b.release("w-1")
        assertEquals(listOf(7), host.deleted)
        assertNull(b.binding("w-1"))
    }

    @Test fun anUnreleasedHoldIsGoneAfterTheAppCloses() {
        val dir = tmp.newFolder(); val host = FakeHost(7)
        WidgetBindings(dir, host).apply { put("w-1", bound(7)); hold("w-1"); settle(cfg(doc())) }
        assertTrue(host.deleted.isEmpty())
        // A new process: the file no longer lists it, so its ID is an orphan and the next settle frees it.
        WidgetBindings(dir, host).settle(cfg(doc()))
        assertEquals(listOf(7), host.deleted)
    }

    @Test fun bindInProgressIsNotAnOrphanAndPutReplacesOldId() {
        val host = FakeHost(7, 9)
        val b = WidgetBindings(tmp.newFolder(), host).apply { put("w-1", bound(7)) }
        b.beginBind(9)
        b.settle(cfg(doc(aw("w-1"))))
        assertTrue(host.deleted.isEmpty())
        b.put("w-1", bound(9))
        assertEquals(listOf(7), host.deleted)
        b.beginBind(10); b.abandonBind(10)
        assertEquals(listOf(7, 10), host.deleted)
    }

    @Test fun engineSettlesOnGoodLoadsOnlyNeverOnB2() = runBlocking {
        val dir = tmp.newFolder()
        val src = LocalFileSource(dir); src.file.writeText(doc(aw("w-1")))
        val host = FakeHost(7)
        val b = WidgetBindings(tmp.newFolder(), host).apply { put("w-1", bound(7)) }
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), 0, bindings = b)
        e.poll(force = true)
        assertEquals(bound(7), b.binding("w-1"))
        // A B2 file without the widget (overlap) deletes nothing.
        src.file.writeText(doc(aw("w-2"), aw("w-3")))
        e.poll(force = true)
        assertNotNull(e.state.value.issue)
        assertTrue(host.deleted.isEmpty())
        // The next good load without it frees the ID.
        src.file.writeText(doc())
        e.poll(force = true)
        assertEquals(listOf(7), host.deleted)
        assertNull(b.binding("w-1"))
    }

    // #308 review (5): Android already deleted the ID (provider uninstalled) → binding pruned, back to needsSetup.
    @Test fun bindingWhoseIdAndroidDeletedIsPruned() {
        val dir = tmp.newFolder()
        val host = FakeHost(7, 8)
        val b = WidgetBindings(dir, host)
        b.put("w-1", bound(7)); b.put("w-2", bound(8))
        host.alive -= 7                                  // the system removed it, not us
        b.prune()
        assertNull(b.binding("w-1"))
        assertEquals(bound(8), b.binding("w-2"))
        assertNull(WidgetBindings(dir, host).binding("w-1"))
        assertTrue(host.deleted.isEmpty())
        // The only binding's ID gone: the host list is empty, so (#310 review (a)) it's kept here; the grid's
        // view check (WidgetRuntime.viewMissing) frees it instead.
        host.alive -= 8
        b.prune()
        assertEquals(bound(8), b.binding("w-2"))
    }

    @Test fun pruneKeepsEverythingWhenTheHostCantList() {
        val host = object : WidgetBindings.Host { override fun ids(): IntArray = error("binder died"); override fun delete(appWidgetId: Int) {} }
        val b = WidgetBindings(tmp.newFolder(), host)
        b.put("w-1", bound(7))
        b.prune()
        assertEquals(bound(7), b.binding("w-1"))
    }

    // #308 review (2): the save replaces the file through a rename (a new inode), never writes it in place.
    @Test fun saveIsAtomicRenameAndStaleTempIsCleaned() {
        val dir = tmp.newFolder()
        val host = FakeHost(7, 8)
        val file = File(dir, WidgetBindings.FILE)
        val b = WidgetBindings(dir, host)
        b.put("w-1", bound(7))
        val ino = java.nio.file.Files.getAttribute(file.toPath(), "unix:ino")
        b.put("w-2", bound(8))
        assertNotEquals("a direct write keeps the inode", ino, java.nio.file.Files.getAttribute(file.toPath(), "unix:ino"))
        assertFalse(File(dir, "${WidgetBindings.FILE}.tmp").exists())
        // A save that died before its rename leaves a temp; the next start drops it and keeps the real file.
        File(dir, "${WidgetBindings.FILE}.tmp").writeText("{half")
        val again = WidgetBindings(dir, host)
        assertFalse(File(dir, "${WidgetBindings.FILE}.tmp").exists())
        assertEquals(bound(8), again.binding("w-2"))
    }

    // #310 review (a): an empty ID list while bindings exist is suspicious; neither prune nor settle drops them.
    @Test fun emptyHostListNeverPrunesEverything() {
        val host = FakeHost(7)
        val b = WidgetBindings(tmp.newFolder(), host)
        b.put("w-1", bound(7))
        host.alive.clear()
        b.prune()
        assertEquals(bound(7), b.binding("w-1"))
        b.settle(cfg(doc(aw("w-1"))))
        assertEquals(bound(7), b.binding("w-1"))
    }

    // #310 review (b): settle drops a binding whose ID Android deleted, keeps the live ones, frees nothing extra.
    @Test fun settleDropsDeadIds() {
        val host = FakeHost(7, 8)
        val b = WidgetBindings(tmp.newFolder(), host)
        b.put("w-1", bound(7)); b.put("w-2", bound(8))
        host.alive -= 7
        b.settle(cfg(doc(aw("w-1"), aw("w-2", x = 1))))
        assertNull(b.binding("w-1"))
        assertEquals(bound(8), b.binding("w-2"))
        assertTrue(host.deleted.isEmpty())
    }
}
