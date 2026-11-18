package dev.nasawa.configlauncher.hosted

import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * #301 NW-4: tap to set up, bind and configure (native-widgets.md §4 D1, §5 NW-4; ux.md §16.2–§16.3). Pure state
 * machine; Android sits behind [Platform]. It writes only the device-local [bindings], never launcher.json.
 *
 * - **D1 silent bind** ([silentBindAll]): while home is resumed, an unbound widget whose provider needs no configure
 *   screen is bound with `bindAppWidgetIdIfAllowed` and no UI. A load never opens a dialog. Until it has been tried
 *   the tile shows only icon and label ([Hint.BINDING]); `Tap to set up` appears only after it failed or isn't allowed.
 * - **Tap** ([tap]): silent bind if allowed → else the §16.3 explanation once per package → Android's bind dialog →
 *   the provider's required configure screen → bound. `Not now` / bind cancel → `Tap to allow`; configure cancel →
 *   `Tap to finish setup`. Every cancel frees the allocated ID, so nothing leaks; each placeholder state taps back
 *   into the same flow.
 * - **Reconfigure** ([reconfigure]): bound + reconfigurable → the configure screen on the same ID; failure keeps
 *   the previous config.
 * One flow at a time; only while home is resumed ([resumed]).
 */
class BindFlow(private val platform: Platform, private val bindings: WidgetBindings?, private val explained: Explained) {
    data class Target(val pkg: String, val cls: String, val label: String?, val configureRequired: Boolean, val reconfigurable: Boolean)

    interface Platform {
        fun target(pkg: String, cls: String): Target?
        fun allocate(): Int
        fun bindIfAllowed(appWidgetId: Int, t: Target): Boolean
        /** Android's `ACTION_APPWIDGET_BIND` dialog; the answer comes back in [onBindResult]. False if it can't start. */
        fun launchBind(appWidgetId: Int, t: Target): Boolean
        /** The provider's configure screen; the answer comes back in [onConfigureResult]. False if it can't start. */
        fun launchConfigure(appWidgetId: Int): Boolean
    }

    interface Explained { fun has(pkg: String): Boolean; fun add(pkg: String) }

    /** What a placeholder says beyond its resolved state. */
    enum class Hint { BINDING, ALLOW, FINISH }

    enum class Step { EXPLAIN, BIND, CONFIGURE, RECONFIGURE }
    data class Pending(val elementId: String, val appWidgetId: Int, val target: Target, val step: Step)

    private val marks = mutableMapOf<String, Hint>()
    private val silentTried = mutableSetOf<String>()
    private val _pending = MutableStateFlow<Pending?>(null)
    /** The flow in progress; [Step.EXPLAIN] means the UI shows the §16.3 sheet. */
    val pending: StateFlow<Pending?> = _pending
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** MainActivity: true between onResume and onPause. Binds never start otherwise. */
    @Volatile var resumed = false
        set(v) { val was = field; field = v; if (v && !was) { synchronized(this) { silentTried.clear() }; bump() } }

    /** #310 review (d): providers changed while home is visible (e.g. a reinstall): try silent binds again now. */
    @Synchronized fun retrySilent() { if (silentTried.isEmpty()) return; silentTried.clear(); bump() }

    @Synchronized fun hint(elementId: String): Hint? = marks[elementId]

    /** A silent bind will still be tried for this unbound widget (tile shows icon and label only, no reason). */
    @Synchronized fun silentPending(elementId: String, t: Target?): Boolean =
        t != null && !t.configureRequired && elementId !in silentTried && elementId !in marks

    /** D1: try every unbound, configure-free widget once per resume. No UI, ever. */
    @Synchronized fun silentBindAll(unbound: List<Pair<String, Target?>>) {
        if (!resumed || _pending.value != null) return
        var changed = false
        for ((eid, t) in unbound) {
            if (!silentPending(eid, t)) continue
            silentTried += eid; changed = true
            val id = platform.allocate()
            bindings?.beginBind(id)
            if (runCatching { platform.bindIfAllowed(id, t!!) }.getOrDefault(false)) bound(eid, id, t!!)
            else bindings?.abandonBind(id)
        }
        if (changed) bump()
    }

