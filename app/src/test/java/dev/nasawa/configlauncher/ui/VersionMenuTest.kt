package dev.nasawa.configlauncher.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.ConfigLauncherApp
import dev.nasawa.configlauncher.MainActivity
import dev.nasawa.configlauncher.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * #255 / #264 review (Grace): the ⋮ menu's `Version <versionName>` line. It must be the APK's versionName, which
 * scripts/version.py stamps from the git tag (the GitHub Release tag without the v). app/build.gradle.kts passes the
 * stamped name to this test as the `reflow.versionName` system property, so the line is checked against the same
 * value Gradle put in the manifest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xhdpi")
class VersionMenuTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(Seed()).around(compose)

    private val app get() = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()

    /** Same seed as ShowSourceTest: app-only storage, the 3-page fixture, no Home prompt. Optionally fakes versionName. */
    private class Seed : TestRule {
        override fun apply(base: Statement, d: Description) = object : Statement() {
            override fun evaluate() {
                val app = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()
                d.getAnnotation(FakeVersionName::class.java)?.let {
                    shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).versionName = it.value
                }
                app.getSharedPreferences("settings", 0).edit().clear().putBoolean("appOnly", true).putBoolean("homePromptShown", true).commit()
                val f = File(app.filesDir, "config/source/launcher.json"); f.parentFile!!.mkdirs()
                f.writeText(javaClass.getResource("/pager/pages3.json")!!.readText())
                kotlinx.coroutines.runBlocking { app.store.engine.poll(force = true) }
                base.evaluate()
            }
        }
    }

    @Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION)
    annotation class FakeVersionName(val value: String)

    private fun openMenu() {
        compose.waitForIdle()
        compose.onAllNodes(hasText("⋮")).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun shown(text: String): Boolean {
        compose.waitForIdle()
        return compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun copyIsVersionThenName() {
        assertEquals("Version 1.0.0-rc1", app.getString(R.string.menu_version, "1.0.0-rc1"))
    }

    @Test fun menuShowsTheVersionNameGradleStamped() {
        val stamped = System.getProperty("reflow.versionName")
        requireNotNull(stamped) { "app/build.gradle.kts must pass reflow.versionName to unit tests" }
        assertEquals("the manifest carries version.py's name", stamped, app.packageManager.getPackageInfo(app.packageName, 0).versionName)
        assertFalse("not in the tree before ⋮ opens", shown("Version $stamped"))
        openMenu()
        compose.onNode(hasText("Version $stamped")).assertExists()
    }

    @Test @FakeVersionName("1.0.0-rc1")
    fun menuShowsAReleaseCandidateNameExactly() {
        openMenu()
        compose.onNode(hasText("Version 1.0.0-rc1")).assertExists()
        assertFalse("no v prefix", shown("Version v1.0.0-rc1"))
    }

    @Test @FakeVersionName("1.0.0")
    fun menuShowsAFinalReleaseName() {
        openMenu()
        compose.onNode(hasText("Version 1.0.0")).assertExists()
    }
}
