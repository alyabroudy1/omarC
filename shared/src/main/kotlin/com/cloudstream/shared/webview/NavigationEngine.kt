package com.cloudstream.shared.webview

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.*
import com.cloudstream.shared.logging.ProviderLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.Charset

class NavigationEngine(
    private val activityProvider: () -> android.app.Activity?
) {
    private val sessionMutex = Mutex()

    /**
     * URL of the document currently loaded, tracked from `onPageStarted`/`onPageFinished`.
     *
     * `shouldInterceptRequest` runs on a Chromium worker thread, where touching `WebView.url` is
     * illegal (all WebView methods are UI-thread only), so the interceptor reads this instead.
     */
    @Volatile
    private var lastPageUrl: String = ""

    /**
     * Blank windows handed to the page when it calls `window.open` — see `onCreateWindow`. Kept alive
     * for the session so the page's reference stays valid (`win.closed === false`), then destroyed.
     */
    private val popupSinks = java.util.Collections.synchronizedList(mutableListOf<WebView>())

    /** Cursor overlay for Mode.FULLSCREEN, so a D-pad remote can click the page. */
    private var tvMouseController: com.cloudstream.shared.ui.TvMouseController? = null

    /** Set by the request interceptor when get-link.php returns the watching URL */
    @Volatile
    var interceptedWatchingUrl: String? = null

    /** Set when a redirect is pending user approval via the confirmation dialog */
    @Volatile
    var pendingRedirectUrl: String? = null

    /**
     * The raw HTML body of the last intercepted main-frame request (cimanow.cc /watching/).
     * Populated by shouldInterceptRequest when it intercepts a main-frame text/html response.
     * Consumed after execute() completes to parse server data directly from the HTTP response
     * bytes, bypassing the WebView's JS environment entirely.
     */
    @Volatile
    var capturedMainFrameHtml: String? = null

    /**
     * Subresource requests seen since the last main-frame body was served.
     *
     * The JS-free stand-in for "did the page's own scripts run?", which used to be answered by
     * reading `document.body.innerHTML.length` out of the page — see [onPageFinished]'s state log.
     * A page that really executed pulls scripts, styles, fonts and images; a page that took a
     * bail-out path pulls next to nothing. Reset on each main-frame interception so the number
     * always describes the current document.
     */
    @Volatile
    private var subresourceRequestsSinceMainFrame: Int = 0

    var autoApproveAllRedirects: Boolean = false

    /** Last baseUrl used by a LoadHtml step — used as the Referer when navigating
     *  to the watching URL (the real browser sends the timer-page referer). */
    var lastHtmlBaseUrl: String? = null

    /** Actual playable video stream URLs captured from network traffic (e.g. VK CDN
     *  vkuser.net) while the WebView plays the embedded video. Returned as a bonus
     *  alongside the extracted HTML so the provider can use them directly. */
    val capturedVideoUrls = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** The same captures with the headers the page sent — see [CapturedVideoRequest]. */
    val capturedVideoRequests = java.util.concurrent.CopyOnWriteArrayList<CapturedVideoRequest>()

    /**
     * Player embeds seen on the wire — third-party iframe documents. See [CapturedEmbedRequest] for
     * why these are worth more than the streams.
     */
    val capturedEmbedRequests = java.util.concurrent.CopyOnWriteArrayList<CapturedEmbedRequest>()

    /**
     * Requests the engine re-issued and was refused on — see [InterceptChallenge].
     *
     * Reported, never acted on here: solving a challenge would mean a second WebView over the one on
     * screen, and only the caller knows whether a retry is worth it.
     */
    val interceptChallenges = java.util.concurrent.CopyOnWriteArrayList<InterceptChallenge>()

    /**
     * Every main-frame URL `onPageStarted` has reported, in order.
     *
     * Polling `webView.url` is not enough to know where the session has been. 2026-08-03: the chain
     * passes through `cimanow.cc/pig/watching/`, which lives for **133 ms** before its meta-refresh
     * moves on — invisible to a 250 ms poll, so a step waiting for that URL never saw it even though
     * the log shows the engine both serving and rendering it. Recorded on the event instead.
     */
    private val mainFrameUrlsSeen = java.util.concurrent.CopyOnWriteArrayList<String>()

    /**
     * While set, a main-frame navigation whose URL does not match is refused.
     *
     * The destination lock's counterpart for the phase *before* arrival — see
     * [NavigationStep.AwaitMainFrameUrl.stayWithin]. Set for the duration of that step only.
     */
    @Volatile
    private var mainFrameNavigationGuard: Regex? = null

    /**
     * When the current main-frame document started loading, and how many times the link-minting
     * endpoint has been called since.
     *
     * `get-link.php`'s response is the one thing in this flow we can no longer see — it is a POST, so
     * the interceptor must decline it and Chromium keeps the body to itself. What is still observable is
     * *when* it fires, *how often*, and *what the page did afterwards*, and that turns out to be most of
     * what a diagnosis needs: 2026-08-03 it fired exactly once, 2 s after load, and the button's href
     * was still a placeholder 11 s later — which says the call was answered uselessly rather than
     * never made, and that the countdown a real user waits out had not run.
     */
    @Volatile
    private var mainFramePageStartedAtMs: Long = 0L

    @Volatile
    private var linkMintCallCount: Int = 0

    /** The header echo runs once per session, on the first refusal — see HEADER_ECHO_URL. */
    @Volatile
    private var echoDiagnosticDone = false

    /**
     * Main-frame navigations refused by [mainFrameNavigationGuard], counted per URL.
     *
     * The observable half of "did the page ever produce a real link?". A tally of eight attempts at the
     * same placeholder is a different finding from one attempt at an ad, and the difference decides
     * whether the next thing to change is the site's gate or our own containment.
     */
    /**
     * The popup URL that was promoted to the main frame, if any — see `promotePopupsMatching`.
     */
    @Volatile
    var promotedPopupUrl: String? = null
        private set

    private val refusedMainFrameNavigations =
        java.util.Collections.synchronizedMap(linkedMapOf<String, Int>())

    /** Set when the user closes the FULLSCREEN dialog, so a waiting step can stop immediately. */
    @Volatile
    private var dialogDismissedByUser = false

    /**
     * Set when the destination page navigates the main frame to its **own site** — the site refusing
     * to play (`/blockedone`, `location.replace('/home')`). Terminal: waiting for a stream after that
     * only buys a white screen.
     */
    @Volatile
    var siteRejectedNavigationUrl: String? = null
        private set

    /**
     * Set when the main frame left the destination page by a route the redirect callback never sees —
     * a `<meta http-equiv="refresh">` or a script navigation.
     *
     * Kept separate from [siteRejectedNavigationUrl] because the cause and the fix differ: that one is
     * the site refusing to play, this one is an interstitial or an ad taking the screen. Both are
     * terminal for a step waiting on a stream, and telling them apart is the difference between
     * "this title is blocked" and "we were never on the real page".
     */
    @Volatile
    var offDestinationNavigationUrl: String? = null
        private set

    /**
     * When the current document finished loading, or 0. Used only to age the inert-page test below.
     */
    @Volatile
    private var lastPageFinishedAt = 0L

    /**
     * True while *we* are dismissing the dialog during cleanup.
     *
     * Without it the dismiss listener cannot tell a user's back-press from our own teardown, and logged
     * "Dialog dismissed by user" 27 ms after the step finished — a claim about the user that was simply
     * untrue, and a flag left set for whatever came next.
     */
    @Volatile
    private var dismissingForCleanup = false

    /** Set by `onRenderProcessGone`: the page is blank because the renderer died. Terminal. */
    @Volatile
    var rendererGone = false
        private set

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun execute(
        steps: List<NavigationStep>,
        userAgent: String,
        mode: Mode = Mode.HEADLESS,
        overallTimeoutMs: Long = 120_000L,
        requestInterceptor: ((
            view: WebView,
            request: WebResourceRequest
        ) -> WebResourceResponse?)? = null,
        allowedDomains: Set<String> = emptySet(),
        destinationLockPatterns: List<Regex> = emptyList(),
        /**
         * Inject [SPOOFING_JS] into every page. Default true (what every existing caller expects).
         *
         * Pass **false** for pages whose anti-bot inspects its own environment. The spoof is not
         * free: it defines `window.DisableDevtool` and reports `navigator.plugins` as `[1,2,3,4,5]`,
         * which on Android Chrome is an empty PluginArray — a fake so crude that looking for it is
         * the first thing an automation check does. It runs at `onPageStarted`, i.e. before the
         * page's own scripts, so the gate sees all of it.
         */
        injectSpoofingJs: Boolean = true,
        /**
         * Let `window.open` popups actually load, in a hidden throwaway WebView.
         *
         * **Off by default, and deliberately opt-in per provider.** With it off a popup gets a live
         * but blank window, which satisfies a page that only checks `window.open() != null`. CimaNow
         * needs more: its ad network re-fires a `/ct?rb=…` conversion ping until the popunder really
         * loads, and until then the page shows a "allow redirection and popups" modal on every server
         * click (2026-07-30: 12 retries of the same `rb` token behind the swallowed popunder).
         *
         * The cost is real and is why this is not the default: the ad genuinely loads, with real
         * requests and a real impression, in a WebView the user never sees. Contained by
         * [MAX_POPUP_SINKS], [POPUP_SINK_TTL_MS], no nested popups, and http(s) only — and nothing it
         * loads can reach the main frame.
         */
        loadPopupsInSink: Boolean = false,
        /**
         * Capture player embeds — subframe navigations — and keep a copy of their HTML.
         *
         * **Off by default and opt-in per provider**, because it is not passive: to keep the bytes the
         * engine has to answer the iframe request itself (`fetchEmbedDocument`), so a provider whose
         * player iframe is sensitive to being re-issued — cookies not yet set, an IP-pinned token, a
         * POST, or simply a slower round trip — would be affected by a feature it never asked for.
         * CimaNow needs it (§5/§7 of the handover); nothing else should pay for it.
         */
        captureEmbeds: Boolean = false,
        /**
         * Rewrite `document.write('<script src=…')` in a cimanow.cc main-frame response and inject the
         * `document.write` interceptor.
         *
         * **Pass false for the surf flow.** Both exist to serve the old sandbox, which needed those CDN
         * scripts to load so it could scrape the decrypted server list out of the DOM. The surf flow
         * scrapes nothing — the user watches the page and we read the network — so the rewrite has no
         * purpose, while the injected hook is the `document.write` wrapper that handover §0.1 rule 3
         * forbids outright.
         *
         * This is what made *some* titles render white while others played (2026-07-30): the injection
         * only fires when the payload actually contains `document.write('<script…')` calls, so it was
         * dormant on the titles we tested and fatal on the ones we did not.
         */
        rewriteDocumentWrite: Boolean = true,
        /**
         * Inject `ANTI_ANTI_BOT_JS` (the `document.write` wrapper) alongside the rewrite.
         *
         * **This is the half that violates §0.1 rule 3** and must be false for CimaNow. Kept separate
         * from [rewriteDocumentWrite] because the two were bundled and then disabled together
         * (2026-07-30), which fixed the decoy and immediately caused a new failure: with the rewrite
         * off, the page's own `document.write('<script src=…')` calls run natively, and one firing
         * *after* load implicitly `document.open()`s and wipes the document — a blank page with no
         * navigation and no network activity, which is what a second server click produced.
         *
         * The rewrite alone leaves `document.write` native; only this flag hooks it.
         */
        injectDocumentWriteHook: Boolean = true,
        /**
         * The provider's say in what happens to cookies and challenges on responses the engine
         * answers itself. Defaults to [NavigationSessionPolicy.None] — do neither, which is what
         * every caller got before the interface existed.
         */
        sessionPolicy: NavigationSessionPolicy = NavigationSessionPolicy.None,
        /**
         * A popup whose URL matches this is **not** a popup — it is where the page meant to send the
         * user, and it is loaded into the main frame instead of a throwaway sink.
         *
         * `window.open` is how an ad-funded page opens its ads, and it is also how some of them open
         * the thing you actually asked for. Sinking every popup indiscriminately therefore risks
         * discarding the destination: it loads into a detached WebView nobody sees and is destroyed on
         * a timer. Naming the destination pattern lets the engine tell the two apart.
         *
         * Null (default) sinks everything, which is what every existing caller expects.
         */
        promotePopupsMatching: Regex? = null,
        /**
         * How long a popup sink lives before it is destroyed — i.e. before the page sees its
         * `window.open()` handle report `closed === true`.
         *
         * **This is a functional signal, not cleanup.** An ad-funded page that gates content on ad
         * views counts a view when the window it opened is *closed*; that is exactly what a user does
         * when they shut the ad tab and click again. A sink that never closes leaves the page waiting
         * forever for a cycle that, from its point of view, never completed.
         *
         * 2026-08-03: the retirement timer only ran when [loadPopupsInSink] was true, so with popups
         * swallowed — the mode that shows the user no ads — sinks lived for the entire session. The
         * freex countdown page never rewrote its watch button's href across 13 clicks, and this is the
         * likeliest reason: every click opened a window that never closed.
         *
         * Must exceed the dwell threshold the page checks (800 ms in cimanow's gate, see
         * [POPUNDER_DWELL_MS]) or the window looks like a blocked popup instead of a viewed one.
         */
        popupSinkTtlMs: Long = POPUP_SINK_TTL_MS,
        /**
         * JS injected at `onPageStarted` — before the page's own scripts — but **only** on pages whose
         * URL matches [earlyInjectOnHosts].
         *
         * Unlike a probe this can change the page, so it is the heaviest tool here and the last one to
         * reach for. It exists for one situation: the page performs a request whose *response* we need
         * and cannot otherwise see. `shouldInterceptRequest` cannot forward a POST body, so a POST
         * endpoint's answer is invisible to the engine — but not to the page, which is about to receive
         * it anyway.
         *
         * Timing is the reason this is not a step: a wrapper around `XMLHttpRequest` or `fetch` is only
         * effective if it is installed before the page captures its own reference, which means
         * `onPageStarted` and nowhere later.
         *
         * The host guard is enforced before evaluation, so a page this was not written for is never
         * touched. Providers whose target inspects its own environment should not use this at all —
         * see `cimanow_decryption_handover.md` §0.1.
         */
        earlyInjectJs: String? = null,
        /** [earlyInjectJs] runs only on URLs matching this. Without it, nothing is injected anywhere. */
        earlyInjectOnHosts: Regex? = null
    ): NavigationResult = withContext(Dispatchers.Main) {
        sessionMutex.withLock {
            // Reset intercepted state for this session
            interceptedWatchingUrl = null
            pendingRedirectUrl = null
            autoApproveAllRedirects = false
            lastHtmlBaseUrl = null
            lastPageUrl = ""
            siteRejectedNavigationUrl = null
            offDestinationNavigationUrl = null
            lastPageFinishedAt = 0L
            rendererGone = false
            capturedMainFrameHtml = null
            subresourceRequestsSinceMainFrame = 0
            capturedVideoUrls.clear()
            capturedVideoRequests.clear()
            capturedEmbedRequests.clear()
            interceptChallenges.clear()
            mainFrameUrlsSeen.clear()
            mainFrameNavigationGuard = null
            mainFramePageStartedAtMs = 0L
            linkMintCallCount = 0
            echoDiagnosticDone = false
            refusedMainFrameNavigations.clear()
            promotedPopupUrl = null
            dialogDismissedByUser = false

            val activity = activityProvider()
            if (activity == null) {
                ProviderLogger.e(TAG, "execute", "No Activity available")
                return@withContext NavigationResult(
                    success = false, finalUrl = "", cookies = emptyMap(),
                    extractedHtml = emptyMap(), completedSteps = 0,
                    failedAtStep = 0, error = "No Activity context"
                )
            }

            var webView: WebView? = null
            var dialog: android.app.Dialog? = null
            var currentUrl = ""
            val extractedHtml = mutableMapOf<String, String>()
            var completedSteps = 0
            var failedStep: Int? = null
            var errorMsg: String? = null
            val result = CompletableDeferred<NavigationResult>()
            var delivered = false

            val timeoutJob = launch {
                delay(overallTimeoutMs)
                if (!delivered) {
                    delivered = true
                    ProviderLogger.w(TAG, "execute", "Overall timeout after ${overallTimeoutMs}ms")
                    cleanupWebView(webView, dialog)
                    result.complete(NavigationResult(
                        success = false, finalUrl = currentUrl,
                        cookies = extractCookiesFromManager(currentUrl),
                        extractedHtml = extractedHtml,
                        completedSteps = completedSteps,
                        failedAtStep = completedSteps,
                        error = "Overall timeout",
                        // Carried through even on timeout: in FULLSCREEN the user may have started
                        // playback moments before the clock ran out, and those captures still play.
                        capturedVideoUrls = capturedVideoUrls.toList(),
                        capturedVideoRequests = capturedVideoRequests.toList(),
                        capturedEmbedRequests = capturedEmbedRequests.toList(),
                        interceptChallenges = interceptChallenges.toList()
                    ))
                }
            }

            try {
                webView = createWebView(activity, userAgent)
                setupWebViewClient(webView, userAgent, requestInterceptor, allowedDomains,
                    destinationLockPatterns, injectSpoofingJs, loadPopupsInSink, captureEmbeds,
                    rewriteDocumentWrite, injectDocumentWriteHook, sessionPolicy,
                    promotePopupsMatching, popupSinkTtlMs, earlyInjectJs, earlyInjectOnHosts)

                if (mode == Mode.FULLSCREEN) {
                    dialog = createDialog(activity, webView)
                    dialog.show()
                }

                for ((index, step) in steps.withIndex()) {
                    if (delivered) break

                    ProviderLogger.i(TAG, "execute", "Step $index: ${step.javaClass.simpleName}")
                    val stepStartMs = SystemClock.uptimeMillis()
                    try {
                        when (step) {
                            is NavigationStep.LoadUrl -> {
                                currentUrl = step.url
                                loadUrlInWebView(webView, step.url, step.referer, step.extraHeaders)
                            }
                            is NavigationStep.LoadHtml -> {
                                ProviderLogger.w(TAG, "execute", "Step $index: LoadHtml",
                                    "baseUrl" to step.baseUrl.take(100),
                                    "htmlLen" to step.html.length.toString())
                                currentUrl = step.baseUrl
                                lastHtmlBaseUrl = step.baseUrl
                                webView.loadDataWithBaseURL(step.baseUrl, step.html, "text/html", "UTF-8", null)
                                ProviderLogger.d(TAG, "execute", "Step $index: LoadHtml — loadDataWithBaseURL called, waiting 2s for initial render")
                                delay(2000)
                            }
                            is NavigationStep.ClickElement -> {
                                val clicked = clickElementInWebView(webView, step.selector, step.timeoutMs, currentUrl)
                                if (!clicked) {
                                    ProviderLogger.w(TAG, "execute", "Step $index: ClickElement failed for selector: ${step.selector}")
                                    if (step.abortOnFailure) {
                                        failedStep = index
                                        errorMsg = "ClickElement failed: ${step.selector}"
                                        break
                                    }
                                }
                            }
                            is NavigationStep.ClickCoordinates -> {
                                dispatchNativeClick(webView, step.x, step.y)
                                delay(150)
                            }
                            is NavigationStep.ExecuteJs -> {
                                val jsResult = executeJsInWebView(webView, step.javascript)
                                if (step.key.isNotBlank()) {
                                    extractedHtml[step.key] = jsResult ?: ""
                                    ProviderLogger.d(TAG, "execute", "JS result stored in extractedHtml['${step.key}']: ${(jsResult ?: "").take(100)}")
                                }
                                delay(300)
                            }
                            is NavigationStep.WaitForSelector -> {
                                val found = waitForSelector(webView, step.selector, step.timeoutMs, currentUrl)
                                if (!found) {
                                    ProviderLogger.w(TAG, "execute", "Step $index: WaitForSelector timed out: ${step.selector}")
                                    if (step.abortOnFailure) {
                                        failedStep = index
                                        errorMsg = "WaitForSelector timed out: ${step.selector}"
                                        break
                                    }
                                }
                            }
                            is NavigationStep.WaitForUrl -> {
                                val found = waitForUrl(webView, step.urlPattern, step.timeoutMs)
                                if (!found) {
                                    ProviderLogger.w(TAG, "execute", "Step $index: WaitForUrl timed out: ${step.urlPattern}")
                                    if (step.abortOnFailure) {
                                        failedStep = index
                                        errorMsg = "WaitForUrl timed out: ${step.urlPattern}"
                                        break
                                    }
                                }
                            }
                            is NavigationStep.WaitForDelay -> {
                                delay(step.delayMs)
                            }
                            is NavigationStep.WaitForDomCondition -> {
                                val met = waitForDomCondition(webView, step.jsCondition, step.timeoutMs, step.pollIntervalMs)
                                if (!met) {
                                    ProviderLogger.w(TAG, "execute", "Step $index: WaitForDomCondition timed out")
                                    if (step.abortOnFailure) {
                                        failedStep = index
                                        errorMsg = "WaitForDomCondition timed out"
                                        break
                                    }
                                }
                            }
                            is NavigationStep.WaitForDomConditionAndSnapshot -> {
                                val snap = waitForDomConditionAndSnapshot(
                                    webView, step.jsCondition,
                                    step.snapshotJs, step.timeoutMs, step.pollIntervalMs
                                )
                                extractedHtml[step.key] = snap ?: ""
                                if (snap == null) {
                                    ProviderLogger.w(TAG, "execute", "Step $index: WaitForDomConditionAndSnapshot timed out")
                                    if (step.abortOnFailure) {
                                        failedStep = index
                                        errorMsg = "WaitForDomConditionAndSnapshot timed out"
                                        break
                                    }
                                }
                            }
                            is NavigationStep.ExtractHtml -> {
                                val html = extractHtmlFromWebView(webView, step.selector)
                                val key = step.key.ifBlank { step.selector ?: "full_page_${index}" }
                                extractedHtml[key] = html ?: ""
                                val len = html?.length ?: 0
                                ProviderLogger.i(TAG, "execute", "Step $index: ExtractHtml ${key.take(40)} -> $len chars")
                                activityProvider()?.let { ctx ->
                                    try {
                                        val file = java.io.File(ctx.cacheDir, "cimanow_html_${key}.html")
                                        file.writeText(html.orEmpty())
                                        ProviderLogger.i("CimaNowHtmlDump", "writeHtml", "HTML $key written to ${file.absolutePath} ($len bytes)")
                                    } catch (e: Exception) {
                                        ProviderLogger.w("CimaNowHtmlDump", "writeHtml", "Failed to write HTML $key: ${e.message}")
                                    }
                                    try {
                                        val dlDir = ctx.externalCacheDir ?: ctx.cacheDir
                                        dlDir.mkdirs()
                                        val dlFile = java.io.File(dlDir, "cimanow_html_${key}.html")
                                        dlFile.writeText(html.orEmpty())
                                        ProviderLogger.i("CimaNowHtmlDump", "writeHtml", "HTML $key written to EXTCACHE ${dlFile.absolutePath} ($len bytes)")
                                    } catch (e: Exception) {
                                        ProviderLogger.w("CimaNowHtmlDump", "writeHtml", "Failed to write HTML $key to Downloads: ${e.message}")
                                    }
                                }
                            }
                            is NavigationStep.WaitForCapturedVideo -> {
                                // Keeps the (visible) session alive while the user picks a server and
                                // presses play. Segments are ignored as an exit trigger — the .ts a
                                // player fetches proves it is playing but is useless as a link, and
                                // the manifest is always requested before them.
                                fun playable() = capturedVideoRequests.filter {
                                    VideoUrlClassifier.isPlayableCapture(it.url)
                                }

                                // Wait for a STREAM, not for an embed.
                                //
                                // An embed capture proves an iframe was inserted; it proves nothing
                                // about whether it plays. 2026-07-30: a VK embed was captured, this
                                // step exited 0.5s later, the window closed — and the embed was VK's
                                // error page (`video_embed_error`, `cry_dog.png`) with
                                // `totalStreamCaptures=0`. The user never got to pick another server,
                                // and the doomed embed then held a spinner for 49s. A byte of video on
                                // the wire is the only honest proof, and waiting for it costs nothing
                                // in the healthy case (the stream follows the embed within ~2s).
                                //
                                // The embed is still what gets resolved first afterwards — it carries
                                // the quality ladder — and embeds are handed back even when no stream
                                // ever arrives, so a slow-but-working server is not lost.
                                // An inert page: served, finished, and then did essentially nothing.
                                //
                                // 2026-08-05. cimanow answered the watch page with a stub — the whole
                                // document pulled 2 subresources (a Cloudflare RUM beacon and a favicon)
                                // and not one theme asset, where a real watch page pulls 17-54 and an
                                // embed within seconds. There is no stream coming from that, but the step
                                // had no way to know, so it sat out its entire budget with a dead page on
                                // screen. The caller's retry — a Cloudflare solve and a second surf, which
                                // is the one thing that might actually help — only runs after this returns.
                                //
                                // Aged deliberately: the count is read a few seconds *after*
                                // `onPageFinished`, never at it, because a healthy page is still adding
                                // requests as it finishes. Requiring zero embeds and zero captures too
                                // means a slow-but-working server can never trip it.
                                fun inertPage(): Boolean {
                                    val finishedAt = lastPageFinishedAt
                                    return finishedAt > 0L &&
                                        System.currentTimeMillis() - finishedAt >= INERT_PAGE_GRACE_MS &&
                                        subresourceRequestsSinceMainFrame < INERT_PAGE_SUBRESOURCE_FLOOR &&
                                        capturedEmbedRequests.isEmpty() &&
                                        capturedVideoRequests.isEmpty()
                                }

                                // Once a **substantive** embed is in hand, stop waiting minutes for a
                                // stream that is not coming.
                                //
                                // 2026-08-05, and this is the spinner the user sees. A VK embed was
                                // captured 2.7 s into the step with 73,963 chars of its own HTML — the
                                // good path, the one that yields the whole quality ladder — and the step
                                // then sat for the rest of its 300 s budget because `streamCaptures`
                                // stayed 0. It stays 0 because the page's own player never initialises:
                                // jQuery is one of the assets cimanow refuses (`$ is not defined`), so
                                // nothing ever asks for video. The embed was already enough; in the
                                // 09:44 run the identical URL produced three playable links from its
                                // captured HTML with no request made at all.
                                //
                                // The existing comment above is right that a stream is the only honest
                                // proof of playback — and that "the stream follows the embed within ~2s"
                                // in the healthy case. So this does not stop waiting, it stops waiting
                                // *forever*: a short grace after the embed, then hand it over, which the
                                // code below already treats as a non-failure.
                                //
                                // Guarded against rule 21 (an embed is not proof the server works): a VK
                                // `video_embed_error` page and an ad frame are both small — the ad frames
                                // in the 08-03 log were 854 chars — so only an embed carrying real markup
                                // starts the clock.
                                // Timed from when this loop first *sees* it rather than from a field on the
                                // capture: the poll interval is a fraction of the grace, so the two are
                                // the same number, and the shared capture type stays untouched.
                                fun hasSubstantiveEmbed() = capturedEmbedRequests.any {
                                    (it.html?.length ?: 0) >= SUBSTANTIVE_EMBED_HTML_CHARS
                                }

                                val deadline = System.currentTimeMillis() + step.timeoutMs
                                var lastHeartbeat = 0L
                                var substantiveEmbedSeenAt = 0L
                                var embedGraceExpired = false
                                while (playable().isEmpty() && !dialogDismissedByUser &&
                                    siteRejectedNavigationUrl == null &&
                                    offDestinationNavigationUrl == null && !rendererGone &&
                                    !inertPage() && !embedGraceExpired &&
                                    System.currentTimeMillis() < deadline
                                ) {
                                    if (substantiveEmbedSeenAt == 0L && hasSubstantiveEmbed()) {
                                        substantiveEmbedSeenAt = System.currentTimeMillis()
                                        ProviderLogger.i(TAG, "execute",
                                            "A substantive embed is in hand — capping the stream wait",
                                            "graceMs" to EMBED_STREAM_GRACE_MS.toString(),
                                            "embeds" to capturedEmbedRequests.size.toString())
                                    }
                                    if (substantiveEmbedSeenAt > 0L &&
                                        System.currentTimeMillis() - substantiveEmbedSeenAt >=
                                        EMBED_STREAM_GRACE_MS
                                    ) {
                                        embedGraceExpired = true
                                        continue
                                    }
                                    delay(step.pollIntervalMs)
                                    // Heartbeat: without it a stuck surf logs nothing for minutes, so a
                                    // blank page and a page nobody touched look identical afterwards.
                                    val nowMs = System.currentTimeMillis()
                                    if (nowMs - lastHeartbeat >= 5_000L) {
                                        lastHeartbeat = nowMs
                                        ProviderLogger.i(TAG, "execute", "⏳ Waiting for a stream",
                                            "embeds" to capturedEmbedRequests.size,
                                            "streamCaptures" to capturedVideoRequests.size,
                                            "sinks" to popupSinks.size,
                                            "url" to (webView?.url ?: lastPageUrl).take(110))
                                    }
                                }

                                if (playable().isEmpty()) {
                                    val why = when {
                                        rendererGone -> "render process died — the blank page was a renderer crash"
                                        embedGraceExpired ->
                                            "no stream within ${EMBED_STREAM_GRACE_MS}ms of a substantive " +
                                                "embed — the page's own player never asked for video " +
                                                "(jQuery is among the assets this site refuses), so the " +
                                                "embed is the answer rather than a consolation"
                                        inertPage() ->
                                            "the page was served but stayed inert — " +
                                                "$subresourceRequestsSinceMainFrame subresource request(s) " +
                                                "and no embed, where a working watch page makes 17-54. The " +
                                                "site handed us a stub, so no stream was ever coming; " +
                                                "bailing out early instead of waiting ${step.timeoutMs}ms"
                                        siteRejectedNavigationUrl != null ->
                                            "site sent us to ${siteRejectedNavigationUrl} — title blocked or session rejected"
                                        offDestinationNavigationUrl != null ->
                                            "the page took the main frame off the destination via a " +
                                                "meta-refresh or script nav (${offDestinationNavigationUrl}) " +
                                                "— we were never on the real watch page"
                                        dialogDismissedByUser -> "user closed the window"
                                        else -> "no stream within ${step.timeoutMs}ms"
                                    }
                                    if (capturedEmbedRequests.isNotEmpty()) {
                                        // Not a failure: the caller can still try the extractors.
                                        ProviderLogger.w(TAG, "execute",
                                            "Step $index: No stream ($why) — handing over " +
                                                "${capturedEmbedRequests.size} embed(s) to try anyway")
                                    } else {
                                        ProviderLogger.w(TAG, "execute", "Step $index: No source — $why")
                                        if (step.abortOnFailure) {
                                            failedStep = index
                                            errorMsg = "No video captured ($why)"
                                            break
                                        }
                                    }
                                } else {
                                    // An embed makes the long grace pointless — see embedGraceMs.
                                    val grace = if (capturedEmbedRequests.isNotEmpty()) {
                                        step.embedGraceMs
                                    } else {
                                        step.graceMs
                                    }
                                    ProviderLogger.i(TAG, "execute",
                                        "Step $index: Source captured — collecting for ${grace}ms",
                                        "embeds" to capturedEmbedRequests.size,
                                        "streams" to playable().size,
                                        "first" to (capturedEmbedRequests.firstOrNull()?.url
                                            ?: playable().first().url).take(120))
                                    // Cut it short if the user closes the window.
                                    val graceEnd = System.currentTimeMillis() + grace
                                    while (System.currentTimeMillis() < graceEnd && !dialogDismissedByUser) {
                                        delay(step.pollIntervalMs)
                                    }
                                    ProviderLogger.i(TAG, "execute",
                                        "Step $index: Done", "embeds" to capturedEmbedRequests.size,
                                        "playableStreams" to playable().size,
                                        "totalStreamCaptures" to capturedVideoRequests.size,
                                        "closedByUser" to dialogDismissedByUser)
                                }
                            }
                            is NavigationStep.AwaitMainFrameUrl -> {
                                // The page will not navigate on its own — the user has to press the
                                // button. See AwaitMainFrameUrl.
                                //
                                // Auto-approve is on for the same reason NavigateToWatchingUrl turns it
                                // on: the route to the destination runs through ad domains that change
                                // per session, and a blocked hop is a dead end. The destination lock
                                // takes over the moment we arrive (onPageStarted).
                                this@NavigationEngine.autoApproveAllRedirects = true
                                // Contain the session to pages that can still lead somewhere. Without
                                // this, one stray tap hands the screen to an ad chain and the step waits
                                // out its whole timeout for a URL that can no longer arrive.
                                this@NavigationEngine.mainFrameNavigationGuard = step.stayWithin?.let {
                                    Regex("(${step.urlPattern.pattern})|(${it.pattern})")
                                }
                                val deadline = System.currentTimeMillis() + step.timeoutMs
                                var landedOn: String? = null
                                var lastSeen = ""
                                var lastRejected: String? = null
                                var lastHeartbeat = 0L
                                var lastProbe = 0L
                                var lastProbeValue: String? = null
                                var probeSkipLogged = false
                                while (System.currentTimeMillis() < deadline &&
                                    !dialogDismissedByUser && !rendererGone
                                ) {
                                    val here = (webView?.url ?: lastPageUrl)
                                    if (here.isNotBlank() && here != lastSeen) {
                                        lastSeen = here
                                        ProviderLogger.i(TAG, "execute",
                                            "Step $index: main frame now at ${here.take(120)}")
                                    }
                                    // Every URL the main frame has *been* to, not just where it is
                                    // now: a waypoint can come and go inside one poll interval — plus
                                    // whatever the interceptor read out of a link-minting response,
                                    // which arrives without any navigation at all.
                                    val candidates = (mainFrameUrlsSeen + here +
                                        listOfNotNull(interceptedWatchingUrl)).filter { it.isNotBlank() }
                                    val hit = candidates.lastOrNull { url ->
                                        step.urlPattern.containsMatchIn(url) &&
                                            step.accept?.invoke(url) != false
                                    }
                                    if (hit != null) {
                                        landedOn = hit
                                        break
                                    }
                                    val rejected = candidates.lastOrNull { step.urlPattern.containsMatchIn(it) }
                                    if (rejected != null && rejected != lastRejected) {
                                        lastRejected = rejected
                                        ProviderLogger.w(TAG, "execute",
                                            "Step $index: URL matches but was rejected — still waiting",
                                            "url" to rejected.take(140))
                                    }
                                    val nowMs = System.currentTimeMillis()

                                    // ── The one page read in this flow — see AwaitMainFrameUrl.probeJs ──
                                    //
                                    // Host-guarded in code, not by convention: no match, no evaluation.
                                    if (step.probeJs != null &&
                                        nowMs - lastProbe >= step.probeIntervalMs &&
                                        webView != null
                                    ) {
                                        lastProbe = nowMs
                                        val hereNow = webView.url ?: lastPageUrl
                                        val allowed = step.probeOnlyOnHosts
                                            ?.containsMatchIn(hereNow) ?: false
                                        if (!allowed) {
                                            if (!probeSkipLogged) {
                                                probeSkipLogged = true
                                                ProviderLogger.i(TAG, "execute",
                                                    "Step $index: probe NOT run — current page is " +
                                                        "outside probeOnlyOnHosts",
                                                    "at" to hereNow.take(110))
                                            }
                                        } else {
                                            probeSkipLogged = false
                                            val probed = try {
                                                executeJsInWebView(webView, step.probeJs)
                                            } catch (e: Exception) {
                                                ProviderLogger.w(TAG, "execute",
                                                    "Step $index: probe threw: ${e.message}")
                                                null
                                            }
                                            if (probed != lastProbeValue) {
                                                lastProbeValue = probed
                                                ProviderLogger.w(TAG, "execute",
                                                    "Step $index: 🔎 probe → ${probed?.take(160) ?: "<null>"}")
                                            }
                                            if (!probed.isNullOrBlank() &&
                                                step.urlPattern.containsMatchIn(probed) &&
                                                step.accept?.invoke(probed) != false
                                            ) {
                                                ProviderLogger.w(TAG, "execute",
                                                    "Step $index: ✅ the page is holding the real link — " +
                                                        "navigating there directly, no click needed",
                                                    "url" to probed.take(160))
                                                val ref = hereNow.takeIf {
                                                    it.isNotBlank() && it != "about:blank"
                                                }
                                                // Pre-approve so the guard and the redirect check both
                                                // let it through, then let the loop notice the arrival
                                                // through the same path a click would have taken.
                                                this@NavigationEngine.pendingRedirectUrl = probed
                                                loadUrlInWebView(webView, probed, ref, emptyMap())
                                            }
                                        }
                                    }

                                    if (nowMs - lastHeartbeat >= 10_000L) {
                                        lastHeartbeat = nowMs
                                        ProviderLogger.i(TAG, "execute",
                                            "Step $index: ⏳ waiting for the user to reach " +
                                                "${step.urlPattern}",
                                            "at" to lastSeen.take(110),
                                            "mainFrameHops" to mainFrameUrlsSeen.size.toString(),
                                            "linkMintCalls" to linkMintCallCount.toString(),
                                            "embedsSoFar" to capturedEmbedRequests.size.toString(),
                                            "elapsedMs" to (nowMs - (deadline - step.timeoutMs)).toString(),
                                            "remainingMs" to (deadline - nowMs).toString())
                                    }
                                    delay(step.pollIntervalMs)
                                }
                                this@NavigationEngine.mainFrameNavigationGuard = null
                                // The route taken, whatever the outcome: which hops happened, in order,
                                // is the difference between "the user never pressed it" and "the press
                                // went somewhere useless".
                                ProviderLogger.i(TAG, "execute",
                                    "Step $index: main-frame path (${mainFrameUrlsSeen.size} hop(s), " +
                                        "$linkMintCallCount link-mint call(s)):")
                                mainFrameUrlsSeen.forEachIndexed { hop, url ->
                                    ProviderLogger.i(TAG, "execute", "    [$hop] ${url.take(150)}")
                                }
                                // Refused attempts, aggregated. Repeated tries at one URL mean the page
                                // kept offering the same dead link — i.e. it never minted a real one —
                                // which is the difference between "the site did not cooperate" and
                                // "the user never pressed the button".
                                if (refusedMainFrameNavigations.isNotEmpty()) {
                                    val total = refusedMainFrameNavigations.values.sum()
                                    ProviderLogger.w(TAG, "execute",
                                        "Step $index: refused $total main-frame navigation(s) to " +
                                            "${refusedMainFrameNavigations.size} distinct URL(s):")
                                    synchronized(refusedMainFrameNavigations) {
                                        refusedMainFrameNavigations.forEach { (url, n) ->
                                            ProviderLogger.w(TAG, "execute", "    ${n}× ${url.take(150)}")
                                        }
                                    }
                                }
                                if (landedOn != null) {
                                    currentUrl = landedOn
                                    // Recorded so the legacy consumers of this value still see it.
                                    this@NavigationEngine.interceptedWatchingUrl = landedOn
                                    ProviderLogger.w(TAG, "execute",
                                        "Step $index: ✅ reached ${landedOn.take(140)}")
                                } else {
                                    val why = when {
                                        dialogDismissedByUser -> "user closed the window"
                                        rendererGone -> "render process died"
                                        else -> "no matching URL within ${step.timeoutMs}ms " +
                                            "(last seen: ${lastSeen.take(110)})"
                                    }
                                    ProviderLogger.w(TAG, "execute", "Step $index: $why")
                                    if (step.abortOnFailure) {
                                        failedStep = index
                                        errorMsg = why
                                        break
                                    }
                                }
                            }
                            is NavigationStep.NavigateToWatchingUrl -> {
                                // Enable auto-approve for the redirect chain through ad domains
                                this@NavigationEngine.autoApproveAllRedirects = true
                                // Referer for the watching request should be the timer page
                                // (lastHtmlBaseUrl). currentUrl is reset to "about:blank" after
                                // loadDataWithBaseURL, so prefer lastHtmlBaseUrl and never send about:blank.
                                val refererForWatching = (lastHtmlBaseUrl ?: currentUrl)
                                    .takeIf { it.isNotBlank() && it != "about:blank" }

                                // A captured URL the caller rejects is a failed chain, not a
                                // destination — see NavigateToWatchingUrl.accept.
                                fun acceptable(url: String): Boolean {
                                    val ok = step.accept?.invoke(url) ?: true
                                    if (!ok) {
                                        ProviderLogger.e(TAG, "execute",
                                            "Step $index: 🚫 Captured watching URL REJECTED by the " +
                                                "caller — the chain produced a link, but not a usable " +
                                                "one. Not navigating.",
                                            null,
                                            "url" to url.take(140), "length" to url.length.toString())
                                    }
                                    return ok
                                }

                                val watchUrl = this@NavigationEngine.interceptedWatchingUrl
                                    ?.takeIf { acceptable(it) }
                                if (!watchUrl.isNullOrBlank()) {
                                    ProviderLogger.w(TAG, "execute", "Step $index: Navigating to watching URL: ${watchUrl.take(120)}")
                                    ProviderLogger.w(TAG, "execute", "Step $index: Watching Referer = ${refererForWatching ?: "<none>"}")
                                    // Pre-approve the redirect so no confirmation dialog appears
                                    this@NavigationEngine.pendingRedirectUrl = watchUrl
                                    // Use the timer page URL (blog-post.html) as Referer to prevent hotlink blocking
                                    loadUrlInWebView(webView, watchUrl, refererForWatching, emptyMap())
                                    currentUrl = watchUrl
                                } else {
                                    ProviderLogger.w(TAG, "execute", "Step $index: No watching URL captured yet, polling...")
                                    val deadline = System.currentTimeMillis() + 15_000L
                                    var polled = false
                                    while (System.currentTimeMillis() < deadline) {
                                        val captured = this@NavigationEngine.interceptedWatchingUrl
                                        if (!captured.isNullOrBlank() && !acceptable(captured)) {
                                            // Rejected, and polling cannot improve it: get-link.php
                                            // already answered. Stop rather than spend the remaining
                                            // 15 s re-reading the same value.
                                            break
                                        }
                                        val url = captured
                                        if (!url.isNullOrBlank()) {
                                            ProviderLogger.w(TAG, "execute", "Step $index: Watching URL appeared after polling: ${url.take(120)}")
                                            ProviderLogger.w(TAG, "execute", "Step $index: Watching Referer = ${refererForWatching ?: "<none>"}")
                                            // Pre-approve the redirect so no confirmation dialog appears
                                            this@NavigationEngine.pendingRedirectUrl = url
                                            loadUrlInWebView(webView, url, refererForWatching, emptyMap())
                                            currentUrl = url
                                            polled = true
                                            break
                                        }
                                        delay(500)
                                    }
                                    if (!polled) {
                                        val captured = this@NavigationEngine.interceptedWatchingUrl
                                        val why = if (!captured.isNullOrBlank()) {
                                            "Watching URL captured but rejected: ${captured.take(120)}"
                                        } else {
                                            "No watching URL captured"
                                        }
                                        ProviderLogger.w(TAG, "execute", "Step $index: $why")
                                        if (step.abortOnFailure) {
                                            failedStep = index
                                            errorMsg = why
                                            break
                                        }
                                    }
                                }
                            }
                        }
                        completedSteps = index + 1
                        val stepMs = SystemClock.uptimeMillis() - stepStartMs
                        currentUrl = getCurrentUrlFromWebView(webView) ?: currentUrl
                        ProviderLogger.d(TAG, "execute", "Step $index done in ${stepMs}ms, currentUrl=${currentUrl.take(80)}")
                    } catch (e: Exception) {
                        ProviderLogger.e(TAG, "execute", "Step $index failed", e)
                        failedStep = index
                        errorMsg = e.message
                        break
                    }
                }

                if (!delivered) {
                    delivered = true
                    timeoutJob.cancel()
                    val cookies = extractCookiesFromManager(currentUrl)
                    currentUrl = getCurrentUrlFromWebView(webView) ?: currentUrl
                    
                    val isSuccess = failedStep == null && errorMsg == null
                    if (!isSuccess && webView != null) {
                        try {
                            // The bytes we SERVED, not the DOM we would have to ask the page for.
                            // `extractHtmlFromWebView` runs `document.documentElement.outerHTML`
                            // inside the page, and a failure is precisely when the session gets
                            // retried — so that read would be the last thing the site sees us do
                            // before we come back. Same diagnostic value for a served payload,
                            // zero JS. See onPageFinished for the same trade.
                            val html = capturedMainFrameHtml
                            val len = html?.length ?: 0
                            val dumpKey = "failure_step_${failedStep ?: completedSteps}"
                            activityProvider()?.let { ctx ->
                                val file = java.io.File(ctx.cacheDir, "cimanow_html_${dumpKey}.html")
                                file.writeText(html.orEmpty())
                                ProviderLogger.e(TAG, "execute", "FAILURE DUMP: HTML written to ${file.absolutePath} ($len bytes)")
                                try {
                                    val dlDir = ctx.externalCacheDir ?: ctx.cacheDir
                                    dlDir.mkdirs()
                                    val dlFile = java.io.File(dlDir, "cimanow_html_${dumpKey}.html")
                                    dlFile.writeText(html.orEmpty())
                                    ProviderLogger.e(TAG, "execute", "FAILURE DUMP: HTML written to EXTCACHE ${dlFile.absolutePath} ($len bytes)")
                                } catch (e: Exception) {
                                    ProviderLogger.w("CimaNowHtmlDump", "writeHtml", "Failed to write failure dump to Downloads: ${e.message}")
                                }
                            }
                        } catch (de: Exception) {
                            ProviderLogger.w(TAG, "execute", "Failed to dump HTML on failure: ${de.message}")
                        }
                    }

                    cleanupWebView(webView, dialog)
                    result.complete(NavigationResult(
                        success = isSuccess,
                        finalUrl = currentUrl,
                        cookies = cookies,
                        extractedHtml = extractedHtml,
                        completedSteps = completedSteps,
                        failedAtStep = failedStep,
                        error = errorMsg,
                        capturedVideoUrls = capturedVideoUrls.toList(),
                        capturedVideoRequests = capturedVideoRequests.toList(),
                        capturedEmbedRequests = capturedEmbedRequests.toList(),
                        mainFrameHtml = capturedMainFrameHtml,
                        interceptChallenges = interceptChallenges.toList()
                    ))
                }
            } catch (e: Exception) {
                if (!delivered) {
                    delivered = true
                    timeoutJob.cancel()
                    if (webView != null) {
                        try {
                            // Served bytes, not a DOM read — see the failure dump above.
                            val html = capturedMainFrameHtml
                            val len = html?.length ?: 0
                            activityProvider()?.let { ctx ->
                                val file = java.io.File(ctx.cacheDir, "cimanow_html_failure_exception.html")
                                file.writeText(html.orEmpty())
                                ProviderLogger.e(TAG, "execute", "EXCEPTION DUMP: HTML written to ${file.absolutePath} ($len bytes)")
                                try {
                                    val dlDir = ctx.externalCacheDir ?: ctx.cacheDir
                                    dlDir.mkdirs()
                                    val dlFile = java.io.File(dlDir, "cimanow_html_failure_exception.html")
                                    dlFile.writeText(html.orEmpty())
                                    ProviderLogger.e(TAG, "execute", "EXCEPTION DUMP: HTML written to EXTCACHE ${dlFile.absolutePath} ($len bytes)")
                                } catch (e: Exception) {
                                    ProviderLogger.w("CimaNowHtmlDump", "writeHtml", "Failed to write exception dump to Downloads: ${e.message}")
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    cleanupWebView(webView, dialog)
                    result.complete(NavigationResult(
                        success = false, finalUrl = currentUrl,
                        cookies = emptyMap(), extractedHtml = extractedHtml,
                        completedSteps = completedSteps,
                        failedAtStep = completedSteps, error = e.message,
                        capturedVideoUrls = capturedVideoUrls.toList(),
                        interceptChallenges = interceptChallenges.toList()
                    ))
                }
            }

            result.await()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(activity: android.app.Activity, userAgent: String): WebView {
        return WebView(activity).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = userAgent
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = true
                blockNetworkImage = false
                loadsImagesAutomatically = true
                @Suppress("DEPRECATION")
                allowFileAccess = false
                // Allow the page to open popups (e.g. the player window launched when a
                // server tab is clicked). Required so the page's JS runs fully and the
                // decrypted player iframe gets injected into the DOM.
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
            }
            // THE NUCLEAR SOLUTION: Hide the package name from ALL WebView requests natively.
            hideXRequestedWithHeader(this)
        }
    }

    private fun hideXRequestedWithHeader(webView: WebView) {
        // Approach 0: the supported API — androidx.webkit's origin allow-list.
        //
        // The four reflection approaches below target WebView internals that moved years ago; on
        // WebView 150 they all fail, and the engine has been logging "X-Requested-With may leak" ever
        // since without anyone reading it. That leak is not cosmetic: verified on-device with curl,
        // `X-Requested-With: <package>` turns freex2line's 35-byte answer into a 4.5 KB Cloudflare
        // block page. Intercepted GETs escape it because they are re-issued through
        // HttpURLConnection; a POST cannot be intercepted, so it goes out wearing the app's name.
        //
        // `setRequestedWithHeaderOriginAllowList(emptySet())` means "send it to no origin at all",
        // which is the only setting that reaches requests the interceptor never sees.
        //
        // Reached by reflection on purpose: androidx.webkit may or may not be on the host app's
        // classpath, and a missing class must degrade to the old behaviour rather than crash the
        // plugin. Nothing here throws outward.
        try {
            val featureCls = Class.forName("androidx.webkit.WebViewFeature")
            val supported = featureCls
                .getMethod("isFeatureSupported", String::class.java)
                .invoke(null, "REQUESTED_WITH_HEADER_ALLOW_LIST") as? Boolean ?: false
            if (supported) {
                Class.forName("androidx.webkit.WebSettingsCompat")
                    .getMethod(
                        "setRequestedWithHeaderOriginAllowList",
                        android.webkit.WebSettings::class.java,
                        Set::class.java
                    )
                    .invoke(null, webView.settings, emptySet<String>())
                ProviderLogger.w(TAG, "hideXRequestedWithHeader",
                    "✅ X-Requested-With disabled for ALL origins via androidx.webkit " +
                        "(setRequestedWithHeaderOriginAllowList) — this covers requests the " +
                        "interceptor cannot touch, POSTs included")
                return
            }
            ProviderLogger.w(TAG, "hideXRequestedWithHeader",
                "androidx.webkit present but REQUESTED_WITH_HEADER_ALLOW_LIST unsupported by this " +
                    "WebView — falling through to reflection")
        } catch (e: ClassNotFoundException) {
            ProviderLogger.w(TAG, "hideXRequestedWithHeader",
                "androidx.webkit not on the classpath — cannot use the supported API, falling " +
                    "through to reflection (which fails on modern WebView; see the leak warning below)")
        } catch (e: Exception) {
            ProviderLogger.w(TAG, "hideXRequestedWithHeader",
                "androidx.webkit attempt failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            // Approach 1: Direct method on WebView itself (newer Chrome WebViews)
            try {
                val method = WebView::class.java.getMethod("setXRequestedWithHeader", String::class.java)
                method.invoke(webView, "")
                ProviderLogger.i(TAG, "hideXRequestedWithHeader", "Cleared via WebView.setXRequestedWithHeader method")
                return
            } catch (_: NoSuchMethodException) {}

            // Approach 2: Method on mProvider
            try {
                val providerField = WebView::class.java.getDeclaredField("mProvider")
                providerField.isAccessible = true
                val provider = providerField.get(webView)
                try {
                    val method = provider.javaClass.getMethod("setXRequestedWithHeader", String::class.java)
                    method.invoke(provider, "")
                    ProviderLogger.i(TAG, "hideXRequestedWithHeader", "Cleared via provider.setXRequestedWithHeader method")
                    return
                } catch (_: NoSuchMethodException) {}

                // Approach 3: Field mXRequestedWithHeader on provider hierarchy
                var cls: Class<*>? = provider.javaClass
                while (cls != null) {
                    try {
                        val f = cls.getDeclaredField("mXRequestedWithHeader")
                        f.isAccessible = true
                        f.set(provider, "")
                        ProviderLogger.i(TAG, "hideXRequestedWithHeader", "Cleared via field mXRequestedWithHeader on provider")
                        return
                    } catch (_: NoSuchFieldException) {
                        cls = cls.superclass
                    }
                }

                // Approach 4: Field xRequestedWithHeader (camelCase, no m-prefix)
                cls = provider.javaClass
                while (cls != null) {
                    try {
                        val f = cls.getDeclaredField("xRequestedWithHeader")
                        f.isAccessible = true
                        f.set(provider, "")
                        ProviderLogger.i(TAG, "hideXRequestedWithHeader", "Cleared via field xRequestedWithHeader on provider")
                        return
                    } catch (_: NoSuchFieldException) {
                        cls = cls.superclass
                    }
                }
            } catch (e: Exception) {
                ProviderLogger.w(TAG, "hideXRequestedWithHeader", "Provider access failed: ${e.message}")
            }

            // Approach 5: Try AwContents fields through WebViewChromium
            try {
                val providerField = WebView::class.java.getDeclaredField("mProvider")
                providerField.isAccessible = true
                val provider = providerField.get(webView)
                val awContentsField = provider.javaClass.getDeclaredField("mAwContents")
                awContentsField.isAccessible = true
                val awContents = awContentsField.get(provider)
                var cls2: Class<*>? = awContents.javaClass
                while (cls2 != null) {
                    try {
                        val f = cls2.getDeclaredField("mXRequestedWithHeader")
                        f.isAccessible = true
                        f.set(awContents, "")
                        ProviderLogger.i(TAG, "hideXRequestedWithHeader", "Cleared via AwContents.mXRequestedWithHeader")
                        return
                    } catch (_: NoSuchFieldException) {
                        cls2 = cls2.superclass
                    }
                }
            } catch (e: Exception) {
                ProviderLogger.w(TAG, "hideXRequestedWithHeader", "AwContents approach failed: ${e.message}")
            }
        } catch (e: Exception) {
            ProviderLogger.w(TAG, "hideXRequestedWithHeader", "Reflection failed: ${e.message}")
        }
        ProviderLogger.w(TAG, "hideXRequestedWithHeader", "All reflection approaches failed — X-Requested-With may leak — using interceptor as fallback")
    }

    /**
     * Fetches an iframe's document so its HTML can be kept, and returns both the text and a response
     * carrying the same bytes for the WebView to render. Null on any failure, which the caller treats
     * as "let Chromium fetch it normally".
     *
     * Runs on a Chromium worker thread (that is where `shouldInterceptRequest` is called), so the
     * blocking IO here is on the right thread already.
     *
     * The headers are the iframe's own, plus the three things a re-issued request loses:
     * `Sec-Fetch-Dest: iframe` (which is what makes an embed-only endpoint answer at all — WebView
     * does not include it in `requestHeaders`, and without it `video_ext.php` returns an error page),
     * real-Chrome `sec-ch-ua` in place of `"Android WebView"`, and the CookieManager cookies.
     */
    /**
     * Hide the page for [POPUNDER_DWELL_MS] after it opens a popup, then show it again.
     *
     * This is what a popunder does on a real phone: the new window takes the screen, the page goes
     * `hidden`, and it only becomes `visible` again seconds later when the user comes back. CimaNow's
     * watch buttons check for exactly that, and the deobfuscated handler shows how strictly (decrypted
     * page, 2026-07-30):
     *
     *     win = window.open(href, "_blank");           // must exist and stay open
     *     …
     *     document.addEventListener("visibilitychange", …)   // mobile: first of these decides
     *     document.addEventListener("touchstart", onReturn, {once:true})
     *     document.addEventListener("mousemove",  onReturn)
     *     onReturn = () => elapsed < 800 ? SweetAlert("let the ad stay open a few seconds") : ok()
     *
     * Our sink satisfies the window checks — it is a real WebView that lives 15 s — but it is detached
     * and invisible, so the page never loses visibility and the next input event (Chromium's compat
     * `mousemove` after a tap, or a second tap because nothing seemed to happen) arrives inside the
     * 800 ms window and raises the modal. Dipping visibility for longer than the threshold makes the
     * gate take its success path instead, and it does so through an ordinary browser signal — nothing
     * is injected into the page, which is the constraint everything here lives under.
     */
    private fun dipPageVisibility(view: WebView?) {
        val target = view ?: return
        Handler(Looper.getMainLooper()).post {
            try {
                target.visibility = android.view.View.INVISIBLE
                ProviderLogger.i(TAG, "dipPageVisibility",
                    "Page hidden for ${POPUNDER_DWELL_MS}ms so the popunder dwell check passes")
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        target.visibility = android.view.View.VISIBLE
                        ProviderLogger.d(TAG, "dipPageVisibility", "Page visible again")
                    } catch (_: Exception) {}
                }, POPUNDER_DWELL_MS)
            } catch (e: Exception) {
                ProviderLogger.w(TAG, "dipPageVisibility", "Failed: ${e.message}")
            }
        }
    }

    /**
     * The headers Chromium adds **after** it hands us the request, which we therefore cannot copy.
     *
     * `WebResourceRequest.getRequestHeaders()` is a snapshot taken before the network stack runs. The
     * fetch-metadata set (`Sec-Fetch-Site`, `-Mode`, `-Dest`, `-User`) and `Accept-Language` are added
     * downstream of that snapshot, so a re-issue built by copying `requestHeaders` and adding
     * `sec-ch-ua` produces a request that is missing every one of them. Every real browser sends them
     * on every request; nothing else does. It is a cheaper bot check than a TLS fingerprint and a
     * server can do it in one line of PHP.
     *
     * Found on 2026-08-05: with the mint call finally answering, the surf reached
     * `cimanow.cc/<slug>/watching/?token=…` — and our re-issue of that main frame came back **403**
     * with cimanow's own Arabic block page (`<title>ستوب! المخرج`), `cloudflare=false`, on a token
     * minted 300 ms earlier and never used. The engine then fell through to Chromium, whose request
     * carries the right fetch metadata but the wrong `sec-ch-ua`, and that load produced two
     * subresources — both Cloudflare-injected — i.e. the block page again.
     *
     * `Sec-Fetch-Site` matters most here and is worth getting right rather than hard-coding: the
     * legitimate path into this page *is* a cross-site link from the freex countdown, so `cross-site`
     * is both the honest value and the one the gate is looking for. It is computed from the Referer
     * against the target, so a same-origin re-issue still says `same-origin`.
     *
     * `Sec-Fetch-User: ?1` goes on main-frame navigations only. Strictly it marks a navigation with
     * user activation, and ours is script-initiated (`location.href` from the capture hook) — but the
     * flow it stands in for is the user tapping the watch button, which is exactly the request the
     * site expects to serve.
     *
     * **`Accept-Encoding` is deliberately not touched.** `HttpURLConnection` sets `gzip` itself and
     * decompresses transparently only while it owns the header; setting it by hand to Chrome's
     * `gzip, deflate, br, zstd` hands back a body we cannot decode.
     *
     * Nothing here overwrites a header the caller already set.
     */
    private fun applyBrowserFetchHeaders(
        conn: java.net.HttpURLConnection,
        requestUrl: String,
        referer: String?,
        isMainFrame: Boolean,
        path: String,
        hasOriginHeader: Boolean
    ) {
        fun absent(name: String) = conn.getRequestProperty(name).isNullOrBlank()

        if (absent("Accept-Language")) {
            val loc = java.util.Locale.getDefault()
            val tag = if (loc.country.isNullOrBlank()) loc.language else "${loc.language}-${loc.country}"
            val header = if (loc.language.equals("en", true)) "$tag,${loc.language};q=0.9"
            else "$tag,${loc.language};q=0.9,en-US;q=0.8,en;q=0.7"
            conn.setRequestProperty("Accept-Language", header)
        }

        // Approximate registrable domain: the last two labels. A full public-suffix list is overkill
        // for distinguishing `cimanow.cc` from `freex2line.online`, and being wrong on a rare
        // multi-part TLD only downgrades `same-site` to `cross-site`.
        fun originOf(u: String): Triple<String, String, Int>? = try {
            val x = java.net.URI(u)
            if (x.host == null) null else Triple(x.scheme ?: "", x.host.lowercase(), x.port)
        } catch (_: Exception) { null }
        fun registrable(host: String) = host.split('.').takeLast(2).joinToString(".")

        if (absent("Sec-Fetch-Site")) {
            val here = originOf(requestUrl)
            val from = referer?.takeIf { it.isNotBlank() }?.let { originOf(it) }
            val site = when {
                from == null || here == null -> "none"
                from == here -> "same-origin"
                registrable(from.second) == registrable(here.second) -> "same-site"
                else -> "cross-site"
            }
            conn.setRequestProperty("Sec-Fetch-Site", site)
        }

        if (isMainFrame) {
            if (absent("Sec-Fetch-Mode")) conn.setRequestProperty("Sec-Fetch-Mode", "navigate")
            if (absent("Sec-Fetch-Dest")) conn.setRequestProperty("Sec-Fetch-Dest", "document")
            if (absent("Sec-Fetch-User")) conn.setRequestProperty("Sec-Fetch-User", "?1")
            return
        }

        // Subresources. `Dest` comes from the extension because that is what the tag that requested it
        // implies; `Mode` follows from whether Chromium attached an `Origin`, which it does for CORS
        // requests and not for a plain `<script src>` or `<img>`.
        val p = path.substringBefore('?').lowercase()
        val dest = when {
            p.endsWith(".js") || p.endsWith(".mjs") -> "script"
            p.endsWith(".css") -> "style"
            p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg") ||
                p.endsWith(".gif") || p.endsWith(".webp") || p.endsWith(".avif") ||
                p.endsWith(".svg") || p.endsWith(".ico") -> "image"
            p.endsWith(".woff") || p.endsWith(".woff2") || p.endsWith(".ttf") || p.endsWith(".otf") -> "font"
            else -> "empty"
        }
        if (absent("Sec-Fetch-Dest")) conn.setRequestProperty("Sec-Fetch-Dest", dest)
        if (absent("Sec-Fetch-Mode")) {
            conn.setRequestProperty(
                "Sec-Fetch-Mode",
                if (hasOriginHeader || dest == "empty" || dest == "font") "cors" else "no-cors"
            )
        }
    }

    private fun fetchEmbedDocument(
        url: String,
        reqHeaders: Map<String, String>,
        userAgent: String
    ): Pair<String, WebResourceResponse>? {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            reqHeaders.forEach { (key, value) ->
                if (!key.equals("X-Requested-With", true) &&
                    !key.equals("sec-ch-ua", true) &&
                    !key.equals("sec-ch-ua-mobile", true) &&
                    !key.equals("sec-ch-ua-platform", true)
                ) {
                    conn.setRequestProperty(key, value)
                }
            }
            conn.setRequestProperty("User-Agent", userAgent)
            val chromeVersion = Regex("""Chrome/(\d+)""").find(userAgent)?.groupValues?.getOrNull(1) ?: "131"
            conn.setRequestProperty(
                "sec-ch-ua",
                "\"Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"$chromeVersion\", \"Chromium\";v=\"$chromeVersion\""
            )
            conn.setRequestProperty("sec-ch-ua-mobile", "?1")
            conn.setRequestProperty("sec-ch-ua-platform", "\"Android\"")
            // The whole reason this works: it really is an iframe request.
            conn.setRequestProperty("Sec-Fetch-Dest", "iframe")
            conn.setRequestProperty("Sec-Fetch-Mode", "navigate")
            conn.setRequestProperty("Sec-Fetch-Site", "cross-site")
            try {
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                    conn.setRequestProperty("Cookie", it)
                }
            } catch (_: Exception) {}

            val code = conn.responseCode
            if (code != 200) {
                ProviderLogger.w(TAG, "fetchEmbedDocument",
                    "Embed fetch non-200 — leaving it to the WebView", "code" to code, "url" to url.take(100))
                return null
            }

            // Because we answered the request instead of Chromium, its cookie jar never sees this
            // response's Set-Cookie — and the iframe's own subresources (player JS, API calls) may need
            // the session it just established. Put them back by hand so the served response behaves
            // like a real one.
            try {
                val cm = CookieManager.getInstance()
                conn.headerFields?.forEach { (name, values) ->
                    if (name != null && name.equals("Set-Cookie", ignoreCase = true)) {
                        values.forEach { cm.setCookie(url, it) }
                    }
                }
            } catch (e: Exception) {
                ProviderLogger.w(TAG, "fetchEmbedDocument", "Set-Cookie propagation failed: ${e.message}")
            }

            val ct = conn.contentType ?: "text/html"
            val mime = ct.substringBefore(";").trim().ifBlank { "text/html" }
            val encodingStr = ct.substringAfter("charset=", "utf-8").trim()
            val charset = try { Charset.forName(encodingStr) } catch (_: Exception) { Charsets.UTF_8 }
            val body = conn.inputStream.bufferedReader(charset).readText()

            body to WebResourceResponse(
                mime, charset.name(), java.io.ByteArrayInputStream(body.toByteArray(charset))
            )
        } catch (e: Exception) {
            ProviderLogger.w(TAG, "fetchEmbedDocument",
                "Embed fetch failed — leaving it to the WebView", "error" to e.message, "url" to url.take(100))
            null
        }
    }

    private fun setupWebViewClient(
        webView: WebView,
        userAgent: String,
        requestInterceptor: ((WebView, WebResourceRequest) -> WebResourceResponse?)?,
        allowedDomains: Set<String> = emptySet(),
        destinationLockPatterns: List<Regex> = emptyList(),
        injectSpoofingJs: Boolean = true,
        loadPopupsInSink: Boolean = false,
        captureEmbeds: Boolean = false,
        rewriteDocumentWrite: Boolean = true,
        injectDocumentWriteHook: Boolean = true,
        sessionPolicy: NavigationSessionPolicy = NavigationSessionPolicy.None,
        promotePopupsMatching: Regex? = null,
        popupSinkTtlMs: Long = POPUP_SINK_TTL_MS,
        earlyInjectJs: String? = null,
        earlyInjectOnHosts: Regex? = null
    ) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        var isOnDestination = false

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (url != null) {
                    lastPageUrl = url
                    if (mainFrameUrlsSeen.lastOrNull() != url) mainFrameUrlsSeen.add(url)
                    mainFramePageStartedAtMs = System.currentTimeMillis()
                    ProviderLogger.i(TAG, "onPageStarted", "URL=${url}")
                    if (destinationLockPatterns.any { it.containsMatchIn(url) }) {
                        if (!isOnDestination) {
                            ProviderLogger.i(TAG, "onPageStarted", "Destination lock engaged for URL matching pattern", "url" to url)
                        }
                        isOnDestination = true
                        autoApproveAllRedirects = false
                    } else if (isOnDestination) {
                        // ── The destination lock's second half, and the one that was missing ──
                        //
                        // The lock lived entirely in `shouldOverrideUrlLoading`, and **that callback is
                        // not invoked for a `<meta http-equiv="refresh">` navigation**. 2026-08-03:
                        // cimanow served a 3,755-byte `<title>Redirect</title>` stub whose meta-refresh
                        // took the main frame to an ad network 9 ms after `onPageFinished`. The log
                        // shows no `shouldOverrideUrlLoading` line for it at all — not blocked, not
                        // auto-approved, simply never seen. The lock then blocked the *ad's* own next
                        // hop, so the WebView sat parked on the ad for the remaining ~295 s.
                        //
                        // By `onPageStarted` the navigation has begun, so this cannot prevent it — but
                        // `stopLoading()` keeps the ad from finishing, and recording it lets a waiting
                        // step give up now instead of burning its whole timeout on a page that can
                        // never produce a stream.
                        ProviderLogger.w(TAG, "onPageStarted",
                            "🚫 LEFT THE DESTINATION without passing through shouldOverrideUrlLoading " +
                                "(meta-refresh or script navigation) — stopping the load",
                            "url" to url.take(140))
                        offDestinationNavigationUrl = url
                        try { view?.stopLoading() } catch (_: Exception) {}
                    }
                }
                // Host-guarded early injection — see earlyInjectJs. Before the page's own scripts,
                // which is the only moment a wrapper on XMLHttpRequest/fetch can still take effect.
                if (earlyInjectJs != null && url != null &&
                    earlyInjectOnHosts?.containsMatchIn(url) == true
                ) {
                    ProviderLogger.w(TAG, "onPageStarted",
                        "💉 Early inject on a matching host (${earlyInjectJs.length} chars) — " +
                            "capturing a response the engine cannot see",
                        "url" to url.take(110))
                    view?.evaluateJavascript(earlyInjectJs, null)
                }
                if (injectSpoofingJs) {
                    view?.evaluateJavascript(SPOOFING_JS, null)
                } else {
                    // Page context left pristine on purpose — see the injectSpoofingJs param doc.
                    ProviderLogger.i(TAG, "onPageStarted", "Spoofing JS NOT injected (pristine page context)")
                }
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null) lastPageUrl = url
                lastPageFinishedAt = System.currentTimeMillis()
                ProviderLogger.i(TAG, "onPageFinished", "URL=${url}")
                // === PAGE STATE, READ ENTIRELY FROM OUR SIDE OF THE BOUNDARY ===
                //
                // Nothing is read out of the document. There used to be three `evaluateJavascript`
                // probes here — `document.title`, `document.body.innerHTML.length` (the delta==47
                // decoy detector) and a `querySelectorAll` state dump — and all three are gone.
                //
                // They were justified as "harmless, they run after the page has decided", but that
                // is an assumption about the page's timing, not a guarantee: the decryptor is still
                // executing at `onPageFinished` (the old comment on the state probe said so
                // outright), a `MutationObserver` or a getter on `document.title` sees the read
                // whenever it happens, and `evaluateJavascript` runs on an isolated-world stack
                // that an `isBot()` stack inspection is built to notice. A diagnostic that can
                // change the outcome it measures is not a diagnostic. Handover §0.1 rules 6/7 —
                // touch nothing in the page context — now hold with no exceptions.
                //
                // What replaces them is strictly weaker and strictly safe. `capturedMainFrameHtml`
                // is the body we served, and the request counts are what the page asked for
                // afterwards. A page whose scripts really ran pulls dozens of subresources; the
                // decoy pulls almost none. That distinguishes the two outcomes the flow actually
                // branches on, without a byte of JS.
                val htmlLen = capturedMainFrameHtml?.length ?: -1
                val subresources = subresourceRequestsSinceMainFrame
                ProviderLogger.i(TAG, "onPageFinished", "📊 PAGE STATE (network-side only)",
                    "servedHtmlLen" to htmlLen.toString(),
                    "subresourceRequests" to subresources.toString(),
                    "embeds" to capturedEmbedRequests.size.toString(),
                    "streamCaptures" to capturedVideoRequests.size.toString())
                // Two different failures, and the earlier version only caught one of them.
                if (subresources < 5) {
                    if (htmlLen in 1..3_000) {
                        // 2026-08-03: 3,755 chars where a real watch page is ~4 MB. That was not the
                        // page at all — it was a `<title>Redirect</title>` interstitial with a
                        // meta-refresh to an ad. A size threshold on the *large* side missed it
                        // entirely, so it is called out from the small side too.
                        ProviderLogger.w(TAG, "onPageFinished",
                            "🚩 A very small page ($htmlLen chars) with $subresources subresource " +
                                "request(s) — this is an interstitial, an error page or a redirect " +
                                "stub, not the page you asked for. Check the served dump before " +
                                "blaming the parser.")
                    } else {
                        // The old delta==47 signature, observed from the outside: a full payload was
                        // served, and then the page asked for essentially nothing. Either the decoy,
                        // or scripts that died before they could fetch anything.
                        ProviderLogger.w(TAG, "onPageFinished",
                            "🚩 A full page was served ($htmlLen chars) but only $subresources " +
                                "subresource request(s) followed — the page's own scripts either " +
                                "never ran or took a bail-out path. Look for an automation signal, " +
                                "not a decryption bug.")
                    }
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null) return null
                val reqUrl = request.url?.toString() ?: return null
                val scheme = request.url?.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return null

                val host = request.url?.host?.lowercase() ?: ""
                val path = request.url?.path?.lowercase() ?: ""
                val reqHeaders = request.requestHeaders ?: emptyMap()
                val isMain = request.isForMainFrame

                // Feeds the JS-free "did the page's own scripts run?" signal — see the field doc.
                if (isMain) subresourceRequestsSinceMainFrame = 0
                else subresourceRequestsSinceMainFrame++

                android.util.Log.d("NavEngineNet", "shouldInterceptRequest: url=$reqUrl main=$isMain headers=${reqHeaders.entries.joinToString(", ") { "${it.key}=${it.value}" }}")

                // === COMPREHENSIVE HEADER LOG ===
                val interceptHeaderSummary = reqHeaders.entries.joinToString(", ") { "${it.key}=${it.value.take(80)}" }
                ProviderLogger.d(TAG, "shouldInterceptRequest", "REQUEST DETECTED",
                    "url" to reqUrl.take(150),
                    "main" to isMain.toString(),
                    "headers" to interceptHeaderSummary)
                android.util.Log.w("NavEngineRequest", "URL: $reqUrl\nMAIN: $isMain\nHEADERS: ${reqHeaders.entries.joinToString("\n  ") { "${it.key}=${it.value}" }}")

                // === CAPTURE ACTUAL VIDEO STREAM URLS ===
                // The WebView may auto-play the embedded video (e.g. VK CDN vkuser.net).
                // Capture these signed stream URLs so they can be returned directly as links.
                val isVideoStream = host.endsWith("vkuser.net")
                        || host.endsWith("vkontakte.ru") || host.endsWith("userapi.net")
                        || path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".ts")
                        || (host.contains("okcdn.ru") && reqUrl.contains("type="))
                        || (host.contains("vkcdn") && path.contains("video"))
                        // Catches what the path tests miss: manifests behind a query string, and the
                        // /hls/ paths some CDNs serve with a .txt or .php name.
                        || VideoUrlClassifier.isVideoUrl(reqUrl)
                // === CAPTURE PLAYER EMBEDS (worth more than the streams) ===
                //
                // A subframe asking for a document is an iframe, and on a watch page that is either an
                // ad slot or the player embed. The embed URL fed to its extractor gives the whole
                // quality ladder from the player's own params, where a sniffed stream only ever gives
                // the rendition ABR happened to pick — on VK the bottom rung, signed per rendition so
                // nothing better can be derived from it.
                //
                // Keyed on `Accept: text/html` rather than `Sec-Fetch-Dest: iframe`, because WebView
                // does not send Sec-Fetch-Dest here (2026-07-30 log). In a whole session that test
                // matched exactly two requests: the watch page itself and the VK embed.
                if (!isMain && captureEmbeds) {
                    fun header(name: String) = reqHeaders.entries
                        .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: ""

                    val accept = header("Accept")
                    val dest = header("Sec-Fetch-Dest")
                    // `Accept: text/html` alone is nowhere near enough. A Chromium
                    // speculation-rules **prefetch** sends the full navigation-style Accept, and one
                    // 2026-07-30 run captured 728 "embeds" that way — 208 of the 209 candidates were
                    // prefetched static assets (`s5.teraboxcdn.com/fe-opera-static/…`), burying the one
                    // genuine embed and grinding the resolution phase for 30s+ on a black screen.
                    //
                    // Two headers separate them cleanly, verified against that log:
                    //  - `Upgrade-Insecure-Requests: 1` is sent for real navigations only — it was on
                    //    the genuine `uqload.is/embed-….html` request and on nothing else.
                    //  - `Sec-Purpose` marks prefetch/prerender — it was on all 208 false positives.
                    val isPrefetch = header("Sec-Purpose").isNotBlank()
                    val isNavigation = dest.equals("iframe", ignoreCase = true) ||
                        dest.equals("document", ignoreCase = true) ||
                        (accept.contains("text/html", ignoreCase = true) &&
                            header("Upgrade-Insecure-Requests").isNotBlank())
                    val isXhr = header("X-Requested-With").contains("XMLHttpRequest", ignoreCase = true)
                    val isDocument = isNavigation && !isPrefetch && !isXhr
                    // Any document subframe that is not the page itself is a candidate. Deliberately
                    // NOT restricted to third-party hosts: some servers embed their player from the
                    // site's own domain, and losing those would push us back onto the sniffed stream.
                    // Ad frames are excluded by host instead, which is the distinction that matters.
                    val notThePageItself = reqUrl.substringBefore("#") != lastPageUrl.substringBefore("#")

                    if (isDocument && notThePageItself && !VideoUrlClassifier.isLikelyAdFrame(reqUrl)) {
                        if (capturedEmbedRequests.size >= MAX_CAPTURED_EMBEDS) {
                            ProviderLogger.d(TAG, "shouldInterceptRequest",
                                "Embed list full (${MAX_CAPTURED_EMBEDS}) — not capturing ${reqUrl.take(80)}")
                        } else if (capturedEmbedRequests.none { it.url == reqUrl }) {
                            // Serve the iframe ourselves so we can keep a copy of its HTML. The player
                            // params live in these bytes, and this is the only context in which the
                            // embed host will hand them over — see CapturedEmbedRequest.html.
                            val fetched = fetchEmbedDocument(reqUrl, reqHeaders, userAgent)
                            capturedEmbedRequests.add(
                                CapturedEmbedRequest(
                                    url = reqUrl,
                                    headers = reqHeaders,
                                    pageUrl = lastPageUrl,
                                    html = fetched?.first
                                )
                            )
                            ProviderLogger.i(TAG, "shouldInterceptRequest",
                                "🎯 CAPTURED EMBED: ${reqUrl.take(160)}",
                                "html" to (fetched?.first?.length ?: -1))
                            // Hand the same bytes to the WebView so the iframe still renders. On any
                            // failure fall through to null and let Chromium fetch it normally — a lost
                            // capture is recoverable, a broken iframe is not.
                            if (fetched != null) return fetched.second
                        }
                    } else if (isDocument && notThePageItself) {
                        ProviderLogger.d(TAG, "shouldInterceptRequest",
                            "Ignoring ad/consent frame: ${reqUrl.take(120)}")
                    }
                }

                // Thumbnails ride the same hosts as the stream and are fetched first, so capturing one
                // as "the video" hands a JPEG to the player. See VideoUrlClassifier.isPreviewAsset.
                if (isVideoStream && VideoUrlClassifier.isPreviewAsset(reqUrl)) {
                    ProviderLogger.d(TAG, "shouldInterceptRequest",
                        "Skipping preview/thumbnail on a video host: ${reqUrl.take(120)}")
                } else if (isVideoStream) {
                    // Strip byte-range param so we keep the canonical URL (range requests are the same file)
                    val clean = reqUrl.substringBefore("&bytes=")
                    if (!capturedVideoUrls.contains(clean)) {
                        capturedVideoUrls.add(clean)
                        // Kept with its headers: a tokenised CDN checks the Referer/Origin the embed
                        // used, and any value reconstructed later is a guess.
                        capturedVideoRequests.add(
                            CapturedVideoRequest(
                                url = clean,
                                headers = reqHeaders,
                                pageUrl = lastPageUrl
                            )
                        )
                        ProviderLogger.i(TAG, "shouldInterceptRequest", "🎬 CAPTURED VIDEO URL: ${clean.take(160)}")
                    }
                }

                // NEVER intercept Cloudflare challenge scripts — they must execute in the
                // original WebView context to properly solve the JS challenge and set cookies.
                val isCfChallenge = path.contains("/cdn-cgi/")
                val isFreeDomain = host.contains("freex2line.online")
                val isCimaDomain = host.contains("cimanow.cc")
                val isProtectedDomain = isFreeDomain || isCimaDomain

                // Detect CDN scripts loaded via document.write that Chrome may block
                // (jquery-cookie, sweetalert2, lazyload on cdnjs.cloudflare.com / cdn.jsdelivr.net)
                val requiresInterventionBypass = (host == "cdnjs.cloudflare.com" || host == "cdn.jsdelivr.net")
                    && (path.endsWith(".js") || path.endsWith(".css")) && !isCfChallenge

                // CRITICAL: Never intercept Cloudflare challenge scripts — they must execute in the
                // original WebView context to properly solve the JS challenge and set cookies.
                // Intercept main-frame for ALL protected domains (including cimanow.cc) to
                // spoof sec-ch-ua headers and hide the WebView fingerprint. Without this,
                // cimanow.cc detects sec-ch-ua="Android WebView" and redirects to home.
                if (request.isForMainFrame && (!isProtectedDomain || reqUrl.contains("/cdn-cgi/"))) return null

                // An automation WebView never displays a favicon, so fetching one is pure cost — and
                // it is the one request whose response Chromium hands to its icon database, decoding
                // the bytes and re-compressing them through a pipe on another thread. That pipe
                // outlives the response stream we hand back here, which is where the stray
                // "java.io.IOException: Pipe closed / Bitmap.compress" traces come from: harmless in
                // itself, but noise in every log and a stream whose lifetime we do not control.
                // Answering with an empty icon ends the whole path.
                if (path.endsWith("/favicon.ico", ignoreCase = true)) {
                    return WebResourceResponse(
                        "image/x-icon", "utf-8",
                        java.io.ByteArrayInputStream(ByteArray(0))
                    )
                }

                // ── Never intercept anything but a GET ──────────────────────────────────────────
                //
                // `WebResourceRequest` exposes the method and the headers but **not the body**, and
                // `HttpURLConnection` here is never told a method, so it defaults to GET. Every
                // intercepted POST was therefore re-issued as a bodyless GET with the original
                // `Content-Type: multipart/form-data; boundary=…` still attached — a request no
                // browser produces, missing exactly the data that gave it meaning.
                //
                // 2026-08-03, and it cost a whole flow: cimanow's countdown calls `get-link.php` as a
                // multipart POST. Stripped of its form fields it answered with the bare, **tokenless**
                // `https://cimanow.cc/pig/watching/` (32 chars, no `?token=`), cimanow served its ad
                // interstitial for that instead of the player — `<title>Redirect</title>`, 3,755 bytes,
                // a meta-refresh to an ad network — and the user got a white screen for 300 s.
                //
                // Chromium can issue it correctly, body and all; we cannot. It will carry
                // `sec-ch-ua: "Android WebView"`, which matters for a *protected main frame* and has
                // never mattered for an XHR to an ad host — and a fingerprint on a request that works
                // beats a clean one on a request that is silently wrong.
                val method = try { request.method ?: "GET" } catch (_: Exception) { "GET" }
                if (!method.equals("GET", ignoreCase = true)) {
                    // The link-minting endpoint gets its own line: it is the hinge of the whole flow and
                    // its response is invisible to us, so everything observable about the *request* is
                    // worth stating explicitly rather than leaving in a sea of identical skip lines.
                    if (path.contains("get-link.php")) {
                        linkMintCallCount++
                        val sincePageStart = if (mainFramePageStartedAtMs > 0L) {
                            System.currentTimeMillis() - mainFramePageStartedAtMs
                        } else -1L
                        val cookieNames = try {
                            CookieManager.getInstance().getCookie(reqUrl)
                                ?.split(";")
                                ?.mapNotNull { it.trim().substringBefore('=', "").takeIf(String::isNotBlank) }
                                ?: emptyList()
                        } catch (_: Exception) { emptyList() }
                        ProviderLogger.w(TAG, "shouldInterceptRequest",
                            "🔑 LINK MINT CALL #$linkMintCallCount ($method) — Chromium will issue this " +
                                "one; we never see the response. Watch what the page does next: an href " +
                                "that stays a placeholder means the answer was useless, not missing.",
                            "url" to reqUrl.take(140),
                            "msSincePageStart" to sincePageStart.toString(),
                            "referer" to (reqHeaders.entries
                                .firstOrNull { it.key.equals("Referer", true) }?.value?.take(90) ?: "<none>"),
                            "origin" to (reqHeaders.entries
                                .firstOrNull { it.key.equals("Origin", true) }?.value?.take(60) ?: "<none>"),
                            // Chromium strips Cookie from requestHeaders, so read the jar it will use.
                            "jarCookies" to (if (cookieNames.isEmpty()) "NONE"
                                else "${cookieNames.size}: ${cookieNames.joinToString(",")}"))
                    } else {
                        ProviderLogger.i(TAG, "shouldInterceptRequest",
                            "Not intercepting a $method — the body cannot be forwarded, so Chromium must " +
                                "issue this one itself",
                            "url" to reqUrl.take(120), "main" to isMain.toString())
                    }
                    return null
                }

                // Identify requests that will leak the package name or are blocked AJAX endpoints
                val hasLeakedHeader = reqHeaders["X-Requested-With"]?.isNotBlank() == true
                val isGetLink = path.contains("get-link.php") && !isCfChallenge
                val isAjaxEndpoint = path.contains("core.php") && !isCfChallenge
                val isScriptAsset = path.endsWith(".js") && !isCfChallenge
                val isStyleAsset = path.endsWith(".css") && !isCfChallenge

                // A copy the provider already holds, served without a single request going out.
                //
                // Deliberately the first thing tried for an asset on a protected domain. Once a site
                // blocks the address rather than the client, every fetch-based path is dead by
                // construction — and this one cannot be refused, fingerprinted or rate-limited, because
                // nothing leaves the device. It is also free: no ladder, no waiting, no MIME guessing.
                if (isProtectedDomain && (isScriptAsset || isStyleAsset)) {
                    val local = try {
                        sessionPolicy.provideLocalSubresource(reqUrl)
                    } catch (e: Exception) {
                        ProviderLogger.w(TAG, "shouldInterceptRequest",
                            "Local subresource lookup threw: ${e.message}")
                        null
                    }
                    if (local != null && local.first.isNotEmpty()) {
                        ProviderLogger.i(TAG, "shouldInterceptRequest",
                            "📦 Served the provider's local copy — no request made",
                            "url" to reqUrl.takeLast(70),
                            "bytes" to local.first.length.toString(),
                            "mime" to local.second)
                        return WebResourceResponse(
                            local.second, "utf-8",
                            java.io.ByteArrayInputStream(local.first.toByteArray(Charsets.UTF_8))
                        )
                    }
                }

                // cimanow's stylesheet is deliberately NOT routed through the interception ladder below.
                //
                // Verified 2026-08-05 against `/wp-content/themes/Cima Now New/Assets/…`: from this
                // device, nothing can fetch it. `HttpURLConnection`, OkHttp, and — decisively — an in-page
                // `fetch()` from a real WebView on cimanow's own origin all receive the site's own 403
                // page (127 KB, `<title>ستوب! المخرج عايز كدة</title>`, `cache-control: private,
                // no-store`). From a clean network every one of those clients gets 200 with the correct
                // `text/css`, cache HIT and MISS alike. The variable is the egress IP, not the client:
                // this device sits behind a VPN range that cimanow's WAF refuses for static theme assets.
                //
                // So interception here had exactly one outcome: ~1.2 s per file spent walking
                // intercept → 403 → retry → 403 → provider → 403, and then Chromium inherits the attempt
                // anyway. Handing it straight to Chromium drops three doomed requests per stylesheet and
                // keeps the best client available, at no cost when it fails too.
                //
                // Scope is deliberately narrow. `.js` is untouched, so the watch page's scripts stay on
                // the exact path that produces working streams. freex2line's `.css` is untouched too — its
                // timer page intercepts cleanly today (`INTERCEPTED …/styleMob.css (text/css)`).
                val isAsset = isScriptAsset || (isStyleAsset && !isCimaDomain)

                // Intercept get-link.php with spoofed headers so the page's JS gets the
                // watching URL. Also intercept assets, AJAX calls, header leaks, and
                // main-frame requests for protected domains to clean headers.
                // Also intercept CDN scripts (cdnjs.cloudflare.com, cdn.jsdelivr.net) that are
                // loaded via document.write — Chrome blocks cross-origin document.write in
                // WebView, breaking the server list extraction.
                if ((isProtectedDomain || requiresInterventionBypass) && (isGetLink || isAsset || isAjaxEndpoint || hasLeakedHeader || request.isForMainFrame)) {
                    // Explicit confirmation of the Referer the WebView sent for the watching page
                    // request. A wrong/blank Referer (e.g. about:blank) causes cimanow.cc to
                    // redirect /watching/ -> /home.
                    if (request.isForMainFrame && isCimaDomain) {
                        val webViewReferer = reqHeaders["Referer"]
                        ProviderLogger.w(TAG, "shouldInterceptRequest",
                            "WATCHING PAGE main-frame | Referer from WebView = ${webViewReferer ?: "<none>"}")
                    }
                    try {
                        val conn = java.net.URL(reqUrl).openConnection() as java.net.HttpURLConnection
                        // Follow redirects internally so we get the final content from the
                        // redirect target (e.g. blog-post.html → blog-post.html/). Our spoofed
                        // sec-ch-ua headers stay on the connection through the redirect chain,
                        // so Cloudflare doesn't block the redirected request. The WebView's URL
                        // tracker stays at the original URL (no trailing slash), but our regex
                        // `blog-post\.html(/|$|\?)` matches both forms.
                        conn.instanceFollowRedirects = true

                        // Copy all headers EXCEPT X-Requested-With and the sec-ch-ua fingerprint headers
                        // (we override these below to mask that we're a WebView)
                        reqHeaders.forEach { (key, value) ->
                            if (!key.equals("X-Requested-With", true) &&
                                !key.equals("sec-ch-ua", true) &&
                                !key.equals("sec-ch-ua-mobile", true) &&
                                !key.equals("sec-ch-ua-platform", true)) {
                                conn.setRequestProperty(key, value)
                            }
                        }

                        // NOT set at all — deliberately.
                        //
                        // It used to be set to "" to overwrite the package name WebView leaks. That
                        // reasoning does not apply here: this is our own HttpURLConnection, which
                        // never adds the header, so setting it empty only produces `X-Requested-With:`
                        // with no value — a header no real Chrome ever sends, i.e. we replaced a
                        // package-name leak with an automation signature. Omitting it is what a
                        // browser does. (Seen in the 2026-07-30 log as `x-requested-with=` on the
                        // watching request.)

                        // CRITICAL: Set a proper browser User-Agent — HttpURLConnection defaults to "Java/1.x"
                        conn.setRequestProperty("User-Agent", userAgent)

                        // SPOOF sec-ch-ua headers to look like a real Chrome browser, not a WebView.
                        // Cloudflare fingerprints "Android WebView" in sec-ch-ua and blocks it.
                        // Use the actual Chrome version from the User-Agent to keep headers consistent.
                        val chromeVersion = Regex("""Chrome/(\d+)""").find(userAgent)?.groupValues?.getOrNull(1) ?: "131"
                        conn.setRequestProperty("sec-ch-ua", "\"Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"$chromeVersion\", \"Chromium\";v=\"$chromeVersion\"")
                        conn.setRequestProperty("sec-ch-ua-mobile", "?1")
                        conn.setRequestProperty("sec-ch-ua-platform", "\"Android\"")

                        // Set Referer if the original request had one (anti-hotlink protection)
                        val originalReferer = reqHeaders["Referer"]
                        if (!originalReferer.isNullOrBlank()) {
                            conn.setRequestProperty("Referer", originalReferer)
                        }

                        // Standard browser accept header — use HTML accept for main-frame, */* for assets
                        if (request.isForMainFrame) {
                            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                        } else {
                            conn.setRequestProperty("Accept", "*/*")
                        }

                        // Pass the Cloudflare cookie that the main frame acquired
                        val cookies = CookieManager.getInstance().getCookie(reqUrl)
                        if (!cookies.isNullOrBlank()) {
                            conn.setRequestProperty("Cookie", cookies)
                        }

                        // Sec-Fetch-* and Accept-Language — the headers Chromium adds after the
                        // snapshot above, so copying `requestHeaders` can never produce them. This is
                        // what the watching page's 403 turned out to be. See applyBrowserFetchHeaders.
                        applyBrowserFetchHeaders(
                            conn = conn,
                            requestUrl = reqUrl,
                            referer = originalReferer,
                            isMainFrame = request.isForMainFrame,
                            path = path,
                            hasOriginHeader = !reqHeaders["Origin"].isNullOrBlank()
                        )

                        conn.connectTimeout = 15000
                        conn.readTimeout = 15000

                        // Exactly what this connection is about to send, as we set it.
                        //
                        // Every hypothesis about this 403 has been checked by replaying my *guess* at
                        // our header set through curl, and every guess returned 200. Guessing is the
                        // problem: `WebResourceRequest.requestHeaders` shows what Chromium told us, not
                        // what we then built, and the two are not the same object. So print ours.
                        if (isProtectedDomain && !isMain) {
                            try {
                                val mine = conn.requestProperties.entries
                                    .joinToString(" | ") { "${it.key}: ${it.value.joinToString(",").take(90)}" }
                                ProviderLogger.i(TAG, "shouldInterceptRequest",
                                    "📤 OUR REQUEST for ${path.takeLast(40)} → $mine")
                            } catch (_: Exception) {}
                        }

                        val code = conn.responseCode

                        // Hand the response's cookies to the provider's policy BEFORE the body is
                        // served, so anything the page fetches next already carries them.
                        //
                        // Chromium's jar never sees a response we answered ourselves, so without
                        // this a session the site tried to establish on the main frame is simply
                        // lost — the embed path had this back-propagation from the start
                        // (`fetchEmbedDocument`) and this one never did. Default policy is a no-op,
                        // so nothing changes for a provider that has not opted in.
                        try {
                            val setCookies = conn.headerFields?.entries
                                ?.firstOrNull { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                                ?.value
                                ?: emptyList()
                            if (setCookies.isNotEmpty()) {
                                sessionPolicy.onInterceptedResponseCookies(reqUrl, setCookies, isMain)
                            }
                        } catch (e: Exception) {
                            ProviderLogger.w(TAG, "shouldInterceptRequest",
                                "Set-Cookie policy hook threw: ${e.message}")
                        }

                        if (code == 200) {
                            val ct = conn.contentType ?: "application/octet-stream"
                            val reportedMime = ct.substringBefore(";").trim()
                            val encodingStr = ct.substringAfter("charset=", "utf-8").trim()
                            val charset = try { Charset.forName(encodingStr) } catch (e: Exception) { Charsets.UTF_8 }

                            // Special handling for get-link.php — capture the watching URL
                            // from the response body, then return it so the page's JS can
                            // update #downloadbtn.href and navigate the main frame.
                            if (isGetLink) {
                                val body = try { conn.inputStream.bufferedReader(charset).readText() } catch (_: Exception) { "" }
                                // Strip UTF-8 BOM (U+FEFF / EF BB BF) that some servers prepend
                                val cleanBody = body.trimStart('\uFEFF').trimStart('\u00BB').trim()
                                if (cleanBody.isNotBlank() && (cleanBody.startsWith("http://") || cleanBody.startsWith("https://"))) {
                                    interceptedWatchingUrl = cleanBody
                                    ProviderLogger.w(TAG, "shouldInterceptRequest", "✅ Captured watching URL: ${cleanBody}",
                                        "rawPrefix" to body.take(20).replace("\uFEFF", "{BOM}").replace("\u00BB", "{»}"),
                                        "length" to cleanBody.length.toString())
                                } else {
                                    ProviderLogger.w(TAG, "shouldInterceptRequest", "⚠️ get-link.php response is not a URL: ${body.take(120)}")
                                }
                                val bodyBytes = body.toByteArray(charset)
                                return WebResourceResponse("text/plain", charset.name(), 200, "OK", emptyMap(), java.io.ByteArrayInputStream(bodyBytes))
                            }

                            // Override wrong MIME types — server may return text/html for JS/CSS
                            // to block scrapers. Force correct type based on file extension.
                            val mime = when {
                                reportedMime == "text/html" && path.endsWith(".js") -> "application/javascript"
                                reportedMime == "text/html" && path.endsWith(".css") -> "text/css"
                                reportedMime == "text/html" && path.endsWith(".json") -> "application/json"
                                reportedMime == "text/html" && path.endsWith(".svg") -> "image/svg+xml"
                                reportedMime == "text/html" && path.endsWith(".woff2") -> "font/woff2"
                                reportedMime == "text/html" && path.endsWith(".woff") -> "font/woff"
                                reportedMime == "text/html" && path.endsWith(".png") -> "image/png"
                                reportedMime == "text/html" && path.endsWith(".jpg") -> "image/jpeg"
                                reportedMime == "text/html" && path.endsWith(".jpeg") -> "image/jpeg"
                                reportedMime == "text/html" && path.endsWith(".gif") -> "image/gif"
                                reportedMime == "text/html" && path.endsWith(".webp") -> "image/webp"
                                else -> reportedMime
                            }

                            val mimeLog = if (mime != reportedMime) "$reportedMime -> $mime" else mime
                            ProviderLogger.d(TAG, "shouldInterceptRequest", "INTERCEPTED ${reqUrl.take(80)} ($mimeLog)")

                            // Detect CDN scripts that were previously loaded via document.write
                            if (requiresInterventionBypass) {
                                ProviderLogger.w(TAG, "shouldInterceptRequest", "CDN bypass: ${reqUrl.take(80)}")
                            }

                            // ⚠️ IDENTITY LEAK, by design for now: these interception fetches use
                            // HttpURLConnection, so they do NOT honour ProviderHttpService.dnsPolicy()
                            // (preferIpv4 / preferIpv6) or the OkHttp cookie jar. Any provider that
                            // combines a DNS policy with this engine has a split identity: page
                            // fetches exit one way, WebView-served requests another. Harmless while
                            // no such provider uses it (only FaselHD sets a policy, and it has its
                            // own extractor), but if that changes, route these through the shared
                            // OkHttp client — a site that pins tokens to the caller's IP will 403
                            // otherwise. See PreferIpv4Dns for the failure mode.
                            //
                            // Rewrite HTML for cimanow.cc main-frame: replace document.write('<script src="...")>
                            // and document.write('<link rel="stylesheet" href="...")> with direct tags
                            // to bypass Chrome's cross-origin document.write intervention
                            // (which blocks sweetalert2, jquery-cookie, lazyload).
                            val bodyStream: java.io.InputStream = if (request.isForMainFrame && isCimaDomain && mime == "text/html") {
                                val html = conn.inputStream.bufferedReader(charset).readText()
                                // Profi #5: Capture the raw server-rendered HTML before any
                                // anti-bot JS can clear/patch it. This is parsed in Kotlin
                                // after execute() completes, bypassing the WebView JS entirely.
                                this@NavigationEngine.capturedMainFrameHtml = html
                                // Keep the exact bytes we hand the WebView. The blanking is page-side
                                // and invisible on the wire, so the only way to stop guessing at it is
                                // to read what the page was given: the `#xqeqjp` hrefs (an empty one
                                // makes the site run `$("main article ul.btns li").remove()` and empty
                                // its own UI) and whether the rewrite had anything to match.
                                try {
                                    activityProvider()?.let { ctx ->
                                        val dir = ctx.externalCacheDir ?: ctx.cacheDir
                                        dir.mkdirs()
                                        val f = java.io.File(dir, "watchpage_served.html")
                                        f.writeText(html)
                                        ProviderLogger.w(TAG, "shouldInterceptRequest",
                                            "📄 WATCH PAGE DUMP: ${f.absolutePath} (${html.length} chars)")
                                    }
                                } catch (e: Exception) {
                                    ProviderLogger.w(TAG, "shouldInterceptRequest",
                                        "Watch page dump failed: ${e.message}")
                                }
                                if (!rewriteDocumentWrite) {
                                    // Serve the page exactly as the server sent it. Nothing rewritten,
                                    // nothing injected — see the rewriteDocumentWrite doc.
                                    //
                                    // === DIAGNOSTIC: understand what the page looks like without rewriting ===
                                    val dwScriptPattern = Regex("""document\.write\s*\(\s*'<script[^>]*src=["']([^"']+)["'][^>]*><\\/script>\s*'\s*\)""")
                                    val dwLinkPattern = Regex("""document\.write\s*\(\s*'<link[^>]*href=["']([^"']+)["'][^>]*>\s*'\s*\)""")
                                    val dwScriptMatches = dwScriptPattern.findAll(html).toList()
                                    val dwLinkMatches = dwLinkPattern.findAll(html).toList()
                                    val dwTotal = dwScriptMatches.size + dwLinkMatches.size
                                    val scriptBlockCount = Regex("""<script[\s>]""", RegexOption.IGNORE_CASE).findAll(html).count()
                                    ProviderLogger.i(TAG, "shouldInterceptRequest",
                                        "Serving cimanow.cc main-frame verbatim (${html.length} chars) — " +
                                            "no document.write rewrite, no interceptor injected | " +
                                            "scriptBlocks=$scriptBlockCount, dwCalls=$dwTotal " +
                                            "(${dwScriptMatches.size} script + ${dwLinkMatches.size} link)")
                                    // Show context around each document.write match so we can see whether
                                    // they're in standalone <script> blocks or inside larger scripts
                                    for ((idx, m) in dwScriptMatches.withIndex()) {
                                        val start = (m.range.first - 120).coerceAtLeast(0)
                                        val end = (m.range.last + 120).coerceAtMost(html.length - 1)
                                        val context = html.substring(start, end + 1)
                                            .replace("\n", "↵").replace("\r", "")
                                        val src = m.groupValues.getOrElse(1) { "?" }
                                        ProviderLogger.w(TAG, "shouldInterceptRequest",
                                            "📝 DW_SCRIPT[$idx] src=${src.takeLast(80)} | context=${context.take(300)}")
                                    }
                                    for ((idx, m) in dwLinkMatches.withIndex()) {
                                        val start = (m.range.first - 120).coerceAtLeast(0)
                                        val end = (m.range.last + 120).coerceAtMost(html.length - 1)
                                        val context = html.substring(start, end + 1)
                                            .replace("\n", "↵").replace("\r", "")
                                        val href = m.groupValues.getOrElse(1) { "?" }
                                        ProviderLogger.w(TAG, "shouldInterceptRequest",
                                            "📝 DW_LINK[$idx] href=${href.takeLast(80)} | context=${context.take(300)}")
                                    }
                                    // Log the first 500 chars to see if the page starts with a BOM, doctype, or something unexpected
                                    ProviderLogger.d(TAG, "shouldInterceptRequest",
                                        "📄 HTML HEAD: ${html.take(500).replace("\n", "↵").replace("\r", "")}")
                                    return WebResourceResponse(
                                        mime, charset.name(),
                                        java.io.ByteArrayInputStream(html.toByteArray(charset))
                                    )
                                }
                                val scriptCount = Regex("""document\.write\s*\(\s*'<script[^>]*src=["']([^"']+)["'][^>]*><\\/script>\s*'\s*\)""").findAll(html).count()
                                val linkCount = Regex("""document\.write\s*\(\s*'<link[^>]*href=["']([^"']+)["'][^>]*>\s*'\s*\)""").findAll(html).count()
                                var rewritten = html.replace(
                                    Regex("""document\.write\s*\(\s*'<script[^>]*src=["']([^"']+)["'][^>]*><\\/script>\s*'\s*\)"""),
                                    """<script src="$1"></script>"""
                                )
                                rewritten = rewritten.replace(
                                    Regex("""document\.write\s*\(\s*'<link[^>]*href=["']([^"']+)["'][^>]*>\s*'\s*\)"""),
                                    """<link rel="stylesheet" href="$1">"""
                                )
                                // The document.write interceptor is injected ONLY when there is
                                // actually a document.write to intercept.
                                //
                                // It wraps document.write, and the watch page's gate aborts —
                                // emitting nothing — when
                                // `Function.prototype.toString.call(document.write)` no longer
                                // contains "[native code]" (handover §0.1 rule 3). On this page the
                                // rewrite finds nothing to rewrite ("no document.write found",
                                // 2026-07-29), so injecting it was pure tripwire for zero benefit —
                                // and it ran against the REAL cimanow.cc in the navigation WebView,
                                // in the same session and from the same IP as the sandbox that runs
                                // moments later. Being flagged there would explain a decoy that no
                                // change inside the sandbox can shift.
                                val total = scriptCount + linkCount
                                val injected = if (total > 0 && !injectDocumentWriteHook) {
                                    ProviderLogger.w(TAG, "shouldInterceptRequest",
                                        "🔧 REWRITE: $total document.write call(s) → direct tags; " +
                                            "hook NOT injected (document.write stays native)")
                                    rewritten
                                } else if (total > 0) {
                                    val antiBotTag = "<script>$ANTI_ANTI_BOT_JS</script>"
                                    ProviderLogger.w(TAG, "shouldInterceptRequest",
                                        "Injected document.write interceptor for cimanow.cc main-frame " +
                                            "(${antiBotTag.length} chars) — rewrote $total document.write call(s)")
                                    "$antiBotTag$rewritten"
                                } else {
                                    ProviderLogger.w(TAG, "shouldInterceptRequest",
                                        "🔧 REWRITE: nothing to rewrite on this page (0 matches) — " +
                                            "document.write stays native, nothing injected")
                                    rewritten
                                }
                                ProviderLogger.d(TAG, "shouldInterceptRequest", "HTML ${html.length} chars for cimanow.cc main-frame")
                                java.io.ByteArrayInputStream(injected.toByteArray(charset))
                            } else if (isCimaDomain && path.endsWith(".js")) {
                                // Keep a copy of the site's own scripts on disk.
                                //
                                // The gate/ad logic lives in a rotating-name script under the theme's
                                // Assets/js/ (currently 0CYA6X1KhKIS.js), and reading it is the only way
                                // to find out what raises the "allow redirection and popups" modal
                                // without injecting anything into the page — which is forbidden here and
                                // is what produced the decoy (§0.1 rule 17).
                                //
                                // It cannot be fetched from a shell: cimanow sits behind Cloudflare,
                                // which stalls a non-browser TLS fingerprint (plain curl hangs). This
                                // WebView session already has the fingerprint, the cookies and the
                                // clearance, so the copy is taken here instead.
                                val js = conn.inputStream.bufferedReader(charset).readText()
                                try {
                                    activityProvider()?.let { ctx ->
                                        val name = path.substringAfterLast('/').ifBlank { "script.js" }
                                        val dir = ctx.externalCacheDir ?: ctx.cacheDir
                                        dir.mkdirs()
                                        val f = java.io.File(dir, "cimanow_js_$name")
                                        f.writeText(js)
                                        ProviderLogger.w(TAG, "shouldInterceptRequest",
                                            "📜 SCRIPT DUMP: ${f.absolutePath} (${js.length} chars)")
                                    }
                                } catch (e: Exception) {
                                    ProviderLogger.w(TAG, "shouldInterceptRequest",
                                        "Script dump failed: ${e.message}")
                                }
                                java.io.ByteArrayInputStream(js.toByteArray(charset))
                            } else {
                                conn.inputStream
                            }
                            return WebResourceResponse(mime, charset.name(), bodyStream)
                        } else {
                            // Record the refusal, then behave exactly as before: fall through to
                            // Chromium.
                            //
                            // Falling through is not a good outcome and never was — Chromium reissues
                            // the request with `sec-ch-ua: "Android WebView"`, which is the
                            // fingerprint this whole interception exists to avoid, so a challenge
                            // degrades into the one request shape known not to work. But solving it
                            // here would mean opening a second WebView on top of the one already on
                            // screen, and the engine cannot know whether the caller would rather
                            // retry, fall back, or give up. So it reports and lets the provider act
                            // once WebView lifecycles are no longer overlapping.
                            //
                            // ── One plain retry before giving up on a static asset ──
                            //
                            // A 403 here is not reproducible by hand: measured on-device with curl, the
                            // same asset URL returns 200 for every header combination this engine sends
                            // — plain, with Referer, with the spoofed sec-ch-ua trio, with the CSS
                            // Accept, with gzip-only, forced HTTP/1.1. So the trigger is not a header;
                            // it is either Android's HttpURLConnection TLS fingerprint or the fact that
                            // these four assets go out as a parallel burst. The main frame 403'ing twice
                            // and then succeeding points at the burst.
                            //
                            // Either way it is worth one more try with nothing but a browser's minimum,
                            // because falling through is not a neutral outcome: Chromium then fetches
                            // the asset itself and cimanow serves `.js`/`.css` as `text/html`, so strict
                            // MIME checking refuses it. That is how jQuery went missing, which left the
                            // watch page unstyled and its lazy-loaded player needing a manual scroll.
                            if (code == 403 && isProtectedDomain && !isMain) {
                                // One-shot: ask an echo service what our client actually puts on the
                                // wire. Every theory about this 403 has been about headers we cannot
                                // see — WebView adding a package name below the API surface, an empty
                                // value we set ourselves, client hints. Guessing has cost days; the
                                // echo answers it in one request, built by this same code so the answer
                                // describes the real client and not a test harness.
                                if (!echoDiagnosticDone) {
                                    echoDiagnosticDone = true
                                    try {
                                        val echo = java.net.URL(HEADER_ECHO_URL)
                                            .openConnection() as java.net.HttpURLConnection
                                        echo.setRequestProperty("User-Agent", userAgent)
                                        echo.setRequestProperty("Accept", "*/*")
                                        echo.connectTimeout = 8000
                                        echo.readTimeout = 8000
                                        val seen = echo.inputStream.bufferedReader().use { it.readText() }
                                        ProviderLogger.w(TAG, "shouldInterceptRequest",
                                            "🪞 ECHO — every header this app's HttpURLConnection " +
                                                "actually emits, as the server receives them: " +
                                                seen.replace(Regex("\\s+"), " ").take(900))
                                    } catch (e: Exception) {
                                        ProviderLogger.w(TAG, "shouldInterceptRequest",
                                            "Header echo failed: ${e.message}")
                                    }
                                }
                                try {
                                    Thread.sleep(ASSET_RETRY_DELAY_MS)
                                    val retry = java.net.URL(reqUrl).openConnection() as java.net.HttpURLConnection
                                    retry.instanceFollowRedirects = true
                                    retry.setRequestProperty("User-Agent", userAgent)
                                    reqHeaders["Referer"]?.let { retry.setRequestProperty("Referer", it) }
                                    retry.setRequestProperty("Accept", "*/*")
                                    retry.connectTimeout = 15000
                                    retry.readTimeout = 15000
                                    val retryCode = retry.responseCode
                                    if (retryCode == 200) {
                                        val ct2 = retry.contentType ?: "application/octet-stream"
                                        val reported2 = ct2.substringBefore(";").trim()
                                        val enc2 = ct2.substringAfter("charset=", "utf-8").trim()
                                        val cs2 = try { Charset.forName(enc2) } catch (_: Exception) { Charsets.UTF_8 }
                                        val fixed2 = when {
                                            path.endsWith(".js") -> "application/javascript"
                                            path.endsWith(".css") -> "text/css"
                                            path.endsWith(".json") -> "application/json"
                                            else -> reported2
                                        }
                                        ProviderLogger.i(TAG, "shouldInterceptRequest",
                                            "♻️ 403 on first try, 200 on a plain retry — serving it",
                                            "url" to reqUrl.take(90),
                                            "mime" to "$reported2 -> $fixed2")
                                        return WebResourceResponse(fixed2, cs2.name(), retry.inputStream)
                                    }
                                    ProviderLogger.w(TAG, "shouldInterceptRequest",
                                        "Plain retry also refused ($retryCode) — asking the provider " +
                                            "policy for the bytes",
                                        "url" to reqUrl.take(90))
                                    // Refused twice with different headers, so it is the transport
                                    // being fingerprinted, not the request. Let the provider fetch it
                                    // through a stack the site accepts (Chromium's own, in CimaNow's
                                    // case) rather than dropping to Chromium and losing MIME control.
                                    val supplied = try {
                                        sessionPolicy.fetchRefusedSubresource(reqUrl, reqHeaders["Referer"])
                                    } catch (e: Exception) {
                                        ProviderLogger.w(TAG, "shouldInterceptRequest",
                                            "Policy subresource fetch threw: ${e.message}")
                                        null
                                    }
                                    if (supplied != null && supplied.first.isNotEmpty()) {
                                        ProviderLogger.i(TAG, "shouldInterceptRequest",
                                            "🩹 Served by the provider policy after a double refusal",
                                            "url" to reqUrl.take(90),
                                            "bytes" to supplied.first.length.toString(),
                                            "mime" to supplied.second)
                                        return WebResourceResponse(
                                            supplied.second, "utf-8",
                                            java.io.ByteArrayInputStream(supplied.first.toByteArray(Charsets.UTF_8))
                                        )
                                    }
                                } catch (e: Exception) {
                                    ProviderLogger.w(TAG, "shouldInterceptRequest",
                                        "Plain retry threw: ${e.message}")
                                }
                            }

                            // The body is read only on this path, and only when the status could be a
                            // challenge, so a healthy session pays nothing.
                            val couldBeChallenge = code == 403 || code == 503 || code == 429
                            var preview = ""
                            var isCf = false
                            if (couldBeChallenge) {
                                preview = try {
                                    val stream = conn.errorStream ?: conn.inputStream
                                    stream?.bufferedReader()?.use { reader ->
                                        val buf = CharArray(CHALLENGE_BODY_PREVIEW_CHARS)
                                        val n = reader.read(buf)
                                        if (n > 0) String(buf, 0, n) else ""
                                    } ?: ""
                                } catch (_: Exception) { "" }
                                isCf = com.cloudstream.shared.cloudflare.CloudflareDetector
                                    .isCloudflareChallenge(preview, code)
                            }
                            interceptChallenges.add(
                                InterceptChallenge(
                                    url = reqUrl,
                                    statusCode = code,
                                    isCloudflare = isCf,
                                    isMainFrame = isMain,
                                    bodyPreview = preview.take(CHALLENGE_BODY_PREVIEW_CHARS)
                                )
                            )
                            ProviderLogger.w(TAG, "shouldInterceptRequest",
                                "Intercept refused — falling through to Chromium (which will send " +
                                    "the WebView fingerprint)",
                                "code" to code.toString(),
                                "cloudflare" to isCf.toString(),
                                "mainFrame" to isMain.toString(),
                                "url" to reqUrl.take(100),
                                // Whose block page is this? `cloudflare=false` on 2026-08-04 meant the
                                // site's own anti-bot, and without a sample of the body there was no
                                // way to tell that from a plain error.
                                "body" to preview.take(180).replace(Regex("\\s+"), " "))
                            return null
                        }
                    } catch (e: Exception) {
                        ProviderLogger.w(TAG, "shouldInterceptRequest", "Intercept failed: ${e.message}")
                    }
                }

                if (requestInterceptor != null && view != null) {
                    return requestInterceptor.invoke(view, request)
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val nextUrl = request?.url?.toString() ?: return super.shouldOverrideUrlLoading(view, request)
                val scheme = request.url?.scheme?.lowercase()
                if (scheme != null && scheme != "http" && scheme != "https") return true

                val isMainFrame = request.isForMainFrame
                val nextHost = try { java.net.URI(nextUrl).host?.lowercase() ?: "" } catch (_: Exception) { "" }
                val method = try { request?.method ?: "GET" } catch (_: Exception) { "GET" }
                val reqHeaders = request.requestHeaders ?: emptyMap()

                // === USER-APPROVED REDIRECT CHECK ===
                val pending = this@NavigationEngine.pendingRedirectUrl
                if (pending != null && pending == nextUrl) {
                    this@NavigationEngine.pendingRedirectUrl = null
                    ProviderLogger.i(TAG, "shouldOverrideUrlLoading", "USER APPROVED REDIRECT",
                        "url" to nextUrl.take(120), "host" to nextHost, "mainFrame" to isMainFrame.toString())
                    return false
                }

                // === PRE-ARRIVAL CONTAINMENT ===
                // Refuse a main-frame navigation that cannot lead to the destination, so a premature
                // or mis-aimed tap leaves the user where they were instead of in an ad chain with no
                // way back. Checked before auto-approve, which would otherwise wave it through.
                val navGuard = this@NavigationEngine.mainFrameNavigationGuard
                if (navGuard != null && isMainFrame && !navGuard.containsMatchIn(nextUrl)) {
                    refusedMainFrameNavigations.merge(nextUrl, 1, Int::plus)
                    ProviderLogger.w(TAG, "shouldOverrideUrlLoading",
                        "⛔ Refused a main-frame navigation that cannot reach the destination — " +
                            "staying on the current page. If this is the site's own domain, the " +
                            "button was pressed before its href was filled in; if it is a third " +
                            "party, it was an ad.",
                        "url" to nextUrl.take(140),
                        "host" to nextHost,
                        "guard" to navGuard.pattern.take(90))
                    return true
                }

                // === AUTO-APPROVE ALL REDIRECTS DURING WATCHING PHASE ===
                // The watching URL goes through dynamic ad domains (viiqkzqv.com, etc.)
                // that change per session. We must allow all redirects to reach the video.
                if (autoApproveAllRedirects && isMainFrame) {
                    ProviderLogger.i(TAG, "shouldOverrideUrlLoading", "AUTO-APPROVED (watching phase)",
                        "url" to nextUrl.take(120), "host" to nextHost)
                    // Stop auto-approving once we return to freex2line.online (the video player page)
                    if (nextHost.contains("freex2line.online") && nextUrl.contains("/pig/watching/")) {
                        autoApproveAllRedirects = false
                        ProviderLogger.w(TAG, "shouldOverrideUrlLoading", "🎬 Reached video player on freex2line",
                            "url" to nextUrl.take(120))
                    }
                    return false
                }

                // === COMPREHENSIVE HEADER/REQUEST LOG ===
                val headerSummary = reqHeaders.entries.joinToString(", ") { "${it.key}=${it.value.take(80)}" }
                ProviderLogger.w(TAG, "shouldOverrideUrlLoading", "== REDIRECT DETECTED ==",
                    "url" to nextUrl.take(150),
                    "host" to nextHost,
                    "mainFrame" to isMainFrame.toString(),
                    "method" to method,
                    "headers" to headerSummary)
                android.util.Log.d("NavEngineNet", "shouldOverrideUrlLoading: nextUrl=$nextUrl host=$nextHost main=$isMainFrame method=$method headers=$headerSummary")
                android.util.Log.w("NavEngineRedirect", "URL: $nextUrl\nHOST: $nextHost\nMAIN: $isMainFrame\nMETHOD: $method\nHEADERS: $headerSummary")

                if (isMainFrame && isOnDestination) {
                    // Blocking is right either way, but *who* is navigating matters.
                    //
                    // A cross-site main-frame navigation from the destination page is an ad hijacking
                    // the screen — block it silently, which is what this lock is for. A **same-site**
                    // one is the site itself moving us off its own player page: cimanow does
                    // `location.replace('/home')` when it dislikes the referrer, and serves
                    // `http://cimanow.cc/blockedone` for a title it will not play (2026-07-30, 73 ms
                    // after the document started, with the decryptor writing nothing).
                    //
                    // Blocking that leaves the WebView on an empty document, so the user watched a
                    // white screen until the 300 s timeout while the site had already answered in 73 ms.
                    // Record it so a waiting step can stop now and the caller can report honestly.
                    val currentHost = try {
                        java.net.URI(lastPageUrl).host?.lowercase() ?: ""
                    } catch (_: Exception) { "" }
                    fun baseDomain(h: String) =
                        h.split('.').let { if (it.size >= 2) it.takeLast(2).joinToString(".") else h }
                    val sameSite = currentHost.isNotBlank() && nextHost.isNotBlank() &&
                        baseDomain(currentHost) == baseDomain(nextHost)

                    if (sameSite) {
                        siteRejectedNavigationUrl = nextUrl
                        ProviderLogger.w(TAG, "shouldOverrideUrlLoading",
                            "🚫 SITE SENT US AWAY from its own player page — treating as terminal " +
                                "(this title is blocked, or the gate rejected the session)",
                            "url" to nextUrl, "host" to nextHost)
                    } else {
                        ProviderLogger.w(TAG, "shouldOverrideUrlLoading", "DESTINATION LOCK BLOCK",
                            "url" to nextUrl, "host" to nextHost)
                    }
                    return true
                }

                var isBlockedByDomain = false
                if (allowedDomains.isNotEmpty()) {
                    val allowed = allowedDomains.any { allowedDomain ->
                        nextHost == allowedDomain || nextHost.endsWith(".$allowedDomain")
                    }
                    if (!allowed) {
                        isBlockedByDomain = true
                        ProviderLogger.w(TAG, "shouldOverrideUrlLoading", "DOMAIN BLOCK", "url" to nextUrl, "host" to nextHost, "allowed" to allowedDomains.joinToString(","))
                    }
                }

                // === REDIRECT CONFIRMATION DIALOG (main-frame only) ===
                if (isMainFrame) {
                    val activity = activityProvider()
                    if (activity != null) {
                        val hostInfo = nextHost.ifBlank { "unknown" }
                        val methodInfo = method.ifBlank { "GET" }
                        val blockedInfo = if (isBlockedByDomain) "\n\n⚠ Domain would be blocked by policy!" else ""
                        val destInfo = if (isOnDestination) "\n\n🔒 Destination lock active!" else ""
                        val headerInfo = reqHeaders.entries.joinToString("\n") { "  ${it.key}: ${it.value.take(100)}" }

                        android.app.AlertDialog.Builder(activity)
                            .setTitle("🔄 Redirect Confirmation")
                            .setMessage(
                                "Target URL:\n$nextUrl\n\n" +
                                "Host: $hostInfo\n" +
                                "Method: $methodInfo\n" +
                                "Main Frame: $isMainFrame\n" +
                                "$blockedInfo$destInfo\n\n" +
                                "--- Request Headers ---\n$headerInfo"
                            )
                            .setPositiveButton("✅ Allow") { _, _ ->
                                this@NavigationEngine.pendingRedirectUrl = nextUrl
                                view?.post { view?.loadUrl(nextUrl) }
                                ProviderLogger.w(TAG, "shouldOverrideUrlLoading", "USER ALLOWED REDIRECT", "url" to nextUrl.take(120))
                            }
                            .setNegativeButton("❌ Block") { _, _ ->
                                ProviderLogger.w(TAG, "shouldOverrideUrlLoading", "USER BLOCKED REDIRECT", "url" to nextUrl.take(120))
                            }
                            .setCancelable(false)
                            .show()
                    } else {
                        ProviderLogger.w(TAG, "shouldOverrideUrlLoading", "No Activity available for redirect confirmation dialog", "url" to nextUrl.take(120))
                    }
                    return true
                }

                ProviderLogger.i(TAG, "shouldOverrideUrlLoading", "ALLOWED (sub-frame)", "url" to nextUrl.take(120), "host" to nextHost, "mainFrame" to isMainFrame.toString())
                return false
            }

            /**
             * A dead render process blanks the WebView instantly, with **no navigation and no network
             * activity** — indistinguishable in a log from the page clearing itself, which is exactly the
             * ambiguity that cost several rounds of guessing here. It was not observed at all before.
             * A 4.7 MB DOM plus a VK player plus sink WebViews is a plausible OOM.
             *
             * Returning true also stops Android killing the whole app along with the renderer.
             */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                val crashed = try { detail?.didCrash() } catch (_: Exception) { null }
                ProviderLogger.w(TAG, "onRenderProcessGone",
                    "💥 RENDER PROCESS GONE — blank page is a renderer death, not a navigation",
                    "didCrash" to crashed, "url" to lastPageUrl.take(120))
                rendererGone = true
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    val desc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        error?.description?.toString()
                    } else error?.toString()
                    ProviderLogger.w(TAG, "onReceivedError", desc ?: "unknown", "url" to (request.url?.toString()?.take(120) ?: ""))
                }
            }
        }

        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                msg?.let {
                    val level = when (it.messageLevel()) {
                        android.webkit.ConsoleMessage.MessageLevel.ERROR -> "E"
                        android.webkit.ConsoleMessage.MessageLevel.WARNING -> "W"
                        else -> "D"
                    }
                    android.util.Log.println(android.util.Log.INFO, "NavEngineJS", "[$level] ${it.message()} [${it.sourceId()}:${it.lineNumber()}]")
                }
                return true
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                // Give the page a REAL window — one it can hold a reference to — and let it load
                // nothing.
                //
                // Returning true without filling the transport (what this did until 2026-07-30) does
                // not "allow the popup": no window is created, so `window.open()` evaluates to null.
                // CimaNow's player reads that as a blocked popunder — it loads an Adcash "iclick"
                // popunder (`luugy.com/5/…?oo=1&js_build=iclick-…`) and gates playback on it — and
                // answers with a SweetAlert2 modal demanding the user allow ads, refusing to play or
                // switch server. Ads were never being blocked here; only the window was missing.
                //
                // So hand over a detached WebView that answers every request with an empty body. The
                // page gets a live window object whose `closed` stays false, no ad content is ever
                // fetched, nothing is shown to the user, and the main frame is untouched. Destroyed
                // with the session in cleanupWebView.
                val activity = activityProvider()
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                if (activity == null || transport == null) {
                    ProviderLogger.w(TAG, "onCreateWindow",
                        "No activity/transport — popup cannot be honoured", "isUserGesture" to isUserGesture)
                    return false
                }

                if (popupSinks.size >= MAX_POPUP_SINKS) {
                    ProviderLogger.w(TAG, "onCreateWindow",
                        "Refusing popup — sink limit reached", "limit" to MAX_POPUP_SINKS)
                    return false
                }

                return try {
                    @SuppressLint("SetJavaScriptEnabled")
                    val sink = WebView(activity).apply {
                        settings.javaScriptEnabled = true
                        settings.userAgentString = userAgent
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                v: WebView?, req: WebResourceRequest?
                            ): Boolean {
                                val scheme = req?.url?.scheme?.lowercase()
                                // An `intent://` or `market://` from an ad would throw the user out of
                                // the app entirely. Never follow a non-web scheme, in either mode.
                                if (scheme != null && scheme != "http" && scheme != "https") {
                                    ProviderLogger.w(TAG, "popupSink",
                                        "Blocked non-web popup scheme", "scheme" to scheme)
                                    return true
                                }
                                val popupUrl = req?.url?.toString() ?: ""

                                // Promote, don't sink: this is the destination, not an ad.
                                if (promotePopupsMatching?.containsMatchIn(popupUrl) == true) {
                                    ProviderLogger.w(TAG, "popupSink",
                                        "🎯 POPUP IS THE DESTINATION — promoting it to the main frame " +
                                            "instead of throwing it away",
                                        "url" to popupUrl.take(160))
                                    promotedPopupUrl = popupUrl
                                    Handler(Looper.getMainLooper()).post {
                                        try {
                                            val referer = lastPageUrl.takeIf {
                                                it.isNotBlank() && it != "about:blank"
                                            }
                                            loadUrlInWebView(webView, popupUrl, referer, emptyMap())
                                        } catch (e: Exception) {
                                            ProviderLogger.e(TAG, "popupSink",
                                                "Promotion failed", e, "url" to popupUrl.take(120))
                                        }
                                    }
                                    return true
                                }

                                // Every popup URL, at a level that survives a filtered log. What a
                                // click actually opens is the only way to tell an ad-counter click from
                                // a real one without reading the page.
                                return if (loadPopupsInSink) {
                                    ProviderLogger.i(TAG, "popupSink",
                                        "🪟 POPUP (loading for real, hidden)", "url" to popupUrl.take(160))
                                    false
                                } else {
                                    ProviderLogger.i(TAG, "popupSink",
                                        "🪟 POPUP (swallowed)", "url" to popupUrl.take(160))
                                    true
                                }
                            }

                            override fun shouldInterceptRequest(
                                v: WebView?, req: WebResourceRequest?
                            ): WebResourceResponse? {
                                // `window.open(url)` loads its target straight into this view without
                                // consulting shouldOverrideUrlLoading, so the promotion test has to run
                                // here as well or the one case that matters is the one case missed.
                                val u = req?.url?.toString() ?: ""
                                if (req?.isForMainFrame == true &&
                                    promotePopupsMatching?.containsMatchIn(u) == true &&
                                    promotedPopupUrl == null
                                ) {
                                    ProviderLogger.w(TAG, "popupSink",
                                        "🎯 POPUP IS THE DESTINATION (seen on its own request) — " +
                                            "promoting to the main frame",
                                        "url" to u.take(160))
                                    promotedPopupUrl = u
                                    Handler(Looper.getMainLooper()).post {
                                        try {
                                            val referer = lastPageUrl.takeIf {
                                                it.isNotBlank() && it != "about:blank"
                                            }
                                            loadUrlInWebView(webView, u, referer, emptyMap())
                                        } catch (e: Exception) {
                                            ProviderLogger.e(TAG, "popupSink", "Promotion failed", e)
                                        }
                                    }
                                    return WebResourceResponse("text/html", "utf-8",
                                        java.io.ByteArrayInputStream(ByteArray(0)))
                                }
                                if (req?.isForMainFrame == true) {
                                    ProviderLogger.i(TAG, "popupSink", "🪟 POPUP TARGET",
                                        "url" to u.take(160), "mode" to
                                            (if (loadPopupsInSink) "loading" else "swallowed"))
                                }
                                return if (loadPopupsInSink) {
                                    // Let it fetch. This is the whole point of the mode: the ad network
                                    // re-fires its conversion ping until the popunder actually loads.
                                    null
                                } else {
                                    // Belt and braces: the window.open target itself is loaded into
                                    // this view without passing through shouldOverrideUrlLoading, so
                                    // answer everything with an empty document.
                                    WebResourceResponse(
                                        "text/html", "utf-8",
                                        java.io.ByteArrayInputStream(ByteArray(0))
                                    )
                                }
                            }
                        }
                        // No popups from a popup. An ad landing page that opens more windows would
                        // otherwise spawn sinks until the limit, for no benefit.
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onCreateWindow(
                                v: WebView?, d: Boolean, g: Boolean, m: Message?
                            ): Boolean {
                                ProviderLogger.d(TAG, "popupSink", "Refused nested popup")
                                return false
                            }
                        }
                    }
                    popupSinks.add(sink)
                    transport.webView = sink
                    resultMsg.sendToTarget()

                    if (isUserGesture) dipPageVisibility(view)
                    ProviderLogger.i(TAG, "onCreateWindow",
                        if (loadPopupsInSink) "Popup honoured into a hidden loading sink"
                        else "Popup honoured into a blank sink window",
                        "sinks" to popupSinks.size, "isUserGesture" to isUserGesture)

                    // ALWAYS retire the sink, whether or not it loaded anything.
                    //
                    // This used to be inside `if (loadPopupsInSink)`, which meant that in the mode that
                    // shows the user no ads the window stayed open for the whole session — and a page
                    // that counts ad views by watching for its popup to *close* therefore never counted
                    // one. See popupSinkTtlMs. Destroying the WebView is what makes the page's handle
                    // report `closed === true`, i.e. what a user closing the ad tab looks like.
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            if (popupSinks.remove(sink)) {
                                sink.stopLoading()
                                sink.destroy()
                                ProviderLogger.i(TAG, "popupSink",
                                    "🪟 Sink closed after ${popupSinkTtlMs}ms — the page's window " +
                                        "handle now reports closed=true",
                                    "remainingSinks" to popupSinks.size)
                            }
                        } catch (_: Exception) {}
                    }, popupSinkTtlMs)
                    true
                } catch (e: Exception) {
                    ProviderLogger.w(TAG, "onCreateWindow", "Sink creation failed: ${e.message}")
                    false
                }
            }
        }
    }

    private fun loadUrlInWebView(
        webView: WebView,
        url: String,
        referer: String?,
        extraHeaders: Map<String, String>
    ) {
        val headers = mutableMapOf<String, String>()
        // A single space, not "" — and not omitted either. Both alternatives are known to fail.
        //
        // The point of setting this at all is to stop WebView filling it with the package name, which
        // it demonstrably does (the get-link.php XHR was blocked until the page's own request forced a
        // value). But "" is not a neutral overwrite: it produces `X-Requested-With:` with no value,
        // which no browser ever sends. Measured against cimanow.cc on-device, same URL, same UA:
        //
        //     no such header                    → 200, text/javascript
        //     X-Requested-With: <empty>         → 403, 127 KB block page
        //     X-Requested-With: <space>         → 200, text/javascript
        //     X-Requested-With: XMLHttpRequest  → 200, text/javascript
        //     X-Requested-With: <package name>  → 403, 127 KB block page
        //
        // So the empty value we were sending is why the watch page 403'd twice and why jQuery,
        // owl.carousel and animate.css never loaded — the page rendered unstyled, and its jQuery-driven
        // lazy-load never fired, which is why the video only appeared after a manual scroll.
        //
        // A space is chosen over "XMLHttpRequest" deliberately: this header goes on **document**
        // navigations, and claiming a navigation is an XHR invites a server to answer with an
        // AJAX-shaped partial instead of the page. A space asserts nothing and still occupies the slot.
        headers["X-Requested-With"] = " "
        if (referer != null) headers["Referer"] = referer
        headers.putAll(extraHeaders)
        ProviderLogger.i(TAG, "loadUrl", "url=$url headers=${headers.entries.joinToString(",") { "${it.key}=${it.value.take(20)}" }}")
        webView.loadUrl(url, headers)
    }

    private suspend fun clickElementInWebView(
        webView: WebView,
        selector: String,
        timeoutMs: Long,
        expectedUrl: String = ""
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val expectedHost = try { java.net.URI(expectedUrl).host?.lowercase() } catch(_: Exception) { null }

        while (System.currentTimeMillis() < deadline) {
            if (expectedHost != null) {
                val currentWebviewUrl = withContext(Dispatchers.Main) { webView.url ?: "" }
                val currentHost = try { java.net.URI(currentWebviewUrl).host?.lowercase() } catch(_: Exception) { null }
                if (currentHost != null && currentHost != expectedHost) {
                    ProviderLogger.i(TAG, "clickElement", "URL host changed from $expectedHost to $currentHost. Breaking early.")
                    return false
                }
            }

            val coords = findElementCoordinates(webView, selector)
            if (coords != null) {
                dispatchNativeClick(webView, coords.first, coords.second)
                ProviderLogger.i(TAG, "clickElement", "Native click $selector at (${coords.first}, ${coords.second})")
                return true
            }
            val jsClicked = jsClickElement(webView, selector)
            if (jsClicked) {
                ProviderLogger.i(TAG, "clickElement", "JS click fallback $selector")
                return true
            }
            delay(500)
        }
        ProviderLogger.w(TAG, "clickElement", "Element not found: $selector within ${timeoutMs}ms")
        return false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun jsClickElement(webView: WebView, selector: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val safeSelector = selector.replace("'", "\\'")
                webView.evaluateJavascript("""
                    (function() {
                        var el = document.querySelector('$safeSelector');
                        if (!el) return JSON.stringify({clicked: false, reason: 'not found'});
                        try {
                            el.click();
                            return JSON.stringify({clicked: true, tag: el.tagName, id: el.id || '', classes: (el.className || '').substring(0, 100)});
                        } catch(e) {
                            return JSON.stringify({clicked: false, reason: e.message});
                        }
                    })();
                """.trimIndent()) { result ->
                    val clicked = try {
                        if (result != null && result != "null") {
                            val parsed = org.json.JSONTokener(result).nextValue()
                            if (parsed is org.json.JSONObject) {
                                ProviderLogger.d(TAG, "jsClickElement", "selector=$safeSelector result=$parsed")
                                parsed.optBoolean("clicked")
                            } else result == "true"
                        } else false
                    } catch (_: Exception) {
                        ProviderLogger.w(TAG, "jsClickElement", "raw result=$result")
                        result == "true"
                    }
                    ProviderLogger.i(TAG, "jsClickElement", "selector=$safeSelector clicked=$clicked")
                    if (cont.isActive) cont.resume(clicked) {}
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun findElementCoordinates(webView: WebView, selector: String): Pair<Float, Float>? {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val safeSelector = selector.replace("'", "\\'")
                webView.evaluateJavascript("""
                    (function() {
                        var el = document.querySelector('$safeSelector');
                        if (!el) return JSON.stringify({found: false});
                        var rect = el.getBoundingClientRect();
                        var cs = window.getComputedStyle(el);
                        return JSON.stringify({
                            found: true,
                            tag: el.tagName,
                            id: el.id || '',
                            classes: el.className || '',
                            rect: {left: rect.left, top: rect.top, width: rect.width, height: rect.height},
                            display: cs.display,
                            visibility: cs.visibility,
                            offsetParent: !!el.offsetParent,
                            rects: el.getClientRects().length,
                            dpr: window.devicePixelRatio || 1
                        });
                    })();
                """.trimIndent()) { result ->
                    try {
                        if (result != null && result != "null" && result != "\"\"") {
                            val parsed = org.json.JSONTokener(result).nextValue()
                            if (parsed is org.json.JSONObject) {
                                if (!parsed.optBoolean("found")) {
                                    ProviderLogger.w(TAG, "findElementCoordinates", "Element not found for $safeSelector")
                                    if (cont.isActive) cont.resume(null) {}
                                    return@evaluateJavascript
                                }
                                ProviderLogger.d(TAG, "findElementCoordinates", "selector=$safeSelector tag=${parsed.optString("tag")} classes=${parsed.optString("classes")} rect=${parsed.optJSONObject("rect")} display=${parsed.optString("display")} visibility=${parsed.optString("visibility")} offsetParent=${parsed.optBoolean("offsetParent")}")
                                val rect = parsed.optJSONObject("rect")
                                val w = rect?.optDouble("width") ?: 0.0
                                val h = rect?.optDouble("height") ?: 0.0
                                if (w > 0 && h > 0) {
                                    val dpr = parsed.optDouble("dpr", 1.0)
                                    val x = (rect.optDouble("left") + w / 2) * dpr
                                    val y = (rect.optDouble("top") + h / 2) * dpr
                                    ProviderLogger.i(TAG, "findElementCoordinates", "Valid rect for $safeSelector -> coords=($x, $y) dpr=$dpr")
                                    if (cont.isActive) cont.resume(Pair(x.toFloat(), y.toFloat())) {}
                                    return@evaluateJavascript
                                }
                                ProviderLogger.w(TAG, "findElementCoordinates", "Zero rect for $safeSelector w=$w h=$h")
                            }
                        }
                    } catch (e: Exception) {
                        ProviderLogger.w(TAG, "findElementCoordinates", "Parse error: ${e.message}")
                    }
                    if (cont.isActive) cont.resume(null) {}
                }
            }
        }
    }

    private fun dispatchNativeClick(webView: WebView, x: Float, y: Float) {
        Handler(Looper.getMainLooper()).post {
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            webView.dispatchTouchEvent(down)
            down.recycle()

            Handler(Looper.getMainLooper()).postDelayed({
                val upTime = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
                webView.dispatchTouchEvent(up)
                up.recycle()
            }, 50)
        }
    }

    private suspend fun waitForSelector(
        webView: WebView,
        selector: String,
        timeoutMs: Long,
        expectedUrl: String = ""
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var pollCount = 0
        val expectedHost = try { java.net.URI(expectedUrl).host?.lowercase() } catch(_: Exception) { null }

        while (System.currentTimeMillis() < deadline) {
            pollCount++

            if (expectedHost != null) {
                val currentWebviewUrl = withContext(Dispatchers.Main) { webView.url ?: "" }
                val currentHost = try { java.net.URI(currentWebviewUrl).host?.lowercase() } catch(_: Exception) { null }
                if (currentHost != null && currentHost != expectedHost) {
                    ProviderLogger.i(TAG, "waitForSelector", "URL host changed from $expectedHost to $currentHost. Breaking early.")
                    return false
                }
            }

            val found = checkSelectorExists(webView, selector)
            ProviderLogger.d(TAG, "waitForSelector", "poll#$pollCount selector=$selector found=$found remaining=${deadline - System.currentTimeMillis()}ms")
            if (found) {
                ProviderLogger.i(TAG, "waitForSelector", "FOUND selector=$selector after ${pollCount} polls")
                return true
            }
            delay(500)
        }
        ProviderLogger.w(TAG, "waitForSelector", "TIMEOUT selector=$selector after ${pollCount} polls")
        return false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun checkSelectorExists(webView: WebView, selector: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val safeSelector = selector.replace("'", "\\'")
                webView.evaluateJavascript("""
                    (function() {
                        var el = document.querySelector('$safeSelector');
                        return JSON.stringify({
                            exists: el !== null,
                            tag: el ? el.tagName : null,
                            id: el ? (el.id || '') : null,
                            classes: el ? (el.className || '') : null,
                            display: el ? window.getComputedStyle(el).display : null,
                            visible: el ? (el.offsetWidth > 0 || el.offsetHeight > 0 || el.getClientRects().length > 0) : false
                        });
                    })();
                """.trimIndent()) { result ->
                    val exists = try {
                        if (result != null && result != "null") {
                            val parsed = org.json.JSONTokener(result).nextValue()
                            if (parsed is org.json.JSONObject) {
                                ProviderLogger.d(TAG, "checkSelectorExists", "selector=$safeSelector exists=${parsed.optBoolean("exists")} tag=${parsed.optString("tag")} id=${parsed.optString("id")} classes=${parsed.optString("classes")} display=${parsed.optString("display")} visible=${parsed.optBoolean("visible")}")
                                parsed.optBoolean("exists")
                            } else result == "true"
                        } else false
                    } catch (_: Exception) { result == "true" }
                    if (cont.isActive) cont.resume(exists) {}
                }
            }
        }
    }

    private suspend fun waitForUrl(
        webView: WebView,
        urlPattern: String,
        timeoutMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val regex = Regex(urlPattern)
        var pollCount = 0
        while (System.currentTimeMillis() < deadline) {
            pollCount++
            val currentUrl = getCurrentUrlFromWebView(webView) ?: ""
            ProviderLogger.d(TAG, "waitForUrl", "poll#$pollCount pattern=$urlPattern currentUrl=${currentUrl.take(120)} match=${regex.containsMatchIn(currentUrl)} remaining=${deadline - System.currentTimeMillis()}ms")
            if (regex.containsMatchIn(currentUrl)) {
                ProviderLogger.i(TAG, "waitForUrl", "MATCHED pattern=$urlPattern after ${pollCount} polls, url=${currentUrl.take(120)}")
                return true
            }
            delay(500)
        }
        ProviderLogger.w(TAG, "waitForUrl", "TIMEOUT pattern=$urlPattern after ${pollCount} polls")
        return false
    }

    private suspend fun waitForDomCondition(
        webView: WebView,
        jsCondition: String,
        timeoutMs: Long,
        pollIntervalMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var pollCount = 0
        while (System.currentTimeMillis() < deadline) {
            pollCount++
            val met = evaluateDomCondition(webView, jsCondition)
            ProviderLogger.d(TAG, "waitForDomCondition", "poll#$pollCount condition=${jsCondition.take(60)} met=$met remaining=${deadline - System.currentTimeMillis()}ms")
            if (met) {
                ProviderLogger.i(TAG, "waitForDomCondition", "MET after ${pollCount} polls")
                return true
            }
            delay(pollIntervalMs)
        }
        ProviderLogger.w(TAG, "waitForDomCondition", "TIMEOUT after ${pollCount} polls")
        return false
    }

    /**
     * Atomically polls a DOM condition in the same evaluateJavascript that captures the
     * snapshot — eliminating the race window between "condition met" and "read innerHTML"
     * that anti-bot scripts (like cimanow's 0CYA6X1KhKIS.js) exploit.
     *
     * The [snapshotJs] should return a non-empty string when the condition is met,
     * or an empty/false string when the condition has not yet been satisfied.
     *
     * @return the snapshot string when condition was met, or null on timeout/error.
     */
    private suspend fun waitForDomConditionAndSnapshot(
        webView: WebView,
        jsCondition: String,
        snapshotJs: String,
        timeoutMs: Long,
        pollIntervalMs: Long
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var pollCount = 0
        while (System.currentTimeMillis() < deadline) {
            pollCount++
            val combinedJs = """
                (function(){
                    try {
                        if (!($jsCondition)) { return ''; }
                        return $snapshotJs
                    } catch(e) { return 'raw_html_error:' + e.message; }
                })();
            """.trimIndent()
            val result = executeJsInWebView(webView, combinedJs)
            val snapshot = result ?: ""
            ProviderLogger.d(TAG, "waitForDomConditionAndSnapshot",
                "poll#$pollCount condition=${jsCondition.take(60)} snapshot=${snapshot.take(100)} remaining=${deadline - System.currentTimeMillis()}ms")
            if (snapshot.isNotBlank() && !snapshot.startsWith("raw_html_error:")) {
                ProviderLogger.i(TAG, "waitForDomConditionAndSnapshot", "MET after ${pollCount} polls, snapshot ${snapshot.length} chars")
                return snapshot
            }
            delay(pollIntervalMs)
        }
        ProviderLogger.w(TAG, "waitForDomConditionAndSnapshot", "TIMEOUT after ${pollCount} polls")
        return null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun evaluateDomCondition(webView: WebView, jsCondition: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                webView.evaluateJavascript("""
                    (function() {
                        try { return !!($jsCondition); }
                        catch(e) { return false; }
                    })();
                """.trimIndent()) { result ->
                    if (cont.isActive) cont.resume(result == "true") {}
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun executeJsInWebView(webView: WebView, javascript: String): String? {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                webView.evaluateJavascript(javascript) { result ->
                    val cleaned = try {
                        if (result == null || result == "null") null
                        else org.json.JSONTokener(result).nextValue().toString()
                    } catch (_: Exception) { result }
                    if (cont.isActive) cont.resume(cleaned) {}
                }
            }
        }
    }

    /**
     * Renders captured (still-encrypted) watch-page HTML in a WebView, lets the page's OWN
     * decryptor run completely untouched, and reads the decrypted server list back via an in-page
     * reader that exfiltrates through chunked console.log → onConsoleMessage. The WebView does all
     * decryption (which the site rotates hourly); nothing is decrypted in Kotlin.
     *
     * **Before changing anything here, read `CimaNowProviderV2/cimanow_decryption_handover.md` §0.**
     * It lists every approach that has already been built and broken (Kotlin decryption, hooking
     * `document.write`, `loadDataWithBaseURL`, `addJavascriptInterface`, `prompt()`,
     * `evaluateJavascript` reads, fixed marker strings, injecting after `<head>`) with the reason and
     * the date each one died. Re-learning any of them costs about a day.
     *
     * This is shaped directly by the site's decoded anti-bot. Its pre-decrypt gate (the
     * `eval(atob(…))` at the end of the payload script) sets `_isB` and `return`s — emitting
     * NOTHING — if any of these hold, so every one of them dictates something we must not do:
     *   - `document.getElementById('<rotating id>')` missing, or `location.hostname` empty. Hence a
     *     genuine navigation to the real https://…/watching/ URL (served from our own bytes via
     *     shouldInterceptRequest), never loadDataWithBaseURL.
     *   - `window.CS_BRIDGE` / `window.__decryptedHtml` / `window.__captured` defined. Hence NO
     *     addJavascriptInterface and nothing set on window — the reader is a bare IIFE.
     *   - `Element.prototype.remove` not working (it appends an <li data-index>, removes it and
     *     checks parentNode). Hence never freeze/patch remove in the sandbox.
     *   - ANY inline <script> in the document whose source contains a known marker of ours. As of
     *     2026-07-25 the gate literally greps every script's innerHTML for "__CSX__" and "[RD]" —
     *     our own previous protocol strings. It also wraps window.prompt to swallow messages
     *     starting with "__CSX__" (which is why the prompt channel silently died).
     *     Hence: the reader carries NO fixed strings (all markers are random per run) and, first
     *     thing it does, it REMOVES ITS OWN <script> node — the gate runs later, enumerates
     *     scripts and finds no trace of us at all.
     * Earlier rotations also required document.write to stay native (they now call
     * `Document.prototype.write.call` instead) and installed a post-decrypt isBot() that sabotaged
     * DOM reads whose call stack contained "evaluatejavascript" or "<anonymous>" with no "http".
     * That is why reads must come from an inline page script (stack = the document's http URL) and
     * never from webView.evaluateJavascript. Assume it can come back.
     *
     * Safe to call from any coroutine context (switches to Dispatchers.Main internally).
     *
     * @param html      the captured (still-encrypted) watch-page HTML
     * @param baseUrl   the real https://…/watching/ URL (served to the WebView as a real navigation)
     * @param userAgent session UA; the WebView tells ("Version/4.0","wv") are stripped for hygiene
     * @param referrer  Referer for the navigation — sets document.referrer. REQUIRED for CimaNow:
     *                  the decrypted page runs `if(document.referrer.indexOf('rm.freex2line.online')
     *                  ===-1) location.replace('/home')`, which aborts the parse before the server
     *                  list. Pass a URL containing that host so the check passes.
     * @param timeoutMs max time to wait for the in-page reader to report back (default 25s)
     * @return the decrypted document.body HTML (containing the <li data-index> server list), or null
     */
    suspend fun renderHtmlInSandbox(
        html: String,
        baseUrl: String,
        userAgent: String,
        referrer: String = "",
        timeoutMs: Long = 25_000L
    ): String? = withContext(Dispatchers.Main) {
        val m = "renderHtmlInSandbox"
        val activity = activityProvider()?.let {
            if (it.isFinishing) null else it
        } ?: run {
            ProviderLogger.e(TAG, m, "No activity available — cannot create sandbox WebView")
            return@withContext null
        }
        if (html.isBlank()) {
            ProviderLogger.w(TAG, m, "Empty html — nothing to render")
            return@withContext null
        }

        // Strip the WebView giveaways from the UA: "Version/4.0" and the "; wv" token do not appear
        // in a real Chrome-mobile UA. (This site's isBot() is stack-based, not UA-based, so this is
        // just hygiene for Cloudflare / other layers.)
        val browserUa = userAgent
            .replace(Regex("\\s*Version/\\S+"), "")
            .replace("; wv", "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        // ── In-page reader ──────────────────────────────────────────────────────────────────
        // Runs as the first inline <script> of the served document, so its call stack is the real
        // http(s) URL (what the post-decrypt guard, when present, demands) and it can leave nothing
        // on window (what the pre-decrypt gate scans for).
        //
        // Two anti-fingerprint measures, both mandatory — the site greps script source for our
        // markers and aborts the decryptor on a hit:
        //   1. Every protocol/log token is derived from [tag], regenerated per call. There is not a
        //      single fixed string in here for them to match on.
        //   2. The script deletes its own <script> node before yielding, so by the time the page's
        //      gate enumerates document scripts, ours is not in the DOM.
        // console.log is captured up-front (bound) so a later hook on console cannot intercept or
        // observe the exfil, and the page's own prompt/dialog patches are irrelevant to us.
        val tag = "q" + java.util.UUID.randomUUID().toString().replace("-", "").take(11)
        val markBegin = "$tag>B"      // <n>|<totalChars>
        val markChunk = "$tag>C"      // <i>|<chunk>
        val markEnd = "$tag>E"
        val maxTries = (timeoutMs / 250).coerceAtLeast(4)
        val readerScript = """
            <script>
            (function(){
              var S = document.currentScript;
              try { if (S && S.parentNode) S.parentNode.removeChild(S); } catch(e){}
              S = null;
              var L; try { L = console.log.bind(console); } catch(e) { L = function(){}; }

              // ── Second, independent status channel: document.title → onReceivedTitle ──────────
              // console.log is the only way the reader can speak, which makes a diagnosis
              // impossible when it goes quiet: a wrapped console.log and a reader that never ran
              // look identical from Kotlin. The title channel touches nothing the pre-decrypt gate
              // scans — no window global, no console, no navigation, no dialog — so comparing which
              // channels arrive tells us which of the two happened. Status only; bulk exfil stays on
              // console (titles are length-capped).
              var tseq = 0;
              function T(s){ try { document.title = '$tag>' + (++tseq) + '>' + s; } catch(e){} }

              // Is console.log still the real thing? The gate already wraps window.prompt to swallow
              // our messages; console is the obvious next target, and a wrapper is usually a plain JS
              // function, so [native code] disappears from its toString.
              function chan(){
                var lg = '?', ti = '?';
                try { lg = (Function.prototype.toString.call(console.log).indexOf('[native code]') >= 0) ? 'native' : 'WRAPPED'; } catch(e) { lg = 'throw'; }
                try {
                  var d = Object.getOwnPropertyDescriptor(Document.prototype, 'title');
                  ti = (d && d.set && Function.prototype.toString.call(d.set).indexOf('[native code]') >= 0) ? 'native' : 'WRAPPED';
                } catch(e) { ti = 'throw'; }
                return 'log=' + lg + ' title=' + ti;
              }
              // How much of the page's own JS actually ran. The sandbox serves no subresources, so
              // the site can answer them with an HTML block page and Chrome refuses them all on
              // strict MIME — jq=0 with extScripts>0 is that fingerprint.
              function extScriptState(){
                try {
                  var s = document.scripts, tot = 0, ext = 0;
                  for (var i = 0; i < s.length; i++) { tot++; if (s[i].src) ext++; }
                  return ext + 'ext/' + tot + ' jq=' + ((typeof window.jQuery !== 'undefined') ? 1 : 0);
                } catch(e) { return '?'; }
              }
              // First words out of the reader, on BOTH channels, before anything can be tampered
              // with further. Absence of both in the log means this script never executed at all.
              T('alive ' + chan());
              try { L('$tag alive ' + chan()); } catch(e){}

              var CH = $CSX_CHUNK_SIZE, MAX = $maxTries, tries = 0, sent = false;
              function emit(frag){
                if (sent) return; sent = true;
                var n = Math.ceil(frag.length / CH);
                L('$markBegin' + n + '|' + frag.length);
                for (var i = 0; i < n; i++) L('$markChunk' + i + '|' + frag.substr(i * CH, CH));
                L('$markEnd');
              }
              // An EMPTY #watch plus an EMPTY #download is not a slow page — it is the decryptor's
              // decoy for a client it flagged, written instead of the real markup. Naming it beats
              // polling 100 times against a document that will never change.
              function isDecoy(){
                try {
                  var w = document.getElementById('watch'), d = document.getElementById('download');
                  return !!(w && d && w.children.length === 0 && d.children.length === 0
                            && (w.innerHTML || '').trim() === '' && (d.innerHTML || '').trim() === '');
                } catch(e) { return false; }
              }
              function diag(){
                var b = document.body, nat = -1;
                try { nat = Function.prototype.toString.call(document.write).indexOf('[native code]'); } catch(e){}
                return 'ready=' + document.readyState
                  + ' vis=' + document.visibilityState
                  + ' size=' + window.innerWidth + 'x' + window.innerHeight
                  + ' bodyLen=' + (b ? b.innerHTML.length : 0)
                  + ' scripts=' + document.scripts.length
                  + ' li=' + document.querySelectorAll('li[data-index]').length
                  + ' watchEl=' + (document.getElementById('watch') ? 1 : 0)
                  + ' decoy=' + (isDecoy() ? 1 : 0)
                  + ' dwNative=' + nat
                  + ' ' + chan()
                  // Where the page thinks it is on the display. The sandbox used to be translated
                  // 20000px off-screen, which shows up here as a screenX no real window ever has —
                  // exactly the kind of thing a client check reads. Keep it in the diag so a
                  // regression is visible rather than inferred.
                  + ' screen=' + (function(){ try {
                      return window.screenX + ',' + window.screenY
                        + '/' + screen.width + 'x' + screen.height
                        + ' dpr=' + window.devicePixelRatio;
                    } catch(e) { return '?'; } })()
                  // Did the page's own assets load? All four being refused (the sandbox serves no
                  // subresources) is a bot signal in its own right, so count what actually ran.
                  + ' extScripts=' + extScriptState();
              }
              // Surface page errors — a decryptor that throws is otherwise completely silent.
              try { window.addEventListener('error', function(ev){
                L('$tag pageerr ' + (ev.message||'') + ' @' + ((ev.filename||'').slice(-60)) + ':' + ev.lineno);
              }, true); } catch(e){}
              try { window.addEventListener('load', function(){ var s = diag(); L('$tag load ' + s); T('load ' + s); }); } catch(e){}
              try {
                var st = (new Error().stack||'').replace(/\n/g,' | ').slice(0,160);
                L('$tag init referrer=' + document.referrer + ' stack: ' + st);
                // Referrer is the gate's hard requirement and the stack is what the post-decrypt
                // guard reads; both belong on the channel that cannot be swallowed.
                T('init ref=' + (document.referrer||'').slice(0,60) + ' httpStack=' + (st.indexOf('http') >= 0 ? 1 : 0));
              } catch(e){}
              var timer = setInterval(function(){
                tries++;
                try {
                  var lis = document.querySelectorAll('li[data-index]');
                  var n = lis ? lis.length : 0;
                  if (n > 0) {
                    clearInterval(timer);
                    // Compact payload: only the server list (#watch) + downloads (#download) —
                    // everything Kotlin needs to resolve the servers.
                    var w = document.getElementById('watch');
                    var d = document.getElementById('download');
                    var frag = (w ? w.outerHTML : '') + (d ? d.outerHTML : '');
                    if (!frag) { var b = document.body; frag = b ? b.innerHTML : ''; }
                    L('$tag captured li=' + n + ' fragLen=' + frag.length);
                    T('captured li=' + n + ' fragLen=' + frag.length);
                    emit(frag);
                    return;
                  }
                  // Bail out immediately on the decoy — waiting the full window changes nothing.
                  if (tries >= 4 && isDecoy()) {
                    clearInterval(timer);
                    L('$tag DECOY served (flagged as bot) after ' + tries + ' tries, ' + diag());
                    T('DECOY t=' + tries + ' ' + diag());
                    emit('');
                    return;
                  }
                  if (tries % 8 === 0) { L('$tag waiting tries=' + tries + ' ' + diag()); T('wait t=' + tries + ' ' + diag()); }
                  if (tries >= MAX) {
                    clearInterval(timer);
                    L('$tag give up after ' + tries + ' tries, ' + diag());
                    T('giveup t=' + tries + ' ' + diag());
                    emit('');
                  }
                } catch(e) { L('$tag err ' + e.message); T('err ' + (e && e.message ? e.message : '?')); }
              }, 250);
            })();
            </script>
        """.trimIndent()

        // Inject the reader at offset 0 — BEFORE the first byte of the page, not after <head>.
        //
        // Anchoring to <head> looked equivalent and was not: on this page the payload script sits at
        // offset 0, *ahead* of <head> (which starts at ~966), so it ran first. It replaces the whole
        // document (document.open()/write()), which resets the parser and discards every byte not yet
        // parsed — the reader among them. It never executed at all: verified by both status channels
        // reporting nothing while onReceivedTitle still delivered the page's own <title>, so the
        // channels were live and the script simply wasn't there. Its first statement is
        // `!function(){try{for(var o=["lo…` — iterating what is almost certainly console method names,
        // i.e. it also neuters the console channel on its way past.
        //
        // Running ahead of it is strictly better on every constraint in the gate's list: the reader
        // arms its poll before the document is replaced (timers and closures survive document.open(),
        // so the poll still sees the new DOM), and it deletes its own <script> node before the gate
        // ever enumerates scripts looking for our markers.
        run {
            val headIdx = html.indexOf("<head", ignoreCase = true)
            ProviderLogger.d(TAG, m, "Reader injection site",
                "tag" to tag,
                "position" to "offset 0 (ahead of the page's first script)",
                "headIdxForReference" to headIdx,
                "htmlStartsWith" to html.take(60).replace("\n", " "),
                "readerLen" to readerScript.length)
        }
        val injectedHtml = run {
            // One exception to "offset 0": a doctype must stay the first thing in the document or the
            // page renders in quirks mode. Slot in immediately after it — still ahead of every script.
            val trimmedStart = html.takeWhile { it.isWhitespace() }.length
            if (html.regionMatches(trimmedStart, "<!doctype", 0, 9, ignoreCase = true)) {
                val docTypeEnd = html.indexOf('>', trimmedStart)
                if (docTypeEnd >= 0) {
                    html.substring(0, docTypeEnd + 1) + readerScript + html.substring(docTypeEnd + 1)
                } else readerScript + html
            } else readerScript + html
        }

        ProviderLogger.i(TAG, m, "Bridge render starting",
            "htmlLen" to html.length, "injectedLen" to injectedHtml.length, "baseUrl" to baseUrl,
            "timeoutMs" to timeoutMs, "maxTries" to maxTries, "tag" to tag, "ua" to browserUa.take(50))

        val captured = CompletableDeferred<String?>()

        // Reassembly state for the chunked console.log channel (written only from onConsoleMessage,
        // i.e. always the main thread).
        val csxChunks = java.util.TreeMap<Int, String>()
        var csxExpectedChunks = -1
        var csxExpectedLen = -1

        // Which of the reader's two channels actually spoke. The whole point of running two: when
        // the reader goes silent, these say whether it never executed (neither channel) or whether
        // its console was neutered (title only) — indistinguishable otherwise, and the site has
        // already killed two exfil channels this way (addJavascriptInterface, then prompt).
        var sawConsoleChannel = false
        var sawTitleChannel = false
        var lastTitleStatus: String? = null
        var titleMsgCount = 0

        val webView = createWebView(activity, browserUa)
        val injectedBytes = injectedHtml.toByteArray(Charsets.UTF_8)
        val mainServed = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            webView.settings.blockNetworkLoads = false   // behave like a real in-app browser
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true

            // Report a phone-shaped viewport. With useWideViewPort the layout viewport defaults to
            // 980 CSS px regardless of the device, and the reader measured exactly that: 980x457 on
            // a 1080p phone whose UA claims Android. A desktop-width viewport on a mobile UA is a
            // free, obvious inconsistency for any client check, and nothing here needs wide layout —
            // the sandbox is never looked at. Disabling it makes innerWidth track the real dp size.
            webView.settings.useWideViewPort = false
            webView.settings.loadWithOverviewMode = false
            // NB: deliberately NO addJavascriptInterface — it would create a window global the
            // decryptor now sniffs for (window.CS_BRIDGE) and abort. Exfil is via onJsPrompt below.

            webView.webViewClient = object : WebViewClient() {
                // Serve the captured HTML as the response to a NORMAL navigation to the real
                // watching URL. Unlike loadDataWithBaseURL, this makes it a genuinely-navigated
                // document, so the decryptor's document.open()/write()/close() full-document
                // replacement commits correctly (under loadDataWithBaseURL it only partially
                // writes and the <li data-index> server list never lands). We still never hit the
                // network for the main doc (Cloudflare would block the WebView) — we feed our own
                // bytes — while location.hostname/origin/stack all read as the real https URL.
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.isForMainFrame && mainServed.compareAndSet(false, true)) {
                        ProviderLogger.d(TAG, m, "Serving captured main document", "url" to (request.url?.toString()?.take(100) ?: ""), "bytes" to injectedBytes.size)
                        return WebResourceResponse("text/html", "utf-8", java.io.ByteArrayInputStream(injectedBytes)).apply {
                            responseHeaders = mapOf("Content-Type" to "text/html; charset=utf-8")
                        }
                    }
                    // Subresources load from the network as usual.
                    //
                    // Re-issuing them through our own client (to strip the leaking X-Requested-With)
                    // was tried on 2026-07-29 and reverted: the reader measured `extScripts=0ext/1`
                    // on this page — one inline script and NO external ones — so the sandbox makes no
                    // subresource requests at all and there was nothing for it to act on. It could
                    // not have affected the decoy, and keeping it would only add an untested network
                    // path. Revisit only if a future page actually loads external assets here.
                    return null
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Block secondary navigations (e.g. the page trying to go to /home) — they would
                    // tear down the DOM before the reader captures it. The initial loadUrl() below
                    // does not go through this callback.
                    ProviderLogger.d(TAG, m, "Blocked navigation", "url" to (request.url?.toString()?.take(120) ?: ""))
                    return true
                }
            }
            webView.webChromeClient = object : WebChromeClient() {
                // This is BOTH the log sink and the exfiltration channel. The reader streams the
                // captured fragment as console.log chunks:
                //     __CSX_BEGIN__<chunkCount>|<totalChars>
                //     __CSX_C<i>__<chunk>            (i = 0 … chunkCount-1)
                //     __CSX_END__
                // console.log is used instead of prompt() because prompt() → onJsPrompt is not
                // delivered for this WebView (JS dialogs are dropped when the page is considered
                // hidden, and dialog text is length-capped), whereas console messages always
                // arrive — and it still leaves no window global for the decryptor to sniff.
                override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                    val raw = cm.message()
                    when {
                        raw.startsWith(markBegin) -> {
                            val meta = raw.removePrefix(markBegin)
                            csxExpectedChunks = meta.substringBefore('|').trim().toIntOrNull() ?: -1
                            csxExpectedLen = meta.substringAfter('|', "").trim().toIntOrNull() ?: -1
                            csxChunks.clear()
                            ProviderLogger.d(TAG, m, "Reader stream begin",
                                "chunks" to csxExpectedChunks, "totalLen" to csxExpectedLen)
                        }
                        raw.startsWith(markChunk) -> {
                            val sep = raw.indexOf('|', markChunk.length)
                            val idx = if (sep > markChunk.length) raw.substring(markChunk.length, sep).toIntOrNull() else null
                            if (idx == null) {
                                ProviderLogger.w(TAG, m, "Malformed reader chunk header: ${raw.take(40)}")
                            } else {
                                csxChunks[idx] = raw.substring(sep + 1)
                            }
                        }
                        raw.startsWith(markEnd) -> {
                            val assembled = buildString { csxChunks.values.forEach { append(it) } }
                            ProviderLogger.i(TAG, m, "Reader stream end",
                                "chunks" to "${csxChunks.size}/$csxExpectedChunks",
                                "len" to "${assembled.length}/$csxExpectedLen")
                            if (csxExpectedChunks >= 0 && csxChunks.size != csxExpectedChunks) {
                                ProviderLogger.w(TAG, m, "Chunk loss — ${csxExpectedChunks - csxChunks.size} chunk(s) missing")
                            } else if (csxExpectedLen >= 0 && assembled.length != csxExpectedLen) {
                                // A short assembly with all chunks present means the console
                                // channel truncated a message — lower CSX_CHUNK_SIZE.
                                ProviderLogger.w(TAG, m, "Length mismatch — console message truncation? got ${assembled.length}, expected $csxExpectedLen")
                            }
                            if (!captured.isCompleted) captured.complete(assembled)
                        }
                        // Reader diagnostics (prefixed with the run tag) and genuine page console
                        // output both surface here.
                        else -> {
                            val fromReader = raw.startsWith("$tag ")
                            if (fromReader) sawConsoleChannel = true
                            ProviderLogger.d(TAG, "$m/console",
                                (if (fromReader) "[rd] " + raw.removePrefix("$tag ") else raw).take(300),
                                "src" to (cm.lineNumber()))
                        }
                    }
                    if (raw.startsWith(markBegin) || raw.startsWith(markChunk) || raw.startsWith(markEnd)) {
                        sawConsoleChannel = true
                    }
                    return true
                }

                /**
                 * The reader's second status channel. Fires on every `document.title` write and is
                 * reachable without a window global, the console, a navigation or a dialog — so it
                 * survives the tampering that has repeatedly killed the primary channel.
                 */
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    val t = title ?: return
                    if (t.startsWith("$tag>")) {
                        sawTitleChannel = true
                        titleMsgCount++
                        // Strip the "<tag>><seq>>" envelope for readability.
                        val status = t.removePrefix("$tag>").substringAfter('>', t)
                        lastTitleStatus = status
                        ProviderLogger.i(TAG, "$m/title", "[rd] $status".take(300))
                    } else {
                        ProviderLogger.d(TAG, "$m/title", "page title: ${t.take(120)}")
                    }
                }
                // Not an exfil path (see above) — just make sure a page prompt/alert never blocks
                // JS execution waiting for a dialog that has no UI to show it.
                override fun onJsPrompt(
                    view: WebView?, url: String?, message: String?, defaultValue: String?,
                    result: android.webkit.JsPromptResult?
                ): Boolean {
                    result?.confirm("")
                    return true
                }
                override fun onJsAlert(
                    view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?
                ): Boolean {
                    result?.confirm()
                    return true
                }
                override fun onJsConfirm(
                    view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?
                ): Boolean {
                    result?.confirm()
                    return true
                }
            }

            // Attach the WebView (1x1, fully transparent) to the activity. A WebView that is never
            // attached to a window is "hidden" to the page-visibility layer: document.visibilityState
            // is 'hidden', requestAnimationFrame never fires and JS dialogs are dropped — any of
            // which can stall a page whose decryptor waits on them. The reader's diag() line reports
            // vis=/size= so the log shows whether this took effect.
            attachVisibleFullSize(activity, webView)

            ProviderLogger.d(TAG, m, "Navigating to real watching URL (main doc served from capture) — page decrypts untouched", "referrer" to referrer.ifBlank { "<none>" })
            if (referrer.isNotBlank()) {
                webView.loadUrl(baseUrl, mapOf("Referer" to referrer))
            } else {
                webView.loadUrl(baseUrl)
            }

            val body = withTimeoutOrNull(timeoutMs + 4000) { captured.await() }

            // Always state which channels spoke, and what that means. Working this out by reading
            // console noise cost a full debugging cycle: absence of reader output was mistaken for
            // a decryption failure, when the gate's designed behaviour is to emit nothing at all.
            val verdict = when {
                sawConsoleChannel && sawTitleChannel ->
                    "both channels alive — reader ran; if no list arrived the gate aborted upstream (check li=/decoy=/dwNative= above)"
                sawTitleChannel && !sawConsoleChannel ->
                    "TITLE ONLY — console.log is being swallowed; move the exfil protocol to the title channel"
                !sawTitleChannel && !sawConsoleChannel ->
                    "SILENT ON BOTH — the reader script never executed; suspect document serving/parsing, not the cipher"
                else ->
                    "console only — title writes blocked (unexpected; the gate may now guard Document.title)"
            }
            ProviderLogger.i(TAG, m, "Reader channel report",
                "console" to sawConsoleChannel, "title" to sawTitleChannel,
                "titleMsgs" to titleMsgCount, "lastTitleStatus" to (lastTitleStatus ?: "<none>"),
                "verdict" to verdict)

            when {
                body == null -> {
                    ProviderLogger.w(TAG, m, "Reader never reported back (timed out) — $verdict")
                    null
                }
                body.isBlank() -> {
                    ProviderLogger.w(TAG, m, "Reader reported empty — server list never appeared in-page")
                    null
                }
                else -> {
                    ProviderLogger.i(TAG, m, "Reader delivered body HTML", "len" to body.length,
                        "hasLiDataIndex" to Regex("<li\\b[^>]*\\bdata-index", RegexOption.IGNORE_CASE).containsMatchIn(body))
                    body
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProviderLogger.e(TAG, m, "Bridge render failed", e)
            null
        } finally {
            try {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.clearHistory()
                webView.removeAllViews()
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                webView.destroy()
                ProviderLogger.d(TAG, m, "Sandbox WebView destroyed")
            } catch (_: Exception) {}
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun extractHtmlFromWebView(webView: WebView, selector: String?): String? {
        return suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val js = if (selector != null) {
                    val safeSelector = selector.replace("'", "\\'")
                    "(function(){ var el = document.querySelector('$safeSelector'); return el ? el.outerHTML : null; })();"
                } else {
                    "(function(){ return document.documentElement.outerHTML; })();"
                }
                webView.evaluateJavascript(js) { result ->
                    val html = try {
                        if (result == null || result == "null") null
                        else org.json.JSONTokener(result).nextValue().toString()
                    } catch (e: Exception) {
                        ProviderLogger.e(TAG, "extractHtml", "Parse error", e)
                        null
                    }
                    if (cont.isActive) cont.resume(html) {}
                }
            }
        }
    }

    private fun getCurrentUrlFromWebView(webView: WebView?): String? {
        return try { webView?.url } catch (_: Exception) { null }
    }

    private fun extractCookiesFromManager(url: String): Map<String, String> {
        if (url.isBlank()) return emptyMap()
        return try {
            val raw = CookieManager.getInstance().getCookie(url)
            if (!raw.isNullOrBlank()) parseCookieString(raw) else emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    private fun parseCookieString(cookie: String): Map<String, String> {
        return cookie.split(";").associate {
            val parts = it.split("=", limit = 2)
            (parts.getOrNull(0)?.trim() ?: "") to (parts.getOrNull(1)?.trim() ?: "")
        }.filter { it.key.isNotBlank() }
    }

    private fun createDialog(activity: android.app.Activity, webView: WebView): android.app.Dialog {
        val container = android.widget.FrameLayout(activity).apply {
            // Black, not white. This is what shows through whenever the WebView is not painting —
            // during load, and for the 900 ms `dipPageVisibility` hides it after a popup. On white that
            // reads as "the page navigated away to a blank page" (reported 2026-07-30 as exactly that);
            // on black it reads as a video app between frames. VideoSnifferEngine's dialog is already
            // black for the same reason.
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(webView.apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            // This dialog exists to be used: in FULLSCREEN the user drives the page by hand.
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
        })

        // The TV mouse is the input path, not a nicety. Server buttons and play controls have to be
        // clicked, and an injected `element.click()` produces an event with `isTrusted === false` —
        // which is precisely what a site checking for automation looks at. TvMouseController
        // dispatches real MotionEvents, so the page sees genuine touch input, and a D-pad remote can
        // drive the screen at all.
        tvMouseController = com.cloudstream.shared.ui.TvMouseController(activity, webView)
        tvMouseController?.attach(container)

        return android.app.Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen).apply {
            setContentView(container)
            setCancelable(true)
            window?.let { w ->
                @Suppress("DEPRECATION")
                w.decorView.systemUiVisibility = (
                        android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        )
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            setOnKeyListener { _, _, event ->
                // Back walks the surf history first, so a wrong tap on an ad is recoverable; only
                // once there is nothing to go back to does Back close the dialog.
                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                    event.action == android.view.KeyEvent.ACTION_UP &&
                    webView.canGoBack()
                ) {
                    webView.goBack()
                    true
                } else {
                    tvMouseController?.onKeyEvent(event) ?: false
                }
            }
            setOnDismissListener {
                tvMouseController?.detach()
                tvMouseController = null
                // A waiting step has to know, or closing the window leaves the caller polling until
                // its timeout with nothing on screen (WaitForCapturedVideo allows 300s).
                if (dismissingForCleanup) {
                    ProviderLogger.d(TAG, "createDialog", "Dialog dismissed by cleanup")
                } else {
                    dialogDismissedByUser = true
                    ProviderLogger.i(TAG, "createDialog", "Dialog dismissed by user — ending any wait")
                }
            }
        }
    }

    /**
     * Adds [webView] to the activity's content view, FULL SIZE but moved far off-screen and fully
     * transparent, so the page sees a normal browser viewport while nothing is visible and no touch
     * can land on it.
     *
     * Two constraints have to hold at once:
     *  - The WebView must be attached to a window. A detached one is reported to the page as
     *    `document.visibilityState === 'hidden'`, never fires requestAnimationFrame, and has its
     *    JS dialogs discarded.
     *  - It must NOT be tiny. CimaNow's decryptor gained this check (decoded 2026-07-25):
     *        if (window.innerWidth <= 15 && window.innerHeight <= 15) _isB = true;
     *    and when flagged it no longer aborts — it writes a decoy
     *    `<div id="watch"></div><div id="download"></div>` (exactly 47 chars, which is how it was
     *    caught: bodyLen - htmlLen == 47) so the scrape "succeeds" with zero servers. The previous
     *    1x1 attachment walked straight into it, and a detached WebView reports 0x0, which trips it
     *    too — hence full size plus an off-screen translation rather than a small view.
     *
     * Removal is handled by the caller's cleanup (`(webView.parent as? ViewGroup)?.removeView(...)`).
     */
    private fun attachVisibleFullSize(activity: android.app.Activity, webView: WebView) {
        try {
            val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            if (root == null) {
                ProviderLogger.w(TAG, "attachVisibleFullSize", "No content view — WebView stays detached (page will be 'hidden' AND 0x0)")
                return
            }
            // A REAL, visible, full-size WebView — not hidden by any means.
            //
            // The progression of what did not work, all with every documented gate condition
            // satisfied (2026-07-29):
            //   * translated 20000px off-screen → decoy. `window.screenX` reported the off-display
            //     coordinate, which no real browser window has.
            //   * on-screen at the origin but `alpha = 0f` → decoy, even with screen=0,0 confirmed
            //     in the reader diagnostics.
            // The one configuration known to render the real server list is an ordinary visible
            // fullscreen WebView. Since alpha is invisible to JavaScript, the remaining difference
            // between "alpha 0" and "visible" is whether the compositor actually paints and presents
            // frames — which the page CAN observe, through requestAnimationFrame cadence,
            // IntersectionObserver, element visibility and paint timing. A surface that never
            // presents is indistinguishable from a headless one by those measures.
            //
            // So this is deliberately fully visible and interactive: the closest thing to the
            // configuration that works. It is short-lived — the sandbox completes in well under a
            // second on success and is destroyed immediately after.
            webView.alpha = 1f
            webView.visibility = android.view.View.VISIBLE
            root.addView(
                webView,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            webView.bringToFront()
            root.requestLayout()
            ProviderLogger.i(TAG, "attachVisibleFullSize", "Sandbox WebView attached VISIBLE and full-size — the page is painted and presented, matching the configuration that renders the real server list")
        } catch (e: Exception) {
            ProviderLogger.w(TAG, "attachVisibleFullSize", "Attach failed: ${e.message}")
        }
    }

    private fun cleanupWebView(webView: WebView?, dialog: android.app.Dialog?) {
        try {
            dismissingForCleanup = true
            dialog?.dismiss()
            // Popup sinks outlive the page on purpose (the page holds references to them), so they
            // are only safe to destroy once the session itself is over.
            val sinks = synchronized(popupSinks) { popupSinks.toList().also { popupSinks.clear() } }
            if (sinks.isNotEmpty()) {
                Handler(Looper.getMainLooper()).post {
                    sinks.forEach { sink ->
                        try {
                            sink.stopLoading()
                            sink.destroy()
                        } catch (_: Exception) {}
                    }
                    ProviderLogger.d(TAG, "cleanupWebView", "Destroyed ${sinks.size} popup sink(s)")
                }
            }
            webView?.let { wv ->
                Handler(Looper.getMainLooper()).post {
                    try {
                        wv.stopLoading()
                        wv.loadUrl("about:blank")
                        wv.clearHistory()
                        wv.removeAllViews()
                        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                        wv.destroy()
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "NavigationEngine"

        /** Ceiling on concurrent `window.open` sinks, so a chatty ad page cannot spawn WebViews. */
        private const val MAX_POPUP_SINKS = 4

        /**
         * Ceiling on captured embeds. One per server the user tries is the real rate; anything beyond
         * this is a detection bug, and each capture costs a document fetch — 728 of them once.
         */
        private const val MAX_CAPTURED_EMBEDS = 12

        /** How long a loading popup sink lives before being destroyed (loadPopupsInSink mode only). */
        private const val POPUP_SINK_TTL_MS = 15_000L

        /**
         * How long the page is hidden after it opens a popup — see [dipPageVisibility].
         *
         * Must exceed the gate's own threshold, which is `0x320` = 800 ms in the decrypted page. Kept as
         * close to it as is safe: the user sees the dialog background for this whole window, and 1.2 s of
         * it was reported as the page having navigated away.
         */
        private const val POPUNDER_DWELL_MS = 900L

        /**
         * How much of a refusal body is kept for [InterceptChallenge.bodyPreview].
         *
         * Enough for Cloudflare's markers, which sit in the `<head>`, and far short of the ~128 KB a
         * real block page runs to — this is a diagnostic, not a copy of the response.
         */
        private const val CHALLENGE_BODY_PREVIEW_CHARS = 4096

        /**
         * How long after `onPageFinished` before a quiet page counts as inert.
         *
         * Long enough that a healthy page has unmistakably declared itself — the observed working runs
         * had 17, 25 and 54 subresource requests logged within a second of finishing — and short enough
         * that a stub costs seconds instead of the step's whole budget.
         *
         * Was 8 s. Cut to 5 s because the gap it discriminates on is not marginal (2 against 17-54, so a
         * healthy page is never near the floor) and because 8 s put the exit *past the end of a typical
         * logcat capture*: the 13:15 run's stub page finished at :22.2 and the log stopped at :27.8,
         * 2.4 s short of the bail-out. A fix nobody can see in the log gets re-debugged from scratch.
         */
        private const val INERT_PAGE_GRACE_MS = 5_000L

        /**
         * Subresource count below which a finished page is considered a stub. The gap between the two
         * outcomes is not subtle: 2 for the stub cimanow served, 17 and 54 for pages that worked.
         */
        private const val INERT_PAGE_SUBRESOURCE_FLOOR = 5

        /**
         * How long to keep waiting for a stream after a substantive embed has been captured.
         *
         * The healthy case needs ~2 s ("the stream follows the embed within ~2s"), and the 09:44 run saw
         * it at ~10 s. Fifteen covers both with room to spare, against a 300 s budget the user otherwise
         * spends looking at a spinner.
         */
        private const val EMBED_STREAM_GRACE_MS = 15_000L

        /**
         * Captured-HTML size above which an embed is worth acting on.
         *
         * Rule 21's counter-examples are all small: VK's `video_embed_error` page and the ad frames from
         * the 08-03 log (854 chars). A real player document is tens of KB — the VK embed here was 73,963.
         */
        private const val SUBSTANTIVE_EMBED_HTML_CHARS = 5_000

        /**
         * Pause before the plain retry of a 403'd asset.
         *
         * Long enough to fall outside a burst window — the four assets that fail arrive within
         * milliseconds of each other — and short enough not to delay a page render noticeably.
         */
        private const val ASSET_RETRY_DELAY_MS = 300L

        /**
         * Endpoint for the one-shot self-identification diagnostic, run on the first refusal.
         *
         * Cloudflare's trace returns plain text with `ip=`, `uag=`, `tls=` and `http=` as the *server*
         * sees them. That is the one thing left unmeasured: headers, cookies, timing and three separate
         * HTTP stacks have all been eliminated, and the app's own block pages have quoted IPs that do
         * not match what `adb shell curl` exits from on the same handset (`2001:16b8:…` and
         * `93.88.156.23` against the shell's `194.213.108.5`). If the app leaves by a different route
         * than the shell, that explains a 403 no header change can fix.
         *
         * `httpbin.org/headers` was tried first and refused the request outright; `postman-echo.com`
         * answers 200 from this handset and reports the full header set, including anything the stack
         * appends below our own code.
         */
        private const val HEADER_ECHO_URL = "https://postman-echo.com/get"

        // ── Sandbox exfiltration protocol (renderHtmlInSandbox) ──────────────────────────────
        // The in-page reader streams its payload back as console.log lines:
        //   <tag>B<chunkCount>|<totalChars>   <tag>C<i>|<chunk>   <tag>E
        // <tag> is random per run ON PURPOSE: the page's pre-decrypt gate greps every inline
        // script's source for known markers of ours (it currently matches the literals "__CSX__"
        // and "[RD]" from earlier versions of this reader) and refuses to decrypt on a hit. Never
        // reintroduce a fixed marker here.
        // Chunked because a single console message is not guaranteed to survive intact at
        // arbitrary length; if a run logs "Length mismatch", lower CSX_CHUNK_SIZE.
        private const val CSX_CHUNK_SIZE = 8000

        /**
         * Anti-anti-bot script injected at the top of <head> for cimanow.cc.
         * Runs before the page's own JS (including the anti-bot) and makes critical
         * prototype properties immutable, preventing the anti-bot from overriding
         * them to strip/clear server entries from the DOM.
         *
         * Protects:
         *   - DOMParser.prototype.parseFromString  (anti-bot strips <li> from parsed docs)
         *   - Element.prototype.querySelectorAll   (anti-bot returns empty for #watch queries)
         *   - Element.prototype.setAttribute       (anti-bot intercepts data-index writes)
         *   - Element.prototype.getAttribute       (anti-bot hides data-index reads)
         *   - Element.prototype.remove             (anti-bot removes server LIs)
         *   - HTMLElement.prototype.innerHTML      (anti-bot clears #watch.innerHTML)
         */
        /**
         * Anti-anti-bot script injected at the top of <head> for cimanow.cc.
         * Runs before the page's own JS and uses a THREE-STRATEGY approach:
         *
         * (A) DOMParser.prototype.parseFromString – getter/setter trap.
         *     The anti-bot script (3578-line inline block) contains BOTH the
         *     DOMParser override AND the legitimate decryption code in the same
         *     script. Freezing the property would crash the script (TypeError)
         *     and prevent the decryption from ever running.
         *     Instead, we install a getter that ALWAYS returns the original
         *     parseFromString, and a setter that silently discards any
         *     override attempt. The anti-bot assignment "succeeds" (no error),
         *     the script continues, and the legitimate code gets the real
         *     parseFromString that preserves <li> elements.
         *
         * (B) Element.prototype (querySelectorAll, setAttribute, getAttribute) – frozen.
         *     The anti-bot overrides these in separate scripts or in the external
         *     0CYA6X1KhKIS.js file. Freezing them prevents subsequent overrides.
         *     These are safe to freeze because they're in DIFFERENT script tags
         *     from the DOMParser trap (no crash cascade).
         *
         * (C) Element.prototype.remove + innerHTML – hooked.
         *     The anti-bot's MutationObserver walks the DOM and calls .remove()
         *     on LI[data-index] elements and sets #watch.innerHTML=''. These
         *     hooks block those cleanup actions, preserving the server entries
         *     in the DOM for the WaitForDomConditionAndSnapshot poll.
         */
        /**
         * Intercepts document.write to capture the decrypted page HTML (which contains
         * <li data-index=".." data-id=".."> server entries) BEFORE the anti-bot
         * generated script can strip them. The captured HTML is stored in
         * window.__decryptedHtml and consumed by the provider's snapshot step.
         */
        private val ANTI_ANTI_BOT_JS = """
            (function(){
                try {
                    var _origWrite = document.write.bind(document);
                    var _captured = false;
                    // Use a getter/setter accessor so document.write can NEVER be replaced,
                    // not even with Object.defineProperty (configurable:false).
                    // The getter always returns our wrapper; the setter wraps any replacement.
                    var _wrapper = function(html) {
                        console.log('[CW] document.write called: length=' + (html ? html.length : 0) + ' type=' + (typeof html) + ' captured=' + _captured);
                        if (html && typeof html === 'string' && html.length > 0) {
                            console.log('[CW] Write preview: ' + html.substring(0, 200).replace(/\n/g, '\\n'));
                        }
                        if (!_captured && html && typeof html === 'string' && html.length > 500) {
                            _captured = true;
                            window.__decryptedHtml = html;
                            console.log('[CW] Captured decrypted HTML: ' + html.length + ' chars');
                            console.log('[CW] Has data-index: ' + (html.indexOf('data-index') !== -1));
                            console.log('[CW] Has li tag: ' + (html.indexOf('<li') !== -1));
                        }
                        return _origWrite(html);
                    };
                    // Spoof native function signature — the decryption script checks
                    // document.write.toString().indexOf('[native code]') and bails if false.
                    _wrapper.toString = function() { return 'function write() { [native code] }'; };
                    try { Object.defineProperty(_wrapper, 'name', { value: 'write', configurable: true }); } catch(e) {}
                    try { Object.defineProperty(_wrapper, 'length', { value: 1, configurable: true }); } catch(e) {}
                    Object.defineProperty(document, 'write', {
                        get: function() { return _wrapper; },
                        set: function(v) {
                            // Anti-bot trying to replace document.write — wrap their function
                            var _newWrite = v;
                            console.log('[CW] document.write replaced by anti-bot, re-wrapping');
                            _wrapper = function(html) {
                                if (!_captured && html && typeof html === 'string' && html.length > 500) {
                                    _captured = true;
                                    window.__decryptedHtml = html;
                                    console.log('[CW] Captured decrypted HTML: ' + html.length + ' chars');
                                }
                                return _newWrite(html);
                            };
                            _wrapper.toString = function() { return 'function write() { [native code] }'; };
                        },
                        configurable: false
                    });
                    // Emergency fallback: poll body.innerHTML every 50ms up to 15 times
                    // (750ms total) to catch decrypted content if document.write
                    // somehow still gets bypassed.
                    var _pollCount = 0;
                    var _pollTimer = setInterval(function() {
                        _pollCount++;
                        if (_captured) { clearInterval(_pollTimer); return; }
                        if (document.body) {
                            var html = document.body.innerHTML;
                            if (html && html.length > 200 && html.indexOf('data-index') !== -1) {
                                window.__decryptedHtml = html;
                                _captured = true;
                                console.log('[CW] Emergency capture (poll #' + _pollCount + '): ' + html.length + ' chars');
                                clearInterval(_pollTimer);
                                return;
                            }
                            if (_pollCount === 1) {
                                console.log('[CW] Poll check #1: body.length=' + html.length + ' no data-index');
                            }
                        }
                        if (_pollCount >= 15) clearInterval(_pollTimer);
                    }, 50);
                    console.log('[CW] document.write hook active');
                    console.log('[CW] Write is getter: ' + (Object.getOwnPropertyDescriptor(document, 'write') !== undefined));
                    console.log('[CW] toString check: ' + document.write.toString().indexOf('[native code]'));
                    console.log('[CW] First 50 of toString: ' + document.write.toString().substring(0, 50).replace(/\n/g, '\\n'));
                    // Delayed check to see when body is populated
                    setTimeout(function() {
                        console.log('[CW] Delayed body check: length=' + (document.body ? document.body.innerHTML.length : 0));
                        if (document.body) {
                            console.log('[CW] Body has data-index: ' + (document.body.innerHTML.indexOf('data-index') !== -1));
                        }
                    }, 100);
                } catch(e) {
                    console.error('[CW] document.write hook failed: ' + e.message);
                }
                // Backup: block LI.remove in case the above doesn't capture (e.g.
                // if the page uses innerHTML instead of document.write).
                try {
                    var _remove = Element.prototype.remove;
                    Element.prototype.remove = function() {
                        if (this.tagName === 'LI' && (this.hasAttribute('data-index') || this.hasAttribute('data-id'))) {
                            console.log('[CW] Blocked LI remove (backup)');
                            return;
                        }
                        return _remove.call(this);
                    };
                    Object.defineProperty(Element.prototype, 'remove', {
                        configurable: false, writable: false, value: Element.prototype.remove
                    });
                } catch(e) {}
            })();
        """.trimIndent()

        private val SPOOFING_JS = """
            (function(){
                try { Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } }); } catch(e) {}
                try {
                    var od;
                    Object.defineProperty(window, 'DisableDevtool', {
                        get: function() {
                            return function(o) { o = o || {}; o.ignore = function() { return true; }; o.url = ""; o.timeOutUrl = ""; o.ondevtoolopen = function() {}; if (od) try { return od(o); } catch(e) {} };
                        },
                        set: function(v) { od = v; },
                        configurable: true
                    });
                } catch(e) {}
                try { Object.defineProperty(navigator, 'plugins', { get: function() { return [1,2,3,4,5]; } }); } catch(e) {}
                try { Object.defineProperty(navigator, 'languages', { get: function() { return ['ar-SA','en-US','en']; } }); } catch(e) {}
            })();
        """.trimIndent()
    }
}