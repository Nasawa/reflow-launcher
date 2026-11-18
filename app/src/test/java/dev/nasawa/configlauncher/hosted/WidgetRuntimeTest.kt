package dev.nasawa.configlauncher.hosted

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.hosted.HostedWidgets.State
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #309 review (blocking): a provider reinstall flips the tile live, and a deleted ID goes back to `Tap to set up`. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WidgetRuntimeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** Our host's allocated IDs as Android sees them. */
    private class FakeHost(vararg ids: Int) : WidgetBindings.Host {
        val alive = ids.toMutableSet()
        override fun ids() = alive.toIntArray()
        override fun delete(appWidgetId: Int) { alive -= appWidgetId }
    }

    private val search = AppWidgetProviderInfo().apply { provider = ComponentName("com.android.quicksearchbox", "com.android.quicksearchbox.SearchWidgetProvider") }
    private val cfg = (ConfigCodec.decode("""{"schemaVersion":1,"pages":[{"id":"home","items":[]}],"elements":[{"id":"w-search","type":"appWidget","requires":5,"page":"home","x":0,"y":0,"w":3,"h":1,"content":{"provider":{"package":"com.android.quicksearchbox","class":".SearchWidgetProvider"}}}]}""") as ParseResult.Ok).config

    private var installed = listOf<AppWidgetProviderInfo>()
    private fun runtime(bindings: WidgetBindings) = WidgetRuntime(app, WidgetHost(app), bindings) { installed }
    private fun WidgetRuntime.state() = tiles(cfg).single().state
    /** What AppWidgetService does once the reinstalled provider is registered (while home listens). */
    private fun providersChanged(h: WidgetHost) =
        AppWidgetHost::class.java.getDeclaredMethod("onProvidersChanged").apply { isAccessible = true }.invoke(h.host)

    @Test fun reinstalledProviderLeavesNotInstalledFromTheHostCallbackAlone() {
        val host = FakeHost(6)
        val b = WidgetBindings(tmp.newFolder(), host).apply { put("w-search", WidgetBindings.Binding(6, WidgetBindings.Provider(search.provider.packageName, ".SearchWidgetProvider"), "personal", WidgetBindings.State.BOUND)) }
        installed = listOf(search)
        val r = runtime(b)
        r.refresh()
        assertEquals(State.BOUND, r.state())
        // `pm uninstall -k`: the provider is gone and Android deletes ID 6.
        installed = emptyList(); host.alive -= 6
        r.refresh()
        assertEquals(State.NOT_INSTALLED, r.state())
        assertNull("deleted ID pruned", b.binding("w-search"))
        // `install-existing`: no app-list event reaches us; only AppWidgetHost.onProvidersChanged.
        installed = listOf(search)
        val v = r.version.value
        providersChanged(r.host)
        assertTrue(r.version.value > v)
        assertEquals(State.NEEDS_SETUP, r.state())
        // Recovers: a new bind shows live.
        host.alive += 7
        b.put("w-search", WidgetBindings.Binding(7, WidgetBindings.Provider(search.provider.packageName, ".SearchWidgetProvider"), "personal", WidgetBindings.State.BOUND))
        assertEquals(State.BOUND, r.state())
    }

    @Test fun staleIdShowsNeedsSetupNotCantShowAndRecovers() {
        val host = FakeHost(6)
        val b = WidgetBindings(tmp.newFolder(), host).apply { put("w-search", WidgetBindings.Binding(6, WidgetBindings.Provider(search.provider.packageName, ".SearchWidgetProvider"), "personal", WidgetBindings.State.BOUND)) }
        installed = listOf(search)
        val r = runtime(b)
        r.refresh()
        // The grid can't make a view: Android has no widget for ID 6 (getAppWidgetInfo is null) → setup, not failed.
        r.viewMissing("w-search", 6)
        assertEquals(State.NEEDS_SETUP, r.state())
        assertNull(b.binding("w-search"))
        assertFalse("its ID is freed", 6 in host.alive)
        // A real failure on a later binding is `failed`; a new binding for the element isn't.
        host.alive += 7
        b.put("w-search", WidgetBindings.Binding(7, WidgetBindings.Provider(search.provider.packageName, ".SearchWidgetProvider"), "personal", WidgetBindings.State.BOUND))
        r.markFailed("w-search", 7)
        assertEquals(State.FAILED, r.state())
        host.alive += 8
        b.put("w-search", WidgetBindings.Binding(8, WidgetBindings.Provider(search.provider.packageName, ".SearchWidgetProvider"), "personal", WidgetBindings.State.BOUND))
        assertEquals("the failure was ID 7's", State.BOUND, r.state())
    }

    // #309 review nit 5: a binding stored as `failed` recovers through Retry (→ `Tap to set up`).
    @Test fun retryRecoversAStoredFailedBinding() {
        val host = FakeHost(6)
        val b = WidgetBindings(tmp.newFolder(), host).apply { put("w-search", WidgetBindings.Binding(6, WidgetBindings.Provider(search.provider.packageName, ".SearchWidgetProvider"), "personal", WidgetBindings.State.FAILED)) }
        installed = listOf(search)
        val r = runtime(b)
        r.refresh()
        assertEquals(State.FAILED, r.state())
        r.retry("w-search")
        assertEquals(State.NEEDS_SETUP, r.state())
        assertTrue(host.alive.isEmpty())
    }
}
