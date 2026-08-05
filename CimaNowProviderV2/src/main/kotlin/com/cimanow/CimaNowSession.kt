package com.cimanow

import com.lagradost.api.Log
import kotlinx.coroutines.launch
import com.cloudstream.shared.service.ProviderHttpService
import com.cloudstream.shared.session.SessionState
import com.cloudstream.shared.webview.InterceptChallenge
import com.cloudstream.shared.webview.NavigationSessionPolicy

/**
 * CimaNow's session policy: everything the shared engine deliberately leaves to the provider.
 *
 * This file exists so that three CimaNow-specific decisions do not become everybody's defaults. The
 * shared side gained only neutral seams — a no-op [NavigationSessionPolicy], a reported
 * [InterceptChallenge] list, and a session snapshot/restore pair — and none of them changes what any
 * other provider does. The opinions live here.
 */

/**
 * Keeps the WebView's cookie jar and the HTTP session in step when the engine answers a request
 * itself.
 *
 * The engine intercepts cimanow's main frame and re-issues it over `HttpURLConnection`, which is what
 * makes the page render at all. The cost is that Chromium never sees that response, so any
 * `Set-Cookie` on it reaches nobody: not the WebView (whose next subresource goes out without it) and
 * not [ProviderHttpService] (whose next HTTP hop goes out without it either). On a site that rotates
 * a session cookie on the watch page, that is a silent split-brain.
 *
 * Two writes, deliberately different in scope:
 *  - **CookieManager, always.** Scoped to the URL that issued the cookie, exactly where a browser
 *    would have put it. This is what the engine's own interceptor reads on the next request.
 *  - **The provider session, only for cimanow's own domain.** An embed host's tracking cookie has no
 *    business in the session that carries `cf_clearance`, and merging it there would widen what every
 *    later HTTP request advertises.
 */
