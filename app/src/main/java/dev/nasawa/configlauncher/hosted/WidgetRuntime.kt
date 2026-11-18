package dev.nasawa.configlauncher.hosted

import kotlinx.coroutines.flow.update
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
class WidgetRuntime(
    context: Context, val host: WidgetHost, val bindings: WidgetBindings?,
    private val installedProviders: () -> List<AppWidgetProviderInfo> = { host.providers() },
    /** Where provider callbacks run their refresh (never the main thread); tests run it inline. */
    private val background: (Runnable) -> Unit = { REFRESH.execute(it) },
    /** Starts a provider's configure screen from [android.app.Activity]; tests record it. */
    private val startConfigure: (android.app.Activity, Int, Int) -> Unit =
        { a, id, req -> host.host.startAppWidgetConfigureActivityForResult(a, id, 0, req, null) },
) : AppWidgetStatus {
    private val app = context.applicationContext
    private val density get() = app.resources.displayMetrics.density
    @Volatile private var providers: List<AppWidgetProviderInfo> = emptyList()
    /** Cell width and height (dp) of the main grid as last drawn; null before the first layout. */
    @Volatile var cellDp: Pair<Float, Float>? = null
        private set
    /** Element id → the appWidgetId whose host view failed; a new binding for that element isn't failed. */
    private val failed = ConcurrentHashMap<String, Int>()
    private val _version = MutableStateFlow(0)
    /** Bumps when providers, the cell size or a host-view failure change. */
    val version: StateFlow<Int> = _version

    // #310 review (c): AppWidgetHost calls back on the main thread; the provider read is a binder call, so move it.
    init { host.onProvidersChanged = { background(Runnable { refresh() }) } }

    /**
     * Re-reads the installed providers. Called on home start, on **every** package event and from
     * `AppWidgetHost.onProvidersChanged` (#309 review: the deduplicated app list can fire before Android has
     * re-registered a reinstalled provider). True when the list changed. Also prunes bindings whose ID Android deleted.
     */
    @Synchronized fun refresh(): Boolean {
        bindings?.prune()
        val next = installedProviders()
        val changed = next.map { it.provider } != providers.map { it.provider }
        providers = next
        if (changed) { _version.update { it + 1 }; debug { "providers: ${next.size}" }; flow.retrySilent() }
        return changed
    }

    /** The grid reports its cell size; true when it changed (status warnings depend on it). */
    fun setCell(w: Float, h: Float): Boolean {
        val next = w to h
        if (cellDp == next) return false
        cellDp = next; _version.update { it + 1 }
        return true
    }

    fun markFailed(id: String, appWidgetId: Int) { if (failed.put(id, appWidgetId) != appWidgetId) { _version.update { it + 1 }; debug { "$id: host view failed" } } }
    fun clearFailed(id: String) { if (failed.remove(id) != null) _version.update { it + 1 } }

    /**
     * The grid couldn't make a view for [elementId]'s [appWidgetId]. If Android no longer has that widget (deleted ID,
     * e.g. after a provider uninstall), the binding is dropped → `Tap to set up`; otherwise it's a real failure.
     */
    fun viewMissing(elementId: String, appWidgetId: Int) {
        if (!host.alive(appWidgetId) && bindings != null) { debug { "$elementId: id=$appWidgetId gone" }; bindings.forget(elementId, appWidgetId); clearFailed(elementId) }
        else markFailed(elementId, appWidgetId)
    }

    /** `Can't show this widget` → Retry: re-read providers; a dead or stored-failed binding goes back to setup. */
    fun retry(elementId: String) {
        clearFailed(elementId)
        refresh()
        val b = bindings?.binding(elementId) ?: return
        if (b.state == WidgetBindings.State.FAILED || !host.alive(b.appWidgetId)) bindings.forget(elementId, b.appWidgetId)
    }

    fun info(pkg: String, cls: String): AppWidgetProviderInfo? = WidgetHost.find(providers, pkg, cls)

    val lookup = HostedWidgets.Providers { pkg, cls, profile -> if (profile != "personal") null else info(pkg, cls)?.let(::facts) }

    fun facts(i: AppWidgetProviderInfo): HostedWidgets.Facts {
        val label = runCatching { i.loadLabel(app.packageManager) }.getOrNull()
        val (cw, ch) = cellDp ?: return HostedWidgets.Facts(label)
        val d = density
        val new = Build.VERSION.SDK_INT >= 31
        return HostedWidgets.facts(label, HostedWidgets.ProviderSize(
            resizeW = i.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0,
            resizeH = i.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0,
            minWDp = i.minWidth / d, minHDp = i.minHeight / d,
            minResizeWDp = i.minResizeWidth / d, minResizeHDp = i.minResizeHeight / d,
            maxResizeWDp = if (new) i.maxResizeWidth / d else 0f, maxResizeHDp = if (new) i.maxResizeHeight / d else 0f,
            targetW = if (new) i.targetCellWidth else 0, targetH = if (new) i.targetCellHeight else 0), cw, ch)
    }

    fun tiles(c: LauncherConfig, pageId: String? = null): List<HostedWidgets.Tile> =
        bindings?.all().orEmpty().let { all ->
            HostedWidgets.tiles(c, all, lookup, failed.filter { (e, id) -> all[e]?.appWidgetId == id }.keys, pageId) { id, pkg, cls ->
                flow.hint(id) ?: BindFlow.Hint.BINDING.takeIf { flow.silentPending(id, info(pkg, cls)?.let(::target)) }
            }
        }

    override fun states(c: LauncherConfig) = HostedWidgets.states(tiles(c))
    override fun warnings(c: LauncherConfig) = HostedWidgets.warnings(tiles(c))

    /**
     * Debug builds: `.configlauncher/widget-providers.debug.json`, so an agent can write a correct provider. Null until
     * the grid has reported its cell size (#310 review (e): sizes would be null), so the first write already has them.
     */
    fun debugProviders(): ByteArray? = if (cellDp == null) null else buildJsonObject {
        put("note", "Debug builds only (#300/#301 hands-on). Sizes in cells of this phone's current main grid.")
        put("providers", JsonArray(providers.map { i ->
            val f = facts(i)
            buildJsonObject {
                put("package", i.provider.packageName); put("class", i.provider.className); put("label", f.label)
                put("minW", f.minW); put("minH", f.minH); put("maxW", f.maxW); put("maxH", f.maxH)
                val t = target(i)
                put("configure", if (t.configureRequired) "required" else if (i.configure != null) "optional" else "none")
                put("reconfigurable", t.reconfigurable)
            }
        }))
    }.toString().plus("\n").toByteArray(Charsets.UTF_8)

    /** #302: what Android reports for [i]'s size (dp and `targetCell*`), for [WidgetPlacement.defaultCells]. */
    fun sizes(i: AppWidgetProviderInfo): WidgetPlacement.Sizes {
        val d = density
        val (tw, th) = if (Build.VERSION.SDK_INT >= 31) i.targetCellWidth to i.targetCellHeight else 0 to 0
        return WidgetPlacement.Sizes(i.minWidth / d, i.minHeight / d, tw, th)
    }

    /** #302: [i]'s default cells on [grid] (picker and apps.json); null before the grid has a cell size. */
    fun defaultCells(i: AppWidgetProviderInfo, grid: dev.nasawa.configlauncher.model.Grid): Pair<Int, Int>? {
        val (cw, ch) = cellDp ?: return null
        return WidgetPlacement.defaultCells(sizes(i), cw, ch, grid.columns, grid.rows)
    }

    /** #302 picker rows: every personal provider with its app, label and default size on [grid] (D3: no work ones). */
    /** [sized] false = the grid hasn't reported its cell size yet: [w]×[h] is only a 1×1 stand-in, shown nowhere (#313 nit 3). */
    data class Pick(val pkg: String, val cls: String, val label: String, val app: String, val w: Int, val h: Int, val info: AppWidgetProviderInfo,
                    val sized: Boolean = true)

    fun picks(grid: dev.nasawa.configlauncher.model.Grid): List<Pick> = providers.mapNotNull { i ->
        val cells = defaultCells(i, grid)
        val (w, h) = cells ?: (1 to 1)
        val pkg = i.provider.packageName
        val app = runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        Pick(pkg, i.provider.className, runCatching { i.loadLabel(this.app.packageManager) }.getOrNull() ?: i.provider.shortClassName, app, w, h, i, sized = cells != null)
    }

    /**
     * #302 native-widgets.md §2.4: `apps.json` `widgets[]`, sizes in cells of this phone's current main grid ([grid]).
     * Null until the grid has reported its cell size, so no entry ever has null sizes.
     */
    fun appsEntries(grid: dev.nasawa.configlauncher.model.Grid): List<kotlinx.serialization.json.JsonObject>? {
        if (cellDp == null) return null
        return providers.map { i ->
            val f = facts(i)
            val (w, h) = defaultCells(i, grid)!!
            val t = target(i)
            fun cells(a: Int?, b: Int?) = if (a == null || b == null) kotlinx.serialization.json.JsonNull else buildJsonObject { put("w", a); put("h", b) }
            buildJsonObject {
                put("package", i.provider.packageName); put("class", i.provider.className); put("label", f.label)
                put("profile", "personal")
                put("size", cells(w, h)); put("minSize", cells(f.minW, f.minH)); put("maxSize", cells(f.maxW, f.maxH))
                val rh = i.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
                val rv = i.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
                put("resize", if (rh && rv) "both" else if (rh) "h" else if (rv) "v" else "none")
                put("configure", if (t.configureRequired) "required" else if (i.configure != null) "optional" else "none")
            }
        }.sortedWith(compareBy({ it["package"].toString() }, { it["class"].toString() }))
    }

    /** MainActivity while resumed; bind and configure screens start from it (never from a file load). */
    @Volatile var activity: android.app.Activity? = null

    private val platform = object : BindFlow.Platform {
        override fun target(pkg: String, cls: String) = info(pkg, cls)?.let(::target)
        override fun allocate(): Int = host.host.allocateAppWidgetId()
        override fun bindIfAllowed(appWidgetId: Int, t: BindFlow.Target): Boolean {
            val i = info(t.pkg, t.cls) ?: return false
            val ok = runCatching { host.manager?.bindAppWidgetIdIfAllowed(appWidgetId, i.profile, i.provider, null) == true }.getOrDefault(false)
            debug { "bindIfAllowed ${t.pkg}/${t.cls} id=$appWidgetId -> $ok" }
            return ok
        }
        override fun launchBind(appWidgetId: Int, t: BindFlow.Target): Boolean {
            val a = activity ?: return false
            val i = info(t.pkg, t.cls) ?: return false
            debug { "bind dialog id=$appWidgetId" }
            return runCatching {
                a.startActivityForResult(android.content.Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, i.provider)
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, i.profile), REQ_BIND)
            }.isSuccess
        }
        override fun launchConfigure(appWidgetId: Int): Boolean {
            val a = activity ?: resultActivity ?: return false
            debug { "configure id=$appWidgetId" }
            return runCatching { startConfigure(a, appWidgetId, REQ_CONFIGURE) }
                .onFailure { debug { "configure failed: $it" } }.isSuccess
        }
    }

    fun target(i: AppWidgetProviderInfo): BindFlow.Target {
        val f = if (Build.VERSION.SDK_INT >= 28) i.widgetFeatures else 0
        val hasConfig = i.configure != null
        return BindFlow.Target(i.provider.packageName, i.provider.className, runCatching { i.loadLabel(app.packageManager) }.getOrNull(),
            configureRequired = hasConfig && f and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL == 0,
            reconfigurable = hasConfig && f and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0)
    }

    /** #301: the bind / configure state machine. Explanations shown are remembered in app settings. */
    val flow = BindFlow(platform, bindings, object : BindFlow.Explained {
        private val prefs = dev.nasawa.configlauncher.settings.Prefs(app)
        override fun has(pkg: String) = pkg in prefs.widgetExplained
        override fun add(pkg: String) { prefs.widgetExplained = prefs.widgetExplained + pkg }
    })

    /** D1: silent binds for every unbound, configure-free widget in [c] (only while home is resumed). */
    fun silentBind(c: LauncherConfig) {
        if (!flow.resumed) return
        val unbound = tiles(c).filter { it.state == HostedWidgets.State.NEEDS_SETUP }.map { it.id to info(it.pkg, it.cls)?.let(::target) }
        flow.silentBindAll(unbound)
    }

    /** The activity delivering a result: onActivityResult runs before onResume, while [activity] is still null. */
    @Volatile private var resultActivity: android.app.Activity? = null

    /**
     * MainActivity.onActivityResult. #310 review: the bind result arrives before onResume, so the configure screen
     * that follows is started from [from], the activity that received the result.
     */
    fun onActivityResult(from: android.app.Activity, requestCode: Int, resultCode: Int) {
        val ok = resultCode == android.app.Activity.RESULT_OK
        debug { "result req=$requestCode ok=$ok" }
        resultActivity = from
        try { when (requestCode) { REQ_BIND -> flow.onBindResult(ok); REQ_CONFIGURE -> flow.onConfigureResult(ok) } }
        finally { resultActivity = null }
    }

    companion object {
        const val TAG = "ReflowWidgets"
        const val DEBUG_FILE = "widget-providers.debug.json"
        const val REQ_BIND = 0x5201
        const val REQ_CONFIGURE = 0x5202
        private val REFRESH = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "widget-refresh").apply { isDaemon = true } }
        inline fun debug(msg: () -> String) { if (BuildConfig.DEBUG) Log.d(TAG, msg()) }
    }
}
