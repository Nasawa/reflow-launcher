package dev.nasawa.configlauncher.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.toSize
import kotlin.math.roundToInt
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.Edits
import dev.nasawa.configlauncher.model.Grid
import dev.nasawa.configlauncher.model.ResolvedItem
import dev.nasawa.configlauncher.store.ConfigEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch

/**
 * Long-press drag to reorder. Tracks the dragged tile in root coordinates and on drop turns the
 * finger's root position into an [Edit] through [targetAt]: a page cell (MoveToCell) or a dock slot
 * (MoveToDock). The same [targetAt] drives the #282 highlight, so the two can't disagree.
 */
class DragController(private val engine: () -> ConfigEngine, private val onCancel: () -> Unit = {}, private val onStart: () -> Unit = {}, private val submit: (Edit) -> Unit) {
    var dragged by mutableStateOf<ResolvedItem?>(null); private set
    var offset by mutableStateOf(Offset.Zero); private set
    private val rects = HashMap<String, Rect>()
    /** #228: each draggable tile's unclipped root bounds and its visual, for the [DragOverlay]. */
    private val unclipped = HashMap<String, Rect>()
    private val visuals = HashMap<String, @Composable () -> Unit>()

    /** #228 (Archimedes): where the lifted tile sat at pickup, unclipped root px; the overlay starts there. */
    var liftedOriginInRoot = Rect.Zero; private set
    /** What the overlay draws for the lifted tile (null: the tile registered no visual). */
    internal var liftedVisual: (@Composable () -> Unit)? = null; private set
    /** #282: the finger's position inside the tile at pickup (tile-local px). */
    var touchInTile = Offset.Zero; private set

    var gridRect = Rect.Zero
    var dockRect = Rect.Zero
    var dockCount = 0
    /** True when the dock is the §1.5 end-edge rail (drop index follows Y). */
    var dockVertical = false
    /** §1.5 RTL (#187): columns and a bottom dock mirror; rects stay physical (boundsInRoot). */
    var rtl = false
    var grid = Grid()
    var currentPageId: String? = null
    /**
     * #282: whether the engine would apply [Edit] (a full dock, a no-op onto its own cell, a reserved cell are refused).
     * HomeScreen wires it to `Edits.apply(config, e).applied`; a refused target gets no highlight and the drop springs back.
     */
    var accepts: (Edit) -> Boolean = { true }

    /**
     * #212 (ux.md §1.5/§4.2): 1 while lifted, eased to 0 while a refused or cancelled drag springs back.
     * The translation and the 1.15 lift scale both follow it, so they return together.
     */
    var lift by mutableFloatStateOf(1f); private set
    /** #282: 0 → 1 over [RAISE_MS] at drag start (the tile eases from its cell to above the finger, 1.0× → 1.15×). */
    var raise by mutableFloatStateOf(1f); private set
    /** True while a refused/cancelled drag springs back; [dragged] stays set so the pager and new drags wait. */
    var returning by mutableStateOf(false); private set
    /** #282: true while an accepted drop settles into its target ([SETTLE_MS]); [submit] runs after it. */
    var settling by mutableStateOf(false); private set
    /**
     * #282 (Pragma, no landing flicker): after an accepted drop is submitted the overlay stays at the target until the
     * tile has been laid out in its new place and that frame has drawn (or [LANDING_TIMEOUT_MS] passes).
     */
    var landing by mutableStateOf(false); private set
    private var landingKey: String? = null
    private var landingSeen = false
    private var landingJob: Job? = null
    private var settleT by mutableFloatStateOf(0f)
    private var settleFrom = Offset.Zero
    private var settleFromScale = 1f
    private var settleTo = Offset.Zero
    private var settleEdit: Edit? = null
    /** Where the spring-back runs: a composition scope (its frame clock). Null → instant, as before #212. */
    var scope: CoroutineScope? = null
    /** §12 reduced motion (wired to [ReducedMotion.on]): the raise, the settle and the return are instant. */
    var reducedMotion: () -> Boolean = { false }
    private var returnJob: Job? = null
    private var raiseJob: Job? = null
    private var settleJob: Job? = null
    /** #281: one tick per drag start (wired by [WireDragHaptics]; a no-op until then). */
    var haptic: () -> Unit = {}
    /** #282: a CLOCK_TICK each time the target changes to a new cell or slot (wired by [WireDragHaptics]). */
    var tick: () -> Unit = {}

