package dev.nasawa.configlauncher.config

/**
 * #361 (RW-4): evaluate a card threshold list. Load-time shape is validated by [Card].
 * Inclusive lower bound: with `from: 75`, 74 stays on the previous tone, 75 and 76 take this one.
 * The same boundary colours ticks and segments when `by` is `"position"`.
 */
object Thresholds {
    const val DEFAULT = "accent"

    data class Entry(val from: Double, val tone: String)

    /** Highest entry whose [Entry.from] is <= [percent] (0–100). An empty list is [DEFAULT]. */
    fun tone(percent: Double, entries: List<Entry>, default: String = DEFAULT): String {
        var t = default
        for (e in entries) {
            if (percent >= e.from) t = e.tone else break
        }
        return t
    }

    fun percent(value: Double, max: Double): Double =
        if (max == 0.0) 0.0 else (value / max) * 100.0

    /**
     * Tick [index] of [count] (0-based) on the 0–100 scale: `index * 100 / count`.
     * With 100 ticks, tick 74 is 74, 75 is 75, and 76 is 76, so `from: 75` matches §4.2.
     */
    fun tickPercent(index: Int, count: Int): Double =
        if (count <= 0) 0.0 else index * 100.0 / count

    fun tickTone(index: Int, count: Int, entries: List<Entry>, default: String = DEFAULT): String =
        tone(tickPercent(index, count), entries, default)

    /** Segment [index] of [segments] (0-based) uses the far edge of that segment on the 0–100 scale. */
    fun segmentPercent(index: Int, segments: Int): Double =
        if (segments <= 0) 0.0 else (index + 1) * 100.0 / segments

    fun segmentTone(index: Int, segments: Int, entries: List<Entry>, default: String = DEFAULT): String =
        tone(segmentPercent(index, segments), entries, default)
}
