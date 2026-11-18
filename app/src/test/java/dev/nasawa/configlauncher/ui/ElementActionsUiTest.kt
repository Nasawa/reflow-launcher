package dev.nasawa.configlauncher.ui

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.Elements
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.hosted.HostedWidgets
import dev.nasawa.configlauncher.hosted.LongPressHostView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #303 NW-6: §16.7.3 TalkBack actions on hosted widgets, the sheet's Remove, and the Move to… / Resize… dialogs. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w320dp-h711dp-xhdpi")
class ElementActionsUiTest {
    @get:Rule val rule = createComposeRule()

    private fun tile(state: HostedWidgets.State) =
        HostedWidgets.Tile("w-1", 0, Elements.Placement("home", 0, 0, 2, 1), "Search", "com.example", ".W", "personal", state, 6, null)
    private fun actions(log: MutableList<String>) = listOf("Resize…", "Move to…", "Remove from home").map { l -> CustomAccessibilityAction(l) { log += l; true } }
    private fun cfg(s: String) = (ConfigCodec.decode(s) as? ParseResult.Ok)?.config ?: error("invalid")

    @Test fun liveHostedWidgetOffersTheCustomActionsToTalkBack() {
        var view: LongPressHostView? = null
        val log = mutableListOf<String>()
        rule.setContent { MaterialTheme { Box(Modifier.size(200.dp)) {
            HostedWidgetCell(tile(HostedWidgets.State.BOUND), null, 160f, 80f, onLong = {}, onTap = null, actions = actions(log),
                makeView = { c, _ -> LongPressHostView(c).also { view = it } })
        } } }
        rule.waitForIdle()
        val info = view!!.createAccessibilityNodeInfo()
        val custom = info.actionList.filter { it.label != null }.map { it.label.toString() }
        assertEquals(listOf("Resize…", "Move to…", "Remove from home"), custom)
        val remove = info.actionList.first { it.label?.toString() == "Remove from home" }
        assertTrue(view!!.performAccessibilityAction(remove.id, null))
        assertEquals(listOf("Remove from home"), log)
        assertNotEquals(AccessibilityNodeInfo.ACTION_LONG_CLICK, remove.id)
    }

    @Test fun placeholderOffersTheSameActions() {
        val log = mutableListOf<String>()
        rule.setContent { MaterialTheme { Box(Modifier.size(200.dp)) {
            HostedWidgetCell(tile(HostedWidgets.State.NEEDS_SETUP), null, 160f, 80f, onLong = {}, onTap = {}, actions = actions(log))
        } } }
        val node = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNode()
        val labels = node.config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Resize…", "Move to…", "Remove from home"), labels)
    }

    @Test fun hostedSheetHasRemoveFromHome() {
        var removed = 0
        rule.setContent { MaterialTheme {
            HostedWidgetSheet(tile(HostedWidgets.State.BOUND), installed = true, onEditLayout = {}, onAppInfo = {}, onRetry = {}, onDismiss = {}, onRemove = { removed++ })
        } }
        rule.onNodeWithText("Remove from home").performClick()
        assertEquals(1, removed)
    }

    @Test fun moveDialogDisablesPagesWithoutRoomAndTakenSpots() {
        // 4×4: a 3×3 widget on home; page two has an app at (1,1), so no 3×3 spot fits there.
        val c = cfg("""{"schemaVersion":1,"grid":{"columns":4,"rows":4},"pages":[{"id":"home","items":[]},{"id":"two","items":[{"id":"x","type":"app","package":"p.x","x":1,"y":1}]}],"elements":[{"id":"w-a","type":"appWidget","requires":5,"page":"home","x":0,"y":0,"w":3,"h":3,"content":{"provider":{"package":"com.example","class":".W"}}}]}""")
        var moved: Triple<String, Int, Int>? = null
        rule.setContent { MaterialTheme { ElementMoveDialog("Clock", c, "w-a", onMove = { p, x, y -> moved = Triple(p, x, y) }, onDismiss = {}) } }
        rule.onNodeWithText("Move").assertIsNotEnabled()          // same place
        rule.onNodeWithContentDescription("Increase: Column 1").performClick()
        rule.onNodeWithText("Column 2").assertExists()
        rule.onNodeWithContentDescription("Increase: Column 2").assertIsNotEnabled()   // a 3-wide rectangle can't start at column 3
        rule.onNodeWithText("Move").assertIsEnabled()
        rule.onNodeWithContentDescription("Increase: Page 1 of 2").performClick()
        rule.onNodeWithText("Page 2 of 2, No room").assertExists()
        rule.onNodeWithText("Move").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Decrease: Page 2 of 2, No room").performClick()
        rule.onNodeWithText("Move").performClick()
        assertEquals(Triple("home", 1, 0), moved)
    }

    @Test fun resizeDialogStepsWithinLimitsAndFreeCells() {
        // 4×4: widget 2×1 at (0,0); an app at (3,0) caps the width at 3.
        val c = cfg("""{"schemaVersion":1,"grid":{"columns":4,"rows":4},"pages":[{"id":"home","items":[{"id":"x","type":"app","package":"p.x","x":3,"y":0}]}],"elements":[{"id":"w-a","type":"appWidget","requires":5,"page":"home","x":0,"y":0,"w":2,"h":1,"content":{"provider":{"package":"com.example","class":".W"}}}]}""")
        var done: Pair<Int, Int>? = null
        rule.setContent { MaterialTheme { ElementResizeDialog("Clock", c, "w-a", ElementInfo.Limits(2, 4, 1, 2), onDone = { w, h -> done = w to h }, onDismiss = {}) } }
        rule.onNodeWithText("Width, 2 of 3").assertExists()
        rule.onNodeWithContentDescription("Decrease: Width, 2 of 3").assertIsNotEnabled()   // provider minimum
        rule.onNodeWithContentDescription("Increase: Width, 2 of 3").performClick()
        rule.onNodeWithContentDescription("Increase: Width, 3 of 3").assertIsNotEnabled()   // the app at (3,0)
        rule.onNodeWithContentDescription("Increase: Height, 1 of 2").performClick()
        rule.onNodeWithText("Height, 2 of 2").assertExists()
        rule.onNodeWithText("Done").performClick()
        assertEquals(3 to 2, done)
    }
}