    /**
     * #282: where the lifted tile may go, set by the [DragOverlay]: its root bounds, the window's top inset and the
     * 16 dp gap, in px. Until the overlay is laid out ([overlayBounds] empty) the tile follows the finger's offset.
     */
    var overlayBounds = Rect.Zero
    var topInsetPx = 0f
    /** #306 (Pragma): hysteresis on cell and slot borders, px ([BORDER_HYSTERESIS_DP]; set by [DragOverlay], 0 = none). */
    var hysteresisPx = 0f
    var liftGapPx = 0f

    /** #282: the drop target under the finger right now (null: none, or refused). The overlay highlights it. */
    var target by mutableStateOf<DropTarget?>(null); private set

    /** The finger's root position: `liftedOriginInRoot.topLeft + touch + offset` (Pragma's #282 hit point). */
    val fingerInRoot: Offset get() = liftedOriginInRoot.topLeft + touchInTile + offset

    /** The lifted tile's offset from [liftedOriginInRoot] once fully raised (above or beside the finger). */
    private fun liftedOffset(): Offset {
        val r = liftedOriginInRoot
        if (overlayBounds.isEmpty || r.isEmpty) return offset
        val sw = r.width * LIFT_SCALE; val sh = r.height * LIFT_SCALE
        val p = LiftPlacement.place(fingerInRoot, sw, sh, overlayBounds, topInsetPx, liftGapPx)
        // The overlay box is the resting size, scaled about its centre: its unscaled top-left sits (sw - w) / 2 inside.
        return Offset(p.x + (sw - r.width) / 2f - r.left, p.y + (sh - r.height) / 2f - r.top)
    }

    /** The lifted tile's offset from [liftedOriginInRoot] and its scale, as the [DragOverlay] draws it. */
    val visualOffset: Offset get() = when {
        dragged == null -> Offset.Zero
        settling || landing -> settleFrom + (settleTo - settleFrom) * settleT
        else -> liftedOffset() * (raise * lift)
    }
    val visualScale: Float get() = when {
        dragged == null -> 1f
        settling || landing -> settleFromScale + (1f - settleFromScale) * settleT
        else -> 1f + (LIFT_SCALE - 1f) * raise * lift
    }
    /** #282: how much of the lifted look (plate, shadow, 0.92 alpha) shows: 1 lifted, 0 at rest. */
    val liftedness: Float get() = when {
        dragged == null -> 0f
        settling || landing -> 1f - settleT
        else -> raise * lift
    }

    /**
     * Lifts [item]. [touch] is the finger inside the tile (tile-local px); by default its centre, so a caller
     * without a pointer (tests) gets the old "tile centre + offset" hit point.
     */
    fun start(item: ResolvedItem, rect: Rect, touch: Offset? = null) {
        settle() // a new long-press during a spring-back snaps it to the end first (and lands a settling drop)
        val k = key(item)
        liftedOriginInRoot = unclipped[k] ?: rect; liftedVisual = visuals[k]
        touchInTile = touch ?: Offset(liftedOriginInRoot.width / 2f, liftedOriginInRoot.height / 2f)
        dragged = item; offset = Offset.Zero; lift = 1f
        acceptCache = null
        target = targetAt(fingerInRoot) // silent: the start buzz is #281's
        val s = scope
        if (s == null || reducedMotion()) raise = 1f
        else {
            raise = 0f
            raiseJob = s.launch { animate(0f, 1f, animationSpec = tween(RAISE_MS, easing = FastOutSlowInEasing)) { v, _ -> raise = v } }
        }
        engine().dragging = true
        haptic()
        onStart()
    }

    fun move(delta: Offset) {
        if (returning || settling || landing || dragged == null) return
        offset += delta
        val t = hit(fingerInRoot)
        // A new cell or slot, re-entering from no target included (#306: intended); leaving to "no target" is silent.
        if (t != null && t.edit != target?.edit) tick()
        target = t
    }

