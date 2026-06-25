package com.cloudstream.shared.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class LuluvidExtractor : ExtractorApi() {
    override val name = "Luluvid"
    override val mainUrl = "https://luluvid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "[$name]"
        val actualReferer = referer ?: mainUrl
        Log.d(tag, "Fetching $url")

        val html = app.get(url, referer = actualReferer).document.outerHtml()

        val evalContent = html.substringAfter("eval(function(p,a,c,k,e,d)")
            .substringBefore("</script>")
        val args = evalContent.substringAfter("}(")

        val sep = Regex(""",(\d+),(\d+),'""").find(args) ?: run {
            Log.w(tag, "Could not find A,C separator in eval")
            return
        }

        val radix = sep.groupValues[1].toIntOrNull() ?: run {
            Log.w(tag, "Invalid radix: ${sep.groupValues[1]}")
            return
        }
        val words = args.substringAfter(sep.value).substringBefore("'.split('|')").split("|")

        val pStr = args.substring(1, sep.range.first)

        var decoded = pStr
        for ((index, word) in words.withIndex()) {
            if (word.isNotEmpty()) {
                decoded = decoded.replace(Regex("\\b${Regex.escape(index.toString(radix))}\\b"), word)
            }
        }

        val videoUrl = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""").find(decoded)?.groupValues?.get(1)
            ?: Regex("""file:\s*["']([^"']+)["']""").find(decoded)?.groupValues?.get(1)

        if (videoUrl != null) {
            val cleanUrl = videoUrl.replace("\\/", "/")
            Log.d(tag, "Found M3U8: ${cleanUrl.take(80)}")
            for (link in M3u8Helper.generateM3u8(
                source = name,
                streamUrl = cleanUrl,
                referer = actualReferer,
                name = name
            )) {
                Log.d(tag, "Emitting variant: ${link.url}")
                callback(link)
            }
        } else {
            Log.w(tag, "No video URL found. Decoded preview: ${decoded.take(500)}")
        }
    }
}
