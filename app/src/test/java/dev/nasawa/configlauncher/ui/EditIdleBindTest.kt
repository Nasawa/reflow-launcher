package dev.nasawa.configlauncher.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.ConfigLauncherApp
import dev.nasawa.configlauncher.MainActivity
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.hosted.BindFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * #315 GO follow-up (review 5958633795): edit mode's 30 s idle exit is paused while a bind flow step is up (Android's
 * bind dialog, the widget's setup screen, or the §16.3 explanation in front of them), so finishing setup lands back in
 * edit mode. Once the flow ends, a fresh 30 s applies.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xhdpi")
class EditIdleBindTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(Seed()).around(compose)
    private val app get() = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()

    private class Seed : TestRule {
        override fun apply(base: Statement, d: Description) = object : Statement() {
            override fun evaluate() {
                val app = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()
                app.getSharedPreferences("settings", 0).edit().clear().putBoolean("appOnly", true).putBoolean("homePromptShown", true).commit()
                shadowOf(AppWidgetManager.getInstance(app)).addInstalledProvidersForProfile(android.os.Process.myUserHandle(), AppWidgetProviderInfo().apply {
                    provider = ComponentName("com.example.gallery", "com.example.gallery.GalleryWidget")
                    configure = ComponentName("com.example.gallery", "com.example.gallery.Configure")
                    minWidth = 100; minHeight = 100
                    // The hidden `providerInfo` field backs loadLabel and getProfile.
                    AppWidgetProviderInfo::class.java.getField("providerInfo").set(this, android.content.pm.ActivityInfo().apply {
                        packageName = "com.example.gallery"; name = "com.example.gallery.GalleryWidget"
                        applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "com.example.gallery"; uid = android.os.Process.myUid() }
                    })
                })
                val f = File(app.filesDir, "config/source/launcher.json"); f.parentFile!!.mkdirs()
                f.writeText("""{"schemaVersion":1,"revision":1,"grid":{"columns":4,"rows":5},"pages":[{"id":"home","items":[""" +
                    """{"id":"a-settings","type":"app","package":"com.android.settings","label":"QA Settings","x":0,"y":0}]}],""" +
                    """"elements":[{"id":"w-gal","type":"appWidget","requires":5,"page":"home","x":0,"y":2,"w":2,"h":2,""" +
                    """"content":{"provider":{"package":"com.example.gallery","class":".GalleryWidget"}}}]}""")
                kotlinx.coroutines.runBlocking { app.store.engine.poll(force = true) }
                base.evaluate()
            }
        }
    }

    private fun shown(text: String): Boolean { compose.waitForIdle(); return compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    private fun advance(ms: Long) { compose.mainClock.autoAdvance = false; compose.mainClock.advanceTimeBy(ms); compose.mainClock.autoAdvance = true }

    @Test fun bindFlowPausesTheIdleExit() {
        compose.waitForIdle()
        compose.onAllNodes(hasText("QA Settings")).onFirst().performTouchInput { longClick() }
        compose.onAllNodes(hasText(app.getString(R.string.action_edit_layout))).onFirst().performClick()
        assertTrue("edit mode", shown(app.getString(R.string.done)))
        // Tap to set up → the §16.3 explanation, then Continue → Android's bind dialog (startActivityForResult).
        val flow = app.widgets.flow
        compose.runOnUiThread { assertTrue("resumed=${flow.resumed} pending=${flow.pending.value} info=${app.widgets.info("com.example.gallery", ".GalleryWidget")}", flow.tap("w-gal", "com.example.gallery", ".GalleryWidget")) }
        compose.runOnUiThread { flow.explainContinue() }
        compose.waitForIdle()
        assertEquals(BindFlow.Step.BIND, flow.pending.value?.step)
        advance(EditIdle.MS + 5_000)
        assertTrue("still editing behind the bind dialog", shown(app.getString(R.string.done)))
        // Allow → the widget's required setup screen.
        compose.runOnUiThread { flow.onBindResult(true) }
        compose.waitForIdle()
        assertEquals(BindFlow.Step.CONFIGURE, flow.pending.value?.step)
        advance(EditIdle.MS + 5_000)
        assertTrue("still editing behind the setup screen", shown(app.getString(R.string.done)))
        compose.runOnUiThread { flow.onConfigureResult(true) }
        compose.waitForIdle()
        assertEquals(null, flow.pending.value)
        assertTrue(shown(app.getString(R.string.done)))
        // The flow ended: a fresh 30 s applies again.
        advance(EditIdle.MS - 5_000)
        assertTrue("not before 30 s", shown(app.getString(R.string.done)))
        advance(10_000)
        assertFalse(shown(app.getString(R.string.done)))
        assertTrue("left edit mode after 30 s idle", shown(app.getString(R.string.apps)))
    }
}
