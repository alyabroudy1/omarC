package com.cimanow

import android.content.Context
import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.cloudstream.shared.provider.BaseProvider
import com.cloudstream.shared.parsing.NewBaseParser
import com.cloudstream.shared.webview.NavigationStep
import com.cloudstream.shared.webview.Mode
import com.cloudstream.shared.session.SessionProvider
import com.cloudstream.shared.webview.CapturedEmbedRequest
import com.cloudstream.shared.webview.CapturedVideoRequest
import com.cloudstream.shared.webview.VideoUrlClassifier
import com.cloudstream.shared.webview.InterceptChallenge
import com.cloudstream.shared.extractors.CimaNowTVEmbed
import com.cloudstream.shared.extractors.VKVideoEmbed
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

import java.io.ByteArrayOutputStream
import java.util.regex.Pattern


class CimaNowProvider : BaseProvider() {
    lateinit var context: Context

    override var name = "سيماناو"
    override val providerName get() = "Cimanow"
    override val baseDomain get() = "cimanow.cc"
    override val githubConfigUrl get() = ""

    override fun getParser(): NewBaseParser {
        return CimaNowParser()
    }

    override var lang = "ar"

    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val TAG = "CimaNowDebug"

    /**
     * Per-server ceiling for core.php + extractor. Servers resolve in parallel but the player only
     * starts once ALL of them settle, so this is the worst case a user waits for playback. Keep it
     * well under the 120s WebView-sniff budget some extractors use internally.
     */
    private val SERVER_RESOLVE_TIMEOUT_MS = 20_000L

    /**
     * How long the remaining servers may keep running after the first playable link arrives.
     *
     * Long enough for a server that is nearly done to still contribute an alternative source, short
     * enough that the user is not left waiting on one that never will.
     */
    private val STRAGGLER_GRACE_MS = 2_500L

    /**
     * Play by surfing the watch page in a real fullscreen WebView instead of decrypting it.
     *
     * Flip to false to fall back to the sandbox-decryption path (resolveViaWebViewSandbox), which is
     * kept intact for exactly that reason — see the arms-race history in
     * `cimanow_decryption_handover.md`.
     *
     * **Flipping it is not a free rollback, and this is the part that is easy to miss.** The surf path
     * runs *no* JavaScript in the page: as of 2026-08-03 not even a diagnostic read (handover rule 28).
     * The sandbox path is the opposite — it injects an in-page reader script and exfiltrates the
     * decrypted DOM through a side channel, which is the very thing rules 6, 7, 9 and 10 were written
     * about. It also runs against the real cimanow.cc in the same session and from the same IP as
     * everything else, so being flagged there is not contained to that one request.
     *
     * So: it is a fallback for when the surf stops working *structurally*, not a knob to try when a
     * single title misbehaves. If you flip it, expect the decoy, and read §0.1 first.
     * [resolveViaWebViewSandbox] says the same thing at the point of no return.
     */
    private val USE_FULLSCREEN_SURF = true

    /**
     * Write every unparseable embed page to disk.
     *
     * Off by default. The dump exists to answer one open question — why [resolveEmbedFromHtml] has
     * never resolved a single embed in a real run (handover §7) — and answering it needs one debugging
     * session, not a file written on every playback forever. Left on, it costs the user disk I/O per
     * embed per play, on the failure path of a fast path that always fails, while the registered
     * extractors do the actual work moments later.
     *
     * Turn it on when you sit down to fix that path; turn it off again after.
     */
    private val DUMP_UNPARSEABLE_EMBED_HTML = false

    /** The freex blog-post page: countdown host, and the Referer the watch page's gate demands. */
    private val TIMER_PAGE_URL = "https://rm.freex2line.online/2020/02/blog-post.html/"

    /** Referer for the timer page itself, mirroring the real redirect chain. */
    private val REDIRECTING_PAGE_URL = "https://rm.freex2line.online/redirectingfree/"

    /**
     * How long the surf window stays open. Generous on purpose: the user is picking a server and
     * pressing play by hand, and an ad interstitial or a mis-click has to be recoverable.
     */
    private val SURF_TIMEOUT_MS = 300_000L

    /**
     * How long the timer page stays up waiting for the user to press the watch button.
     *
     * The countdown itself is ~10 s, and the button only appears after it. Then a person has to notice
     * it and aim at it on a page covered in ads. Two minutes is not generous, it is realistic — and the
     * window closes the moment they either succeed or dismiss it, so a long ceiling costs nothing when
     * things go well.
     */
    private val WATCH_BUTTON_TIMEOUT_MS = 120_000L

    /**
     * The one page that counts as arriving: the watch page **with its token**.
     *
     * From the 2026-07-02 HAR, the request that returns the real 335 KB player document:
     * ```
     * GET https://cimanow.cc/<slug>/watching/?token=03720e1fb6a3de…  (64 hex)
     * Referer: https://rm.freex2line.online/2020/02/blog-post.html/
     * ```
     *
     * **`https://cimanow.cc/pig/watching/` is a placeholder**, not a destination — it is what the watch
     * button's `href` holds until the countdown's `get-link.php` call replaces it with the URL above.
     * Follow it and cimanow answers with a 3.7 KB `<title>Redirect</title>` stub that meta-refreshes to
     * an ad network. Every white screen in this flow has been that stub.
     *
     * Which is why the query string is part of the pattern and not a separate check. A pattern of
     * `/(watch|watching)/` matches the placeholder, and on 2026-08-03 that single fact broke the surf
     * twice over: the destination lock engaged on the placeholder, so the session was pinned to a page
     * that was never the page — and worse, `shouldOverrideUrlLoading` treats a *same-site* navigation
     * away from a locked destination as terminal, so the user's **second, correct** tap would have been
     * recorded as "the site sent us away" and killed the session outright.
     *
     * `cimanow\.[a-z]+` rather than a literal domain: the provider's domain rotates.
     */
    private val PLAYER_PAGE_PATTERN = Regex("""cimanow\.[a-z]+/.+/watching/\?.+""")

    /**
     * Surf with a TV user agent so the watch page's own `isTv()` check disables its ad gate.
     *
     * **Off, and it should stay off unless the whole session can move to a TV identity together.** The
     * gate bypass is real (see §14), but the UA is not a free variable:
     *  - `cf_clearance` is bound to the UA that solved the challenge. Solve as a phone over HTTP, then
     *    present a TV UA in the WebView, and the clearance is void the moment Cloudflare appears.
     *  - Our own interceptor derives `sec-ch-ua` from the Chrome build while `sec-ch-ua-mobile` stays
     *    `?1`, so a TV UA ships with mobile client hints — a mismatch a CF fingerprint check reads
     *    directly.
     *  - The HTTP half of the chain (`navigateToTimerPageViaHttp`) keeps the session UA regardless, so
     *    the two halves would disagree about what device this is.
     * The flow also worked end to end with the phone UA hours earlier, so the regression is elsewhere.
     */
    private val SURF_AS_TV_UA = false

    /**
     * Rewrites a UA so the page's `isTv()` regex matches, changing as little else as possible.
     *
     * Keeps the real Chrome build and everything the anti-bot's other checks look at; only the device
     * descriptor changes, since the regex only needs one of its tokens (`android tv` here).
     */
    private fun asTvUserAgent(ua: String): String {
        if (Regex("(?i)smart-?tv|hbbtv|netcast|webos|tizen|viera|aquos|android tv|apple tv|roku|fire tv")
                .containsMatchIn(ua)
        ) return ua
        // "Linux; Android 16; SM-S918B Build/…" → "Linux; Android 16; Android TV"
        val replaced = ua.replace(Regex("""\(Linux; Android ([\d.]+)[^)]*\)"""), "(Linux; Android $1; Android TV)")
        return if (replaced != ua) replaced else "$ua Android TV"
    }

    /**
     * Per-embed ceiling for extractor resolution.
     *
     * This phase runs with the surf window already closed, so every second is a spinner on a blank
     * screen. `VKVideoEmbed` alone can spend 45 s in a headless sniffer when the embed page carries no
     * player params, which is exactly what a dead embed does (2026-07-30: 49 s, then failure). 12 s is
     * comfortably above the healthy case measured at 0.7 s.
     */
    private val EMBED_RESOLVE_TIMEOUT_MS = 12_000L

    /**
     * How many embeds to resolve, newest first.
     *
     * A per-embed timeout is not a bound on the phase: 307 embeds at up to 12 s each is what left a
     * black screen up for 30 s+ (2026-07-30). One embed per server the user actually tried is the real
     * rate, and the newest is the one they are waiting on.
     */
    private val MAX_EMBEDS_TO_RESOLVE = 6

    /** Ceiling for the whole embed-resolution phase, which runs with the surf window already closed. */
    private val EMBED_PHASE_BUDGET_MS = 25_000L

    override val mainPage = mainPageOf(
        mainUrl + "/الاحدث/" to "الاحدث",
        mainUrl + "/category/افلام-اجنبية/page/" to "افلام اجنبية",
        mainUrl + "/category/مسلسلات-اجنبية/page/" to "مسلسلات اجنبية",
        mainUrl + "/category/افلام-نتفليكس/page/" to "افلام نتفليكس",
        mainUrl + "/category/مسلسلات-نتفليكس/page/" to "مسلسلات نتفليكس",
        mainUrl + "/category/افلام-مارفل/page/" to "افلام مارفل",
        mainUrl + "/category/مسلسلات-عربية/page/" to "مسلسلات عربية",
        mainUrl + "/category/افلام-عربية/page/" to "افلام عربية",
        mainUrl + "/category/مسلسلات-عربية/page/" to "مسلسلات عربية",
        mainUrl + "/category/افلام-هندية/page/" to "أفلام هندية",
        mainUrl + "/category/افلام-تركية/page/" to "أفلام تركية",
        mainUrl + "/category/مسلسلات-تركية/page/" to "مسلسلات تركية"
    )

    private fun getIntFromText(text: String): Int? {
        return Regex("\\d+").find(text)?.value?.toIntOrNull()
    }

    // ==================== decodeHtml ====================

    private fun decodeAndWriteFast(chunk: StringBuilder, key: Long, out: ByteArrayOutputStream): Int {
        val r = chunk.length % 4
        if (r > 0) {
            when (r) {
                2 -> chunk.append("==")
                3 -> chunk.append("=")
            }
        }
        return try {
            val bytes = Base64.decode(chunk.toString(), Base64.DEFAULT)
            var num = 0L
            for (b in bytes) {
                val bInt = b.toInt()
                if (bInt in 48..57) {
                    num = num * 10 + (bInt - 48)
                }
            }
            if (num > 0) {
                out.write((num - key).toInt())
                1
            } else 0
        } catch (_: Exception) {
            0
        }
    }

    private fun decodeHtml(doc: Document): Document {
        try {
            val rawHtml = doc.outerHtml()

            val keyMatcher = Pattern.compile("var\\s+_r\\s*=\\s*(\\d+(?:\\s*\\+\\s*\\d+)*)\\s*;").matcher(rawHtml)
            if (!keyMatcher.find()) return doc
            val dynamicKey = keyMatcher.group(1).split("+").sumOf { it.trim().toLong() }

            val dataMatcher = Pattern.compile("['\"]([A-Za-z0-9+/=~]{20,})['\"]").matcher(rawHtml)
            val extractedData = StringBuilder(100000)
            while (dataMatcher.find()) {
                val chunk = dataMatcher.group(1)
                if (chunk.indexOf('~') >= 0) extractedData.append(chunk)
            }
            if (extractedData.isEmpty()) return doc

            val out = ByteArrayOutputStream(extractedData.length / 4)
            val chunk = StringBuilder(64)
            val len = extractedData.length

            for (i in 0 until len) {
                val c = extractedData[i]
                when {
                    c == '~' -> {
                        if (chunk.isNotEmpty()) {
                            decodeAndWriteFast(chunk, dynamicKey, out)
                            chunk.setLength(0)
                        }
                    }
                    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '=' -> {
                        chunk.append(c)
                    }
                }
            }
            if (chunk.isNotEmpty()) {
                decodeAndWriteFast(chunk, dynamicKey, out)
            }

            val decoded = out.toString("UTF-8")
            if (decoded.isBlank()) return doc
            return Jsoup.parse(decoded)
        } catch (e: Exception) {
            Log.e(TAG, "decodeHtml error: ${e.message}")
        }
        return doc
    }

    // ==================== search ====================

    override suspend fun searchNormal(query: String): List<SearchResponse> {
        if (query.trim().equals("test", ignoreCase = true)) {
            return listOf(
                newMovieSearchResponse("Test WebView Fallback", "https://cimanow.cc/test-webview-fallback/", TvType.Movie) {
                    this.posterUrl = "https://cimanow.cc/wp-content/themes/Cima%20Now%20New/Assets/imgs/logo.svg"
                }
            )
        }
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = httpService.getDocument("$mainUrl/?s=$encoded", rewriteDomain = true) ?: return emptyList()
        val items = getParser().parseSearch(doc)
        return items.map { item ->
            newMovieSearchResponse(item.title, item.url, if (item.isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = item.posterUrl
                this.posterHeaders = httpService.getImageHeaders()
            }
        }
    }

    override suspend fun searchLazy(query: String): List<SearchResponse> {
        return searchNormal(query)
    }

    // ==================== getMainPage ====================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = request.data + page
        val doc = httpService.getDocument(url, rewriteDomain = true) ?: return null
        val items = getParser().parseMainPage(doc)
        return newHomePageResponse(request.name, items.map { item ->
            val type = if (item.isMovie) TvType.Movie else TvType.TvSeries
            newMovieSearchResponse(item.title, item.url, type) {
                this.posterUrl = item.posterUrl
                this.posterHeaders = httpService.getImageHeaders()
            }
        })
    }

