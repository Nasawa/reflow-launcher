package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.Edit
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #314 review: [ConfigEngine.editKept] says whether the write kept the edit, and runs `onKept` only then, before the write. */
class EditKeptTest {
    @get:Rule val tmp = TemporaryFolder()
    private val el = """{"id":"w-a","type":"appWidget","requires":5,"page":"home","x":0,"y":0,"w":2,"h":2,"content":{"provider":{"package":"com.example","class":".W"}}}"""
    private val agentEl = el.replace("\"x\":0,\"y\":0", "\"x\":2,\"y\":2")
    private fun doc(rev: Int, vararg els: String) =
        """{"schemaVersion":1,"revision":$rev,"grid":{"columns":4,"rows":4},"pages":[{"id":"home","items":[]}],"elements":[${els.joinToString(",")}]}"""
    private fun pos(j: JsonObject) = j["x"]!!.jsonPrimitive.int to j["y"]!!.jsonPrimitive.int
    private fun onDisk(src: LocalFileSource) = (ConfigCodec.decode(src.file.readBytes()) as ParseResult.Ok).config

    private fun removed(): Pair<ConfigEngine, LocalFileSource> = runBlocking {
        val src = LocalFileSource(tmp.newFolder()); src.file.writeText(doc(1, el)); src.file.setLastModified(1000)
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), stabilityMs = 0); e.poll()
        assertTrue(e.edit(Edit.RemoveElement("w-a"))); assertTrue(e.flush())
        assertTrue(onDisk(src).elements.orEmpty().isEmpty())
        e to src
    }
    private val restore = Edit.RestoreElement(Json.parseToJsonElement(el).jsonObject, 0)

    @Test fun keptRestoreRunsOnKeptBeforeTheWrite() = runBlocking {
        val (e, src) = removed()
        var onDiskAtKept = -1
        assertTrue(e.editKept(restore) { onDiskAtKept = onDisk(src).elements.orEmpty().size })
        assertEquals("onKept ran before the write", 0, onDiskAtKept)
        assertEquals(0 to 0, onDisk(src).elements!!.single().let(::pos))
    }

    @Test fun restoreSkippedByTheMergeReportsFalseAndNeverCallsOnKept() = runBlocking {
        val (e, src) = removed()
        // The agent re-added w-a elsewhere; the phone hasn't polled it, so the Restore applies in memory…
        src.file.writeText(doc(7, agentEl)); src.file.setLastModified(5000)
        var kept = 0
        assertFalse(e.editKept(restore) { kept++ })
        assertEquals(0, kept)
        // …and the merge kept the agent's element.
        assertEquals(2 to 2, onDisk(src).elements!!.single().let(::pos))
        assertEquals(2 to 2, e.state.value.config.elements!!.single().let(::pos))
    }

    @Test fun anEditThatDoesNotApplyReturnsFalseAtOnce() = runBlocking {
        val (e, _) = removed()
        var kept = 0
        assertFalse(e.editKept(Edit.RemoveElement("w-none")) { kept++ })
        assertEquals(0, kept)
    }

    // ---- #314 GO follow-ups (review 5958633363) ----

    /** launcher.json in app storage whose next [failNext] writes throw (B5), like a provider that's offline. */
    private class Flaky(val inner: LocalFileSource) : ConfigSource by inner {
        var failNext = 0
        /** #318: runs once at the start of the next write, before the source's re-check: another writer lands then. */
        var beforeWrite: (() -> Unit)? = null
        override fun write(bytes: ByteArray, expectHash: String?) {
            if (failNext > 0) { failNext--; throw java.io.IOException("provider offline") }
            beforeWrite?.let { beforeWrite = null; it() }
            inner.write(bytes, expectHash)
        }
    }

    private class FakeHost(vararg ids: Int) : dev.nasawa.configlauncher.hosted.WidgetBindings.Host {
        val alive = ids.toMutableSet(); val deleted = mutableListOf<Int>()
        override fun ids() = alive.toIntArray()
        override fun delete(appWidgetId: Int) { alive -= appWidgetId; deleted += appWidgetId }
    }

    @Test fun aFailedKeptWriteCallsOnUnkeptAndTheRetryDecidesAgain() = runBlocking {
        val (e0, src0) = removed()
        val src = Flaky(src0); e0.source = src
        src.failNext = 1
        val log = mutableListOf<String>()
        val d = async { e0.editKept(restore, onUnkept = { log += "unkept" }) { log += "kept" } }
        yield()
        assertEquals("kept before the write, unkept when it failed", listOf("kept", "unkept"), log)
        assertFalse("still waiting for a write that keeps it", d.isCompleted)
        assertTrue(e0.state.value.issue is Issue.SaveFailed)
        assertTrue(e0.retrySave())
        assertTrue(d.await())
        assertEquals(listOf("kept", "unkept", "kept"), log)
    }

    @Test fun aFailedUndoWriteSetsTheBindingAsideSoARetryAfterAnAgentReAddNeverHandsItOver() = runBlocking {
        // Grace (#314 GO): editKept gave the IDs back before the write; the write failed, the agent re-added w-a, and the
        // Retry's merge skipped the Restore with the old ID 55 already back, so the agent's element got it.
        val dir = tmp.newFolder()
        val inner = LocalFileSource(tmp.newFolder()); inner.file.writeText(doc(1, el)); inner.file.setLastModified(1000)
        val src = Flaky(inner)
        val host = FakeHost(55)
        val bindings = dev.nasawa.configlauncher.hosted.WidgetBindings(dir, host)
        bindings.put("w-a", dev.nasawa.configlauncher.hosted.WidgetBindings.Binding(55,
            dev.nasawa.configlauncher.hosted.WidgetBindings.Provider("com.example", ".W"), "personal", dev.nasawa.configlauncher.hosted.WidgetBindings.State.BOUND))
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), stabilityMs = 0, bindings = bindings); e.poll()
        val hold = object : dev.nasawa.configlauncher.ui.ElementUndo.Hold {
            override fun hold(id: String) = bindings.hold(id); override fun restore(id: String) = bindings.restore(id)
            override fun release(id: String) = bindings.release(id); override fun setAside(id: String) = bindings.setAside(id)
        }
        val undo = dev.nasawa.configlauncher.ui.ElementUndo({ e.state.value.config }, { ed -> e.edit(ed).also { e.flush() } }, listOf(hold)) { ed, onKept, onUnkept ->
            e.editKept(ed, onUnkept, onKept)
        }
        val removed = undo.remove("w-a")!!
        assertTrue("set aside by the Remove write", bindings.isAside("w-a"))
        src.failNext = 1
        val out = async { undo.undo(removed) }
        yield()
        assertTrue(e.state.value.issue is Issue.SaveFailed)
        assertTrue("aside again after the failed write", bindings.isAside("w-a"))
        assertNull(bindings.binding("w-a"))
        // The agent re-adds w-a (same provider) before the Retry.
        inner.file.writeText(doc(7, agentEl)); inner.file.setLastModified(5000)
        assertTrue(e.retrySave())
        assertEquals(dev.nasawa.configlauncher.ui.ElementUndo.Undone.Back, out.await())
        assertEquals(2 to 2, onDisk(inner).elements!!.single().let(::pos))
        assertNull("the agent's element never gets the old ID", bindings.binding("w-a"))
        assertTrue(bindings.isAside("w-a"))
        undo.release(removed)
        assertEquals(listOf(55), host.deleted)
    }

    /** Engine + [WidgetBindings] + [ElementUndo] as HomeScreen wires them, on [src]. */
    private class Wired(val e: ConfigEngine, val bindings: dev.nasawa.configlauncher.hosted.WidgetBindings, val host: FakeHost, val undo: dev.nasawa.configlauncher.ui.ElementUndo)
    private fun wired(src: ConfigSource): Wired {
        val host = FakeHost(55)
        val bindings = dev.nasawa.configlauncher.hosted.WidgetBindings(tmp.newFolder(), host)
        bindings.put("w-a", dev.nasawa.configlauncher.hosted.WidgetBindings.Binding(55,
            dev.nasawa.configlauncher.hosted.WidgetBindings.Provider("com.example", ".W"), "personal", dev.nasawa.configlauncher.hosted.WidgetBindings.State.BOUND))
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), stabilityMs = 0, bindings = bindings)
        runBlocking { e.poll() }
        val hold = object : dev.nasawa.configlauncher.ui.ElementUndo.Hold {
            override fun hold(id: String) = bindings.hold(id); override fun restore(id: String) = bindings.restore(id)
            override fun release(id: String) = bindings.release(id); override fun setAside(id: String) = bindings.setAside(id)
        }
        val undo = dev.nasawa.configlauncher.ui.ElementUndo({ e.state.value.config }, { ed -> runBlocking { e.edit(ed).also { e.flush() } } }, listOf(hold)) { ed, onKept, onUnkept ->
            e.editKept(ed, onUnkept, onKept)
        }
        return Wired(e, bindings, host, undo)
    }

    @Test fun anAgentWriteRacingTheUndoWriteSetsTheBindingAsideBeforeTheMergeSkipsIt() = runBlocking {
        // #318 × #319: the Undo's write runs onKept (ID 55 back), then the source's re-check sees the agent's re-add
        // (ConcurrentChangeException). The engine merges onto the agent's file, which skips the Restore: 55 must be
        // aside again by then, never settled onto the agent's w-a.
        val inner = LocalFileSource(tmp.newFolder()); inner.file.writeText(doc(1, el)); inner.file.setLastModified(1000)
        val src = Flaky(inner)
        val w = wired(src)
        val removed = w.undo.remove("w-a")!!
        assertTrue(w.bindings.isAside("w-a"))
        src.beforeWrite = { inner.file.writeText(doc(7, agentEl)); inner.file.setLastModified(5000) }
        assertEquals(dev.nasawa.configlauncher.ui.ElementUndo.Undone.Back, w.undo.undo(removed))
        assertEquals("the agent's file, merged", 2 to 2, onDisk(inner).elements!!.single().let(::pos))
        assertNull("the agent's element never gets the old ID", w.bindings.binding("w-a"))
        assertTrue(w.bindings.isAside("w-a"))
        assertNull(w.e.state.value.issue)
        w.undo.release(removed)
        assertEquals(listOf(55), w.host.deleted)
    }

    @Test fun useFileDropsAWaitingUndo() = runBlocking {
        val (e, src) = removed()
        e.askBeforeMerge = true
        src.file.writeText(doc(7, agentEl)); src.file.setLastModified(5000)
        var kept = 0
        val d = async { e.editKept(restore) { kept++ } }
        yield()
        assertTrue("B6: the Undo waits for the decision", e.state.value.conflict)
        assertFalse(d.isCompleted)
        e.resolveUseFile()
        yield()
        assertTrue("Use file ends the wait", d.isCompleted)
        assertFalse(d.await())
        assertEquals(0, kept)
        assertEquals(2 to 2, onDisk(src).elements!!.single().let(::pos))
    }

    @Test fun useFileAfterAFailedKeptWriteDropsItWithTheHoldsAside() = runBlocking {
        val (e0, src0) = removed()
        val src = Flaky(src0); e0.source = src
        src.failNext = 1
        val log = mutableListOf<String>()
        val d = async { e0.editKept(restore, onUnkept = { log += "unkept" }) { log += "kept" } }
        yield()
        e0.resolveUseFile()
        yield()
        assertTrue(d.isCompleted); assertFalse(d.await())
        assertEquals("never left kept", listOf("kept", "unkept"), log)
    }
}
