package com.cloudstream.shared.webview

/**
 * Shared types for WebView engines.
 *
 * Contains the common sealed classes and data types used by both
 * [CfBypassEngine] and [VideoSnifferEngine].
 */

/**
 * Operating mode for WebView engines.
 */
enum class Mode {
    HEADLESS,    // No UI, runs in background
    FULLSCREEN   // User-visible dialog for CAPTCHA or video sniffing
}

/**
 * Exit conditions for WebView sessions.
 */
sealed class ExitCondition {
    /** Exit when page loads without CF challenge */
    object PageLoaded : ExitCondition()

    /** Exit when specific cookies are present */
    data class CookiesPresent(val keys: List<String>) : ExitCondition()

    /** Exit when video URLs are found */
    data class VideoFound(val minCount: Int = 1) : ExitCondition()

    /** Exit when a CSS selector matches at least [minCount] elements */
    data class ElementsFound(val selector: String, val minCount: Int = 1) : ExitCondition()

    /** Exit when current URL matches [urlPattern] regex */
    data class UrlMatches(val urlPattern: String) : ExitCondition()

    /** Exit after a fixed delay from page load */
    data class AfterDelay(val delayMs: Long) : ExitCondition()
}

/**
 * Result of a WebView session.
 */
sealed class WebViewResult {
    data class Success(
        val cookies: Map<String, String>,
        val html: String,
        val finalUrl: String,
        val foundLinks: List<CapturedLinkData> = emptyList()
    ) : WebViewResult()

    /** Video is playing in the WebView itself (DRM or unsniffable). Dialog stays open as player. */
    data class PlayingInWebView(
        val dialog: android.app.Dialog
    ) : WebViewResult()

    data class Timeout(
        val lastUrl: String,
        val partialHtml: String?
    ) : WebViewResult()

    data class Error(val reason: String) : WebViewResult()

    /** User explicitly cancelled the operation (e.g., pressed back on CF dialog). */
    data class Cancelled(val reason: String) : WebViewResult()
}

data class CapturedLinkData(
    val url: String,
    val qualityLabel: String,
    val headers: Map<String, String>
)

/**
 * A video request seen by [NavigationEngine]'s interceptor, kept with the headers the page sent and
 * the document that sent them.
 */
data class CapturedVideoRequest(
    val url: String,
    val headers: Map<String, String>,
    /** The page the request came from — the natural Referer if the request itself carried none. */
    val pageUrl: String
)

/**
 * A third-party **iframe document** seen by [NavigationEngine]'s interceptor — i.e. a player embed.
 *
 * This is the more valuable capture of the two. A sniffed stream is whatever the embed's ABR happened
 * to fetch in the moment (on VK, the bottom rung), and its CDN URL is signed per rendition so no
 * higher quality can be derived from it. The embed URL, handed to the matching extractor, yields the
 * whole quality ladder from the player's own parameters — an HLS master, or one progressive URL per
 * quality.
 */
data class CapturedEmbedRequest(
    val url: String,
    val headers: Map<String, String>,
    /** The document that hosted the iframe — the Referer the embed expects. */
    val pageUrl: String,
    /**
     * The embed page's own HTML, captured **as the iframe loaded it** — null if it could not be read.
     *
     * This is the difference between working and not working, not an optimisation. Asking the embed
     * host for the same page a second time fails two ways at once (measured 2026-07-30):
     *  - `vkvideo.ru/video_ext.php` rate-limits a repeat caller, and the retry stalls until timeout
     *    (10 s) instead of erroring.
     *  - Loaded as a **top-level document** rather than an iframe, VK serves its error page
     *    (`video_embed_error`) regardless — the endpoint is embed-only and a top-level navigation
     *    cannot pretend otherwise.
     * The bytes the page itself received have neither problem, and they contain the player params
     * (`"hls"`, `"url1080"`) the extractor was going to re-fetch anyway.
     */
    val html: String? = null
)

/**
 * A single step in a multi-step WebView navigation flow.
 */
