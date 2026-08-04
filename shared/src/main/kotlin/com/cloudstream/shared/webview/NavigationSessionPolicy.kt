package com.cloudstream.shared.webview

/**
 * The provider's say in session-level decisions [NavigationEngine] deliberately does not make.
 *
 * The engine intercepts requests and answers them itself — that is the whole reason pages render at
 * all (real-Chrome `sec-ch-ua` instead of `"Android WebView"`). But answering a request means the
 * engine, not Chromium, now holds the response, and two things that a browser would have done
 * automatically become policy questions:
 *
 *  - **What happens to `Set-Cookie`?** Chromium's jar never sees a response the engine answered, so
 *    a session the site tried to establish is silently dropped.
 *  - **What happens to a challenge?** When the engine's own re-issue is refused, it hands the request
 *    back to Chromium, which retries it with the WebView fingerprint the interception existed to
 *    avoid — so a challenge degrades into the one request shape known not to work.
 *
 * Neither has a single right answer. Writing every `Set-Cookie` into the global CookieManager is a
 * real side effect on a shared store, and a provider whose embed hosts issue tracking cookies may
 * not want them; solving a challenge mid-session means a second WebView over the first. So the
 * engine's default is to do neither — exactly what it did before this interface existed — and a
 * provider that needs more supplies it here.
 *
 * Implementations live with their provider, not in shared. See `CimaNowNavigationPolicy`.
 */
interface NavigationSessionPolicy {

    /**
     * Called for every `Set-Cookie` on a response the engine answered instead of Chromium.
     *
     * Runs on the WebView's background request thread, in the middle of building a response, so it
     * must be quick and must not block. It is called before the body is handed to the WebView, which
     * is what makes it useful: cookies are in place before the page's own subresources go out.
     *
     * @param url the request URL the cookies came from — the scope they belong to
     * @param setCookieHeaders raw header values, unparsed (`"name=v; Path=/; Secure"`)
     * @param isMainFrame true for the document request, false for a subresource
     */
    fun onInterceptedResponseCookies(
        url: String,
        setCookieHeaders: List<String>,
        isMainFrame: Boolean
    ) {}

    /**
     * Last resort for a subresource the engine cannot fetch itself: return its bytes, or null.
     *
     * Called only after the engine's own re-issue has been refused **twice** — once normally and once
     * with nothing but a browser's minimum headers. At that point the refusal is not about headers:
     * measured on-device 2026-08-04, cimanow returns 200 to curl for the identical URL under every
     * header combination the engine sends, and 403 to Android's `HttpURLConnection` every time. It is a
     * transport fingerprint, and no header rearranging fixes it.
     *
     * Why it matters enough to have a hook: falling through is not neutral. Chromium then fetches the
     * asset itself and cimanow serves `.js`/`.css` as `text/html`, so strict MIME checking refuses it —
     * which is how jQuery went missing, leaving the watch page unstyled and its lazy-loaded player
     * needing a manual scroll to appear.
     *
     * Implementations are expected to be **blocking and cached**: this runs on the WebView's request
     * thread, in the middle of a page load, and the same asset must not be fetched twice.
     *
     * @return body and the Content-Type to serve it as, or null to let Chromium try.
     */
    fun fetchRefusedSubresource(url: String, referer: String?): Pair<String, String>? = null

    companion object {
        /** Do nothing — the behaviour every caller had before this interface existed. */
        val None: NavigationSessionPolicy = object : NavigationSessionPolicy {}
    }
}

/**
 * A request the engine re-issued and was refused on.
 *
 * Recorded rather than acted on. The engine cannot solve a challenge without opening a second
 * WebView over the one already on screen, and it has no idea whether the caller would rather retry,
 * fall back, or give up — so it reports what happened and lets the provider decide once the session
 * is closed and WebView lifecycles are no longer overlapping.
 *
 * A provider that ignores [NavigationResult.interceptChallenges] gets exactly the old behaviour: the
 * request falls through to Chromium.
 */
data class InterceptChallenge(
    val url: String,
    val statusCode: Int,
    /** The response carried Cloudflare's challenge markers, not merely a non-200 status. */
    val isCloudflare: Boolean,
    val isMainFrame: Boolean,
    /** First bytes of the refusal body, for diagnosis. Bounded — never the whole page. */
    val bodyPreview: String
)
