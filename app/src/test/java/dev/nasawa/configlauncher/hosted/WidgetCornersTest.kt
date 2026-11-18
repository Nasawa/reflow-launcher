package dev.nasawa.configlauncher.hosted

import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** dev1 fix (b): API 31+ widgets get the launcher's rounded corners at the system radius, like Launcher3. */
@RunWith(AndroidJUnit4::class)
class WidgetCornersTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun host(bgId: Int = View.NO_ID, clip: Boolean = false): Pair<FrameLayout, View> {
        val host = FrameLayout(ctx)
        val content = FrameLayout(ctx).apply { id = bgId; setBackgroundColor(Color.DKGRAY); clipToOutline = clip }
        host.addView(content, FrameLayout.LayoutParams(300, 120))
        host.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 300, 120)
        return host to content
    }

    @Config(sdk = [35])
    @Test fun clipsToTheSystemRadiusOnApi31Plus() {
        val (h, content) = host()
        assertSame(content, WidgetCorners.findBackground(h))
        WidgetCorners.enforce(h)
        assertTrue(h.clipToOutline)
        val o = android.graphics.Outline(); h.outlineProvider.getOutline(h, o)
        val sys = ctx.resources.getDimension(android.R.dimen.system_app_widget_background_radius)
        assertTrue(sys > 0f)
        assertEquals(minOf(sys, 60f), o.radius, 0.01f)
    }

    @Config(sdk = [35])
    @Test fun theHostViewRoundsItselfAfterLayout() {
        val h = LongPressHostView(ctx)
        h.addView(FrameLayout(ctx).apply { setBackgroundColor(Color.DKGRAY) }, FrameLayout.LayoutParams(300, 120))
        h.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY))
        h.layout(0, 0, 300, 120)
        assertTrue(h.clipToOutline)
    }

    @Config(sdk = [35])
    @Test fun aWidgetThatRoundsItsOwnBackgroundIsLeftAlone() {
        val (h, _) = host(bgId = android.R.id.background, clip = true)
        WidgetCorners.enforce(h)
        assertFalse(h.clipToOutline)
    }

    @Config(sdk = [30])
    @Test fun noClipBelowApi31() {
        val (h, _) = host()
        WidgetCorners.enforce(h)
        assertFalse(h.clipToOutline)
    }

    @Test fun radiusIsNeverMoreThanHalfTheShorterSide() {
        assertEquals(28f, WidgetCorners.radius(28f, 300, 120), 0f)
        assertEquals(20f, WidgetCorners.radius(28f, 300, 40), 0f)
        assertEquals(0f, WidgetCorners.radius(0f, 300, 120), 0f)
    }
}
