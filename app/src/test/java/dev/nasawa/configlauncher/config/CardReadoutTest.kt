package dev.nasawa.configlauncher.config

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** #360 RW-1: M1 readout keys at FL_CARDS_READOUT. Drawing, loading and evaluation are later issues. */
class CardReadoutTest {
    private fun doc(card: String) =
        """{"schemaVersion":1,"grid":{"columns":4,"rows":12},"pages":[{"id":"home","items":[{"id":"a","type":"app","package":"p.a","x":0,"y":0}]}],"elements":[$card]}"""
    private fun ok(s: String) = (ConfigCodec.decode(s) as? ParseResult.Ok)?.config ?: error("invalid: ${(ConfigCodec.decode(s) as ParseResult.Invalid).errors}")
    private fun errors(s: String) = (ConfigCodec.decode(s) as ParseResult.Invalid).errors
    private fun warnings(s: String) = Elements.warnings(ok(s))
    private fun card(extra: String = "", layouts: String = """{"1x1":[{"type":"text","text":"hi"}]}""", requires: Int = 6) =
        """{"id":"w-1","type":"card","requires":$requires,"page":"home","x":0,"y":1,"w":4,"h":3,"layouts":$layouts$extra}"""

    private val status = """
        {"id":"w-status","type":"card","requires":6,"page":"home","x":0,"y":2,"w":4,"h":5,
         "minSpan":{"w":2,"h":2},"title":"Example Co","author":"agent","source":"https://status.example.com",
         "capabilities":[{"kind":"data.file"},{"kind":"ui.reload"},{"kind":"ui.openUrl"}],
         "data":{"staleAfter":"PT30M"},
         "layouts":{"2x2":[{"type":"metric","label":"Monitors","value":"{data:monitors.summary}","status":"{data:monitors.status}"}],
           "4x5":[{"type":"tile","tap":{"kind":"ui.openUrl","url":"{data:blog.url}"},"children":[
               {"type":"text","role":"caption","text":"Latest on status.example.com"},
               {"type":"text","role":"title","text":"{data:blog.title}","maxLines":2},
               {"type":"text","role":"body","text":"{data:blog.excerpt}","maxLines":1}]},
             {"type":"row","children":[
               {"type":"metric","label":"Site A","value":"{data:site_a.value}","status":"{data:site_a.status}","weight":1},
               {"type":"metric","label":"Site B","value":"{data:site_b.value}","status":"{data:site_b.status}","weight":1,"priority":4},
               {"type":"metric","label":"Monitors","value":"{data:monitors.summary}","status":"{data:monitors.status}","weight":1}]},
             {"type":"tile","children":[
               {"type":"text","role":"caption","text":"Unhealthy monitors"},
               {"type":"row","children":[
                 {"type":"text","text":"{data:unhealthy.text}","status":"{data:unhealthy.status}","weight":1},
                 {"type":"text","text":"{data:monitors.summary}","align":"end"}]}]}]}}
    """.trimIndent()

    private val usage = """
        {"id":"w-usage","type":"card","requires":6,"page":"home","x":0,"y":8,"w":4,"h":3,
         "title":"Agent usage","author":"agent","header":false,"surface":{"color":"#0F6E6E"},
         "layers":[{"kind":"picture","src":"sticker-{data:state}.webp","default":"sticker-idle.webp",
           "fit":"fixed","width":0.4,"align":"bottom","offset":{"x":0,"y":0.15},"overflow":true,"opacity":0.8,"scrim":"none"},
           {"kind":"content"}],
         "summary":"Agent usage, {data:state}. Context {data:ctx|round} percent",
         "capabilities":[{"kind":"data.file"},{"kind":"ui.reload"}],"data":{"staleAfter":"PT15M"},
         "layouts":{"4x3":[
           {"type":"text","role":"caption","font":"mono","align":"end","text":"up {data:started_at|since}"},
           {"type":"progress","label":"ctx","value":"{data:ctx}","max":100,"style":"ticks","by":"position",
             "thresholds":[{"from":0,"tone":"accent"},{"from":75,"tone":"warn"}]},
           {"type":"progress","label":"5h","value":"{data:five_hour}","max":100,"style":"ticks","by":"position",
             "thresholds":[{"from":0,"tone":"accent"},{"from":75,"tone":"warn"}]},
           {"type":"progress","label":"7d","value":"{data:seven_day}","max":100,"style":"ticks","by":"position",
             "thresholds":[{"from":0,"tone":"accent"},{"from":75,"tone":"warn"}]},
           {"type":"text","role":"caption","font":"mono","align":"end","text":"status: {data:state|default:unknown}"}]}}
    """.trimIndent()

