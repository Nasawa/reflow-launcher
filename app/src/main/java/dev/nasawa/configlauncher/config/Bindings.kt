package dev.nasawa.configlauncher.config

import dev.nasawa.configlauncher.widgetdata.WidgetData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * #361 (RW-4): card binding grammar, the five formatters, `{relative}`, ambient tokens, and evaluation.
 * `{fetch:…}` is parsed and checked here; evaluation of a fetch source is #368 (M1 returns a missing path).
 * Threshold lists live in [Thresholds]. `scripts/validate.py` mirrors both.
 *
 * Unpinned load-error copy (Pragma grammar, not yet a tone pass) is listed in the RW-4 PR.
 */
object Bindings {
    const val MISSING = "—"
    const val MAX_FORMATTERS = 2
    const val MAX_ROUND = 10
    private val SEG = Regex("^([A-Za-z0-9_-]+)((\\[[0-9]+])*)$")
    private val SOURCE = Regex("^[A-Za-z0-9_][A-Za-z0-9_-]*$")

    val AMBIENT = listOf("time", "date", "battery", "batteryFraction", "batteryState", "nextAlarm", "relative")
    val FORMATTERS = listOf("round", "duration", "since", "time", "default")

    data class Env(
        val now: Instant,
        val zone: ZoneId,
        val locale: Locale,
        val is24Hour: Boolean,
        val data: JsonObject? = null,
        /** Source id → snapshot. Empty in M1; `{fetch:…}` then evaluates as a missing path. */
        val fetch: Map<String, JsonObject> = emptyMap(),
        val batteryPercent: Int? = null,
        val charging: Boolean = false,
        val full: Boolean = false,
        val nextAlarm: Instant? = null,
        /** When set, bare `{time}` / `{nextAlarm}` use this DateTimeFormatter pattern instead of H:mm / h:mm a. */
        val timePattern: String? = null,
    )

    /** A resolved template. [empty] is the §4.3 empty-string case (not drawn). [number] is set when the whole result is one numeric token. */
    data class Value(val text: String, val empty: Boolean = false, val number: Double? = null) {
        fun drawn(): String? = if (empty) null else text
    }

    sealed interface Part
    data class Lit(val text: String) : Part
    data class Tok(
        val raw: String,
        val kind: Kind,
        val path: String? = null,
        val source: String? = null,
        val pattern: String? = null,
        val formatters: List<Fmt> = emptyList(),
    ) : Part

    enum class Kind { Data, Fetch, Time, Date, Battery, BatteryFraction, BatteryState, NextAlarm, Relative }

    sealed interface Fmt {
        data class Round(val n: Int) : Fmt
        data object Duration : Fmt
        data object Since : Fmt
        data class Time(val pattern: String?) : Fmt
        data class Default(val text: String) : Fmt
    }

    sealed interface Result {
        data class Ok(val parts: List<Part>) : Result
        data class Bad(val message: String) : Result
    }

    fun legalPath(path: String): Boolean =
        path.isNotEmpty() && path.split('.').all { SEG.matches(it) }

    /** Load-time check. Returns a full sentence starting with [path], or null when the string is fine. */
    fun check(s: String, path: String): String? = (parse(s, path) as? Result.Bad)?.message