class CimaNowNavigationPolicy(
    private val httpService: ProviderHttpService,
    /** Hosts whose cookies may enter the provider session. Subdomains included. */
    private val sessionHosts: Set<String> = setOf("cimanow.cc")
) : NavigationSessionPolicy {

    override fun onInterceptedResponseCookies(
        url: String,
        setCookieHeaders: List<String>,
        isMainFrame: Boolean
    ) {
        // Runs on the WebView's request thread while a response is being built — no blocking work.
        val cm = try {
            android.webkit.CookieManager.getInstance()
        } catch (e: Exception) {
            Log.w(TAG, "CookieManager unavailable: ${e.message}")
            null
        }

        val parsed = linkedMapOf<String, String>()
        for (header in setCookieHeaders) {
            // Hand the raw header to CookieManager: it understands Path/Domain/Secure/Max-Age, and
            // reproducing that parsing is how cookies end up at the wrong scope.
            try { cm?.setCookie(url, header) } catch (_: Exception) {}

            val pair = header.substringBefore(';')
            val name = pair.substringBefore('=', "").trim()
            if (name.isNotEmpty()) parsed[name] = pair.substringAfter('=', "").trim()
        }
        try { cm?.flush() } catch (_: Exception) {}

        if (parsed.isEmpty()) return

        val host = try {
            java.net.URI(url).host?.lowercase() ?: ""
        } catch (_: Exception) { "" }
        val isSessionHost = sessionHosts.any { host == it || host.endsWith(".$it") }

        if (isSessionHost) {
            httpService.mergeSessionCookies(parsed)
            Log.i(TAG, "Merged ${parsed.size} cookie(s) from an intercepted response into the " +
                "session | host=$host mainFrame=$isMainFrame names=${parsed.keys.joinToString(",")}")
        } else {
            Log.d(TAG, "Kept ${parsed.size} cookie(s) in CookieManager only (third-party host " +
                "$host) | names=${parsed.keys.joinToString(",")}")
        }
    }

    /**
     * Serves an asset from [CimaNowAssetCache] — bytes already fetched over a transport cimanow accepts.
     *
     * Consulted before the engine attempts anything, so a warm cache means the page's `<link>` resolves
     * with no request at all. Only the stylesheet is ever cached (see [CimaNowAssetCache.warm]); a `.js`
     * lookup falls straight through, which is deliberate — the watch page's scripts stay on exactly the
     * path that currently produces working streams.
     */
    override fun provideCachedSubresource(url: String): Pair<String, String>? {
        val path = try {
            java.net.URI(url).path?.lowercase() ?: ""
        } catch (_: Exception) { "" }
        if (!path.endsWith(".css")) return null
        val body = CimaNowAssetCache.get(url) ?: return null
        return body to "text/css"
    }

    /**
     * Last resort for a `.js` the engine could not fetch: try OkHttp, a transport it has not used yet.
     *
     * Reached only after the engine's own re-issue has been refused twice. On current evidence it will
     * not succeed either — see [CimaNowAssetCache] for the 2026-08-05 measurements showing that *every*
     * transport this app owns gets cimanow's 403 page while any real-Chromium request gets 200 — but it
     * is cheap, it is the only thing standing between a refusal and a bare fall-through, and it costs
     * nothing on a healthy asset.
     *
     * Two claims that used to live in this comment were wrong and are recorded here so they are not
     * re-derived:
     *  - *"curl gets 200 under every header combination."* Not any more. Curl is refused on every cimanow
     *    path, the homepage included. What gets 200 is a real Chromium network stack, nothing else.
     *  - *"cimanow mislabels `.js`/`.css` as `text/html`."* It does not. Over an accepted transport it
     *    answers `text/javascript` and `text/css` correctly. The `text/html` Chromium refuses is the body
     *    of the 403 block page (`<title>ستوب! المخرج عايز كدة</title>`), not a mislabelled asset.
     *
     * Cached because this blocks a page load: the same files are requested on every watch page.
     */
    override fun fetchRefusedSubresource(url: String, referer: String?): Pair<String, String>? {
        val path = try {
            java.net.URI(url).path?.lowercase() ?: ""
        } catch (_: Exception) { "" }
        val mime = when {
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".json") -> "application/json"
            else -> return null
        }

        assetCache[url]?.let {
            Log.i(TAG, "🗃 Asset served from cache (${it.length} chars): ${url.takeLast(60)}")
            return it to mime
        }

        // OkHttp, not ChromiumFetcher.
        //
        // `fetchViaChromeTls` looked like the obvious answer — the log proves Chromium's stack gets
        // through where ours does not — but it cannot return a script. It **navigates a WebView to the
        // URL and scrapes `document.documentElement.outerHTML`**, so a `.js` file comes back wrapped and
        // HTML-escaped inside `<html><body><pre>…`. Fine for an HTML page, useless for an asset.
        //
        // OkHttp is byte-accurate, streams, and is a genuinely different transport from the
        // `HttpURLConnection` the engine already tried twice — which is the only variable left, since
        // every header combination the engine sends returns 200 to curl on the same device.
        val body = try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(ASSET_FETCH_TIMEOUT_MS) {
                    val res = httpService.getRaw(
                        url,
                        headers = buildMap {
                            referer?.let { put("Referer", it) }
                            put("Accept", "*/*")
                        }
                    )
                    val code = res.code
                    val text = res.body?.string()
                    res.close()
                    if (code != 200) {
                        Log.w(TAG, "OkHttp asset fetch got HTTP $code for ${url.takeLast(60)}")
                        null
                    } else text
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OkHttp asset fetch threw for ${url.takeLast(60)}: ${e.message}")
            null
        }

        if (body.isNullOrEmpty()) {
            Log.w(TAG, "❌ OkHttp asset fetch returned nothing for ${url.takeLast(60)} — letting " +
                "Chromium try, which is the stack that actually works on this site")
            return null
        }
        // A block page would be HTML, and serving that as JavaScript is worse than serving nothing:
        // the parser would choke instead of the loader simply failing. This guard is what stopped the
        // first version of this method — which used ChromiumFetcher — from serving `<pre>`-wrapped
        // markup as a script.
        if (body.trimStart().startsWith("<", ignoreCase = true)) {
            Log.w(TAG, "❌ OkHttp returned HTML for an asset (${body.length} chars) — that is the block " +
                "page, and serving markup as $mime is worse than serving nothing: ${url.takeLast(60)}")
            return null
        }
        assetCache[url] = body
        Log.i(TAG, "✅ OkHttp fetched the refused asset (${body.length} chars) as $mime: " +
            url.takeLast(60))
        return body to mime
    }

    companion object {
        private const val TAG = "CimaNowPolicy"

        /**
         * Ceiling for one asset fetch. This blocks a page render, so it is deliberately tight — an
         * unstyled page beats a stalled one.
         */
        private const val ASSET_FETCH_TIMEOUT_MS = 6_000L

        /**
         * Fetched assets, kept for the process. The same jQuery/carousel/animate trio is requested by
         * every watch page, and re-fetching them per title would multiply the cost by every play.
         */
        private val assetCache = java.util.Collections.synchronizedMap(HashMap<String, String>())
    }
}

