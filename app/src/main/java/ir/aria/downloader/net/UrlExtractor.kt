package ir.aria.downloader.net

import java.net.URI

/**
 * Turns arbitrary pasted text into a clean, de-duplicated list of http(s) urls, and expands
 * numeric range patterns such as:
 *   https://host/img{1-12}.jpg      -> img1.jpg ... img12.jpg
 *   https://host/s{01-03}/a.png     -> s01/a.png ... s03/a.png
 *   https://host/{1-2}/{1-2}.mp4    -> cartesian product of the numeric ranges
 */
object UrlExtractor {

    private val URL_REGEX = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
    private val TRAILING = charArrayOf(
        '.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'', '،', '؛', '»', '…'
    )
    private val LEADING = charArrayOf('"', '\'', '<', '(', '[', '«', '“', '‘')

    /** Extracts urls from free text (one per line, comma separated, html, chat messages, ...). */
    fun extract(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (match in URL_REGEX.findAll(text)) {
            val cleaned = clean(match.value)
            if (isValid(cleaned)) out.add(cleaned)
        }
        return out.toList()
    }

    fun clean(raw: String): String {
        var value = raw.trim()
        // strip wrapping quotes/brackets that are almost never part of a url
        while (value.isNotEmpty() && LEADING.contains(value.first())) {
            value = value.drop(1)
        }
        // strip trailing punctuation that is almost never part of a url
        var changed = true
        while (changed && value.isNotEmpty()) {
            changed = false
            val last = value.last()
            if (TRAILING.contains(last)) {
                value = value.dropLast(1)
                changed = true
            }
        }
        return value
    }

    fun isValid(url: String): Boolean {
        if (url.length > 4096) return false
        if (url.any { it.isWhitespace() }) return false
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        // Batch patterns ({1-10}) have to survive validation before they are expanded.
        val probe = url.replace("{", "%7B").replace("}", "%7D").replace("|", "%7C")
        return try {
            val uri = URI(probe)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Expands every numeric `{from-to}` token in every line (cartesian product across tokens).
     * Result is capped at [limit] entries to protect memory.
     */
    fun expandPatterns(lines: List<String>, limit: Int = 2000): List<String> {
        val out = LinkedHashSet<String>()
        for (line in lines) {
            if (!line.contains('{')) {
                out.add(line)
            } else {
                for (expanded in expandLine(line, limit)) {
                    out.add(expanded)
                    if (out.size >= limit) return out.toList()
                }
            }
            if (out.size >= limit) return out.toList()
        }
        return out.toList()
    }

    /** One `{from-to}` token found inside a url. */
    private class RangeToken(val start: Int, val end: Int, val left: String, val right: String)

    /**
     * Finds the first `{123-456}` token without using a regex.
     *
     * The pattern used to be `\{(\d+)-(\d+)}`, which the JVM accepts but Android's ICU regex
     * engine rejects ("Syntax error in regexp pattern near index 14") — the whole object then
     * failed to initialise and the app crashed on the first pasted link. A plain scanner has no
     * such platform differences and is easier to reason about.
     */
    private fun findRange(text: String, fromIndex: Int = 0): RangeToken? {
        var open = text.indexOf('{', fromIndex)
        while (open >= 0) {
            val close = text.indexOf('}', open + 1)
            if (close < 0) return null
            val inner = text.substring(open + 1, close)
            val dash = inner.indexOf('-')
            if (dash > 0 && dash < inner.length - 1) {
                val left = inner.substring(0, dash)
                val right = inner.substring(dash + 1)
                if (left.isNotEmpty() && right.isNotEmpty() &&
                    left.all { it in '0'..'9' } && right.all { it in '0'..'9' } &&
                    left.length <= 9 && right.length <= 9
                ) {
                    return RangeToken(open, close, left, right)
                }
            }
            open = text.indexOf('{', open + 1)
        }
        return null
    }

    fun expandLine(line: String, limit: Int = 2000): List<String> {
        var results = listOf(line)
        var guard = 0
        while (guard++ < 6) {
            var replacedAny = false
            val next = ArrayList<String>()
            for (candidate in results) {
                val token = findRange(candidate)
                if (token == null) {
                    next.add(candidate)
                    continue
                }
                replacedAny = true
                val left = token.left
                val right = token.right
                val from = left.toLongOrNull() ?: 0L
                val to = right.toLongOrNull() ?: 0L
                val pad = if ((left.length > 1 && left.startsWith("0")) ||
                    (right.length > 1 && right.startsWith("0"))
                ) maxOf(left.length, right.length) else 0
                val step = if (from <= to) 1L else -1L
                var value = from
                while ((step > 0 && value <= to) || (step < 0 && value >= to)) {
                    val number = if (pad > 0) value.toString().padStart(pad, '0') else value.toString()
                    next.add(candidate.substring(0, token.start) + number + candidate.substring(token.end + 1))
                    if (next.size > limit) return next.take(limit)
                    value += step
                }
            }
            results = next
            if (!replacedAny) break
        }
        return results.take(limit)
    }

    /** Full pipeline used by the add screen: extract -> expand patterns -> validate -> dedupe. */
    fun parse(text: String, limit: Int = 2000): List<String> {
        val raw = extract(text)
        val expanded = expandPatterns(raw, limit)
        val out = LinkedHashSet<String>()
        for (url in expanded) {
            if (isValid(url)) out.add(url)
        }
        return out.toList()
    }
}