    fun parse(s: String, path: String = "elements[0]"): Result {
        val cps = s.codePoints().toArray()
        if ('\n'.code in cps || '\r'.code in cps) return Result.Bad("$path: A template is one line. Remove the line break.")
        val parts = mutableListOf<Part>()
        val lit = StringBuilder()
        var tokens = 0
        var i = 0
        fun str(from: Int, to: Int) = String(cps, from, to - from)
        while (i < cps.size) {
            val c = cps[i]
            when {
                c == '{'.code && cps.getOrNull(i + 1) == '{'.code -> { lit.append('{'); i += 2 }
                c == '}'.code && cps.getOrNull(i + 1) == '}'.code -> { lit.append('}'); i += 2 }
                c == '}'.code -> return Result.Bad("$path: the \"}\" at character ${i + 1} has no opening \"{\". For a literal brace, write }}.")
                c == '{'.code -> {
                    var j = i + 1
                    while (j < cps.size && cps[j] != '}'.code) j++
                    if (j >= cps.size) return Result.Bad("$path: the \"{\" at character ${i + 1} isn't closed. For a literal brace, write {{.")
                    val inner = str(i + 1, j)
                    when (val tok = token(inner, path)) {
                        is Result.Bad -> return tok
                        is Result.Ok -> {
                            if (lit.isNotEmpty()) { parts += Lit(lit.toString()); lit.clear() }
                            parts += tok.parts
                            tokens++
                            i = j + 1
                        }
                    }
                }
                else -> { lit.appendCodePoint(c); i++ }
            }
        }
        if (lit.isNotEmpty()) parts += Lit(lit.toString())
        if (tokens > TitleBar.MAX_TOKENS) return Result.Bad("$path has $tokens tokens; the maximum is ${TitleBar.MAX_TOKENS}.")
        return Result.Ok(parts)
    }

    /** Filename templates may only use `{data:…}` or `{fetch:…}`, with no formatters. */
    fun checkFileToken(inner: String, path: String): String? {
        val t = token(inner, path)
        if (t is Result.Bad) return t.message
        val tok = (t as Result.Ok).parts.single() as Tok
        if (tok.kind != Kind.Data && tok.kind != Kind.Fetch)
            return "$path: \"${srcWrap(inner)}\" isn't a file name token, so this card wasn't loaded. Use {data:…} or {fetch:…} in a picture src."
        if (tok.formatters.isNotEmpty())
            return "$path: ${srcWrap(inner)} has a formatter, so this card wasn't loaded. A picture file name can only use {data:…} or {fetch:…}."
        return null
    }

    private fun srcWrap(inner: String) = "{$inner}"

    private fun token(inner: String, path: String): Result {
        val bits = splitFormatters(inner)
        if (bits.isEmpty() || bits[0].isEmpty())
            return Result.Bad("$path: unknown token {$inner}, so this card wasn't loaded. Use {data:…}, {fetch:…}, {time}, {date}, {battery}, {batteryFraction}, {batteryState}, {nextAlarm} or {relative}.")
        if (bits.size - 1 > MAX_FORMATTERS)
            return Result.Bad("$path: {$inner} has ${bits.size - 1} formatters, so this card wasn't loaded. A token can have at most $MAX_FORMATTERS.")
        val head = bits[0]
        val fmts = mutableListOf<Fmt>()
        for (f in bits.drop(1)) {
            when (val parsed = formatter(f, inner, path)) {
                is Result.Bad -> return parsed
                is Result.Ok -> fmts += parsed.parts.filterIsInstance<FmtHolder>().map { it.fmt }
            }
        }
        return headToken(head, inner, path, fmts)
    }

    // Holder so formatter() can reuse Result.Ok. Not a Part used in templates.
    private data class FmtHolder(val fmt: Fmt) : Part

    private fun splitFormatters(inner: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var i = 0
        while (i < inner.length) {
            if (inner[i] == '|') { out += buf.toString(); buf.clear(); i++; continue }
            buf.append(inner[i]); i++
        }
        out += buf.toString()
        return out
    }