    // ==================== toSearchResponse ====================

    private fun selectPosterImg(element: Element): Element? {
        val img = element.selectFirst("img[data-src]")
            ?: element.selectFirst("img.lazy")
            ?: element.selectFirst("img[alt!=logo]")
            ?: element.selectFirst("img")
        return img
    }

    private fun getPosterUrl(img: Element): String {
        return img.attr("data-src").ifBlank { img.attr("src") }
    }

    private fun getTitle(element: Element, img: Element?): String? {
        val fromLi = element.selectFirst("li[aria-label='title']")?.text()
        if (!fromLi.isNullOrBlank()) return fromLi
        val fromH3 = element.selectFirst("h3 a")?.text()
        if (!fromH3.isNullOrBlank()) return fromH3
        val fromATitle = element.selectFirst("a[title]")?.attr("title")
        if (!fromATitle.isNullOrBlank()) return fromATitle
        val fromImgAlt = img?.attr("alt")
        if (!fromImgAlt.isNullOrBlank() && fromImgAlt != "logo") return fromImgAlt
        return null
    }

    private fun getHref(element: Element): String? {
        val link = element.selectFirst("a[href]")
            ?: element.parent()?.selectFirst("a[href]") ?: return null
        return link.attr("href").takeUnless { it.isBlank() || it.startsWith("#") || it.startsWith("javascript:") }
    }

