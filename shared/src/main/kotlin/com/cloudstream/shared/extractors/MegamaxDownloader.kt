package com.cloudstream.shared.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Shared resolver for MegaMax/Share4Max mirror download pages:
 * GET {mirrorHost}/download/{hash} lists per-quality attachments, then
 * POST {attach} returns the mirrors for that quality.
 *
 * Extracted from the (verified working) TukTukcima implementation so every
 * provider whose download path lands on this backend can reuse it.
 * Uses the global app client directly (mirror fronts are not CF-guarded).
 *
 * @return list of (mirrorUrl, qualityLabel), deduplicated by URL.
 */
object MegamaxDownloader {
    private const val TAG = "MegamaxDownloader"

    suspend fun resolveMirrors(downloadPageUrl: String, referer: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

            val pageReq = okhttp3.Request.Builder()
                .url(downloadPageUrl)
                .header("User-Agent", ua)
                .header("Referer", referer)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()
            val pageCookies: List<String>
            val pageText: String
            app.baseClient.newCall(pageReq).execute().use { res ->
                if (!res.isSuccessful) {
                    Log.w(TAG, "Download page HTTP ${res.code}: $downloadPageUrl")
                    return emptyList()
                }
                pageCookies = res.headers("Set-Cookie")
                pageText = res.body?.string().orEmpty()
            }
            if (pageText.isBlank()) return emptyList()

            val xsrf = pageCookies.find { it.startsWith("XSRF-TOKEN=") }
                ?.substringAfter("XSRF-TOKEN=")?.substringBefore(";")
                ?.let { try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { null } }
            val cookieHeader = pageCookies.joinToString("; ") { it.substringBefore(";") }

            val pageJson = Regex("<script data-page=\"app\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
                .find(pageText)?.groupValues?.get(1) ?: return emptyList()
            val version = Regex("\"version\":\"([^\"]+)\"").find(pageJson)?.groupValues?.get(1)
                ?: return emptyList()
            val attachments = try {
                JSONObject(pageJson).optJSONObject("props")?.optJSONArray("attachments")
            } catch (_: Exception) { null } ?: return emptyList()

            for (i in 0 until attachments.length()) {
                val attachHash = attachments.optJSONObject(i)?.optString("hashId")?.trim().orEmpty()
                val label = attachments.optJSONObject(i)?.optString("label").orEmpty()
                if (attachHash.isBlank()) continue

                try {
                    val body = JSONObject().put("attach", attachHash).toString()
                        .toRequestBody("application/json".toMediaType())
                    val request = okhttp3.Request.Builder()
                        .url(downloadPageUrl)
                        .header("X-Inertia", "true")
                        .header("X-Inertia-Version", version)
                        .header("X-Inertia-Partial-Component", "files/mirror/download")
                        .header("X-Inertia-Partial-Data", "download")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("User-Agent", ua)
                        .header("Referer", downloadPageUrl)
                        .apply {
                            if (!xsrf.isNullOrBlank()) header("X-XSRF-TOKEN", xsrf)
                            if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader)
                        }
                        .post(body)
                        .build()
                    val jsonText = app.baseClient.newCall(request).execute().use { res ->
                        if (!res.isSuccessful) "" else (res.body?.string() ?: "")
                    }
                    if (jsonText.isBlank()) continue
                    val mirrors = try {
                        JSONObject(jsonText).optJSONObject("props")
                            ?.optJSONObject("download")?.optJSONObject("data")
                            ?.optJSONArray("mirrors")
                    } catch (_: Exception) { null } ?: continue
                    for (j in 0 until mirrors.length()) {
                        val link = mirrors.optJSONObject(j)?.optString("link")?.trim().orEmpty()
                        if (link.isBlank()) continue
                        val finalLink = if (link.startsWith("//")) "https:$link" else link
                        if (!finalLink.startsWith("http")) continue
                        out.add(finalLink to label)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "attach $label failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveMirrors failed: ${e.message}")
        }
        return out.distinctBy { it.first }
    }
}
