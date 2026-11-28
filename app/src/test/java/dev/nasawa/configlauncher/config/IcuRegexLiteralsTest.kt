package dev.nasawa.configlauncher.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * API 29's ICU regex engine rejects an unescaped literal `{` or `}` that is not a `{n,m}`
 * quantifier. The JVM's `java.util.regex` accepts that form, so a Robolectric test would not
 * have caught [Card]'s `TOKEN` pattern (`\{(data|fetch):[^}]*}`), which crashed in `Card.<clinit>`
 * on a real API 29 device. This scans `app/src/main` Regex / Pattern string literals the way ICU
 * would see them.
 */
class IcuRegexLiteralsTest {

    @Test fun flagsTheOldCardTokenAndAcceptsTheFixedOne() {
        // Pattern after Kotlin unescape is \{(data|fetch):[^}]*}; the final } is at index 20.
        assertEquals("unescaped } at 20", unescapedBrace("\\{(data|fetch):[^}]*}"))
        assertNull(unescapedBrace("\\{(data|fetch):[^}]*\\}"))
        assertNull(unescapedBrace("^[a-z0-9][a-z0-9_-]{0,63}$"))
        assertNull(unescapedBrace("^#[0-9A-Fa-f]{6}$"))
        assertNull(unescapedBrace("\\\$\\{secret:|\\{secret:"))
        assertNull(unescapedBrace("\\p{Mn}+"))
    }

