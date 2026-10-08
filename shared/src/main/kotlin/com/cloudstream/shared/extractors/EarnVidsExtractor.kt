package com.cloudstream.shared.extractors

import com.cloudstream.shared.core.Fingerprint
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import android.util.Log
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.delay

/**
 * Every host the EarnVids family is known under, as one place.
 *
 * The union of what [registerSharedExtractors] registers and what the four deleted per-provider
 * `ExternalEarnVidsExtractor` copies (Animerco, Lodynet, Replaymatch, Shahid4u) reached, including
 * `fdewsdc.sbs`, which those copies handled by referer special case but which was never registered.
 *
 * [registerSharedExtractors] registers straight from this list, so there is exactly one list (F9).
 */
internal val EARNVIDS_REGISTRATIONS: List<Pair<String, String>> = listOf(
    "earnvids.com" to "EarnVids",
    "dingtezuni.com" to "EarnVids",
    "fsdcmo.sbs" to "StreamGH",
    "govid.live" to "GoVid",
    "1vid1shar.space" to "Vid1Shar",
    "mycima.page" to "MyCima",
    // Folded from the four deleted copies: the referer-hijack host they all special-cased.
    "fdewsdc.sbs" to "Fdewsdc"
)

/** Derived from [EARNVIDS_REGISTRATIONS] so the matcher and the registry can never drift (F9). */
internal val EARNVIDS_HOSTS: List<String> = EARNVIDS_REGISTRATIONS.map { it.first }

/** Pure host test — no Android, no network. Used by the extractor and by its unit test. */
internal fun matchesEarnVidsHost(url: String): Boolean =
    EARNVIDS_HOSTS.any { url.contains(it, ignoreCase = true) }

/** Resolves a payload-extracted link against the embed URL it came from. Pure. */
internal fun normalizeVideoUrl(videoUrl: String, pageUrl: String): String = when {
    videoUrl.startsWith("//") -> "https:$videoUrl"
    videoUrl.startsWith("/") -> try {
        java.net.URI(pageUrl).resolve(videoUrl).toString()
    } catch (e: Exception) {
        videoUrl
    }
    else -> videoUrl
}

interface EarnVidsObfuscationStrategy {
    /** [pageUrl] is the embed URL the payload was fetched from; used for `location` substitution. */
    fun decode(response: String, pageUrl: String): String?
}

class HexObfuscationStrategy : EarnVidsObfuscationStrategy {
    override fun decode(response: String, pageUrl: String): String? {
        val hexRegex = Regex("""const\s+[A-Za-z0-9_]+\s*=\s*["']([0-9a-fA-F]{40,})["']""")
        val hexMatch = hexRegex.find(response) ?: return null
        val hexStr = hexMatch.groupValues[1]
        var decodedUrl = ""
        for (i in 0 until hexStr.length step 2) {
            try {
                decodedUrl += hexStr.substring(i, i + 2).toInt(16).toChar()
            } catch (e: Exception) {
                break
            }
        }
        return if (decodedUrl.startsWith("http")) decodedUrl else null
    }
}

