package dev.nasawa.configlauncher.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nasawa.configlauncher.config.Elements

/**
 * #304 NW-8 (ux.md §16.4, native-widgets.md D4): drag-resize handles on the selected widget or card in edit mode.
 * Pure math in [ResizeMath] (JVM-tested); [ResizeFrame] draws the frame, the handles and the live outline.
 */
object ResizeMath {
    /** A handle: which logical edges it moves (start = `x`, end = `x + w`, top = `y`, bottom = `y + h`). */
    enum class Handle(val start: Boolean = false, val top: Boolean = false, val end: Boolean = false, val bottom: Boolean = false) {
        START(start = true), END(end = true), TOP(top = true), BOTTOM(bottom = true),
        TOP_START(start = true, top = true), TOP_END(top = true, end = true),
        BOTTOM_START(bottom = true, start = true), BOTTOM_END(bottom = true, end = true);
        val horizontal get() = start || end
        val vertical get() = top || bottom
    }

    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    /** Where a drag ended up: [rect], and the edges that stopped at a taken cell (drawn red). */
    data class Result(val rect: Rect, val blockedH: Boolean = false, val blockedV: Boolean = false)

    /**
     * Handles offered for [b] (§16.4): edge handles for each axis that can change size, corners when both can.
     * An axis whose min equals its max (a provider that doesn't resize it, or a fixed size) has none.
     */
    fun handles(b: ElementInfo.Limits): List<Handle> {
        val h = b.minW < b.maxW; val v = b.minH < b.maxH
        return buildList {
            if (h) { add(Handle.START); add(Handle.END) }
            if (v) { add(Handle.TOP); add(Handle.BOTTOM) }
            if (h && v) addAll(listOf(Handle.TOP_START, Handle.TOP_END, Handle.BOTTOM_START, Handle.BOTTOM_END))
        }
    }

    /** Whole cells moved by a drag of ([dxPx], [dyPx]) px; logical (RTL: a drag to the right is toward the start). */
    fun cells(dxPx: Float, dyPx: Float, cellWPx: Float, cellHPx: Float, rtl: Boolean): Pair<Int, Int> =
        ElementActionList.cellDelta(dxPx, dyPx, cellWPx, cellHPx, rtl)

    /**
     * [p] resized by [handle] moved [dCols] × [dRows] whole cells. Clamped to [b] (min/max) and the grid
     * ([columns] × [rows]); growth goes one cell at a time and stops before the first step whose rectangle isn't
     * [free] (that axis is then `blocked`). The opposite edge never moves. Shrinking always fits.
     */
    fun resize(p: Rect, handle: Handle, dCols: Int, dRows: Int, b: ElementInfo.Limits, columns: Int, rows: Int,
               free: (x: Int, y: Int, w: Int, h: Int) -> Boolean): Result {
        var r = p
        var blockedH = false; var blockedV = false
        if (handle.horizontal) {
            val room = if (handle.end) columns - p.x else p.x + p.w
            val target = (if (handle.end) p.w + dCols else p.w - dCols)
                .coerceIn(minOf(b.minW, p.w), maxOf(minOf(b.maxW, room), p.w))
            fun at(w: Int) = Rect(if (handle.end) p.x else p.x + p.w - w, p.y, w, p.h)
            r = if (target <= p.w) at(target) else {
                var w = p.w
                while (w < target) { val n = at(w + 1); if (!free(n.x, n.y, n.w, n.h)) { blockedH = true; break }; w++ }
                at(w)
            }
        }
        if (handle.vertical) {
            val base = r
            val room = if (handle.bottom) rows - base.y else base.y + base.h
            val target = (if (handle.bottom) base.h + dRows else base.h - dRows)
                .coerceIn(minOf(b.minH, base.h), maxOf(minOf(b.maxH, room), base.h))
            fun at(h: Int) = Rect(base.x, if (handle.bottom) base.y else base.y + base.h - h, base.w, h)
            r = if (target <= base.h) at(target) else {
                var h = base.h
                while (h < target) { val n = at(h + 1); if (!free(n.x, n.y, n.w, n.h)) { blockedV = true; break }; h++ }
                at(h)
            }
        }
        return Result(r, blockedH, blockedV)
    }

    /**
     * #304 acceptance: a 48 dp hit box centred on the handle point ([cx], [cy]) is kept fully inside the page area
     * ([areaW] × [areaH]), so a handle on the screen edge or right above the dock is still a whole 48 dp target.
     * Returns the box's top-left.
     */
    fun hitBox(cx: Float, cy: Float, areaW: Float, areaH: Float, size: Float = 48f): Pair<Float, Float> =
        (cx - size / 2).coerceIn(0f, maxOf(0f, areaW - size)) to (cy - size / 2).coerceIn(0f, maxOf(0f, areaH - size))
}

/** §4.1 edit-mode idle exit (30 s), paused while anything edit-related is open or held (#313 review nit 1). */
object EditIdle {
    const val MS = 30_000L
    fun paused(vararg open: Boolean) = open.any { it }
    /**
     * #315 GO follow-up: a bind flow step is up: Android's bind dialog, the widget's setup screen (configure or
     * reconfigure) or the §16.3 explanation sheet. The idle exit must not leave edit mode behind it.
     */
    fun bindUp(p: dev.nasawa.configlauncher.hosted.BindFlow.Pending?) = p != null
}