sealed class NavigationStep {
    data class LoadUrl(
        val url: String,
        val referer: String? = null,
        val extraHeaders: Map<String, String> = emptyMap()
    ) : NavigationStep()

    /** Load raw HTML string into the WebView with a base URL (for resolving relative URLs) */
    data class LoadHtml(
        val html: String,
        val baseUrl: String,
        val referer: String? = null
    ) : NavigationStep()

    data class ClickElement(
        val selector: String,
        val timeoutMs: Long = 10_000L,
        val abortOnFailure: Boolean = true
    ) : NavigationStep()

    data class ClickCoordinates(
        val x: Float,
        val y: Float
    ) : NavigationStep()

    data class ExecuteJs(
        val javascript: String,
        /** If non-blank, the JS return value is stored in NavigationResult.extractedHtml[key] */
        val key: String = ""
    ) : NavigationStep()

    data class WaitForSelector(
        val selector: String,
        val timeoutMs: Long = 10_000L,
        val abortOnFailure: Boolean = true
    ) : NavigationStep()

    data class WaitForUrl(
        val urlPattern: String,
        val timeoutMs: Long = 15_000L,
        val abortOnFailure: Boolean = true
    ) : NavigationStep()

    data class WaitForDelay(
        val delayMs: Long
    ) : NavigationStep()

    data class WaitForDomCondition(
        val jsCondition: String,
        val timeoutMs: Long = 15_000L,
        val pollIntervalMs: Long = 500L,
        val abortOnFailure: Boolean = true
    ) : NavigationStep()

    /**
     * Hold a **visible** session open until the main frame lands on a URL matching [urlPattern],
     * because the page will only go there when the user acts.
     *
     * For a page whose next step is a real click, not a redirect. CimaNow's countdown page fills in
     * `#downloadbtn.href` when the timer expires and then *waits* — nothing navigates on its own, so
     * there is no redirect to follow and no response to read. Previously the URL was lifted out of the
     * `get-link.php` response by the request interceptor, which stopped working the moment the site
     * moved that endpoint from a GET with query parameters to a `multipart/form-data` POST: a POST body
     * cannot be forwarded through `shouldInterceptRequest`, so re-issuing it produced a tokenless
     * answer, and declining to intercept it means never seeing the answer at all (2026-08-03, both
     * failure modes observed in one afternoon).
     *
     * Waiting for the user's tap needs neither. The navigation the button triggers carries the page's
     * own `Referer` — which is exactly the one the destination demands — and the URL it goes to is the
     * tokenised one, whatever the site's current scheme for minting it. Nothing is read out of the
     * page, nothing is re-issued, and a change to the token format cannot break it.
     *
     * Main-frame navigations are auto-approved while this step runs, so a mis-tap on an ad will be
     * followed too; it logs every URL it sees and keeps waiting for one that matches.
     */
    data class AwaitMainFrameUrl(
        /** Matched against each main-frame URL as it starts loading. */
        val urlPattern: Regex,
        /** Ends the step unsuccessfully. Generous: a human has to read a countdown and press a button. */
        val timeoutMs: Long = 120_000L,
        val pollIntervalMs: Long = 250L,
        /**
         * A second gate on a URL that already matched [urlPattern] — for "matches, but is not usable".
         * Return false and the step keeps waiting rather than accepting it.
         */
        val accept: ((String) -> Boolean)? = null,
        val abortOnFailure: Boolean = true
    ) : NavigationStep()

    /** Navigate to the watching URL captured by the request interceptor from get-link.php */
    data class NavigateToWatchingUrl(
        val abortOnFailure: Boolean = true,
        /**
         * Checked before navigating. Return false to treat the captured URL as a failed chain.
         *
         * A URL being *present* is not the same as it being *usable*. 2026-08-03: `get-link.php`,
         * answered without its POST body, returned the bare `https://cimanow.cc/pig/watching/` with no
         * token — and the step navigated there quite happily, so the site's ad interstitial rendered
         * as a white screen for 300 s. The chain had already failed one request earlier; nothing
         * looked at what it produced.
         *
         * Null means accept anything, which is what every caller did before this existed.
         */
        val accept: ((String) -> Boolean)? = null
    ) : NavigationStep()

