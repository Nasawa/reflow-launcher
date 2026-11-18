package dev.nasawa.configlauncher.model

import dev.nasawa.configlauncher.config.Elements
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * #303 NW-6 (ux.md §16.4, §16.7.1–§16.7.2): edits of grid-placed root elements (`appWidget`, `card`). Each is one write
 * touching only that element (and its `pages[].overrides` entry on remove). No reflow, no swap: a target rectangle
 * must be inside the grid and free of items and other elements, checked again at write time (replay, §4.4).
 */
object ElementEdits {
    private fun JsonObject.id() = (this["id"] as? JsonPrimitive)?.contentOrNull

    fun index(c: LauncherConfig, id: String): Int = c.elements.orEmpty().indexOfFirst { it.id() == id }
    fun element(c: LauncherConfig, id: String): JsonObject? = c.elements.orEmpty().firstOrNull { it.id() == id }

    /** True when the [w]×[h] rectangle at ([x], [y]) on [pageId] is inside the grid and free, ignoring element [ignore]. */
    fun fits(c: LauncherConfig, pageId: String, x: Int, y: Int, w: Int, h: Int, ignore: String? = null): Boolean {
        val g = c.effectiveGrid
        val page = c.pages.firstOrNull { it.id == pageId } ?: return false
        if (x < 0 || y < 0 || w < 1 || h < 1 || x + w > g.columns || y + h > g.rows) return false
        val used = HashSet<Pair<Int, Int>>()
        page.items.forEach { i -> if (i.x != null && i.y != null) used += i.x!! to i.y!! }
        c.elements.orEmpty().forEach { e -> if (e.id() != ignore) Elements.placement(e)?.takeIf { it.page == pageId }?.let { used += it.cells() } }
        for (cx in x until x + w) for (cy in y until y + h) if ((cx to cy) in used) return false
        return true
    }

    /** First free top-left for a [w]×[h] rectangle on [pageId], row-major. */
    fun firstFree(c: LauncherConfig, pageId: String, w: Int, h: Int, ignore: String? = null): Pair<Int, Int>? {
        val g = c.effectiveGrid
        for (y in 0..g.rows - h) for (x in 0..g.columns - w) if (fits(c, pageId, x, y, w, h, ignore)) return x to y
        return null
    }

    fun remove(c: LauncherConfig, id: String): ApplyResult {
        val els = c.elements ?: return ApplyResult(c, false)
        if (els.none { it.id() == id }) return ApplyResult(c, false)
        val pages = c.pages.map { p ->
            val ov = p.overrides ?: return@map p
            if (id !in ov) p else p.copy(overrides = JsonObject(ov - id).takeIf { it.isNotEmpty() })
        }
        return ApplyResult(c.copy(elements = els.filter { it.id() != id }, pages = pages), true)
    }

    /** What Undo will do (§16.7.1); [Back] and [NoRoom] write nothing. */
    sealed interface Plan {
        data object Back : Plan
        data object NoRoom : Plan
        data class Same(val pageId: String) : Plan
        data class Moved(val pageId: String, val x: Int, val y: Int) : Plan
    }

    fun restorePlan(c: LauncherConfig, element: JsonObject): Plan {
        val id = element.id() ?: return Plan.NoRoom
        if (c.elements.orEmpty().any { it.id() == id } || c.allItems().any { it.id == id } || c.pages.any { it.id == id }) return Plan.Back
        val p = Elements.placement(element) ?: return Plan.NoRoom
        if (fits(c, p.page, p.x, p.y, p.w, p.h)) return Plan.Same(p.page)
        val start = c.pages.indexOfFirst { it.id == p.page }.coerceAtLeast(0)
        for (page in c.pages.drop(start)) firstFree(c, page.id, p.w, p.h)?.let { (x, y) -> return Plan.Moved(page.id, x, y) }
        return Plan.NoRoom
    }

    fun restore(c: LauncherConfig, e: Edit.RestoreElement): ApplyResult {
        val el = when (val plan = restorePlan(c, e.element)) {
            Plan.Back, Plan.NoRoom -> return ApplyResult(c, false)
            is Plan.Same -> e.element
            is Plan.Moved -> e.element.with("page" to JsonPrimitive(plan.pageId), "x" to JsonPrimitive(plan.x), "y" to JsonPrimitive(plan.y))
        }
        val els = c.elements.orEmpty().toMutableList().also { it.add(e.index.coerceIn(0, it.size), el) }
        return ApplyResult(c.copy(elements = els), true)
    }

    fun move(c: LauncherConfig, e: Edit.MoveElement): ApplyResult {
        val i = index(c, e.id).takeIf { it >= 0 } ?: return ApplyResult(c, false)
        val el = c.elements!![i]
        val p = Elements.placement(el) ?: return ApplyResult(c, false)
        if (p.page == e.pageId && p.x == e.x && p.y == e.y) return ApplyResult(c, false)
        if (!fits(c, e.pageId, e.x, e.y, p.w, p.h, ignore = e.id)) return ApplyResult(c, false)
        return set(c, i, el.with("page" to JsonPrimitive(e.pageId), "x" to JsonPrimitive(e.x), "y" to JsonPrimitive(e.y)))
    }

    fun resize(c: LauncherConfig, e: Edit.ResizeElement): ApplyResult {
        val i = index(c, e.id).takeIf { it >= 0 } ?: return ApplyResult(c, false)
        val el = c.elements!![i]
        val p = Elements.placement(el) ?: return ApplyResult(c, false)
        if (p.w == e.w && p.h == e.h) return ApplyResult(c, false)
        if (!fits(c, p.page, p.x, p.y, e.w, e.h, ignore = e.id)) return ApplyResult(c, false)
        return set(c, i, el.with("w" to JsonPrimitive(e.w), "h" to JsonPrimitive(e.h)))
    }

    /** Largest [w] (or [h]) the element can grow to in place, given free cells (§16.4: the drag stops at the last free cell). */
    fun maxFree(c: LauncherConfig, id: String, horizontal: Boolean, other: Int): Int {
        val el = element(c, id) ?: return 1
        val p = Elements.placement(el) ?: return 1
        val g = c.effectiveGrid
        var n = 1
        val limit = if (horizontal) g.columns - p.x else g.rows - p.y
        while (n < limit && (if (horizontal) fits(c, p.page, p.x, p.y, n + 1, other, id) else fits(c, p.page, p.x, p.y, other, n + 1, id))) n++
        return n
    }

    /** Same keys, same order, only the given values replaced. */
    private fun JsonObject.with(vararg kv: Pair<String, JsonElement>): JsonObject {
        val m = LinkedHashMap<String, JsonElement>(this)
        kv.forEach { (k, v) -> m[k] = v }
        return JsonObject(m)
    }

    private fun set(c: LauncherConfig, i: Int, el: JsonObject) =
        ApplyResult(c.copy(elements = c.elements!!.toMutableList().also { it[i] = el }), true)
}