class PackerObfuscationStrategy : EarnVidsObfuscationStrategy {
    override fun decode(response: String, pageUrl: String): String? {
        var currentPayload = response
        var unpackedPayload = ""
        val packedRegex = Regex("""eval\(function\(p,a,c,k,e,[d|r]\)\{.*?\}\(\s*['"](.+?)['"]\s*,\s*(\d+)\s*,\s*\d+\s*,\s*['"](.+?)['"]""")
        
        // Unpack recursively up to 4 times
        for (i in 1..4) {
            val match = packedRegex.find(currentPayload)
            if (match == null) {
                if (i == 1) Log.w("EarnVidsExtractor", "❌ No eval(function) found on pass $i, exiting unpacker.")
                break
            }
            
            val payloadRaw = match.groupValues[1]
            val radixStr = match.groupValues[2]
            val sympipe = match.groupValues[3]
            
            val radix = radixStr.toIntOrNull() ?: 36
            val symtab = sympipe.split("|")
            
            // Folded from the deleted copies: substitute the real page URL for `location`, not "",
            // so payloads that build their link out of location.href still yield an absolute URL.
            val payload = payloadRaw
                .replace("location.href", "'$pageUrl'")
                .replace("location", "'$pageUrl'")
                .replace("document.cookie", "''")
                .replace("window.location", "'$pageUrl'")
                .replace("window", "this")
                
            val tokenRe = Regex("""\b[0-9a-zA-Z]+\b""")
            val unpacked = tokenRe.replace(payload) { mo ->
                val tok = mo.value
                try {
                    val idx = tok.toInt(radix)
                    if (idx in symtab.indices && symtab[idx].isNotEmpty()) symtab[idx] else tok
                } catch (e: Exception) {
                    tok
                }
            }
            unpackedPayload = unpacked
            currentPayload = unpacked
            Log.d("EarnVidsExtractor", "🔄 Unpack iteration $i successful (Length: ${unpacked.length})")
        }
        
        if (unpackedPayload.isEmpty()) return null
        
        // Target 1: sources: [{ file: "..." }]
        val sourceRegex = Regex("""sources:\s*\[\s*\{.*?(?:file|src):\s*['"](.*?)['"].*?\}\s*\]""")
        val sourceMatch = sourceRegex.find(unpackedPayload)
        if (sourceMatch != null) {
            Log.d("EarnVidsExtractor", "✅ Extracted M3U8 via [sources] syntax: ${sourceMatch.groupValues[1]}")
            return sourceMatch.groupValues[1]
        }
        
        // Target 2: var links = { "hls4": "...", "hls": "..." }
        val linksRegex = Regex("""var\s+links\s*=\s*(\{.*?})\s*;""", RegexOption.DOT_MATCHES_ALL)
        val linksMatch = linksRegex.find(unpackedPayload)
        
        if (linksMatch != null) {
            Log.d("EarnVidsExtractor", "🔎 Found [var links] JSON dictionary instead of sources.")
            val jsonStr = linksMatch.groupValues[1].replace("'", "\"")
            // Basic regex parsing to extract the keys gracefully without org.json
            val hls4Regex = Regex(""""hls4"\s*:\s*"([^"]+)"""")
            val hlsRegex = Regex(""""hls"\s*:\s*"([^"]+)"""")
            
            val hls4Val = hls4Regex.find(jsonStr)?.groupValues?.get(1)
            if (!hls4Val.isNullOrBlank()) {
                Log.d("EarnVidsExtractor", "✅ Extracted M3U8 via [var links -> hls4]: $hls4Val")
                return hls4Val
            }
            
            val hlsVal = hlsRegex.find(jsonStr)?.groupValues?.get(1)
            if (!hlsVal.isNullOrBlank()) {
                Log.d("EarnVidsExtractor", "✅ Extracted M3U8 via [var links -> hls]: $hlsVal")
                return hlsVal
            }
        }
        
        // Target 3: Quick fallback if JSON bracket wasn't matched but keys are there
        val fallbackHls4 = Regex(""""hls4"\s*:\s*"([^"]+)"""").find(unpackedPayload)?.groupValues?.get(1)
        if (!fallbackHls4.isNullOrBlank()) {
            Log.d("EarnVidsExtractor", "✅ Extracted M3U8 via Regex [\"hls4\":]: $fallbackHls4")
            return fallbackHls4
        }
        
        val fallbackHls = Regex(""""hls"\s*:\s*"([^"]+)"""").find(unpackedPayload)?.groupValues?.get(1)
        if (!fallbackHls.isNullOrBlank()) {
            Log.d("EarnVidsExtractor", "✅ Extracted M3U8 via Regex [\"hls\":]: $fallbackHls")
            return fallbackHls
        }
        
        Log.w("EarnVidsExtractor", "❌ Finished all unpackings but no valid M3U8 links were intercepted!")
        return null
    }
}

/**
 * Folded from the four deleted copies: scan the raw HTML for a bare `.m3u8` before giving up.
 *
 * Runs last, not first as in the copies, so it can only add a link where hex and packer both found
 * nothing — the copies' ordering would have let a decoy in the markup win over a decoded one.
 */
class RawM3u8Strategy : EarnVidsObfuscationStrategy {
    override fun decode(response: String, pageUrl: String): String? {
        val match = Regex("""https?://[^'"\s>]+?\.m3u8[^'"\s>]*""", RegexOption.IGNORE_CASE)
            .find(response) ?: return null
        return match.value.replace("\\/", "/")
    }
}

/**
 * The one strategy pipeline, in order: decoders first, the raw-`.m3u8` scan last.
 *
 * Both entry points ([EarnVidsExtractor.getUrl] and [EarnVidsExtractor.extractDirect]) run exactly
 * this list, and `EarnVidsStrategyOrderTest` pins the order.
 */
internal val EARNVIDS_STRATEGIES: List<EarnVidsObfuscationStrategy> = listOf(
    HexObfuscationStrategy(),
    PackerObfuscationStrategy(),
    RawM3u8Strategy()
)

class EarnVidsExtractor(
    override val mainUrl: String = "earnvids.com",
    private val displayName: String = "EarnVids"
) : ExtractorApi() {
    override val name = displayName
    override val requiresReferer = true
    
    private val strategies = EARNVIDS_STRATEGIES

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val finalUrl = if (url.startsWith("http")) url else "https:$url"
        Log.d("EarnVidsExtractor", "▶️ Initializing Phase 2 Extractor | Domain: $name | URL: $finalUrl")
        if (!matchesEarnVidsHost(finalUrl)) {
            Log.w("EarnVidsExtractor", "⚠️ $finalUrl is not a known EarnVids host — trying anyway")
        }
        try {
            // 1. Spoof Desktop UA headers & standard Accept headers
            // 2. Intercept fdewsdc.sbs and forcefully enforce referer validation
            val activeReferer = if (finalUrl.contains("fdewsdc.sbs", ignoreCase = true)) {
                Log.d("EarnVidsExtractor", "🌐 [fdewsdc.sbs Match] Hijacking Referer to https://shhahid4u.cam")
                "https://shhahid4u.cam"
            } else {
                // Registered `getUrl` path: referer is the embed/final URL, exactly as before the fold
                // (the caller-supplied `referer` argument is deliberately ignored here).
                Log.d("EarnVidsExtractor", "🌐 Using explicit embed finalUrl as Referer: $finalUrl")
                finalUrl
            }
            
            val headers = mapOf(
                "User-Agent" to Fingerprint.current().userAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Connection" to "keep-alive"
            )
            
            Log.d("EarnVidsExtractor", "📥 Requesting payload with spoofed Windows headers...")
            val response = app.get(finalUrl, referer = activeReferer, headers = headers).text
            Log.d("EarnVidsExtractor", "✅ Response acquired. Length: ${response.length}")
            
            // Extract proprietary Javascript cookies (e.g. $.cookie('file_id', ...))
            val jsCookies = Regex("""\$\.cookie\(['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]""")
                .findAll(response)
                .map { "${it.groupValues[1]}=${it.groupValues[2]}" }
                .distinct()
                .toList()
                
            // Natively extract Cloudflare clearance & session cookies from Android WebView
            val webViewCookieString = android.webkit.CookieManager.getInstance().getCookie(finalUrl) ?: ""
            val webViewCookies = webViewCookieString.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            
            // Merge JS Cookies and WebView Cookies securely without duplicating keys!
            val mergedCookieMap = mutableMapOf<String, String>()
            for (cookie in jsCookies + webViewCookies) {
                if (cookie.contains("=")) {
                    val split = cookie.split("=", limit = 2)
                    mergedCookieMap[split[0]] = split[1]
                }
            }
            
            val finalCookieHeader = mergedCookieMap.map { "${it.key}=${it.value}" }.joinToString("; ")

            
            if (finalCookieHeader.isNotEmpty()) {
                Log.d("EarnVidsExtractor", "🍪 Assembled Master Cookies! Attaching to headers: $finalCookieHeader")
            } else {
                Log.w("EarnVidsExtractor", "⚠️ No cookies intercepted from WebView or HTML.")
            }

            val customHeaders = Fingerprint.current()
                .playbackHeaders(activeReferer, null, finalCookieHeader)
            
            for (strategy in strategies) {
                var videoUrl = strategy.decode(response, finalUrl)
                if (!videoUrl.isNullOrBlank()) {
                    videoUrl = normalizeVideoUrl(videoUrl, finalUrl)
                    if (videoUrl.startsWith("http")) {
                        Log.d("EarnVidsExtractor", "🥇 Extraction Success via Strategy: ${strategy::class.java.simpleName}")
                        Log.d("EarnVidsExtractor", "🔗 Handing off Link to ExoPlayer: $videoUrl")
                        
                        callback(
                            com.lagradost.cloudstream3.utils.newExtractorLink(
                                source = name,
                                name = name,
                                url = videoUrl,
                                type = if (videoUrl.contains(".m3u8", ignoreCase = true)) com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8 else com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
                            ) {
                                this.referer = activeReferer
                                this.quality = Qualities.Unknown.value
                                this.headers = customHeaders
                            }
                        )
                        return // Stop after first successful decode
                    }
                }
            }
            Log.e("EarnVidsExtractor", "🛑 All extraction strategies failed heavily against $finalUrl")
        } catch (e: Exception) {
            Log.e("EarnVidsExtractor", "❌ Catastrophic Error running EarnVidsExtractor: ${e.message}", e)
        }
    }

    companion object {
        /**
         * String-returning entry point for providers that resolve an EarnVids embed themselves
         * instead of going through `loadExtractor`.
         *
         * This replaces the four identical `ExternalEarnVidsExtractor` copies that lived in
         * Animerco, Lodynet, Replaymatch and Shahid4u. Their referer contract is preserved: the
         * caller's [referer] is sent, except on `fdewsdc.sbs`, which needs a hijacked one.
         *
         * This is the only path that honours a caller-supplied referer; the registered `getUrl`
         * path keeps sending the embed/final URL.
         */
        suspend fun extractDirect(pageUrl: String, referer: String): String? {
            return try {
                val activeReferer = if (pageUrl.contains("fdewsdc.sbs", ignoreCase = true)) {
                    "https://shhahid4u.cam"
                } else {
                    // extractDirect only: the caller-supplied referer is the contract here.
                    referer
                }
                val headers = mapOf(
                    "User-Agent" to Fingerprint.current().userAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Connection" to "keep-alive",
                    "Referer" to activeReferer
                )
                val html = app.get(pageUrl, headers = headers).text
                for (strategy in EARNVIDS_STRATEGIES) {
                    val decoded = strategy.decode(html, pageUrl)
                    if (decoded.isNullOrBlank()) continue
                    val normalized = normalizeVideoUrl(decoded.replace("\\/", "/"), pageUrl)
                    if (normalized.startsWith("http")) {
                        Log.d("EarnVidsExtractor", "🥇 extractDirect success via ${strategy::class.java.simpleName}")
                        return normalized
                    }
                }
                Log.w("EarnVidsExtractor", "🛑 extractDirect: all strategies failed for $pageUrl")
                null
            } catch (e: Exception) {
                Log.e("EarnVidsExtractor", "❌ extractDirect threw: ${e.message}")
                null
            }
        }
    }
}
