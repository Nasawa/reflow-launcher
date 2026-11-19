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
 * #334 (Claw, dev1 A51): an agent's non-atomic `cp` (truncate, then write) racing the phone's save. Before the fix the
 * read-back landed between the agent's truncate and its write, saw an empty file, called it "write verification
 * failed" and restored the pre-save bytes over the agent's finished write; the engine then sat on the failed edit
 * (polls and RELOAD skipped the file, RELOAD said busy, status.json kept the old `ok`, Retry could be a silent no-op
 * under B6). These pin the fixed behaviour. Real [SafWrites] on a fake tree, the agent's steps landed at fixed points
 * of the save; no clock, no timing.
 */
class Issue334WedgeTest {
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
        /** While true the provider drops our writes to launcher.json (the read-back is the old file): a save that fails. */
        var dropWrites = false
        /** Once, right after a read of launcher.json returns: the agent's write completing after our read-back. */
        var afterTargetRead: (() -> Unit)? = null
        override fun read(docId: String): ByteArray? {
            val out = t.read(docId)
            if (t.docs[docId]?.name == "launcher.json") afterTargetRead?.let { afterTargetRead = null; it() }
            return out
        }
        override fun write(docId: String, bytes: ByteArray) {
            val name = t.docs[docId]?.name
            if (dropWrites && name == "launcher.json") { targetWrites++; return }
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

    /** The race: our bytes land; the agent's `cp` truncates; our read-back sees ""; the agent's bytes land. */
    private fun TreeSource.agentCpRacesTheReadBack(json: String) {
        r.afterTargetWrite = { agentWrites("") ; r.afterTargetRead = { agentWrites(json) } }
    }
    private fun TreeSource.warned() = status().contains("isn't saved yet")

    @Test fun theAgentsWriteIsMergedNotRestoredOver() = runBlocking {
        val src = TreeSource(SafDirsTest.FakeTree()); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(Edit.Remove("w-clock")))
        src.agentCpRacesTheReadBack(agent(37))
        assertTrue(e.flush())
        // Before: the file was back at rev 36 (the agent's rev 37 restored over) and B5 "write verification failed".
        assertEquals("the agent's item kept, our Remove merged onto it", listOf("a-keep", "a-agent-gal"), ids(src.file))
        assertEquals(38L, rev(src.file))
        assertNull(e.state.value.issue)
        assertEquals(0, e.state.value.pendingEdits)
        assertTrue(src.status().contains(ConfigCodec.sha256(src.file!!.toByteArray())))
        assertFalse(src.warned())
        assertEquals(ReloadOutcome.UNCHANGED, e.reloadForAgent())
    }

    @Test fun ourOwnFailedWriteIsStillRestoredWhenNobodyElseWrote() = runBlocking {
        // #321 restore-path cases: the read-back is the original, empty, or a torn prefix of ours, and stays so.
        for (case in listOf("original", "empty", "torn")) {
            val src = TreeSource(SafDirsTest.FakeTree()); val e = engine(src)
            assertTrue(e.poll(force = true))
            assertTrue(e.edit(Edit.Remove("w-clock")))
            when (case) {
                "original" -> src.r.dropWrites = true
                "empty" -> src.r.afterTargetWrite = { src.agentWrites("") }
                else -> src.r.afterTargetWrite = { src.agentWrites(String(ConfigCodec.encodeBytes(e.state.value.config.copy(revision = 37))).take(30)) }
            }
            assertFalse(case, e.flush())
            assertEquals("$case: the old file is put back", base, src.file)
            assertTrue(case, (e.state.value.issue as Issue.SaveFailed).message.contains("write verification failed"))
            assertEquals(case, 1, e.state.value.pendingEdits)
        }
    }

    @Test fun afterAFailedSaveTheEngineDoesNotWedge() = runBlocking {
        val src = TreeSource(SafDirsTest.FakeTree()); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(Edit.Remove("w-clock")))
        src.r.dropWrites = true                       // the provider fails our writes for a while
        assertFalse(e.flush())
        assertTrue(e.state.value.issue is Issue.SaveFailed)
        // status.json says so (#321's stale ok): still the file's hash, plus the pending-change warning.
        assertTrue(src.status(), src.warned())
        assertTrue(src.status().contains(ConfigCodec.sha256(base.toByteArray())))

        // An observer firing (a forced poll) with the file unchanged doesn't hammer the folder with retries.
        val writes = src.r.targetWrites
        assertFalse(e.poll(force = true)); assertEquals(writes, src.r.targetWrites)

