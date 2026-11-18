package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.config.Elements
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.ElementEdits
import dev.nasawa.configlauncher.model.LauncherConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * #303 NW-6 ux.md §16.7.1: `Remove from home` + Undo for a grid element, with the device-local hold.
 *
 * Order matters: every [Hold] is taken **before** the Remove write (the engine settles bindings and consent inside
 * that write, and an unheld record is GC'd at once). A failed Remove gives the holds straight back. A successful Undo
 * puts them back right after its edit applied (before its debounced write). An Undo that writes nothing releases
 * nothing: the window's end ([release]) does.
 */
class ElementUndo(
    private val config: () -> LauncherConfig,
    private val edit: suspend (Edit) -> Boolean,
    private val holds: List<Hold>,
) {
    /** Device-local state kept per element id ([dev.nasawa.configlauncher.hosted.WidgetBindings], consent). */
    interface Hold { fun hold(id: String): Boolean; fun restore(id: String); fun release(id: String) }

    data class Removed(val id: String, val element: JsonObject, val index: Int)

    sealed interface Undone {
        /** Back in its old cells (no message). */
        data object Same : Undone
        /** Back on [pageId] at another spot (`undo_element_moved`). */
        data class Moved(val pageId: String) : Undone
        data object Back : Undone
        data object NoRoom : Undone
    }

    suspend fun remove(id: String): Removed? {
        val c = config()
        val i = ElementEdits.index(c, id).takeIf { it >= 0 } ?: return null
        val el = c.elements!![i]
        holds.forEach { it.hold(id) }
        if (!edit(Edit.RemoveElement(id))) { holds.forEach { it.restore(id) }; return null }
        return Removed(id, el, i)
    }

    suspend fun undo(r: Removed): Undone {
        val plan = ElementEdits.restorePlan(config(), r.element)
        if (!(plan is ElementEdits.Plan.Same || plan is ElementEdits.Plan.Moved)) return outcome(plan)
        if (!edit(Edit.RestoreElement(r.element, r.index))) return outcome(ElementEdits.restorePlan(config(), r.element))
        holds.forEach { it.restore(r.id) }
        return if (plan is ElementEdits.Plan.Moved) Undone.Moved(plan.pageId) else Undone.Same
    }

    fun release(r: Removed) = holds.forEach { it.release(r.id) }

    private fun outcome(p: ElementEdits.Plan): Undone = if (p == ElementEdits.Plan.Back) Undone.Back else Undone.NoRoom
}

/** #303 names and limits for grid elements. */
object ElementInfo {
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** §17.2 name: the card's `title` or [fallback] (an appWidget's provider label), else the element id. */
    fun name(c: LauncherConfig, id: String, fallback: String? = null): String {
        val e = ElementEdits.element(c, id) ?: return fallback ?: id
        return e.str("title")?.takeIf { it.isNotBlank() }?.let(Labels::clean)
            ?: fallback?.takeIf { it.isNotBlank() }
            ?: (e["content"] as? JsonObject)?.str("label")?.takeIf { it.isNotBlank() }?.let(Labels::clean)
            ?: id
    }

    /** Size bounds for `Resize…` (before free cells): min and max `w`/`h`; a fixed axis has min == max == current. */
    data class Limits(val minW: Int, val maxW: Int, val minH: Int, val maxH: Int)

    /**
     * §16.4: an appWidget is bounded by its provider's min/max cells ([facts]; a provider that can't resize an axis
     * keeps it); a card by `minSpan` (else its smallest `layouts` key); both by the grid. Current sizes outside the
     * range stay reachable as the starting point.
     */
    fun limits(c: LauncherConfig, id: String, facts: dev.nasawa.configlauncher.hosted.HostedWidgets.Facts? = null): Limits? {
        val e = ElementEdits.element(c, id) ?: return null
        val p = Elements.placement(e) ?: return null
        val g = c.effectiveGrid
        val colsLeft = g.columns - p.x; val rowsLeft = g.rows - p.y
        var minW = 1; var minH = 1; var maxW = colsLeft; var maxH = rowsLeft
        if (e.str("type") == "appWidget") {
            facts?.minW?.let { minW = it }; facts?.minH?.let { minH = it }
            facts?.maxW?.let { maxW = minOf(maxW, it) }; facts?.maxH?.let { maxH = minOf(maxH, it) }
            if (facts?.resizeW == false) { minW = p.w; maxW = p.w }
            if (facts?.resizeH == false) { minH = p.h; maxH = p.h }
        } else {
            val span = e["minSpan"] as? JsonObject
            fun int(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.content?.toIntOrNull()
            val keys = (e["layouts"] as? JsonObject)?.keys.orEmpty().mapNotNull { k ->
                k.split('x').takeIf { it.size == 2 }?.let { (a, b) -> a.toIntOrNull()?.let { w -> b.toIntOrNull()?.let { h -> w to h } } }
            }
            minW = int(span, "w") ?: keys.minOfOrNull { it.first } ?: 1
            minH = int(span, "h") ?: keys.minOfOrNull { it.second } ?: 1
        }
        minW = minOf(minW, p.w).coerceAtLeast(1); minH = minOf(minH, p.h).coerceAtLeast(1)
        maxW = maxOf(maxW, p.w).coerceAtMost(colsLeft).coerceAtLeast(minW)
        maxH = maxOf(maxH, p.h).coerceAtMost(rowsLeft).coerceAtLeast(minH)
        return Limits(minW, maxW, minH, maxH)
    }

    /** §16.7.2: a page offers a spot when some top-left cell fits the element's `w`×`h` there (itself ignored). */
    fun pagesWithRoom(c: LauncherConfig, id: String): Set<String> {
        val p = ElementEdits.element(c, id)?.let(Elements::placement) ?: return emptySet()
        return c.pages.filter { ElementEdits.firstFree(c, it.id, p.w, p.h, ignore = id) != null }.map { it.id }.toSet()
    }
}

/**
 * §16.7.2 `Move to…` for a grid element: same picker as items; row and column pick the top-left cell. A page with no
 * room is listed but disabled (`No room`); `Move` is disabled where the rectangle doesn't fit.
 */
@Composable
fun ElementMoveDialog(name: String, c: LauncherConfig, id: String, onMove: (String, Int, Int) -> Unit, onDismiss: () -> Unit) {
    val p = remember(id) { ElementEdits.element(c, id)?.let(Elements::placement) }
    val pageIds = c.pages.map { it.id }
    if (p == null || pageIds.isEmpty()) { LaunchedEffect(Unit) { onDismiss() }; return }
    val room = remember(c, id) { ElementInfo.pagesWithRoom(c, id) }
    val g = c.effectiveGrid
    var page by remember { mutableIntStateOf(pageIds.indexOf(p.page).coerceAtLeast(0)) }
    var x by remember { mutableIntStateOf(p.x) }
    var y by remember { mutableIntStateOf(p.y) }
    val pageId = pageIds[page]
    val hasRoom = pageId in room
    val ok = hasRoom && ElementEdits.fits(c, pageId, x, y, p.w, p.h, ignore = id) && !(pageId == p.page && x == p.x && y == p.y)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to_title, name)) },
        text = {
            Column {
                val pageLabel = if (hasRoom) stringResource(R.string.move_to_page, page + 1, pageIds.size)
                    else stringResource(R.string.move_element_page_no_room, page + 1, pageIds.size)
                StepperRow(pageLabel, page, 0, pageIds.size - 1) { page = it }
                StepperRow(stringResource(R.string.move_to_row, y + 1), y, 0, g.rows - p.h, enabled = hasRoom) { y = it }
                StepperRow(stringResource(R.string.move_to_column, x + 1), x, 0, g.columns - p.w, enabled = hasRoom) { x = it }
            }
        },
        confirmButton = { TextButton({ onMove(pageId, x, y) }, enabled = ok) { Text(stringResource(R.string.move_to_confirm)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** §16.4 `Resize…`: Width and Height steppers (`Width, 2 of 4`), bounded by [limits] and free cells; `Done` writes once. */
@Composable
fun ElementResizeDialog(name: String, c: LauncherConfig, id: String, limits: ElementInfo.Limits, onDone: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val p = remember(id) { ElementEdits.element(c, id)?.let(Elements::placement) }
    if (p == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    var w by remember { mutableIntStateOf(p.w) }
    var h by remember { mutableIntStateOf(p.h) }
    // Free cells: the largest size the element can grow to in place, for the other axis as it is now.
    val maxW = minOf(limits.maxW, ElementEdits.maxFree(c, id, horizontal = true, other = h)).coerceAtLeast(w)
    val maxH = minOf(limits.maxH, ElementEdits.maxFree(c, id, horizontal = false, other = w)).coerceAtLeast(h)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.resize_title, name)) },
        text = {
            Column {
                StepperRow(stringResource(R.string.resize_width, w, maxW), w, limits.minW, maxW) { w = it }
                StepperRow(stringResource(R.string.resize_height, h, maxH), h, limits.minH, maxH) { h = it }
                Text(stringResource(R.string.resize_range, limits.minW, limits.minH, limits.maxW, limits.maxH),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton({ onDone(w, h) }) { Text(stringResource(R.string.resize_done)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun StepperRow(label: String, value: Int, min: Int, max: Int, enabled: Boolean = true, set: (Int) -> Unit) {
    val less = stringResource(R.string.a11y_decrease, label); val more = stringResource(R.string.a11y_increase, label)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton({ set((value - 1).coerceAtLeast(min)) }, Modifier.semantics { contentDescription = less }, enabled = enabled && value > min) { Text("−") }
        Text(label, Modifier.weight(1f))
        TextButton({ set((value + 1).coerceAtMost(max)) }, Modifier.semantics { contentDescription = more }, enabled = enabled && value < max) { Text("+") }
    }
}

/** #303: which §16.7.3 row an element's TalkBack actions follow. */
enum class ElementKind { APP_WIDGET, LIVE_CARD, CONSENT_TILE }

/** §16.7.3 TalkBack custom actions after the element's own first one (Review / Permissions), in order. */
enum class ElementAction { RESIZE, MOVE_TO, REMOVE }

object ElementActionList {
    fun of(kind: ElementKind): List<ElementAction> = when (kind) {
        ElementKind.APP_WIDGET, ElementKind.LIVE_CARD -> listOf(ElementAction.RESIZE, ElementAction.MOVE_TO, ElementAction.REMOVE)
        ElementKind.CONSENT_TILE -> listOf(ElementAction.MOVE_TO, ElementAction.REMOVE)
    }

    /** Cells moved by a drag of ([dxPx], [dyPx]): nearest whole cells; in RTL a drag to the right lowers `x`. */
    fun cellDelta(dxPx: Float, dyPx: Float, cellWPx: Float, cellHPx: Float, rtl: Boolean): Pair<Int, Int> {
        if (cellWPx <= 0f || cellHPx <= 0f) return 0 to 0
        val dx = kotlin.math.round(dxPx / cellWPx).toInt()
        return (if (rtl) -dx else dx) to kotlin.math.round(dyPx / cellHPx).toInt()
    }
}

/**
 * #303 §16.7.2 edit-mode drag of a span: long-press then drag; the outline follows in whole cells (white where the
 * rectangle is free, red where it isn't); release on a free spot → [onDrop] (one write); anywhere else → no write.
 * A long-press released without moving opens the sheet ([onLong]). No semantics: TalkBack uses `Move to…`.
 * [selected] draws the §16.4 white 24 % frame (the picked widget; handles are NW-8, #304).
 */
@Composable
fun ElementDragTarget(
    p: Elements.Placement, cellW: androidx.compose.ui.unit.Dp, cellH: androidx.compose.ui.unit.Dp,
    baseX: androidx.compose.ui.unit.Dp, baseY: androidx.compose.ui.unit.Dp, rtl: Boolean, selected: Boolean,
    canDrop: (Int, Int) -> Boolean, onDrop: (Int, Int) -> Unit, onLong: () -> Unit,
) {
    var drag by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cwPx = with(density) { cellW.toPx() }; val chPx = with(density) { cellH.toPx() }
    val drop by rememberUpdatedState(onDrop); val long by rememberUpdatedState(onLong); val can by rememberUpdatedState(canDrop)
    val target = drag?.let { (dx, dy) -> ElementActionList.cellDelta(dx, dy, cwPx, chPx, rtl).let { (a, b) -> (p.x + a) to (p.y + b) } }
    val frame = Modifier.offset(baseX + cellW * p.x, baseY + cellH * p.y).size(cellW * p.w, cellH * p.h)
    androidx.compose.foundation.layout.Box(frame
        .then(if (selected) Modifier.border(2.dp, androidx.compose.ui.graphics.Color(0x3DFFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) else Modifier)
        .pointerInput(p) {
            detectDragGesturesAfterLongPress(
                onDragStart = { drag = 0f to 0f },
                onDrag = { ch, d -> ch.consume(); drag = drag?.let { (x, y) -> (x + d.x) to (y + d.y) } },
                onDragEnd = {
                    val t = drag?.let { (dx, dy) -> ElementActionList.cellDelta(dx, dy, cwPx, chPx, rtl) }
                    drag = null
                    when {
                        t == null -> {}
                        t == (0 to 0) -> long()
                        can(p.x + t.first, p.y + t.second) -> drop(p.x + t.first, p.y + t.second)
                    }
                },
                onDragCancel = { drag = null },
            )
        })
    target?.let { (tx, ty) ->
        val ok = (tx to ty) == (p.x to p.y) || canDrop(tx, ty)
        androidx.compose.foundation.layout.Box(Modifier.offset(baseX + cellW * tx, baseY + cellH * ty).size(cellW * p.w, cellH * p.h)
            .border(2.dp, if (ok) androidx.compose.ui.graphics.Color(0xCCFFFFFF) else androidx.compose.ui.graphics.Color(0xFFE53935),
                androidx.compose.foundation.shape.RoundedCornerShape(12.dp)))
    }
}

/** #303 §16.7.1: the `Removed "<name>" from home.` snackbar; [job] ends the Undo window (release unless [restored]). */
class ElementSnack(val removed: ElementUndo.Removed, val name: String, val text: String) {
    @Volatile var restored = false
    var job: kotlinx.coroutines.Job? = null

    companion object {
        const val BASE_MS = 10_000L
        /** 10 s, or the user's accessibility "Time to take action" when that's longer. */
        fun windowMs(ctx: android.content.Context): Long {
            val am = ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java) ?: return BASE_MS
            val flags = android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT or android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS
            return maxOf(BASE_MS, am.getRecommendedTimeoutMillis(BASE_MS.toInt(), flags).toLong())
        }
    }
}

/** A 4 s note with no action (`undo_element_moved`, `move_element_taken`, …); a new instance each time, so it restarts. */
class ElementNote(val text: String)

@Composable
fun ElementUndoSnackbar(u: ElementSnack, windowMs: Long, onUndo: () -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(u) { kotlinx.coroutines.delay(windowMs); onDismiss() }
    val undo = stringResource(R.string.merge_undo)
    androidx.compose.material3.Snackbar(
        // §16.7.1: Undo is also a TalkBack custom action on the snackbar.
        Modifier.padding(8.dp).semantics {
            liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
            customActions = listOf(androidx.compose.ui.semantics.CustomAccessibilityAction(undo) { onUndo(); true })
        },
        action = { TextButton(onUndo, colors = snackbarActionColors()) { Text(undo) } },
        dismissAction = { TextButton(onDismiss, colors = snackbarActionColors()) { Text("✕") } },
    ) { Text(u.text) }
}