    private fun formatter(f: String, inner: String, path: String): Result {
        val colon = f.indexOf(':')
        val name = if (colon < 0) f else f.substring(0, colon)
        val arg = if (colon < 0) null else f.substring(colon + 1)
        return when (name) {
            "round" -> {
                val n = if (arg == null) 0 else arg.toIntOrNull()
                if (n == null || n !in 0..MAX_ROUND)
                    Result.Bad("$path: the n in {$inner} isn't a whole number from 0 to $MAX_ROUND, so this card wasn't loaded. Write {…|round} or {…|round:1}.")
                else Result.Ok(listOf(FmtHolder(Fmt.Round(n))))
            }
            "duration" -> if (arg != null)
                Result.Bad("$path: duration in {$inner} doesn't take an argument, so this card wasn't loaded. Write {…|duration}.")
            else Result.Ok(listOf(FmtHolder(Fmt.Duration)))
            "since" -> if (arg != null)
                Result.Bad("$path: since in {$inner} doesn't take an argument, so this card wasn't loaded. Write {…|since}.")
            else Result.Ok(listOf(FmtHolder(Fmt.Since)))
            "time" -> {
                if (arg != null) {
                    if (arg.isEmpty()) return Result.Bad("$path: {time:} has an empty pattern. Write {time} for the default format.")
                    val len = arg.codePointCount(0, arg.length)
                    if (len > TitleBar.MAX_PATTERN) return Result.Bad("$path: the pattern in {time:…} is $len characters; the maximum is ${TitleBar.MAX_PATTERN}.")
                    Template.badInPattern(arg)?.let { bad ->
                        return Result.Bad(when {
                            bad == "'" -> "$path: A ' quote isn't closed. Add a closing ' after the text."
                            bad.length > 1 || (bad[0] in Template.LETTERS) -> "$path: $bad is too long. Use at most ${Template.LETTERS.getValue(bad[0])}, like ${bad.take(Template.LETTERS.getValue(bad[0]))}."
                            else -> "$path: \"$bad\" isn't allowed in {time:$arg}. Use y M d E a H h k K m; seconds and time zones aren't supported."
                        })
                    }
                }
                Result.Ok(listOf(FmtHolder(Fmt.Time(arg))))
            }
            "default" -> {
                if (arg == null) Result.Bad("$path: default in {$inner} needs a text value, so this card wasn't loaded. Write {…|default:text}.")
                else Result.Ok(listOf(FmtHolder(Fmt.Default(arg))))
            }
            else -> Result.Bad("$path: unknown formatter \"$name\" in {$inner}, so this card wasn't loaded. Use round, duration, since, time or default.")
        }
    }