        // The agent writes: a poll sees the change and retries (it still fails here); RELOAD retries and says so.
        src.agentWrites(agent(38, "a-agent-two"))
        assertFalse(e.poll())
        assertTrue("retried", src.r.targetWrites > writes)
        assertEquals("a real answer, not busy", ReloadOutcome.SAVE_FAILED, e.reloadForAgent())
        assertTrue(src.warned())

        // The provider recovers; the agent writes again; the next poll merges our Remove onto the agent's file.
        src.r.dropWrites = false
        src.agentWrites(agent(39, "a-agent-three"))  // (a new size: the fake tree's stamp is the size)
        assertTrue(e.poll())
        assertEquals(listOf("a-keep", "a-agent-three"), ids(src.file))
        assertNull(e.state.value.issue)
        assertEquals(0, e.state.value.pendingEdits)
        assertFalse(src.warned())
        assertTrue(src.status().contains(ConfigCodec.sha256(src.file!!.toByteArray())))
        assertEquals(ReloadOutcome.UNCHANGED, e.reloadForAgent())
    }

    @Test fun reloadAloneRecoversOnceTheProviderWorks() = runBlocking {
        val src = TreeSource(SafDirsTest.FakeTree()); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(Edit.Remove("w-clock")))
        src.r.dropWrites = true
        assertFalse(e.flush())
        src.r.dropWrites = false
        assertEquals("RELOAD retries the save even with the file unchanged", ReloadOutcome.RELOADED, e.reloadForAgent())
        assertEquals(listOf("a-keep"), ids(src.file))
        assertNull(e.state.value.issue)
        assertFalse(src.warned())
    }

    @Test fun retryAfterAConflictShowsB6NeverASilentNoOp() = runBlocking {
        val src = TreeSource(SafDirsTest.FakeTree()); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(Edit.Remove("w-clock")))
        src.r.dropWrites = true
        assertFalse(e.flush())                       // B5
        src.r.dropWrites = false
        src.agentWrites(agent(38).take(40))         // the agent's next cp, caught mid-write
        assertFalse(e.retrySave())
        // B6 takes over: conflict, and no B5 left beside it (its Retry would do nothing under B6).
        assertTrue(e.state.value.conflict)
        assertNull(e.state.value.issue)
        assertFalse(src.warned())
        assertEquals(ReloadOutcome.BUSY, e.reloadForAgent()) // B6 waits on the user
        // Retry again while B6 is up: still B6, never a lone B5 that does nothing.
        assertFalse(e.retrySave())
        assertTrue(e.state.value.conflict); assertNull(e.state.value.issue)
        // The user picks Keep mine once the agent's file is whole: recovered.
        src.agentWrites(agent(39))
        assertTrue(e.resolveKeepMine())
        assertFalse(e.state.value.conflict)
        assertEquals(listOf("a-keep", "a-agent-gal"), ids(src.file))
        assertNull(e.state.value.issue)
    }

    @Test fun aB5BesideB6IsClearedByRetry() = runBlocking {
        val src = TreeSource(SafDirsTest.FakeTree()); val e = engine(src)
        assertTrue(e.poll(force = true))
        e.askBeforeMerge = true
        assertTrue(e.edit(Edit.Remove("w-clock")))
        src.agentWrites(agent(37))
        assertFalse(e.flush()); assertTrue(e.state.value.conflict) // B6 (Ask before merging)
        src.r.dropWrites = true
        assertFalse(e.resolveKeepMine())             // Keep mine's write fails: B5 beside B6
        assertTrue(e.state.value.issue is Issue.SaveFailed); assertTrue(e.state.value.conflict)
        assertFalse(e.retrySave())
        assertTrue("B6 stays, with its actions", e.state.value.conflict)
        assertNull("B5's Retry did something: it handed over to B6", e.state.value.issue)
    }

    @Test fun aFailedWritesBytesAreNoEchoLater() = runBlocking {
        // The failed save's bytes never became the file, so when an agent writes exactly those bytes they're its file:
        // loaded and receipted, not swallowed as our own echo.
        val src = TreeSource(SafDirsTest.FakeTree()); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(Edit.Remove("w-clock")))
        val ours = String(ConfigCodec.encodeBytes(e.state.value.config.copy(revision = 37)))
        src.r.dropWrites = true
        assertFalse(e.flush())
        src.r.dropWrites = false
        src.agentWrites(ours)
        e.resolveUseFile()                           // drop the phone's edit; take the file
        assertEquals(ConfigCodec.sha256(ours.toByteArray()), e.state.value.baseHash)
        assertTrue(src.status().contains(ConfigCodec.sha256(ours.toByteArray())))
    }
}
