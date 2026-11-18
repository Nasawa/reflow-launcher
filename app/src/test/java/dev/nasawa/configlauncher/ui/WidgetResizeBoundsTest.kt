package dev.nasawa.configlauncher.ui

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.hosted.HostedWidgets
import dev.nasawa.configlauncher.ui.ResizeMath.Handle
import org.junit.Assert.*
import org.junit.Test

/**
 * dev1 (S25 Ultra): a 4×2 weather widget resized to 4×1 lost its top/bottom handles for good. Handles come from the
 * provider's resizeMode and min/max span only, and the provider's default size is always within that span.
 */
class WidgetResizeBoundsTest {
    private fun cfg(w: Int, h: Int) = (ConfigCodec.decode("""{"schemaVersion":1,"grid":{"columns":4,"rows":6},"pages":[{"id":"home","items":[]}],
        "elements":[{"id":"w-a","type":"appWidget","requires":5,"page":"home","x":0,"y":0,"w":$w,"h":$h,
        "content":{"provider":{"package":"com.sec.android.daemonapp","class":".Weather"}}}]}""") as ParseResult.Ok).config

    // Like the S25 Ultra report: a 4×2 default (targetCell), vertical resize down to 1 row, and a maxResizeHeight
    // that floors to 1 row of our (taller) cells.
    private val weather = HostedWidgets.ProviderSize(resizeW = true, resizeH = true, minWDp = 250f, minHDp = 110f,
        minResizeWDp = 180f, minResizeHDp = 50f, maxResizeWDp = 400f, maxResizeHDp = 220f, targetW = 4, targetH = 2)
    private val facts = HostedWidgets.facts("Weather", weather, cw = 95f, ch = 130f)

    @Test fun theDefaultSizeIsAlwaysWithinTheProvidersRange() {
        assertEquals(1, facts.minH)
        assertEquals("maxResizeHeight floors to 1 row, but the 2-row default is allowed", 2, facts.maxH)
        assertNull("no 'size may be wrong' cue at the provider's own default size", HostedWidgets.range(4, 2, facts))
    }

    @Test fun aWidgetShrunkToOneRowKeepsItsVerticalHandles() {
        val tall = ElementInfo.sizeBounds(cfg(4, 2), "w-a", facts)!!
        val short = ElementInfo.sizeBounds(cfg(4, 1), "w-a", facts)!!
        assertEquals("bounds don't depend on the current size", tall, short)
        val lowMax = HostedWidgets.Facts("W", minW = 1, minH = 1, maxW = 4, maxH = 1)
        assertEquals("not even when the current size is over the provider's max", ElementInfo.sizeBounds(cfg(4, 2), "w-a", lowMax),
            ElementInfo.sizeBounds(cfg(4, 1), "w-a", lowMax))
        val handles = ResizeMath.handles(short)
        assertTrue("top/bottom handles at 4×1: $handles", Handle.TOP in handles && Handle.BOTTOM in handles)
        // And the bottom handle can take it back to 2 rows.
        val back = ResizeMath.resize(ResizeMath.Rect(0, 0, 4, 1), Handle.BOTTOM, 0, 1, short, 4, 6) { _, _, _, _ -> true }
        assertEquals(ResizeMath.Rect(0, 0, 4, 2), back.rect)
    }

    @Test fun onlyAnAxisTheProviderCantResizeIsPinned() {
        val fixedH = HostedWidgets.facts("Clock", weather.copy(resizeH = false), cw = 95f, ch = 130f)
        val b = ElementInfo.sizeBounds(cfg(4, 2), "w-a", fixedH)!!
        assertEquals(listOf(Handle.START, Handle.END), ResizeMath.handles(b))
        // A provider minimum above the current size doesn't hide handles either (the steppers still start there).
        val bigMin = HostedWidgets.Facts("W", minW = 2, minH = 2, maxW = 4, maxH = 4)
        assertTrue(Handle.BOTTOM in ResizeMath.handles(ElementInfo.sizeBounds(cfg(4, 1), "w-a", bigMin)!!))
        assertEquals(ElementInfo.Limits(2, 4, 1, 4), ElementInfo.limits(cfg(4, 1), "w-a", bigMin))
    }
}
