package dev.nasawa.configlauncher.config

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** #298 NW-1: the `appWidget` element (feature level 5), docs/design/native-widgets.md §2.1–§2.2. No host yet. */
class AppWidgetTest {
    private fun doc(vararg els: String) =
        """{"schemaVersion":1,"grid":{"columns":4,"rows":5},"pages":[{"id":"home","items":[{"id":"a","type":"app","package":"p.a","x":0,"y":0}]}],"elements":[${els.joinToString(",")}]}"""
    private fun ok(s: String) = (ConfigCodec.decode(s) as? ParseResult.Ok)?.config ?: error("invalid: ${(ConfigCodec.decode(s) as ParseResult.Invalid).errors}")
    private fun errors(s: String) = (ConfigCodec.decode(s) as ParseResult.Invalid).errors
    private val provider = """{"package":"com.example.weather","class":".WeatherWidget"}"""
    private fun aw(content: String = """{"provider":$provider}""", extra: String = "", requires: String = "5", id: String = "w-1", x: Int = 0) =
        """{"id":"$id","type":"appWidget","requires":$requires,"page":"home","x":$x,"y":1,"w":2,"h":1,"content":$content$extra}"""

    @Test fun levelFiveTypeIsRegistered() {
        assertEquals(5, AppWidget.level)
        assertSame(AppWidget, Elements.types["appWidget"])
    }

    @Test fun minimalAppWidgetIsShownAndReservesItsCells() {
        val c = ok(doc(aw()))
        assertTrue(Elements.warnings(c).isEmpty())
        assertSame(AppWidget, Elements.typeOf(c.elements!!.single()))
        assertEquals(setOf(0 to 1, 1 to 1), Elements.reserved(c, "home"))
    }

    @Test fun profilesAndLabel() {
        ok(doc(aw("""{"provider":$provider,"profile":"personal","label":"Weather"}""", ""","enabled":false""")))
        ok(doc(aw("""{"provider":$provider,"profile":"work"}""")))
        assertEquals(listOf("elements[0].content.profile: must be personal or work"), errors(doc(aw("""{"provider":$provider,"profile":"private"}"""))))
        assertEquals(listOf("elements[0].content.label: longer than 40 characters"), errors(doc(aw("""{"provider":$provider,"label":"${"x".repeat(41)}"}"""))))
        assertEquals(listOf("elements[0].enabled: must be true or false"), errors(doc(aw(extra = ""","enabled":"no""""))))
    }

    @Test fun providerNeedsPackageAndClass() {
        assertEquals(listOf("elements[0].content.provider: missing required \"class\""), errors(doc(aw("""{"provider":{"package":"com.example.weather"}}"""))))
        assertEquals(listOf("elements[0].content: missing required \"provider\""), errors(doc(aw("""{"label":"W"}"""))))
        assertEquals(listOf("elements[0].content.provider.package: \"weather\" is not valid"), errors(doc(aw("""{"provider":{"package":"weather","class":".W"}}"""))))
        assertEquals(listOf("elements[0].content.provider: unknown key \"x-a\""), errors(doc(aw("""{"provider":{"package":"a.b","class":".W","x-a":1}}"""))))
        assertEquals(listOf("elements[0].content: unknown key \"style\""), errors(doc(aw("""{"provider":$provider,"style":{}}"""))))
        assertEquals(listOf("elements[0]: missing required \"content\""), errors(doc(aw().replace(""","content":{"provider":$provider}""", ""))))
    }

    @Test fun styleGetsPragmasSentenceOnly() {
        assertEquals(listOf("elements[0]: appWidget \"w-1\" doesn't support \"style\" yet. Remove it."), errors(doc(aw(extra = ""","style":{"padding":8}"""))))
        assertEquals(listOf("elements[0]: unknown key \"colour\""), errors(doc(aw(extra = ""","colour":"red""""))))
    }

    @Test fun requiresFiveAndTooNewIsSkipped() {
        assertEquals(listOf("elements[0].requires: an appWidget needs feature level 5 or higher"), errors(doc(aw(requires = "4"))))
        assertEquals(listOf("elements[0]: missing required \"requires\""), errors(doc(aw().replace("\"requires\":5,", ""))))
        // Above the level: skipped like any element, not checked strictly, cells kept.
        val c = ok(doc(aw(requires = "7", extra = ""","style":{"padding":8}""")))
        assertEquals(1, Elements.warnings(c).size)
        assertEquals(2, Elements.reserved(c, "home").size)
    }

    @Test fun overlapAndOutOfGridAreB2() {
        assertEquals(listOf("elements[1]: overlaps elements[0] at cell (1, 1)"), errors(doc(aw(), aw(id = "w-2", x = 1))))
        assertEquals(listOf("elements[0]: x 3 + w 2 is outside the grid (4 columns)"), errors(doc(aw(x = 3))))
    }

    @Test fun writeBackIsCanonicalAndStable() {
        val scrambled = """{"x-note":"mine","content":{"label":"W","profile":"work","provider":{"class":".W","package":"com.example.weather"}},"h":1,"w":2,"y":1,"x":0,"page":"home","enabled":true,"requires":5,"type":"appWidget","id":"w-1"}"""
        val out = ConfigCodec.encode(ok(doc(scrambled)))
        val back = Json.parseToJsonElement(out).jsonObject["elements"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("id", "type", "requires", "enabled", "page", "x", "y", "w", "h", "content", "x-note"), back.keys.toList())
        assertEquals(listOf("provider", "profile", "label"), back["content"]!!.jsonObject.keys.toList())
        assertEquals(listOf("package", "class"), back["content"]!!.jsonObject["provider"]!!.jsonObject.keys.toList())
        assertEquals(Json.parseToJsonElement(scrambled), back)   // same values, nothing added (no default profile)
        assertEquals(out, ConfigCodec.encode(ok(out)))
    }
}
