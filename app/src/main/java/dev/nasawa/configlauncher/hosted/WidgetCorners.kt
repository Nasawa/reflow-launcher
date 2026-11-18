package dev.nasawa.configlauncher.hosted

import android.graphics.Outline
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider

/**
 * dev1 fix (b): Android 12+ widgets expect the launcher to round them (Launcher3's `RoundedCornerEnforcement`):
 * many providers, Samsung's clock and weather among them, draw a plain background and leave the corners to the host.
 * We clip the host view to an outline around the widget's background view at the system radius
 * (`android.R.dimen.system_app_widget_background_radius`). A widget whose `@android:id/background` already clips to
 * its own outline has opted out and keeps its shape. Below API 31 there is no system radius, so nothing is clipped.
 */
object WidgetCorners {
    /** The widget's background view (Launcher3's rule): the one `@android:id/background`, else the first view down
     *  the single-child chain from the content root that draws a background; null = nothing to round. */
    fun findBackground(host: ViewGroup): View? {
        val ids = mutableListOf<View>()
        fun collect(v: View) { if (v.id == android.R.id.background) ids += v; if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i)) }
        collect(host)
        if (ids.size == 1) return ids[0]
        var cur: View? = host.getChildAt(0) ?: return null
        while (cur != null) {
            if (cur.visibility != View.VISIBLE) return null
            if (cur.background != null) return cur
            cur = (cur as? ViewGroup)?.takeIf { it.childCount == 1 }?.getChildAt(0)
        }
        return null
    }

    /** True when the provider rounds its own background (`@android:id/background` with `clipToOutline`). */
    fun optedOut(bg: View): Boolean = bg.id == android.R.id.background && bg.clipToOutline

    /** [bg]'s bounds in [host]'s coordinates. */
    fun bounds(host: View, bg: View): Rect {
        val r = Rect(0, 0, bg.width, bg.height)
        var v: View = bg
        while (v !== host) {
            r.offset(v.left, v.top)
            v = v.parent as? View ?: break
        }
        return r
    }

    /** The radius to clip at (px), never more than half the shorter side; 0 = no clip. */
    fun radius(systemPx: Float, w: Int, h: Int): Float = if (systemPx <= 0f || w <= 0 || h <= 0) 0f else minOf(systemPx, minOf(w, h) / 2f)

    /** Apply (or clear) the clip on [host]; call after layout. */
    fun enforce(host: ViewGroup) {
        if (Build.VERSION.SDK_INT < 31) return
        val bg = findBackground(host)
        val sys = runCatching { host.resources.getDimension(android.R.dimen.system_app_widget_background_radius) }.getOrDefault(0f)
        if (bg == null || optedOut(bg) || sys <= 0f) { clear(host); return }
        val r = bounds(host, bg)
        val rad = radius(sys, r.width(), r.height())
        if (rad <= 0f) { clear(host); return }
        host.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(r, rad) }
        }
        host.clipToOutline = true
    }

    private fun clear(host: View) {
        if (!host.clipToOutline) return
        host.outlineProvider = ViewOutlineProvider.BACKGROUND
        host.clipToOutline = false
    }
}
