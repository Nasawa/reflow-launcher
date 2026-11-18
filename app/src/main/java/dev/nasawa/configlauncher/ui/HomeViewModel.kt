package dev.nasawa.configlauncher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nasawa.configlauncher.ConfigLauncherApp
import dev.nasawa.configlauncher.model.InstalledApp
import dev.nasawa.configlauncher.model.Reconcile
import dev.nasawa.configlauncher.model.ResolvedLayout
import dev.nasawa.configlauncher.store.LoadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeUi(val layout: ResolvedLayout, val load: LoadState)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as ConfigLauncherApp
    val apps = graph.apps
    val store = graph.store
    /** #300: hosted widgets (providers, tiles, status). */
    val widgets = graph.widgets
    private val installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = installed.asStateFlow()
    /** Home key pressed while home is already in front (MainActivity.onNewIntent): ux.md §1, §4.1. */
    val homePresses = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val ui: StateFlow<HomeUi> = combine(store.state, installed) { load, inst -> HomeUi(Reconcile.resolve(load.config, inst), load) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeUi(Reconcile.resolve(store.state.value.config, emptyList()), store.state.value))

    /** Home start: the host wasn't listening while stopped, so provider changes may have been missed. */
    fun refreshWidgets() { viewModelScope.launch(Dispatchers.IO) { widgets.refresh() } }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            installed.value = apps.load()
            // #309 review: providers refresh on every package event, not on the deduplicated list.
            apps.changes().collect { installed.value = apps.load(); widgets.refresh() }
        }
        // #54: keep .configlauncher/apps.json current on app changes and after a source switch;
        // #72: also when drawer.hidden or the Include hidden apps setting changes.
        viewModelScope.launch(Dispatchers.IO) {
            combine(installed, store.state.map { it.sourceName to it.config.drawer?.hidden }.distinctUntilChanged(), store.includeHiddenApps) { inst, _, incl -> inst to incl }
                .filter { it.first.isNotEmpty() }
                .collect { (inst, incl) -> store.engine.writeApps(inst, includeHidden = incl) }
        }
        // #300: providers come and go with packages (every LauncherApps event above, AppWidgetHost.onProvidersChanged
        // and home start), never on a timer.
        // Any provider, binding or cell-size change refreshes status.json `appWidgets` (deduplicated by the engine).
        viewModelScope.launch(Dispatchers.IO) { widgets.refresh() }
        viewModelScope.launch(Dispatchers.IO) {
            combine(widgets.version, widgets.bindings?.version ?: flowOf(0)) { a, b -> a to b }.collect {
                store.engine.appWidgetsChanged()
                // Debug builds only: the providers this phone has, in cells, for the hands-on session.
                if (dev.nasawa.configlauncher.BuildConfig.DEBUG) widgets.debugProviders()?.let { store.engine.writeDebugSidecar(dev.nasawa.configlauncher.hosted.WidgetRuntime.DEBUG_FILE, it) }
            }
        }
    }
}
