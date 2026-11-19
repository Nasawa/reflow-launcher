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
 * #334 (Claw, dev1 A51): an agent's non-atomic `cp` (truncate, then write) racing the phone's save. The read-back
 * lands between the agent's truncate and its write, sees an empty file, calls it "write verification failed" and
 * restores the pre-save bytes over the agent's finished write. The engine then sits on the failed edit: polls and
 * RELOAD skip the file while it's pending, and status.json keeps the old `ok`. Real [SafWrites] on a fake tree, the
 * agent's steps landed at fixed points of the save; no clock, no timing.
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
        /** Once, right after a read of launcher.json returns: the agent's write completing after our read-back. */
        var afterTargetRead: (() -> Unit)? = null
        override fun read(docId: String): ByteArray? {
            val out = t.read(docId)
            if (t.docs[docId]?.name == "launcher.json") afterTargetRead?.let { afterTargetRead = null; it() }
            return out
        }
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

    /** The race: our bytes land; the agent's `cp` truncates; our read-back sees ""; the agent's bytes land; we "restore". */
    private fun TreeSource.agentCpRacesTheReadBack(json: String) {
        r.afterTargetWrite = { agentWrites("") ; r.afterTargetRead = { agentWrites(json) } }
    }

    @Test fun theAgentsWriteIsLostAndTheEngineWedges() = runBlocking {
        val src = TreeSource(SafDirsTest.FakeTree()); val e = engine(src)
        assertTrue(e.poll(force = true))
        val statusBefore = src.status()
        assertTrue(e.edit(Edit.Remove("w-clock")))
        src.agentCpRacesTheReadBack(agent(37))
        assertFalse(e.flush())

        // 1. Lost write: launcher.json is back to the pre-round bytes (rev 36); the agent's rev 37 is gone.
        assertEquals("the agent's write was restored over", base, src.file)
        val issue = e.state.value.issue as Issue.SaveFailed
        assertTrue(issue.message, issue.message.contains("write verification failed"))
        assertEquals(1, e.state.value.pendingEdits)

        // 2. Wedge: the agent writes again (next round); nothing sees it while the edit is pending.
        src.agentWrites(agent(38, "a-agent-two"))
        assertFalse("poll skips the file while an edit is pending", e.poll(force = true))
        assertFalse(ids(String(ConfigCodec.encodeBytes(e.state.value.config))).contains("a-agent-two"))
        assertEquals("RELOAD says busy for as long as the failed edit is pending", ReloadOutcome.BUSY, e.reloadForAgent())
        assertEquals(ReloadOutcome.BUSY, e.reloadForAgent())
        // 3. status.json: still the old ok receipt for rev 36 (re-emitted by RELOAD), no word of the failed save.
        assertTrue(src.status(), src.status().contains(ConfigCodec.sha256(base.toByteArray())))
        assertTrue(src.status(), src.status().contains("\"ok\""))
        assertFalse(src.status(), src.status().contains("verification"))
        assertEquals(statusBefore.replace(Regex("\"time\"[^,}]*"), ""), src.status().replace(Regex("\"time\"[^,}]*"), ""))
        // Nothing retries on its own: only Retry (or a new edit) runs another save.
    }

    @Test fun retryAfterTheLostWriteWorksWhenTheWriteCanSucceed() = runBlocking {
        // Control: with the provider behaving, Retry does save (merging onto the agent's next write).
        val src = TreeSource(SafDirsTest.FakeTree()); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(Edit.Remove("w-clock")))
        src.agentCpRacesTheReadBack(agent(37))
        assertFalse(e.flush())
        src.agentWrites(agent(38, "a-agent-two"))
        assertTrue(e.retrySave())
        assertEquals(listOf("a-keep", "a-agent-two"), ids(src.file))
        assertNull(e.state.value.issue)
        assertEquals(ReloadOutcome.UNCHANGED, e.reloadForAgent())
    }

    @Test fun retryIsASilentNoOpOnceConflictIsSetUnderTheSaveFailedBanner() = runBlocking {
        // A flush that meets a torn (invalid) file sets conflict, and the B5 issue from the earlier failure stays.
        val src = TreeSource(SafDirsTest.FakeTree()); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(Edit.Remove("w-clock")))
        src.agentCpRacesTheReadBack(agent(37))
        assertFalse(e.flush())
        src.agentWrites(agent(38).take(40))         // the next agent cp caught mid-write by a save
        assertFalse(e.retrySave())
        assertTrue(e.state.value.conflict)
        assertTrue(e.state.value.issue is Issue.SaveFailed)
        src.agentWrites(agent(39))                  // the agent's file is whole again
        assertFalse("Retry: flushOnce returns at once while conflict is set", e.retrySave())
        assertTrue(e.state.value.issue is Issue.SaveFailed)
        assertEquals(ReloadOutcome.BUSY, e.reloadForAgent())
    }
}
