package dev.nasawa.configlauncher.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.config.Elements
import dev.nasawa.configlauncher.hosted.HostedWidgets
import dev.nasawa.configlauncher.hosted.HostedWidgets.State
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #300: placeholders announce `<Label>, widget, <reason>`; a state with no tap yet offers no click. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w320dp-h711dp-xhdpi")
class HostedPlaceholderTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun tile(state: State, range: HostedWidgets.Range? = null, label: String? = "Weather") =
        HostedWidgets.Tile("w-1", 0, Elements.Placement("home", 0, 0, 2, 2), label, "com.example.weather", ".W", "personal", state, null, range)

    @Test fun everyReasonIsAnnounced() {
        for ((s, reason) in listOf(State.NEEDS_SETUP to "Tap to set up", State.NEEDS_CONFIG to "Tap to finish setup", State.NOT_INSTALLED to "Not installed",
            State.FAILED to "Can't show this widget", State.UNSUPPORTED to "Work widgets come later")) {
            assertEquals(reason, ctx.getString(reasonRes(s)!!))
        }
        assertNull(reasonRes(State.BOUND))
    }

    @Test fun workWidgetHasNoTapButLongPressOpensTheSheet() {
        var longs = 0
        rule.setContent { MaterialTheme { Box(Modifier.size(160.dp)) { HostedPlaceholder(tile(State.UNSUPPORTED), onLong = { longs++ }, onTap = null) } } }
        val n = rule.onNodeWithContentDescription("Weather, widget, Work widgets come later")
        assertFalse(SemanticsActions.OnClick in n.fetchSemanticsNode().config)
        n.performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(1, longs)
    }

    @Test fun tapRunsAndSizeCueIsSpoken() {
        var taps = 0
        rule.setContent { MaterialTheme { Box(Modifier.size(160.dp)) {
            HostedPlaceholder(tile(State.NOT_INSTALLED, HostedWidgets.Range(true, 3, 2), label = null), onLong = {}, onTap = { taps++ })
        } } }
        rule.onNodeWithContentDescription("w-1, widget, Not installed, size may be wrong").performClick()
        assertEquals(1, taps)
    }
}
