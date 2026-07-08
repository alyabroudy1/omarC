package com.cloudstream.shared.webview

import com.cloudstream.shared.logging.ProviderLogger
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Generic, safe WebView helper for authorized browser automation/debugging.
 *
 * This version intentionally does NOT:
 * - rely on protected endpoint interception
 * - fetch page-owned AJAX iframe sources
 * - perform site-specific watch-link extraction
 *
 * It DOES:
 * - navigate to a page
 * - optionally wait for destination URL patterns
 * - dismiss common consent dialogs
 * - extract generic visible links
 * - extract generic explicit download/document links
 * - save raw page HTML
 * - emit very verbose logs
 */
class WebViewFlowHelper(
    private val navigationEngine: NavigationEngine
) {
    private val TAG = "WebViewFlowHelperSafe"

    data class Config(
        val userAgent: String = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        val overallTimeoutMs: Long = 120_000L,
        val allowedDomains: List<String> = emptyList(),
        val destinationLockPatterns: List<String> = emptyList(),
        val mode: Mode = Mode.FULLSCREEN,

        // Generic timing knobs
        val initialDelayMs: Long = 1_500L,
        val waitForDestinationMs: Long = 20_000L,
        val postConsentDelayMs: Long = 500L,
        val settleDelayMs: Long = 1_500L,

        // Extraction knobs
        val maxExtractedLinks: Int = 80
    )

    /**
     * Kept for compatibility with older calling code.
     * This safe helper does NOT populate iframe/server URLs.
     */
    data class ServerInfo(
        val name: String,
        val index: String,
        val id: String,
        val iframeUrl: String = ""
    )

    /**
     * Generic explicit download/document-like links.
     * Intentionally not focused on media-stream extraction.
     */
    data class DownloadInfo(
        val name: String,
        val url: String
    )

    data class GenericLinkInfo(
        val text: String,
        val url: String,
        val target: String,
        val rel: String,
        val visible: Boolean,
        val downloadAttr: String,
        val tag: String
    )

    data class FlowResult(
        val success: Boolean,
        val finalUrl: String,
        val servers: List<ServerInfo> = emptyList(),     // compatibility only; safe helper leaves empty
        val downloads: List<DownloadInfo> = emptyList(),
        val watchPageHtml: String? = null,
        val error: String? = null,

        // New generic debug/extraction outputs
        val debugDom: String? = null,
        val actions: List<GenericLinkInfo> = emptyList(),
        val consentResult: String? = null
    )

    /**
     * New generic API.
     */
    suspend fun navigateToPage(
        startUrl: String,
        config: Config = Config()
    ): FlowResult {
        logI(
            "navigateToPage",
            buildString {
                append("Starting generic flow. ")
                append("startUrl=${startUrl.take(120)}, ")
                append("allowedDomains=${config.allowedDomains.joinToString(",")}, ")
                append("destinationPatterns=${config.destinationLockPatterns.joinToString(",")}, ")
                append("timeoutMs=${config.overallTimeoutMs}, ")
                append("mode=${config.mode}, ")
                append("initialDelayMs=${config.initialDelayMs}, ")
                append("settleDelayMs=${config.settleDelayMs}, ")
                append("maxExtractedLinks=${config.maxExtractedLinks}")
            }
        )

        val destinationLockRegexes = config.destinationLockPatterns.map { Regex(it) }
        val steps = buildGenericStepList(startUrl, config)

        logI("navigateToPage", "Built ${steps.size} navigation steps")
        steps.forEachIndexed { index, step ->
            logD("navigateToPage", "step[$index]=${step.javaClass.simpleName}")
        }

        val navResult = navigationEngine.execute(
            steps = steps,
            userAgent = config.userAgent,
            mode = config.mode,
            overallTimeoutMs = config.overallTimeoutMs,
            allowedDomains = config.allowedDomains.toSet(),
            destinationLockPatterns = destinationLockRegexes
        )

        logI(
            "navigateToPage",
            buildString {
                append("Navigation result: ")
                append("success=${navResult.success}, ")
                append("finalUrl=${navResult.finalUrl.take(140)}, ")
                append("completedSteps=${navResult.completedSteps}, ")
                append("failedAt=${navResult.failedAtStep ?: "none"}, ")
                append("error=${navResult.error ?: "none"}, ")
                append("keys=${navResult.extractedHtml.keys.joinToString(",")}")
            }
        )

        val consentResult = navResult.extractedHtml["consent_result"]
        val debugDom = navResult.extractedHtml["debug_dom"]
        val actions = parseGenericLinks(navResult.extractedHtml["action_links"])
        val downloads = parseDownloads(navResult.extractedHtml["download_links"])
        val pageHtml = navResult.extractedHtml["page_raw"]

        logI(
            "navigateToPage",
            buildString {
                append("Parsed outputs: ")
                append("actions=${actions.size}, ")
                append("downloads=${downloads.size}, ")
                append("pageHtmlLen=${pageHtml?.length ?: 0}, ")
                append("consentResult=${consentResult ?: "null"}")
            }
        )

        actions.forEachIndexed { i, link ->
            logD(
                "navigateToPage",
                "action[$i] text='${link.text.take(60)}' url=${link.url.take(120)} visible=${link.visible} target=${link.target}"
            )
        }

        downloads.forEachIndexed { i, dl ->
            logD(
                "navigateToPage",
                "download[$i] name='${dl.name.take(60)}' url=${dl.url.take(120)}"
            )
        }

        return FlowResult(
            success = navResult.success,
            finalUrl = navResult.finalUrl,
            servers = emptyList(), // intentionally empty in safe helper
            downloads = downloads,
            watchPageHtml = pageHtml,
            error = if (navResult.success) null else "Navigation failed at step ${navResult.failedAtStep}: ${navResult.error}",
            debugDom = debugDom,
            actions = actions,
            consentResult = consentResult
        )
    }

    /**
     * Compatibility wrapper for older provider code.
     *
     * NOTE:
     * - This now behaves as a generic page-navigation snapshot helper.
     * - It does NOT fetch server iframe URLs or protected watch links.
     * - `servers` is intentionally empty.
     */
    suspend fun navigateMovieToWatchPage(
        movieUrl: String,
        config: Config = Config()
    ): FlowResult {
        logW(
            "navigateMovieToWatchPage",
            "Compatibility wrapper invoked. This safe helper no longer performs site-specific server/iframe extraction."
        )
        return navigateToPage(movieUrl, config)
    }

    // ---------------------------------------------------------------------------------------------
    // Step building
    // ---------------------------------------------------------------------------------------------

    private fun buildGenericStepList(
        startUrl: String,
        config: Config
    ): List<NavigationStep> {
        val steps = mutableListOf<NavigationStep>()

        // 0) Load the initial page
        steps += NavigationStep.LoadUrl(startUrl)

        // 1) Give the page a moment to initialize
        steps += NavigationStep.WaitForDelay(config.initialDelayMs)

        // 2) Optionally wait for declared destination patterns
        config.destinationLockPatterns.forEachIndexed { i, pattern ->
            steps += NavigationStep.WaitForUrl(
                urlPattern = pattern,
                timeoutMs = config.waitForDestinationMs,
                abortOnFailure = false
            )
            steps += NavigationStep.WaitForDelay(500L)
            logD("buildGenericStepList", "Added destination wait[$i] pattern=$pattern")
        }

        // 3) Dismiss consent/popups generically
        steps += NavigationStep.ExecuteJs(
            javascript = JS_DISMISS_CONSENT,
            key = "consent_result"
        )

        // 4) Let the DOM settle after dismissals/clicks
        steps += NavigationStep.WaitForDelay(config.postConsentDelayMs)

        // 5) Snapshot/debug page state
        steps += NavigationStep.ExecuteJs(
            javascript = JS_DEBUG_DOM_SUMMARY,
            key = "debug_dom"
        )

        // 6) Extract generic visible links
        steps += NavigationStep.ExecuteJs(
            javascript = jsExtractGenericLinks(config.maxExtractedLinks),
            key = "action_links"
        )

        // 7) Extract explicit download/document-like links only
        steps += NavigationStep.ExecuteJs(
            javascript = jsExtractDownloadLinks(config.maxExtractedLinks),
            key = "download_links"
        )

        // 8) Wait briefly, then dump full HTML
        steps += NavigationStep.WaitForDelay(config.settleDelayMs)
        steps += NavigationStep.ExtractHtml(key = "page_raw")

        return steps
    }

    // ---------------------------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------------------------

    private fun parseGenericLinks(raw: String?): List<GenericLinkInfo> {
        if (raw.isNullOrBlank()) {
            logW("parseGenericLinks", "No raw action_links value")
            return emptyList()
        }

        return try {
            val cleaned = JSONTokener(raw).nextValue().toString()
            val arr = JSONArray(cleaned)
            val list = mutableListOf<GenericLinkInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list += GenericLinkInfo(
                    text = obj.optString("text", ""),
                    url = obj.optString("url", ""),
                    target = obj.optString("target", ""),
                    rel = obj.optString("rel", ""),
                    visible = obj.optBoolean("visible", false),
                    downloadAttr = obj.optString("download", ""),
                    tag = obj.optString("tag", "")
                )
            }
            logI("parseGenericLinks", "Parsed ${list.size} action links")
            list
        } catch (e: Exception) {
            logW("parseGenericLinks", "Failed: ${e.message} raw=${raw.take(240)}")
            emptyList()
        }
    }

    private fun parseDownloads(raw: String?): List<DownloadInfo> {
        if (raw.isNullOrBlank()) {
            logW("parseDownloads", "No raw download_links value")
            return emptyList()
        }

        return try {
            val cleaned = JSONTokener(raw).nextValue().toString()
            val arr = JSONArray(cleaned)
            val list = mutableListOf<DownloadInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list += DownloadInfo(
                    name = obj.optString("name", ""),
                    url = obj.optString("url", "")
                )
            }
            logI("parseDownloads", "Parsed ${list.size} downloads")
            list
        } catch (e: Exception) {
            logW("parseDownloads", "Failed: ${e.message} raw=${raw.take(240)}")
            emptyList()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------------------------------

    private fun logD(method: String, message: String) {
        try { ProviderLogger.d(TAG, method, message) } catch (_: Exception) {}
    }

    private fun logI(method: String, message: String) {
        try { ProviderLogger.i(TAG, method, message) } catch (_: Exception) {}
    }

    private fun logW(method: String, message: String) {
        try { ProviderLogger.w(TAG, method, message) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------------------------------------
    // Generic JS helpers
    // ---------------------------------------------------------------------------------------------

    companion object {
        /**
         * Generic consent / popup dismissal.
         * Very broad, browser-safe, and verbose.
         */
        val JS_DISMISS_CONSENT = """
(function() {
    try {
        var clicked = [];
        var seen = [];
        var keywords = [
            'accept','allow','agree','continue','confirm','close','dismiss','ok','got it',
            'accept all','i agree','consent',
            'أوافق','موافق','قبول','استمرار','متابعة','اغلاق','إغلاق'
        ];

        function isVisible(el) {
            if (!el) return false;
            try {
                var rect = el.getBoundingClientRect();
                var style = window.getComputedStyle(el);
                return rect.width > 0 &&
                       rect.height > 0 &&
                       style.visibility !== 'hidden' &&
                       style.display !== 'none';
            } catch(e) {
                return false;
            }
        }

        function maybeClick(el, reason) {
            try {
                if (!el || !isVisible(el)) return false;
                var text = ((el.innerText || el.textContent || '').trim().toLowerCase()).slice(0, 80);
                var sig = el.tagName + '|' + text + '|' + reason;
                if (seen.indexOf(sig) !== -1) return false;
                seen.push(sig);
                try { el.click(); } catch(e) {}
                clicked.push({ tag: el.tagName, text: text, reason: reason });
                console.log('[WVSafe] consent click', sig);
                return true;
            } catch(e) {
                return false;
            }
        }

        var selectors = [
            'button',
            'a[role="button"]',
            'a',
            '[role="button"]',
            '.fc-button',
            '.fc-cta-consent',
            '.cc-btn',
            '.cookie-btn',
            '.accept-btn',
            '.agree-btn',
            '.consent-btn',
            '.close',
            '.close-btn',
            '.modal-close',
            '.popup-close',
            '[onclick*="close"]',
            '[aria-label*="close" i]'
        ];

        for (var s = 0; s < selectors.length; s++) {
            var els = document.querySelectorAll(selectors[s]);
            for (var i = 0; i < els.length; i++) {
                var el = els[i];
                var text = ((el.innerText || el.textContent || '').trim().toLowerCase());
                var matched = false;

                if (text && text.length <= 80) {
                    for (var k = 0; k < keywords.length; k++) {
                        if (text.indexOf(keywords[k]) !== -1) {
                            matched = true;
                            break;
                        }
                    }
                }

                if (matched) {
                    maybeClick(el, 'keyword');
                }
            }
        }

        return JSON.stringify({
            dismissed: clicked.length > 0,
            count: clicked.length,
            clicks: clicked
        });
    } catch (e) {
        return JSON.stringify({
            dismissed: false,
            error: e.message || 'unknown'
        });
    }
})();
        """.trimIndent()

        /**
         * Generic page state summary for debugging.
         */
        val JS_DEBUG_DOM_SUMMARY = """
(function() {
    try {
        function count(sel) {
            try { return document.querySelectorAll(sel).length; } catch(e) { return -1; }
        }

        var summary = {
            url: location.href || '',
            origin: location.origin || '',
            title: document.title || '',
            readyState: document.readyState || '',
            bodyHtmlLength: document.body ? (document.body.innerHTML || '').length : -1,
            counts: {
                links: count('a[href]'),
                buttons: count('button'),
                iframes: count('iframe'),
                forms: count('form'),
                inputs: count('input'),
                scripts: count('script'),
                videos: count('video'),
                sources: count('source')
            }
        };

        console.log('[WVSafe] DOM summary', summary);
        return JSON.stringify(summary);
    } catch (e) {
        return JSON.stringify({
            error: e.message || 'unknown'
        });
    }
})();
        """.trimIndent()

        /**
         * Generic visible link extraction.
         * Collects only normal http/https links and basic metadata.
         */
        fun jsExtractGenericLinks(maxLinks: Int): String {
            return """
(function() {
    try {
        function isVisible(el) {
            if (!el) return false;
            try {
                var rect = el.getBoundingClientRect();
                var style = window.getComputedStyle(el);
                return rect.width > 0 &&
                       rect.height > 0 &&
                       style.visibility !== 'hidden' &&
                       style.display !== 'none';
            } catch(e) {
                return false;
            }
        }

        var anchors = document.querySelectorAll('a[href]');
        var out = [];

        for (var i = 0; i < anchors.length; i++) {
            if (out.length >= $maxLinks) break;

            var a = anchors[i];
            var href = a.href || '';
            if (!href) continue;
            if (!/^https?:/i.test(href)) continue;
            if (/^javascript:/i.test(href)) continue;

            var text = ((a.innerText || a.textContent || '').trim()).replace(/\s+/g, ' ').slice(0, 160);
            out.push({
                tag: a.tagName || 'A',
                text: text,
                url: href,
                target: a.target || '',
                rel: a.rel || '',
                visible: isVisible(a),
                download: a.getAttribute('download') || ''
            });
        }

        console.log('[WVSafe] extracted generic links', out.length);
        return JSON.stringify(out);
    } catch (e) {
        return JSON.stringify([{ error: e.message || 'unknown' }]);
    }
})();
            """.trimIndent()
        }

        /**
         * Generic explicit download/document-like link extraction.
         *
         * Intentionally excludes streaming-specific logic.
         * Focuses on:
         * - [download] attribute
         * - common document/archive file extensions
         */
        fun jsExtractDownloadLinks(maxLinks: Int): String {
            return """
(function() {
    try {
        function isVisible(el) {
            if (!el) return false;
            try {
                var rect = el.getBoundingClientRect();
                var style = window.getComputedStyle(el);
                return rect.width > 0 &&
                       rect.height > 0 &&
                       style.visibility !== 'hidden' &&
                       style.display !== 'none';
            } catch(e) {
                return false;
            }
        }

        var links = document.querySelectorAll('a[href]');
        var out = [];
        var exts = /\.(pdf|zip|rar|7z|doc|docx|xls|xlsx|ppt|pptx|txt|csv)(\?|#|$)/i;

        for (var i = 0; i < links.length; i++) {
            if (out.length >= $maxLinks) break;

            var a = links[i];
            var href = a.href || '';
            if (!href) continue;
            if (!/^https?:/i.test(href)) continue;

            var hasDownloadAttr = !!a.getAttribute('download');
            var looksLikeDoc = exts.test(href);

            if (!hasDownloadAttr && !looksLikeDoc) continue;

            out.push({
                name: ((a.innerText || a.textContent || '').trim()).replace(/\s+/g, ' ').slice(0, 160),
                url: href,
                visible: isVisible(a),
                download: a.getAttribute('download') || ''
            });
        }

        console.log('[WVSafe] extracted download links', out.length);
        return JSON.stringify(out);
    } catch (e) {
        return JSON.stringify([{ error: e.message || 'unknown' }]);
    }
})();
            """.trimIndent()
        }
    }
}