    private fun end() {
        returnJob?.cancel(); returnJob = null; raiseJob?.cancel(); raiseJob = null; settleJob?.cancel(); settleJob = null
        landingJob?.cancel(); landingJob = null
        returning = false; settling = false; settleT = 0f; settleEdit = null
        landing = false; landingKey = null; landingSeen = false
        dragged = null; offset = Offset.Zero; lift = 1f; raise = 1f; liftedVisual = null; target = null
    }

    private fun key(item: ResolvedItem) = item.item.id + "|" + item.app?.key

    /** Finishes a running spring-back at once (nothing lifted afterwards); a settling drop lands and is written now. */
    fun settle() {
        if (returning || landing) end()
        if (settling) land()
    }

    /**
     * The accepted drop's write, after its settle animation (or at once). [submit] runs first; the overlay then stays
     * at the target ([landing]) until [tilePositioned] reports the tile in its new place and one more frame has
     * started, so the tile never shows in its old cell. Without a scope: end, then submit (as before).
     */
    private fun land() {
        val e = settleEdit ?: return
        val item = dragged ?: return
        engine().dragging = false
        val s = scope
        if (s == null) { end(); submit(e); return }
        settleJob = null // land() may run from that job's finally; don't cancel it from here
        settleT = 1f; settling = false; settleEdit = null
        landing = true; landingKey = key(item); landingSeen = false
        submit(e)
        var job: Job? = null
        job = s.launch {
            try { delay(LANDING_TIMEOUT_MS) } finally { if (landing && landingJob === job) end() }
        }
        landingJob = job
        if (job.isCompleted && landing) end()
    }

    internal fun tilePositioned(item: ResolvedItem, bounds: Rect) = tilePositioned(key(item), bounds)

    /** Every tile reports its unclipped root bounds after layout; the landing tile in a new place ends the hold. */
    internal fun tilePositioned(key: String, bounds: Rect) {
        if (!landing || landingSeen || key != landingKey || bounds == liftedOriginInRoot) return
        landingSeen = true
        val s = scope ?: run { end(); return }
        val hold = landingJob
        // This frame draws the overlay over the tile's new place; clear it at the start of the next one.
        s.launch { withFrameNanos { }; if (landing && landingJob === hold) end() }
    }

    /**
     * #212: nothing is written; `dragging` drops right away so the §17 Q6 held-file apply isn't
     * delayed, then the tile eases back to its cell. Leaving composition (rotate, recreate) cancels
     * the scope and the `finally` clears the lift.
     */
    private fun springBack() {
        if (dragged == null || returning || settling || landing) return
        engine().dragging = false
        onCancel()
        target = null
        raiseJob?.cancel(); raiseJob = null
        val s = scope
        if (s == null || reducedMotion() || (visualOffset == Offset.Zero && visualScale == 1f)) { end(); return }
        returning = true
        var job: Job? = null
        job = s.launch {
            try {
                animate(lift, 0f, animationSpec = RETURN_SPEC) { v, _ -> lift = v }
            } finally {
                if (returnJob === job) end()
            }
        }
        returnJob = job
        if (job.isCompleted) end() // a scope that is already cancelled
    }

    /**
     * §17 Q6: a cancelled drag has no pending op, so a held incoming file is applied right away.
     * A pointer cancel (#214 Step.CANCEL) springs back like a refused drop (#212).
     */
    fun cancel() { if (returning || settling || landing) return; springBack() }

    /**
     * The gesture coroutine ended mid-drag (tile left composition, Activity destroyed / recreated):
     * clear at once, no animation (the layout under the tile is gone), and never write. An accepted
     * drop that is only settling is already decided, so it lands (is written) at once instead.
     */
    fun cancelNow() {
        if (landing) { end(); return } // already written
        if (settling) { land(); return }
        if (returning) { end(); return }
        if (dragged == null) return
        engine().dragging = false
        end()
        onCancel()
    }

    /** The tile for [id]/[app] left composition (recreate, reload, page disposed): a drag on it ends at once. */
    internal fun detached(id: String, app: Any?) {
        // #282: while landing, the old node leaving is the move itself; the overlay waits for the new one.
        if (landing) return
        if (dragged?.item?.id == id && dragged?.app == app) cancelNow()
    }

    /** [item] is the lifted tile, during the drag, its settle and its spring-back (#282: its source shows a ghost). */
    fun isLifted(item: ResolvedItem): Boolean = dragged?.item?.id == item.item.id && dragged?.app == item.app

