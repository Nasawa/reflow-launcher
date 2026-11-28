package dev.nasawa.configlauncher.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** #361 RW-4: tokens, formatters, missing vs empty, ambient tokens. Clock/zone/locale are injected. */
class BindingsTest {
    private val zone = ZoneId.of("America/Denver")
    private val now = Instant.parse("2026-11-28T16:38:00Z") // 9:38 AM MST
    private fun env(
        data: String? = """{"ctx":79.43,"started_at":"2026-11-28T07:35:00-07:00","state":"idle","updatedAt":"2026-11-28T09:33:00-07:00","blank":"","gone":null}""",
        is24Hour: Boolean = false,
        battery: Int? = 80,
        charging: Boolean = false,
        full: Boolean = false,
        nextAlarm: Instant? = Instant.parse("2026-11-28T18:00:00Z"),
        locale: Locale = Locale.US,
        instant: Instant = now,
        fetch: Map<String, JsonObject> = emptyMap(),
    ) = Bindings.Env(
        now = instant,
        zone = zone,
        locale = locale,
        is24Hour = is24Hour,
        data = data?.let { Json.parseToJsonElement(it).jsonObject },
        fetch = fetch,
        batteryPercent = battery,
        charging = charging,
        full = full,
        nextAlarm = nextAlarm,
    )

    private fun text(s: String, e: Bindings.Env = env()) = Bindings.expand(s, e).text
    private fun value(s: String, e: Bindings.Env = env()) = Bindings.expand(s, e)

    @Test fun checkAcceptsDataAndFetchAndRejectsUnknown() {
        assertNull(Bindings.check("{data:ctx}", "elements[0].text"))
        assertNull(Bindings.check("{fetch:usage.state}", "elements[0].text"))
        assertEquals(
            "elements[0].text: unknown token {foo}, so this card wasn't loaded. Use {data:…}, {fetch:…}, {time}, {date}, {battery}, {batteryFraction}, {batteryState}, {nextAlarm} or {relative}.",
            Bindings.check("{foo}", "elements[0].text"),
        )
        assertEquals(
            "elements[0].text: {data:ctx|round|duration|time} has 3 formatters, so this card wasn't loaded. A token can have at most 2.",
            Bindings.check("{data:ctx|round|duration|time}", "elements[0].text"),
        )
    }

    @Test fun roundDefaultsToZeroAndKeepsOneDigit() {
        assertEquals("79", text("{data:ctx|round}"))
        assertEquals("79.4", text("{data:ctx|round:1}"))
        assertEquals("-3", text("{data:neg|round}", env("""{"neg":-2.5}""")))
        assertEquals(Bindings.MISSING, text("{data:missing|round}"))
        assertEquals(Bindings.MISSING, text("{data:state|round}")) // "idle" isn't a number
    }

    @Test fun durationAndSince() {
        assertEquals("9h 3m", Bindings.formatDuration(9 * 3600 + 3 * 60))
        assertEquals("3m 5s", Bindings.formatDuration(185))
        assertEquals("0s", Bindings.formatDuration(0))
        assertEquals("-1h", Bindings.formatDuration(-3600))
        // started_at 07:35 local, now 09:38 local → 2h 3m
        assertEquals("2h 3m", text("{data:started_at|since}"))
        assertEquals(Bindings.MISSING, text("{data:state|since}"))
    }

    @Test fun twoFormattersLeftToRight() {
        assertEquals("79", text("{data:ctx|round:1|round}"))
        assertEquals("1m 19s", text("{data:ctx|round|duration}"))
    }

    @Test fun missingPathVsEmptyStringVsDefault() {
        val missing = value("{data:missing}")
        assertEquals(Bindings.MISSING, missing.text)
        assertFalse(missing.empty)
        val def = value("{data:missing|default:offline}")
        assertEquals("offline", def.text)
        assertFalse(def.empty)
        val empty = value("{data:blank}")
        assertEquals("", empty.text)
        assertTrue(empty.empty)
        assertNull(empty.drawn())
        // default does not apply to a present empty string
        val emptyDef = value("{data:blank|default:offline}")
        assertTrue(emptyDef.empty)
        assertNull(emptyDef.drawn())
    }

