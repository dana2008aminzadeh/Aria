package ir.aria.downloader

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Guards against regexes that compile on the JVM but **crash on Android**.
 *
 * Android does not use `java.util.regex`'s own engine: it delegates to ICU. The two disagree on
 * bare braces — `\{(\d+)-(\d+)}` is fine on a desktop JVM but ICU throws
 * `PatternSyntaxException: Syntax error in regexp pattern near index 14` as soon as the enclosing
 * object is initialised. That exact pattern took the app down on a real phone while every unit
 * test stayed green, so this test reads the production sources and applies ICU's stricter rule.
 */
class RegexSafetyTest {

    // ------------------------------------------------------------------ strict checker

    /** Returns human readable problems for constructs ICU rejects but the JVM accepts. */
    private fun icuFindings(pattern: String): List<String> {
        val problems = ArrayList<String>()
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    if (i + 1 >= pattern.length) problems.add("trailing backslash at $i")
                    // ICU supports Unicode property escapes such as \p{L}, \p{N} and
                    // \p{M}. Their braces are part of the escape, not bare quantifiers.
                    if (i + 2 < pattern.length &&
                        (pattern[i + 1] == 'p' || pattern[i + 1] == 'P') &&
                        pattern[i + 2] == '{'
                    ) {
                        val close = pattern.indexOf('}', i + 3)
                        if (close < 0) {
                            problems.add("unclosed Unicode property escape at $i")
                            return problems
                        }
                        i = close + 1
                        continue
                    }
                    i += 2
                    continue
                }
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '}' -> problems.add("unescaped '}' at index $i (ICU requires \\})")
                c == '{' -> {
                    val close = pattern.indexOf('}', i + 1)
                    if (close < 0) {
                        problems.add("unclosed '{' at index $i")
                    } else {
                        val inner = pattern.substring(i + 1, close)
                        val ok = inner.isNotEmpty() && (
                            inner.all { it.isDigit() } ||
                                (inner.contains(',') &&
                                    inner.substringBefore(',').all { it.isDigit() } &&
                                    inner.substringAfter(',').let { it.isEmpty() || it.all { ch -> ch.isDigit() } } &&
                                    inner.substringBefore(',').isNotEmpty())
                            )
                        if (!ok) problems.add("bare '{' at index $i is not a quantifier")
                        i = close
                    }
                }
            }
            i++
        }
        return problems
    }

    // ------------------------------------------------------------------ source scanning

    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError("cannot locate src/main/java from " + File(".").absolutePath)
    }

    private fun kotlinFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Reads the string literal that starts at [start] (must point at a quote). */
    private fun readLiteral(text: String, start: Int): Pair<String, Int>? {
        if (start >= text.length) return null
        if (text.startsWith("\"\"\"", start)) {
            val end = text.indexOf("\"\"\"", start + 3)
            if (end < 0) return null
            return text.substring(start + 3, end) to (end + 3)
        }
        if (text[start] != '"') return null
        val sb = StringBuilder()
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> return sb.toString() to (i + 1)
                c == '\\' && i + 1 < text.length -> {
                    when (val next = text[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        '\'' -> sb.append('\'')
                        '$' -> sb.append('$')
                        else -> sb.append('\\').append(next)
                    }
                    i += 2
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    @Test
    fun everyProductionRegexIsAcceptedByIcu() {
        val root = sourceRoot()
        val failures = ArrayList<String>()
        var checked = 0

        for (file in kotlinFiles(root)) {
            val text = file.readText()
            var index = text.indexOf("Regex(")
            while (index >= 0) {
                var cursor = index + "Regex(".length
                while (cursor < text.length && text[cursor].isWhitespace()) cursor++
                val literal = readLiteral(text, cursor)
                if (literal != null) {
                    val pattern = literal.first
                    val line = text.substring(0, index).count { it == '\n' } + 1
                    checked++
                    for (problem in icuFindings(pattern)) {
                        failures.add("${file.name}:$line  pattern=<$pattern>  -> $problem")
                    }
                }
                index = text.indexOf("Regex(", index + 1)
            }
        }

        assertTrue("no Regex( calls were found - the scan is broken", checked > 0)
        if (failures.isNotEmpty()) {
            fail("Android's ICU engine would reject these patterns:\n" + failures.joinToString("\n"))
        }
    }

    /** The checker itself must be able to see the bug that broke the phone. */
    @Test
    fun checkerDetectsThePatternThatCrashedTheApp() {
        val bad = """\{(\d+)-(\d+)}"""
        assertTrue(
            "the checker must flag a bare closing brace",
            icuFindings(bad).any { it.contains("unescaped '}'") }
        )
        assertTrue(icuFindings("""\{(\d+)-(\d+)\}""").isEmpty())
        assertTrue(icuFindings("""^file-\d+\.bin$""").isEmpty())
        assertTrue(icuFindings("""https?://[^\s<>"']+""").isEmpty())
        assertTrue(icuFindings("""bytes\s+(\d+)-(\d+)/(\d+|\*)""").isEmpty())
        assertTrue(icuFindings("""\p{L}+""").isEmpty())
        assertTrue(icuFindings("""[^\p{L}\p{N}]+""").isEmpty())
        assertTrue(icuFindings("""\p{M}+""").isEmpty())
        // legitimate quantifiers stay legal
        assertTrue(icuFindings("""^a{2}$""").isEmpty())
        assertTrue(icuFindings("""^a{2,}$""").isEmpty())
        assertTrue(icuFindings("""^a{2,4}$""").isEmpty())
        // braces inside a character class are literal
        assertTrue(icuFindings("""[{}]+""").isEmpty())
    }
}
