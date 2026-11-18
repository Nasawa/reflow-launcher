package dev.nasawa.configlauncher.hosted

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * #300: an `AppWidgetHostView` whose long-press is ours (like Launcher3's CheckLongPressHelper): after the system
 * long-press timeout with the finger still within touch slop, [onLongPress] runs and the widget's children get
 * ACTION_CANCEL, so the widget's own click never fires. A move past slop cancels the timer, so a horizontal swipe can
 * go to the pager and a scrolling list keeps scrolling.
 */
@android.annotation.SuppressLint("ClickableViewAccessibility")   // taps belong to the widget's own views; ours is the long-press only
class LongPressHostView(context: Context) : AppWidgetHostView(context) {
    var onLongPress: (() -> Unit)? = null

    init {
        // TalkBack / Switch Access: the long-press action opens the same sheet.
        setOnLongClickListener { onLongPress?.invoke(); onLongPress != null }
    }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var fired = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val check = Runnable { fired = true; onLongPress?.invoke() }

    /** For tests: the timeout used (the system long-press timeout). */
    var timeoutMs: Long = ViewConfiguration.getLongPressTimeout().toLong()

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (fired) { if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) reset(); return true }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { reset(); downX = ev.x; downY = ev.y; handler.postDelayed(check, timeoutMs) }
            MotionEvent.ACTION_MOVE -> if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) handler.removeCallbacks(check)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> handler.removeCallbacks(check)
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Reached when no child took the touch (a non-clickable widget) or after we intercepted: keep the stream.
        if (fired) { if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) reset(); return true }
        onInterceptTouchEvent(ev)
        return true
    }

    override fun cancelLongPress() { super.cancelLongPress(); handler.removeCallbacks(check) }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); reset() }

    private fun reset() { handler.removeCallbacks(check); fired = false }
}
