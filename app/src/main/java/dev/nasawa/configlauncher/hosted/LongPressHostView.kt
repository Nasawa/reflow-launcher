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
open class LongPressHostView(context: Context) : AppWidgetHostView(context) {
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
    /** ux.md §16.5 (#309 review nit 1): appended to what TalkBack reads, e.g. `, size may be wrong`; null = none. */
    var sizeCue: String? = null

    /** #303 ux.md §16.7.3: TalkBack custom actions (`Resize…`, `Move to…`, `Remove from home`), label → action. */
    var a11yActions: List<Pair<String, () -> Unit>> = emptyList()

    override fun createAccessibilityNodeInfo(): android.view.accessibility.AccessibilityNodeInfo =
        super.createAccessibilityNodeInfo().also { info ->
            sizeCue?.let { cue -> info.contentDescription = (info.contentDescription ?: contentDescription ?: "").toString() + cue }
            a11yActions.take(ACTION_IDS.size).forEachIndexed { i, (label, _) ->
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(ACTION_IDS[i], label))
            }
        }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        val i = ACTION_IDS.indexOf(action)
        if (i >= 0) { a11yActions.getOrNull(i)?.second?.invoke() ?: return false; return true }
        return super.performAccessibilityAction(action, arguments)
    }

    companion object {
        private val ACTION_IDS = intArrayOf(dev.nasawa.configlauncher.R.id.a11y_custom_action_0, dev.nasawa.configlauncher.R.id.a11y_custom_action_1,
            dev.nasawa.configlauncher.R.id.a11y_custom_action_2, dev.nasawa.configlauncher.R.id.a11y_custom_action_3)
    }

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

    /** dev1 fix (b): rounded corners like Launcher3 on API 31+ ([WidgetCorners]); re-checked after every layout. */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        WidgetCorners.enforce(this)
    }

    override fun cancelLongPress() { super.cancelLongPress(); handler.removeCallbacks(check) }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); reset() }

    private fun reset() { handler.removeCallbacks(check); fired = false }
}
