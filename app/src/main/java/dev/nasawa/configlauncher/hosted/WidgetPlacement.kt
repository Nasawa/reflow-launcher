package dev.nasawa.configlauncher.hosted

import dev.nasawa.configlauncher.config.AppWidget
import dev.nasawa.configlauncher.config.Elements
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.Edits
import dev.nasawa.configlauncher.model.LauncherConfig
import dev.nasawa.configlauncher.model.Page
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * #302 NW-5 (native-widgets.md §5 NW-5, ux.md §16.1): a provider's default size in cells, and where the picker puts
 * it. Pure, so `apps.json` `widgets[].size` and the picker always agree on the same grid.
 */
object WidgetPlacement {
    /** Provider sizes as Android reports them: dp, and `targetCell*` (API 31+, 0 = unset). */
    data class Sizes(val minWDp: Float, val minHDp: Float, val targetW: Int = 0, val targetH: Int = 0)

    /**
     * Default size: `targetCellWidth/Height` when set, else `minWidth/minHeight` rounded up to cells of this phone's
     * main grid ([cellW] × [cellH] dp). Clamped to the grid ([columns] × [rows]) and at least 1×1.
     */
    fun defaultCells(s: Sizes, cellW: Float, cellH: Float, columns: Int, rows: Int): Pair<Int, Int> {
        val w = if (s.targetW > 0) s.targetW else HostedWidgets.cells(s.minWDp, cellW)
        val h = if (s.targetH > 0) s.targetH else HostedWidgets.cells(s.minHDp, cellH)
        return w.coerceIn(1, maxOf(1, columns)) to h.coerceIn(1, maxOf(1, rows))
    }

    /** True when the [w]×[h] rectangle at ([x], [y]) is inside the grid and touches no item or element cell. */
    fun fits(c: LauncherConfig, page: Page, x: Int, y: Int, w: Int, h: Int): Boolean {
        val g = c.effectiveGrid
        if (x < 0 || y < 0 || w < 1 || h < 1 || x + w > g.columns || y + h > g.rows) return false
        val used = page.items.mapNotNull { i -> i.x?.let { ix -> i.y?.let { iy -> ix to iy } } }.toSet() + Elements.reserved(c, page.id)
        for (cx in x until x + w) for (cy in y until y + h) if ((cx to cy) in used) return false
        return true
    }

    /** First free [w]×[h] rectangle on [page], row by row (top-left first); null when none. */
    fun freeRect(c: LauncherConfig, page: Page, w: Int, h: Int): Pair<Int, Int>? {
        val g = c.effectiveGrid
        for (y in 0..g.rows - h) for (x in 0..g.columns - w) if (fits(c, page, x, y, w, h)) return x to y
        return null
    }

    /**
     * ux.md §16.1: the current page ([pageId]) first, then the pages after it, then the ones before, then a new page
     * (at most [Edits.MAX_PAGES]). Null = home is full (`home_full`). The result is the one [Edit.AddElement] to write.
     */
    fun place(c: LauncherConfig, pageId: String?, pkg: String, cls: String, w: Int, h: Int, label: String? = null,
              random: () -> Int = { (0..Int.MAX_VALUE).random() }): Edit.AddElement? {
        val start = c.pages.indexOfFirst { it.id == pageId }.coerceAtLeast(0)
        val order = c.pages.drop(start) + c.pages.take(start)
        val id = Edits.newId(c, Edits.IdKind.WIDGET, label ?: pkg.substringAfterLast('.'), random)
        for (p in order) freeRect(c, p, w, h)?.let { (x, y) -> return Edit.AddElement(element(id, p.id, x, y, w, h, pkg, cls)) }
        if (c.pages.size >= Edits.MAX_PAGES) return null
        val g = c.effectiveGrid
        if (w > g.columns || h > g.rows) return null
        val taken = c.pages.map { it.id }.toSet()
        val newId = generateSequence(1) { it + 1 }.map { "page-$it" }.first { it !in taken }   // §14 R9: lowest unused N
        return Edit.AddElement(element(id, newId, 0, 0, w, h, pkg, cls), newPage = newId)
    }

    /** The element the picker writes: provider, page and cells, nothing else. */
    fun element(id: String, page: String, x: Int, y: Int, w: Int, h: Int, pkg: String, cls: String) = buildJsonObject {
        put("id", id); put("type", AppWidget.name); put("requires", AppWidget.MIN_REQUIRES)
        put("page", page); put("x", x); put("y", y); put("w", w); put("h", h)
        put("content", buildJsonObject { put("provider", buildJsonObject { put("package", pkg); put("class", cls) }) })
    }
}
