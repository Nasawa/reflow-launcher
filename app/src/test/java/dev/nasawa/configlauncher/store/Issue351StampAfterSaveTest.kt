package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.Edit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #351 (Claw, v1.1.0-dev5, A51, 3/200): an agent's `cp` finishing 40-120 ms after the save's read-back was never loaded.
 * The save took launcher.json's stamp (docId, modified, size) only after the read-back, the temp delete and the sweep,
 * so that stamp was already the agent's file; every later poll saw "no change" and skipped the read. Screen and
 * status.json kept our save over the agent's file. Real [ConfigEngine] and [SafWrites] on a fake tree; the agent's
 * write lands at a fixed provider call after the read-back. No clock.
 */
class Issue351StampAfterSaveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val base = """{"schemaVersion":1,"revision":54,"pages":[{"id":"home","items":[""" +
        """{"id":"a-camera","type":"app","package":"a.camera","x":0,"y":0},{"id":"a-keep","type":"app","package":"a.keep","x":1,"y":0}]}]}"""
    private val agent = """{"schemaVersion":1,"revision":55,"pages":[{"id":"home","items":[""" +
        """{"id":"a-camera","type":"app","package":"a.camera","x":0,"y":0},{"id":"a-keep","type":"app","package":"a.keep","x":1,"y":0},""" +
        """{"id":"a-agent-55","type":"app","package":"com.sec.android.app.popupcalculator","x":2,"y":4}]}]}"""

    /** A provider whose launcher.json stamp changes with every write (like `modified` + size), and a hook after a call. */
    private class Racing(val t: SafDirsTest.FakeTree) : DocTree by t {
        var version = 0L
        /** Runs once, right after the n-th call on launcher.json (0-based) returns. */
        val after = mutableMapOf<Int, () -> Unit>()
        var calls = 0
        private fun isTarget(id: String) = t.docs[id]?.name == "launcher.json"
        private fun <T> step(id: String, f: () -> T): T {
            if (!isTarget(id)) return f()
            val n = calls++
            return f().also { after.remove(n)?.invoke() }
        }
        override fun read(docId: String): ByteArray? = step(docId) { t.read(docId) }
        override fun write(docId: String, bytes: ByteArray) = step(docId) { t.write(docId, bytes); if (isTarget(docId)) version++ }
        override fun replace(docId: String, bytes: ByteArray, check: (ByteArray) -> Unit): Boolean = step(docId) {
            check(t.read(docId) ?: ByteArray(0)); t.write(docId, bytes); version++; true
        }
    }

    private inner class TreeSource : ConfigSource {
        val t = SafDirsTest.FakeTree()
        val r = Racing(t)
        private val key = "t351-${System.identityHashCode(this)}"
        val writes = SafWrites(r, SafDirs(r, key), key)
        private val doc = t.add("root", "launcher.json", "application/json")
        init { t.write(doc, base.toByteArray()) }
        val file: String get() = String(t.read(doc)!!)
        val sidecars = mutableMapOf<String, String>()
        fun agentWrites(json: String = agent) { t.write(doc, json.toByteArray()); r.version++ }
        override val displayName = "Folder: test"
        override fun stamp() = Stamp(doc, r.version, t.read(doc)!!.size.toLong())
        override fun read(maxBytes: Int) = r.read(doc)
        override fun write(bytes: ByteArray, expectHash: String?) = writes.writeVerified("root", "launcher.json", "application/json", bytes, "layout", expectHash)
        override fun lastWriteOverlapped() = writes.overlapped
        override fun writeSidecar(subdir: String, name: String, bytes: ByteArray) { sidecars["$subdir/$name"] = String(bytes) }
        fun statusHash() = Regex("\"hash\":\"([0-9a-f]+)\"").find(sidecars["/status.json"]!!)!!.groupValues[1]
    }

    private fun ids(c: dev.nasawa.configlauncher.model.LauncherConfig) = c.pages.flatMap { p -> p.items.map { it.id } }.sorted()

    /** The phone's drag saves; the agent's write lands right after the save's read-back (before any bookkeeping). */
    private fun savedThenAgentAfterReadBack(): Pair<TreeSource, ConfigEngine> = runBlocking {
        val src = TreeSource()
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), stabilityMs = 0, clock = { 1_000L })
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(Edit.MoveToCell("a-camera", "home", 3, 3)))
        src.r.calls = 0
        // Calls on launcher.json in the save: flush's look (0), original (1), replace (2), read-back (3).
        src.r.after[3] = { src.agentWrites() }
        assertTrue(e.flush())
        assertEquals("the agent's file is on disk", agent, src.file)
        src to e
    }

    @Test fun anAgentWriteJustAfterTheReadBackIsLoadedByTheNextPoll() = runBlocking {
        val (src, e) = savedThenAgentAfterReadBack()
        // A plain timed poll (not forced, not a hash-anyway one): Claw's phone saw only these for 8 s.
        assertTrue("the next poll loads the agent's file", e.poll())
        assertEquals(listOf("a-agent-55", "a-camera", "a-keep"), ids(e.state.value.config))
        assertEquals(ConfigCodec.sha256(agent.toByteArray()), e.state.value.baseHash)
        assertEquals("status.json names the file on disk", ConfigCodec.sha256(agent.toByteArray()), src.statusHash())
    }

    @Test fun ourOwnSaveIsStillAnEchoForTheNextPoll() = runBlocking {
        val src = TreeSource()
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), stabilityMs = 0, clock = { 1_000L })
        assertTrue(e.poll(force = true))
        assertTrue(e.edit(Edit.MoveToCell("a-camera", "home", 3, 3)))
        assertTrue(e.flush())
        val saved = e.state.value.baseHash
        assertFalse("no reload of our own save", e.poll())
        assertEquals(saved, e.state.value.baseHash)
        assertFalse("and the stamp is settled after that read", e.poll())
    }

    /** The same window in a folder move that uses the folder's own file: its stamp came after the read too. */
    @Test fun anAgentWriteBetweenTheAdoptsReadAndItsStampIsLoadedByTheNextPoll() = runBlocking {
        val src = LocalFileSource(tmp.newFolder()); src.file.writeText(base); src.file.setLastModified(1_000L)
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), stabilityMs = 0, clock = { 1_000L })
        assertTrue(e.poll(force = true))
        val to = LocalFileSource(tmp.newFolder()); to.file.writeText(base.replace("\"revision\":54", "\"revision\":60"))
        to.file.setLastModified(2_000L)
        var agentDone = false
        val racing = object : ConfigSource by to {
            override fun stamp(): Stamp? {
                if (!agentDone) { agentDone = true; to.file.writeText(agent); to.file.setLastModified(3_000L) }
                return to.stamp()
            }
        }
        assertTrue(e.moveTo(racing, useMine = false))
        assertTrue("the agent's write landed in the window", agentDone)
        assertTrue("the next poll loads the agent's file", e.poll())
        assertEquals(listOf("a-agent-55", "a-camera", "a-keep"), ids(e.state.value.config))
        assertEquals(ConfigCodec.sha256(agent.toByteArray()), e.state.value.baseHash)
        val status = File(to.dir, ".configlauncher/status.json").readText()
        assertEquals("status.json names the file on disk", ConfigCodec.sha256(agent.toByteArray()),
            Regex("\"hash\":\"([0-9a-f]+)\"").find(status)!!.groupValues[1])
    }
}