    private fun toSearchResponse(element: Element): SearchResponse? {
        val img = selectPosterImg(element) ?: return null
        val href = getHref(element) ?: return null
        val title = getTitle(element, img) ?: return null

        val posterUrl = getPosterUrl(img)

        val category = element.select("a[href*='/category/']").text()
        val year = element.select("a[href*='/release-year/']").text().toIntOrNull()

        val quality = null

        val type = if (category.contains("مسلسلات", true) || category.contains("موسم", true)) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = quality
        }
    }

    // ==================== load ====================

    override suspend fun load(url: String): LoadResponse? {
        if (url == "https://cimanow.cc/test-webview-fallback/") {
            return newMovieLoadResponse("Test WebView Fallback", url, TvType.Movie, "https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-agent-kim-reactivated-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-1-%d8%a7%d9%84%d8%a7%d9%88%d9%84%d9%8a-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9/") {
                this.posterUrl = "https://cimanow.cc/wp-content/themes/Cima%20Now%20New/Assets/imgs/logo.svg"
            }
        }
        val doc = httpService.getDocument(url, rewriteDomain = true) ?: return null
        val decodedDoc = decodeHtml(doc)

        val isMovie = decodedDoc.title().contains("فيلم")

        val posterUrl = decodedDoc.select("figure img").attr("src")
        val year = decodedDoc.select("ul li a[href^='https://cimanow.cc/release-year/']").text().toIntOrNull()

        val titleRegex = Regex("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|\\|${year}|Cima Now|-|سيما ناو|ج[0-9]|\\|")
        val title = titleRegex.replace(decodedDoc.title(), "")

        val tags = decodedDoc.select("article ul li")
            .filter { it.attr("aria-label") != "story" }
            .flatMap { it.text().split("،") }
            .map { it.trim() }

        val recommendations = decodedDoc.select("ul.related li").mapNotNull { toSearchResponse(it) }

        val synopsis = decodedDoc.select("li[aria-label=story] p").text()

        val actors = decodedDoc.select("ul li a[href^='https://cimanow.cc/actor/']")
            .map { it.text() }
            .filter { !it.isNullOrBlank() }
            .map { ActorData(Actor(it)) }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.recommendations = recommendations
                this.actors = actors
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasonElements = decodedDoc.select("section[aria-label=seasons] ul li a")

        if (seasonElements.isNotEmpty()) {
            coroutineScope {
                val deferredEpisodes = seasonElements.map { seasonElement ->
                    async {
                        val seasonDoc = try {
                            httpService.getDocument(seasonElement.attr("href"), rewriteDomain = true)
                        } catch (_: Exception) { null }
                        if (seasonDoc != null) {
                            val decodedSeason = decodeHtml(seasonDoc)
                            val seasonTitle = decodedSeason.selectFirst("span[aria-label=season-title]")
                            val seasonNum = getIntFromText(seasonTitle?.text() ?: "") ?: 1
                            decodedSeason.select("ul#eps li a").mapNotNull { epElement ->
                                newEpisode(epElement.attr("href")) {
                                    this.name = epElement.selectFirst("img")?.attr("alt")
                                    this.season = seasonNum
                                    this.episode = epElement.selectFirst("em")?.text()?.toIntOrNull()
                                    this.posterUrl = posterUrl
                                }
                            }
                        } else emptyList()
                    }
                }
                episodes.addAll(deferredEpisodes.awaitAll().flatten())
            }
        } else {
            val seasonTitle = decodedDoc.selectFirst("span[aria-label=season-title]")
            val seasonNum = getIntFromText(seasonTitle?.text() ?: "") ?: 1

            decodedDoc.select("ul#eps li a").mapNotNull { epElement ->
                newEpisode(epElement.attr("href")) {
                    this.name = epElement.selectFirst("img")?.attr("alt")
                    this.season = seasonNum
                    this.episode = epElement.selectFirst("em")?.text()?.toIntOrNull()
                    this.posterUrl = posterUrl
                }
            }.let { episodes.addAll(it) }
        }

        episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.year = year
            this.plot = synopsis
            this.tags = tags
            this.recommendations = recommendations
            this.actors = actors
        }
    }

    // ==================== loadLinks ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i("CimaNowLoadLinks", "================ [START LOADLINKS] ================")
        Log.d("CimaNowLoadLinks", "-> Data URL: $data")
        val found = if (USE_FULLSCREEN_SURF) {
            resolveViaFullscreenSurfWithRetry(data, callback)
        } else {
            // Legacy path: fight the watch page's anti-bot for the decrypted server list.
            resolveViaWebViewSandbox(data, subtitleCallback, callback)
        }
        Log.i("CimaNowLoadLinks", "================ [END LOADLINKS found=$found] ================")
        return found
    }

    // ==================== Fullscreen surf ====================

    /**
     * The surf, plus one retry when Cloudflare — not the site's content — is what stopped it.
     *
     * The engine cannot solve a challenge in the middle of a session: it would mean a second WebView
     * on top of the one the user is looking at. So it records what happened
     * ([NavigationResult.interceptChallenges]) and falls through to Chromium, which reissues the
     * request with the `sec-ch-ua: "Android WebView"` fingerprint the interception existed to avoid —
     * a request cimanow bounces. That is why a challenge on the watch page shows up as a blank
     * fullscreen window rather than an error.
     *
     * Handled here instead, where the window is already closed and WebView lifecycles no longer
     * overlap: solve through the ordinary session path, then surf once more.
     *
     * Deliberately narrow. The retry costs the user a second pass at picking a server, so it fires
     * only when the first attempt produced **nothing usable at all** *and* a cimanow-domain challenge
     * was recorded. A surf that captured even one embed is not retried — the user got a choice, and
     * some other thing failed.
     */
    private suspend fun resolveViaFullscreenSurfWithRetry(
        movieUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val TAG_RETRY = "CimaNowSurf"
        val first = resolveViaFullscreenSurf(movieUrl, callback)
        if (first.found) return true

        val block = first.challenges.cimaCloudflareBlock()
        if (block == null) {
            Log.i(TAG_RETRY, "Surf found nothing and no Cloudflare block was recorded — " +
                "not retrying (nothing suggests a second attempt would differ)")
            return false
        }
        if (first.producedCandidates) {
            Log.i(TAG_RETRY, "Cloudflare refused ${block.url.take(80)} but the surf still produced " +
                "candidates — not retrying, the failure is downstream of the block")
            return false
        }

        Log.w(TAG_RETRY, "🔒 Cloudflare blocked the surf at its source | code=${block.statusCode} " +
            "mainFrame=${block.isMainFrame} url=${block.url.take(100)}")
        Log.w(TAG_RETRY, "Body preview: ${block.bodyPreview.take(200).replace("\n", " ")}")

        // Solve through the ordinary session path: getDocument sees the 403 + CF markers and runs the
        // WebView solve itself, then updateCookies syncs the clearance into the system CookieManager —
        // which is exactly where the surf's interceptor reads its cookies from. The home page is the
        // cheapest thing to ask for; what matters is the session it leaves behind, not the bytes.
        if (!reestablishSession(httpService, mainUrl, TAG_RETRY)) {
            Log.e(TAG_RETRY, "❌ Could not re-establish a session — the CF solve failed or the user " +
                "cancelled it. Not surfing again.")
            return false
        }
        Log.i(TAG_RETRY, "✅ Session re-established — surfing once more")
        return resolveViaFullscreenSurf(movieUrl, callback).found
    }

    /**
     * What one surf attempt came to.
     *
     * A return value rather than fields on the provider. The retry needs three facts — did we get
     * links, was Cloudflare in the way, did the user get a real choice — and holding them on the
     * instance made the decision non-reentrant: two concurrent `loadLinks` calls would each read
     * whatever the other wrote last, and every early return had to remember to reset them.
     */
    private data class SurfOutcome(
        /** Links were delivered to the callback. Nothing else matters when this is true. */
        val found: Boolean,
        /** Requests the engine re-issued and was refused on — see [InterceptChallenge]. */
        val challenges: List<InterceptChallenge> = emptyList(),
        /** An embed or stream was captured, i.e. the user reached a working server list. */
        val producedCandidates: Boolean = false
    )

    /**
     * Play by showing the user the watch page and taking the stream off the network.
     *
     * We stop trying to read the decrypted server list. Every approach in
     * `cimanow_decryption_handover.md` §0.1 fails for the same structural reason: the gate inspects
     * *its own environment*, not its input, so a hook, bridge, marker or `evaluateJavascript` read is
     * on the page's side of the boundary and therefore visible to it. `shouldInterceptRequest` is on
     * ours. So the page is put on screen, the user clicks a server and presses play, and we read the
     * stream URL out of the request.
     *
     * **This is the sandbox flow's navigation, unchanged, with `Mode.FULLSCREEN` instead of
     * `HEADLESS`** — and that is deliberate. A freshly built WebView pointed at the watch link does
     * not work (tried 2026-07-29, see §0.1 rule 16): it loads in ~380 ms and yields nothing, because
     * the request goes out with `sec-ch-ua: "Android WebView"` and cimanow bounces it. What makes the
     * page render is [NavigationEngine]'s interceptor, which re-issues the main frame through
     * HttpURLConnection with real-Chrome `sec-ch-ua`, an emptied `X-Requested-With`, the CookieManager
     * cookies and the original Referer. Reaching the page and watching the page are the same WebView
     * for exactly that reason.
     *
     * Steps 0–1 are byte-for-byte the sandbox flow: render the timer HTML from our own bytes (so
     * Cloudflare never sees a WebView fetch the page), then let the countdown mint the token and
     * navigate to `/watching/` with the blog-post page as Referer. Step 2 is the only addition — it
     * holds the visible session open while the user picks a server, and ends a beat after the first
     * stream appears.
     */
    private suspend fun resolveViaFullscreenSurf(
        movieUrl: String,
        callback: (ExtractorLink) -> Unit
    ): SurfOutcome {
        val TAG_SURF = "CimaNowSurf"
        Log.i(TAG_SURF, "========== [START] fullscreen surf resolve ==========")
        Log.i(TAG_SURF, "Movie URL: $movieUrl")

        try {
            // The page turns its entire ad gate off for TV user agents — its own code, first line of
            // the handler (decrypted page, 2026-07-30):
            //
            //     if (isTv()) return;                      // before any button is touched
            //     isTv = () => /smart-tv|smarttv|hbbtv|netcast|webos|tizen|viera|aquos|
            //                   android tv|apple tv|roku|fire tv/.test(navigator.userAgent)
            //
            // Everything we have been fighting sits *after* that return: the popunder `window.open`,
            // the 800 ms dwell check, the "allow the ads" modal, and `$("main article ul.btns li")
            // .remove()` — which is the site emptying its own button list when a button has no href,
            // and the likeliest source of the "white empty page" after a second server tap.
            //
            // So claim to be an Android TV and the site skips all of it. This is a path the page
            // provides, not a hook or an injection — presumably because a TV cannot show a popunder.
            val userAgent = if (SURF_AS_TV_UA) {
                asTvUserAgent(httpService.userAgent).also {
                    Log.i(TAG_SURF, "Surfing with a TV UA so the site's own isTv() bypass applies: $it")
                }
            } else {
                httpService.userAgent
            }

            // ---------- PHASE 1: token chain over HTTP, up to the timer page ----------
            val timerHtml = navigateToTimerPageViaHttp(movieUrl)
            if (timerHtml == null) {
                Log.e(TAG_SURF, "❌ Could not reach the timer page — no token chain, nothing to surf")
                // No navigation happened, so there is nothing to report and nothing to retry: a
                // failure here was already diagnosed by navigateToTimerPageViaHttp.
                return SurfOutcome(found = false)
            }
            Log.i(TAG_SURF, "✅ Timer page HTML: ${timerHtml.length} chars")

            // ---------- PHASE 2: navigate to the watch page, VISIBLY, and wait for a stream ----------
            val steps = listOf(
                // Rendered from our own bytes rather than fetched by the WebView, which keeps
                // Cloudflare from seeing sec-ch-ua: "Android WebView" on the page request.
                NavigationStep.LoadHtml(
                    html = timerHtml,
                    baseUrl = TIMER_PAGE_URL,
                    referer = REDIRECTING_PAGE_URL
                ),
                // The countdown finishes, the page fills in the watch button's href — and stops. It
                // does not navigate on its own, so the user presses the button, exactly as they would
                // in a browser.
                //
                // This replaces `NavigateToWatchingUrl`, which read the tokenised URL out of the
                // `get-link.php` response and navigated there itself. That worked only while
                // `get-link.php` was a GET carrying its parameters in the query string. The site moved
                // it to a `multipart/form-data` POST, and a POST cannot survive
                // `shouldInterceptRequest`: `WebResourceRequest` exposes no body, so re-issuing it
                // dropped `request_id`/`hmac_token` and got back the tokenless
                // `https://cimanow.cc/pig/watching/` — which cimanow answers with an ad interstitial,
                // i.e. the white page. Declining to intercept it fixed the corruption and removed the
                // only place the URL was ever captured.
                //
                // Waiting for the tap needs neither the response body nor the token format. The
                // navigation the button fires carries the timer page as `Referer` (handover rule 5)
                // and goes to whatever URL the site currently mints, and nothing is read out of the
                // page to get it (rule 28).
                NavigationStep.AwaitMainFrameUrl(
                    // Requires the token — see PLAYER_PAGE_PATTERN. A tap on the unfilled placeholder
                    // does not match, so the step keeps waiting instead of declaring arrival at the
                    // interstitial (which is the white page).
                    urlPattern = PLAYER_PAGE_PATTERN,
                    timeoutMs = WATCH_BUTTON_TIMEOUT_MS,
                    abortOnFailure = true
                ),
                // The user is already on the page: pick a server, press play. Ends a beat after the
                // first stream request so the HLS variants land too.
                NavigationStep.WaitForCapturedVideo(
                    timeoutMs = SURF_TIMEOUT_MS,
                    graceMs = STRAGGLER_GRACE_MS,
                    abortOnFailure = false
                )
            )

            val movieHost = try { java.net.URI(movieUrl).host } catch (_: Exception) { null }
            val allowedDomains = mutableSetOf(
                "cimanow.cc", "freex2line.online", "rm.freex2line.online",
                "href.li", "www.freex2line.online"
            )
            if (movieHost != null) allowedDomains.add(movieHost)

            // FULLSCREEN: the same engine, the same interceptor, the same headers — on screen.
            val navResult = httpService.navigationEngine.execute(
                steps = steps,
                userAgent = userAgent,
                mode = Mode.FULLSCREEN,
                // Must outlast **every** hand-driven phase, not just the surf: the user first waits
                // out the countdown and presses the watch button (up to WATCH_BUTTON_TIMEOUT_MS), and
                // only then starts picking servers (SURF_TIMEOUT_MS). Budget for one and the overall
                // timeout tears the WebView down mid-surf — with the step timeouts each looking
                // perfectly reasonable in isolation.
                overallTimeoutMs = WATCH_BUTTON_TIMEOUT_MS + SURF_TIMEOUT_MS + 60_000L,
                allowedDomains = allowedDomains,
                // Same lock as the sandbox flow, and it earns its keep here: once the WebView is on
                // /watching/ every main-frame navigation is refused silently, so the ad redirects this
                // page fires on a stray tap cannot steal the screen from under the user. Servers are
                // switched by AJAX into an iframe, so nothing legitimate needs a main-frame nav.
                // The tokenised page only. `/(watch|watching)/` also matched the `pig/watching/`
                // placeholder, which pinned the lock to the interstitial and turned the user's next
                // (correct) tap into a same-site "site sent us away" — terminal. See
                // PLAYER_PAGE_PATTERN.
                destinationLockPatterns = listOf(PLAYER_PAGE_PATTERN),
                // The one thing that is NOT the same as before, because the same-as-before attempt
                // produced the decoy: 2026-07-30 the watch page loaded fully (4,249,217-byte body)
                // and rendered blank white, and bodyLen-htmlLen was exactly 47 — the decryptor's
                // "I am being automated" decoy. The only thing left in the page context was our own
                // SPOOFING_JS, which defines window.DisableDevtool and claims
                // navigator.plugins == [1,2,3,4,5] (Android Chrome reports an empty PluginArray).
                // Handover §0.1 rules 6 and 7 say exactly this: do not set anything on window.
                injectSpoofingJs = false,
                // Back to false, on evidence. It was turned on because `luugy.com/ct?rb=…` kept
                // re-firing against a swallowed popunder, which looked like the ad network waiting for
                // the popup to load. The next log showed `/ct` fires at the same cadence with the ad
                // fully loaded — it is a heartbeat, not a retry — and the decrypted page then showed
                // what the gate actually checks: that the popup **stays open** past 800 ms
                // (`dipPageVisibility`), which a blank sink already satisfies. So loading the ad for
                // real bought nothing and cost real impressions on the user's connection.
                loadPopupsInSink = false,
                // Also CimaNow-only: capturing embeds means the engine answers the iframe request
                // itself to keep a copy of the HTML, and no other provider should inherit that.
                captureEmbeds = true,
                // 2026-08-01: rewrite OFF. The page structure changed since July 30 — the 2
                // matched document.write calls are now inside inline <script> blocks. The
                // rewrite's </script> in the replacement prematurely closes the enclosing block,
                // producing a syntax error that kills ALL subsequent scripts: zero subresources
                // load, the decryptor never runs (delta=-57), the page sits blank for 2 minutes.
                //
                // Without the rewrite, document.write during initial parse is safe (it appends
                // to the parser, doesn't wipe). The "second server click wipes the doc" issue
                // from §13 may or may not recur with the current page structure — test and
                // re-enable if needed (handover §15, rule 28).
                //
                // The hook stays OFF — §0.1 rule 3 still applies.
                rewriteDocumentWrite = false,
                injectDocumentWriteHook = false,
                // Keeps the WebView jar and the HTTP session in step for responses the engine answers
                // itself — see CimaNowNavigationPolicy. Default policy is a no-op, so this is the only
                // provider that pays for it.
                sessionPolicy = CimaNowNavigationPolicy(httpService)
            )
            val challenges = navResult.interceptChallenges
            val producedCandidates = navResult.capturedEmbedRequests.isNotEmpty() ||
                navResult.capturedVideoRequests.isNotEmpty()
            if (navResult.interceptChallenges.isNotEmpty()) {
                Log.w(TAG_SURF, "⚠️ ${navResult.interceptChallenges.size} intercepted request(s) " +
                    "were refused: " + navResult.interceptChallenges.take(5).joinToString("; ") {
                        "${it.statusCode}${if (it.isCloudflare) "/CF" else ""} ${it.url.take(60)}"
                    })
            }
            Log.i(TAG_SURF, "Nav result: success=${navResult.success} error=${navResult.error}")
            Log.i(TAG_SURF, "Final URL: ${navResult.finalUrl}")
            Log.i(TAG_SURF, "Captured: ${navResult.capturedEmbedRequests.size} embed(s), " +
                "${navResult.capturedVideoRequests.size} video request(s)")

            // ---------- PHASE 4a: embeds → extractors (the good path) ----------
            // The iframe URL the page loaded when the user picked a server is the same embed URL the
            // old sandbox flow used to dig out of the decrypted <li data-index> list via core.php. Its
            // extractor asks the player for the whole quality ladder, which is the only way to get
            // above what ABR happened to fetch: VK signs each rendition separately (`sig` differs per
            // `type=`), so a sniffed 480p URL cannot be promoted to 1080p.
            var found = false
            val embedCallback: (ExtractorLink) -> Unit = { link ->
                found = true
                Log.i(TAG_SURF, ">>> EMBED LINK q=${link.quality} ${link.name} ${link.url.take(140)}")
                callback(link)
            }

            // Most recent first: the last iframe the page inserted is the server the user just picked,
            // and the earlier ones may be from a server they had already given up on. Capped and
            // time-boxed because this phase runs with the surf window already closed — a 307-embed loop
            // at up to 12 s each is what put a black screen in front of the user for 30 s+ (2026-07-30).
            val embeds = navResult.capturedEmbedRequests
                .distinctBy { it.url }
                .reversed()
                .take(MAX_EMBEDS_TO_RESOLVE)
            val phaseDeadline = System.currentTimeMillis() + EMBED_PHASE_BUDGET_MS
            if (navResult.capturedEmbedRequests.size > embeds.size) {
                Log.i(TAG_SURF, "Resolving the ${embeds.size} newest of " +
                    "${navResult.capturedEmbedRequests.size} embed(s)")
            }
            // Once links exist, the rest of the queue is a bonus and must not cost the user a spinner.
            // 2026-07-30: cimanowtv resolved the full 480/720/1080 ladder 0.5 s in, then the loop spent
            // 15 more seconds on ok.ru and a VK embed that burned its entire 12 s cap for nothing —
            // playback sat waiting with every link it needed already delivered. This is the same
            // straggler problem STRAGGLER_GRACE_MS was introduced for on the server-resolution path.
            var firstLinkAt: Long? = null
            for (embed in embeds) {
                val now = System.currentTimeMillis()
                if (now > phaseDeadline) {
                    Log.w(TAG_SURF, "⏱ Embed phase budget (${EMBED_PHASE_BUDGET_MS}ms) spent — " +
                        "stopping with found=$found")
                    break
                }
                if (found) {
                    if (firstLinkAt == null) firstLinkAt = now
                    val sinceFirst = now - firstLinkAt
                    if (sinceFirst > STRAGGLER_GRACE_MS) {
                        Log.i(TAG_SURF, "⏭ Links already delivered — cancelling the remaining " +
                            "embed(s) after ${sinceFirst}ms of grace")
                        break
                    }
                }
                val serverName = try {
                    java.net.URI(embed.url).host ?: "Surf"
                } catch (_: Exception) { "Surf" }
                Log.i(TAG_SURF, "Resolving embed: host=$serverName html=${embed.html?.length ?: -1} " +
                    "url=${embed.url.take(140)}")

                // Captured HTML first: no second request, so no rate limit and no embed-only endpoint
                // refusing a top-level fetch. This is what the network path kept failing at.
                val htmlLinks = resolveEmbedFromHtml(embed, serverName, embedCallback)
                if (htmlLinks) {
                    Log.i(TAG_SURF, "✅ Resolved $serverName from the captured embed HTML — no request made")
                    continue
                }

                // Capped, because the user is watching a spinner with no WebView on screen.
                // VKVideoEmbed falls back to a 45s HEADLESS sniffer session when the embed page has no
                // player params, and on 2026-07-30 a dead VK embed (its error page) spent 49s in there
                // before failing. A bound turns that into a quick "next".
                //
                // Once links exist the cap shrinks to whatever grace is left: an embed that *starts*
                // inside the grace window would otherwise still run the full 12 s, which is exactly how
                // the VK straggler held playback for 12 s with the ladder already delivered.
                val cap = if (found && firstLinkAt != null) {
                    (STRAGGLER_GRACE_MS - (System.currentTimeMillis() - firstLinkAt)).coerceAtLeast(250L)
                } else {
                    EMBED_RESOLVE_TIMEOUT_MS
                }
                try {
                    kotlinx.coroutines.withTimeout(cap) {
                        // Referer = the page that hosted the iframe, which is what the embed expects.
                        fallbackExtractIframe(
                            iframeUrl = embed.url,
                            serverName = serverName,
                            referer = embed.pageUrl.ifBlank { navResult.finalUrl },
                            callback = embedCallback
                        )
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    Log.w(TAG_SURF, "⏱ Extractor for $serverName exceeded ${cap}ms — moving on")
                } catch (e: Exception) {
                    Log.w(TAG_SURF, "Extractor threw for $serverName: ${e.message}")
                }
            }

            if (found) {
                Log.i(TAG_SURF, "✅ Extractors produced links from ${navResult.capturedEmbedRequests.size} embed(s) " +
                    "— skipping the sniffed-stream fallback")
                return SurfOutcome(true, challenges, producedCandidates)
            }
            Log.w(TAG_SURF, "No extractor produced links — falling back to the sniffed stream(s)")

            // A rejection filter, NOT VideoUrlClassifier.isVideoUrl — see isPlayableCapture. The VK
            // servers stream from `vk6-3.vkuser.net/?expires=…&type=1&…`, which has no extension and
            // no /hls/ path, so a recognition filter discards the one thing the sniffer was for.
            val playable = navResult.capturedVideoRequests
                .filter { VideoUrlClassifier.isPlayableCapture(it.url) }
                .distinctBy { renditionKey(it.url) }

            if (playable.isEmpty()) {
                Log.w(TAG_SURF, "No playable video seen on the wire " +
                    "(${navResult.capturedVideoRequests.size} capture(s), all segments/assets)")
                return SurfOutcome(false, challenges, producedCandidates)
            }

            for (capture in playable) {
                val link = buildSurfLink(capture, userAgent)
                Log.i(TAG_SURF, ">>> LINK q=${link.quality} ${link.url.take(150)} referer=${link.referer.take(80)}")
                callback(link)
                found = true
            }
            return SurfOutcome(found, challenges, producedCandidates)
        } catch (e: Exception) {
            Log.e(TAG_SURF, "Surf resolve failed: ${e.message}")
            Log.e(TAG_SURF, "Stack: ${e.stackTrace?.joinToString("\n") { "  at $it" }}")
            return SurfOutcome(found = false)
        }
    }

    /**
     * Turns one sniffed request into an ExtractorLink ExoPlayer can actually open.
     *
     * The headers are the request's own, because that is the whole value of sniffing rather than
     * reconstructing: tokenised CDNs check `Referer`/`Origin` (and often a cookie) against what the
     * embed sent, and any value we invent is a guess. Only the hop-by-hop headers WebView fills in
     * are dropped — ExoPlayer sets its own, and passing `Host` or `Connection` through breaks the
     * request outright.
     */
    /**
     * Writes a captured embed page to disk when nothing could be parsed out of it.
     *
     * The fast path claims to resolve an embed with no request; on 2026-07-30 it resolved **none** of
     * three captured pages (cimanowtv 4,412 chars, ok.ru 23,851, VK 56,152) and the network extractors
     * did all the work — with the VK miss costing a 12 s rate-limited round trip. Whether the sources
     * are behind a packer variant the unpacker misses, or fetched by JS after load, or simply not in the
     * document, is not answerable from a length. So keep the bytes.
     *
     * Failure path only, and the log line carries the path.
     */
    private fun dumpEmbedHtml(embed: CapturedEmbedRequest, serverName: String, html: String) {
        try {
            val ctx = if (::context.isInitialized) context else null
            val dir = ctx?.externalCacheDir ?: ctx?.cacheDir ?: return
            dir.mkdirs()
            val safeHost = serverName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
            val file = java.io.File(dir, "embedhtml_$safeHost.html")
            file.writeText("<!-- source: ${embed.url}\n     referer: ${embed.pageUrl} -->\n$html")
            Log.w("CimaNowEmbedHtml", "📄 EMBED HTML DUMP: ${file.absolutePath} (${html.length} chars)")
        } catch (e: Exception) {
            Log.w("CimaNowEmbedHtml", "Embed HTML dump failed: ${e.message}")
        }
    }

    /**
     * Resolves an embed from the HTML the WebView already received, without touching the network.
     *
     * Tried before the URL-based extractors because a second request to an embed host is exactly what
     * kept failing: `video_ext.php` rate-limited the retry into a 10 s timeout, and its WebView
     * fallback — a top-level navigation rather than an iframe — got VK's error page. The captured
     * bytes are the ones the player itself was served.
     *
     * Host-specific parsing first, then a generic scan for direct media URLs, which covers embeds that
     * simply list their sources in the page.
     *
     * @return true if any link was produced.
     */
    private suspend fun resolveEmbedFromHtml(
        embed: CapturedEmbedRequest,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = embed.html
        if (html.isNullOrBlank()) return false
        val TAG_EH = "CimaNowEmbedHtml"

        val host = try { java.net.URI(embed.url).host?.lowercase() ?: "" } catch (_: Exception) { "" }

        if (host.contains("vkvideo") || host.contains("vk.com") || host.contains("vk.ru")) {
            try {
                if (VKVideoEmbed().getUrlFromHtml(html, embed.url, callback)) return true
                Log.w(TAG_EH, "VK params not found in captured HTML (${html.length} chars)")
            } catch (e: Exception) {
                Log.w(TAG_EH, "VK HTML parse threw: ${e.message}")
            }
        }

        // Generic: direct media URLs written into the embed page.
        //
        // Unpack first: uqload and a whole family of hosts keep their sources inside a Dean Edwards
        // `eval(function(p,a,c,k,e,d){…})` block, where the URL exists only as dictionary indices. The
        // uqload embed was captured intact (10,214 chars) and still produced nothing for exactly this
        // reason (2026-07-30). Then unescape, because what survives is usually JSON
        // (`"file":"https:\/\/…m3u8"`).
        val unpacked = try {
            VideoUrlClassifier.unpackJs(html)
        } catch (e: Exception) {
            Log.w(TAG_EH, "unpackJs failed, scanning raw: ${e.message}")
            html
        }
        if (unpacked.length != html.length) {
            Log.i(TAG_EH, "Unpacked packed script(s): ${html.length} → ${unpacked.length} chars")
        }
        val unescaped = unpacked.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        val urls = Regex("""https?://[^"'\s\\<>]+\.(?:m3u8|mp4)[^"'\s\\<>]*""")
            .findAll(unescaped)
            .map { it.value }
            .filter { VideoUrlClassifier.isPlayableCapture(it) }
            .distinctBy { renditionKey(it) }
            .take(12)
            .toList()

        if (urls.isEmpty()) {
            // A length and a host are enough to tell "this was an ad iframe" from "this was a player
            // page we failed to parse", which is all the log needs day to day. The bytes themselves
            // only matter to someone actually fixing this path — see DUMP_UNPARSEABLE_EMBED_HTML.
            Log.i(TAG_EH, "No media URLs in $serverName embed HTML (${html.length} chars) — " +
                "leaving it to the registered extractors")
            if (DUMP_UNPARSEABLE_EMBED_HTML) dumpEmbedHtml(embed, serverName, html)
            return false
        }
        Log.i(TAG_EH, "Generic scan found ${urls.size} media URL(s) in $serverName embed HTML")

        for (mediaUrl in urls) {
            val quality = vkQuality(mediaUrl) ?: getQualityFromName(mediaUrl)
            val link = newExtractorLink(
                "CimaNow", "CimaNow $serverName", mediaUrl, type = getLinkType(mediaUrl)
            ) {
                this.referer = embed.url
                this.quality = quality
            }
            callback(link)
        }
        return true
    }

    /**
     * Identity of a stream *rendition*, for de-duplicating sniffed captures.
     *
     * The raw URL is not that identity. A player re-requests the same rendition with volatile
     * parameters — VK varies `ct=` (11 vs 12), adds `fromCache=1`, and reorders the query string — so
     * `distinctBy { url }` let the same 480p stream through twice and put two identical entries in the
     * picker (2026-07-30). What actually identifies a VK rendition is its signature and type, since
     * each rung is signed separately. Falls back to host + path + a sorted query with the known
     * volatile keys stripped.
     */
    private fun renditionKey(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val query = uri.rawQuery ?: ""
            val params = query.split("&").mapNotNull { pair ->
                val name = pair.substringBefore("=", "")
                if (name.isBlank()) null else name.lowercase() to pair.substringAfter("=", "")
            }.toMap()

            // VK: sig is per-rendition, type is the quality rung. Together they are the identity.
            val sig = params["sig"]
            val type = params["type"]
            if (sig != null && type != null) {
                return "${uri.host}|type=$type|sig=$sig"
            }

            val volatileKeys = setOf("ct", "fromcache", "bytes", "range", "_", "cachebust", "ch")
            val stable = params.filterKeys { it !in volatileKeys }
                .entries.sortedBy { it.key }
                .joinToString("&") { "${it.key}=${it.value}" }
            "${uri.host}${uri.path}|$stable"
        } catch (_: Exception) {
            url
        }
    }

    /**
     * Resolution for a VK CDN stream, whose URL states its quality only as `type=N`.
     *
     * Without this every VK capture is `Qualities.Unknown`, and since one server produces the whole
     * ladder at once (`type=1`, `3`, `5` in the same session) the user gets several identically
     * labelled sources with no way to tell 360p from 1080p. Returns null for anything that is not a
     * VK URL so the caller falls back to reading digits out of the URL.
     */
    private fun vkQuality(url: String): Int? {
        if (!url.contains("vkuser.net") && !url.contains("vkcdn") && !url.contains("userapi.net")) {
            return null
        }
        val type = Regex("""[?&]type=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        return when (type) {
            0 -> Qualities.P144.value
            1 -> Qualities.P240.value
            2 -> Qualities.P360.value
            3 -> Qualities.P480.value
            4 -> Qualities.P720.value
            5 -> Qualities.P1080.value
            6 -> Qualities.P1440.value
            7 -> Qualities.P2160.value
            else -> null
        }
    }

    private suspend fun buildSurfLink(
        capture: CapturedVideoRequest,
        userAgent: String
    ): ExtractorLink {
        val url = capture.url
        val cookies = try {
            android.webkit.CookieManager.getInstance().getCookie(url)
        } catch (_: Exception) { null }

        val headers = capture.headers.filterKeys { key ->
            !key.equals("Host", ignoreCase = true) &&
            !key.equals("Connection", ignoreCase = true) &&
            !key.equals("Content-Length", ignoreCase = true) &&
            !key.equals("Content-Type", ignoreCase = true) &&
            !key.equals("Upgrade", ignoreCase = true) &&
            !key.equals("Transfer-Encoding", ignoreCase = true) &&
            !key.equals("Accept-Encoding", ignoreCase = true)
        }.toMutableMap()

        // Fall back to the embed document only where the request itself said nothing.
        val pageOrigin = try {
            val uri = java.net.URI(capture.pageUrl)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) { "" }

        if (!headers.keys.any { it.equals("Referer", ignoreCase = true) } && capture.pageUrl.isNotBlank()) {
            headers["Referer"] = capture.pageUrl
        }
        if (!headers.keys.any { it.equals("Origin", ignoreCase = true) } && pageOrigin.isNotBlank()) {
            headers["Origin"] = pageOrigin
        }
        headers["User-Agent"] = userAgent

        // The request's own cookies win — they are the ones the CDN issued for this stream. Session
        // cookies (cf_clearance and friends, held by ProviderHttpService) only fill the gaps, which
        // matters when the stream is served from the provider's own domain.
        val mergedCookies = linkedMapOf<String, String>()
        for ((name, value) in SessionProvider.getCookies()) mergedCookies[name] = value
        cookies?.split(";")?.forEach { pair ->
            val trimmed = pair.trim()
            val name = trimmed.substringBefore("=", "")
            if (name.isNotBlank()) mergedCookies[name] = trimmed.substringAfter("=", "")
        }
        if (mergedCookies.isNotEmpty()) {
            headers["Cookie"] = mergedCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        val refererValue = headers.entries
            .firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
            ?: capture.pageUrl

        val quality = vkQuality(url) ?: when {
            url.contains("2160") || url.contains("4k", ignoreCase = true) -> Qualities.P2160.value
            url.contains("1440") -> Qualities.P1440.value
            url.contains("1080") -> Qualities.P1080.value
            url.contains("720") -> Qualities.P720.value
            url.contains("480") -> Qualities.P480.value
            url.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }

        // One server hands over its whole ladder at once, so the label has to carry the resolution —
        // otherwise the picker shows several identical "CimaNow (Surf)" entries.
        val label = if (quality == Qualities.Unknown.value) {
            "CimaNow (Surf)"
        } else {
            "CimaNow (Surf) ${quality}p"
        }

        return newExtractorLink("CimaNow", label, url, type = getLinkType(url)) {
            this.referer = refererValue
            this.quality = quality
            this.headers = headers
        }
    }

    // ==================== handlecima ====================

    private suspend fun handlecima(
        iframeUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val TAG_CI = "CimaNowExtractor"
        Log.i(TAG_CI, "handlecima: iframe=$iframeUrl")
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            Log.d(TAG_CI, "Fetching cimanow iframe page: $finalUrl")
            val iframeResponse = httpService.getText(finalUrl, headers = mapOf("Referer" to finalUrl), rewriteDomain = false) ?: ""
            Log.d(TAG_CI, "Iframe response size: ${iframeResponse.length} bytes")

            val regex = Regex("\\[(\\d+p)]\\s+(/uploads/[^\"]+\\.mp4)")
            val baseUrlMatch = Regex("(https?://[^/]+)").find(finalUrl)
            val baseUrl = baseUrlMatch?.groupValues?.get(1) ?: ""
            Log.d(TAG_CI, "Base URL for mp4: $baseUrl")

            val links = mutableListOf<ExtractorLink>()

            for (match in regex.findAll(iframeResponse)) {
                val qualityStr = match.groupValues[1]
                val filePath = match.groupValues[2]
                val videoUrl = baseUrl + filePath
                Log.d(TAG_CI, "Found quality link: $qualityStr -> $videoUrl")

                val link = newExtractorLink("CimaNow", "CimaNow", videoUrl, type = getLinkType(videoUrl))
                link.quality = getQualityFromName(qualityStr)
                link.referer = finalUrl
                links.add(link)
            }

            if (links.size > 1) {
                links.sortByDescending { it.quality }
            }

            if (links.isEmpty()) {
                Log.w(TAG_CI, "No quality links found in response. Preview: ${iframeResponse.take(200)}")
            } else {
                for (link in links) {
                    Log.i(TAG_CI, "Reporting quality link: ${link.quality}p -> ${link.url}")
                    callback(link)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG_CI, "handlecima error: ${e.message}")
            Log.e(TAG_CI, "Stack: ${e.stackTrace?.joinToString("\n") { "  at $it" }}")
        }
    }

    // ==================== Simple handle methods (loadExtractor based) ====================

    private suspend fun handleVidPro(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (_: Exception) {}
    }

    private suspend fun handleGovid(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (_: Exception) {}
    }

    private suspend fun handleVidlook(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (_: Exception) {}
    }

    private suspend fun handleStreamwish(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (_: Exception) {}
    }

    private suspend fun handleStreamfileAndLuluvid(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (_: Exception) {}
    }

    private suspend fun handleVadbamAndViidshare(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, { }, callback)
        } catch (_: Exception) {}
    }

    // ==================== handleJetload ====================

    private suspend fun handleJetload(
        url: String,
        quality: Int,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val TAG_JL = "JetloadExtractor"
        val headers = mapOf(
            "User-Agent" to httpService.userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "ar-EG,ar;q=0.9"
        )

        try {
            val res1 = httpService.getRaw(url, headers = headers)
            res1.close()
            val sessionCookies = mutableMapOf<String, String>()
            for (header in res1.headers("Set-Cookie")) {
                val eqIdx = header.indexOf('=')
                if (eqIdx > 0) {
                    val semiIdx = header.indexOf(';')
                    val value = if (semiIdx > 0) header.substring(eqIdx + 1, semiIdx) else header.substring(eqIdx + 1)
                    sessionCookies[header.substring(0, eqIdx)] = value
                }
            }

            val headers2 = headers + ("Referer" to url)
            val targetUrl = "https://jetload.pp.ua/Jetload4/"
            val cookieHeader = sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val res2 = httpService.getRaw(targetUrl, headers = headers2 + ("Cookie" to cookieHeader))
            val html = res2.body?.string() ?: return
            res2.close()
            for (header in res2.headers("Set-Cookie")) {
                val eqIdx = header.indexOf('=')
                if (eqIdx > 0) {
                    val semiIdx = header.indexOf(';')
                    val value = if (semiIdx > 0) header.substring(eqIdx + 1, semiIdx) else header.substring(eqIdx + 1)
                    sessionCookies[header.substring(0, eqIdx)] = value
                }
            }

            val extraToken = Regex("window\\.extraToken\\s*=\\s*'([^']+)'").find(html)?.groupValues?.get(1)
            val dataToken = Regex("data-token=\"([^\"]+)\"").find(html)?.groupValues?.get(1)

            if (extraToken == null || dataToken == null) {
                Log.e(TAG_JL, "[-] Failed to extract tokens.")
                return
            }

            delay(10000)

            val ajaxUrl = "https://jetload.pp.ua/Jetload4/get-link.php?token=$dataToken"
            val ajaxHeaders = headers2 + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to targetUrl
            )
            val cookieHdr = sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val finalResp = httpService.getRaw(ajaxUrl, headers = ajaxHeaders + ("Cookie" to cookieHdr))
            val rawLink = finalResp.body?.string()?.trim() ?: return
            finalResp.close()

            if (!rawLink.startsWith("http")) {
                Log.e(TAG_JL, "[-] Invalid server response: $rawLink")
                return
            }

            val intermediateLink = "$rawLink?t=$extraToken"
            Log.d(TAG_JL, "[+] Final Media Link: $intermediateLink")

            val link = newExtractorLink("Jetload", "Jetload", intermediateLink)
            link.referer = targetUrl
            link.quality = quality
            callback(link)

        } catch (e: Exception) {
            Log.e(TAG_JL, "[-] Error in Jetload: ${e.message}")
        }
    }

    // ==================== handleForafile ====================

    private suspend fun handleForafile(
        url: String,
        quality: Int,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val TAG_FF = "ForafileExtractor"
        try {
            val match = Regex("(https://forafile\\.com/([^/]+)/)").find(url) ?: return
            val baseUrl = match.groupValues[1]
            val fileId = match.groupValues[2]

            val headers = mapOf(
                "User-Agent" to httpService.userAgent,
                "Referer" to url
            )
            val data = mapOf(
                "op" to "download2",
                "id" to fileId,
                "rand" to "",
                "referer" to "",
                "method_free" to "",
                "method_premium" to "",
                "adblock_detected" to "0"
            )

            val formBody = okhttp3.FormBody.Builder().apply {
                for ((k, v) in data) { add(k, v) }
            }.build()
            val headerBuilder = okhttp3.Headers.Builder()
            for ((k, v) in headers) { headerBuilder.add(k, v) }
            val okRequest = okhttp3.Request.Builder()
                .url(baseUrl)
                .headers(headerBuilder.build())
                .post(formBody)
                .build()
            val client = app.baseClient.newBuilder()
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val response = client.newCall(okRequest).execute()
            val location = response.header("location") ?: response.header("Location")
            response.close()

            if (location.isNullOrBlank()) {
                Log.e(TAG_FF, "[-] No redirect location found.")
                return
            }

            val link = newExtractorLink("Forafile", "Forafile", location)
            link.referer = baseUrl
            link.quality = quality
            callback(link)

        } catch (e: Exception) {
            Log.e(TAG_FF, "[-] Error in Forafile: ${e.message}")
        }
    }

    // ==================== fallbackExtractIframe ====================

    private suspend fun fallbackExtractIframe(
        iframeUrl: String,
        serverName: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val TAG_FE = "FallbackExtract"
        try {
            // Try dedicated extractors first (before loadExtractor)
            val host = Regex("https?://([^/]+)").find(iframeUrl)?.groupValues?.get(1) ?: ""
            var extracted = false

            val countingCb: (ExtractorLink) -> Unit = { link ->
                extracted = true
                Log.i(TAG_FE, "Dedicated extractor SUCCEEDED for '$serverName' -> ${link.url.take(120)}")
                callback(link)
            }

            // ── CimaNowTV embed (random subdomain *.cimanowtv.com/e/{id}) ──
            if (!extracted && (host.endsWith(".cimanowtv.com") || host == "cimanowtv.com")) {
                Log.i(TAG_FE, "Trying CimaNowTVEmbed for '$serverName'")
                try {
                    CimaNowTVEmbed().getUrl(iframeUrl, referer, {}, countingCb)
                } catch (e: Exception) {
                    Log.w(TAG_FE, "CimaNowTVEmbed threw for '$serverName': ${e.message}")
                }
                if (extracted) {
                    Log.i(TAG_FE, "CimaNowTVEmbed succeeded for '$serverName'")
                    return
                }
            }

            // Upnshare has no dedicated extractor: it was the server that consistently burned the
            // full 20s budget and returned nothing, and losing an extractor costs nothing here —
            // when no extractor claims an iframe, the sniffer below takes it.

            // ── VK Video embed (vkvideo.ru or vk.com) ──
            if (!extracted && (host.contains("vkvideo") || host.contains("vk.com"))) {
                Log.i(TAG_FE, "Trying VKVideoEmbed for '$serverName'")
                try {
                    VKVideoEmbed().getUrl(iframeUrl, referer, {}, countingCb)
                } catch (e: Exception) {
                    Log.w(TAG_FE, "VKVideoEmbed threw for '$serverName': ${e.message}")
                }
                if (extracted) {
                    Log.i(TAG_FE, "VKVideoEmbed succeeded for '$serverName'")
                    return
                }
            }

            // ── Standard registered extractor ──
            if (!extracted) {
                val countingCallback: (ExtractorLink) -> Unit = { link ->
                    extracted = true
                    Log.i(TAG_FE, "loadExtractor SUCCEEDED for '$serverName' -> ${link.url.take(120)}")
                    callback(link)
                }
                try {
                    Log.i(TAG_FE, "Calling loadExtractor for server='$serverName' iframeUrl=$iframeUrl")
                    loadExtractor(iframeUrl, referer, {}, countingCallback)
                } catch (e: Exception) {
                    Log.w(TAG_FE, "loadExtractor threw for '$serverName': ${e.message}")
                }
                if (extracted) {
                    Log.i(TAG_FE, "loadExtractor produced links for '$serverName', skipping HTTP fallback")
                    return
                }
            }

            // ── HTTP fallback: fetch page and scrape video URLs ──
            Log.w(TAG_FE, "No extractor matched for '$serverName' — trying HTTP fallback for $iframeUrl")
            val html = httpService.getText(iframeUrl, headers = mapOf("Referer" to referer), rewriteDomain = false)
            if (html == null) {
                Log.w(TAG_FE, "HTTP fallback returned null for '$serverName'")
                return
            }
            Log.i(TAG_FE, "HTTP fallback got ${html.length} chars for '$serverName'")
            val doc = Jsoup.parse(html, iframeUrl)

            val urls = mutableListOf<String>()
            Regex("""file:\s*["']([^"']+)["']""").findAll(html).forEach { urls.add(it.groupValues[1]) }
            Regex("""src=["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""").findAll(html).forEach { urls.add(it.groupValues[1]) }
            doc.select("source[src]").forEach { urls.add(it.attr("src")) }
            doc.select("video[src]").forEach { urls.add(it.attr("src")) }

            val baseUrl = Regex("(https?://[^/]+)").find(iframeUrl)?.groupValues?.get(1) ?: ""
            Log.i(TAG_FE, "HTTP fallback found ${urls.size} raw URLs for '$serverName'")
            for (url in urls.distinct()) {
                val finalUrl = when {
                    url.startsWith("http") -> url
                    url.startsWith("//") -> "https:$url"
                    url.startsWith("/") -> "$baseUrl$url"
                    else -> "$baseUrl/$url"
                }
                if (finalUrl.contains(".mp4") || finalUrl.contains(".m3u8")) {
                    val link = newExtractorLink(serverName, serverName, finalUrl, type = getLinkType(finalUrl))
                    link.referer = iframeUrl
                    callback(link)
                    Log.i(TAG_FE, "HTTP fallback EMITTED link for '$serverName': ${finalUrl.take(100)}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG_FE, "Error extracting from $serverName: ${e.message}")
        }
    }

    // ==================== resolveFreex2line ====================

    private suspend fun resolveFreex2line(url: String): String? {
        val TAG_FX = "Freex2lineResolver"
        Log.i(TAG_FX, "======= [START] =======")
        Log.d(TAG_FX, "Input URL: $url")

        try {
            val linkParam = url.substringAfter("link=", "")
            Log.d(TAG_FX, "Extracted link param (base64): ${linkParam.take(50)}...")

            if (linkParam.isBlank()) {
                Log.e(TAG_FX, "No 'link' parameter found in URL: $url")
                return null
            }

            val decodedBytes = Base64.decode(linkParam, Base64.DEFAULT)
            val decodedUrl = String(decodedBytes, Charsets.UTF_8)
            Log.d(TAG_FX, "Decoded raw: $decodedUrl")

            if (decodedUrl.startsWith("http")) {
                Log.i(TAG_FX, "[SUCCESS] Resolved URL: $decodedUrl")
                return decodedUrl
            }

            Log.e(TAG_FX, "Decoded value is not a valid URL: $decodedUrl")
        } catch (e: Exception) {
            Log.e(TAG_FX, "Exception: ${e.message}")
            Log.e(TAG_FX, "Stack: ${e.stackTrace?.joinToString("\n") { "  at $it" }}")
        }

        Log.i(TAG_FX, "======= [FAILED] =======")
        return null
    }

    /**
     * Media type for a URL, defaulting to **progressive**, not HLS.
     *
     * The old default was `M3U8` for anything without a recognised extension, which is how the first
     * working surf run still failed to play (2026-07-30): VK streams from
     * `vk6-3.vkuser.net/?…&type=3&…` have no extension, were typed M3U8, and ExoPlayer's HLS reader
     * died on the first bytes — `ParserException: Input does not start with the #EXTM3U header`. On the
     * way there `M3u8Helper2.hslLazy` also tried to read the 5 MB video as text and threw.
     *
     * Extension-less URLs from a network sniffer are overwhelmingly progressive files behind a token,
     * so that is the default; genuine manifests are caught either by `.m3u8` or by the `/hls/` path
     * test in [VideoUrlClassifier.isLikelyHlsManifest]. Guessing wrong in the other direction is not
     * symmetric: a mislabelled manifest is one failed source, a mislabelled progressive stream is
     * every VK server in the provider.
     */
    private fun getLinkType(url: String): ExtractorLinkType {
        return when {
            url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            VideoUrlClassifier.isLikelyHlsManifest(url) -> ExtractorLinkType.M3U8
            url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
    }

    /**
     * True only when the HTML contains a real server-list element (<li ... data-index=...>),
     * NOT merely the substring "data-index". The bare substring also appears in injected
     * hook/script text, so a substring check yields a false positive.
     */
    private fun hasServerEntries(html: String): Boolean =
        Regex("<li\\b[^>]*\\bdata-index\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(html)

    /**
     * Decrypt the captured (still-encrypted) watch-page HTML by letting the page's OWN decryptor
     * run in a WebView — the WebView does all the work; nothing is decrypted in Kotlin.
     *
     * Delegates to NavigationEngine.renderHtmlInSandbox, which (per the decoded anti-bot): serves
     * the HTML as a real navigation to [pageUrl] (so document.write/open commit and location.host
     * is set), sets document.referrer via [referrer] (to pass the /home redirect gate), and reads
     * the decrypted server list back through an in-page reader over a JavascriptInterface (never
     * via evaluateJavascript, which the page's isBot() stack-check would sabotage).
     *
     * @param watchHtml captured watch-page HTML (still encrypted)
     * @param pageUrl   the real /watching/?token=… URL — served as the document's URL/origin
     * @param referrer  the Referer the watch page was actually reached with (the freex blog-post
     *                  page). The decrypted page redirects to /home unless document.referrer
     *                  matches that host, which aborts the parse — so this must mirror the real
     *                  navigation, NOT be hardcoded. See the HAR: watching → Referer blog-post.html/.
     */
    /**
     * Re-fetches the resolved `/watching/?token=…` page so the sandbox renders a payload generated
     * for the current session rather than a replay of one captured earlier.
     *
     * Sent with the blog-post page as Referer (the gate redirects to `/home` without it), the session
     * User-Agent, and whatever cookies the CookieManager now holds — which after a CF solve includes
     * `cf_clearance`, something the originally-captured copy predates.
     *
     * Returns null on any failure; the caller then falls back to the captured copy, so this can only
     * add information.
     */
    private suspend fun refetchWatchPage(watchUrl: String, referrer: String): String? =
        withContext(Dispatchers.IO) {
            if (watchUrl.isBlank()) return@withContext null
            try {
                val cookies = try {
                    android.webkit.CookieManager.getInstance().getCookie(watchUrl)
                } catch (_: Exception) { null }
                Log.i("CimaNowSandbox", "🔄 Refetching watch page | referer=${referrer.take(60)} " +
                    "cookies=${cookies?.split(";")?.size ?: 0} " +
                    "hasClearance=${cookies?.contains("cf_clearance") == true}")

                val headers = buildMap {
                    put("Referer", referrer)
                    put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    put("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                    put("Sec-Fetch-Dest", "document")
                    put("Sec-Fetch-Mode", "navigate")
                    put("Sec-Fetch-Site", "cross-site")
                    put("Upgrade-Insecure-Requests", "1")
                }
                val body = httpService.getText(watchUrl, headers = headers)
                if (body.isNullOrBlank()) {
                    Log.w("CimaNowSandbox", "Refetch returned no body")
                    null
                } else {
                    Log.i("CimaNowSandbox", "Refetch ok | ${body.length} chars, hasServerEntries=${hasServerEntries(body)}")
                    body
                }
            } catch (e: Exception) {
                Log.w("CimaNowSandbox", "Refetch failed: ${e.message}")
                null
            }
        }

    private suspend fun decryptViaSandbox(watchHtml: String, pageUrl: String, referrer: String): String? {
        val TAG_SB = "CimaNowSandbox"
        Log.i(TAG_SB, "Phase 2: WebView stealth render starting — input ${watchHtml.length} chars, base=$pageUrl, referrer=$referrer")
        if (watchHtml.isBlank()) {
            Log.w(TAG_SB, "Empty watchHtml — nothing to decrypt")
            return null
        }
        return try {
            val result = httpService.navigationEngine.renderHtmlInSandbox(
                html = watchHtml,
                baseUrl = pageUrl.ifBlank { "https://cimanow.cc/" },
                userAgent = httpService.userAgent,
                referrer = referrer,
                timeoutMs = 25_000L
            )

            when {
                result.isNullOrBlank() -> {
                    Log.w(TAG_SB, "Sandbox returned no HTML")
                    null
                }
                hasServerEntries(result) -> {
                    Log.i(TAG_SB, "✅ Sandbox decrypt succeeded — ${result.length} chars, real <li data-index> present")
                    result
                }
                else -> {
                    // No real server elements. The page's inline decryptor did not emit the
                    // server list (see the render heartbeat: li=0/dh=0 for the whole window).
                    // Returning this HTML would only trip a substring false-positive downstream,
                    // so report failure honestly and let higher-level fallbacks decide.
                    Log.w(TAG_SB, "❌ Sandbox produced no <li data-index> elements (${result.length} chars) — decryptor did not run/emit")
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG_SB, "Sandbox error: ${e.message}")
            null
        }
    }

    // ==================== Hybrid Approach: HTTP Nav → WebView Timer ====================

    /**
     * Navigates the freex redirect chain using httpService (okhttp) directly with spoofed headers.
     *
     * Flow: loadon → redirectingfree → blog-post.html → (follow 301) → blog-post.html/ 158KB timer HTML.
     * Cookies are extracted at each hop and set into CookieManager for the WebView.
     *
     * @param movieUrl The CimaNow movie/episode page URL
     * @return The blog-post.html/ timer HTML (158KB) or null on failure
     */
    /**
     * The first `freex2line` link in a page — the "loadon" hop that starts the token chain.
     *
     * Kept as a function so the caller can try it against several bodies (cache-busted, plain,
     * decoded) without repeating the pattern, and so a miss is distinguishable from a block.
     */
    private fun extractFreexUrl(html: String): String? {
        if (html.isEmpty()) return null
        val m = Pattern.compile("href=[\"'](https?://[^\"']*freex2line[^\"']*)[\"']").matcher(html)
        return if (m.find()) m.group(1) else null
    }

    private suspend fun navigateToTimerPageViaHttp(movieUrl: String): String? {
        val TAG_HT = "CimaNowHttpNav"
        try {
            Log.i(TAG_HT, "======== [START] HTTP redirect chain navigation ========")
            Log.i(TAG_HT, "movieUrl: $movieUrl")

            // ====================== Step 1: Get movie page ======================
            //
            // Through the session-aware path, NOT `getRaw`.
            //
            // 2026-08-03: this step fetched the movie page with `httpService.getRaw(fetchUrl, UA +
            // Accept)`. `getRaw` is a bare OkHttp call — it sends exactly the headers it is handed,
            // so no `cf_clearance`, no `Sec-Ch-Ua`, no `Referer`, no `Sec-Fetch-*` — and it has no
            // Cloudflare handling of any kind. Cloudflare answered **HTTP 403 with a 128,267-byte
            // block page**, which of course contains no `freex2line` href, and the chain died with
            // "FATAL: No freex URL found in movie page" — a message that reads like the site changed
            // its markup when in fact we were never let in. Total elapsed time: 60 ms.
            //
            // The status code was fetched into `movieStatus` and then never checked, which is what
            // let a block masquerade as a parse failure.
            //
            // `getDocument(rewriteDomain = true)` is the same path `load()` uses on this very URL
            // seconds earlier — and that one succeeds. It carries the session cookies (including a
            // `cf_clearance` already minted for this UA), real-Chrome client hints, and falls back
            // to a WebView CF solve when a 403 carries CF markers.
            Log.i(TAG_HT, "Step 1/4: Fetching movie page to extract freex URL")
            val cacheBuster = "_ts=${System.currentTimeMillis()}"
            // Was `"?_ts=…"` concatenated after a `&`, producing `…&?_ts=…` on any URL that already
            // had a query. Harmless on this URL shape, wrong on every other.
            val fetchUrl = if (movieUrl.contains("?")) "$movieUrl&$cacheBuster" else "$movieUrl?$cacheBuster"
            Log.d(TAG_HT, "GET $fetchUrl (session path: cookies + client hints + CF fallback)")

            // Guarded: this is the first call in the flow that can trigger a CF solve, and a solve the
            // user cancels clears the old session without restoring it — which would break the surf
            // below for a reason unrelated to the surf. See withSessionGuard.
            val fetched = withSessionGuard(httpService, TAG_HT) {
                var doc = httpService.getDocument(fetchUrl, rewriteDomain = true)
                var html = doc?.outerHtml() ?: ""
                Log.i(TAG_HT, "Movie page: ${html.length} chars")
                var freex = extractFreexUrl(html)

                // Retry without the cache-buster before giving up. The `?_ts=` param guarantees a
                // Cloudflare cache MISS, so every attempt is evaluated by the WAF at the origin — the
                // one request shape most likely to be challenged, for a page whose freex link does not
                // change minute to minute.
                if (freex == null) {
                    Log.w(TAG_HT, "No freex URL with the cache-buster — retrying the plain URL")
                    doc = httpService.getDocument(movieUrl, rewriteDomain = true)
                    html = doc?.outerHtml() ?: ""
                    Log.i(TAG_HT, "Movie page (plain URL): ${html.length} chars")
                    freex = extractFreexUrl(html)
                }
                Triple(doc, html, freex)
            }
            val movieDoc = fetched.first
            val movieHtml = fetched.second
            var freexUrl = fetched.third

            // Last resort: the link may be inside the page's encoded payload rather than the markup.
            if (freexUrl == null && movieDoc != null) {
                val decoded = decodeHtml(movieDoc).outerHtml()
                if (decoded.length != movieHtml.length) {
                    Log.i(TAG_HT, "Searching the decoded payload (${decoded.length} chars)")
                    freexUrl = extractFreexUrl(decoded)
                }
            }

            val resolvedFreexUrl = freexUrl
            if (resolvedFreexUrl == null) {
                // Say which failure this is. The two are fixed in completely different places.
                val cfBlocked = com.cloudstream.shared.cloudflare.CloudflareDetector
                    .isCloudflareChallenge(movieHtml, 403)
                if (cfBlocked || movieHtml.isEmpty()) {
                    Log.e(TAG_HT, "FATAL: Cloudflare/WAF blocked the movie page — no markup to " +
                        "search (${movieHtml.length} chars). This is a session problem, not a " +
                        "parsing problem: the CF solve did not run or its clearance was rejected.")
                } else {
                    Log.e(TAG_HT, "FATAL: Movie page loaded (${movieHtml.length} chars) but carries " +
                        "no freex2line href — the site's watch-link markup changed")
                }
                return null
            }
            Log.i(TAG_HT, "Extracted freex URL: $resolvedFreexUrl")

            // ====================== Step 2: Fetch loadon ======================
            Log.i(TAG_HT, "Step 2/4: Fetching loadon → $resolvedFreexUrl")
            val sessionHeaders = mutableMapOf<String, String>()
            sessionHeaders["User-Agent"] = httpService.userAgent
            sessionHeaders["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

            val loadonResponse = httpService.getRaw(resolvedFreexUrl, headers = sessionHeaders)
            val loadonStatus = loadonResponse.code
            val loadonBody = loadonResponse.body?.string() ?: ""
            Log.i(TAG_HT, "loadon response: HTTP $loadonStatus, ${loadonBody.length} bytes")
            Log.d(TAG_HT, "loadon body preview (200 chars): ${loadonBody.take(200)}")

            // Extract Set-Cookie headers
            val cookies = mutableMapOf<String, String>()
            for (header in loadonResponse.headers("Set-Cookie")) {
                val eqIdx = header.indexOf('=')
                if (eqIdx > 0) {
                    val semiIdx = header.indexOf(';')
                    val value = if (semiIdx > 0) header.substring(eqIdx + 1, semiIdx) else header.substring(eqIdx + 1)
                    val key = header.substring(0, eqIdx)
                    cookies[key] = value
                    Log.d(TAG_HT, "Cookie from loadon: $key=$value")
                }
            }
            for (header in loadonResponse.headers("set-cookie")) {
                if (header.startsWith("Cookie=")) continue  // skip if already captured above
            }
            loadonResponse.close()

            if (cookies.isNotEmpty()) {
                sessionHeaders["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                Log.i(TAG_HT, "Tracking ${cookies.size} cookies: ${cookies.keys.joinToString(", ")}")
            } else {
                Log.w(TAG_HT, "No cookies set by loadon")
            }

            // Try to extract JS redirect URL from loadon response body for debugging
            val jsRedirectMatch = Regex("""window\.location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""").find(loadonBody)
            if (jsRedirectMatch != null) {
                Log.d(TAG_HT, "JS redirect target in loadon: ${jsRedirectMatch.groupValues[1]}")
            } else {
                Log.d(TAG_HT, "No JS redirect found in loadon body (expected if href.li is skipped)")
            }

            // ====================== Step 3: Fetch redirectingfree ======================
            Log.i(TAG_HT, "Step 3/4: Fetching redirectingfree")
            sessionHeaders["Referer"] = resolvedFreexUrl
            Log.d(TAG_HT, "GET https://rm.freex2line.online/redirectingfree/")
            Log.d(TAG_HT, "Request headers: ${sessionHeaders.entries.joinToString(", ") { "${it.key}=${it.value.take(50)}" }}")

            val redirResponse = httpService.getRaw("https://rm.freex2line.online/redirectingfree/", headers = sessionHeaders)
            val redirStatus = redirResponse.code
            val redirBody = redirResponse.body?.string() ?: ""
            Log.i(TAG_HT, "redirectingfree response: HTTP $redirStatus, ${redirBody.length} bytes")
            Log.d(TAG_HT, "redirectingfree body preview (200 chars): ${redirBody.take(200)}")

            // Extract any cookies
            var redirCookies = 0
            for (header in redirResponse.headers("Set-Cookie")) {
                val eqIdx = header.indexOf('=')
                if (eqIdx > 0) {
                    val semiIdx = header.indexOf(';')
                    val value = if (semiIdx > 0) header.substring(eqIdx + 1, semiIdx) else header.substring(eqIdx + 1)
                    val key = header.substring(0, eqIdx)
                    if (key !in cookies) {
                        cookies[key] = value
                        redirCookies++
                        Log.d(TAG_HT, "New cookie from redirectingfree: $key=$value")
                    }
                }
            }
            if (redirCookies > 0) {
                Log.i(TAG_HT, "Got $redirCookies new cookies from redirectingfree")
            }

            // Update cookie header
            if (cookies.isNotEmpty()) {
                sessionHeaders["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
            redirResponse.close()

            // Extract JS redirect target from redirectingfree
            val redirJsMatch = Regex("""window\.location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""").find(redirBody)
            if (redirJsMatch != null) {
                Log.d(TAG_HT, "JS redirect target in redirectingfree: ${redirJsMatch.groupValues[1]}")
            } else {
                Log.w(TAG_HT, "No JS redirect found in redirectingfree body")
                Log.d(TAG_HT, "redirectingfree full body: $redirBody")
            }

            // ====================== Step 4: Fetch blog-post.html ======================
            Log.i(TAG_HT, "Step 4/4: Fetching blog-post.html (with 301 redirect to blog-post.html/)")
            sessionHeaders["Referer"] = "https://rm.freex2line.online/redirectingfree/"
            sessionHeaders["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"

            Log.d(TAG_HT, "GET https://rm.freex2line.online/2020/02/blog-post.html")
            Log.d(TAG_HT, "Request headers: ${sessionHeaders.entries.joinToString(", ") { "${it.key}=${it.value.take(50)}" }}")

            val blogResponse = httpService.getRaw("https://rm.freex2line.online/2020/02/blog-post.html", headers = sessionHeaders)
            val blogStatus = blogResponse.code
            val blogFinalUrl = blogResponse.request.url.toString()
            val blogBody = blogResponse.body?.string() ?: ""
            Log.i(TAG_HT, "blog-post.html response: HTTP $blogStatus, ${blogBody.length} bytes, finalUrl=$blogFinalUrl")
            Log.d(TAG_HT, "blog-post.html body preview (200 chars): ${blogBody.take(200)}")

            // Check if we got the timer page (expecting ~158KB)
            when {
                blogBody.length > 100000 -> Log.i(TAG_HT, "✅ Timer page detected! Body is ${blogBody.length} bytes (expected ~158KB)")
                blogBody.length > 10000 -> Log.w(TAG_HT, "⚠️ Timer page seems smaller than expected: ${blogBody.length} bytes")
                blogBody.length < 1000 -> {
                    Log.e(TAG_HT, "❌ Timer page too small (${blogBody.length} bytes). Response may be blocked or empty.")
                    Log.d(TAG_HT, "Full body: $blogBody")
                }
            }

            // Extract any additional cookies
            for (header in blogResponse.headers("Set-Cookie")) {
                val eqIdx = header.indexOf('=')
                if (eqIdx > 0) {
                    val semiIdx = header.indexOf(';')
                    val value = if (semiIdx > 0) header.substring(eqIdx + 1, semiIdx) else header.substring(eqIdx + 1)
                    val key = header.substring(0, eqIdx)
                    if (key !in cookies) {
                        cookies[key] = value
                        Log.d(TAG_HT, "New cookie from blog-post: $key=$value")
                    }
                }
            }
            blogResponse.close()

            // ====================== Set cookies in CookieManager ======================
            if (cookies.isNotEmpty()) {
                Log.i(TAG_HT, "Setting ${cookies.size} cookies in CookieManager for rm.freex2line.online")
                val cm = android.webkit.CookieManager.getInstance()
                var setCount = 0
                for ((key, value) in cookies) {
                    cm.setCookie("https://rm.freex2line.online", "$key=$value; domain=.rm.freex2line.online")
                    setCount++
                }
                cm.flush()
                Log.i(TAG_HT, "Flushed $setCount cookies to CookieManager")
            } else {
                Log.w(TAG_HT, "No cookies to set in CookieManager")
            }

            // ====================== Validate the HTML ======================
            // Check for key markers that indicate it's the real timer page
            val hasCountdown = blogBody.contains("countdown") || blogBody.contains("setInterval") || blogBody.contains("setTimeout")
            val hasGetLink = blogBody.contains("get-link.php")
            val hasDownloadBtn = blogBody.contains("downloadbtn") || blogBody.contains("download-btn") || blogBody.contains("download_btn")
            Log.i(TAG_HT, "Validation: hasCountdown=$hasCountdown, hasGetLink=$hasGetLink, hasDownloadBtn=$hasDownloadBtn")

            if (blogBody.length < 5000) {
                Log.e(TAG_HT, "❌ Timer page body too short (${blogBody.length}), likely blocked by CF")
                Log.d(TAG_HT, "Dumping blog-post body for analysis:\n$blogBody")
                return null
            }

            Log.i(TAG_HT, "======== [END] HTTP redirect chain SUCCESS ========")
            return blogBody
        } catch (e: Exception) {
            Log.e(TAG_HT, "EXCEPTION in HTTP navigation: ${e.message}")
            Log.e(TAG_HT, "Stack: ${e.stackTrace?.joinToString("\n") { "  at $it" }}")
        }
        Log.i(TAG_HT, "======== [END] HTTP redirect chain FAILED ========")
        return null
    }


    /**
     * Official link resolver. The WebView does ALL decryption:
     *   1. HTTP-navigate the freex redirect chain to the timer (blog-post) page.
     *   2. Render it in the NavigationEngine, which follows the countdown → get-link.php → the
     *      cimanow /watching/ URL and captures its raw (still-encrypted) HTTP response.
     *   3. Decrypt that HTML in an isolated WebView via decryptViaSandbox (page's own JS runs,
     *      stealth in-page reader returns the server list — see NavigationEngine.renderHtmlInSandbox).
     *   4. Parse servers/downloads/iframes with Jsoup and resolve each (core.php → extractors).
     */
    private suspend fun resolveViaWebViewSandbox(
        movieUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val TAG_TEST = "CimaNowResolve"
        Log.i(TAG_TEST, "========== [START] WebView sandbox resolve ==========")
        Log.i(TAG_TEST, "Target URL: $movieUrl")
        // Said out loud, because the difference does not show up anywhere else in the log and the two
        // paths otherwise look alike from the outside.
        Log.w(TAG_TEST, "⚠️ LEGACY PATH (USE_FULLSCREEN_SURF = false): this injects an in-page reader " +
            "script and exfiltrates the decrypted DOM — handover rules 6/7/9/10 — against the real " +
            "cimanow.cc in this session. The surf path runs no JS in the page at all. If you did not " +
            "mean to flip that flag, flip it back.")

        // Declared OUTSIDE the try: links are handed to the player through `callback` as they are
        // found, so a late failure (a slow server blowing a timeout) must not erase the fact that
        // earlier servers already delivered. The catch below returns this, not a hard false.
        var found = false
        try {
            val userAgent = httpService.userAgent

            // ======================== PHASE 1: HTTP navigation ========================
            Log.i(TAG_TEST, "PHASE 1: Navigating redirect chain via httpService (OkHttp)...")
            val timerHtml = navigateToTimerPageViaHttp(movieUrl)
            if (timerHtml == null) {
                Log.e(TAG_TEST, "❌ PHASE 1 FAILED: Could not fetch timer page HTML via HTTP")
                return false
            }
            Log.i(TAG_TEST, "✅ PHASE 1 SUCCESS: Got timer page HTML (${timerHtml.length} bytes)")
            Log.d(TAG_TEST, "Timer HTML first 300 chars: ${timerHtml.take(300)}")
            Log.d(TAG_TEST, "Timer HTML last 100 chars: ...${timerHtml.takeLast(100)}")

            // Log key markers in the HTML
            for (marker in listOf("countdown", "get-link.php", "downloadbtn", "request_id", "hmac", "_0x_cfg", "setInterval", "setTimeout")) {
                val idx = timerHtml.indexOf(marker)
                if (idx >= 0) {
                    Log.d(TAG_TEST, "Marker '$marker' found at position $idx, context: ...${timerHtml.substring(maxOf(0, idx - 30), minOf(timerHtml.length, idx + 80))}...")
                } else {
                    Log.w(TAG_TEST, "Marker '$marker' NOT found in timer HTML")
                }
            }

            // ======================== PHASE 2: WebView rendering ========================
            Log.i(TAG_TEST, "PHASE 2: Rendering timer HTML in WebView via loadDataWithBaseURL...")
            val baseUrl = "https://rm.freex2line.online/2020/02/blog-post.html/"
            val referer = "https://rm.freex2line.online/redirectingfree/"

            val steps = listOf(
                // Step 0: Load the timer HTML directly (no network request for the page itself,
                //          bypassing Cloudflare's sec-ch-ua: "Android WebView" check entirely)
                NavigationStep.LoadHtml(html = timerHtml, baseUrl = baseUrl, referer = referer),

                // Step 1: Navigate to the watching URL once the countdown timer fires get-link.php.
                //         The request interceptor stashes the raw HTTP response (capturedMainFrameHtml)
                //         — that encrypted HTML is ALL we need; decryption happens later in the sandbox.
                // Rejects the tokenless fallback URL rather than navigating to an ad interstitial and
                // showing the user a white screen for the whole window — see isTokenisedWatchUrl.
                NavigationStep.NavigateToWatchingUrl(
                    abortOnFailure = true,
                    accept = ::isTokenisedWatchUrl
                ),

                // Step 2: Wait until the WebView is actually ON the watching page — i.e. the
                //         navigation committed and the request interceptor has fetched+stashed the
                //         raw watching-page response (capturedMainFrameHtml). That encrypted HTML is
                //         ALL we need; decryption happens later in the sandbox (<1s).
                //
                //         MUST key on location, NOT document.readyState: right after Step 1 issues
                //         loadUrl, the WebView still shows the (fully-loaded) timer page, so a
                //         readyState==='complete' check passes INSTANTLY against the stale page and
                //         the nav ends before the watching response is even fetched. The watching
                //         URL only appears in location once the response commits — which happens
                //         AFTER the interceptor captured the body — so this is a reliable barrier.
                //
                //         We deliberately do NOT poll for in-nav decryption or SweetAlert: THIS
                //         WebView carries the anti-anti-bot document.write hook, which trips the
                //         decryptor's "[native code]" self-check so it never decrypts — the previous
                //         20s DOM snapshot + 8s Swal waits always timed out (~28s wasted).
                NavigationStep.WaitForDomCondition(
                    jsCondition = "(''+window.location.href).indexOf('/watching/') > -1",
                    timeoutMs = 12000L,
                    pollIntervalMs = 250L,
                    abortOnFailure = false
                ),
            )

            val movieHost = try { java.net.URI(movieUrl).host } catch(_: Exception) { null }
            val allowedDomains = mutableSetOf(
                "cimanow.cc", "freex2line.online", "rm.freex2line.online",
                "href.li", "www.freex2line.online"
            )
            if (movieHost != null) {
                allowedDomains.add(movieHost)
            }
            val destinationLockRegexes = listOf(Regex("/(watch|watching)/"))

            Log.i(TAG_TEST, "Executing navigation engine in FULLSCREEN mode...")

            val navResult = httpService.navigationEngine.execute(
                steps = steps,
                userAgent = userAgent,
                mode = Mode.HEADLESS,
                overallTimeoutMs = 180000L,
                allowedDomains = allowedDomains,
                destinationLockPatterns = destinationLockRegexes
            )

            Log.i(TAG_TEST, "Navigation Result: success=${navResult.success}, error=${navResult.error}")
            Log.i(TAG_TEST, "Final URL: ${navResult.finalUrl}")
            Log.i(TAG_TEST, "HTML dumps: ${navResult.extractedHtml.keys.filter { it.startsWith("html") }.joinToString(", ")}")
            navResult.extractedHtml.filterKeys { it.startsWith("html") }.forEach { (key, html) ->
                Log.i(TAG_TEST, "  $key: ${html.length} chars")
            }
            Log.i(TAG_TEST, "mainFrameHtml available: ${!navResult.mainFrameHtml.isNullOrBlank()}, length: ${navResult.mainFrameHtml?.length ?: 0}")
            Log.i(TAG_TEST, "capturedVideoUrls count: ${navResult.capturedVideoUrls.size}")

            // Don't return early on failure — the interceptor may have captured
            // mainFrameHtml (server-rendered DOM pre anti-bot) and/or video URLs
            // (e.g. VK CDN) even when DOM polling times out.
            if (!navResult.success) {
                Log.w(TAG_TEST, "Isolated flow had step failures: ${navResult.error}")
                Log.w(TAG_TEST, "Continuing anyway to parse mainFrameHtml and capturedVideoUrls...")
            }

            // ======================== EXTRACT FROM CAPTURED DATA ========================
            val watchUrl = navResult.finalUrl
            // Synchronized: the server-resolution loop below appends from several coroutines at once,
            // and the straggler watchdog reads it concurrently.
            val foundLinks = java.util.Collections.synchronizedList(mutableListOf<String>())
            val loggingCallback: (ExtractorLink) -> Unit = { link ->
                foundLinks.add(link.url)
                Log.i(TAG_TEST, ">>> LINK: source=${link.source} name=${link.name} quality=${link.quality} url=${link.url.take(150)} referer=${link.referer.take(80)}")
                callback(link)
            }

            // ======================== 1. CAPTURED VIDEO URLS (from interceptor) ========================
            // The request interceptor captures video stream URLs (e.g. VK CDN, ok.ru)
            // directly from network requests. These are available even if the DOM
            // was cleared by anti-bot JS.
            // We filter using VideoUrlClassifier (same logic as the SnifferExtractor)
            // to skip segments (.ts, .m4s, .key), images, ads, DRM, and other non-video assets.
            val capturedVideoUrls = navResult.capturedVideoUrls
            val playableUrls = capturedVideoUrls.distinct().filter { url ->
                url.isNotBlank() &&
                !VideoUrlClassifier.isSegmentOrAsset(url) &&
                !VideoUrlClassifier.isBlacklisted(url) &&
                VideoUrlClassifier.isVideoUrl(url)
            }
            if (playableUrls.isNotEmpty()) {
                Log.i(TAG_TEST, "Processing ${playableUrls.size} playable video URLs from interceptor (filtered from ${capturedVideoUrls.size} total)")
                for (videoUrl in playableUrls) {
                    Log.i(TAG_TEST, ">>> CAPTURED VIDEO: ${videoUrl.take(150)}")
                    if (videoUrl.contains(".m3u8", ignoreCase = true)) {
                        M3u8Helper.generateM3u8(
                            source = "CimaNow",
                            streamUrl = videoUrl,
                            referer = watchUrl,
                            headers = mapOf("User-Agent" to httpService.userAgent)
                        ).forEach { link ->
                            Log.i(TAG_TEST, ">>> M3U8 quality: ${link.quality}p -> ${link.url.take(100)}")
                            callback(link)
                            found = true
                        }
                    } else {
                        val link = newExtractorLink("CimaNow", "CimaNow", videoUrl, type = getLinkType(videoUrl))
                        link.referer = watchUrl
                        callback(link)
                        found = true
                    }
                }
            } else {
                Log.d(TAG_TEST, "No playable video URLs from interceptor (${capturedVideoUrls.size} total captured)")
            }

            // ======================== 2. OFFLINE PARSING OF captured HTML ========================
            // Primary source: decrypted HTML captured by the document.write interceptor
            // (injected via NavigationEngine.ANTI_ANTI_BOT_JS) which stores the unmodified
            // page content in window.__decryptedHtml BEFORE the anti-bot runs.
            // Fallback source: mainFrameHtml (encrypted server HTML from HTTP interceptor).

            val mainFrameHtml = navResult.mainFrameHtml
            val rawHtmlData = navResult.extractedHtml["raw_html"] ?: ""

            // Prefer the JS snapshot if available — the document.write interceptor
            // (NavigationEngine.ANTI_ANTI_BOT_JS) captures the decrypted page HTML into
            // window.__decryptedHtml before the anti-bot can strip <li> entries.
            // Fall back to mainFrameHtml (encrypted server HTML) only if the snapshot is empty.
            val watchHtml: String = if (rawHtmlData.startsWith("RAW_HTML:")) {
                val html = rawHtmlData.removePrefix("RAW_HTML:")
                val source = if (html.contains("data-index")) "decrypted" else "DOM"
                Log.i(TAG_TEST, "Using JS snapshot: ${html.length} chars ($source, anti-bot bypassed)")
                html
            } else if (!mainFrameHtml.isNullOrBlank()) {
                Log.i(TAG_TEST, "Using captured mainFrameHtml: ${mainFrameHtml.length} chars (fallback, pre anti-bot)")
                mainFrameHtml
            } else {
                Log.w(TAG_TEST, "No HTML captured. mainFrameHtml=${mainFrameHtml != null}, rawHtmlData=${rawHtmlData.take(100)}")
                ""
            }

            data class ServerInfo(val index: String, val id: String, val name: String)

            if (watchHtml.isNotBlank()) {
                // Real server elements already present? (substring "data-index" is NOT enough —
                // see hasServerEntries.) If not, the HTML is still encrypted → try the sandbox.
                val htmlForParsing = if (!hasServerEntries(watchHtml)) {
                    Log.i(TAG_TEST, "⚙️ SANDBOX FALLBACK: watchHtml (${watchHtml.length} chars) has no <li data-index> — running through decryptViaSandbox...")

                    // Prefer a FRESH fetch of the resolved watch link over the copy captured during
                    // the navigation phase.
                    //
                    // Until now the sandbox always replayed `capturedMainFrameHtml` — bytes taken
                    // minutes earlier, before the CF solve had produced a clearance, and byte-identical
                    // across every run (4249170 chars at 19:28, 20:27, 20:49, 22:19) while the page
                    // consistently answered with the decoy. Fetching the link again means the payload
                    // is generated for the session we are actually in, with the cf_clearance and
                    // PHPSESSID we now hold, and with the blog-post page as Referer — the same
                    // conditions under which an ordinary fullscreen WebView renders the real list.
                    // Falls back to the captured copy if the refetch fails or looks wrong.
                    val freshHtml = refetchWatchPage(watchUrl, baseUrl)
                    val htmlForSandbox = if (freshHtml != null && freshHtml.length > 10_000) {
                        Log.i(TAG_TEST, "🔄 Using FRESH fetch of the watch link: ${freshHtml.length} chars " +
                            "(captured copy was ${watchHtml.length}) — delta ${freshHtml.length - watchHtml.length}")
                        freshHtml
                    } else {
                        Log.w(TAG_TEST, "Refetch unusable (len=${freshHtml?.length ?: -1}) — replaying the captured copy")
                        watchHtml
                    }

                    // baseUrl (the freex blog-post page) is exactly the Referer the watch page was
                    // navigated with — pass it so document.referrer passes the /home redirect gate.
                    val sandboxResult = decryptViaSandbox(htmlForSandbox, watchUrl.ifBlank { movieUrl }, baseUrl)
                    if (sandboxResult != null && hasServerEntries(sandboxResult)) {
                        Log.i(TAG_TEST, "✅ SANDBOX SUCCESS: decrypted HTML has real <li data-index> (${sandboxResult.length} chars)")
                        sandboxResult
                    } else {
                        // Decryption did not yield server elements — parsing the raw/rendered HTML
                        // will find 0 servers. Keep watchHtml so downstream logging is consistent,
                        // but expect NOTHING FOUND unless another source (video URLs) populated links.
                        Log.w(TAG_TEST, "⚠️ SANDBOX did not produce server elements — decryptor did not run/emit; parsing will likely find nothing")
                        watchHtml
                    }
                } else {
                    Log.i(TAG_TEST, "✅ watchHtml already contains real <li data-index> — skipping sandbox")
                    watchHtml
                }

                // Parse with Jsoup — robust across multi-line/attribute-order variation (the old
                // regex parser missed the multi-line <a>…</a> download anchors → 0 downloads).
                val doc = Jsoup.parse(htmlForParsing, watchUrl.ifBlank { movieUrl })

                // Servers: <li data-index="XX" data-id="YY">Name</li>
                val servers = doc.select("li[data-index]").mapNotNull { el ->
                    val idx = el.attr("data-index").trim()
                    val id = el.attr("data-id").trim()
                    if (idx.isBlank() && id.isBlank()) null else ServerInfo(idx, id, el.text().trim().take(50))
                }

                // Direct iframes (e.g. VK embed rendered straight into the DOM)
                val iframeUrls = doc.select("iframe[src]").map { it.attr("src") }
                    .filter { it.isNotBlank() && !it.contains("about:blank") }

                // Download links: every anchor inside #download, plus known file hosts anywhere.
                val downloads = doc.select(
                    "#download a[href], a[href*='jetload'], a[href*='forafile'], a[href*='vk.com/doc'], a[href*='frdl.my'], a[href*='bysetayico']"
                ).mapNotNull { a ->
                    val href = a.attr("href")
                    if (href.isBlank() || !href.startsWith("http")) return@mapNotNull null
                    Pair(href, a.text().trim().take(50))
                }.distinctBy { it.first }

                Log.i(TAG_TEST, "Parsed ${servers.size} servers, ${iframeUrls.size} iframes, ${downloads.size} downloads from ${if (htmlForParsing !== watchHtml) "SANDBOX-DECRYPTED" else "raw"} HTML")

                // 1. Process servers — call core.php for each to get iframe URLs
                if (servers.isNotEmpty()) {
                    Log.i(TAG_TEST, "Found ${servers.size} servers from raw HTML")
                    val cookieString = navResult.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    val coreHeaders = mutableMapOf(
                        "Referer" to watchUrl,
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                    if (cookieString.isNotBlank()) {
                        coreHeaders["Cookie"] = cookieString
                    }
                    coroutineScope {
                        val serverJobs = mutableListOf<Job>()
                        for (sv in servers) {
                            if (sv.index.isBlank() || sv.id.isBlank()) continue
                            serverJobs += launch {
                                // Hard per-server budget. This block is a barrier — the player only
                                // starts once loadLinks returns — so without a cap a single slow
                                // host (VK's embed sniff used to eat the full 120s) delays EVERY
                                // link. Whatever this server would have produced is not worth
                                // holding the other five hostage for.
                                val done = withTimeoutOrNull(SERVER_RESOLVE_TIMEOUT_MS) {
                                    try {
                                        val ajaxUrl = "https://cimanow.cc/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=${sv.index}&id=${sv.id}"
                                        Log.d(TAG_TEST, "core.php GET for server '${sv.name}': index=${sv.index} id=${sv.id}")
                                        val coreText = httpService.getText(ajaxUrl, headers = coreHeaders) ?: ""
                                        val iframeMatch = Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']").find(coreText)
                                        val iframeUrl = iframeMatch?.groupValues?.get(1)?.let {
                                            if (it.startsWith("//")) "https:$it" else it
                                        } ?: ""
                                        if (iframeUrl.isNotBlank() && iframeUrl != "123456789") {
                                            Log.i(TAG_TEST, "Server '${sv.name}' iframe: $iframeUrl")
                                            fallbackExtractIframe(iframeUrl, sv.name, watchUrl, loggingCallback)
                                            found = true
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG_TEST, "core.php failed for server '${sv.name}': ${e.message}")
                                    }
                                }
                                if (done == null) {
                                    Log.w(TAG_TEST, "⏱ Server '${sv.name}' exceeded ${SERVER_RESOLVE_TIMEOUT_MS}ms — abandoned (other servers unaffected)")
                                }
                            }
                        }

                        // Straggler watchdog. The per-server cap alone was not enough: this scope is a
                        // barrier, so the player waited for the SLOWEST server even when the others had
                        // already produced links. Measured 2026-07-29: five servers resolved in 240ms,
                        // then Upnshare burned its full 20s budget — 59% of a 33.7s loadLinks spent on
                        // a server that yielded nothing. Once a playable link exists, the remaining
                        // servers get a short grace period and are then cancelled; an extra source is
                        // not worth 20s of black screen, and a cancelled server costs nothing because
                        // the sniffer takes over when an extractor yields no links.
                        val watchdog = launch {
                            while (foundLinks.isEmpty()) delay(100)
                            delay(STRAGGLER_GRACE_MS)
                            val alive = serverJobs.count { it.isActive }
                            if (alive > 0) {
                                Log.w(TAG_TEST, "⏭ ${foundLinks.size} link(s) ready — cancelling $alive still-resolving server(s) after ${STRAGGLER_GRACE_MS}ms grace")
                                serverJobs.forEach { it.cancel() }
                            }
                        }
                        serverJobs.joinAll()
                        watchdog.cancel()
                    }
                }

                // 2. Process download links — ONLY as a fallback when there are no watch links
                //    (no servers and no direct embed iframes). Watch/stream links are preferred;
                //    the download list is passed to the player only when nothing else is available.
                val haveWatchLinks = servers.isNotEmpty() || iframeUrls.isNotEmpty()
                if (!haveWatchLinks && downloads.isNotEmpty()) {
                    Log.i(TAG_TEST, "No watch links — falling back to ${downloads.size} download links")
                    for ((dlUrl, name) in downloads) {
                        val quality = Regex("""\d+p""").find(name)?.value?.let { getQualityFromName(it) } ?: Qualities.Unknown.value
                        try {
                            when {
                                dlUrl.contains("jetload.pp.ua", true) -> handleJetload(dlUrl, quality, watchUrl, loggingCallback)
                                dlUrl.contains("forafile.com", true) -> handleForafile(dlUrl, quality, watchUrl, loggingCallback)
                                else -> {
                                    val extractorLink = newExtractorLink("CimaNow", name.ifBlank { "CimaNow" }, dlUrl, type = getLinkType(dlUrl))
                                    extractorLink.referer = watchUrl
                                    extractorLink.quality = quality
                                    loggingCallback(extractorLink)
                                }
                            }
                            found = true
                        } catch (e: Exception) {
                            Log.e(TAG_TEST, "Error processing download link: ${e.message}")
                        }
                    }
                }

                // 3. Process direct iframes (e.g. VK embed)
                if (iframeUrls.isNotEmpty()) {
                    Log.i(TAG_TEST, "Found ${iframeUrls.size} direct iframes from raw HTML")
                    coroutineScope {
                        iframeUrls.map { url ->
                            async {
                                try { fallbackExtractIframe(url, "direct_embed", watchUrl, loggingCallback) }
                                catch (e: Exception) { Log.e(TAG_TEST, "fallbackExtractIframe failed for $url: ${e.message}") }
                            }
                        }.awaitAll()
                    }
                    found = true
                }
            }
            if (found) {
                Log.i(TAG_TEST, "=== ALL WATCH LINKS (${foundLinks.size}) ===")
                foundLinks.forEachIndexed { i, url -> Log.i(TAG_TEST, "  [$i] $url") }
            } else {
                Log.w(TAG_TEST, "========== NOTHING FOUND ==========")
            }
            Log.i(TAG_TEST, "========== [END] Isolated WebView Test Flow, found=$found ==========")
            return found
        } catch (e: Exception) {
            // Includes TimeoutCancellationException from any inner withTimeout. Links already
            // pushed through `callback` are live in the player, so report what we actually got.
            Log.e(TAG_TEST, "Exception in isolated test flow: ${e.message} (found=$found so far)")
        }
        return found
    }

}

