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
import java.io.IOException

/**
 * #347 review nit (Grace's scratch f1/f3): a failed save never becomes the stored layout hash (the base), whether its
 * bytes reached the file or not, and status.json never names them `ok`; the retry then saves for real.
 */
class Issue347FailedSaveTest {
    @get:Rule val tmp = TemporaryFolder()
    private val start = """{"schemaVersion":1,"revision":1,"grid":{"columns":4,"rows":5},"pages":[{"id":"home","items":[
        {"id":"a","type":"app","package":"p.a","x":0,"y":0},{"id":"b","type":"app","package":"p.b","x":1,"y":0}]}],"dock":[]}"""

    /** mode: "ok" | "landThenThrow" (the bytes reach the file, the save still fails) | "throw" (nothing reaches it). */
    private class Flaky(val src: LocalFileSource) : ConfigSource by src {
        var mode = "ok"; var lastAttempt: ByteArray? = null
        override fun write(bytes: ByteArray, expectHash: String?) {
            lastAttempt = bytes
            when (mode) {
                "landThenThrow" -> { src.write(bytes, expectHash); throw IOException("write verification failed (test)") }
                "throw" -> throw IOException("provider offline (test)")
                else -> src.write(bytes, expectHash)
            }
        }
    }
    private fun setup(): Triple<ConfigEngine, LocalFileSource, Flaky> = runBlocking {
        val src = LocalFileSource(tmp.newFolder()); src.file.writeText(start); src.file.setLastModified(1_000L)
        val f = Flaky(src)
        val e = ConfigEngine(f, LastGoodStore(tmp.newFolder()), stabilityMs = 0, clock = { 5_000L }); assertTrue(e.poll(force = true))
        Triple(e, src, f)
    }
    private fun status(src: LocalFileSource) = Json.parseToJsonElement(File(src.dir, ".configlauncher/status.json").readText()).jsonObject
    private fun pos(c: LauncherConfig, id: String) = c.pages[0].items.first { it.id == id }.let { it.x to it.y }
    private fun disk(src: LocalFileSource) = (ConfigCodec.decode(src.file.readBytes()) as ParseResult.Ok).config

    /** Grace f1: the bytes landed but the save failed. */
    @Test fun aSaveWhoseBytesLandedButFailedIsNotTheBase() = runBlocking {
        val (e, src, f) = setup(); val base0 = e.state.value.baseHash
        f.mode = "landThenThrow"
        assertTrue(e.edit(Edit.MoveToCell("a", "home", 2, 2)))
        e.flush()
        val failed = f.lastAttempt!!
        assertEquals("a failed save is not the base", base0, e.state.value.baseHash)
        val s = status(src)
        assertFalse("status.json never names the unverified bytes ok",
            s["result"]!!.jsonPrimitive.content == "ok" && s["hash"]!!.jsonPrimitive.content == ConfigCodec.sha256(failed))
        e.poll(force = true)
        f.mode = "ok"
        e.reloadForAgent()
        assertEquals(2 to 2, pos(disk(src), "a")); assertEquals(0, e.state.value.pendingEdits)
        assertEquals(ConfigCodec.sha256(src.file.readBytes()), e.state.value.baseHash)
    }

    /** Grace f3: nothing reached the file: the file is still the base (an echo), the edit stays pending, then saves. */
    @Test fun aSaveThatFailedBeforeLandingLeavesTheBase() = runBlocking {
        val (e, src, f) = setup(); val base0 = e.state.value.baseHash
        f.mode = "throw"
        assertTrue(e.edit(Edit.MoveToCell("a", "home", 2, 2))); e.flush()
        e.poll(force = true)
        assertEquals(base0, e.state.value.baseHash); assertEquals(1, e.state.value.pendingEdits)
        f.mode = "ok"; e.reloadForAgent()
        assertEquals(2 to 2, pos(disk(src), "a"))
    }
}
