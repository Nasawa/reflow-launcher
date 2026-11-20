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
 * #339 (Claw, v1.1.0-dev3, A51, 62 drag-vs-agent-write rounds): two outcomes #334 left.
 * - Silent loss: the agent's `cp` landed between the save's last look at launcher.json and its truncate-write (two
 *   provider calls), was overwritten in full, and the read-back saw only our bytes: status.json `ok`, agent item gone.
 * - Torn file: the agent's longer file landed after our truncate and we wrote over its start: our JSON plus its
 *   tail. The read-back did differ, but "not a prefix of ours" made it "the agent's file": left torn, B6 over it, RELOAD
 *   busy and status.json on the old `ok` (polls skip the file under B6) until the user tapped Use file.
 * Real [SafWrites] and [ConfigEngine] on a fake tree; the agent's steps land at fixed points of the save. No clock.
 */
class Issue339TornWriteTest {
    @get:Rule val tmp = TemporaryFolder()

    private val base = """{"schemaVersion":1,"revision":62,"pages":[{"id":"home","items":[""" +
        """{"id":"a-camera","type":"app","package":"a.camera","x":0,"y":0},{"id":"a-keep","type":"app","package":"a.keep","x":1,"y":0}]}]}"""
    private fun agent(rev: Int, extra: String = "a-agent-22") = """{"schemaVersion":1,"revision":$rev,"pages":[{"id":"home","items":[""" +
        """{"id":"a-camera","type":"app","package":"a.camera","x":0,"y":0},{"id":"a-keep","type":"app","package":"a.keep","x":1,"y":0},""" +
        """{"id":"$extra","type":"app","package":"com.sec.android.app.popupcalculator","x":2,"y":4}]}]}"""

    /** A fake provider. [fd]: it gives a seekable read/write descriptor ([DocTree.replace]), as the A51's does. */
    private class Racing(val t: SafDirsTest.FakeTree, val fd: Boolean) : DocTree by t {
        /** Once, right after a read of launcher.json returns (the save's own look comes first). */
        var afterTargetRead: (() -> Unit)? = null
        /** Once, after our bytes reach launcher.json: what the file becomes (the agent's write meeting ours). */
        var afterTargetWrite: ((ByteArray) -> ByteArray)? = null
        val replaces = mutableListOf<String>()
        private fun isTarget(id: String) = t.docs[id]?.name == "launcher.json"
        override fun read(docId: String): ByteArray? {
            val out = t.read(docId)
            if (isTarget(docId)) afterTargetRead?.let { afterTargetRead = null; it() }
            return out
        }
        override fun write(docId: String, bytes: ByteArray) {
            t.write(docId, bytes)
            if (isTarget(docId)) afterTargetWrite?.let { f -> afterTargetWrite = null; t.write(docId, f(bytes)) }
        }
        override fun replace(docId: String, bytes: ByteArray, check: (ByteArray) -> Unit): Boolean {
            if (!fd) return false
            replaces += docId
            check(t.read(docId) ?: ByteArray(0)) // the same open: nothing lands between this and the write
            write(docId, bytes)
            return true
        }
    }

    private inner class TreeSource(fd: Boolean = true) : ConfigSource {
        val t = SafDirsTest.FakeTree()
        val r = Racing(t, fd)
        private val key = "t339-${System.identityHashCode(this)}"
        val writes = SafWrites(r, SafDirs(r, key), key)
        init { t.write(t.add("root", "launcher.json", "application/json"), base.toByteArray()) }
        val file: String? get() = t.list("root")["launcher.json"]?.let { t.read(it.docId) }?.let { String(it) }
        val sidecars = mutableMapOf<String, String>()
        fun agentWrites(json: String) { t.write(t.list("root")["launcher.json"]!!.docId, json.toByteArray()) }
        override val displayName = "Folder: test"
        override fun stamp() = t.list("root")["launcher.json"]?.let { Stamp(it.docId, 1, it.size) }
        override fun read(maxBytes: Int) = t.list("root")["launcher.json"]?.let { t.read(it.docId) }
        override fun write(bytes: ByteArray, expectHash: String?) = writes.writeVerified("root", "launcher.json", "application/json", bytes, "layout", expectHash)
        override fun lastWriteOverlapped() = writes.overlapped
        override fun writeSidecar(subdir: String, name: String, bytes: ByteArray) { sidecars["$subdir/$name"] = String(bytes) }
        fun status() = sidecars["/status.json"]!!
        fun result() = Regex("\"result\":\"([a-z_]+)\"").find(status())!!.groupValues[1]
    }

