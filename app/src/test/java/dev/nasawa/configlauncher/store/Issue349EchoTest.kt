package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.LauncherConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #349 (Grace, s29): the phone saves A, then B; an agent copies A back. A's hash was among the phone's last 4 saves, so
 * the echo guard swallowed it: B kept showing, RELOAD said "unchanged", and with #347 status.json said `ok` with A's hash
 * and B's revision. Only the layout showing is an echo now. No clock (file times are set by hand).
 */
class Issue349EchoTest {
    @get:Rule val tmp = TemporaryFolder()
    private val start = """{"schemaVersion":1,"revision":1,"grid":{"columns":4,"rows":5},"pages":[{"id":"home","items":[
        {"id":"a","type":"app","package":"p.a","x":0,"y":0},{"id":"b","type":"app","package":"p.b","x":1,"y":0}]}],"dock":[]}"""
    private var mtime = 1_000L

    private fun setup(): Pair<ConfigEngine, LocalFileSource> = runBlocking {
        val src = LocalFileSource(tmp.newFolder()); src.file.writeText(start); src.file.setLastModified(mtime)
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), stabilityMs = 0, clock = { 5_000L }); assertTrue(e.poll(force = true))
        e to src
    }
    private fun LocalFileSource.agentWrites(bytes: ByteArray) { file.writeBytes(bytes); mtime += 1_000; file.setLastModified(mtime) }
    private fun status(src: LocalFileSource) = Json.parseToJsonElement(File(src.dir, ".configlauncher/status.json").readText()).jsonObject
    private fun pos(c: LauncherConfig, id: String) = c.pages[0].items.first { it.id == id }.let { it.x to it.y }
    private fun onDisk(src: LocalFileSource) = (ConfigCodec.decode(src.file.readBytes()) as ParseResult.Ok).config

    /** The phone saves A (a moved), then B (b moved too); returns A's bytes. */
    private fun savesAThenB(e: ConfigEngine, src: LocalFileSource): ByteArray = runBlocking {
        assertTrue(e.edit(Edit.MoveToCell("a", "home", 2, 2))); assertTrue(e.flush())
        val a = src.file.readBytes()
        assertTrue(e.edit(Edit.MoveToCell("b", "home", 3, 3))); assertTrue(e.flush())
        assertEquals(3 to 3, pos(e.state.value.config, "b"))
        a
    }

    @Test fun anOlderSaveCopiedBackIsLoaded() = runBlocking {
        val (e, src) = setup()
        val a = savesAThenB(e, src)
        src.agentWrites(a)
        assertEquals("not swallowed as our echo", ReloadOutcome.RELOADED, e.reloadForAgent())
        val shown = e.state.value.config
        assertEquals("A shows", 1 to 0, pos(shown, "b")); assertEquals(2 to 2, pos(shown, "a"))
        assertEquals(ConfigCodec.sha256(a), e.state.value.baseHash)
    }

    @Test fun anOlderSaveCopiedBackIsLoadedByAPollToo() = runBlocking {
        val (e, src) = setup()
        val a = savesAThenB(e, src)
        src.agentWrites(a)
        assertTrue(e.poll(force = true))
        assertEquals(1 to 0, pos(e.state.value.config, "b"))
    }

    @Test fun statusNamesTheLayoutShowing() = runBlocking {
        val (e, src) = setup()
        val a = savesAThenB(e, src)
        val aRevision = (ConfigCodec.decode(a) as ParseResult.Ok).config.revision
        src.agentWrites(a)
        e.reloadForAgent()
        val s = status(src)
        assertEquals("ok", s["result"]!!.jsonPrimitive.content)
        assertEquals(ConfigCodec.sha256(a), s["hash"]!!.jsonPrimitive.content)
        assertEquals("A's revision, not B's", aRevision, s["revision"]!!.jsonPrimitive.long)
        assertEquals("what shows is what status.json names", aRevision, e.state.value.config.revision)
    }

    /** With a phone edit pending, the copy-back is merged onto (the agent's A wins except the touched item), never overwritten. */
    @Test fun anOlderSaveCopiedBackUnderAPendingEditIsMerged() = runBlocking {
        val (e, src) = setup()
        val a = savesAThenB(e, src)
        assertTrue(e.edit(Edit.MoveToCell("a", "home", 0, 4)))
        src.agentWrites(a)
        assertTrue(e.flush())
        val c = onDisk(src)
        assertEquals("the agent's A for b", 1 to 0, pos(c, "b"))
        assertEquals("the phone's pending move for a", 0 to 4, pos(c, "a"))
        assertNotNull("the user is told about the merge", e.state.value.merge)
        assertEquals(ConfigCodec.sha256(src.file.readBytes()), status(src)["hash"]!!.jsonPrimitive.content)
    }

    /** Our latest save is still an echo: no reload, and status.json stays as it was. */
    @Test fun theLatestSaveIsStillAnEcho() = runBlocking {
        val (e, src) = setup()
        savesAThenB(e, src)
        val before = File(src.dir, ".configlauncher/status.json").readText()
        assertFalse(e.poll(force = true))
        assertEquals(before, File(src.dir, ".configlauncher/status.json").readText())
    }

    /**
     * #343 review (Grace): the invalid-hash reset. A broken write, then the loaded file restored, clears it; the same broken
     * bytes written again while a phone edit is pending are the agent's new write (B6 over a file that doesn't load), not
     * the "still broken" file the phone may overwrite.
     */
    @Test fun restoringTheLoadedFileForgetsTheBrokenOne() = runBlocking {
        val (e, src) = setup()
        val good = src.file.readBytes()
        val broken = start.dropLast(4).toByteArray()
        src.agentWrites(broken); assertFalse(e.poll(force = true))
        assertEquals("parse_error", status(src)["result"]!!.jsonPrimitive.content)
        src.agentWrites(good); assertFalse(e.poll(force = true))
        assertEquals("ok", status(src)["result"]!!.jsonPrimitive.content)
        assertTrue(e.edit(Edit.MoveToCell("a", "home", 2, 2)))
        src.agentWrites(broken)
        assertFalse(e.flush())
        assertArrayEquals("the agent's file is left alone", broken, src.file.readBytes())
        assertTrue("B6", e.state.value.conflict)
        assertEquals(1, e.state.value.pendingEdits)
    }
}
