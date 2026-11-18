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
    private val configures = mutableListOf<Pair<android.app.Activity, Int>>()
    private fun runtime(bindings: WidgetBindings) = WidgetRuntime(app, WidgetHost(app), bindings, installedProviders = { installed },
        background = { it.run() }, startConfigure = { a, id, _ -> configures += a to id })
    private fun WidgetRuntime.state() = tiles(cfg).single().state
    /** What AppWidgetService does once the reinstalled provider is registered (while home listens). */
    private fun providersChanged(h: WidgetHost) =
        AppWidgetHost::class.java.getDeclaredMethod("onProvidersChanged").apply { isAccessible = true }.invoke(h.host)

    @Test fun reinstalledProviderLeavesNotInstalledFromTheHostCallbackAlone() {
        val host = FakeHost(6, 9)                         // 9: another widget on home, so the host list isn't empty
        val b = WidgetBindings(tmp.newFolder(), host).apply {
            put("w-search", WidgetBindings.Binding(6, WidgetBindings.Provider(search.provider.packageName, ".SearchWidgetProvider"), "personal", WidgetBindings.State.BOUND))
            put("w-other", WidgetBindings.Binding(9, WidgetBindings.Provider("com.other", ".W"), "personal", WidgetBindings.State.BOUND))
        }
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

    // #310 review (blocking): the bind result arrives before onResume (MainActivity.onPause cleared `activity`), and
    // the configure screen must still start, from the activity that received the result.
    @Test fun configureStartsFromTheResultActivityWhileDetached() {
        val gallery = AppWidgetProviderInfo().apply {
            provider = ComponentName("com.android.gallery3d", "com.android.gallery3d.gadget.PhotoAppWidgetProvider")
            configure = ComponentName("com.android.gallery3d", "com.android.gallery3d.gadget.WidgetConfigure")
            // `profile` reads providerInfo's uid; Robolectric leaves it unset.
            org.robolectric.util.ReflectionHelpers.setField(this, "providerInfo", android.content.pm.ActivityInfo().apply {
                packageName = "com.android.gallery3d"; applicationInfo = android.content.pm.ApplicationInfo().apply { uid = android.os.Process.myUid() }
            })
        }
        installed = listOf(gallery)
        org.robolectric.Shadows.shadowOf(android.appwidget.AppWidgetManager.getInstance(app)).setAllowedToBindAppWidgets(false)
        val host = FakeHost()
        val r = runtime(WidgetBindings(tmp.newFolder(), host))
        r.refresh()
        dev.nasawa.configlauncher.settings.Prefs(app).widgetExplained = setOf(gallery.provider.packageName)
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        r.activity = activity; r.flow.resumed = true
        assertNotNull("target", r.info(gallery.provider.packageName, ".gadget.PhotoAppWidgetProvider"))
        val tapped = r.flow.tap("w-g", gallery.provider.packageName, ".gadget.PhotoAppWidgetProvider")
        assertTrue("tap: pending=${r.flow.pending.value} hint=${r.flow.hint("w-g")} bound=${r.bindings!!.all()}", tapped)
        val id = r.flow.pending.value!!.appWidgetId
        assertEquals(BindFlow.Step.BIND, r.flow.pending.value!!.step)
        // The bind dialog covers home: onPause.
        r.flow.resumed = false; r.activity = null
        // Allow (CREATE) without "Always": the result is delivered before onResume.
        r.onActivityResult(activity, WidgetRuntime.REQ_BIND, android.app.Activity.RESULT_OK)
        assertEquals(listOf(activity to id), configures)
        assertEquals(BindFlow.Step.CONFIGURE, r.flow.pending.value!!.step)
        assertNull("not Tap to finish setup", r.flow.hint("w-g"))
        r.onActivityResult(activity, WidgetRuntime.REQ_CONFIGURE, android.app.Activity.RESULT_OK)
        assertEquals(id, r.bindings!!.binding("w-g")!!.appWidgetId)
    }

    // #310 review (e): the debug provider file is written only once the grid has a cell size, so sizes aren't null.
    @Test fun debugProvidersWaitForTheCellSize() {
        installed = listOf(AppWidgetProviderInfo().apply { provider = search.provider; minWidth = 200; minHeight = 40 })
        val r = runtime(WidgetBindings(tmp.newFolder(), FakeHost()))
        r.refresh()
        assertNull(r.debugProviders())
        r.setCell(80f, 100f)
        val p = kotlinx.serialization.json.Json.parseToJsonElement(String(r.debugProviders()!!)).let { it as kotlinx.serialization.json.JsonObject }["providers"]!!
            .let { (it as kotlinx.serialization.json.JsonArray).single() as kotlinx.serialization.json.JsonObject }
        assertNotEquals(kotlinx.serialization.json.JsonNull, p["minW"])
        assertNotEquals(kotlinx.serialization.json.JsonNull, p["minH"])
    }

    // Grace's #309 repro with a single widget: the host list goes empty, so (a) skips the prune, and the grid's
    // view check still sends the stale ID to `Tap to set up` after the reinstall.
    @Test fun onlyWidgetsDeletedIdStillRecoversThroughTheViewCheck() {
        val host = FakeHost(6)
        val b = WidgetBindings(tmp.newFolder(), host).apply { put("w-search", WidgetBindings.Binding(6, WidgetBindings.Provider(search.provider.packageName, ".SearchWidgetProvider"), "personal", WidgetBindings.State.BOUND)) }
        installed = listOf(search)
        val r = runtime(b)
        r.refresh()
        installed = emptyList(); host.alive -= 6
        r.refresh()
        assertEquals(State.NOT_INSTALLED, r.state())
        installed = listOf(search)
        providersChanged(r.host)
        r.viewMissing("w-search", 6)                     // what HostedWidgetCell does when the view can't be made
        assertEquals(State.NEEDS_SETUP, r.state())
    }
}