    private var acceptCache: Pair<Edit, Boolean>? = null
    private fun acceptsCached(e: Edit): Boolean {
        acceptCache?.let { (k, v) -> if (k == e) return v }
        return accepts(e).also { acceptCache = e to it }
    }

    /**
     * #282: the one hit test for both the highlight and [drop]. [point] is a physical root position (the finger).
     * Null outside the grid block and the dock (margins, chrome, the hinge, a book fold's end half) and when the
     * engine would refuse the edit ([accepts]).
     */
    fun targetAt(point: Offset): DropTarget? {
        val item = dragged ?: return null
        val t = when {
            dockRect.contains(point) -> {
                val index = GridHit.dockIndex(
                    point.x, point.y, dockRect.left, dockRect.top, dockRect.width, dockRect.height,
                    dockCount, dockVertical, rtl,
                )
                DropTarget(Edit.MoveToDock(item.item.id, index), DropRects.dockSlot(dockRect, dockCount, index, dockVertical, rtl))
            }
            gridRect.contains(point) && currentPageId != null -> {
                GridHit.cellInBlock(
                    point.x, point.y, gridRect.left, gridRect.top, gridRect.width, gridRect.height,
                    grid.columns, grid.rows, rtl,
                )?.let { (x, y) -> DropTarget(Edit.MoveToCell(item.item.id, currentPageId!!, x, y), DropRects.cell(gridRect, grid.columns, grid.rows, x, y, rtl)) }
            }
            else -> null
        } ?: return null
        return t.takeIf { acceptsCached(it.edit) }
    }

    /**
     * #306: [targetAt] with hysteresis. The current target holds while the finger stays within [hysteresisPx] of its
     * rect (same page), so a finger resting on a border doesn't chatter between two cells. The highlight and the drop
     * both use this, so they still can't disagree.
     */
    fun hit(point: Offset): DropTarget? {
        val cur = target
        if (cur != null && hysteresisPx > 0f && cur.rect.inflate(hysteresisPx).contains(point)) {
            val samePage = (cur.edit as? Edit.MoveToCell)?.let { it.pageId == currentPageId } ?: true
            if (samePage && acceptsCached(cur.edit)) return cur
        }
        return targetAt(point)
    }

    fun drop() {
        dragged ?: return
        if (returning || settling || landing) return
        val t = hit(fingerInRoot)
        target = null
        if (t == null) { springBack(); return }
        raiseJob?.cancel(); raiseJob = null
        settleEdit = t.edit
        settleTo = t.rect.center - liftedOriginInRoot.center
        val s = scope
        if (s == null || reducedMotion()) { settleFrom = settleTo; settleFromScale = 1f; land(); return }
        // Animate first, write after (Pragma): submitting first would recompose the tile in its new cell and cut this.
        settleFrom = visualOffset; settleFromScale = visualScale
        settleT = 0f; settling = true
        var job: Job? = null
        job = s.launch {
            try {
                animate(0f, 1f, animationSpec = tween(SETTLE_MS, easing = FastOutSlowInEasing)) { v, _ -> settleT = v }
            } finally {
                // Finished: write. Cancelled by its scope (screen gone): still write, the drop was accepted.
                if (settleJob === job && settling) land()
            }
        }
        settleJob = job
        if (job.isCompleted && settling) land()
    }

    companion object {
        const val LIFT_SCALE = 1.15f
        /** Critically damped (no overshoot); settles in about 170–200 ms. */
        val RETURN_SPEC = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
        /** #282: the lift eases in over 120 ms; an accepted drop settles over 150 ms. */
        const val RAISE_MS = 120
        const val SETTLE_MS = 150
        /** #282: the longest the landing overlay waits for the committed layout (a refused write never moves the tile). */
        const val LANDING_TIMEOUT_MS = 1_000L
        /** #282: the source cell's ghost, and the lifted tile's alpha. */
        const val GHOST_ALPHA = 0.3f
        const val LIFTED_ALPHA = 0.92f
    }

