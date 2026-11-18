package dev.nasawa.configlauncher.hosted

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.time.Duration

/** #300: a long-press on a live widget is ours (sheet), never the widget's click; a move past slop isn't one. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LongPressHostViewTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun ev(action: Int, x: Float, y: Float) = MotionEvent.obtain(0L, SystemClock.uptimeMillis(), action, x, y, 0)

    @Test fun holdFiresOnceAndInterceptsTheRest() {
        var n = 0
        val v = LongPressHostView(ctx).apply { onLongPress = { n++ } }
        assertFalse(v.onInterceptTouchEvent(ev(MotionEvent.ACTION_DOWN, 10f, 10f)))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(v.timeoutMs + 10))
        assertEquals(1, n)
        // The widget's children get ACTION_CANCEL from here on: its own click can't fire.
        assertTrue(v.onInterceptTouchEvent(ev(MotionEvent.ACTION_MOVE, 11f, 11f)))
        assertTrue(v.onInterceptTouchEvent(ev(MotionEvent.ACTION_UP, 11f, 11f)))
        // The next gesture starts clean.
        assertFalse(v.onInterceptTouchEvent(ev(MotionEvent.ACTION_DOWN, 10f, 10f)))
        v.onInterceptTouchEvent(ev(MotionEvent.ACTION_UP, 10f, 10f))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(v.timeoutMs + 10))
        assertEquals(1, n)
    }

    @Test fun aSwipePastSlopIsNotALongPress() {
        var n = 0
        val v = LongPressHostView(ctx).apply { onLongPress = { n++ } }
        v.onInterceptTouchEvent(ev(MotionEvent.ACTION_DOWN, 10f, 10f))
        assertFalse(v.onInterceptTouchEvent(ev(MotionEvent.ACTION_MOVE, 200f, 12f)))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(v.timeoutMs + 10))
        assertEquals(0, n)
    }
}
