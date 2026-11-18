package dev.nasawa.configlauncher.ui

import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.config.Elements
import dev.nasawa.configlauncher.hosted.HostedWidgets
import dev.nasawa.configlauncher.hosted.LongPressHostView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #309 review nit 3: the capped host size reaches the provider once per distinct size; the cue reaches TalkBack. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w320dp-h711dp-xhdpi")
class HostedWidgetCellTest {
    @get:Rule val rule = createComposeRule()

    private class Recording(ctx: android.content.Context) : LongPressHostView(ctx) {
        val sizes = mutableListOf<List<SizeF>>()
        override fun updateAppWidgetSize(newOptions: Bundle, sizes: List<SizeF>) { this.sizes += sizes }
    }

    private fun tile(range: HostedWidgets.Range? = null) =
        HostedWidgets.Tile("w-1", 0, Elements.Placement("home", 0, 0, 2, 1), "Search", "com.example", ".W", "personal", HostedWidgets.State.BOUND, 6, range)

    @Test fun sizeIsReportedOncePerDistinctSize() {
        var view: Recording? = null
        var w by mutableStateOf(228f)
        var t by mutableStateOf(tile())
        rule.setContent { MaterialTheme { Box(Modifier.size(200.dp)) {
            HostedWidgetCell(t, null, w, 80.8f, onLong = {}, onTap = null, makeView = { c, id -> assertEquals(6, id); Recording(c).also { view = it } })
        } } }
        rule.waitForIdle()
        assertEquals(listOf(listOf(SizeF(228f, 80.8f))), view!!.sizes)
        t = tile(HostedWidgets.Range(true, 3, 1))           // recomposes, same size: not sent again
        rule.waitForIdle()
        assertEquals(1, view!!.sizes.size)
        assertEquals(", size may be wrong", view!!.sizeCue)
        w = 168f
        rule.waitForIdle()
        assertEquals(listOf(SizeF(168f, 80.8f)), view!!.sizes.last())
        assertEquals(2, view!!.sizes.size)
    }
}
