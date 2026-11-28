package dev.nasawa.configlauncher.config

import kotlinx.serialization.json.*

/**
 * The `card` element: feature level 4 (#137 S3-A) plus M1 readout keys at [Elements.FL_CARDS_READOUT] (#360 RW-1).
 * Schema, codec and validation only: drawing is #362, file/picture loading is #363, formatter and threshold
 * evaluation is #361, and `net.fetch` / `data.sources` / `auth` are M2.
 *
 * - Grid-placed (`page`, `x`, `y`, `w`, `h`), `requires` 4 or higher (level 3 is retired; `appWidget` is level 5).
 *   A card that uses any readout key needs `requires` at least [Elements.FL_CARDS_READOUT].
 * - `capabilities`: the stage-3 kinds only, with kind-specific keys (§4): `data.file` and `ui.reload` (no fields),
 *   `ui.openApp` + `packages`, and a bare `ui.openUrl` (Q-D5: no `hosts`; taps to the card's optional `source` site
 *   open silently, any other host asks on every tap). On the phone, a kind this launcher doesn't know **skips the
 *   card** with a warning and keeps its cells ([unknownCapability]); `scripts/validate.py` reports it as an error so
 *   agents catch typos.
 * - `layouts`: 1–3 `"WxH"` keys, each a list of nodes: containers `column`, `row`, `tile`; primitives `text`, `metric`,
 *   `list`, `icon`, `progress`, `button`. Taps must be allowed by a declared capability.
 * - Optional `title`, `author` (omitted = hidden), `source` (in the consent fingerprint; not a fetch origin), `surface`
 *   (`"auto"`, `"none"`, or `{color, opacity}`), `header`, `summary`, `layers`, `minSpan`, `data.staleAfter`, `data.asOf`.
 * - A text or metric that resolves to an empty string is not drawn ([emptyNotDrawn]); a missing path is drawn as "—".
 * Strict like every known type: unknown keys are errors. `scripts/validate.py` mirrors this.
 */
object Card : Elements.ElementType {
    override val name = "card"
    override val level = 4

