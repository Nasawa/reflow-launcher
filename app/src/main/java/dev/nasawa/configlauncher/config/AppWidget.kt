package dev.nasawa.configlauncher.config

import kotlinx.serialization.json.*

/**
 * #298 NW-1: the `appWidget` element (feature level 5), docs/design/native-widgets.md §2.1–§2.2. Schema, codec and
 * validation only: there is no host yet (NW-2, #299), so a shown `appWidget` reserves its cells and draws nothing.
 *
 * - Grid-placed (`page`, `x`, `y`, `w`, `h`), `requires` 5 or higher. Optional `enabled`.
 * - `content`: `provider {package, class}` (required), `profile` (`personal` | `work`; omitted = personal) and an
 *   optional `label` (max 40, shown only on the placeholder before the provider is known).
 * - No `style` in the first release: it gets its own plain sentence ([STYLE_MESSAGE]) instead of "unknown key".
 * Strict like every known type: unknown keys are errors, `x-` keys allowed at the top level only.
 * `scripts/validate.py` mirrors this.
 */
object AppWidget : Elements.ElementType {
    override val name = "appWidget"
    override val level = 5

    val KEYS = listOf("id", "type", "requires", "enabled", "page", "x", "y", "w", "h", "content")
    val CONTENT_KEYS = listOf("provider", "profile", "label")
    val PROVIDER_KEYS = listOf("package", "class")
    val PROFILES = listOf("personal", "work")
    const val MIN_REQUIRES = 5
    const val MAX_LABEL = 40
    private val CLS = Regex("^[A-Za-z0-9_.$]+$")

    /** Pragma (#297): the phone and validate.py use the same sentence, after "elements[N]: ". */
    fun styleMessage(id: String?) = "appWidget \"${id ?: ""}\" doesn't support \"style\" yet. Remove it."

    override fun validate(o: JsonObject, path: String, c: Validator.Checks) {
        if ("style" in o) c.err(path, styleMessage((o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content))
        c.keys(JsonObject(o - "style"), path, KEYS.toSet(), setOf("requires", "page", "content"))
        (o["requires"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()?.let {
            if (it < MIN_REQUIRES) c.err("$path.requires", "an appWidget needs feature level $MIN_REQUIRES or higher")
        }
        c.bool(o, "enabled", path)
        val content = o["content"]?.let { c.obj(it, "$path.content") } ?: return
        val cp = "$path.content"
        c.keys(content, cp, CONTENT_KEYS.toSet(), setOf("provider"), xKeys = false)
        content["provider"]?.let { c.obj(it, "$cp.provider") }?.let { p ->
            c.keys(p, "$cp.provider", PROVIDER_KEYS.toSet(), PROVIDER_KEYS.toSet(), xKeys = false)
            c.str(p, "package", "$cp.provider", re = Validator.PKG)
            c.str(p, "class", "$cp.provider", re = CLS)
        }
        c.str(content, "profile", cp)?.let { if (it !in PROFILES) c.err("$cp.profile", "must be personal or work") }
        c.str(content, "label", cp, max = MAX_LABEL)
    }

    // ---- canonical order (native-widgets.md §2.2): id, type, requires, enabled, page, x, y, w, h, content, then x- keys

    override fun canonical(o: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (k in KEYS) o[k]?.let { v ->
            out[k] = if (k == "content") order(v as JsonObject, CONTENT_KEYS) { ck, cv -> if (ck == "provider") order(cv as JsonObject, PROVIDER_KEYS) else cv } else v
        }
        o.filterKeys { it.startsWith("x-") }.toSortedMap().forEach { (k, v) -> out[k] = v }
        return JsonObject(out)
    }

    private fun order(o: JsonObject, keys: List<String>, inner: (String, JsonElement) -> JsonElement = { _, v -> v }) =
        JsonObject(LinkedHashMap<String, JsonElement>().apply { for (k in keys) o[k]?.let { put(k, inner(k, it)) } })
}