    private fun headToken(head: String, inner: String, path: String, fmts: List<Fmt>): Result {
        val colon = head.indexOf(':')
        val name = if (colon < 0) head else head.substring(0, colon)
        val arg = if (colon < 0) null else head.substring(colon + 1)
        fun tok(kind: Kind, p: String? = null, source: String? = null, pattern: String? = null) =
            Result.Ok(listOf(Tok(inner, kind, path = p, source = source, pattern = pattern, formatters = fmts)))
        return when (name) {
            "data" -> {
                if (arg == null || !legalPath(arg))
                    Result.Bad("$path: {$inner} isn't a data path, so this card wasn't loaded. Use a path of dots and [index] like {data:updatedAt}.")
                else tok(Kind.Data, p = arg)
            }
            "fetch" -> {
                val dot = arg?.indexOf('.') ?: -1
                val source = if (dot > 0) arg!!.substring(0, dot) else null
                val rest = if (dot > 0) arg!!.substring(dot + 1) else null
                if (arg == null || source == null || !SOURCE.matches(source) || rest.isNullOrEmpty() || !legalPath(rest))
                    Result.Bad("$path: {$inner} isn't a fetch path, so this card wasn't loaded. Write {fetch:source.path}, with a source name and a path of dots and [index].")
                else tok(Kind.Fetch, p = rest, source = source)
            }
            "time", "date" -> {
                if (arg != null) {
                    if (arg.isEmpty()) return Result.Bad("$path: {$name:} has an empty pattern. Write {$name} for the default format.")
                    val len = arg.codePointCount(0, arg.length)
                    if (len > TitleBar.MAX_PATTERN) return Result.Bad("$path: the pattern in {$name:…} is $len characters; the maximum is ${TitleBar.MAX_PATTERN}.")
                    Template.badInPattern(arg)?.let { bad ->
                        return Result.Bad(when {
                            bad == "'" -> "$path: A ' quote isn't closed. Add a closing ' after the text."
                            bad.length > 1 || (bad[0] in Template.LETTERS) -> "$path: $bad is too long. Use at most ${Template.LETTERS.getValue(bad[0])}, like ${bad.take(Template.LETTERS.getValue(bad[0]))}."
                            else -> "$path: \"$bad\" isn't allowed in {$name:$arg}. Use y M d E a H h k K m; seconds and time zones aren't supported."
                        })
                    }
                }
                tok(if (name == "time") Kind.Time else Kind.Date, pattern = arg)
            }
            "battery" -> if (arg != null)
                Result.Bad("$path: {battery} doesn't take a path, so this card wasn't loaded. Write {battery}.")
            else tok(Kind.Battery)
            "batteryFraction" -> if (arg != null)
                Result.Bad("$path: {batteryFraction} doesn't take a path, so this card wasn't loaded. Write {batteryFraction}.")
            else tok(Kind.BatteryFraction)
            "batteryState" -> if (arg != null)
                Result.Bad("$path: {batteryState} doesn't take a path, so this card wasn't loaded. Write {batteryState}.")
            else tok(Kind.BatteryState)
            "nextAlarm" -> {
                if (arg != null) {
                    if (arg.isEmpty()) return Result.Bad("$path: {nextAlarm:} has an empty pattern. Write {nextAlarm} for the default format.")
                    val len = arg.codePointCount(0, arg.length)
                    if (len > TitleBar.MAX_PATTERN) return Result.Bad("$path: the pattern in {nextAlarm:…} is $len characters; the maximum is ${TitleBar.MAX_PATTERN}.")
                    Template.badInPattern(arg)?.let { bad ->
                        return Result.Bad(when {
                            bad == "'" -> "$path: A ' quote isn't closed. Add a closing ' after the text."
                            bad.length > 1 || (bad[0] in Template.LETTERS) -> "$path: $bad is too long. Use at most ${Template.LETTERS.getValue(bad[0])}, like ${bad.take(Template.LETTERS.getValue(bad[0]))}."
                            else -> "$path: \"$bad\" isn't allowed in {nextAlarm:$arg}. Use y M d E a H h k K m; seconds and time zones aren't supported."
                        })
                    }
                }
                tok(Kind.NextAlarm, pattern = arg)
            }
            "relative" -> {
                val p = when {
                    arg == null || arg.isEmpty() -> "updatedAt"
                    arg.startsWith("data.") && legalPath(arg.removePrefix("data.")) -> arg.removePrefix("data.")
                    legalPath(arg) -> arg
                    else -> return Result.Bad("$path: {relative} doesn't take a pattern, so this card wasn't loaded. Write {relative} or {relative:updatedAt}.")
                }
                tok(Kind.Relative, p = p)
            }
            else -> Result.Bad("$path: unknown token {$inner}, so this card wasn't loaded. Use {data:…}, {fetch:…}, {time}, {date}, {battery}, {batteryFraction}, {batteryState}, {nextAlarm} or {relative}.")
        }
    }

    fun checkAsOf(s: String, path: String): String? {
        val p = if (s.startsWith("data.") && legalPath(s.removePrefix("data."))) s.removePrefix("data.") else s
        return if (legalPath(p)) null
        else "$path: \"$s\" isn't a data path, so this card wasn't loaded. Use a path of dots and [index] like updatedAt."
    }

    fun asOfPath(s: String): String = if (s.startsWith("data.") && legalPath(s.removePrefix("data."))) s.removePrefix("data.") else s

    /** ISO-8601 instant at [path] in [root], or null if missing or not a timestamp. */
    fun asOf(root: JsonObject?, path: String, zone: ZoneId): Instant? = parseTime(scalar(root, path)?.text, zone)

    fun expand(s: String, env: Env, path: String = "elements[0]"): Value {
        val parsed = parse(s, path) as? Result.Ok ?: return Value(MISSING)
        return expand(parsed.parts, env)
    }

