package dev.nasawa.configlauncher.hosted

import kotlinx.coroutines.flow.update
import dev.nasawa.configlauncher.config.AppWidget
import dev.nasawa.configlauncher.config.Elements
import dev.nasawa.configlauncher.model.LauncherConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream

/**
 * #299 NW-2: device-local `appWidget` bindings (docs/design/native-widgets.md §2.5), in app-private storage
 * (`filesDir/widgets/bindings.json`, temp + rename, like [dev.nasawa.configlauncher.consent.ConsentStore]).
 * Never synced, never in status.json; excluded from backup and device transfer (D2: no restore).
 *
 * `elementId → {appWidgetId, provider, profile, state}`. The store never binds anything itself (NW-4 does, through
 * [put]); it frees AppWidget IDs through [host]:
 * - [settle] runs after every successful load (same hook as consent). A rejected file (B2) never reaches it, so it
 *   never deletes a binding. IDs whose element left the file, or whose element now names another provider or profile
 *   (→ `needsSetup`), are deleted; so are host IDs nobody owns (orphans, e.g. after a corrupt store).
 * - Undo hold for NW-6 (#303, ux.md §16.7.1): a [hold]-ed binding is **set aside** by the first [settle] that sees its
 *   element gone: out of [all], so it never applies to an element the agent adds with the same id, but its ID is
 *   still owned (no orphan sweep). Only [restore] (a successful Undo) puts it back; [release] (the window ended)
 *   frees the ID. Set-aside bindings live in memory only: after the app closes the file no longer lists them, so the
 *   next settle frees their IDs as orphans (= released).
 */
class WidgetBindings(private val dir: File?, private val host: Host) {
    /** The parts of `AppWidgetHost` the store needs (fake in tests). */
    interface Host {
        /** IDs currently allocated to our host. */
        fun ids(): IntArray
        fun delete(appWidgetId: Int)
    }

    data class Provider(val pkg: String, val cls: String)