    /**
     * Tile gestures (#53, ux.md §1.1/§4.1, #280): tap → [onClick]; hold past the long-press timeout and
     * release without moving → [onLongClick] (item sheet); hold at least [LongPress.dragHoldMillis] (half the
     * long-press timeout) then move more than 8 dp → drag (when [canDrag]). Moving past 8 dp before that hold
     * leaves the gesture to the pager or swipe-up. A release after a long-press never fires [onClick].
     * Timing comes from the pointer events' own uptimes, so there is no timer.
     *
     * #228: while lifted the tile stays composed and keeps the gesture, its semantics and its hit area (the
     * #212/#214 cancel paths run from this coroutine's `finally`), but draws at alpha 0; the root
     * [DragOverlay] draws [visual] instead, so no cell, page, pager or region clips it.
     */
    fun Modifier.tile(
        item: ResolvedItem, canDrag: Boolean, onClick: () -> Unit, onLongClick: () -> Unit,
        actions: List<CustomAccessibilityAction> = emptyList(),
        visual: (@Composable () -> Unit)? = null,
    ): Modifier {
        val key = key(item)
        if (visual != null) visuals[key] = visual else visuals.remove(key)
        val isMe = isLifted(item)
        return this
            .onGloballyPositioned {
                rects[key] = it.boundsInRoot(); val u = Rect(it.localToRoot(Offset.Zero), it.size.toSize()); unclipped[key] = u
                tilePositioned(key, u)
            }
            // #212/#214: leaving composition sends a pointer cancel before the gesture coroutine ends; this
            // makes that path instant (no spring-back toward a layout that is gone) whichever runs first.
            .then(DetachElement(this@DragController, item.item.id, item.app))
            // One TalkBack node: the label merges into the node that owns tap, long-press (the item sheet)
            // and the ux.md §12 custom actions.
            .semantics(mergeDescendants = true) {
                onClick { onClick(); true }; onLongClick { onLongClick(); true }
                if (actions.isNotEmpty()) customActions = actions
            }
            .pointerInput(item.item.id, item.app?.key, canDrag) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val menuMs = viewConfiguration.longPressTimeoutMillis
                    val res = LongPress.track(down.position, 8.dp.toPx(), canDrag, down.uptimeMillis, LongPress.dragHoldMillis(menuMs), menuMs)
                    var mine = false // this gesture started the drag and has not ended it yet
                    try {
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (ch == null) { if (mine) { mine = false; cancel() }; break }
                            // #214 (5): an ACTION_CANCEL (rotate / window resize mid-drag) arrives as a lift that is already
                            // consumed. It must cancel, never drop (Compose's own waitForUpOrCancellation reads it the same way).
                            when (res.next(ch.position, ch.pressed, ch.isConsumed, ch.uptimeMillis)) {
                                LongPress.Step.TAP -> { ch.consume(); onClick(); break }
                                // Not ours yet: if the pager (or anyone after us) took this move, the gesture is theirs.
                                LongPress.Step.PASS ->
                                    if (awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id }?.isConsumed != false) break
                                LongPress.Step.SWIPE -> break
                                LongPress.Step.START_DRAG -> { start(item, rects[key] ?: Rect.Zero, down.position); mine = true; move(ch.position - down.position); ch.consume() }
                                LongPress.Step.DRAG -> { move(ch.positionChange()); ch.consume() }
                                LongPress.Step.DROP -> { ch.consume(); mine = false; drop(); break }
                                LongPress.Step.MENU -> { ch.consume(); onLongClick(); break }
                                LongPress.Step.CANCEL -> { if (mine) { mine = false; cancel() }; break }
                                LongPress.Step.WAIT -> ch.consume()
                            }
                        }
                    } finally {
                        // The gesture coroutine was cancelled (tile left composition, Activity destroyed) mid-drag: no write,
                        // and release the engine's dragging hold so a held incoming file isn't stuck. Instant (#212): the
                        // spring-back is only for a pointer cancel or a refused drop while the screen is still there.
                        if (mine) cancelNow()
                    }
                }
            }
            .then(if (isMe) Modifier.alpha(GHOST_ALPHA) else Modifier) // #282: the origin keeps a 30 % ghost
    }
}