    @Test fun section81ExampleValidates() {
        val c = ok(doc(status))
        assertTrue(Elements.warnings(c).isEmpty())
        assertEquals(1, Elements.shown(c).size)
    }

    @Test fun section82M1ExampleValidates() {
        val c = ok(doc(usage))
        assertTrue(Elements.warnings(c).isEmpty())
        assertTrue(Card.honorsHeaderFalse(c.elements!![0]))
        assertFalse(Card.usesDefaultSurface(c.elements!![0]))
    }

    @Test fun newKeysNeedReadoutLevel() {
        val msg = "elements[0].requires: a card that uses readout keys needs feature level 6 or higher"
        assertTrue(errors(doc(card(extra = ""","header":false""", requires = 4))).contains(msg))
        assertTrue(errors(doc(card(extra = ""","surface":"none"""", requires = 4))).contains(msg))
        assertTrue(errors(doc(card(layouts = """{"1x1":[{"type":"text","text":"hi","align":"end"}]}""", requires = 4))).contains(msg))
        ok(doc(card(requires = 4))) // old keys only stay valid
    }

    @Test fun featureLevel5ApkSkipsReadoutCardAndKeepsCells() {
        val c = ok(doc(usage))
        assertNull(Elements.skip(c.elements!![0]))
        val skip = Elements.skip(c.elements!![0], featureLevel = 5)
        assertEquals(Elements.Skip.TooNew(6), skip)
        val ws = Elements.warnings(c, featureLevel = 5)
        assertEquals(listOf("elements[0] needs feature level 6, but this Reflow Launcher supports up to 5, so it was skipped. It stays in the file. Update the app to show it."), ws)
        assertEquals(setOf(0 to 8, 1 to 8, 2 to 8, 3 to 8, 0 to 9, 1 to 9, 2 to 9, 3 to 9, 0 to 10, 1 to 10, 2 to 10, 3 to 10), Elements.reserved(c, "home"))
        assertTrue(Elements.skip(c.elements!![0], featureLevel = 5) is Elements.Skip.TooNew)
        assertTrue(Elements.shown(c, featureLevel = 5).isEmpty())
        assertEquals(12, Elements.reserved(c, "home").size)
        // The "Update" tile is ConsentUi.Reason.Update when skip() is TooNew (emulator check on a FEATURE_LEVEL 5 APK).
    }

    @Test fun headerFalseWithOpenUrlWarnsAndStaysValid() {
        val s = doc(card(extra = ""","header":false,"capabilities":[{"kind":"ui.openUrl"}]"""))
        assertEquals(listOf("""elements[0].header: false is ignored because this card opens links or needs your OK, so the header is shown."""), warnings(s))
        assertFalse(Card.honorsHeaderFalse(ok(s).elements!![0]))
    }

    @Test fun headerFalseWithSurfaceNoneWarnsAndKeepsSurface() {
        val s = doc(card(extra = ""","header":false,"surface":"none""""))
        assertEquals(listOf("""elements[0].surface: "none" can't be used with header: false, so the default card surface was used."""), warnings(s))
        assertTrue(Card.honorsHeaderFalse(ok(s).elements!![0]))
        assertTrue(Card.usesDefaultSurface(ok(s).elements!![0]))
    }

    @Test fun defaultScrimWarningUsesRealIndex() {
        val s = doc(card(extra = ""","layers":[{"kind":"picture","src":"photo.webp"},{"kind":"content"}]"""))
        assertEquals(listOf("""elements[0].layers[0]: a picture is right under content, so a default scrim was added to keep text readable. Set this layer's scrim to choose it, or "none" to turn it off."""), warnings(s))
        val none = doc(card(extra = ""","layers":[{"kind":"picture","src":"photo.webp","scrim":"none"},{"kind":"content"}]"""))
        assertTrue(warnings(none).isEmpty())
        val custom = doc(card(extra = ""","layers":[{"kind":"picture","src":"photo.webp","scrim":{"color":"#000000","opacity":0.4}},{"kind":"content"}]"""))
        assertTrue(warnings(custom).isEmpty())
        val notUnder = doc(card(extra = ""","layers":[{"kind":"picture","src":"a.webp"},{"kind":"picture","src":"b.webp","scrim":"auto"},{"kind":"content"}]"""))
        // only the picture immediately under content warns (index 1); index 0 is not directly under content
        assertEquals(listOf("""elements[0].layers[1]: a picture is right under content, so a default scrim was added to keep text readable. Set this layer's scrim to choose it, or "none" to turn it off."""), warnings(notUnder))
    }

    @Test fun extraLayersAndAboveContentOpacity() {
        val layers = (1..5).joinToString(",") { """{"kind":"picture","src":"p$it.webp"}""" } + """,{"kind":"content"}"""
        val s = doc(card(extra = ""","layers":[$layers]"""))
        val w = warnings(s)
        assertTrue(w[0], w[0] == "elements[0].layers: has 6 layers, so the last 2 were skipped. A card can have at most 4.")
        // first 4 are pictures (content at index 5 is skipped), implicit content at front of the kept 4? kept = first 4 pictures, no content listed in kept → implicit at top, all 4 are under implicit content. Last kept picture (index 3) is directly under implicit content.
        assertTrue(w.any { it == """elements[0].layers[3]: a picture is right under content, so a default scrim was added to keep text readable. Set this layer's scrim to choose it, or "none" to turn it off.""" })
        val above = doc(card(extra = ""","layers":[{"kind":"content"},{"kind":"picture","src":"over.webp","opacity":0.9}]"""))
        assertEquals(listOf("elements[0].layers[1].opacity is above 0.6 on a layer above content, so it's shown at 0.6 to keep text readable. Use 0.6 or less, or move the layer under the content."), warnings(above))
        val aboveOk = doc(card(extra = ""","layers":[{"kind":"content"},{"kind":"picture","src":"over.webp","opacity":0.5}]"""))
        assertTrue(warnings(aboveOk).isEmpty())
    }

    @Test fun secondContentAndRemoteSrcAreErrors() {
        assertTrue(errors(doc(card(extra = ""","layers":[{"kind":"content"},{"kind":"content"}]"""))).contains("elements[0].layers[1]: a card can have only one content layer"))
        assertTrue(errors(doc(card(extra = ""","layers":[{"kind":"picture","src":"https://example.com/x.webp"}]"""))).contains("elements[0].layers[0].src must be a file name in the card's pictures folder, not a web address."))
        assertTrue(Card.legalTemplate("sticker-{data:state}.webp"))
        ok(doc(card(extra = ""","layers":[{"kind":"picture","src":"sticker-{data:state}.webp","default":"sticker-idle.webp"},{"kind":"content"}]""")))
    }

    @Test fun widthOffsetAndTickCountWarnings() {
        val width = doc(card(extra = ""","layers":[{"kind":"picture","src":"a.webp","fit":"cover","width":0.4}]"""))
        assertTrue(warnings(width).any { it == """elements[0].layers[0].width is ignored unless fit is "fixed", so the picture is sized with "cover". Set fit to "fixed" to use width, or drop width.""" })
        val contain = doc(card(extra = ""","layers":[{"kind":"picture","src":"a.webp","fit":"contain","width":0.4}]"""))
        assertTrue(warnings(contain).any { it == """elements[0].layers[0].width is ignored unless fit is "fixed", so the picture is sized with "contain". Set fit to "fixed" to use width, or drop width.""" })
        val wide = doc(card(extra = ""","layers":[{"kind":"picture","src":"a.webp","fit":"fixed","width":2}]"""))
        assertTrue(warnings(wide).any { it == "elements[0].layers[0].width: 2 is outside 0.1 to 1.5, so it was set to 1.5. Use a value in that range." })
        val narrow = doc(card(extra = ""","layers":[{"kind":"picture","src":"a.webp","fit":"fixed","width":0.05}]"""))
        assertTrue(warnings(narrow).any { it == "elements[0].layers[0].width: 0.05 is outside 0.1 to 1.5, so it was set to 0.1. Use a value in that range." })
        val off = doc(card(extra = ""","layers":[{"kind":"picture","src":"a.webp","offset":{"x":-0.4,"y":0.3}}]"""))
        val ow = warnings(off)
        assertTrue(ow.contains("elements[0].layers[0].offset.x: -0.4 is outside -0.25 to 0.25, so it was set to -0.25. Use a value in that range."))
        assertTrue(ow.contains("elements[0].layers[0].offset.y: 0.3 is outside -0.25 to 0.25, so it was set to 0.25. Use a value in that range."))
        val high = doc(card(layouts = """{"1x1":[{"type":"progress","value":0.5,"style":"ticks","count":200}]}"""))
        assertEquals(listOf("elements[0].layouts.1x1[0].count: 200 is outside 10 to 120, so it was set to 120. A ticks bar can have at most 120 ticks."), warnings(high))
        val low = doc(card(layouts = """{"1x1":[{"type":"progress","value":0.5,"style":"ticks","count":5}]}"""))
        assertEquals(listOf("elements[0].layouts.1x1[0].count: 5 is outside 10 to 120, so it was set to 10. A ticks bar needs at least 10 ticks."), warnings(low))
        val segs = doc(card(layouts = """{"1x1":[{"type":"progress","value":0.5,"style":"ticks","segments":8}]}"""))
        assertEquals(listOf("""elements[0].layouts.1x1[0].segments is ignored when style is "ticks", so the bar uses ticks. Drop segments, or drop style to use a segmented bar."""), warnings(segs))
        val countIgnored = doc(card(layouts = """{"1x1":[{"type":"progress","value":0.5,"count":40}]}"""))
        assertEquals(listOf("""elements[0].layouts.1x1[0].count is ignored unless style is "ticks", so the tick count isn't used. Set style to "ticks" to use count, or drop count."""), warnings(countIgnored))
    }

    @Test fun missingFileWarningsAreExact() {
        val o = Json.parseToJsonElement(card(extra = ""","layers":[{"kind":"picture","src":"sticker.webp","default":"sticker-idle.webp","scrim":"none"},{"kind":"content"}]""")).jsonObject
        assertEquals(
            listOf(
                """elements[0].layers[0].src: "sticker.webp" isn't in this card's pictures folder, so the layer was skipped.""",
                """elements[0].layers[0].default: "sticker-idle.webp" isn't in this card's pictures folder, so the default picture was skipped.""",
            ),
            Card.loadWarnings(o, "elements[0]", files = emptySet()),
        )
        assertTrue(Card.loadWarnings(o, "elements[0]", files = setOf("sticker.webp", "sticker-idle.webp")).none { "isn't in this card's pictures folder" in it })
        val tmpl = Json.parseToJsonElement(card(extra = ""","layers":[{"kind":"picture","src":"sticker-{data:state}.webp","scrim":"none"},{"kind":"content"}]""")).jsonObject
        assertTrue(Card.loadWarnings(tmpl, "elements[0]", files = emptySet()).none { "isn't in this card's pictures folder" in it })
        assertEquals("""elements[0].layers[0]: "sticker.webp" can't be shown (too big, or not a PNG, JPEG or WebP), so the layer was skipped.""", Card.imageBadWarning("elements[0].layers[0]", "sticker.webp"))
    }

    @Test fun dataChosenLinkValidAndLeftoverSecretIsAnError() {
        ok(doc(card(extra = ""","capabilities":[{"kind":"ui.openUrl"}]""", layouts = """{"1x1":[{"type":"text","text":"go","tap":{"kind":"ui.openUrl","url":"{data:blog.url}"}}]}""")))
        val err = errors(doc(card(extra = ""","capabilities":[{"kind":"ui.openUrl"}]""", layouts = """{"1x1":[{"type":"text","text":"go","tap":{"kind":"ui.openUrl","url":"https://example.com/${'$'}{secret:ha}"}}]}""")))
        assertTrue(err.toString(), err.contains("elements[0].layouts.1x1[0].tap.url: a link can't use a secret."))
        val brace = errors(doc(card(extra = ""","capabilities":[{"kind":"ui.openUrl"}]""", layouts = """{"1x1":[{"type":"text","text":"go","tap":{"kind":"ui.openUrl","url":"https://example.com/{secret:ha}"}}]}""")))
        assertTrue(brace.contains("elements[0].layouts.1x1[0].tap.url: a link can't use a secret."))
    }

    @Test fun from75ThresholdsAreAccepted() {
        ok(doc(card(layouts = """{"1x1":[{"type":"progress","value":79,"max":100,"thresholds":[{"from":0,"tone":"accent"},{"from":75,"tone":"warn"}]}]}""")))
        ok(doc(card(layouts = """{"1x1":[{"type":"metric","value":"{data:n}","thresholds":[{"from":0,"tone":"ok"},{"from":75,"tone":"#FFC400"}]}]}""")))
        assertTrue(errors(doc(card(layouts = """{"1x1":[{"type":"progress","value":1,"thresholds":[{"from":75,"tone":"warn"}]}]}"""))).contains("elements[0].layouts.1x1[0].thresholds[0].from: must be 0"))
    }

    @Test fun emptyNotDrawnRule() {
        assertTrue(Card.emptyNotDrawn(""))
        assertFalse(Card.emptyNotDrawn("—"))
        assertFalse(Card.emptyNotDrawn("up"))
    }

    @Test fun displayAlignFontStatusColorAndButtonIcon() {
        ok(doc(card(layouts = """{"1x1":[{"type":"text","role":"display","align":"center","font":"mono","status":"ok","text":"Hi","color":"onSurface"}]}""")))
        ok(doc(card(layouts = """{"1x1":[{"type":"button","label":"Go","icon":"refresh","tap":{"kind":"ui.reload"}}]}""", extra = ""","capabilities":[{"kind":"ui.reload"}]""")))
        ok(doc(card(layouts = """{"1x1":[{"type":"progress","value":79,"max":100,"segments":10}]}""")))
    }
}
