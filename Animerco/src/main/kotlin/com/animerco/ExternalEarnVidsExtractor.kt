package com.animerco

import android.util.Log
import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.net.URI
import kotlin.text.RegexOption

object ExternalEarnVidsExtractor {

    private const val TAG = "EarnVidsExtractor"

    suspend fun extract(pageUrl: String, mainReferer: String): String? {
        try {

            val headers = mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Connection" to "keep-alive"
            )

            if (pageUrl.contains("fdewsdc.sbs", true)) {
                headers["Referer"] = "https://shhahid4u.cam"

            } else {
                headers["Referer"] = mainReferer
            }

            val response = app.get(pageUrl, headers = headers)
            val html = response.text ?: ""

            try {
                val m3u8Regex = Regex("""https?://[^'"\s>]+?\.m3u8[^'"\s>]*""", RegexOption.IGNORE_CASE)
                val m3u8Match = m3u8Regex.find(html)
                if (m3u8Match != null) {
                    var direct = m3u8Match.value.replace("\\/", "/")
                    if (direct.startsWith("/")) direct = URI(pageUrl).resolve(direct).toString()

                    return direct
                }
            } catch (e: Exception) {

            }

            if (!html.contains("eval(function")) {

                return null
            }

            var working = html
            var unpacked: String? = null
            val maxIterations = 4
            for (i in 1..maxIterations) {
                unpacked = unpackPackerSimple(working, pageUrl)
                if (unpacked.isNullOrBlank()) {

                    break
                }

                if (!unpacked.contains("eval(function")) {
                    working = unpacked
                    break
                } else {

                    working = unpacked
                }
            }

            if (unpacked.isNullOrBlank()) {

                return null
            }

            val cleaned = unpacked.replace("\\/", "/")

            val linksRegex = Regex("""var\s+links\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            val match = linksRegex.find(cleaned)
            if (match == null) {

                val hlsInline = Regex(""""hls4"\s*:\s*"([^"]+)"""").find(cleaned)?.groupValues?.get(1)
                    ?: Regex(""""hls"\s*:\s*"([^"]+)"""").find(cleaned)?.groupValues?.get(1)
                if (!hlsInline.isNullOrBlank()) {
                    var link = hlsInline.replace("\\/", "/")
                    if (link.startsWith("/")) link = URI(pageUrl).resolve(link).toString()

                    return link
                }
                return null
            }

            val jsonRaw = match.groupValues[1].replace("'", "\"")

            val map = mutableMapOf<String, String>()
            try {
                val jo = JSONObject(jsonRaw)
                val keys = jo.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    try {
                        map[k] = jo.getString(k)
                    } catch (_: Exception) {

                    }
                }
            } catch (e: Exception) {

                val pairRegex = Regex(""""([^"]+)"\s*:\s*"([^"]+)"""")
                for (m in pairRegex.findAll(jsonRaw)) {
                    map[m.groupValues[1]] = m.groupValues[2]
                }
            }

            var link = map["hls4"] ?: map["hls"] ?: ""
            if (link.isBlank()) {

                return null
            }
            link = link.replace("\\/", "/")
            if (link.startsWith("/")) {
                link = URI(pageUrl).resolve(link).toString()
            }

            return link
        } catch (e: Exception) {

            return null
        }
    }

    /**
     * يطابق منطق unpack_packer_simple من بايثون.
     * يُعيد payload بعد استبدال الرموز بالرموز الحقيقية وفق symtab.
     * يقوم باستبدال مراجع المتصفح الشائعة لكي لا تحتاج بيئة JS.
     */
    private fun unpackPackerSimple(js: String, pageUrl: String): String? {
        try {
            val regex = Regex(
                """eval\(function\(p,a,c,k,e,d\)\{.*?\}\(\s*['"](.+?)['"]\s*,\s*(\d+)\s*,\s*\d+\s*,\s*['"](.+?)['"]""",
                RegexOption.DOT_MATCHES_ALL
            )
            val match = regex.find(js) ?: return null
            val (payloadRaw, radixStr, sympipe) = match.destructured
            val radix = radixStr.toIntOrNull() ?: 36
            val symtab = sympipe.split("|")

            var payload = payloadRaw
                .replace("location.href", "'$pageUrl'")
                .replace("location", "'$pageUrl'")
                .replace("document.cookie", "''")
                .replace("window.location", "'$pageUrl'")
                .replace("window", "this")

            val tokenRe = Regex("""\b[0-9a-zA-Z]+\b""")

            val replaced = tokenRe.replace(payload) { mo ->
                val tok = mo.value
                try {
                    val idx = tok.toInt(radix)
                    if (idx in 0 until symtab.size) symtab[idx] else tok
                } catch (_: Exception) {
                    tok
                }
            }

            return replaced
        } catch (e: Exception) {

            return null
        }
    }
}
