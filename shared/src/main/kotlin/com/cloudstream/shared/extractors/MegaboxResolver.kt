package com.cloudstream.shared.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import org.json.JSONObject

/**
 * Shared resolver for Megabox/Share4max/Megamax video servers.
 * Uses Inertia.js XHR requests to extract stream mirror URLs.
 * Extracted from duplicate implementations in Animerco and Anime4up providers.
 */
object MegaboxResolver {
    private const val TAG = "MegaboxResolver"

    /**
     * Process a Megabox URL and extract mirror iframe URLs via Inertia.js API.
     *
     * @param url The megabox/share4max/megamax page URL
     * @param referer The referer to use for requests
     * @return List of resolved mirror iframe URLs
     */
    suspend fun process(url: String, referer: String): List<String> {
        val extractedIframes = mutableListOf<String>()

        try {
            Log.d(TAG, "Processing Megabox URL: $url")

            val initialResponse = app.get(url, referer = referer)
            val soup = initialResponse.document
            val version = soup.selectFirst("script[data-page=app]")?.html()?.let {
                try { JSONObject(it).optString("version") } catch (_: Exception) { null }
            }

            if (version == null) {
                Log.w(TAG, "No Inertia version found for: $url")
                return emptyList()
            }

            val inertiaHeaders = mapOf(
                "X-Inertia" to "true",
                "X-Inertia-Partial-Component" to "files/mirror/video",
                "X-Inertia-Partial-Data" to "streams",
                "X-Inertia-Version" to version,
                "X-Requested-With" to "XMLHttpRequest"
            )

            val streamResponse = app.get(url, headers = inertiaHeaders, referer = referer)
            val streamJson = JSONObject(streamResponse.text)
            val data = streamJson.optJSONObject("props")
                ?.optJSONObject("streams")
                ?.optJSONArray("data")

            if (data != null) {
                for (i in 0 until data.length()) {
                    val qualityLevel = data.optJSONObject(i) ?: continue
                    val mirrors = qualityLevel.optJSONArray("mirrors")
                    if (mirrors != null) {
                        for (j in 0 until mirrors.length()) {
                            val mirror = mirrors.optJSONObject(j) ?: continue
                            val link = mirror.optString("link")
                            if (link.isNotBlank()) {
                                val finalUrl = if (link.startsWith("//")) "https:$link" else link
                                extractedIframes.add(finalUrl)
                            }
                        }
                    }
                }
            }

            Log.d(TAG, "Extracted ${extractedIframes.size} mirror URLs")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process Megabox URL: ${e.message}")
        }

        return extractedIframes
    }
}
