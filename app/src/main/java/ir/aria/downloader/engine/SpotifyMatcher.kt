package ir.aria.downloader.engine

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Ranks source candidates against Spotify metadata instead of blindly taking search result #1. */
object SpotifyMatcher {
    const val MIN_CONFIDENCE = 65

    data class Match(val hit: ExtractorEngine.SearchHit, val score: Int)

    fun bestMatch(track: SpotifyResolver.Track, hits: List<ExtractorEngine.SearchHit>): Match? =
        hits.filter { it.url.isNotBlank() }
            .filter { titleCoverage(SpotifyResolver.cleanTitle(track.title), it.title) >= 0.60 }
            .distinctBy { it.url }
            .map { Match(it, score(track, it)) }
            .maxByOrNull { it.score }
            ?.takeIf { it.score >= MIN_CONFIDENCE }

    fun score(track: SpotifyResolver.Track, hit: ExtractorEngine.SearchHit): Int {
        val cleanTarget = SpotifyResolver.cleanTitle(track.title)
        var result = titleScore(cleanTarget, hit.title)
        result += artistScore(track.artists, hit.title + " " + hit.uploader)
        result += durationScore(track.durationSec, hit.durationSec)

        val targetFlags = versionFlags(track.title)
        val candidateFlags = versionFlags(hit.title + " " + hit.uploader)
        for (flag in HARD_VARIANTS) {
            val wanted = flag in targetFlags
            val offered = flag in candidateFlags
            result += when {
                wanted && offered -> 5
                wanted != offered -> -60
                else -> 0
            }
        }
        if (("remaster" in targetFlags) != ("remaster" in candidateFlags)) result -= 5

        val channel = normalize(hit.uploader)
        val officialText = normalize(hit.uploader + " " + hit.title)
        if (containsPhrase(channel, "topic")) result += 7
        if (containsPhrase(officialText, "official") || channel.endsWith("vevo")) result += 5
        return result
    }

    private fun titleCoverage(target: String, candidate: String): Double {
        val targetTokens = tokens(normalize(target))
        val candidateTokens = tokens(normalize(candidate))
        if (targetTokens.isEmpty() || candidateTokens.isEmpty()) return 0.0
        return targetTokens.intersect(candidateTokens).size.toDouble() / targetTokens.size
    }

    private fun titleScore(target: String, candidate: String): Int {
        val targetNormalized = normalize(target)
        val candidateNormalized = normalize(candidate)
        val targetTokens = tokens(targetNormalized)
        val candidateTokens = tokens(candidateNormalized)
        if (targetTokens.isEmpty() || candidateTokens.isEmpty()) return 0
        val common = targetTokens.intersect(candidateTokens).size
        val coverage = common.toDouble() / targetTokens.size
        val precision = common.toDouble() / candidateTokens.size
        val exact = containsPhrase(candidateNormalized, targetNormalized)
        return (coverage * 30.0 + precision * 10.0 + if (exact) 10.0 else 0.0)
            .roundToInt().coerceIn(0, 50)
    }

    private fun artistScore(artists: String, candidateText: String): Int {
        val candidate = normalize(candidateText)
        val candidateTokens = tokens(candidate)
        val primary = normalize(SpotifyResolver.primaryArtist(artists))
        val primaryTokens = tokens(primary)
        if (primaryTokens.isEmpty()) return 0
        val common = primaryTokens.count { it in candidateTokens }
        var score = when {
            containsPhrase(candidate, primary) -> 25
            common == primaryTokens.size -> 23
            else -> (15.0 * common / primaryTokens.size).roundToInt()
        }
        val otherArtists = artists.split(',', '&').map { it.trim() }
            .filter { it.isNotBlank() && it != SpotifyResolver.primaryArtist(artists) }
        var bonus = 0
        for (artist in otherArtists) {
            if (containsPhrase(candidate, normalize(artist))) bonus += 3
        }
        score += bonus.coerceAtMost(5)
        return score
    }

    private fun durationScore(expected: Long?, actual: Long?): Int {
        if (expected == null || expected <= 0 || actual == null || actual <= 0) return 0
        val difference = abs(expected - actual)
        return when {
            difference <= 2 -> 30
            difference <= 5 -> 25
            difference <= 10 -> 18
            difference <= 20 -> 8
            difference <= 30 -> 0
            difference <= 60 -> -25
            difference <= 120 -> -50
            else -> -70
        }
    }

    private val VARIANT_PATTERNS = mapOf(
        "cover" to Regex("""\bcovers?\b"""),
        "karaoke" to Regex("""\bkaraoke\b"""),
        "remix" to Regex("""\bremix(?:ed)?\b"""),
        "live" to Regex("""\b(?:live|unplugged)\b"""),
        "acoustic" to Regex("""\bacoustic\b"""),
        "instrumental" to Regex("""\binstrumental\b"""),
        "sped_up" to Regex("""\b(?:sped up|speed up|nightcore)\b"""),
        "slowed" to Regex("""\b(?:slowed|slow down)\b"""),
        "reverb" to Regex("""\breverb\b"""),
        "remaster" to Regex("""\bremaster(?:ed)?\b""")
    )

    private val HARD_VARIANTS = VARIANT_PATTERNS.keys - "remaster"

    private fun versionFlags(text: String): Set<String> {
        val normalized = normalize(text)
        return VARIANT_PATTERNS.filterValues { it.containsMatchIn(normalized) }.keys
    }

    internal fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return decomposed.replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
    }

    private fun tokens(text: String): Set<String> =
        text.split(' ').filter { it.isNotBlank() }.toSet()

    private fun containsPhrase(text: String, phrase: String): Boolean =
        phrase.isNotBlank() && " $text ".contains(" $phrase ")
}
