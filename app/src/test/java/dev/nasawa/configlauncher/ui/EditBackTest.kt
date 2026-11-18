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
import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import org.junit.Assert.assertEquals
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
 * #315 GO follow-up (review 5958633795): Back in edit mode leaves it the way `Done` does: the layout is saved and
 * empty pages are dropped (ux.md §4.1). Kills the "Back exits without saving" mutant.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xhdpi")
class EditBackTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(Seed()).around(compose)
    private val app get() = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()
    private val file get() = File(app.filesDir, "config/source/launcher.json")

    private class Seed : TestRule {
        override fun apply(base: Statement, d: Description) = object : Statement() {
            override fun evaluate() {
                val app = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()
                app.getSharedPreferences("settings", 0).edit().clear().putBoolean("appOnly", true).putBoolean("homePromptShown", true).commit()
                val f = File(app.filesDir, "config/source/launcher.json"); f.parentFile!!.mkdirs()
                f.writeText("""{"schemaVersion":1,"revision":4,"grid":{"columns":4,"rows":5},"pages":[""" +
                    """{"id":"home","items":[{"id":"a-settings","type":"app","package":"com.android.settings","label":"QA Settings","x":0,"y":0}]},""" +
                    """{"id":"spare","items":[]}]}""")
                kotlinx.coroutines.runBlocking { app.store.engine.poll(force = true) }
                base.evaluate()
            }
        }
    }

    private fun shown(text: String): Boolean { compose.waitForIdle(); return compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    private fun onDisk() = (ConfigCodec.decode(file.readBytes()) as ParseResult.Ok).config

    @Test fun backInEditModeSavesAndDropsEmptyPages() {
        compose.waitForIdle()
        assertEquals(listOf("home", "spare"), onDisk().pages.map { it.id })
        compose.onAllNodes(hasText("QA Settings")).onFirst().performTouchInput { longClick() }
        compose.onAllNodes(hasText(app.getString(R.string.action_edit_layout))).onFirst().performClick()
        assertTrue("edit mode", shown(app.getString(R.string.done)))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue("left edit mode", shown(app.getString(R.string.apps)))
        // The save runs on the store's IO scope: wait for its write (a bound, not a timing assumption).
        compose.waitUntil(5_000) { onDisk().pages.size == 1 }
        assertEquals(listOf("home"), onDisk().pages.map { it.id })
        assertEquals(5L, onDisk().revision)
        assertEquals(listOf("home"), app.store.engine.state.value.config.pages.map { it.id })
    }
}