    private fun engine(src: ConfigSource) = ConfigEngine(src, LastGoodStore(tmp.newFolder()), stabilityMs = 0, clock = { 1_000L })
    private fun ids(json: String?) = ((ConfigCodec.decode(json!!.toByteArray()) as ParseResult.Ok).config.pages.flatMap { p -> p.items.map { it.id } }).sorted()
    private fun camera(json: String?) = (ConfigCodec.decode(json!!.toByteArray()) as ParseResult.Ok).config.pages.flatMap { it.items }
        .first { it.id == "a-camera" }.let { it.x to it.y }
    private fun parses(json: String?) = ConfigCodec.decode(json!!.toByteArray()) is ParseResult.Ok
    private fun moveCamera() = Edit.MoveToCell("a-camera", "home", 3, 3)

    // ---- (1) silent loss ----

    @Test fun anAgentWriteAfterTheSavesLookIsMergedNotOverwritten() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(moveCamera()))
        // Claw rounds 102/113: the agent's whole cp lands right after the save's look at launcher.json, before its write.
        src.r.afterTargetRead = { src.agentWrites(agent(63)) }
        assertTrue(e.flush())
        assertTrue("the replace re-checked in its own open", src.r.replaces.isNotEmpty())
        assertEquals("the agent's item kept, our move merged onto it", listOf("a-agent-22", "a-camera", "a-keep"), ids(src.file))
        assertEquals("our move kept", 3 to 3, camera(src.file))
        assertEquals("ok", src.result())
        assertTrue(src.status().contains(ConfigCodec.sha256(src.file!!.toByteArray())))
        assertNull(e.state.value.issue); assertFalse(e.state.value.conflict)
    }

    // ---- (2) torn file ----

    /** Claw rounds 22/124: the agent's longer file landed after our truncate; our bytes went over its start. */
    private fun TreeSource.tearsTheNextWrite(agentJson: String) {
        // As the agent's tool would write it: pretty-printed, so longer than our save (Claw: 3,087 vs 2,844 bytes).
        val a = ConfigCodec.encodeBytes((ConfigCodec.decode(agentJson.toByteArray()) as ParseResult.Ok).config)
        r.afterTargetWrite = { ours -> check(a.size > ours.size); ours + a.copyOfRange(ours.size, a.size) }
    }

    @Test fun aReadBackWithTrailingBytesIsRejectedAndRepaired() {
        val src = TreeSource()
        val ours = base.replace("\"revision\":62", "\"revision\":63").toByteArray()
        val longer = agent(63)
        assertTrue(longer.length > ours.size)
        src.tearsTheNextWrite(longer)
        src.writes.writeVerified("root", "launcher.json", "application/json", ours, "layout", expectOf(base.toByteArray()))
        assertEquals("exactly our bytes: no trailing ones left", String(ours), src.file)
        assertTrue(src.writes.overlapped)
        // The next clean save resets it.
        src.writes.writeVerified("root", "launcher.json", "application/json", base.toByteArray(), "layout", expectOf(ours))
        assertFalse(src.writes.overlapped)
    }

    @Test fun anOldTailFromAProviderThatDidNotTruncateIsNoOverlap() {
        val src = TreeSource(fd = false)
        val longOld = agent(62); src.agentWrites(longOld)
        val ours = base.toByteArray()
        src.r.afterTargetWrite = { o -> val a = longOld.toByteArray(); o + a.copyOfRange(o.size, a.size) }
        src.writes.writeVerified("root", "launcher.json", "application/json", ours, "layout", expectOf(longOld.toByteArray()))
        assertEquals(base, src.file)
        assertFalse("the tail was the old file's, nobody else wrote", src.writes.overlapped)
    }

    @Test fun aTornSaveEndsValidAndStatusSaysTheAgentWriteWasCut() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(moveCamera()))
        src.tearsTheNextWrite(agent(63))
        assertTrue(e.flush())
        assertTrue("never left torn", parses(src.file))
        assertFalse(e.state.value.conflict); assertNull(e.state.value.issue)
        assertEquals("ok", src.result())
        assertTrue(src.status(), src.status().contains("overlapped the phone's save"))
        assertEquals(ReloadOutcome.UNCHANGED, e.reloadForAgent())
        // The agent writes again: loaded (merged nothing pending), and the overlap line is answered.
        src.agentWrites(agent(64, "a-agent-23"))
        assertEquals(ReloadOutcome.RELOADED, e.reloadForAgent())
        assertFalse(src.status().contains("overlapped"))
    }

    // ---- status.json while B6 is up ----

    @Test fun aTornFileUnderB6IsReportedAndMergedOnceItLoads() = runBlocking {
        // A torn file the save didn't make (e.g. the agent's own cp caught mid-way): B6 over a file that doesn't load.
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertEquals("ok", src.result())
        assertTrue(e.edit(moveCamera()))
        val torn = String(ConfigCodec.encodeBytes(e.state.value.config)) + agent(63).takeLast(40)
        src.agentWrites(torn)
        assertFalse(e.flush())
        assertTrue(e.state.value.conflict)
        // Before: status.json kept the last "ok" (rev 62) with a fresh time; now it describes the torn file.
        assertEquals("parse_error", src.result())
        assertTrue(src.status().contains(ConfigCodec.sha256(torn.toByteArray())))
        assertTrue(src.status().contains("doesn't load"))
        assertEquals("rejected, not busy", ReloadOutcome.REJECTED, e.reloadForAgent())
        assertEquals("parse_error", src.result())
        // The agent writes a valid file: the next poll merges our move onto it and B6 goes (no Use file needed).
        src.agentWrites(agent(84))
        assertTrue(e.poll())
        assertFalse(e.state.value.conflict); assertNull(e.state.value.issue)
        assertEquals(listOf("a-agent-22", "a-camera", "a-keep"), ids(src.file))
        assertEquals("our move kept", 3 to 3, camera(src.file))
        assertEquals("ok", src.result())
        assertTrue(src.status().contains(ConfigCodec.sha256(src.file!!.toByteArray())))
        assertFalse(src.status().contains("isn't saved yet"))
        assertEquals(ReloadOutcome.UNCHANGED, e.reloadForAgent())
    }

    @Test fun reloadAloneClearsATornB6OnceTheFileLoads() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(moveCamera()))
        src.agentWrites(agent(63).dropLast(3))
        assertFalse(e.flush()); assertTrue(e.state.value.conflict)
        src.agentWrites(agent(64))
        assertEquals(ReloadOutcome.RELOADED, e.reloadForAgent())
        assertFalse(e.state.value.conflict)
        assertEquals(listOf("a-agent-22", "a-camera", "a-keep"), ids(src.file))
    }

    @Test fun askBeforeMergingB6IsConflictInStatusNotOk() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        e.askBeforeMerge = true
        assertTrue(e.edit(moveCamera()))
        src.agentWrites(agent(63))
        assertFalse(e.flush()); assertTrue(e.state.value.conflict)
        assertEquals("conflict", src.result())
        assertTrue(src.status().contains(ConfigCodec.sha256(agent(63).toByteArray())))
        assertTrue(src.status(), src.status().contains("\"revision\":63"))
        assertTrue(src.status().contains("Use file or Keep mine"))
        // The user's call: polls and RELOAD don't merge it away, and status.json stays honest through RELOAD.
        src.agentWrites(agent(64))
        assertFalse(e.poll(force = true)); assertTrue(e.state.value.conflict)
        assertEquals(ReloadOutcome.BUSY, e.reloadForAgent())
        assertEquals("conflict", src.result())
        e.resolveUseFile()
        assertFalse(e.state.value.conflict)
        assertEquals("ok", src.result())
        assertTrue(src.status().contains("\"revision\":64"))
        assertFalse(src.status().contains("isn't saved yet"))
    }

    @Test fun keepMineAfterATornB6ClearsTheBrokenState() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(moveCamera()))
        src.agentWrites(agent(63).dropLast(3))
        assertFalse(e.flush()); assertTrue(e.state.value.conflict)
        assertTrue(e.resolveKeepMine())
        assertFalse(e.state.value.conflict)
        assertEquals("ok", src.result())
        // A later broken write is an ordinary rejected file, not a B6 left over.
        src.agentWrites("{")
        assertEquals(ReloadOutcome.REJECTED, e.reloadForAgent())
        assertFalse(e.state.value.conflict)
        assertFalse(src.status().contains("isn't saved yet"))
    }

    @Test fun aTearThatCannotBeRepairedIsRestoredNeverKeptAsTheirs() = runBlocking {
        // Every write of our bytes comes back torn (the repair too): the original goes back and B5 says so. Before, a
        // torn read-back was "someone else's file": kept torn, B6 over it.
        val src = TreeSource()
        val tail = "  ]}]}  , \"a-agent-22\" }".toByteArray()
        val t = src.t
        val tearing = object : DocTree by src.r {
            override fun write(docId: String, bytes: ByteArray) {
                src.r.write(docId, bytes)
                if (t.docs[docId]?.name == "launcher.json" && !bytes.contentEquals(base.toByteArray())) t.write(docId, bytes + tail)
            }
            override fun replace(docId: String, bytes: ByteArray, check: (ByteArray) -> Unit): Boolean {
                check(t.read(docId) ?: ByteArray(0)); write(docId, bytes); return true
            }
        }
        val key = "t339-tear-${System.identityHashCode(this)}"
        val w = SafWrites(tearing, SafDirs(tearing, key), key)
        val src2 = object : ConfigSource by src {
            override fun write(bytes: ByteArray, expectHash: String?) = w.writeVerified("root", "launcher.json", "application/json", bytes, "layout", expectHash)
            override fun lastWriteOverlapped() = w.overlapped
        }
        val e2 = engine(src2)
        assertTrue(e2.poll(force = true))
        assertTrue(e2.edit(moveCamera()))
        assertFalse(e2.flush())
        assertEquals("the old file is put back", base, src.file)
        assertFalse("no B6 over a torn file", e2.state.value.conflict)
        assertTrue(e2.state.value.issue is Issue.SaveFailed)
        assertEquals(1, e2.state.value.pendingEdits)
    }

    @Test fun withAskBeforeMergingABrokenFileB6StaysTheUsersCall() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        e.askBeforeMerge = true
        assertTrue(e.edit(moveCamera()))
        src.agentWrites(agent(63).dropLast(3))
        assertFalse(e.flush()); assertTrue(e.state.value.conflict)
        assertEquals("parse_error", src.result())
        assertTrue(src.status().contains("Use file or Keep mine"))
        assertEquals(ReloadOutcome.BUSY, e.reloadForAgent())
        src.agentWrites(agent(64))
        assertFalse(e.poll(force = true))
        assertTrue("never merged without asking", e.state.value.conflict)
        assertEquals(ReloadOutcome.BUSY, e.reloadForAgent())
    }
}
