package com.cloudstream.shared.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import java.net.URI
import kotlin.random.Random

// ──────────────────────────────────────────────────────────────────────────────
// MixDrop (mixdrop.ag, mixdrop.co, mixdrop.ch, mixdrop.to, mixdrop.bz)
// ──────────────────────────────────────────────────────────────────────────────
class MixDropExtractor(
    override val mainUrl: String,
    override val name: String = "MixDrop"
) : ExtractorApi() {
    override val requiresReferer = false
    private val srcRegex = Regex("""wurl.*?=.*?"(.*?)";""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        with(app.get(url.replaceFirst("/f/", "/e/"))) {
            getAndUnpack(this.text).let { unpackedText ->
                srcRegex.find(unpackedText)?.groupValues?.get(1)?.let { link ->
                    callback(
                        newExtractorLink(name, name, httpsify(link)) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// DoodStream (used by ds2play.com, ds2video.com)
// ──────────────────────────────────────────────────────────────────────────────
class Ds2playExtractor(
    override val mainUrl: String,
    override val name: String = "DoodStream"
) : ExtractorApi() {
    override val requiresReferer = false
    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.replace("/d/", "/e/")
        val req = app.get(embedUrl)
        val host = URI(req.url).let { "${it.scheme}://${it.host}" }
        val response0 = req.text
        val md5 = host + (Regex("/pass_md5/[^']*").find(response0)?.value ?: return)
        val token = md5.substringAfterLast("/")
        val hashTable = buildString { repeat(10) { append(alphabet.random()) } }
        val trueUrl = app.get(md5, referer = req.url).text + hashTable + "?token=$token"
        val quality = Regex("\\d{3,4}p")
            .find(response0.substringAfter("<title>").substringBefore("</title>"))
            ?.groupValues?.getOrNull(0)
        callback(
            newExtractorLink(name, name, trueUrl) {
                this.referer = "$mainUrl/"
                this.quality = getQualityFromName(quality)
            }
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Krakenfiles (krakenfiles.com)
// ──────────────────────────────────────────────────────────────────────────────
class KrakenfilesExtractor(
    override val mainUrl: String,
    override val name: String = "Krakenfiles"
) : ExtractorApi() {
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("/(?:view|embed-video)/([\\da-zA-Z]+)").find(url)?.groupValues?.get(1)
            ?: return
        val doc = app.get("$mainUrl/embed-video/$id").document
        val link = doc.selectFirst("source")?.attr("src") ?: return
        callback(newExtractorLink(name, name, httpsify(link)))
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// LuluStream (luluvdo.com, lulustream.com, kinoger.pw)
// ──────────────────────────────────────────────────────────────────────────────
class LuluStreamExtractor(
    override val mainUrl: String,
    override val name: String = "LuluStream"
) : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val filecode = url.substringAfterLast("/")
        val post = app.post("$mainUrl/dl", data = mapOf(
            "op" to "embed",
            "file_code" to filecode,
            "auto" to "1",
            "referer" to (referer ?: "")
        )).document
        post.selectFirst("script:containsData(vplayer)")?.data()
            ?.let { script ->
                Regex("file:\"(.*)\"").find(script)?.groupValues?.get(1)?.let { link ->
                    callback(
                        newExtractorLink(name, name, link) {
                            this.referer = mainUrl
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// VidHidePro (filelions.to, filelions.live, filelions.online, vidhidepro.com)
// ──────────────────────────────────────────────────────────────────────────────
class FilelionsExtractor(
    override val mainUrl: String,
    override val name: String = "VidHidePro"
) : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
        )

        val embedUrl = when {
            url.contains("/d/") -> url.replace("/d/", "/v/")
            url.contains("/download/") -> url.replace("/download/", "/v/")
            url.contains("/file/") -> url.replace("/file/", "/v/")
            else -> url.replace("/f/", "/v/")
        }

        val response = app.get(embedUrl, referer = referer)
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            var result = getAndUnpack(response.text)
            if (result.contains("var links")) {
                result = result.substringAfter("var links")
            }
            result
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        } ?: return

        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { m3u8Match ->
            generateM3u8(
                name,
                fixUrl(m3u8Match.groupValues[1]),
                referer = "$mainUrl/",
                headers = headers
            ).forEach(callback)
        }
    }
}
