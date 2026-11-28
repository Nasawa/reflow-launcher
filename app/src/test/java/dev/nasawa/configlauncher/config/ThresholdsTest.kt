package dev.nasawa.configlauncher.config

import org.junit.Assert.assertEquals
import org.junit.Test

/** #361 RW-4: inclusive 74/75/76 boundary, same for ticks and segments by position. */
class ThresholdsTest {
    private val entries = listOf(
        Thresholds.Entry(0.0, "accent"),
        Thresholds.Entry(75.0, "warn"),
        Thresholds.Entry(90.0, "error"),
    )

    @Test fun valueBoundaryInclusive() {
        assertEquals("accent", Thresholds.tone(74.0, entries))
        assertEquals("warn", Thresholds.tone(75.0, entries))
        assertEquals("warn", Thresholds.tone(76.0, entries))
        assertEquals("error", Thresholds.tone(90.0, entries))
        assertEquals("error", Thresholds.tone(100.0, entries))
        assertEquals("accent", Thresholds.tone(Thresholds.percent(74.0, 100.0), entries))
        assertEquals("warn", Thresholds.tone(Thresholds.percent(75.0, 100.0), entries))
        assertEquals("warn", Thresholds.tone(Thresholds.percent(76.0, 100.0), entries))
    }

    @Test fun emptyListIsAccent() {
        assertEquals("accent", Thresholds.tone(80.0, emptyList()))
    }

    @Test fun ticksByPositionUseTheSameBoundary() {
        // 100 ticks: tick i sits at i percent, so 74/75/76 match the value scale.
        assertEquals("accent", Thresholds.tickTone(74, 100, entries))
        assertEquals("warn", Thresholds.tickTone(75, 100, entries))
        assertEquals("warn", Thresholds.tickTone(76, 100, entries))
    }

    @Test fun segmentsByPositionUseTheFarEdge() {
        // 100 segments: segment 74 ends at 75, so it takes warn; 73 ends at 74 and stays accent.
        assertEquals("accent", Thresholds.segmentTone(73, 100, entries))
        assertEquals("warn", Thresholds.segmentTone(74, 100, entries))
        assertEquals("warn", Thresholds.segmentTone(75, 100, entries))
    }
}
