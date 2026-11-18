package dev.nasawa.configlauncher.hosted

import dev.nasawa.configlauncher.config.AppWidget
import dev.nasawa.configlauncher.config.Elements
import dev.nasawa.configlauncher.model.LauncherConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * #300 NW-3: what each shown `appWidget` element looks like on this phone (native-widgets.md §2.3–§2.4, ux.md §16.2,
 * §16.5). Pure: provider facts and bindings come in, tiles, status.json states and phone-only warnings come out.
 * Never rewrites `w`/`h`: an out-of-range size draws anyway, with a cue and a warning.
 */
object HostedWidgets {
    /** status.json `appWidgets` states (§2.4). Resolved states only; never the `appWidgetId`. */
    enum class State(val key: String) {
        BOUND("bound"), NEEDS_SETUP("needsSetup"), NEEDS_CONFIG("needsConfig"),
        NOT_INSTALLED("notInstalled"), UNSUPPORTED("unsupported"), FAILED("failed"),
    }

    /** A provider as this phone sees it, with its size limits in cells of the current main grid (null = unknown/none). */
    data class Facts(val label: String?, val minW: Int? = null, val minH: Int? = null, val maxW: Int? = null, val maxH: Int? = null)

    /** Size problem for the §16.5 cue: [below] true = under the provider's minimum, else over its maximum. */
    data class Range(val below: Boolean, val limitW: Int, val limitH: Int)

    data class Tile(
        val id: String, val index: Int, val placement: Elements.Placement, val label: String?, val pkg: String, val cls: String,
        val profile: String, val state: State, val appWidgetId: Int?, val range: Range?,
        /** #301: `Tap to allow`, or icon and label only while a silent bind is pending. */
        val hint: BindFlow.Hint? = null,
    )

    /** Provider lookup: (package, class, profile) → facts, or null when not installed on this phone. */
    fun interface Providers { fun facts(pkg: String, cls: String, profile: String): Facts? }

    /** Every shown `appWidget` (file order); [pageId] null = all pages. [failed] = element ids whose host view failed. */
    fun tiles(c: LauncherConfig, bindings: Map<String, WidgetBindings.Binding>, providers: Providers,
              failed: Set<String> = emptySet(), pageId: String? = null, hints: (id: String, pkg: String, cls: String) -> BindFlow.Hint? = { _, _, _ -> null }): List<Tile> =
        c.elements.orEmpty().mapIndexedNotNull { i, e ->
            if (Elements.typeOf(e) != AppWidget) return@mapIndexedNotNull null
            if ((e["enabled"] as? JsonPrimitive)?.content == "false") return@mapIndexedNotNull null
            val p = Elements.placement(e) ?: return@mapIndexedNotNull null
            if (pageId != null && p.page != pageId) return@mapIndexedNotNull null
            val id = (e["id"] as JsonPrimitive).content
            val content = e["content"] as JsonObject
            val prov = content["provider"] as JsonObject
            val pkg = (prov["package"] as JsonPrimitive).content
            val cls = (prov["class"] as JsonPrimitive).content
            val profile = (content["profile"] as? JsonPrimitive)?.content ?: "personal"
            val label = (content["label"] as? JsonPrimitive)?.content
            val facts = if (profile == "personal") providers.facts(pkg, cls, profile) else null
            val b = bindings[id]?.takeIf { WidgetBindings.norm(it.provider) == WidgetBindings.norm(WidgetBindings.Provider(pkg, cls)) && it.profile == profile }
            val hint = if (b == null && profile == "personal" && facts != null) hints(id, pkg, cls) else null
            val state = when {
                profile != "personal" -> State.UNSUPPORTED   // D3: work widgets come later
                facts == null -> State.NOT_INSTALLED
                b == null -> if (hint == BindFlow.Hint.FINISH) State.NEEDS_CONFIG else State.NEEDS_SETUP
                b.state == WidgetBindings.State.NEEDS_CONFIG -> State.NEEDS_CONFIG
                b.state == WidgetBindings.State.FAILED || id in failed -> State.FAILED
                else -> State.BOUND
            }
            Tile(id, i, p, label ?: facts?.label, pkg, cls, profile, state, b?.appWidgetId?.takeIf { state == State.BOUND },
                facts?.let { range(p.w, p.h, it) }, hint?.takeIf { it != BindFlow.Hint.FINISH })
        }

    fun range(w: Int, h: Int, f: Facts): Range? {
        val minW = f.minW ?: 1; val minH = f.minH ?: 1
        if (w < minW || h < minH) return Range(true, minW, minH)
        val maxW = f.maxW; val maxH = f.maxH
        if ((maxW != null && w > maxW) || (maxH != null && h > maxH)) return Range(false, maxOf(maxW ?: w, 1), maxOf(maxH ?: h, 1))
        return null
    }

    /** status.json `appWidgets`: element id → state key. */
    fun states(tiles: List<Tile>): Map<String, String> = tiles.associate { it.id to it.state.key }

    /** ux.md §16.5 phone-only warnings (validate.py can't know providers). */
    fun warnings(tiles: List<Tile>): List<String> = tiles.mapNotNull { t ->
        val r = t.range ?: return@mapNotNull null
        val size = "${t.placement.w}×${t.placement.h}"
        if (r.below) "elements[${t.index}]: size $size is below the provider's minimum ${r.limitW}×${r.limitH}. Shown at $size anyway."
        else "elements[${t.index}]: size $size is above the provider's maximum ${r.limitW}×${r.limitH}. Shown at $size anyway."
    }

    /** Cells a provider dimension (dp) needs on a grid whose cell is [cellDp] wide or tall. */
    fun cells(dp: Float, cellDp: Float): Int = if (cellDp <= 0f) 1 else maxOf(1, kotlin.math.ceil((dp - 0.5f) / cellDp).toInt())

    /** Cells that fit in a maximum dimension (dp). */
    fun cellsFit(dp: Float, cellDp: Float): Int = if (cellDp <= 0f) 1 else maxOf(1, kotlin.math.floor((dp + 0.5f) / cellDp).toInt())
}

/** #300: what [dev.nasawa.configlauncher.store.ConfigEngine] adds to status.json for hosted widgets. */
interface AppWidgetStatus {
    fun states(c: LauncherConfig): Map<String, String>
    fun warnings(c: LauncherConfig): List<String>
}
