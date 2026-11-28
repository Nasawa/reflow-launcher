package dev.nasawa.configlauncher.ui

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.apps.InstalledApps
import dev.nasawa.configlauncher.config.Elements
import dev.nasawa.configlauncher.hosted.HostedWidgets
import dev.nasawa.configlauncher.model.AppItem
import dev.nasawa.configlauncher.model.InstalledApp
import dev.nasawa.configlauncher.model.ResolvedItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * SD-3: opening the long-press menu or sheet buzzes once (`HapticFeedbackConstants.LONG_PRESS` through the view).
 * Drag start already has its own buzz (#281); a moved long-press that does not open the menu (#327) buzzes none.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class MenuHapticTest {
    @get:Rule val rule = createComposeRule()
    private val density get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
    private val buzzes = mutableListOf<View>()
    private val app = InstalledApp("com.android.settings", "com.android.settings.Settings", "QA Settings")
    private val item = ResolvedItem(AppItem("a", app.packageName, label = "QA Settings"), app)

    @Before fun hook() { Haptics.menuOpenHook = { buzzes += it } }
    @After fun unhook() { Haptics.menuOpenHook = null; buzzes.clear() }

    private val widgetTile = HostedWidgets.Tile(
        "w-1", 0, Elements.Placement("home", 1, 1, 1, 1), "Weather",
        "com.example.weather", ".WeatherWidget", "personal", HostedWidgets.State.BOUND, 7, null,
    )

    @Test fun anAppLongPressOpensTheMenuWithOneHaptic() {
        var open by mutableStateOf(false)
        val apps = InstalledApps(ApplicationProvider.getApplicationContext())
        val drag = DragController({ error("no drag") }) { }
        rule.setContent {
            MaterialTheme {
                with(drag) { Tile(item, apps, Modifier.tile(item, canDrag = true, onClick = {}, onLongClick = { open = true })) }
                if (open) ItemSheet(item, onAction = {}, onDismiss = { open = false })
            }
        }
        rule.onNodeWithText("QA Settings").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals("one buzz when the app sheet opens", 1, buzzes.size)
        rule.onNodeWithText("Edit layout").assertExists()
    }

    @Test fun aWidgetLongPressOpensTheMenuWithOneHaptic() {
        var open by mutableStateOf(false)
        showElementTarget(onLong = { open = true }) { if (open) HostedWidgetSheet(widgetTile, installed = true,
            onEditLayout = {}, onAppInfo = {}, onRetry = {}, onDismiss = { open = false }, onRemove = {}) }
        stillLongPress()
        assertEquals("one buzz when the widget sheet opens", 1, buzzes.size)
        rule.onNodeWithText("Edit layout").assertExists()
    }

    @Test fun aCardLongPressOpensTheMenuWithOneHaptic() {
        var open by mutableStateOf(false)
        showElementTarget(onLong = { open = true }) { if (open) WidgetItemSheet("Weather", live = true, onAction = {}, onDismiss = { open = false }) }
        stillLongPress()
        assertEquals("one buzz when the card sheet opens", 1, buzzes.size)
        rule.onNodeWithText("Edit layout").assertExists()
    }

    /** #327 / dev1 (c): a long-press that moved does not open the menu, so it must not buzz. */
    @Test fun aMovedLongPressDoesNotOpenTheMenuAndDoesNotBuzz() {
        var open by mutableStateOf(false)
        showElementTarget(onLong = { open = true }) { if (open) WidgetItemSheet("Weather", live = true, onAction = {}, onDismiss = {}) }
        rule.onNodeWithTag("area").performTouchInput {
            val start = Offset(150f * density, 150f * density)
            down(start); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            // out 1.6 cells and back, as in ElementDragTargetTest
            repeat(10) { moveBy(Offset(16f * density, 0f)) }
            repeat(10) { moveBy(Offset(-16f * density, 0f)) }
            up()
        }
        rule.waitForIdle()
        assertEquals(false, open)
        assertEquals("no menu haptic when the menu does not open", 0, buzzes.size)
    }

    @Test fun aDragStartDoesNotBuzzTheMenuHaptic() {
        var open by mutableStateOf(false)
        showElementTarget(onLong = { open = true }) { if (open) WidgetItemSheet("Weather", live = true, onAction = {}, onDismiss = {}) }
        rule.onNodeWithTag("area").performTouchInput {
            val start = Offset(150f * density, 150f * density)
            down(start); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(10) { moveBy(Offset(0f, 21f * density)) } // into another cell: a drag, not a menu
            up()
        }
        rule.waitForIdle()
        assertEquals(false, open)
        assertEquals("drag start already buzzes on its own path; the menu haptic stays quiet", 0, buzzes.size)
    }

    private fun showElementTarget(onLong: () -> Unit, sheet: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp, 500.dp).testTag("area")) {
                    ElementDragTarget(Elements.Placement("home", 1, 1, 1, 1), 100.dp, 100.dp, 0.dp, 0.dp, rtl = false, selected = false,
                        canDrop = { _, _, _ -> true }, onDrop = { _, _, _ -> }, onLong = onLong)
                }
                sheet()
            }
        }
    }

    private fun stillLongPress() {
        rule.onNodeWithTag("area").performTouchInput {
            val start = Offset(150f * density, 150f * density)
            down(start); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(1f * density, 1f * density)) // within slop
            up()
        }
        rule.waitForIdle()
    }
}