    enum class State(val key: String) {
        BOUND("bound"), NEEDS_CONFIG("needsConfig"), FAILED("failed");
        companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } }
    }

    data class Binding(val appWidgetId: Int, val provider: Provider, val profile: String, val state: State)

    private val file = dir?.let { File(it, FILE) }
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** #300: bumps on every change, so the home grid redraws hosted widgets. */
    val version: kotlinx.coroutines.flow.StateFlow<Int> = _version
    // #308 review (2): a temp left by a save that died before its rename is stale; the real file is the truth.
    private var entries: MutableMap<String, Binding> = run { dir?.let { File(it, "$FILE.tmp").delete() }; load() }
    private val held = mutableSetOf<String>()
    /** Held bindings whose element has left the file (§16.7.1 "set aside"). */
    private val aside = mutableMapOf<String, Binding>()
    /** IDs allocated by a bind in progress (NW-4), not yet [put]: never treated as orphans. */
    private val inFlight = mutableSetOf<Int>()
    /** Element IDs of `appWidget`s in the last settled file. */
    private var lastIds: Set<String> = emptySet()

    @Synchronized fun all(): Map<String, Binding> = entries.toMap()
    @Synchronized fun binding(elementId: String): Binding? = entries[elementId]
    @Synchronized fun isHeld(elementId: String) = elementId in held

    /** NW-4: an ID was allocated and the bind flow is running. */
    @Synchronized fun beginBind(appWidgetId: Int) { inFlight += appWidgetId }

    /** NW-4: the bind flow gave up; the ID is freed. */
    @Synchronized fun abandonBind(appWidgetId: Int) { if (inFlight.remove(appWidgetId)) host.delete(appWidgetId) }

    /** Record (or replace) a binding. A replaced binding's old ID is freed. */
    @Synchronized fun put(elementId: String, b: Binding) {
        inFlight -= b.appWidgetId
        val old = entries[elementId]
        if (old == b) return
        if (old != null && old.appWidgetId != b.appWidgetId) host.delete(old.appWidgetId)
        entries[elementId] = b
        save()
    }

    /** After a successful load of [c]: GC, provider changes and orphans. Writes only when something changed. */
    @Synchronized fun settle(c: LauncherConfig) {
        val els = c.elements.orEmpty().filter { (it["type"] as? JsonPrimitive)?.content == AppWidget.name }
            .mapNotNull { e -> (e["id"] as? JsonPrimitive)?.content?.let { it to e } }.toMap()
        lastIds = els.keys
        val next = LinkedHashMap<String, Binding>()
        for ((id, b) in entries) {
            val e = els[id]
            when {
                e == null -> if (id in held && id !in aside) aside[id] = b else host.delete(b.appWidgetId)
                // A skipped element (e.g. `requires` above this app's level) keeps its binding untouched.
                Elements.typeOf(e) != AppWidget -> next[id] = b
                target(e) != (norm(b.provider) to b.profile) -> host.delete(b.appWidgetId)   // → needsSetup
                else -> next[id] = b
            }
            // A held binding seen with its element present (before the Remove write, or after Undo): plain again.
            if (e != null && id in held && id !in aside) held -= id
        }
        val alive = aliveIds()
        // #308 review (5): Android already deleted the ID (e.g. the provider was uninstalled): the binding is gone too.
        if (alive != null && (alive.isNotEmpty() || next.isEmpty())) next.entries.removeAll { it.value.appWidgetId !in alive }
        val owned = next.values.map { it.appWidgetId }.toSet() + aside.values.map { it.appWidgetId } + inFlight
        alive?.forEach { if (it !in owned) host.delete(it) }
        if (next != entries) { entries = next; save() }
    }

    /** #308 review (5): drop bindings whose ID Android has deleted (provider uninstalled). Called on package changes. */
    @Synchronized fun prune() {
        val alive = aliveIds() ?: return
        // #310 review (a): an empty list while we hold bindings is suspicious (host not ready); never prune everything.
        if (alive.isEmpty()) return
        if (entries.values.all { it.appWidgetId in alive }) return
        entries = entries.filterValues { it.appWidgetId in alive }.toMutableMap()
        save()
    }

    private fun aliveIds(): Set<Int>? = runCatching { host.ids() }.getOrNull()?.toSet()

    /** #309 review: [elementId]'s ID can't be shown any more (Android has no widget for it): free it → `needsSetup`. */
    @Synchronized fun forget(elementId: String, appWidgetId: Int) {
        val b = entries[elementId]?.takeIf { it.appWidgetId == appWidgetId } ?: return
        entries.remove(elementId); host.delete(b.appWidgetId); save()
    }

    /**
     * NW-6: keep [elementId]'s binding through the next [settle]s while its Remove can still be undone.
     * **Call this before the Remove write.** [settle] runs inside that write (the engine settles after every
     * successful write, the phone's own included), so a binding that isn't held yet is freed immediately.
     */
    @Synchronized fun hold(elementId: String): Boolean = (elementId in entries).also { if (it) held += elementId }

    /**
     * NW-6 Undo succeeded (call it once the Undo write keeps the restore, before that write; #314 review): the set-aside binding is
     * back, kept as it was (no rebind). It stays held until a [settle] sees the element, so a load in between
     * can't free it. A binding the agent's re-added element got meanwhile is replaced (its ID freed).
     */
    @Synchronized fun restore(elementId: String) {
        val b = aside.remove(elementId)
        if (b == null) { if (elementId !in entries) held -= elementId; return }
        entries[elementId]?.let { if (it.appWidgetId != b.appWidgetId) host.delete(it.appWidgetId) }
        entries[elementId] = b
        save()
    }

    /**
     * #314 GO follow-up: the Undo write that [restore] ran for failed, so the file still has no element: set the binding
     * aside again (and keep it held), so a later settle can't hand it to an element the agent re-adds with this id.
     */
    @Synchronized fun setAside(elementId: String) {
        if (elementId !in held || elementId in aside) return
        val b = entries.remove(elementId) ?: return
        aside[elementId] = b
        save()
    }

    /** NW-6: the Undo window ended without a successful Undo. A set-aside binding's ID is freed (`deleteAppWidgetId`). */
    @Synchronized fun release(elementId: String) {
        aside.remove(elementId)?.let { host.delete(it.appWidgetId) }
        // Not settled without its element yet (the write is still pending): the next settle treats it as gone.
        if (held.remove(elementId) && elementId !in lastIds) entries.remove(elementId)?.let { host.delete(it.appWidgetId); save() }
    }

    @Synchronized fun isAside(elementId: String) = elementId in aside

    private fun target(e: JsonObject): Pair<Provider, String>? {
        val content = e["content"] as? JsonObject ?: return null
        val p = content["provider"] as? JsonObject ?: return null
        val pkg = (p["package"] as? JsonPrimitive)?.content ?: return null
        val cls = (p["class"] as? JsonPrimitive)?.content ?: return null
        return norm(Provider(pkg, cls)) to ((content["profile"] as? JsonPrimitive)?.content ?: "personal")
    }

    private fun load(): MutableMap<String, Binding> = try {
        val root = file?.takeIf { it.isFile }?.let { Json.parseToJsonElement(it.readText()).jsonObject }
        root?.get("bindings")?.jsonObject?.mapNotNull { (k, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            fun s(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = (o["appWidgetId"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return@mapNotNull null
            val p = o["provider"] as? JsonObject ?: return@mapNotNull null
            val pkg = (p["package"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val cls = (p["class"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            k to Binding(id, Provider(pkg, cls), s("profile") ?: "personal", State.of(s("state")) ?: State.FAILED)
        }?.toMap()?.toMutableMap() ?: mutableMapOf()
    } catch (_: Exception) {
        // Unreadable: start empty. Every widget shows `Tap to set up`; the host's IDs become orphans and the next
        // settle frees them, so nothing leaks.
        mutableMapOf()
    }

    private fun save() {
        _version.update { it + 1 }
        val f = file ?: return
        val bytes = buildJsonObject {
            put("version", 1)
            put("bindings", JsonObject(entries.toSortedMap().mapValues { (_, b) ->
                buildJsonObject {
                    put("appWidgetId", b.appWidgetId)
                    put("provider", buildJsonObject { put("package", b.provider.pkg); put("class", b.provider.cls) })
                    put("profile", b.profile)
                    put("state", b.state.key)
                }
            }))
        }.toString().toByteArray(Charsets.UTF_8)
        try {
            dir!!.mkdirs()
            val tmp = File(dir, "$FILE.tmp")
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Exception) { /* kept in memory; the next change retries */ }
    }

    companion object {
        const val FILE = "bindings.json"
        /** A class starting with "." is relative to the package. */
        fun resolve(pkg: String, cls: String) = if (cls.startsWith(".")) pkg + cls else cls
        fun norm(p: Provider) = Provider(p.pkg, resolve(p.pkg, p.cls))
    }
}
