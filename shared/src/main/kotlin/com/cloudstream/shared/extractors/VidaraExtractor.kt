package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Extractor for Vidara (vidaraa.cc) embeds.
 *
 * Page is a JS app; the stream comes from POST {host}/api/stream
 * with {"filecode":"<id from /e/<id>>"} which returns
 * {"streaming_url":"https://.../master.m3u8?token=...","subtitles":...}.
 */
class VidaraExtractor(
    val host: String = "vidaraa.cc",
    override val name: String = "Vidara"
) : ExtractorApi() {
    override val mainUrl: String get() = "https://$host"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val methodTag = "[$name] [getUrl]"
        try {
            val filecode = Regex("/(?:e|v|d)/([A-Za-z0-9]+)").find(url)?.groupValues?.get(1)
            if (filecode.isNullOrBlank()) {
                Log.w(methodTag, "No filecode in $url")
                return
            }

            val body = JSONObject().put("filecode", filecode).toString()
                .toRequestBody("application/json".toMediaType())
            val request = okhttp3.Request.Builder()
                .url("$mainUrl/api/stream")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Referer", url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
                )
                .post(body)
                .build()

            val responseBody = app.baseClient.newCall(request).execute().use {
                if (!it.isSuccessful) {
                    Log.w(methodTag, "HTTP ${it.code} for $filecode")
                    return
                }
                it.body?.string().orEmpty()
            }
            if (responseBody.isBlank()) return

            val streamingUrl = try {
                JSONObject(responseBody).optString("streaming_url")
            } catch (_: Exception) { "" }
            if (streamingUrl.isBlank()) {
                Log.w(methodTag, "No streaming_url for $filecode")
                return
            }

            Log.d(methodTag, "Streaming URL: ${streamingUrl.take(100)}")
            for (link in M3u8Helper.generateM3u8(
                source = name,
                streamUrl = streamingUrl,
                referer = "$mainUrl/",
                name = name
            )) {
                callback(link)
            }
        } catch (e: Exception) {
            Log.e(methodTag, "Error extracting Vidara: ${e.message}")
        }
    }
}
