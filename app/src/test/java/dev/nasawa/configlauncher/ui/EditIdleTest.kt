package dev.nasawa.configlauncher.ui

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.annotation.Config
import java.io.File

/**
 * #313 review nit 1: edit mode's 30 s idle exit is paused while the widget picker is open, so browsing it for a while
 * and closing it lands back in edit mode (`Done`), not on `Apps`. After it closes, a fresh 30 s still applies.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xhdpi")
class EditIdleTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(Seed()).around(compose)
    private val app get() = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()

    private class Seed : TestRule {
        override fun apply(base: Statement, d: Description) = object : Statement() {
            override fun evaluate() {
                val app = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()
                app.getSharedPreferences("settings", 0).edit().clear().putBoolean("appOnly", true).putBoolean("homePromptShown", true).commit()
                val f = File(app.filesDir, "config/source/launcher.json"); f.parentFile!!.mkdirs()
                f.writeText(javaClass.getResource("/pager/pages3.json")!!.readText())
                kotlinx.coroutines.runBlocking { app.store.engine.poll(force = true) }
                base.evaluate()
            }
        }
    }

    private fun shown(text: String): Boolean { compose.waitForIdle(); return compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    private fun click(text: String) = compose.onAllNodes(hasText(text)).onFirst().performClick()

    @Test fun pickerOpenPausesTheIdleExit() {
        compose.waitForIdle()
        compose.onAllNodes(hasText("QA Settings")).onFirst().performTouchInput { longClick() }
        click(app.getString(R.string.action_edit_layout))
        assertTrue("edit mode", shown(app.getString(R.string.done)))
        click(app.getString(R.string.action_add_widget))
        assertTrue("picker open", shown(app.getString(R.string.widget_picker_title)))
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(EditIdle.MS + 5_000)
        compose.mainClock.autoAdvance = true
        click(app.getString(R.string.close))
        assertTrue("still editing after a long browse", shown(app.getString(R.string.done)))
        assertFalse(shown(app.getString(R.string.apps)))
        // Nothing open now: the idle exit applies again, from a fresh 30 s.
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(EditIdle.MS - 5_000)
        compose.mainClock.autoAdvance = true
        assertTrue("not before 30 s", shown(app.getString(R.string.done)))
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(10_000)
        compose.mainClock.autoAdvance = true
        assertTrue("left edit mode after 30 s idle", shown(app.getString(R.string.apps)))
    }
}
