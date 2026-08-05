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
     * with nothing but a browser's minimum headers. At that point the refusal is not about headers, so an
     * implementation's only useful move is a different client entirely.
     *
     * Implementations are expected to be **blocking and cached**: this runs on the WebView's request
     * thread, in the middle of a page load, and the same asset must not be fetched twice.
     *
     * Two corrections from 2026-08-05, both of which cost real time to re-derive:
     *  - Falling through is **neutral**, not harmful. The older comment claimed cimanow "serves
     *    `.js`/`.css` as `text/html`" so that dropping to Chromium guaranteed a MIME refusal. It does not:
     *    over an accepted transport it answers `text/javascript` and `text/css` correctly, and the
     *    `text/html` Chromium refuses is the body of the 403 block page. Chromium's stack is in fact the
     *    *best* client available here, so handing the request back is a reasonable outcome, not a loss.
     *  - It is **not** a transport fingerprint. An in-page `fetch()` from a real WebView on the site's own
     *    origin — indistinguishable from a browser — is refused too. The variable is the egress IP: from a
     *    clean address every client gets 200, from a flagged VPN range no client does. A provider hook
     *    cannot fix that, and should not be extended in the hope that it can.
     *
     * @return body and the Content-Type to serve it as, or null to let Chromium try.
     */
    fun fetchRefusedSubresource(url: String, referer: String?): Pair<String, String>? = null

    /**
     * A local copy of a subresource the provider can supply without asking the network, or null.
     *
     * Consulted **before any request is made**, which is the entire point: it cannot be refused, cannot
     * be fingerprinted and cannot be rate-limited, because nothing goes out. That makes it the only
     * mechanism that still works once a site has decided to block the address rather than the client.
     *
     * Distinct from [fetchRefusedSubresource] in both timing and cost. That one is a last-resort *fetch*
     * after two refusals; this one is a lookup on the happy path and must stay that way — it runs on the
     * WebView's request thread with a page load waiting on it, so an implementation must never touch the
     * network and never block.
     *
     * Answering here is invisible to the page: `document.styleSheets` ends up exactly as it would in a
     * browser that had been allowed to download the file. It is the *refusal* that is the anomaly — a
     * page throwing MIME errors and `ReferenceError`s looks far less like a browser than one that got
     * its stylesheet.
     *
     * @return body and the Content-Type to serve it as, or null to let the normal path continue.
     */
    fun provideLocalSubresource(url: String): Pair<String, String>? = null

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
