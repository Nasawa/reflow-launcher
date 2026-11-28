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
 * #342 (in device runs): an agent's `cp` landed inside the phone's pending save (debounce, then
 * read → temp write → replace) and the final file was the phone's save, with no banner or warning.
 *
 * Every step of the save where the agent's write can land, in order, on the real [ConfigEngine] and [SafWrites] over a
 * fake tree. The agent's bytes land at a fixed point (a hook on the provider call before it), so each window is hit
 * every time; no clock, no threads. In each, the agent's item must survive (merged) or the user/agent must be told (B6 /
 * status.json); never a quiet `ok` without it.
 */
class Issue342PendingSaveRaceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val base = """{"schemaVersion":1,"revision":65,"pages":[{"id":"home","items":[""" +
        """{"id":"a-camera","type":"app","package":"a.camera","x":0,"y":0},{"id":"a-keep","type":"app","package":"a.keep","x":1,"y":0}]}]}"""
    private val agent = """{"schemaVersion":1,"revision":66,"pages":[{"id":"home","items":[""" +
        """{"id":"a-camera","type":"app","package":"a.camera","x":0,"y":0},{"id":"a-keep","type":"app","package":"a.keep","x":1,"y":0},""" +
        """{"id":"a-agent-66","type":"app","package":"com.sec.android.app.popupcalculator","x":2,"y":4}]}]}"""

    /** Provider calls on launcher.json, in order: each one can run the agent's next step before it starts. */
    private class Racing(val t: SafDirsTest.FakeTree, val fd: Boolean) : DocTree by t {
        /** Steps by the n-th call on launcher.json (0-based), run just before that call. */
        val before = mutableMapOf<Int, () -> Unit>()
        val calls = mutableListOf<String>()
        private fun isTarget(id: String) = t.docs[id]?.name == "launcher.json"
        private fun step(id: String, what: String) { if (isTarget(id)) { before.remove(calls.size)?.invoke(); calls += what } }
        override fun read(docId: String): ByteArray? { step(docId, "read"); return t.read(docId) }
        override fun write(docId: String, bytes: ByteArray) { step(docId, "write"); t.write(docId, bytes) }
        override fun replace(docId: String, bytes: ByteArray, check: (ByteArray) -> Unit): Boolean {
            if (!fd) { step(docId, "replace-unsupported"); return false }
            step(docId, "replace")
            check(t.read(docId) ?: ByteArray(0)) // one open: the check and the write
            t.write(docId, bytes)
            return true
        }
    }

    private inner class TreeSource(fd: Boolean = true) : ConfigSource {
        val t = SafDirsTest.FakeTree()
        val r = Racing(t, fd)
        private val key = "t342-${System.identityHashCode(this)}"
        val writes = SafWrites(r, SafDirs(r, key), key)
        private val doc = t.add("root", "launcher.json", "application/json")
        init { t.write(doc, base.toByteArray()) }
        val file: String get() = String(t.read(doc)!!)
        val sidecars = mutableMapOf<String, String>()
        /** The agent's `cp`: truncate, then (maybe later) the bytes. */
        fun agentTruncates() = t.write(doc, ByteArray(0))
        fun agentWrites(json: String = agent) = t.write(doc, json.toByteArray())
        override val displayName = "Folder: test"
        override fun stamp() = Stamp(doc, 1, t.read(doc)!!.size.toLong())
        // The engine's own whole-file reads (flush, poll) go through the same counted provider read.
        override fun read(maxBytes: Int) = r.read(doc)
        override fun write(bytes: ByteArray, expectHash: String?) = writes.writeVerified("root", "launcher.json", "application/json", bytes, "layout", expectHash)
        override fun lastWriteOverlapped() = writes.overlapped
        override fun writeSidecar(subdir: String, name: String, bytes: ByteArray) { sidecars["$subdir/$name"] = String(bytes) }
        fun status() = sidecars["/status.json"]!!
        fun result() = Regex("\"result\":\"([a-z_]+)\"").find(status())!!.groupValues[1]
    }

    private fun engine(src: ConfigSource) = ConfigEngine(src, LastGoodStore(tmp.newFolder()), stabilityMs = 0, clock = { 1_000L })
    private fun ids(json: String) = ((ConfigCodec.decode(json.toByteArray()) as ParseResult.Ok).config.pages.flatMap { p -> p.items.map { it.id } }).sorted()
    private fun camera(json: String) = (ConfigCodec.decode(json.toByteArray()) as ParseResult.Ok).config.pages.flatMap { it.items }
        .first { it.id == "a-camera" }.let { it.x to it.y }
    private val both = listOf("a-agent-66", "a-camera", "a-keep")

    /** The phone's drag is pending (inside the save delay); [race] places the agent's steps; then the save runs. */
    private fun round(fd: Boolean = true, race: TreeSource.() -> Unit): Pair<TreeSource, ConfigEngine> = runBlocking {
        val src = TreeSource(fd); val e = engine(src)
        assertTrue(e.poll(force = true))
        src.r.calls.clear()
        assertTrue(e.edit(Edit.MoveToCell("a-camera", "home", 3, 3)))
        src.race()
        e.flush()
        src to e
    }

    /** Both changes in the file, status.json `ok` on it, no banner. */
    private fun assertMerged(src: TreeSource, e: ConfigEngine) {
        assertEquals("the agent's item kept: ${src.r.calls}", both, ids(src.file))
        assertEquals("our move kept", 3 to 3, camera(src.file))
        assertEquals("ok", src.result())
        assertTrue(src.status().contains(ConfigCodec.sha256(src.file.toByteArray())))
        assertFalse(e.state.value.conflict); assertNull(e.state.value.issue)
    }

    /** The save's calls on launcher.json with no race: flush's look (0), the original (1), the replace (2), read-back (3). */
    @Test fun theSaveLooksThreeTimesBeforeItsBytesAreFinal() {
        val (src, _) = round { }
        assertEquals(listOf("read", "read", "replace", "read"), src.r.calls)
    }

    @Test fun anAgentWriteInsideTheSaveDelayIsMerged() {
        // Before the debounced save starts: its first look already sees the agent's file.
        val (src, e) = round { r.before[0] = { agentWrites() } }
        assertMerged(src, e)
    }

    @Test fun anAgentWriteSeenByAPollInsideTheSaveDelayIsMerged() = runBlocking {
        val src = TreeSource(); val e = engine(src)
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(Edit.MoveToCell("a-camera", "home", 3, 3)))
        src.agentWrites()
        e.poll(force = true) // the observer fires while the drag waits to be saved
        e.flush()
        assertMerged(src, e)
    }

    /** In device runs: the agent's cp after the save's look, around the temp write (before the original's read). */
    @Test fun anAgentWriteDuringTheTempWriteIsMerged() {
        val (src, e) = round { r.before[1] = { agentWrites() } }
        assertMerged(src, e)
    }

    @Test fun anAgentWriteBetweenTheOriginalAndTheReplaceIsMerged() {
        val (src, e) = round { r.before[2] = { agentWrites() } }
        assertMerged(src, e)
    }

    /** cp truncates first: the save's look at the original sees an empty file, the bytes land at its next look. */
    @Test fun anAgentCpCaughtHalfWayIsMergedOnceItsBytesLand() {
        val (src, e) = round { r.before[1] = { agentTruncates() }; r.before[2] = { agentWrites() } }
        assertMerged(src, e)
    }

    /** The bytes land only after the retry looked too (empty both times): B6 over a file that doesn't load, then merged. */
    @Test fun anAgentCpStillEmptyAtTheRetryIsNeverOverwritten() = runBlocking {
        val (src, e) = round { r.before[1] = { agentTruncates() } }
        assertTrue("an empty launcher.json is never written over: ${src.r.calls}", src.file.isEmpty())
        assertTrue("B6 is up", e.state.value.conflict)
        assertNotEquals("status.json isn't a quiet ok", "ok", src.result())
        src.agentWrites()
        e.poll(force = true)
        assertMerged(src, e)
    }

    /** A provider without a seekable descriptor: the save looks again right before its "wt" write (#342). */
    @Test fun withoutReplaceAnAgentWriteAfterTheOriginalIsStillMerged() {
        val (src, e) = round(fd = false) { r.before[2] = { agentWrites() } }
        assertMerged(src, e)
    }

    @Test fun withoutReplaceTheSaveLooksAgainRightBeforeItsWrite() {
        val (src, _) = round(fd = false) { }
        assertEquals(listOf("read", "read", "replace-unsupported", "read", "write", "read"), src.r.calls)
    }

    /** The agent's file lands after ours in full: the read-back sees theirs, kept and merged onto (never overwritten). */
    @Test fun anAgentWriteRightAfterOursIsMerged() {
        val (src, e) = round { r.before[3] = { agentWrites() } }
        assertMerged(src, e)
    }
}