    /**
     * Hold the session open until the request interceptor sees a **player embed** (preferred) or a
     * playable video URL on the network.
     *
     * Built for Mode.FULLSCREEN: the user is looking at the page and clicking a server and a play
     * button, so this step is what keeps the WebView alive while that happens instead of tearing it
     * down as soon as the scripted steps run out. It also ends the moment the user dismisses the
     * dialog — otherwise closing the window would leave the caller polling until [timeoutMs].
     *
     * An embed counts as success because it is the better outcome: see [CapturedEmbedRequest].
     *
     * @param graceMs extra time to keep collecting after the first hit. A player asks for an HLS
     *   master and then its variant playlists a few hundred ms later, and those carry the per-quality
     *   tokens; exiting on the first request throws the quality ladder away.
     */
    data class WaitForCapturedVideo(
        val timeoutMs: Long = 300_000L,
        val pollIntervalMs: Long = 250L,
        val graceMs: Long = 2_500L,
        /**
         * Grace used instead of [graceMs] once an embed has been captured.
         *
         * Much shorter, because the reason for a long grace does not apply: [graceMs] exists to catch
         * the HLS variant playlists that follow a master on the *sniffed* path, and an embed makes
         * those redundant — its extractor produces the whole ladder. Measured 2026-07-30: with the
         * embed in hand at +0.15s the full 2.5s window then collected three streams that were
         * immediately discarded, a third of the time to first frame spent on nothing. Not zero, so a
         * second server the user already clicked can still land.
         */
        val embedGraceMs: Long = 600L,
        val abortOnFailure: Boolean = true
    ) : NavigationStep()

    data class ExtractHtml(
        val selector: String? = null,
        val key: String = ""
    ) : NavigationStep()

    /**
     * Atomically polls a DOM condition and, when met, captures a snapshot via snapshotJs
     * in a single evaluateJavascript call — eliminating the race window between
     * "condition met" and "read innerHTML" that anti-bot scripts exploit.
     */
    data class WaitForDomConditionAndSnapshot(
        val jsCondition: String,
        val snapshotJs: String,
        val key: String,
        val timeoutMs: Long = 15_000L,
        val pollIntervalMs: Long = 500L,
        val abortOnFailure: Boolean = true
    ) : NavigationStep()
}

/**
 * Result of a multi-step navigation flow.
 */
data class NavigationResult(
    val success: Boolean,
    val finalUrl: String,
    val cookies: Map<String, String>,
    val extractedHtml: Map<String, String>,
    val completedSteps: Int,
    val failedAtStep: Int? = null,
    val error: String? = null,
    val capturedVideoUrls: List<String> = emptyList(),
    /**
     * The same captures as [capturedVideoUrls] but with the headers the page actually sent.
     * Tokenised CDNs validate Referer/Origin (and often a cookie), so playback needs these rather
     * than values reconstructed afterwards.
     */
    val capturedVideoRequests: List<CapturedVideoRequest> = emptyList(),
    /** Third-party iframe documents seen on the wire — player embeds. See [CapturedEmbedRequest]. */
    val capturedEmbedRequests: List<CapturedEmbedRequest> = emptyList(),
    /** Raw HTML of the last intercepted main-frame response (e.g. cimanow.cc /watching/).
     *  Populated by the request interceptor. Contains the server-rendered DOM before the
     *  page's anti-bot JS can clear/patch it. */
    val mainFrameHtml: String? = null,
    /**
     * Requests the engine re-issued and was refused on — see [InterceptChallenge].
     *
     * Empty for every provider that never gets challenged, and safe to ignore: a refused
     * interception falls through to Chromium exactly as it always did. Providers that care can
     * distinguish "the site blocked us" from "the page had nothing" instead of guessing.
     */
    val interceptChallenges: List<InterceptChallenge> = emptyList()
)