/**
 * The theme stylesheet, fetched once through the only transport cimanow accepts and kept on disk.
 *
 * ## Why a cache and not a fetch
 *
 * The watch page renders unstyled because exactly one file is missing:
 * `…/Assets/css/animate.css` — misleadingly named, it is the theme's *entire* stylesheet, 72 KB
 * including the `@font-face` rules for "Cima Now Bold". (The 30 KB of inline `<style>` in the document
 * does arrive, which is why the page is laid out but bare.)
 *
 * Measured 2026-08-05, the same URL over five clients:
 *
 * | client                              | result                        |
 * |-------------------------------------|-------------------------------|
 * | real Chromium network stack         | `200 text/css` (HIT and MISS) |
 * | Android `HttpURLConnection`         | `403` block page              |
 * | OkHttp                              | `403` block page              |
 * | desktop curl                        | `403` block page              |
 * | Playwright's Node client            | `403` block page              |
 *
 * Invariant under UA (device, desktop, none), cookies, `Referer`, referrer-policy, `Sec-Fetch-*`, and
 * `<link>` vs `fetch()`. Only the transport moves it. So the bytes have to come from a Chromium stack,
 * and [ChromiumFetcher.fetchSameOriginText] is how: a cheap document on cimanow's origin, then the
 * page's own `fetch()`.
 *
 * ## Why warming is off the critical path
 *
 * Nothing here runs while the watch page is loading. [warm] is called after the surf has already
 * produced its links, in a *separate* WebView — the watch page is never touched, never has JS injected,
 * and never waits on us. The consequence is honest and worth stating: **the first ever play of a title
 * is unstyled**, and every play after it is styled, on any title, until the site bumps `?v=`. Keying on
 * the full URL is what makes that self-updating — a new `?v=` is simply a new key with no entry.
 */
object CimaNowAssetCache {
    private const val TAG = "CimaNowAssets"

    /** Served from here on the request thread. A disk read happens at most once per URL per process. */
    private val memory = java.util.Collections.synchronizedMap(HashMap<String, String>())

    /** URLs already looked for and not found, so a cold asset costs one disk stat, not one per request. */
    private val absent = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Bytes for [url], or null. Safe to call on the WebView's request thread: memory first, then at most
     * one disk read, then a negative marker so the miss is never paid twice.
     */
    fun get(url: String): String? {
        memory[url]?.let { return it }
        if (absent.contains(url)) return null
        val file = fileFor(url)
        if (file == null || !file.isFile || file.length() == 0L) {
            absent.add(url)
            return null
        }
        return try {
            val body = file.readText()
            if (body.isEmpty()) {
                absent.add(url)
                null
            } else {
                memory[url] = body
                Log.i(TAG, "Loaded ${body.length} chars from disk for ${url.takeLast(60)}")
                body
            }
        } catch (e: Exception) {
            Log.w(TAG, "Disk read failed for ${url.takeLast(60)}: ${e.message}")
            absent.add(url)
            null
        }
    }

