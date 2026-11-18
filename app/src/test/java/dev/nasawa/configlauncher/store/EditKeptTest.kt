package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.Edit
import kotlinx.coroutines.runBlocking
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
}
