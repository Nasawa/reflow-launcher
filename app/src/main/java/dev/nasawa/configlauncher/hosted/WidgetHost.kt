package dev.nasawa.configlauncher.hosted

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Process

/**
 * #299 NW-2: the app-scoped `AppWidgetHost` (native-widgets.md §2.3, §2.5), owned by `ConfigLauncherApp` so an
 * activity recreation (rotate, fold, resize: no `configChanges`) rebuilds views, not bindings. Listens only while
 * home is visible ([start] on `onStart`, [stop] on `onStop`); providers push updates, so there is no polling.
 * Never asks for `BIND_APPWIDGET` or `QUERY_ALL_PACKAGES`: the manifest's `APPWIDGET_UPDATE` `<queries>` entry makes
 * providers visible on Android 11+.
 */
class WidgetHost(context: Context) : WidgetBindings.Host {
    private val app = context.applicationContext
    val host: AppWidgetHost = object : AppWidgetHost(app, HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): android.appwidget.AppWidgetHostView = LongPressHostView(context)
    }
    val manager get() = mgr
    private val mgr: AppWidgetManager? = AppWidgetManager.getInstance(app)

    /** Our host view for a bound [appWidgetId], or null when the provider is gone. */
    fun createView(context: Context, appWidgetId: Int): LongPressHostView? = runCatching {
        val info = mgr?.getAppWidgetInfo(appWidgetId) ?: return null
        host.createView(context, appWidgetId, info) as? LongPressHostView
    }.getOrNull()

    fun start() { runCatching { host.startListening() } }
    fun stop() { runCatching { host.stopListening() } }

    override fun ids(): IntArray = host.appWidgetIds
    override fun delete(appWidgetId: Int) { runCatching { host.deleteAppWidgetId(appWidgetId) } }

    /**
     * Installed providers for [profile]. Only `personal` is hosted in this milestone (D3): `work` returns none, so a
     * work widget stays a `Work widgets come later` placeholder.
     */
    fun providers(profile: String = "personal"): List<AppWidgetProviderInfo> =
        if (profile != "personal") emptyList()
        else runCatching { mgr?.getInstalledProvidersForProfile(Process.myUserHandle()) }.getOrNull().orEmpty()

    /** The provider named by an element's `content.provider` (a class starting with "." is relative to the package). */
    fun provider(pkg: String, cls: String, profile: String = "personal"): AppWidgetProviderInfo? = find(providers(profile), pkg, cls)

    companion object {
        /** Fixed, so IDs survive process death ("RF"). */
        const val HOST_ID = 0x5246
        fun find(list: List<AppWidgetProviderInfo>, pkg: String, cls: String): AppWidgetProviderInfo? {
            val want = ComponentName(pkg, WidgetBindings.resolve(pkg, cls))
            return list.firstOrNull { it.provider == want }
        }
    }
}
