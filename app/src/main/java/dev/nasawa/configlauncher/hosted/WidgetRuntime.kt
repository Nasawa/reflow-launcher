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
        bindings?.prune()
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
        HostedWidgets.tiles(c, bindings?.all().orEmpty(), lookup, failed, pageId) { id, pkg, cls ->
            flow.hint(id) ?: BindFlow.Hint.BINDING.takeIf { flow.silentPending(id, info(pkg, cls)?.let(::target)) }
        }

    override fun states(c: LauncherConfig) = HostedWidgets.states(tiles(c))
    override fun warnings(c: LauncherConfig) = HostedWidgets.warnings(tiles(c))

    /** Debug builds: `.configlauncher/widget-providers.debug.json`, so an agent can write a correct provider. */
    fun debugProviders(): ByteArray = buildJsonObject {
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
            val a = activity ?: return false
            debug { "configure id=$appWidgetId" }
            return runCatching { host.host.startAppWidgetConfigureActivityForResult(a, appWidgetId, 0, REQ_CONFIGURE, null) }
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

    /** MainActivity.onActivityResult. */
    fun onActivityResult(requestCode: Int, resultCode: Int) {
        val ok = resultCode == android.app.Activity.RESULT_OK
        debug { "result req=$requestCode ok=$ok" }
        when (requestCode) { REQ_BIND -> flow.onBindResult(ok); REQ_CONFIGURE -> flow.onConfigureResult(ok) }
    }

    companion object {
        const val TAG = "ReflowWidgets"
        const val DEBUG_FILE = "widget-providers.debug.json"
        const val REQ_BIND = 0x5201
        const val REQ_CONFIGURE = 0x5202
        inline fun debug(msg: () -> String) { if (BuildConfig.DEBUG) Log.d(TAG, msg()) }
    }
}