/**
 * #228 (Archimedes): the one drag layer, the last child of the HomeScreen root. While a tile is lifted it
 * draws that tile's visual at [DragController.liftedOriginInRoot] + [DragController.visualOffset], scaled by
 * [DragController.visualScale], so the drag and the #212 spring-back stay whole over the dock, the rail
 * (either edge) and across a fold split. Placement is absolute px (`place`, a graphicsLayer translation),
 * never `offset`, which would mirror x in RTL; root and pointer coordinates are physical. No pointer
 * input (hit-testing and drops are unchanged) and no semantics: TalkBack sees only the source tile.
 *
 * #282 (Pragma): below the tile, the [DragController.target] highlight (20 % primary fill, 2 dp white stroke,
 * 1 dp 60 % black ring outside it, inset 2 dp, 8 dp corners). The tile draws at 0.92 alpha on a 12 dp rounded
 * `surfaceContainerHigh` plate with an 8 dp shadow; plate, shadow and alpha follow [DragController.liftedness].
 */
@Composable
fun DragOverlay(drag: DragController, modifier: Modifier = Modifier) {
    val host = remember { floatArrayOf(0f, 0f) }
    val density = LocalDensity.current
    drag.topInsetPx = WindowInsets.safeDrawing.getTop(density).toFloat()
    drag.liftGapPx = with(density) { LIFT_GAP_DP.dp.toPx() }
    drag.hysteresisPx = with(density) { BORDER_HYSTERESIS_DP.dp.toPx() }
    val plate = MaterialTheme.colorScheme.surfaceContainerHigh
    val accent = MaterialTheme.colorScheme.primary
    Layout(
        content = {
            val t = drag.target
            if (drag.dragged != null && t != null) {
                val r = t.rect
                Box(
                    Modifier
                        .layout { m, _ ->
                            val w = r.width.roundToInt().coerceAtLeast(0); val h = r.height.roundToInt().coerceAtLeast(0)
                            val p = m.measure(Constraints.fixed(w, h))
                            layout(w, h) { p.place(0, 0) }
                        }
                        .graphicsLayer { translationX = r.left - host[0]; translationY = r.top - host[1] }
                        .drawBehind { drawTargetHighlight(accent) }
                        .testTag(DRAG_TARGET_TAG)
                        .clearAndSetSemantics {},
                )
            }
            val visual = drag.liftedVisual
            if (drag.dragged != null && visual != null) {
                val r = drag.liftedOriginInRoot
                Box(
                    Modifier
                        .layout { m, _ ->
                            val w = r.width.roundToInt().coerceAtLeast(0); val h = r.height.roundToInt().coerceAtLeast(0)
                            val p = m.measure(Constraints.fixed(w, h))
                            layout(w, h) { p.place(0, 0) }
                        }
                        .graphicsLayer {
                            val o = drag.visualOffset; val k = drag.visualScale
                            translationX = r.left - host[0] + o.x; translationY = r.top - host[1] + o.y
                            scaleX = k; scaleY = k
                            alpha = 1f - (1f - DragController.LIFTED_ALPHA) * drag.liftedness
                        }
                        .testTag(DRAG_OVERLAY_TAG)
                        .clearAndSetSemantics {},
                ) {
                    // The backing plate: a graphicsLayer shadow follows a shape, not the icon's pixels.
                    Box(
                        Modifier.matchParentSize()
                            .graphicsLayer {
                                val l = drag.liftedness
                                shadowElevation = PLATE_ELEVATION_DP.dp.toPx() * l; shape = RoundedCornerShape(PLATE_CORNER_DP.dp); clip = false
                                alpha = l
                            }
                            .background(plate, RoundedCornerShape(PLATE_CORNER_DP.dp))
                            .testTag(DRAG_PLATE_TAG),
                    )
                    visual()
                }
            }
        },
        modifier = modifier.onGloballyPositioned {
            val o = it.localToRoot(Offset.Zero); host[0] = o.x; host[1] = o.y
            drag.overlayBounds = Rect(o, it.size.toSize())
        },
    ) { ms, c ->
        val ps = ms.map { it.measure(Constraints()) }
        layout(c.maxWidth, c.maxHeight) { ps.forEach { it.place(0, 0) } } // absolute: never mirrored in RTL
    }
}