    /** Placeholder tap for `Tap to set up`, `Tap to allow` or `Tap to finish setup`. */
    @Synchronized fun tap(elementId: String, pkg: String, cls: String): Boolean {
        if (!resumed || _pending.value != null) return false
        val t = platform.target(pkg, cls) ?: return false
        val id = platform.allocate()
        bindings?.beginBind(id)
        if (runCatching { platform.bindIfAllowed(id, t) }.getOrDefault(false)) return afterBind(elementId, id, t)
        if (!explained.has(t.pkg)) { explained.add(t.pkg); _pending.value = Pending(elementId, id, t, Step.EXPLAIN); bump(); return true }
        return askBind(Pending(elementId, id, t, Step.BIND))
    }

    /** §16.3 `Continue`. */
    @Synchronized fun explainContinue() { val p = _pending.value?.takeIf { it.step == Step.EXPLAIN } ?: return; askBind(p.copy(step = Step.BIND)) }

    /** §16.3 `Not now` (or the sheet dismissed): stays on the placeholder, `Tap to allow`. */
    @Synchronized fun explainNotNow() { val p = _pending.value?.takeIf { it.step == Step.EXPLAIN } ?: return; cancel(p, Hint.ALLOW) }

    @Synchronized fun onBindResult(ok: Boolean) {
        val p = _pending.value?.takeIf { it.step == Step.BIND } ?: return
        if (ok) { _pending.value = null; afterBind(p.elementId, p.appWidgetId, p.target) } else cancel(p, Hint.ALLOW)
    }

    @Synchronized fun onConfigureResult(ok: Boolean) {
        val p = _pending.value ?: return
        when (p.step) {
            Step.CONFIGURE -> if (ok) { _pending.value = null; bound(p.elementId, p.appWidgetId, p.target); bump() } else cancel(p, Hint.FINISH)
            Step.RECONFIGURE -> { _pending.value = null; bump() }   // the provider keeps or applies its own config
            else -> {}
        }
    }

    /** Sheet `Reconfigure` for a bound, reconfigurable widget. */
    @Synchronized fun reconfigure(elementId: String): Boolean {
        if (!resumed || _pending.value != null) return false
        val b = bindings?.binding(elementId) ?: return false
        val t = platform.target(b.provider.pkg, b.provider.cls)?.takeIf { it.reconfigurable } ?: return false
        val p = Pending(elementId, b.appWidgetId, t, Step.RECONFIGURE)
        _pending.value = p
        if (!platform.launchConfigure(b.appWidgetId)) { _pending.value = null; return false }
        bump(); return true
    }

    private fun afterBind(eid: String, id: Int, t: Target): Boolean {
        if (!t.configureRequired) { bound(eid, id, t); bump(); return true }
        val p = Pending(eid, id, t, Step.CONFIGURE)
        _pending.value = p
        if (!platform.launchConfigure(id)) { cancel(p, Hint.FINISH); return false }
        bump(); return true
    }

    private fun askBind(p: Pending): Boolean {
        _pending.value = p
        if (!platform.launchBind(p.appWidgetId, p.target)) { cancel(p, Hint.ALLOW); return false }
        bump(); return true
    }

    private fun cancel(p: Pending, hint: Hint) {
        _pending.value = null
        bindings?.abandonBind(p.appWidgetId)
        marks[p.elementId] = hint
        bump()
    }

    private fun bound(eid: String, id: Int, t: Target) {
        bindings?.put(eid, WidgetBindings.Binding(id, WidgetBindings.Provider(t.pkg, t.cls), "personal", WidgetBindings.State.BOUND))
        marks -= eid
    }

    private fun bump() { _version.update { it + 1 } }
}
