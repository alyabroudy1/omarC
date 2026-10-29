package com.egydead

import org.jsoup.nodes.Document
import java.net.URLDecoder

/**
 * Helpers for the current EgyDead URL layout:
 *   /Series/<slug>/          -> parent series, seasons live in .seasons-list
 *   /seasons/<slug>/         -> one season, episodes live in .EpsList
 *   /episodes/<slug>/        -> one episode, breadcrumb links back to its season
 *
 * The site is Arabic-first, so season numbers appear either as an `s05` token in the
 * slug or as an Arabic ordinal in the title ("الموسم الخامس").
 */
internal object EgyDeadSeries {

    private val arabicOrdinals: List<Pair<String, Int>> = listOf(
        "الثاني عشر" to 12,
        "الحادي عشر" to 11,
        "العاشر" to 10,
        "التاسع" to 9,
        "الثامن" to 8,
        "السابع" to 7,
        "السادس" to 6,
        "الخامس" to 5,
        "الرابع" to 4,
        "الثالث" to 3,
        "الثاني" to 2,
        "الاول" to 1,
    ).sortedByDescending { it.first.length }

    fun isSeriesUrl(url: String): Boolean {
        return Regex("""/(?:series|serie|seasons?|episodes?)/""", RegexOption.IGNORE_CASE)
            .containsMatchIn(url)
    }

    /**
     * Find the season page linked from an episode breadcrumb. The category link
     * (`/seasons/`) is deliberately ignored.
     */
    fun seasonUrlFromBreadcrumb(doc: Document): String? {
        return doc.select("div.breadcrumbs-single a[href], .breadcrumbs-single a[href]")
            .map { it.absUrl("href").ifBlank { it.attr("href") }.trim() }
            .firstOrNull {
                it.contains("/seasons/", ignoreCase = true) &&
                    it.substringAfter("/seasons/", "").trim('/').isNotEmpty()
            }
    }

    /**
     * Detect a season number from any of the supplied strings (URL, title, episode URL).
     * Returns null when the string contains no usable season token.
     */
    fun seasonNumber(vararg candidates: String?): Int? {
        for (raw in candidates) {
            if (raw.isNullOrBlank()) continue
            val text = normalize(raw)

            Regex(
                """(?:^|[\s/])s(?:eason)?\s*0*(\d{1,3})(?:e\d+)?(?:[\s/]|$)""",
                RegexOption.IGNORE_CASE
            ).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

            Regex(
                """(?:^|[\s/])(?:season|الموسم|موسم)\s*0*(\d{1,3})(?:[\s/]|$)""",
                RegexOption.IGNORE_CASE
            ).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

            arabicOrdinals.firstOrNull { text.contains(it.first) }?.let { return it.second }
        }
        return null
    }

    /**
     * Detect an episode number. Text ("حلقه 16") wins over the slug; the slug fallback
     * understands both `s05e16` and older `...-e16` spellings.
     */
    fun episodeNumber(url: String, text: String): Int? {
        val normalizedText = normalize(text)
        Regex("""(?:الحلقة|حلقة|حلقه)\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
            .find(normalizedText)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        val normalizedUrl = normalize(url)
        Regex("""s\d{1,3}e(\d{1,4})""", RegexOption.IGNORE_CASE)
            .find(normalizedUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("""(?:^|[-_/\s])e(?:p|pisode)?\s*(\d{1,4})(?:[-_/\s]|$)""", RegexOption.IGNORE_CASE)
            .find(normalizedUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        Regex("""(\d{1,4})""").find(normalizedText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { return it }
        return Regex("""(\d{1,4})""").find(normalizedUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun normalize(value: String): String {
        val decoded = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
        return decoded
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .replace('-', ' ')
            .replace('_', ' ')
            .replace(Regex("""[\u064B-\u065F\u0670]"""), "")
            .lowercase()
    }
}