/**
 * #304: the selected element's frame (white 24 %) and handles. Dragging a handle shows the new size live as a whole-cell
 * outline (one buzz per cell step, [Haptics.cellTick]; a red edge where a taken cell stops it); release → [onResize] once, with the
 * new rectangle, only when it changed. A cancel (gesture cancelled, rotation or window resize re-keying the gesture)
 * writes nothing. With reduced motion the outline jumps instead of animating. Handles have no semantics: TalkBack uses
 * `Resize…` (NW-6).
 */
@Composable
fun BoxScope.ResizeFrame(
    p: Elements.Placement, bounds: ElementInfo.Limits, columns: Int, rows: Int,
    cellW: Dp, cellH: Dp, baseX: Dp, baseY: Dp, areaW: Dp, areaH: Dp, rtl: Boolean, reducedMotion: Boolean,
    free: (Int, Int, Int, Int) -> Boolean, onActive: (Boolean) -> Unit, onResize: (ResizeMath.Rect) -> Unit,
    /** One buzz per cell step (dev1 fix: [Haptics.cellTick], which fires on One UI). Tests pass a counter. */
    onTick: (() -> Unit)? = null,
) {
    val start = ResizeMath.Rect(p.x, p.y, p.w, p.h)
    var live by remember(p) { mutableStateOf<ResizeMath.Result?>(null) }
    var liveHandle by remember(p) { mutableStateOf<ResizeMath.Handle?>(null) }
    val shown = live?.rect ?: start
    val view = androidx.compose.ui.platform.LocalView.current
    val tick by rememberUpdatedState(onTick ?: { Haptics.cellTick(view) })
    val density = LocalDensity.current
    val cwPx = with(density) { cellW.toPx() }; val chPx = with(density) { cellH.toPx() }
    val spec: androidx.compose.animation.core.AnimationSpec<Dp> = if (reducedMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring()
    @Composable fun anim(target: Dp, label: String): State<Dp> = animateDpAsState(target, spec, label = label)
    val fx by anim(baseX + cellW * shown.x, "rx"); val fy by anim(baseY + cellH * shown.y, "ry")
    val fw by anim(cellW * shown.w, "rw"); val fh by anim(cellH * shown.h, "rh")
    val frameColor = if (live != null) Color(0xCCFFFFFF) else Color(0x3DFFFFFF)
    Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(fx.roundToPx(), fy.roundToPx()) }.size(fw, fh).border(2.dp, frameColor, RoundedCornerShape(12.dp)).clearAndSetSemantics { })
    val lh = liveHandle
    live?.takeIf { lh != null }?.let { l ->
        // The edge a taken cell stopped: red (static, so reduced motion needs nothing else).
        val red = Color(0xFFE53935)
        val r = l.rect
        if (l.blockedH) Box(Modifier.offset(baseX + cellW * (if (lh!!.end) r.x + r.w else r.x) - 2.dp, baseY + cellH * r.y)
            .size(4.dp, cellH * r.h).background(red).clearAndSetSemantics { })
        if (l.blockedV) Box(Modifier.offset(baseX + cellW * r.x, baseY + cellH * (if (lh!!.bottom) r.y + r.h else r.y) - 2.dp)
            .size(cellW * r.w, 4.dp).background(red).clearAndSetSemantics { })
    }
    val active by rememberUpdatedState(onActive); val commit by rememberUpdatedState(onResize); val fits by rememberUpdatedState(free)
    ResizeMath.handles(bounds).forEach { h ->
        // The handle point on the (resting) frame, in dp from the page area's top-start.
        val cx = (baseX + cellW * (if (h.start) p.x else if (h.end) p.x + p.w else p.x)).value + if (h.horizontal) 0f else (cellW * p.w).value / 2
        val cy = (baseY + cellH * (if (h.top) p.y else if (h.bottom) p.y + p.h else p.y)).value + if (h.vertical) 0f else (cellH * p.h).value / 2
        val (bx, by) = ResizeMath.hitBox(cx, cy, areaW.value, areaH.value)
        key(h) {
            Box(Modifier.offset(bx.dp, by.dp).size(48.dp).clearAndSetSemantics { testTag = "resize-${h.name}" }
                .pointerInput(p, h, cwPx, chPx, bounds, rtl) {
                    var drag = 0f to 0f
                    try {
                        detectDragGestures(
                            onDragStart = { drag = 0f to 0f; live = ResizeMath.Result(start); liveHandle = h; active(true) },
                            onDrag = { ch, d ->
                                ch.consume()
                                drag = (drag.first + d.x) to (drag.second + d.y)
                                val (dc, dr) = ResizeMath.cells(drag.first, drag.second, cwPx, chPx, rtl)
                                val next = ResizeMath.resize(start, h, dc, dr, bounds, columns, rows, fits)
                                if (next.rect != live?.rect) tick()
                                live = next
                            },
                            onDragEnd = {
                                val r = live?.rect; live = null; active(false)
                                if (r != null && r != start) commit(r)
                            },
                            onDragCancel = { live = null; active(false) },
                        )
                    } finally {
                        // Re-keyed (rotation, window resize, the element changed) or left composition mid-drag: no write.
                        if (live != null) { live = null; active(false) }
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                // Visual dot centred on the handle point (offset when the hit box was pushed inside the area).
                val ox = (cx - 24f - bx).dp; val oy = (cy - 24f - by).dp
                Box(Modifier.offset(ox, oy).size(14.dp).background(Color.White, CircleShape).border(1.dp, Color(0x99000000), CircleShape))
            }
        }
    }
}