    /**
     * Fetches and stores any of [assetUrls] not already held. Returns how many were newly cached.
     *
     * Only stylesheets are considered. Scripts are deliberately excluded: substituting one would change
     * what runs on the watch page, and the extraction works as it stands.
     *
     * Never throws and never blocks anything that matters — call it fire-and-forget after the links are
     * already in hand.
     */
    suspend fun warm(
        assetUrls: List<String>,
        fetcher: com.cloudstream.shared.network.ChromiumFetcher,
        userAgent: String?,
        fallbackOriginUrl: String? = null
    ): Int {
        val wanted = assetUrls
            .filter { it.substringBefore('?').endsWith(".css", ignoreCase = true) }
            .distinct()
            .filter { get(it) == null }
        if (wanted.isEmpty()) return 0

        // Two origins, cheapest first. `/robots.txt` is the smallest same-origin document cimanow has and
        // a text/plain viewer is still a real document with a working `fetch()` — but it is unverified,
        // so a page known to load is kept behind it. The movie page is heavy and *proven*: an in-page
        // fetch from it is the measurement this whole mechanism is built on.
        val origins = buildList {
            try {
                val u = java.net.URI(wanted.first())
                add("${u.scheme}://${u.host}/robots.txt")
            } catch (e: Exception) {
                Log.w(TAG, "Cannot derive an origin from ${wanted.first().take(60)}: ${e.message}")
            }
            fallbackOriginUrl?.let { add(it) }
        }
        if (origins.isEmpty()) return 0

        var fetched: Map<String, String> = emptyMap()
        for (origin in origins) {
            Log.i(TAG, "🔥 Warming ${wanted.size} stylesheet(s) via the Chromium stack, " +
                "origin=${origin.take(90)}")
            fetched = try {
                fetcher.fetchSameOriginText(
                    originUrl = origin,
                    assetUrls = wanted,
                    headers = buildMap { userAgent?.let { put("User-Agent", it) } },
                    // A 200 is not proof: a block page, an interstitial or a MITM proxy will all answer
                    // 200 with HTML for a .css URL. Observed 2026-08-05 — a proxy returned 563 KB of
                    // `<!DOCTYPE html>` as a 200 for this exact stylesheet.
                    requireContentTypeContains = "css"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Warm via ${origin.take(60)} failed: ${e.message}")
                emptyMap()
            }
            if (fetched.isNotEmpty()) break
            Log.w(TAG, "Nothing came back via ${origin.take(60)}" +
                if (origin != origins.last()) " — trying the next origin" else "")
        }
        if (fetched.isEmpty()) return 0

        var stored = 0
        for ((url, body) in fetched) {
            // Belt and braces alongside the Content-Type gate. Caching markup as CSS would render as
            // nothing forever, and with the request gone there would be no signal left to explain it.
            if (body.trimStart().startsWith("<")) {
                Log.w(TAG, "Refusing to cache markup as CSS (${body.length} chars): ${url.takeLast(60)}")
                continue
            }
            memory[url] = body
            absent.remove(url)
            stored++
            val file = fileFor(url)
            if (file == null) continue
            try {
                file.parentFile?.mkdirs()
                file.writeText(body)
                Log.i(TAG, "💾 Cached ${body.length} chars → ${file.name} for ${url.takeLast(60)}")
            } catch (e: Exception) {
                Log.w(TAG, "Disk write failed for ${url.takeLast(60)}: ${e.message}")
            }
        }
        Log.i(TAG, "🔥 Warm done: $stored/${wanted.size} stylesheet(s) now cached")
        return stored
    }

    /**
     * Fire-and-forget [warm]. Returns immediately; nothing in the play path waits on the result.
     *
     * Deliberately not tied to the caller's scope — `loadLinks` is finishing when this is called and
     * cancelling the warm along with it would mean the cache never fills. At most one warm runs at a
     * time, and a fully warm cache costs a list filter and returns without launching anything.
     */
    fun warmAsync(
        assetUrls: List<String>,
        fetcher: com.cloudstream.shared.network.ChromiumFetcher,
        userAgent: String?,
        fallbackOriginUrl: String? = null
    ) {
        if (warming) return
        val wanted = assetUrls.filter {
            it.substringBefore('?').endsWith(".css", ignoreCase = true) && get(it) == null
        }
        if (wanted.isEmpty()) return
        warming = true
        scope.launch {
            try {
                warm(wanted, fetcher, userAgent, fallbackOriginUrl)
            } catch (e: Exception) {
                Log.w(TAG, "Async warm threw: ${e.message}")
            } finally {
                warming = false
            }
        }
    }

    @Volatile
    private var warming = false

    /** Main dispatcher: [ChromiumFetcher] requires it, and the work inside suspends rather than blocks. */
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )

    private fun fileFor(url: String): java.io.File? {
        val ctx = com.cloudstream.shared.android.PluginContext.context ?: return null
        return try {
            java.io.File(ctx.cacheDir, "cimanow_asset_${hash(url)}.css")
        } catch (_: Exception) { null }
    }

    /** Full URL in, stable filename out — the `?v=` is part of the identity, so a bump misses cleanly. */
    private fun hash(url: String): String = try {
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        url.hashCode().toUInt().toString(16)
    }
}

/**
 * Is this a watch URL worth navigating to, or the bare fallback `get-link.php` hands out when it was
 * asked without its form data?
 *
 * 2026-08-03: the chain captured `https://cimanow.cc/pig/watching/` — 32 characters, no query at all —
 * and the surf navigated there, where cimanow answered with a `<title>Redirect</title>` ad
 * interstitial that rendered as a white screen for 300 s. The working flow's URL carries a token
 * (`/watching/?token=…`, see the HAR in the handover).
 *
 * Deliberately a test for *a query string*, not for the literal `token=`: the parameter name is the
 * site's to change, while "the URL identifies a session" is the actual requirement. A bare path is
 * unambiguously the fallback.
 */
