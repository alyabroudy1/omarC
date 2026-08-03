package com.cimanow

import com.lagradost.api.Log
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

    companion object {
        private const val TAG = "CimaNowPolicy"
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
