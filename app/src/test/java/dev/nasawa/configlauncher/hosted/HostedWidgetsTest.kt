package dev.nasawa.configlauncher.hosted

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.hosted.HostedWidgets.Facts
import dev.nasawa.configlauncher.hosted.HostedWidgets.State
import dev.nasawa.configlauncher.store.ConfigEngine
import dev.nasawa.configlauncher.store.LastGoodStore
import dev.nasawa.configlauncher.store.LocalFileSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #300 NW-3: placeholder states, status.json `appWidgets`, and the §16.5 size check (never a rewrite). */
class HostedWidgetsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun aw(id: String, x: Int = 0, w: Int = 2, h: Int = 1, cls: String = ".W", page: String = "home", extra: String = "", content: String = "") =
        """{"id":"$id","type":"appWidget","requires":5$extra,"page":"$page","x":$x,"y":1,"w":$w,"h":$h,"content":{"provider":{"package":"com.example.weather","class":"$cls"}$content}}"""
    private fun doc(vararg els: String) =
        """{"schemaVersion":1,"grid":{"columns":4,"rows":5},"pages":[{"id":"home","items":[]},{"id":"two","items":[]}],"elements":[${els.joinToString(",")}]}"""
    private fun cfg(s: String) = (ConfigCodec.decode(s) as ParseResult.Ok).config
    private fun bound(id: Int, cls: String = ".W", state: WidgetBindings.State = WidgetBindings.State.BOUND) =
        WidgetBindings.Binding(id, WidgetBindings.Provider("com.example.weather", cls), "personal", state)
    private val installed = HostedWidgets.Providers { _, cls, _ -> if (cls == ".W" || cls == "com.example.weather.W") Facts("Weather", 2, 1, 4, 2) else null }

    @Test fun everyStateResolves() {
        val c = cfg(doc(aw("w-bound"), aw("w-setup", x = 2), aw("w-cfg").replace("\"x\":0,\"y\":1", "\"x\":0,\"y\":2"),
            aw("w-gone", cls = ".Gone").replace("\"y\":1", "\"y\":3"), aw("w-work", content = ",\"profile\":\"work\"").replace("\"y\":1", "\"y\":4"),
            aw("w-fail", page = "two")))
        val b = mapOf("w-bound" to bound(7), "w-cfg" to bound(8, state = WidgetBindings.State.NEEDS_CONFIG), "w-fail" to bound(9))
        val tiles = HostedWidgets.tiles(c, b, installed, failed = setOf("w-fail"))
        assertEquals(mapOf("w-bound" to State.BOUND, "w-setup" to State.NEEDS_SETUP, "w-cfg" to State.NEEDS_CONFIG,
            "w-gone" to State.NOT_INSTALLED, "w-work" to State.UNSUPPORTED, "w-fail" to State.FAILED), tiles.associate { it.id to it.state })
        // Only a bound tile carries its ID (for the host view); status.json gets keys only.
        assertEquals(listOf(7), tiles.mapNotNull { it.appWidgetId })
        assertEquals("unsupported", HostedWidgets.states(tiles)["w-work"])
        assertFalse(HostedWidgets.states(tiles).values.any { it.any(Char::isDigit) })
        assertEquals(listOf("w-fail"), HostedWidgets.tiles(c, b, installed, setOf("w-fail"), pageId = "two").map { it.id })
        assertEquals("Weather", tiles.first().label)   // provider label when the file has none
    }

    @Test fun bindingForAnotherProviderMeansSetupAndDisabledIsHidden() {
        val c = cfg(doc(aw("w-1", cls = "com.example.weather.W"), aw("w-off", x = 2, extra = ",\"enabled\":false")))
        assertEquals(State.BOUND, HostedWidgets.tiles(c, mapOf("w-1" to bound(7, ".W")), installed).single().state)   // same class, short form
        val other = mapOf("w-1" to bound(7, ".Other"))
        assertEquals(State.NEEDS_SETUP, HostedWidgets.tiles(c, other, installed).single().state)
    }

    @Test fun outOfRangeIsAWarningAndNeverARewrite() {
        val c = cfg(doc(aw("w-small", w = 1, h = 1), aw("w-big", x = 0, w = 4, h = 3).replace("\"y\":1", "\"y\":2"), aw("w-ok", x = 2)))
        val tiles = HostedWidgets.tiles(c, emptyMap(), installed)
        assertEquals(listOf(
            "elements[0]: size 1×1 is below the provider's minimum 2×1. Shown at 1×1 anyway.",
            "elements[1]: size 4×3 is above the provider's maximum 4×2. Shown at 4×3 anyway.",
        ), HostedWidgets.warnings(tiles))
        assertNull(tiles[2].range)
        // Unknown limits (no cell size yet) never warn.
        assertTrue(HostedWidgets.warnings(HostedWidgets.tiles(c, emptyMap(), { _, _, _ -> Facts("W") })).isEmpty())
    }

    @Test fun cellMath() {
        assertEquals(2, HostedWidgets.cells(110f, 80f))
        assertEquals(1, HostedWidgets.cells(80f, 80f))
        assertEquals(1, HostedWidgets.cells(40f, 0f))
        assertEquals(3, HostedWidgets.cellsFit(250f, 80f))
        assertEquals(1, HostedWidgets.cellsFit(10f, 80f))
    }

    @Test fun statusJsonCarriesAppWidgetsAndWarningsAndTheFileIsUntouched() = runBlocking {
        val dir = tmp.newFolder()
        val src = LocalFileSource(dir); val text = doc(aw("w-small", w = 1, h = 1), aw("w-work", x = 2, content = ",\"profile\":\"work\""))
        src.file.writeText(text)
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), 0)
        e.appWidgets = object : AppWidgetStatus {
            override fun states(c: dev.nasawa.configlauncher.model.LauncherConfig) = HostedWidgets.states(HostedWidgets.tiles(c, emptyMap(), installed))
            override fun warnings(c: dev.nasawa.configlauncher.model.LauncherConfig) = HostedWidgets.warnings(HostedWidgets.tiles(c, emptyMap(), installed))
        }
        e.poll(force = true)
        val status = Json.parseToJsonElement(File(dir, ".configlauncher/status.json").readText()).jsonObject
        assertEquals(buildJsonObject { put("w-small", "needsSetup"); put("w-work", "unsupported") }, status["appWidgets"])
        assertTrue(status["warnings"]!!.jsonArray.any { "below the provider's minimum" in it.jsonPrimitive.content })
        assertEquals(text, src.file.readText())
        // No appWidget elements → no map.
        src.file.writeText(doc()); e.poll(force = true)
        assertNull(Json.parseToJsonElement(File(dir, ".configlauncher/status.json").readText()).jsonObject["appWidgets"])
    }
}