fun isTokenisedWatchUrl(url: String): Boolean {
    val query = url.substringAfter('?', "").substringBefore('#')
    return query.isNotBlank()
}

/**
 * Was this navigation refused by Cloudflare on cimanow's own domain?
 *
 * The distinction that matters: a challenge on the main frame means we never got the page, which is
 * worth another attempt after a solve. A refused ad-network subresource means nothing — those fail
 * constantly and always have.
 */
fun List<InterceptChallenge>.cimaCloudflareBlock(): InterceptChallenge? =
    firstOrNull { challenge ->
        challenge.isCloudflare && challenge.url.contains("cimanow", ignoreCase = true)
    } ?: firstOrNull { challenge ->
        // A main-frame 403 on cimanow with no recognisable CF markers is still a block, and still
        // ours to react to — the markers move, the outcome does not.
        challenge.isMainFrame &&
            challenge.statusCode == 403 &&
            challenge.url.contains("cimanow", ignoreCase = true)
    }

/**
 * Fetches [url] through the session path purely to get a Cloudflare challenge solved, and reports
 * whether we actually came out the other side with a usable session.
 *
 * `getDocument` returning non-null is **not** that test: it parses whatever HTML it got, so a
 * Cloudflare block page comes back as a perfectly valid `Document` and a failed solve reads as
 * success. Two things have to be true — the body is not itself a challenge, and the session now holds
 * a clearance (or never needed one, on a site that answers without a challenge).
 *
 * Guarded, because this is exactly the call that can wipe the session it was meant to repair.
 */
suspend fun reestablishSession(
    httpService: ProviderHttpService,
    url: String,
    tag: String
): Boolean = withSessionGuard(httpService, tag) {
    val doc = httpService.getDocument(url, rewriteDomain = true)
    if (doc == null) {
        Log.w(tag, "Re-establish: no response at all for ${url.take(80)}")
        return@withSessionGuard false
    }
    val html = doc.outerHtml()
    if (com.cloudstream.shared.cloudflare.CloudflareDetector.isCloudflareChallenge(html, 403)) {
        Log.w(tag, "Re-establish: still a Cloudflare challenge after the solve " +
            "(${html.length} chars) — the clearance was refused or the solve never ran")
        return@withSessionGuard false
    }
    val cookies = httpService.snapshotSession().cookies
    Log.i(tag, "Re-establish: got a clean page (${html.length} chars) | " +
        "cookies=${cookies.size} hasClearance=" +
        cookies.keys.any { it.equals("cf_clearance", ignoreCase = true) })
    true
}

/**
 * Runs [block] without letting a cancelled Cloudflare solve leave the session worse than it started.
 *
 * `solveCloudflareThenRequest` invalidates the session and clears the system cookies *before* opening
 * its dialog, and returns a plain failure when the user presses back. Nothing puts the old cookies
 * back — so a flow that merely *might* have needed a solve can end up with no `cf_clearance` at all,
 * and the surf that follows is then guaranteed to fail for a reason that has nothing to do with the
 * surf. This restores the snapshot when the session came out strictly poorer.
 *
 * Never rolls back a successful solve: a fresh clearance replaces the snapshot's, so the check is for
 * cookies having been *lost*, not merely changed.
 */
suspend fun <T> withSessionGuard(
    httpService: ProviderHttpService,
    tag: String,
    block: suspend () -> T
): T {
    val before: SessionState = httpService.snapshotSession()
    val hadClearance = before.cookies.keys.any { it.equals("cf_clearance", ignoreCase = true) }
    try {
        return block()
    } finally {
        val after = httpService.snapshotSession()
        val hasClearance = after.cookies.keys.any { it.equals("cf_clearance", ignoreCase = true) }
        val lostClearance = hadClearance && !hasClearance
        val lostEverything = before.cookies.isNotEmpty() && after.cookies.isEmpty()
        if (lostClearance || lostEverything) {
            Log.w(tag, "⚠️ Session came out poorer than it went in " +
                "(cookies ${before.cookies.size}→${after.cookies.size}, " +
                "clearance $hadClearance→$hasClearance) — a CF solve was almost certainly " +
                "cancelled or failed after clearing the old session. Restoring it.")
            httpService.restoreSession(before, "CF solve cleared the session without replacing it")
        }
    }
}