    fun expand(parts: List<Part>, env: Env): Value {
        if (parts.isEmpty()) return Value("", empty = true)
        if (parts.size == 1 && parts[0] is Tok) return expandTok(parts[0] as Tok, env)
        val sb = StringBuilder()
        var any = false
        for (p in parts) {
            when (p) {
                is Lit -> { sb.append(p.text); if (p.text.isNotEmpty()) any = true }
                is Tok -> {
                    val v = expandTok(p, env)
                    if (!v.empty) { sb.append(v.text); any = true }
                }
                is FmtHolder -> error("FmtHolder is not a template part")
            }
        }
        val text = sb.toString()
        return if (!any && text.isEmpty()) Value("", empty = true) else Value(text)
    }

    private fun expandTok(tok: Tok, env: Env): Value {
        var missing = false
        var empty = false
        var number: Double? = null
        var text = ""
        when (tok.kind) {
            Kind.Data -> {
                when (val s = scalar(env.data, tok.path!!)) {
                    null -> missing = true
                    is Scalar.Empty -> empty = true
                    is Scalar.Num -> { number = s.n; text = s.text }
                    is Scalar.Text -> text = s.text
                }
            }
            Kind.Fetch -> {
                // M1: parse-only. A snapshot in [Env.fetch] is honoured when present so tests can pin the grammar.
                when (val s = scalar(env.fetch[tok.source], tok.path!!)) {
                    null -> missing = true
                    is Scalar.Empty -> empty = true
                    is Scalar.Num -> { number = s.n; text = s.text }
                    is Scalar.Text -> text = s.text
                }
            }
            Kind.Time -> text = formatClock(env, tok.pattern, time = true)
            Kind.Date -> text = formatClock(env, tok.pattern, time = false)
            Kind.Battery -> {
                val b = env.batteryPercent
                if (b == null) missing = true else { number = b.toDouble(); text = b.toString() }
            }
            Kind.BatteryFraction -> {
                val b = env.batteryPercent
                if (b == null) missing = true else {
                    number = (b.coerceIn(0, 100)) / 100.0
                    text = fractionText(number!!)
                }
            }
            Kind.BatteryState -> {
                text = batteryState(env)
                if (text.isEmpty()) empty = true
            }
            Kind.NextAlarm -> {
                val at = env.nextAlarm
                if (at == null) empty = true
                else text = formatInstant(at, tok.pattern, env, time = true)
            }
            Kind.Relative -> {
                val at = parseTime(scalar(env.data, tok.path!!)?.text, env.zone)
                if (at == null) missing = true else text = relative(at, env.now)
            }
        }
        for (f in tok.formatters) {
            if (missing || empty) {
                // default applies only to a missing or null path, not to a present empty string (§4.3).
                if (f is Fmt.Default && missing) {
                    text = f.text
                    missing = false
                    empty = text.isEmpty()
                    number = null
                    break
                }
                continue
            }
            when (f) {
                is Fmt.Default -> { /* present: no-op */ }
                is Fmt.Round -> {
                    val n = number ?: text.toDoubleOrNull()
                    if (n == null) { missing = true; text = ""; number = null }
                    else {
                        val rounded = round(n, f.n)
                        text = rounded
                        number = rounded.toDouble()
                    }
                }
                Fmt.Duration -> {
                    val n = number ?: text.toDoubleOrNull()
                    if (n == null) { missing = true; text = ""; number = null }
                    else { text = formatDuration(kotlin.math.round(n).toLong()); number = null }
                }
                Fmt.Since -> {
                    val at = parseTime(text, env.zone)
                    if (at == null) { missing = true; text = ""; number = null }
                    else {
                        val sec = java.time.Duration.between(at, env.now).seconds
                        text = formatDuration(sec)
                        number = null
                    }
                }
                is Fmt.Time -> {
                    val at = parseTime(text, env.zone)
                    if (at == null) { missing = true; text = ""; number = null }
                    else { text = formatInstant(at, f.pattern, env, time = true); number = null }
                }
            }
        }
        if (empty) return Value("", empty = true)
        if (missing) return Value(MISSING)
        return Value(text, number = number)
    }

    sealed interface Scalar {
        val text: String
        data class Text(override val text: String) : Scalar
        data class Num(val n: Double, override val text: String) : Scalar
        data object Empty : Scalar { override val text = "" }
    }