/** #282: Pragma's target style; white on a dark ring is about 5.7:1 on any wallpaper (non-text 3:1). */
private fun DrawScope.drawTargetHighlight(accent: Color) {
    val inset = 2.dp.toPx(); val corner = 8.dp.toPx(); val stroke = 2.dp.toPx(); val ring = 1.dp.toPx()
    val w = size.width - 2 * inset; val h = size.height - 2 * inset
    if (w <= 0f || h <= 0f) return
    val tl = Offset(inset, inset)
    drawRoundRect(accent.copy(alpha = 0.2f), tl, Size(w, h), CornerRadius(corner))
    // Ring outside the white stroke (its centre line half a ring width outside the rect).
    drawRoundRect(Color.Black.copy(alpha = 0.6f), tl - Offset(ring / 2f, ring / 2f), Size(w + ring, h + ring), CornerRadius(corner + ring / 2f), style = Stroke(ring))
    drawRoundRect(Color.White, tl + Offset(stroke / 2f, stroke / 2f), Size(w - stroke, h - stroke), CornerRadius((corner - stroke / 2f).coerceAtLeast(0f)), style = Stroke(stroke))
}

/** #282: a drop target: the edit [DragController.drop] submits and the physical root rect the overlay highlights. */
data class DropTarget(val edit: Edit, val rect: Rect)

/** #282: the highlight rects, the same maths as [GridHit.cellInBlock] / [GridHit.dockIndex]. Physical root px. */
object DropRects {
    /** Cell ([x], [y]) of a [columns] × [rows] block; in [rtl] column 0 is at the right. */
    fun cell(block: Rect, columns: Int, rows: Int, x: Int, y: Int, rtl: Boolean): Rect {
        val cw = block.width / columns; val ch = block.height / rows
        val px = if (rtl) columns - 1 - x else x
        return Rect(block.left + px * cw, block.top + y * ch, block.left + (px + 1) * cw, block.top + (y + 1) * ch)
    }

    /** Drop slot [index] of a dock with [count] items: [count] + 1 equal bands along its axis (a bottom dock mirrors in RTL). */
    fun dockSlot(dock: Rect, count: Int, index: Int, vertical: Boolean, rtl: Boolean): Rect {
        val slots = count + 1
        return if (vertical) {
            val bh = dock.height / slots
            Rect(dock.left, dock.top + index * bh, dock.right, dock.top + (index + 1) * bh)
        } else {
            val bw = dock.width / slots
            val p = if (rtl) slots - 1 - index else index
            Rect(dock.left + p * bw, dock.top, dock.left + (p + 1) * bw, dock.bottom)
        }
    }
}

/**
 * #282: where the lifted (scaled) tile goes, as its top-left in root px. Centred on the finger with its bottom edge
 * [gap] above it; with no room above (it would cross [topInset]) it's centred vertically on the finger and [gap] to
 * the side toward the horizontal centre of [bounds] (physical, the same in LTR and RTL). Then clamped inside [bounds].
 */
object LiftPlacement {
    fun place(finger: Offset, w: Float, h: Float, bounds: Rect, topInset: Float, gap: Float): Offset {
        var x = finger.x - w / 2f
        var y = finger.y - gap - h
        if (y < bounds.top + topInset) {
            y = finger.y - h / 2f
            x = if (finger.x <= bounds.center.x) finger.x + gap else finger.x - gap - w
        }
        x = x.coerceIn(bounds.left, maxOf(bounds.left, bounds.right - w))
        y = y.coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - h))
        return Offset(x, y)
    }
}

/** #282 numbers (Pragma). */
const val LIFT_GAP_DP = 16
const val PLATE_CORNER_DP = 12
const val PLATE_ELEVATION_DP = 8
/** #306 (Pragma): about 4 dp of hysteresis on cell borders, so the target (and its tick) doesn't chatter. */
const val BORDER_HYSTERESIS_DP = 4

/**
 * #281: the drag-start tick goes through Compose's [LocalHapticFeedback] as [HapticFeedbackType.LongPress]. On
 * Android that is `View.performHapticFeedback(LONG_PRESS)` with the default flags, so the system touch-feedback
 * setting decides whether it buzzes (it is never forced).
 */
