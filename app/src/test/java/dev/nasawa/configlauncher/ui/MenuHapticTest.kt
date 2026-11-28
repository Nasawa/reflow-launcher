package dev.nasawa.configlauncher.ui

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
 * One buzz per gesture: if pickup already buzzed, the sheet does not add a second. A moved long-press that
 * does not open the menu (#327) keeps only the pickup buzz.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class MenuHapticTest {
    @get:Rule val rule = createComposeRule()
    private val density get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
    private val buzzes = mutableListOf<View>()
    private val app = InstalledApp("com.android.settings", "com.android.settings.Settings", "QA Settings")
    private val item = ResolvedItem(AppItem("a", app.packageName, label = "QA Settings"), app)

    @Before fun hook() { Haptics.beginGesture(); Haptics.buzzHook = { buzzes += it } }
    @After fun unhook() { Haptics.buzzHook = null; Haptics.beginGesture(); buzzes.clear() }

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

    /** Edit mode: pickup already buzzes at the hold; the sheet must not add a second. */
    @Test fun aStillLongPressOnAWidgetInEditModeBuzzesOnce() {
        var open by mutableStateOf(false)
        showElementTarget(onLong = { open = true }) { if (open) HostedWidgetSheet(widgetTile, installed = true,
            onEditLayout = {}, onAppInfo = {}, onRetry = {}, onDismiss = { open = false }, onRemove = {}) }
        stillLongPress()
        assertEquals(true, open)
        assertEquals("pickup buzz only: the sheet open does not buzz again", 1, buzzes.size)
        rule.onNodeWithText("Edit layout").assertExists()
    }

    @Test fun aStillLongPressOnACardInEditModeBuzzesOnce() {
        var open by mutableStateOf(false)
        showElementTarget(onLong = { open = true }) { if (open) WidgetItemSheet("Weather", live = true, onAction = {}, onDismiss = { open = false }) }
        stillLongPress()
        assertEquals(true, open)
        assertEquals("pickup buzz only: the sheet open does not buzz again", 1, buzzes.size)
        rule.onNodeWithText("Edit layout").assertExists()
    }

    @Test fun aCardSheetWithoutAPickupBuzzesOnceWhenItOpens() {
        rule.setContent { MaterialTheme { WidgetItemSheet("Weather", live = true, onAction = {}, onDismiss = {}) } }
        rule.waitForIdle()
        assertEquals("outside edit mode there is no pickup buzz", 1, buzzes.size)
        rule.onNodeWithText("Edit layout").assertExists()
    }

    /** #327 / dev1 (c): a long-press that moved does not open the menu; only the pickup buzz fires. */
    @Test fun aMovedLongPressDoesNotOpenTheMenuAndDoesNotAddASecondBuzz() {
        var open by mutableStateOf(false)
        showElementTarget(onLong = { open = true }) { if (open) WidgetItemSheet("Weather", live = true, onAction = {}, onDismiss = {}) }
        rule.onNodeWithTag("area").performTouchInput {
            val start = Offset(150f * density, 150f * density)
            down(start); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(10) { moveBy(Offset(16f * density, 0f)) }
            repeat(10) { moveBy(Offset(-16f * density, 0f)) }
            up()
        }
        rule.waitForIdle()
        assertEquals(false, open)
        assertEquals("pickup only; no menu haptic", 1, buzzes.size)
    }

    @Test fun aDragStartDoesNotAddAMenuHaptic() {
        var open by mutableStateOf(false)
        showElementTarget(onLong = { open = true }) { if (open) WidgetItemSheet("Weather", live = true, onAction = {}, onDismiss = {}) }
        rule.onNodeWithTag("area").performTouchInput {
            val start = Offset(150f * density, 150f * density)
            down(start); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(10) { moveBy(Offset(0f, 21f * density)) }
            up()
        }
        rule.waitForIdle()
        assertEquals(false, open)
        assertEquals("drag start buzzes once; the menu path stays quiet", 1, buzzes.size)
    }

    @Test fun theEmptyHomeMenuOpensWithOneHaptic() {
        rule.setContent { MaterialTheme { HomeMenuSheet(3, 0, onAction = {}, onDismiss = {}) } }
        rule.waitForIdle()
        assertEquals("one buzz when the empty-space menu opens", 1, buzzes.size)
        rule.onNodeWithText("Pages").assertExists()
        rule.onNodeWithText("Widgets").assertExists()
        rule.onNodeWithText("Reload layout").assertExists()
    }

    @Test fun aDrawerAppSheetOpensWithOneHaptic() {
        rule.setContent { MaterialTheme { DrawerAppSheet(app, includeWork = false, onAction = {}, onDismiss = {}) } }
        rule.waitForIdle()
        assertEquals("one buzz when the drawer app sheet opens", 1, buzzes.size)
        rule.onNodeWithText("Add to home").assertExists()
    }

    @Test fun aRecompositionWhileTheSheetIsOpenDoesNotBuzzAgain() {
        var tick by mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                Text("tick-$tick")
                // [tick] forces this node to recompose in place (with no inputs Compose would skip it).
                // Every long-press sheet uses this helper, so a SideEffect here would buzz again.
                MenuOpenHaptic(tick)
            }
        }
        rule.waitForIdle()
        assertEquals(1, buzzes.size)
        tick = 1
        rule.waitForIdle()
        rule.onNodeWithText("tick-1").assertExists()
        assertEquals("recomposition of an already-open sheet does not buzz again", 1, buzzes.size)
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
            moveBy(Offset(1f * density, 1f * density))
            up()
        }
        rule.waitForIdle()
    }
}