    @Test fun mainSourcesHaveNoUnescapedLiteralBraces() {
        val root = mainSrc()
        val hits = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            extractRegexLiterals(f.readText()).forEach { (line, pattern) ->
                unescapedBrace(pattern)?.let { hits += "${f.relativeTo(root)}:$line: $it in /$pattern/" }
            }
        }
        assertTrue("no Regex literals found under ${root.path}", extractRegexLiterals(File(root, "java/dev/nasawa/configlauncher/config/Card.kt").readText()).isNotEmpty())
        assertEquals(hits.joinToString("\n"), emptyList<String>(), hits)
    }

    companion object {
        fun mainSrc(): File {
            val here = File("src/main/java")
            if (here.isDirectory) return File("src/main")
            val fromRepo = File("app/src/main")
            if (fromRepo.isDirectory) return fromRepo
            error("cannot find app/src/main (cwd=${File(".").canonicalPath})")
        }

        /**
         * First unescaped literal `{` / `}` that is not a `{n}`, `{n,}` or `{n,m}` quantifier
         * and is not inside a character class. Matches ICU's PatternSyntaxException rule.
         */
        fun unescapedBrace(pattern: String): String? {
            var i = 0
            var inClass = false
            while (i < pattern.length) {
                val c = pattern[i]
                if (c == '\\') {
                    if (i + 1 < pattern.length) {
                        val nxt = pattern[i + 1]
                        // \\p{...}, \\P{...}, \\N{...}, \\x{...} are legal ICU/Java escapes, not literal braces.
                        if (nxt in "pPxN" && i + 2 < pattern.length && pattern[i + 2] == '{') {
                            val close = pattern.indexOf('}', i + 3)
                            if (close < 0) return "unescaped { at ${i + 2}"
                            i = close + 1
                            continue
                        }
                    }
                    i += 2
                    continue
                }
                if (!inClass && c == '[') { inClass = true; i++; continue }
                if (inClass && c == ']') { inClass = false; i++; continue }
                if (!inClass && c == '{') {
                    val rest = pattern.substring(i + 1)
                    val q = Regex("^\\d+(,\\d*)?}").find(rest)
                    if (q == null) return "unescaped { at $i"
                    i += 1 + q.value.length
                    continue
                }
                if (!inClass && c == '}') return "unescaped } at $i"
                i++
            }
            return null
        }

        data class Lit(val line: Int, val pattern: String)

        fun extractRegexLiterals(src: String): List<Lit> {
            val out = mutableListOf<Lit>()
            var i = 0
            while (i < src.length) {
                when {
                    isCall(src, i, "Regex(") -> {
                        val parsed = parseString(src, skipWs(src, i + "Regex(".length))
                        if (parsed != null) {
                            out += Lit(lineOf(src, i), parsed.first)
                            i = parsed.second
                            continue
                        }
                    }
                    isCall(src, i, "Pattern.compile(") -> {
                        val parsed = parseString(src, skipWs(src, i + "Pattern.compile(".length))
                        if (parsed != null) {
                            out += Lit(lineOf(src, i), parsed.first)
                            i = parsed.second
                            continue
                        }
                    }
                    src.startsWith(".toRegex(", i) -> {
                        var k = i
                        while (k > 0 && src[k - 1].isWhitespace()) k--
                        parseStringEndingAt(src, k)?.let { out += Lit(lineOf(src, it.second), it.first) }
                    }
                }
                i++
            }
            return out
        }

        private fun isCall(src: String, i: Int, name: String): Boolean =
            src.startsWith(name, i) && (i == 0 || !src[i - 1].isLetterOrDigit())

        private fun skipWs(src: String, i: Int): Int {
            var j = i
            while (j < src.length && src[j].isWhitespace()) j++
            return j
        }

        private fun lineOf(src: String, i: Int): Int {
            var n = 1
            var j = 0
            while (j < i && j < src.length) {
                if (src[j] == '\n') n++
                j++
            }
            return n
        }

        private fun parseString(src: String, at: Int): Pair<String, Int>? = when {
            at >= src.length -> null
            src.startsWith("\"\"\"", at) -> rawString(src, at)
            src[at] == '"' -> escapedString(src, at)
            else -> null
        }

        /** [end] is the index just after a string literal. */
        private fun parseStringEndingAt(src: String, end: Int): Pair<String, Int>? {
            if (end >= 3 && src.startsWith("\"\"\"", end - 3)) {
                val open = src.lastIndexOf("\"\"\"", end - 4)
                if (open < 0) return null
                return src.substring(open + 3, end - 3) to open
            }
            if (end > 0 && src[end - 1] == '"') {
                var i = end - 2
                while (i >= 0) {
                    if (src[i] == '"') {
                        var bs = 0
                        var k = i - 1
                        while (k >= 0 && src[k] == '\\') { bs++; k-- }
                        if (bs % 2 == 0) return unescape(src.substring(i + 1, end - 1)) to i
                    }
                    i--
                }
            }
            return null
        }

        private fun rawString(src: String, at: Int): Pair<String, Int>? {
            if (!src.startsWith("\"\"\"", at)) return null
            val end = src.indexOf("\"\"\"", at + 3)
            if (end < 0) return null
            return src.substring(at + 3, end) to (end + 3)
        }

        private fun escapedString(src: String, at: Int): Pair<String, Int>? {
            if (src[at] != '"') return null
            val buf = StringBuilder()
            var i = at + 1
            while (i < src.length) {
                val c = src[i]
                if (c == '"') return buf.toString() to (i + 1)
                if (c == '\\' && i + 1 < src.length) {
                    val (ch, next) = unescapeChar(src[i + 1], src, i + 1)
                    buf.append(ch)
                    i = next
                    continue
                }
                if (c == '\n') return null
                buf.append(c); i++
            }
            return null
        }

        private fun unescape(s: String): String {
            val buf = StringBuilder()
            var i = 0
            while (i < s.length) {
                if (s[i] == '\\' && i + 1 < s.length) {
                    val (ch, next) = unescapeChar(s[i + 1], s, i + 1)
                    buf.append(ch); i = next
                } else { buf.append(s[i]); i++ }
            }
            return s.let { buf.toString() }
        }

        private fun unescapeChar(c: Char, s: String, at: Int): Pair<Char, Int> = when (c) {
            'n' -> '\n' to (at + 1)
            't' -> '\t' to (at + 1)
            'r' -> '\r' to (at + 1)
            'b' -> '\b' to (at + 1)
            '\\', '"', '\'', '$' -> c to (at + 1)
            'u' if at + 4 < s.length -> s.substring(at + 1, at + 5).toIntOrNull(16)?.toChar()?.let { it to (at + 5) } ?: (c to (at + 1))
            else -> c to (at + 1) // \{, \}, \d, etc. keep the escaped char as in the regex pattern
        }
    }
}