@Composable
fun WireDragHaptics(drag: DragController) {
    val feedback = LocalHapticFeedback.current
    val view = LocalView.current
    drag.haptic = { feedback.performHapticFeedback(HapticFeedbackType.LongPress) }
    // #282: a light tick per new target, also with the default flags (the system setting decides).
    drag.tick = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
}

/** Test tag of the [DragOverlay]'s lifted-tile node (no semantics besides the tag). */
const val DRAG_OVERLAY_TAG = "drag-overlay"
/** #282: test tags of the target highlight and the lifted tile's backing plate. */
const val DRAG_TARGET_TAG = "drag-target"
const val DRAG_PLATE_TAG = "drag-plate"

private data class DetachElement(val drag: DragController, val id: String, val app: Any?) : ModifierNodeElement<DetachNode>() {
    override fun create() = DetachNode(drag, id, app)
    override fun update(node: DetachNode) { node.drag = drag; node.id = id; node.app = app }
}

private class DetachNode(var drag: DragController, var id: String, var app: Any?) : Modifier.Node() {
    override fun onDetach() { drag.detached(id, app) }
}

/**
 * Pure state machine for one tile gesture (unit-tested; #53, #280). Times are pointer-event uptimes in ms, so
 * tests drive it with plain numbers and never a real clock. The decision is made when the finger first moves
 * past [slop] from [origin]:
 *  - before [holdMs]: [Step.SWIPE], the gesture is left to the pager or swipe-up (never a drag or a sheet);
 *  - at or after [holdMs] (when [canDrag]): [Step.START_DRAG].
 * A release without that move is [Step.TAP] before [menuMs] and [Step.MENU] at or after it.
 * [track] without times starts after the long-press timeout (the pre-#280 behaviour).
 */
class LongPress private constructor(
    private val origin: Offset, private val slop: Float, private val canDrag: Boolean,
    private val downTime: Long, private val holdMs: Long, private val menuMs: Long,
) {
    /**
     * [PASS]: not ours yet, leave the event unconsumed (the pager may still take it). [WAIT]: ours, consume it.
     * [SWIPE]: moved before the hold, the gesture is someone else's; stop tracking.
     */
    enum class Step { PASS, WAIT, TAP, SWIPE, START_DRAG, DRAG, DROP, MENU, CANCEL }
    var dragging = false; private set

    /** [consumed]: a lift that someone already consumed is a pointer cancel, not a release (#214). */
    fun next(pos: Offset, pressed: Boolean, consumed: Boolean = false, time: Long = downTime + menuMs): Step {
        val t = time - downTime
        val far = (pos - origin).getDistance() > slop
        return when {
            !pressed && consumed -> Step.CANCEL
            dragging -> if (pressed) Step.DRAG else Step.DROP
            // Before the long-press, a move someone else consumed (the pager) ends it, as Compose's tap detection does.
            consumed && t < menuMs -> Step.CANCEL
            !pressed -> when {
                t >= menuMs -> Step.MENU
                far -> Step.SWIPE // a fling that lifted in one event: not a tap
                else -> Step.TAP
            }
            far && canDrag && t >= holdMs -> { dragging = true; Step.START_DRAG }
            far && t < menuMs -> Step.SWIPE
            (canDrag && t >= holdMs) || t >= menuMs -> Step.WAIT
            else -> Step.PASS
        }
    }

    companion object {
        /** #280: the hold before a move can start a drag, as a share of the system long-press timeout. */
        const val DRAG_HOLD_FRACTION = 0.5
        /** 200 ms at the default 400 ms timeout; longer when the user set a longer touch-and-hold delay. */
        fun dragHoldMillis(longPressTimeoutMillis: Long): Long = (longPressTimeoutMillis * DRAG_HOLD_FRACTION).toLong()

        /** After the long-press timeout (the old entry point): a move past [slop] drags, a release opens the menu. */
        fun track(origin: Offset, slop: Float, canDrag: Boolean) = LongPress(origin, slop, canDrag, 0L, 0L, 0L)

        fun track(origin: Offset, slop: Float, canDrag: Boolean, downTime: Long, holdMs: Long, menuMs: Long): LongPress {
            require(holdMs in 0..menuMs) { "the drag hold ($holdMs) must not exceed the long-press timeout ($menuMs)" }
            return LongPress(origin, slop, canDrag, downTime, holdMs, menuMs)
        }
    }
}
