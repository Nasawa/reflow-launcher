package dev.nasawa.configlauncher.hosted

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.util.Log
import dev.nasawa.configlauncher.BuildConfig
import dev.nasawa.configlauncher.model.LauncherConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * #300 NW-3: the phone side of hosted widgets. It caches the installed providers (refreshed from the existing
 * `LauncherApps.Callback` path, never on a timer), turns their sizes into cells of the current main grid, and gives
 * the grid its [HostedWidgets.Tile]s and status.json its states and §16.5 warnings.
 *
 * Debug builds only ([BuildConfig.DEBUG]): logcat tag [TAG] and [debugProviders] for the hands-on session.
 */
class WidgetRuntime(context: Context, val host: WidgetHost, val bindings: WidgetBindings?) : AppWidgetStatus {
    private val app = context.applicationContext
    private val density get() = app.resources.displayMetrics.density
    @Volatile private var providers: List<AppWidgetProviderInfo> = emptyList()
    /** Cell width and height (dp) of the main grid as last drawn; null before the first layout. */
    @Volatile var cellDp: Pair<Float, Float>? = null
        private set
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val _version = MutableStateFlow(0)
    /** Bumps when providers, the cell size or a host-view failure change. */
    val version: StateFlow<Int> = _version

    /** Re-reads the installed providers (home start, app installed/removed/changed). True when the list changed. */
    fun refresh(): Boolean {
        val next = host.providers()
        val changed = next.map { it.provider } != providers.map { it.provider }
        providers = next
        if (changed) { _version.value++; debug { "providers: ${next.size}" } }
        return changed
    }

    /** The grid reports its cell size; true when it changed (status warnings depend on it). */
    fun setCell(w: Float, h: Float): Boolean {
        val next = w to h
        if (cellDp == next) return false
        cellDp = next; _version.value++
        return true
    }

    fun markFailed(id: String) { if (failed.add(id)) { _version.value++; debug { "$id: host view failed" } } }
    fun clearFailed(id: String) { if (failed.remove(id)) _version.value++ }

    fun info(pkg: String, cls: String): AppWidgetProviderInfo? = WidgetHost.find(providers, pkg, cls)

    val lookup = HostedWidgets.Providers { pkg, cls, profile -> if (profile != "personal") null else info(pkg, cls)?.let(::facts) }

    fun facts(i: AppWidgetProviderInfo): HostedWidgets.Facts {
        val label = runCatching { i.loadLabel(app.packageManager) }.getOrNull()
        val (cw, ch) = cellDp ?: return HostedWidgets.Facts(label)
        val d = density
        val resH = i.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
        val resV = i.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
        val minW = (if (resH && i.minResizeWidth > 0) i.minResizeWidth else i.minWidth) / d
        val minH = (if (resV && i.minResizeHeight > 0) i.minResizeHeight else i.minHeight) / d
        var maxW: Int? = null; var maxH: Int? = null
        if (Build.VERSION.SDK_INT >= 31) {
            if (i.maxResizeWidth > 0) maxW = HostedWidgets.cellsFit(i.maxResizeWidth / d, cw)
            if (i.maxResizeHeight > 0) maxH = HostedWidgets.cellsFit(i.maxResizeHeight / d, ch)
        }
        val mw = HostedWidgets.cells(minW, cw); val mh = HostedWidgets.cells(minH, ch)
        return HostedWidgets.Facts(label, mw, mh, maxW?.coerceAtLeast(mw), maxH?.coerceAtLeast(mh))
    }

    fun tiles(c: LauncherConfig, pageId: String? = null): List<HostedWidgets.Tile> =
        HostedWidgets.tiles(c, bindings?.all().orEmpty(), lookup, failed, pageId)

    override fun states(c: LauncherConfig) = HostedWidgets.states(tiles(c))
    override fun warnings(c: LauncherConfig) = HostedWidgets.warnings(tiles(c))

    /** Debug builds: `.configlauncher/widget-providers.debug.json`, so an agent can write a correct provider. */
    fun debugProviders(): ByteArray = buildJsonObject {
        put("note", "Debug builds only (#300 hands-on). Sizes in cells of this phone's current main grid.")
        put("providers", JsonArray(providers.map { i ->
            val f = facts(i)
            buildJsonObject {
                put("package", i.provider.packageName); put("class", i.provider.className); put("label", f.label)
                put("minW", f.minW); put("minH", f.minH); put("maxW", f.maxW); put("maxH", f.maxH)
                put("configure", if (i.configure != null) "yes" else "none")
            }
        }))
    }.toString().plus("\n").toByteArray(Charsets.UTF_8)

    /** Debug-only stand-in for NW-4: allocate an ID and try a silent bind. Returns the ID and whether it bound. */
    fun debugTryBind(i: AppWidgetProviderInfo): Pair<Int, Boolean> {
        val id = host.host.allocateAppWidgetId()
        bindings?.beginBind(id)
        val ok = runCatching { host.manager?.bindAppWidgetIdIfAllowed(id, i.profile, i.provider, null) == true }.getOrDefault(false)
        debug { "bind ${i.provider.flattenToShortString()} id=$id silent=$ok" }
        return id to ok
    }

    /** Debug-only: record a bind that Android allowed. */
    fun debugBound(elementId: String, id: Int, pkg: String, cls: String) {
        bindings?.put(elementId, WidgetBindings.Binding(id, WidgetBindings.Provider(pkg, cls), "personal", WidgetBindings.State.BOUND))
        clearFailed(elementId)
        debug { "$elementId bound id=$id" }
    }

    fun debugAbandon(id: Int) { bindings?.abandonBind(id); debug { "bind abandoned id=$id" } }

    companion object {
        const val TAG = "ReflowWidgets"
        const val DEBUG_FILE = "widget-providers.debug.json"
        inline fun debug(msg: () -> String) { if (BuildConfig.DEBUG) Log.d(TAG, msg()) }
    }
}
