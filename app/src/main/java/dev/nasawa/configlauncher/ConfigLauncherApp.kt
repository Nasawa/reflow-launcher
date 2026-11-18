package dev.nasawa.configlauncher

import android.app.Application
import dev.nasawa.configlauncher.apps.InstalledApps
import dev.nasawa.configlauncher.store.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Holds the manual DI graph (ARCHITECTURE.md §10). */
class ConfigLauncherApp : Application() {
    val scope = CoroutineScope(SupervisorJob())
    val apps by lazy { InstalledApps(this) }
    /** #299: app-scoped, so a rotate or resize rebuilds widget views, not bindings. */
    val widgetHost by lazy { dev.nasawa.configlauncher.hosted.WidgetHost(this) }
    val store by lazy { ConfigStore(this, scope, widgetHost) }
    /** #300: providers, tiles and status for hosted widgets. */
    val widgets by lazy { dev.nasawa.configlauncher.hosted.WidgetRuntime(this, widgetHost, store.engine.bindings).also { store.engine.appWidgets = it } }
}