    val KEYS = listOf("id", "type", "requires", "page", "x", "y", "w", "h", "minSpan", "title", "author", "source", "surface", "header", "summary", "layers", "capabilities", "data", "layouts")
    /** Stage-3 capability kinds and their fields (§4 "Capability field names"). */
    val KINDS: Map<String, List<String>> = linkedMapOf("data.file" to emptyList(), "ui.reload" to emptyList(), "ui.openApp" to listOf("packages"), "ui.openUrl" to emptyList())
    val TAP_KINDS: Map<String, List<String>> = linkedMapOf("ui.openUrl" to listOf("url"), "ui.openApp" to listOf("package"), "ui.reload" to emptyList())
    val NODES: Map<String, List<String>> = linkedMapOf(
        "column" to listOf("children"), "row" to listOf("children"), "tile" to listOf("children"),
        "text" to listOf("text", "role", "maxLines", "align", "font", "status"),
        "metric" to listOf("label", "value", "unit", "status", "thresholds"),
        "list" to listOf("label", "items", "maxItems", "emptyText"), "icon" to listOf("name", "package"),
        "progress" to listOf("label", "value", "max", "segments", "style", "count", "thresholds", "by"),
        "button" to listOf("label", "icon"),
    )
    val NODE_REQUIRED: Map<String, Set<String>> = mapOf(
        "column" to setOf("children"), "row" to setOf("children"), "tile" to setOf("children"), "text" to setOf("text"),
        "metric" to setOf("value"), "list" to setOf("items"), "progress" to setOf("value"), "button" to setOf("label", "tap"),
    )
    val COMMON = listOf("type", "priority", "weight", "tap", "color")
    val ROLES = listOf("caption", "body", "title", "display")
    val ALIGN = listOf("start", "center", "end")
    val FONT = listOf("default", "mono")
    val LAYER_KINDS = listOf("picture", "content")
    val PICTURE_KEYS = listOf("kind", "src", "default", "fit", "width", "align", "offset", "opacity", "overflow", "scrim")
    val FIT = listOf("cover", "contain", "fixed")
    val LAYER_ALIGN = listOf("topStart", "top", "topEnd", "start", "center", "end", "bottomStart", "bottom", "bottomEnd")
    val TONES = listOf("ok", "warn", "error", "accent", "muted")
    val BY = listOf("value", "position")
    val LAYOUT = Regex("^([1-9][0-9]?)x([1-9][0-9]?)$")
    val HOST = Regex("^(https?://)?[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$")
    val DURATION = Regex("^P(?=[0-9]|T[0-9])([0-9]+D)?(T(?=[0-9])([0-9]+H)?([0-9]+M)?([0-9]+S)?)?$")
    private val URL = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)")
    private val FILE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*\\.(png|jpg|jpeg|webp)$")
    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")
    /** Leftover secret-reference syntax (reflow-widgets.md §5 / §5.1). Auth-based checks are #366. */
    private val SECRET_REF = Regex("\\\$\\{secret:|\\{secret:")
    private val TOKEN = Regex("\\{(data|fetch):[^}]*\\}")

    const val MIN_REQUIRES = 4
    const val MAX_CAPABILITIES = 8
    const val MAX_TARGETS = 8
    const val MAX_LAYOUTS = 3
    const val MAX_CHILDREN = 12
    const val MAX_DEPTH = 4
    const val MAX_NODES = 48
    const val MAX_TEXT = 200
    const val MAX_URL = 2048
    const val MAX_SUMMARY = 120
    const val MAX_LAYERS = 4
    const val MAX_FILE = 80
    const val MAX_THRESHOLDS = 5
    const val MAX_SEGMENTS = 20
    const val MIN_TICKS = 10
    const val MAX_TICKS = 120
    const val MIN_LINES = 1
    const val MAX_LINES = 6
    const val MIN_WIDTH = 0.1
    const val MAX_WIDTH = 1.5
    const val MAX_OFFSET = 0.25
    const val ABOVE_OPACITY = 0.6

    /** §4 "Enforcement": the first `capabilities[].kind` this launcher doesn't know, so the card is skipped. */
    override fun unknownCapability(o: JsonObject): String? =
        (o["capabilities"] as? JsonArray)?.firstNotNullOfOrNull { c -> ((c as? JsonObject)?.get("kind") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it !in KINDS } }

    private class Caps(val kinds: Set<String>, val packages: Set<String>)

    override fun validate(o: JsonObject, path: String, c: Validator.Checks) {
        c.keys(o, path, KEYS.toSet(), setOf("requires", "page", "layouts"))
        (o["requires"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()?.let {
            if (it < MIN_REQUIRES) c.err("$path.requires", "a card needs feature level $MIN_REQUIRES or higher")
            else if (it < Elements.FL_CARDS_READOUT && usesReadoutKeys(o))
                c.err("$path.requires", "a card that uses readout keys needs feature level ${Elements.FL_CARDS_READOUT} or higher")
        }
        o["minSpan"]?.let { e -> c.obj(e, "$path.minSpan")?.let { m ->
            c.keys(m, "$path.minSpan", setOf("w", "h"), setOf("w", "h"), xKeys = false)
            c.int(m, "w", "$path.minSpan", 1, 12); c.int(m, "h", "$path.minSpan", 1, 12)
        } }
        c.str(o, "title", path, max = 40)?.let { binding(it, "$path.title", c) }
        c.str(o, "author", path, max = 40, min = 1)
        // Q-D5: the site taps open silently: a bare hostname (https) or http:// / https:// + hostname.
        c.str(o, "source", path)?.let { if (it.length > 253 + 8 || !HOST.matches(it)) c.err("", "$path.source must be a lowercase hostname, optionally with http:// or https://.") }
        surface(o, path, c)
        c.bool(o, "header", path)
        c.str(o, "summary", path, max = MAX_SUMMARY)?.let { binding(it, "$path.summary", c) }
        o["layers"]?.let { e ->
            if (e !is JsonArray) c.err("$path.layers", "must be an array")
            else layers(e, "$path.layers", c)
        }
        val caps = capabilities(o, path, c)
        o["data"]?.let { e -> c.obj(e, "$path.data")?.let { d ->
            c.keys(d, "$path.data", setOf("staleAfter", "asOf"), emptySet(), xKeys = false)
            c.str(d, "staleAfter", "$path.data", max = 32, re = DURATION)
            c.str(d, "asOf", "$path.data", max = 64, min = 1)?.let { asOf ->
                Bindings.checkAsOf(asOf, "$path.data.asOf")?.let { c.err("", it) }
            }
        } }
        o["layouts"]?.let { e -> c.obj(e, "$path.layouts")?.let { ls -> layouts(ls, "$path.layouts", caps, c) } }
    }

    private fun surface(o: JsonObject, path: String, c: Validator.Checks) {
        val e = o["surface"] ?: return
        when (e) {
            is JsonObject -> {
                c.keys(e, "$path.surface", setOf("color", "opacity"), setOf("color"), xKeys = false)
                e["color"]?.let { TitleBar.checkColor(it, "$path.surface.color", c) }
                c.int(e, "opacity", "$path.surface", 50, 100)
            }
            is JsonPrimitive if e.isString -> {
                val s = e.content
                if (s != "auto" && s != "none") c.err("", """$path.surface must be "auto", "none", or an object with color.""")
            }
            else -> c.err("", """$path.surface must be "auto", "none", or an object with color.""")
        }
    }

    private fun layers(arr: JsonArray, path: String, c: Validator.Checks) {
        var contents = 0
        arr.forEachIndexed { i, e ->
            val lp = "$path[$i]"
            val o = c.obj(e, lp) ?: return@forEachIndexed
            val kind = c.str(o, "kind", lp)
            if (kind == null) { if ("kind" !in o) c.err(lp, "missing required \"kind\""); return@forEachIndexed }
            when (kind) {
                "content" -> {
                    c.keys(o, lp, setOf("kind"), setOf("kind"), xKeys = false)
                    if (++contents > 1) c.err(lp, "a card can have only one content layer")
                }
                "picture" -> picture(o, lp, c)
                else -> c.err("$lp.kind", "must be picture or content")
            }
        }
    }

    private fun picture(o: JsonObject, path: String, c: Validator.Checks) {
        c.keys(o, path, PICTURE_KEYS.toSet(), setOf("kind", "src"), xKeys = false)
        c.str(o, "src", path, max = MAX_FILE, min = 1)?.let { fileOrTemplate(it, "$path.src", template = true, c) }
        c.str(o, "default", path, max = MAX_FILE, min = 1)?.let { fileOrTemplate(it, "$path.default", template = false, c) }
        c.str(o, "fit", path)?.let { if (it !in FIT) c.err("$path.fit", "must be cover, contain or fixed") }
        if ("width" in o) num(o, "width", path, c)
        c.str(o, "align", path)?.let { if (it !in LAYER_ALIGN) c.err("$path.align", "must be topStart, top, topEnd, start, center, end, bottomStart, bottom or bottomEnd") }
        o["offset"]?.let { e -> c.obj(e, "$path.offset")?.let { off ->
            c.keys(off, "$path.offset", setOf("x", "y"), emptySet(), xKeys = false)
            if ("x" in off) num(off, "x", "$path.offset", c)
            if ("y" in off) num(off, "y", "$path.offset", c)
        } }
        o["opacity"]?.let { num(o, "opacity", path, c)?.let { n -> if (n !in 0.0..1.0) c.err("$path.opacity", "must be a number from 0 to 1") } }
        c.bool(o, "overflow", path)
        o["scrim"]?.let { scrim(it, "$path.scrim", c) }
    }

    private fun scrim(e: JsonElement, path: String, c: Validator.Checks) {
        when (e) {
            is JsonObject -> {
                c.keys(e, path, setOf("color", "opacity"), setOf("color"), xKeys = false)
                e["color"]?.let { TitleBar.checkColor(it, "$path.color", c) }
                e["opacity"]?.let { num(e, "opacity", path, c)?.let { n -> if (n !in 0.0..1.0) c.err("$path.opacity", "must be a number from 0 to 1") } }
            }
            is JsonPrimitive if e.isString -> {
                if (e.content != "auto" && e.content != "none") c.err("", """$path must be "auto", "none", or an object with color.""")
            }
            else -> c.err("", """$path must be "auto", "none", or an object with color.""")
        }
    }

    private fun fileOrTemplate(s: String, path: String, template: Boolean, c: Validator.Checks) {
        if (looksRemote(s)) { c.err("", "$path must be a file name in the card's pictures folder, not a web address."); return }
        if (".." in s || s.startsWith(".")) { c.err(path, fileNameMsg()); return }
        if ('{' !in s) { if (!FILE.matches(s)) c.err(path, fileNameMsg()); return }
        if (!template) { c.err(path, "must be a file name, not a template"); return }
        fileTokenError(s, path)?.let { c.err("", it); return }
        if (!legalTemplate(s)) c.err(path, "must be a file name template ending in .png, .jpg, .jpeg or .webp")
    }

    /** First load-error from a `{data:…}` / `{fetch:…}` token in a picture `src`, or null. */
    fun fileTokenError(s: String, path: String): String? {
        var i = 0
        while (i < s.length) {
            if (s[i] == '{') {
                val j = s.indexOf('}', i + 1)
                if (j < 0) return Bindings.check(s, path)
                Bindings.checkFileToken(s.substring(i + 1, j), path)?.let { return it }
                i = j + 1
            } else i++
        }
        return null
    }

    private fun looksRemote(s: String) =
        s.startsWith("http://") || s.startsWith("https://") || s.startsWith("//") || '/' in s

    private fun fileNameMsg() = "must be a file name (letters, digits, '.', '_' or '-') ending in .png, .jpg, .jpeg or .webp"

    /** Static pattern: literal parts are a legal file name, tokens are `{data:…}` or `{fetch:…}`. Runtime resolution is #363. */
    fun legalTemplate(s: String): Boolean {
        val stripped = TOKEN.replace(s, "x")
        if ('{' in stripped || '}' in stripped) return false
        if (".." in s || '/' in s || s.startsWith(".")) return false
        return FILE.matches(stripped)
    }

    private fun capabilities(o: JsonObject, path: String, c: Validator.Checks): Caps {
        val kinds = mutableSetOf<String>(); val packages = mutableSetOf<String>()
        c.arr(o, "capabilities", path, max = MAX_CAPABILITIES)?.forEachIndexed { i, e ->
            val cp = "$path.capabilities[$i]"
            val co = c.obj(e, cp) ?: return@forEachIndexed
            val kind = c.str(co, "kind", cp)
            if (kind == null) { if ("kind" !in co) c.err(cp, "missing required \"kind\""); return@forEachIndexed }
            val fields = KINDS[kind] ?: return@forEachIndexed   // unknown kinds skip the card before validation
            c.keys(co, cp, (listOf("kind") + fields).toSet(), fields.toSet(), xKeys = false)
            if (!kinds.add(kind)) c.err("$cp.kind", "\"$kind\" is listed twice")
            c.arr(co, "packages", cp, max = MAX_TARGETS, min = 1)?.forEachIndexed { j, p ->
                val s = (p as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (s == null || !Validator.PKG.matches(s)) c.err("$cp.packages[$j]", "must be a package name") else if (!packages.add(s)) c.err("$cp.packages[$j]", "\"$s\" is listed twice")
            }
        }
        return Caps(kinds, packages)
    }

    private fun layouts(ls: JsonObject, path: String, caps: Caps, c: Validator.Checks) {
        if (ls.isEmpty()) c.err(path, "needs at least 1 entries")
        if (ls.size > MAX_LAYOUTS) c.err(path, "has ${ls.size} entries, at most $MAX_LAYOUTS allowed")
        for (k in ls.keys) {
            val m = LAYOUT.matchEntire(k)
            if (m == null || m.groupValues[1].toInt() > 12 || m.groupValues[2].toInt() > 12) { c.err(path, "\"$k\" is not a size like \"4x3\" (lowercase x, 1 to 12)"); continue }
            val count = intArrayOf(0)
            c.arr(ls, k, path, max = MAX_CHILDREN, min = 1)?.forEachIndexed { i, n -> node(n, "$path.$k[$i]", 1, count, "$path.$k", caps, c) }
        }
    }

    private fun node(e: JsonElement, np: String, depth: Int, count: IntArray, lp: String, caps: Caps, c: Validator.Checks) {
        if (++count[0] == MAX_NODES + 1) c.err(lp, "has more than $MAX_NODES nodes")
        val o = c.obj(e, np) ?: return
        val type = c.str(o, "type", np)
        if (type == null) { if ("type" !in o) c.err(np, "missing required \"type\""); return }
        val fields = NODES[type] ?: run { c.err("$np.type", "unknown node type \"$type\". Use ${NODES.keys.joinToString(", ")}."); return }
        c.keys(o, np, (COMMON + fields).toSet(), NODE_REQUIRED[type].orEmpty(), xKeys = false)
        c.int(o, "priority", np, 0, 9)
        c.int(o, "weight", np, 1, 12)
        o["color"]?.let { TitleBar.checkColor(it, "$np.color", c) }
        val has = { k: String -> k in fields }
        if (has("text")) c.str(o, "text", np, max = MAX_TEXT)?.let { binding(it, "$np.text", c) }
        if (has("role")) c.str(o, "role", np)?.let { if (it !in ROLES) c.err("$np.role", "must be caption, body, title or display") }
        // Range 1–6 is a load warning (clamped), not an error.
        if (has("maxLines")) c.int(o, "maxLines", np, Long.MIN_VALUE)
        if (has("align")) c.str(o, "align", np)?.let { if (it !in ALIGN) c.err("$np.align", "must be start, center or end") }
        if (has("font")) c.str(o, "font", np)?.let { if (it !in FONT) c.err("$np.font", "must be default or mono") }
        if (has("label")) c.str(o, "label", np, max = 40)?.let { binding(it, "$np.label", c) }
        if (has("icon") && type == "button") c.str(o, "icon", np, max = 64)
        if (type == "progress") {
            val max = o["max"]?.let { num(o, "max", np, c) }
            if (max != null && max <= 0.0) c.err("$np.max", "must be a number greater than 0")
            o["value"]?.let { v ->
                val p = v as? JsonPrimitive
                if (p?.isString == true) c.str(o, "value", np, max = MAX_TEXT)?.let { binding(it, "$np.value", c) }
                else {
                    val n = p?.content?.toDoubleOrNull()
                    val hi = if (max != null && max > 0.0) max else 1.0
                    if (n == null || n !in 0.0..hi) c.err("$np.value", if (max != null && max > 0.0) "must be a template or a number from 0 to $max" else "must be a template or a number from 0 to 1")
                }
            }
            c.int(o, "segments", np, 2, MAX_SEGMENTS.toLong())
            c.str(o, "style", np)?.let { if (it != "ticks") c.err("$np.style", "must be \"ticks\"") }
            if ("count" in o) {
                val n = o["count"] as? JsonPrimitive
                if (n == null || n.isString || n.content.toLongOrNull() == null) c.err("$np.count", "must be an integer")
            }
            c.str(o, "by", np)?.let { if (it !in BY) c.err("$np.by", "must be value or position") }
        } else if (has("value")) c.str(o, "value", np, max = MAX_TEXT)?.let { binding(it, "$np.value", c) }
        if (has("unit")) c.str(o, "unit", np, max = 16)?.let { binding(it, "$np.unit", c) }
        if (has("status")) c.str(o, "status", np, max = MAX_TEXT)?.let { binding(it, "$np.status", c) }
        if (has("items")) c.str(o, "items", np, max = MAX_TEXT)?.let { binding(it, "$np.items", c) }
        if (has("maxItems")) o["maxItems"]?.let { v ->
            if ((v as? JsonPrimitive)?.let { it.isString && it.content == "auto" } != true) c.int(o, "maxItems", np, 1, 20)
        }
        if (has("emptyText")) c.str(o, "emptyText", np, max = 80)?.let { binding(it, "$np.emptyText", c) }
        if (type == "icon") {
            c.str(o, "name", np, max = 64)
            c.str(o, "package", np, re = Validator.PKG)
            if (("name" in o) == ("package" in o)) c.err(np, "needs exactly one of \"name\" or \"package\"")
        }
        if (has("thresholds")) o["thresholds"]?.let { thresholds(it, "$np.thresholds", c) }
        o["tap"]?.let { tap(it, "$np.tap", caps, c) }
        if (has("children") && "children" in o) {
            if (depth >= MAX_DEPTH) c.err(np, "is nested too deep (at most $MAX_DEPTH levels)")
            else c.arr(o, "children", np, max = MAX_CHILDREN, min = 1)?.forEachIndexed { i, n -> node(n, "$np.children[$i]", depth + 1, count, lp, caps, c) }
        }
    }

    private fun thresholds(e: JsonElement, path: String, c: Validator.Checks) {
        val a = e as? JsonArray ?: run { c.err(path, "must be an array"); return }
        if (a.size !in 1..MAX_THRESHOLDS) c.err(path, "needs 1 to $MAX_THRESHOLDS entries")
        var prev: Double? = null
        a.forEachIndexed { i, item ->
            val tp = "$path[$i]"
            val o = c.obj(item, tp) ?: return@forEachIndexed
            c.keys(o, tp, setOf("from", "tone"), setOf("from", "tone"), xKeys = false)
            val from = num(o, "from", tp, c)
            if (from != null) {
                if (from !in 0.0..100.0) c.err("$tp.from", "must be 0 to 100")
                if (i == 0 && from != 0.0) c.err("$tp.from", "must be 0")
                if (prev != null && from <= prev!!) c.err("$tp.from", "must be greater than the previous from")
                prev = from
            }
            c.str(o, "tone", tp)?.let { tone ->
                if (tone !in TONES && !HEX.matches(tone)) c.err("$tp.tone", "must be ok, warn, error, accent, muted, or #RRGGBB")
            }
        }
    }

    private fun tap(e: JsonElement, tp: String, caps: Caps, c: Validator.Checks) {
        val o = c.obj(e, tp) ?: return
        val kind = c.str(o, "kind", tp)
        if (kind == null) { if ("kind" !in o) c.err(tp, "missing required \"kind\""); return }
        val fields = TAP_KINDS[kind] ?: run { c.err("$tp.kind", "must be ui.openUrl, ui.openApp or ui.reload"); return }
        c.keys(o, tp, (listOf("kind") + fields).toSet(), fields.toSet(), xKeys = false)
        if (kind !in caps.kinds) { c.err(tp, "$kind isn't in this card's capabilities"); return }
        c.str(o, "package", tp, re = Validator.PKG)?.let { if (it !in caps.packages) c.err("$tp.package", "\"$it\" isn't in the ui.openApp packages") }
        c.str(o, "url", tp, max = MAX_URL, min = 1)?.let { url ->
            if (mentionsSecret(url)) c.err("$tp.url", "a link can't use a secret.")
            else if ('{' !in url) urlProblem(url)?.let { c.err("$tp.url", it) }
            else binding(url, "$tp.url", c)
        }
    }

    /** A leftover `${secret:…}` or `{secret:…}` in a string (reflow-widgets.md §5). */
    fun mentionsSecret(s: String): Boolean = SECRET_REF.containsMatchIn(s)

    /** Load-time binding grammar (#361). A sentence starting with [path], or null when [s] is fine. */
    fun binding(s: String, path: String, c: Validator.Checks) {
        Bindings.check(s, path)?.let { c.err("", it) }
    }

    /** Expand a template with an injected [Bindings.Env]. Never uses the real clock. */
    fun bind(s: String, env: Bindings.Env, path: String = "elements[0]"): Bindings.Value =
        Bindings.expand(s, env, path)

    private fun isFalse(e: JsonElement?): Boolean =
        (e as? JsonPrimitive)?.let { !it.isString && it.content == "false" } == true

    /**
     * A literal tap URL (a `{data:…}` URL is checked at tap time) must be http or https with no user info. Which host
     * it opens is decided at tap time (Q-D5): the card's `source` opens silently, anything else asks every time.
     */
    fun urlProblem(url: String): String? {
        val m = URL.find(url) ?: return "must be an http or https URL"
        val scheme = m.groupValues[1].lowercase()
        if (scheme != "http" && scheme != "https") return "must be an http or https URL"
        if ('@' in m.groupValues[2]) return "must not contain user info"
        return null
    }

    private fun num(o: JsonObject, k: String, path: String, c: Validator.Checks): Double? {
        val v = o[k] ?: return null
        val p = v as? JsonPrimitive
        val n = p?.content?.toDoubleOrNull()
        if (p == null || p.isString || n == null || n.isNaN() || n.isInfinite()) { c.err("$path.$k", "must be a number"); return null }
        return n
    }

    // ---- readout helpers (rules consumed by later issues; tested here) ----

    /** §4.1 / §1.3: a text or metric that resolves to the empty string is not drawn. A missing path becomes "—" and is drawn. */
    fun emptyNotDrawn(resolved: String): Boolean = resolved.isEmpty()

    /** True when the card uses a key introduced at [Elements.FL_CARDS_READOUT]. */
    fun usesReadoutKeys(o: JsonObject): Boolean {
        if ("header" in o || "summary" in o || "layers" in o) return true
        (o["data"] as? JsonObject)?.let { if ("asOf" in it) return true }
        o["surface"]?.let { s ->
            if (s is JsonObject) return true
            if ((s as? JsonPrimitive)?.takeIf { it.isString }?.content != "auto") return true
        }
        fun walk(e: JsonElement): Boolean {
            val n = e as? JsonObject ?: return false
            val type = (n["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if ("color" in n) return true
            when (type) {
                "text" -> if ("align" in n || "font" in n || "status" in n || (n["role"] as? JsonPrimitive)?.content == "display") return true
                "progress" -> if (listOf("max", "segments", "style", "count", "thresholds", "by").any { it in n }) return true
                "metric" -> if ("thresholds" in n) return true
                "button" -> if ("icon" in n) return true
            }
            (n["children"] as? JsonArray)?.forEach { if (walk(it)) return true }
            return false
        }
        (o["layouts"] as? JsonObject)?.values?.forEach { v -> (v as? JsonArray)?.forEach { if (walk(it)) return true } }
        return false
    }

    fun hasOpenUrl(o: JsonObject): Boolean =
        (o["capabilities"] as? JsonArray)?.any { ((it as? JsonObject)?.get("kind") as? JsonPrimitive)?.content == "ui.openUrl" } == true

    /** After §4.5: `header: false` is honoured only when the card has no `ui.openUrl`. (`net.fetch` is #368.) */
    fun honorsHeaderFalse(o: JsonObject): Boolean =
        isFalse(o["header"]) && !hasOpenUrl(o)

    fun usesDefaultSurface(o: JsonObject): Boolean {
        val none = (o["surface"] as? JsonPrimitive)?.content == "none"
        return none && honorsHeaderFalse(o)
    }

    // ---- load warnings (exact strings in reflow-widgets.md §4.4 / §4.5) ----

    override fun warnings(o: JsonObject, path: String): List<String> = loadWarnings(o, path, files = null)

    /**
     * Load warnings. [files] is the names in the card's pictures folder; null skips missing-file cases
     * (those need a folder listing, wired by #363). The sentences themselves are accepted here.
     */
    fun loadWarnings(o: JsonObject, path: String, files: Set<String>?): List<String> {
        val out = mutableListOf<String>()
        val headerFalse = isFalse(o["header"])
        if (headerFalse && hasOpenUrl(o))
            out += """$path.header: false is ignored because this card opens links or needs your OK, so the header is shown."""
        if (headerFalse && (o["surface"] as? JsonPrimitive)?.content == "none")
            out += """$path.surface: "none" can't be used with header: false, so the default card surface was used."""
        (o["layers"] as? JsonArray)?.let { arr ->
            if (arr.size > MAX_LAYERS)
                out += "$path.layers: has ${arr.size} layers, so the last ${arr.size - MAX_LAYERS} were skipped. A card can have at most $MAX_LAYERS."
            val kept = arr.take(MAX_LAYERS)
            val contentAt = kept.indexOfFirst { ((it as? JsonObject)?.get("kind") as? JsonPrimitive)?.content == "content" }
                .let { if (it < 0) kept.size else it } // implicit content at the top (front)
            kept.forEachIndexed { i, e ->
                val layer = e as? JsonObject ?: return@forEachIndexed
                if ((layer["kind"] as? JsonPrimitive)?.content != "picture") return@forEachIndexed
                val lp = "$path.layers[$i]"
                val fit = (layer["fit"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "cover"
                if ("width" in layer && fit != "fixed")
                    out += """$lp.width is ignored unless fit is "fixed", so the picture is sized with "$fit". Set fit to "fixed" to use width, or drop width."""
                numOf(layer, "width")?.let { w ->
                    if (fit == "fixed" && (w < MIN_WIDTH || w > MAX_WIDTH)) {
                        val setTo = w.coerceIn(MIN_WIDTH, MAX_WIDTH)
                        out += "$lp.width: ${fmtNum(w)} is outside ${fmtNum(MIN_WIDTH)} to ${fmtNum(MAX_WIDTH)}, so it was set to ${fmtNum(setTo)}. Use a value in that range."
                    }
                }
                (layer["offset"] as? JsonObject)?.let { off ->
                    fun clamp(axis: String) {
                        val v = numOf(off, axis) ?: return
                        if (v < -MAX_OFFSET || v > MAX_OFFSET) {
                            val setTo = v.coerceIn(-MAX_OFFSET, MAX_OFFSET)
                            out += "$lp.offset.$axis: ${fmtNum(v)} is outside ${fmtNum(-MAX_OFFSET)} to ${fmtNum(MAX_OFFSET)}, so it was set to ${fmtNum(setTo)}. Use a value in that range."
                        }
                    }
                    clamp("x"); clamp("y")
                }
                val under = i == contentAt - 1
                val above = i > contentAt
                val scrim = layer["scrim"]
                val scrimAuto = scrim == null || ((scrim as? JsonPrimitive)?.content == "auto")
                if (under && scrimAuto)
                    out += "$lp: a picture is right under content, so a default scrim was added to keep text readable. Set this layer's scrim to choose it, or \"none\" to turn it off."
                if (above) {
                    val opacity = numOf(layer, "opacity") ?: 1.0
                    if (opacity > ABOVE_OPACITY)
                        out += "$lp.opacity is above ${fmtNum(ABOVE_OPACITY)} on a layer above content, so it's shown at ${fmtNum(ABOVE_OPACITY)} to keep text readable. Use ${fmtNum(ABOVE_OPACITY)} or less, or move the layer under the content."
                }
                if (files != null) {
                    val src = (layer["src"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (src != null && '{' !in src && src !in files)
                        out += """$lp.src: "$src" isn't in this card's pictures folder, so the layer was skipped."""
                    val def = (layer["default"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (def != null && def !in files)
                        out += """$lp.default: "$def" isn't in this card's pictures folder, so the default picture was skipped."""
                }
            }
        }
        fun walkNodes(e: JsonElement, np: String) {
            val n = e as? JsonObject ?: return
            val type = (n["type"] as? JsonPrimitive)?.content
            n["maxLines"]?.let { mv ->
                val k = (mv as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
                if (k != null && (k < MIN_LINES || k > MAX_LINES)) {
                    val setTo = k.coerceIn(MIN_LINES.toLong(), MAX_LINES.toLong())
                    out += "$np.maxLines: $k is outside $MIN_LINES to $MAX_LINES, so it was set to $setTo. Use a value in that range."
                }
            }
            if (type == "progress") {
                val style = (n["style"] as? JsonPrimitive)?.content
                n["count"]?.let { cv ->
                    val k = (cv as? JsonPrimitive)?.content?.toLongOrNull()
                    if (k != null) {
                        when {
                            style != "ticks" -> out += """$np.count is ignored unless style is "ticks", so the tick count isn't used. Set style to "ticks" to use count, or drop count."""
                            k > MAX_TICKS -> out += "$np.count: $k is outside $MIN_TICKS to $MAX_TICKS, so it was set to $MAX_TICKS. A ticks bar can have at most $MAX_TICKS ticks."
                            k < MIN_TICKS -> out += "$np.count: $k is outside $MIN_TICKS to $MAX_TICKS, so it was set to $MIN_TICKS. A ticks bar needs at least $MIN_TICKS ticks."
                        }
                    }
                }
                if (style == "ticks" && "segments" in n)
                    out += """$np.segments is ignored when style is "ticks", so the bar uses ticks. Drop segments, or drop style to use a segmented bar."""
            }
            (n["children"] as? JsonArray)?.forEachIndexed { i, ch -> walkNodes(ch, "$np.children[$i]") }
        }
        (o["layouts"] as? JsonObject)?.forEach { (k, v) ->
            (v as? JsonArray)?.forEachIndexed { i, n -> walkNodes(n, "$path.layouts.$k[$i]") }
        }
        return out
    }

    /** Exact sentence for a decode / size failure (#363 will emit it). */
    fun imageBadWarning(path: String, fileName: String) =
        """$path: "$fileName" can't be shown (too big, or not a PNG, JPEG or WebP), so the layer was skipped."""

    private fun numOf(o: JsonObject, k: String): Double? {
        val p = o[k] as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.content.toDoubleOrNull()
    }

    /** Whole numbers without a trailing ".0", so "width 2" matches the §4.4 sentence. */
    internal fun fmtNum(n: Double): String {
        val i = n.toLong()
        return if (n == i.toDouble()) i.toString() else n.toString()
    }

    override fun canonical(o: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (k in KEYS) o[k]?.let { v ->
            out[k] = when (k) {
                "minSpan" -> order(v as JsonObject, listOf("w", "h"))
                "surface" -> if (v is JsonObject) colorObj(v, listOf("color", "opacity")) else v
                "layers" -> JsonArray((v as JsonArray).map { layer ->
                    val lo = layer as JsonObject
                    when ((lo["kind"] as? JsonPrimitive)?.content) {
                        "picture" -> order(lo, PICTURE_KEYS) { key, ev ->
                            when (key) {
                                "offset" if ev is JsonObject -> order(ev, listOf("x", "y"))
                                "scrim" if ev is JsonObject -> colorObj(ev, listOf("color", "opacity"))
                                else -> ev
                            }
                        }
                        else -> order(lo, listOf("kind"))
                    }
                })
                "capabilities" -> JsonArray((v as JsonArray).map { cap -> (cap as JsonObject).let { co -> order(co, listOf("kind") + KINDS[(co["kind"] as JsonPrimitive).content].orEmpty()) } })
                "layouts" -> JsonObject((v as JsonObject).mapValues { (_, nodes) ->
                    JsonArray((nodes as JsonArray).map { canonicalNode(it) })
                })
                else -> v
            }
        }
        o.filterKeys { it.startsWith("x-") }.toSortedMap().forEach { (k, v) -> out[k] = v }
        return JsonObject(out)
    }

    private fun canonicalNode(e: JsonElement): JsonElement {
        val o = e as? JsonObject ?: return e
        val type = (o["type"] as? JsonPrimitive)?.content
        val keys = COMMON + NODES[type].orEmpty()
        return order(o, keys) { k, v ->
            when {
                k == "color" && v is JsonObject -> colorObj(v, listOf("light", "dark"))
                k == "thresholds" && v is JsonArray -> JsonArray(v.map { t -> if (t is JsonObject) order(t, listOf("from", "tone")) else t })
                k == "tap" && v is JsonObject -> {
                    val kind = (v["kind"] as? JsonPrimitive)?.content
                    order(v, listOf("kind") + TAP_KINDS[kind].orEmpty())
                }
                k == "children" && v is JsonArray -> JsonArray(v.map { canonicalNode(it) })
                else -> v
            }
        }
    }

    private fun colorObj(o: JsonObject, keys: List<String>) = order(o, keys) { k, v ->
        if (k == "color" && v is JsonObject) order(v, listOf("light", "dark")) else v
    }

    private fun order(o: JsonObject, keys: List<String>, inner: (String, JsonElement) -> JsonElement = { _, v -> v }) =
        JsonObject(LinkedHashMap<String, JsonElement>().apply { for (k in keys) o[k]?.let { put(k, inner(k, it)) } })
}
