package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.Edit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #317 (Grace): the phone's debounced save checked launcher.json, then wrote it without looking again, so an agent's
 * write landing in between (or during the target write) was replaced and status.json still said `ok`. These tests run
 * the real [SafWrites] path on a fake tree and land the agent's write at a fixed point of the save, no timing involved.
 */
class LostWriteRaceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val base = """{"schemaVersion":1,"revision":36,"pages":[{"id":"home","items":[""" +
        """{"id":"w-clock","type":"app","package":"a.clock","x":0,"y":0},{"id":"a-keep","type":"app","package":"a.keep","x":1,"y":0}]}]}"""
    /** Grace's run 1/2: the agent adds `a-agent-gal` at a higher revision. */
    private fun agent(rev: Int, extra: String = "a-agent-gal") = """{"schemaVersion":1,"revision":$rev,"pages":[{"id":"home","items":[""" +
        """{"id":"w-clock","type":"app","package":"a.clock","x":0,"y":0},{"id":"a-keep","type":"app","package":"a.keep","x":1,"y":0},""" +
        """{"id":"$extra","type":"app","package":"a.gal","x":2,"y":0}]}]}"""

    /** Lets a test land "another writer's" bytes at one point of the save. Agent writes go to [t] directly. */
    private class Racing(val t: SafDirsTest.FakeTree) : DocTree by t {
        /** Once, right after a save's temp copy is written: after the engine's check, before the target is touched. */
        var onTempWrite: (() -> Unit)? = null
        /** Once, right after our bytes reach launcher.json and before the read-back: the agent's write lands mid-save. */
        var afterTargetWrite: (() -> Unit)? = null
        var targetWrites = 0
        override fun write(docId: String, bytes: ByteArray) {
            val name = t.docs[docId]?.name
            t.write(docId, bytes)
            if (name != null && name.endsWith(".tmp")) onTempWrite?.let { onTempWrite = null; it() }
            if (name == "launcher.json") { targetWrites++; afterTargetWrite?.let { afterTargetWrite = null; it() } }
        }
    }

    /** launcher.json in a fake SAF tree, saved through the real [SafWrites] (like SafSource). */
    private inner class TreeSource(val t: SafDirsTest.FakeTree, val r: Racing = Racing(t)) : ConfigSource {
        private val key = "race-${System.identityHashCode(this)}"
        private val dirs = SafDirs(r, key)
        private val writes = SafWrites(r, dirs, key)
        init { t.write(t.add("root", "launcher.json", "application/json"), base.toByteArray()) }
        val file: String? get() = t.list("root")["launcher.json"]?.let { t.read(it.docId) }?.let { String(it) }
        val sidecars = mutableMapOf<String, String>()
        /** What another process does: replace launcher.json's bytes outright. */
        fun agentWrites(json: String) { t.write(t.list("root")["launcher.json"]!!.docId, json.toByteArray()) }
        override val displayName = "Folder: test"
        override fun stamp() = t.list("root")["launcher.json"]?.let { Stamp(it.docId, 1, it.size) }
        override fun read(maxBytes: Int) = t.list("root")["launcher.json"]?.let { t.read(it.docId) }
        override fun write(bytes: ByteArray, expectHash: String?) = writes.writeVerified("root", "launcher.json", "application/json", bytes, "layout", expectHash)
        override fun writeSidecar(subdir: String, name: String, bytes: ByteArray) { sidecars["$subdir/$name"] = String(bytes) }
    }

    private fun engine(src: ConfigSource) = ConfigEngine(src, LastGoodStore(tmp.newFolder()), stabilityMs = 0, clock = { 1_000L })
    private fun ids(json: String?) = (ConfigCodec.decode(json!!.toByteArray()) as ParseResult.Ok).config.pages.flatMap { p -> p.items.map { it.id } }
    private fun rev(json: String?) = (ConfigCodec.decode(json!!.toByteArray()) as ParseResult.Ok).config.revision
    private fun TreeSource.status() = sidecars["/status.json"]!!

    @Test fun agentWriteBetweenTheCheckAndTheWriteIsMergedNotOverwritten() = runBlocking {
        val src = TreeSource(SafDirsTest.FakeTree()); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(Edit.Remove("w-clock"))) // the phone's Remove from home, waiting for its debounced save
        // The flush reads base (hash check passes), stages its temp... and the agent's rev 39 lands now.
        src.r.onTempWrite = { src.agentWrites(agent(39)) }
        assertTrue(e.flush())
        // Old code: the phone's base + 1 (rev 37) without a-agent-gal, status ok. Now: the Remove replays onto rev 39.
        assertEquals(listOf("a-keep", "a-agent-gal"), ids(src.file))
        assertEquals(40L, rev(src.file))
        assertEquals(listOf("a-keep", "a-agent-gal"), ids(String(ConfigCodec.encodeBytes(e.state.value.config))))
        assertTrue(src.status(), src.status().contains("\"result\": \"ok\"") || src.status().contains("\"result\":\"ok\""))
        assertTrue("status.json describes the merged file", src.status().contains(ConfigCodec.sha256(src.file!!.toByteArray())))
        assertNotNull("the merge snackbar, as for any merge", e.state.value.merge)
        assertEquals(0, e.state.value.pendingEdits)
        assertNull(e.state.value.issue)
    }

    @Test fun agentWriteLandingDuringTheTargetWriteIsKeptAndMerged() = runBlocking {
        val src = TreeSource(SafDirsTest.FakeTree()); val e = engine(src)
        e.poll(force = true)
        e.edit(Edit.Remove("w-clock"))
        // Our bytes reach launcher.json, then the agent's rev 56 replaces them before our read-back.
        src.r.afterTargetWrite = { src.agentWrites(agent(56)) }
        assertTrue(e.flush())
        // Old code: verification failed, the target was "restored" to base (the agent's rev 56 gone) and B5 shown.
        assertEquals(listOf("a-keep", "a-agent-gal"), ids(src.file))
        assertEquals(57L, rev(src.file))
        assertTrue(src.status().contains(ConfigCodec.sha256(src.file!!.toByteArray())))
        assertNull(e.state.value.issue)
        assertEquals(0, e.state.value.pendingEdits)
    }

    @Test fun agentWriteThatStillChangesAfterTheMergeIsMergedAgain() = runBlocking {
        val src = TreeSource(SafDirsTest.FakeTree()); val e = engine(src)
        e.poll(force = true)
        e.edit(Edit.Remove("w-clock"))
        src.r.onTempWrite = {
            src.agentWrites(agent(39))
            // ...and the agent writes again while the merge onto rev 39 is being saved.
            src.r.onTempWrite = { src.agentWrites(agent(41, "a-agent-two")) }
        }
        assertTrue(e.flush())
        assertEquals(listOf("a-keep", "a-agent-two"), ids(src.file))
        assertEquals(42L, rev(src.file))
        assertNull(e.state.value.issue)
    }

    @Test fun anAgentThatKeepsWritingGetsB5NotOk() = runBlocking {
        val src = TreeSource(SafDirsTest.FakeTree()); val e = engine(src)
        e.poll(force = true)
        val okBefore = src.status()
        e.edit(Edit.Remove("w-clock"))
        var n = 60
        lateinit var again: () -> Unit
        again = { src.agentWrites(agent(n++)); src.r.onTempWrite = again }
        src.r.onTempWrite = again
        assertFalse(e.flush())
        src.r.onTempWrite = null
        assertEquals("the agent's last write is the file", agent(n - 1), src.file)
        assertTrue(e.state.value.issue is Issue.SaveFailed)
        assertEquals("the phone's edit is still pending for Retry", 1, e.state.value.pendingEdits)
        assertEquals("no ok receipt for bytes that aren't the file", okBefore, src.status())
        assertEquals(0, src.r.targetWrites)
        // Retry once the agent stops: merged onto its last file.
        assertTrue(e.retrySave())
        assertEquals(listOf("a-keep", "a-agent-gal"), ids(src.file))
        assertNull(e.state.value.issue)
    }

    @Test fun askBeforeMergingShowsB6AfterTheRace() = runBlocking {
        val src = TreeSource(SafDirsTest.FakeTree()); val e = engine(src); e.askBeforeMerge = true
        e.poll(force = true)
        e.edit(Edit.Remove("w-clock"))
        src.r.onTempWrite = { src.agentWrites(agent(39)) }
        assertFalse(e.flush())
        assertTrue(e.state.value.conflict)
        assertEquals(agent(39), src.file)
        assertEquals(0, src.r.targetWrites)
    }

    @Test fun aTornMixMidWriteIsNotRestoredOverAndShowsB6() = runBlocking {
        val src = TreeSource(SafDirsTest.FakeTree()); val e = engine(src)
        e.poll(force = true)
        e.edit(Edit.Remove("w-clock"))
        // Two writers on one "wt" stream: the file ends up as neither (the agent may still be writing it).
        src.r.afterTargetWrite = { src.agentWrites(agent(56).take(40)) }
        assertFalse(e.flush())
        assertEquals("not overwritten with the old bytes, nor with ours", agent(56).take(40), src.file)
        assertTrue(e.state.value.conflict)
    }

    @Test fun keepMineReplaysOntoAWriteThatLandsDuringItsSave() = runBlocking {
        val src = TreeSource(SafDirsTest.FakeTree()); val e = engine(src); e.askBeforeMerge = true
        e.poll(force = true)
        e.edit(Edit.Remove("w-clock"))
        src.agentWrites(agent(39))
        assertFalse(e.flush()); assertTrue(e.state.value.conflict)
        src.r.onTempWrite = { src.agentWrites(agent(45, "a-agent-two")) }
        assertTrue(e.resolveKeepMine())
        assertEquals(listOf("a-keep", "a-agent-two"), ids(src.file))
        assertFalse(e.state.value.conflict)
    }

    @Test fun safWritesStopsBeforeTheTargetWhenItChangedAndLeavesNoTemp() {
        val t = SafDirsTest.FakeTree(); val r = Racing(t); val key = "race-unit"
        val w = SafWrites(r, SafDirs(r, key), key)
        w.writeVerified("root", "launcher.json", "application/json", "old".toByteArray(), "layout")
        val stale = ConfigCodec.sha256("old".toByteArray())
        r.onTempWrite = { t.write(t.list("root")["launcher.json"]!!.docId, "agent".toByteArray()) }
        val ex = assertThrows(ConcurrentChangeException::class.java) {
            w.writeVerified("root", "launcher.json", "application/json", "mine".toByteArray(), "layout", stale)
        }
        assertEquals("agent", String(ex.current!!))
        assertEquals("agent", String(t.read(t.list("root")["launcher.json"]!!.docId)!!))
        val cl = t.list("root")[".configlauncher"]!!.docId
        assertEquals("temp deleted", emptyList<String>(), t.namesIn(cl))
        // No expectation (AGENTS.md, legacy callers): unchanged behaviour.
        w.writeVerified("root", "launcher.json", "application/json", "mine".toByteArray(), "layout")
        assertEquals("mine", String(t.read(t.list("root")["launcher.json"]!!.docId)!!))
    }

    @Test fun localFileSourceChecksRightBeforeTheRename() {
        val dir = tmp.newFolder(); val s = LocalFileSource(dir)
        s.write("old".toByteArray())
        val stale = ConfigCodec.sha256("other".toByteArray())
        assertThrows(ConcurrentChangeException::class.java) { s.write("mine".toByteArray(), stale) }
        assertEquals("old", s.file.readText())
        assertFalse(java.io.File(dir, "launcher.json.tmp").exists())
        s.write("mine".toByteArray(), ConfigCodec.sha256("old".toByteArray()))
        assertEquals("mine", s.file.readText())
        assertThrows(ConcurrentChangeException::class.java) { LocalFileSource(dir).write("x".toByteArray(), ABSENT) }
    }
}