    @Test fun batteryTokens() {
        assertEquals("80", text("{battery}"))
        assertEquals("0.8", text("{batteryFraction}"))
        assertEquals("", value("{batteryState}").text)
        assertTrue(value("{batteryState}").empty)
        assertEquals("Charging", text("{batteryState}", env(charging = true)))
        assertEquals("Full", text("{batteryState}", env(battery = 100, charging = true)))
        assertEquals("Low", text("{batteryState}", env(battery = 15)))
        assertEquals(Bindings.MISSING, text("{battery}", env(battery = null)))
        assertTrue(value("{nextAlarm}", env(nextAlarm = null)).empty)
    }

    @Test fun relativeJustNowAndMinutes() {
        assertEquals("5 min ago", text("{relative}", env()))
        assertEquals("just now", text("{relative}", env(data = """{"updatedAt":"2026-11-28T09:37:30-07:00"}""")))
        assertEquals("2 h ago", text("{relative}", env(data = """{"updatedAt":"2026-11-28T07:38:00-07:00"}""")))
    }

    @Test fun time12And24HourAndInjectedClock() {
        assertEquals("9:38 AM", text("{time}", env(is24Hour = false)))
        assertEquals("9:38", text("{time}", env(is24Hour = true)))
        assertEquals("9:38 AM", text("{time:h:mm a}"))
        assertEquals("09:38", text("{time:HH:mm}", env(is24Hour = true)))
    }

    @Test fun timeFormatterOnATimestampAndDst() {
        // 2026-03-08 02:00 MST → 03:00 MDT in America/Denver. 10:00Z is 04:00 MDT.
        val spring = Instant.parse("2026-03-08T10:00:00Z")
        assertEquals("4:00 AM", text("{data:t|time:h:mm a}", env(data = """{"t":"2026-03-08T10:00:00Z"}""", instant = spring)))
        // the day before, 10:00Z is 03:00 MST
        assertEquals("3:00 AM", text("{data:t|time:h:mm a}", env(data = """{"t":"2026-03-07T10:00:00Z"}""")))
    }

    @Test fun fetchIsParseOnlyUnlessSnapshotProvided() {
        assertNull(Bindings.check("{fetch:usage.state|round:1}", "elements[0].text"))
        assertEquals(Bindings.MISSING, text("{fetch:usage.state}"))
        val snap = Json.parseToJsonElement("""{"state":79.4}""").jsonObject
        assertEquals("79.4", text("{fetch:usage.state}", env(fetch = mapOf("usage" to snap))))
    }

    @Test fun pictureSrcRejectsFormattersAndAmbientTokens() {
        assertNull(Bindings.checkFileToken("data:state", "elements[0].layers[0].src"))
        assertEquals(
            "elements[0].layers[0].src: {data:state|round} has a formatter, so this card wasn't loaded. A picture file name can only use {data:…} or {fetch:…}.",
            Bindings.checkFileToken("data:state|round", "elements[0].layers[0].src"),
        )
        assertEquals(
            "elements[0].layers[0].src: \"{time}\" isn't a file name token, so this card wasn't loaded. Use {data:…} or {fetch:…} in a picture src.",
            Bindings.checkFileToken("time", "elements[0].layers[0].src"),
        )
    }

    @Test fun asOfPath() {
        assertNull(Bindings.checkAsOf("updatedAt", "elements[0].data.asOf"))
        assertNull(Bindings.checkAsOf("data.last_updated", "elements[0].data.asOf"))
        assertEquals(
            "elements[0].data.asOf: \"bad path!\" isn't a data path, so this card wasn't loaded. Use a path of dots and [index] like updatedAt.",
            Bindings.checkAsOf("bad path!", "elements[0].data.asOf"),
        )
        val at = Bindings.asOf(env().data, "updatedAt", zone)
        assertEquals(Instant.parse("2026-11-28T16:33:00Z"), at)
    }

    @Test fun widgetDataTextStillMapsEmptyToDash() {
        val root = env().data
        assertEquals("—", dev.nasawa.configlauncher.widgetdata.WidgetData.text(root, "blank"))
        assertEquals("—", dev.nasawa.configlauncher.widgetdata.WidgetData.text(root, "missing"))
        assertEquals("idle", dev.nasawa.configlauncher.widgetdata.WidgetData.text(root, "state"))
        assertTrue(Bindings.scalar(root, "blank") is Bindings.Scalar.Empty)
        assertNull(Bindings.scalar(root, "missing"))
    }
}