    /** Missing, JSON null, or a structured value is null. An empty string is [Scalar.Empty] (not drawn), not `—`. */
    fun scalar(root: JsonObject?, path: String): Scalar? {
        val v = WidgetData.lookup(root, path) ?: return null
        if (v is JsonNull) return null
        if (v is JsonArray || v is JsonObject) return null
        val p = v as? JsonPrimitive ?: return null
        if (p.content == "null" && !p.isString) return null
        if (p.isString) return if (p.content.isEmpty()) Scalar.Empty else Scalar.Text(p.content)
        val n = p.content.toDoubleOrNull() ?: return Scalar.Text(p.content)
        return Scalar.Num(n, p.content)
    }

    fun round(n: Double, digits: Int): String {
        val v = BigDecimal.valueOf(n).setScale(digits, RoundingMode.HALF_UP)
        return v.stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }
    }

    /** Seconds to `9h 3m`. Hours and minutes when at least one hour; minutes and seconds under one hour; `0s` for zero. */
    fun formatDuration(seconds: Long): String {
        val neg = seconds < 0
        var s = kotlin.math.abs(seconds)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val body = when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            m > 0 && sec > 0 -> "${m}m ${sec}s"
            m > 0 -> "${m}m"
            else -> "${sec}s"
        }
        return if (neg) "-$body" else body
    }

    /** `{relative}`: `just now` under 60 s, then `5 min ago`, `3 h ago`, `2 d ago`. */
    fun relative(at: Instant, now: Instant): String {
        val sec = java.time.Duration.between(at, now).seconds
        if (sec < 0) {
            // A future timestamp uses the same units with "from now".
            val ahead = -sec
            return when {
                ahead < 60 -> "just now"
                ahead < 3600 -> "${ahead / 60} min from now"
                ahead < 86400 -> "${ahead / 3600} h from now"
                else -> "${ahead / 86400} d from now"
            }
        }
        return when {
            sec < 60 -> "just now"
            sec < 3600 -> "${sec / 60} min ago"
            sec < 86400 -> "${sec / 3600} h ago"
            else -> "${sec / 86400} d ago"
        }
    }

    fun batteryState(env: Env): String {
        val b = env.batteryPercent ?: return ""
        return when {
            env.full || (env.charging && b >= 100) -> "Full"
            env.charging -> "Charging"
            b <= 15 -> "Low"
            else -> ""
        }
    }

    fun parseTime(text: String?, zone: ZoneId): Instant? {
        if (text.isNullOrEmpty()) return null
        return try { OffsetDateTime.parse(text).toInstant() } catch (_: DateTimeException) {
            try { ZonedDateTime.parse(text).toInstant() } catch (_: DateTimeException) {
                try { Instant.parse(text) } catch (_: DateTimeException) {
                    try { LocalDateTime.parse(text).atZone(zone).toInstant() } catch (_: DateTimeException) { null }
                }
            }
        }
    }

    private fun formatClock(env: Env, pattern: String?, time: Boolean): String =
        formatInstant(env.now, pattern, env, time)

    fun formatInstant(at: Instant, pattern: String?, env: Env, time: Boolean): String {
        val zdt = at.atZone(env.zone)
        val fmt = try {
            if (pattern != null) DateTimeFormatter.ofPattern(pattern, env.locale)
            else if (time) {
                // Pure Kotlin default (config/ cannot import android.text.format).
                // The host may pass a locale pattern via Env.timePattern later.
                val pat = env.timePattern ?: if (env.is24Hour) "H:mm" else "h:mm a"
                DateTimeFormatter.ofPattern(pat, env.locale)
            } else DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(env.locale)
        } catch (_: Exception) { return MISSING }
        return try { fmt.format(zdt) } catch (_: Exception) { MISSING }
    }

    private fun fractionText(n: Double): String {
        val s = BigDecimal.valueOf(n).stripTrailingZeros().toPlainString()
        return if (s == "-0") "0" else s
    }
}
