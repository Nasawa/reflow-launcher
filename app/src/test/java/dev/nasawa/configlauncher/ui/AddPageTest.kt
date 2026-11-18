package dev.nasawa.configlauncher.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.ConfigLauncherApp
import dev.nasawa.configlauncher.MainActivity
import dev.nasawa.configlauncher.R
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

/** #329 ux.md §4.2 (Pragma, #322): `+ Page` in edit mode adds an empty page after the current one, writes nothing. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xhdpi")
class AddPageTest {
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
                val pages = d.getAnnotation(Pages::class.java)?.n
                f.writeText(if (pages == null) javaClass.getResource("/pager/pages3.json")!!.readText() else
                    """{"schemaVersion":1,"grid":{"columns":4,"rows":5},"pages":[""" +
                        (1..pages).joinToString(",") { """{"id":"p$it","items":[{"id":"i$it","type":"app","package":"com.android.settings","label":"QA Settings","x":0,"y":0}]}""" } +
                        """],"dock":[]}""")
                app.store.drafts.value = emptyList()
                kotlinx.coroutines.runBlocking { app.store.engine.poll(force = true) }
                base.evaluate()
            }
        }
    }
    @Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class Pages(val n: Int)

    private fun shown(text: String): Boolean { compose.waitForIdle(); return compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    private fun click(text: String) = compose.onAllNodes(hasText(text)).onFirst().performClick()
    private fun enterEdit() {
        compose.waitForIdle()
        // Landscape tiles may drop their label text, so match TalkBack's description too.
        compose.onAllNodes(hasText("QA Settings") or androidx.compose.ui.test.hasContentDescription("QA Settings", substring = true)).onFirst()
            .performTouchInput { longClick() }
        click(app.getString(R.string.action_edit_layout))
        assertTrue("edit mode", shown(app.getString(R.string.done)))
    }

    @Test fun addsAnEmptyPageAfterTheCurrentOneAndWritesNothing() {
        val before = file.readText()
        enterEdit()
        compose.onNodeWithContentDescription(app.getString(R.string.edit_add_page_a11y)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("home", "page-1", "second", "third"), app.store.shownConfig().pages.map { it.id })
        assertTrue("moved to the new page", compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(app.getString(R.string.page_of, 2, 4)))
            .fetchSemanticsNodes().isNotEmpty())
        assertEquals("still editing", true, shown(app.getString(R.string.done)))
        assertEquals("nothing written", before, file.readText())
        // Done with nothing on it: the page just goes, still with no write.
        click(app.getString(R.string.done))
        compose.waitForIdle()
        assertEquals(listOf("home", "second", "third"), app.store.shownConfig().pages.map { it.id })
        assertEquals(before, file.readText())
    }

    @Test fun theFirstEditOntoTheNewPageIsOneWriteThatIncludesIt() {
        enterEdit()
        compose.onNodeWithContentDescription(app.getString(R.string.edit_add_page_a11y)).performClick()
        compose.waitForIdle()
        kotlinx.coroutines.runBlocking {
            assertTrue(app.store.tryEdit(dev.nasawa.configlauncher.model.Edit.MoveToCell("a-settings", "page-1", 1, 1)))
            app.store.engine.retrySave()
        }
        val c = (dev.nasawa.configlauncher.config.ConfigCodec.decode(file.readText()) as dev.nasawa.configlauncher.config.ParseResult.Ok).config
        assertEquals(listOf("home", "page-1", "second", "third"), c.pages.map { it.id })
        assertEquals(listOf("a-settings"), c.pages[1].items.map { it.id })
        assertEquals("one write", 2L, c.revision)
        assertTrue("written, so no longer a draft", app.store.drafts.value.isEmpty())
    }

    @Config(qualifiers = "w891dp-h411dp-land-xhdpi")
    @Test fun railMenuHasAddPageRightAfterAddWidget() {
        enterEdit()
        click("⋮")
        compose.waitForIdle()
        val widget = compose.onAllNodes(hasText(app.getString(R.string.action_add_widget))).fetchSemanticsNodes().single().boundsInRoot
        val page = compose.onAllNodes(hasText(app.getString(R.string.edit_add_page))).fetchSemanticsNodes().single().boundsInRoot
        assertTrue("+ Page directly below + Widget: $widget / $page", page.top >= widget.bottom - 1f && page.top - widget.bottom < widget.height)
    }

    @Pages(20)
    @Test fun atTwentyPagesItSaysHomeIsFull() {
        enterEdit()
        compose.onNodeWithContentDescription(app.getString(R.string.edit_add_page_a11y)).performClick()
        assertTrue(shown(app.getString(R.string.home_full)))
        assertEquals(20, app.store.shownConfig().pages.size)
        assertTrue(app.store.drafts.value.isEmpty())
    }
}
