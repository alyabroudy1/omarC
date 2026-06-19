package com.cloudstream.shared.webview

import com.cloudstream.shared.logging.ProviderLogger
import org.json.JSONTokener

/**
 * Reusable WebView navigation flow helper.
 *
 * Builds step sequences for common provider flows (freex2line → watching page)
 * and executes them through [NavigationEngine] with comprehensive debug logging.
 *
 * ## Architecture
 *
 * ```
 * Provider (CimaNow, etc.)
 *   ↕  resolveFreex2line/intermediate → intermediateUrl
 *   ↕  WebViewFlowHelper
 *        ↕  NavigationEngine (step executor)
 *             ↕  WebView (actual browser)
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * val helper = WebViewFlowHelper(navigationEngine)
 * val result = helper.navigateToWatchPage(
 *     intermediateUrl = "https://rm.freex2line.online/2020/02/blog-post.html/",
 *     referer = "https://cimanow.cc/some-movie/",
 *     config = FlowConfig(
 *         // allowedDomains = setOf("cimanow.cc", "freex2line.online", "href.li"),
 *         // destinationLockPatterns = listOf(Regex("/watching/")),
 *     )
 * )
 *
 * if (result.success) {
 *     // Use result.servers, result.downloads, result.watchPageHtml etc.
 * }
 * ```
 */
class WebViewFlowHelper(
    private val navigationEngine: NavigationEngine
) {
    private val TAG = "WebViewFlowHelper"

    /**
     * Configuration for a complete navigation flow.
     */
    data class FlowConfig(
        val overallTimeoutMs: Long = 120_000L,
        val mode: Mode = Mode.HEADLESS,
        /** CF-safe wait for first real content; extended for CF auto-solve */
        val initialWaitTimeoutMs: Long = 45_000L,
        /** How long to wait after triggering AJAX server fetches */
        val serverFetchDelayMs: Long = 6_000L,
        /** How long to wait after dismissing consent popup */
        val consentDismissDelayMs: Long = 2_000L,
        /** Max servers to request via AJAX (avoid timeout on long lists) */
        val maxServersToFetch: Int = 10,
        /** CSS selector for the consent popup to dismiss */
        val consentSelector: String = "",
        /** By default, only allow these domains to navigate (empty = all) */
        val allowedDomains: Set<String> = emptySet(),
        /** Once URL matches these patterns, lock navigation */
        val destinationLockPatterns: List<Regex> = emptyList(),
        /** Custom UA (null = use NavigationEngine default) */
        val userAgent: String = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        /** CSS selector for the link that leads from freex2line to watching page */
        val serverLinkSelector: String =
            "a[href*='get-link'], a[href*='download'], a[href*='watch'], a.continue-btn, a[href*='server']",
        /** CSS selector for the server list on the watching page */
        val watchListSelector: String = "#watch li[data-index], li[data-index], [data-index]"
    )

    /**
     * Parsed server info from the watching page.
     */
    data class ServerInfo(
        val name: String,
        val index: String,
        val id: String,
        val iframeUrl: String = ""
    )

    /**
     * Parsed download link info.
     */
    data class DownloadInfo(
        val name: String,
        val url: String
    )

    /**
     * Complete result of a navigation flow.
     */
    data class FlowResult(
        val success: Boolean,
        val finalUrl: String,
        val watchPageHtml: String?,
        val servers: List<ServerInfo>,
        val downloads: List<DownloadInfo>,
        val allExtractedHtml: Map<String, String>,
        val stepResults: Map<String, String>,
        val completedSteps: Int,
        val failedAtStep: Int?,
        val error: String?
    )

    // ──────────────────────────────────────────────
    //  HIGH-LEVEL ENTRY POINTS
    // ──────────────────────────────────────────────

    /**
     * Run the complete flow: navigate to intermediate page → dismiss consent →
     * click server link → arrive at watching page → extract servers → extract downloads.
     *
     * @param intermediateUrl The freex2line blog-post URL (or similar intermediate page)
     * @param referer The original movie/detail page URL
     * @param config Flow configuration
     * @return [FlowResult] with parsed servers, downloads, and extracted HTML
     */
    suspend fun navigateToWatchPage(
        intermediateUrl: String,
        referer: String,
        config: FlowConfig = FlowConfig()
    ): FlowResult {
        ProviderLogger.i(TAG, "navigateToWatchPage", "========== FLOW START ==========",
            "intermediateUrl" to intermediateUrl.take(100),
            "referer" to referer.take(80),
            "userAgent" to config.userAgent.take(60),
            "mode" to config.mode.name)

        // Phase 1: Navigate from freex2line to the watching page
        val navSteps = buildPhase1Steps(intermediateUrl, referer, config)
        ProviderLogger.i(TAG, "navigateToWatchPage", "Phase 1: ${navSteps.size} steps (intermediate → watch page)")

        val phase1Result = navigationEngine.execute(
            steps = navSteps,
            userAgent = config.userAgent,
            mode = config.mode,
            overallTimeoutMs = config.overallTimeoutMs,
            allowedDomains = config.allowedDomains,
            destinationLockPatterns = config.destinationLockPatterns
        )

        ProviderLogger.i(TAG, "navigateToWatchPage", "Phase 1 result: success=${phase1Result.success}, " +
            "finalUrl=${phase1Result.finalUrl.take(100)}, " +
            "completedSteps=${phase1Result.completedSteps}, " +
            "failedAt=${phase1Result.failedAtStep}, " +
            "error=${phase1Result.error}")

        if (!phase1Result.success) {
            ProviderLogger.w(TAG, "navigateToWatchPage", "Phase 1 failed — returning partial result")
            return FlowResult(
                success = false,
                finalUrl = phase1Result.finalUrl,
                watchPageHtml = phase1Result.extractedHtml.values.firstOrNull(),
                servers = emptyList(),
                downloads = emptyList(),
                allExtractedHtml = phase1Result.extractedHtml,
                stepResults = phase1Result.extractedHtml,
                completedSteps = phase1Result.completedSteps,
                failedAtStep = phase1Result.failedAtStep,
                error = "Phase 1 failed at step ${phase1Result.failedAtStep}: ${phase1Result.error}"
            )
        }

        val finalUrl = phase1Result.finalUrl

        // Phase 2: Extract server metadata from the watching page
        ProviderLogger.i(TAG, "navigateToWatchPage", "Phase 2: Extracting server metadata from watch page")
        val servers = extractServers(config)

        // Phase 3: AJAX fetch iframe URLs for each server
        ProviderLogger.i(TAG, "navigateToWatchPage", "Phase 3: AJAX-fetching iframe URLs for ${servers.size} servers")
        val serversWithIframes = fetchServerIframes(servers, config)

        // Phase 4: Extract download links
        ProviderLogger.i(TAG, "navigateToWatchPage", "Phase 4: Extracting download links")
        val downloads = extractDownloadLinks(config)

        // Phase 5: Full page snapshot
        ProviderLogger.i(TAG, "navigateToWatchPage", "Phase 5: Capturing full page snapshot")
        val snapshotSteps = listOf(
            NavigationStep.ExtractHtml(key = "watch_page_full")
        )
        val snapshotResult = navigationEngine.execute(
            steps = snapshotSteps,
            userAgent = config.userAgent,
            mode = config.mode,
            overallTimeoutMs = 15_000L,
            allowedDomains = config.allowedDomains,
            destinationLockPatterns = config.destinationLockPatterns
        )
        val watchPageHtml = snapshotResult.extractedHtml["watch_page_full"]
            ?: phase1Result.extractedHtml.values.firstOrNull()

        ProviderLogger.i(TAG, "navigateToWatchPage",
            "========== FLOW COMPLETE ==========",
            "success" to true.toString(),
            "finalUrl" to finalUrl.take(100),
            "servers" to serversWithIframes.size.toString(),
            "downloads" to downloads.size.toString(),
            "watchHtmlLen" to (watchPageHtml?.length ?: 0).toString())

        return FlowResult(
            success = true,
            finalUrl = finalUrl,
            watchPageHtml = watchPageHtml,
            servers = serversWithIframes,
            downloads = downloads,
            allExtractedHtml = phase1Result.extractedHtml + snapshotResult.extractedHtml,
            stepResults = phase1Result.extractedHtml,
            completedSteps = phase1Result.completedSteps,
            failedAtStep = null,
            error = null
        )
    }

    // ──────────────────────────────────────────────
    //  PHASE BUILDERS
    // ──────────────────────────────────────────────

    /**
     * Build Phase 1 steps: navigate from freex2line/blog-post page to watching page.
     *
     * Steps:
     *   0. Load the intermediate URL
     *   1. Wait for page content (CF-safe — waits up to 45s for freex2line link)
     *   2. Dismiss consent popup
     *   3. Wait for visible server link
     *   4. Click server link (redirects through href.li → back to cimanow.cc/watching/)
     *   5. Wait for watching page URL
     *   6. Wait for server list elements to appear
     */
    /**
     * Build Phase 1 steps: navigate from freex2line/blog-post page to watching page.
     *
     * Steps:
     *   0. Load the intermediate URL with referer
     *   1. Wait for redirect to blog-post.html (loadon → href.li → redirectingfree → blog-post)
     *   2. If stuck on redirectingfree, navigate directly to blog-post.html
     *   3. Wait again for blog-post.html
     *   4. Dismiss consent popup if present
     *   5. Wait for a visible server link (post-consent, CF-safe extended poll)
     *   6. Click server link (redirects through href.li → cimanow.cc/watching/)
     *   7. Wait for watching page URL
     *   8. Wait for server list elements on watching page
     */
    private fun buildPhase1Steps(
        url: String,
        referer: String,
        config: FlowConfig
    ): List<NavigationStep> {
        return listOf(
            // Step 0: Load the intermediate page with correct referer
            NavigationStep.LoadUrl(url, referer = referer),
            // Step 1: Wait for redirect chain to reach blog-post.html
            NavigationStep.WaitForUrl("blog-post\\.html", timeoutMs = 30_000L, abortOnFailure = false),
            // Step 2: If stuck on redirectingfree, try direct nav to blog-post
            NavigationStep.ExecuteJs(
                javascript = JS_REDIRECTINGFREE_RECOVERY,
                key = "redirect_recovery"
            ),
            NavigationStep.WaitForDelay(5_000L),
            // Step 3: Wait again for blog-post.html (after potential recovery nav)
            NavigationStep.WaitForUrl("blog-post\\.html", timeoutMs = 20_000L, abortOnFailure = false),
            // Step 4: Dismiss consent popup
            NavigationStep.ExecuteJs(
                javascript = JS_DISMISS_CONSENT,
                key = "consent_dismissed"
            ),
            NavigationStep.WaitForDelay(config.consentDismissDelayMs),
            // Step 5: Wait for a visible server link (CF-safe extended poll)
            NavigationStep.WaitForDomCondition(
                jsCondition = JS_VISIBLE_SERVER_LINK_CONDITION
                    .replace("__SERVER_LINK_SELECTOR__", config.serverLinkSelector),
                timeoutMs = config.initialWaitTimeoutMs,
                pollIntervalMs = 500L
            ),
            // Step 6: Click server link
            NavigationStep.ClickElement(
                selector = config.serverLinkSelector,
                timeoutMs = 5_000L
            ),
            // Step 7: Wait for watching page URL
            NavigationStep.WaitForUrl("/watching/", timeoutMs = 30_000L),
            // Step 8: Wait for server list elements on watching page
            NavigationStep.WaitForSelector(
                selector = config.watchListSelector,
                timeoutMs = 15_000L
            )
        )
    }

    // ──────────────────────────────────────────────
    //  SERVER EXTRACTION
    // ──────────────────────────────────────────────

    /**
     * Extract server metadata (name, index, id) from the current watching page.
     * Runs as a separate NavigationEngine flow on the current page.
     */
    private suspend fun extractServers(
        config: FlowConfig
    ): List<ServerInfo> {
        val steps = listOf(
            NavigationStep.ExecuteJs(
                javascript = JS_EXTRACT_SERVERS
                    .replace("__WATCH_LIST_SELECTOR__", config.watchListSelector),
                key = "server_list_raw"
            )
        )

        val result = navigationEngine.execute(
            steps = steps,
            userAgent = config.userAgent,
            mode = config.mode,
            overallTimeoutMs = 10_000L,
            allowedDomains = config.allowedDomains,
            destinationLockPatterns = config.destinationLockPatterns
        )

        val raw = result.extractedHtml["server_list_raw"] ?: return emptyList()
        val servers = parseServerList(raw)

        ProviderLogger.i(TAG, "extractServers", "Found ${servers.size} servers",
            "raw" to raw.take(200))

        servers.forEachIndexed { i, s ->
            ProviderLogger.d(TAG, "extractServers", "  server[$i]: name='${s.name}' index=${s.index} id=${s.id}")
        }

        return servers
    }

    /**
     * For each server, AJAX-fetch the iframe URL using the same endpoint
     * the CimaNow page uses on click.
     */
    private suspend fun fetchServerIframes(
        servers: List<ServerInfo>,
        config: FlowConfig
    ): List<ServerInfo> {
        if (servers.isEmpty()) return emptyList()

        val fetchJs = buildServerFetchJs(servers, config.maxServersToFetch)

        val steps = listOf(
            NavigationStep.ExecuteJs(
                javascript = fetchJs,
                key = "server_fetch_initiated"
            ),
            NavigationStep.WaitForDelay(config.serverFetchDelayMs),
            NavigationStep.ExecuteJs(
                javascript = "(function(){ return window._serverResults || '[]'; })();",
                key = "server_iframe_results"
            )
        )

        val result = navigationEngine.execute(
            steps = steps,
            userAgent = config.userAgent,
            mode = config.mode,
            overallTimeoutMs = 30_000L,
            allowedDomains = config.allowedDomains,
            destinationLockPatterns = config.destinationLockPatterns
        )

        val rawIframes = result.extractedHtml["server_iframe_results"] ?: return servers
        val iframeMap = parseIframeResults(rawIframes)

        ProviderLogger.i(TAG, "fetchServerIframes", "Resolved ${iframeMap.size} iframe URLs")

        return servers.mapIndexed { index, server ->
            val iframe = iframeMap[index] ?: ""
            if (iframe.isNotBlank()) {
                ProviderLogger.d(TAG, "fetchServerIframes", "  ${server.name} -> ${iframe.take(80)}")
            } else {
                ProviderLogger.w(TAG, "fetchServerIframes", "  ${server.name} -> NO iframe URL")
            }
            server.copy(iframeUrl = iframe)
        }
    }

    /**
     * Extract download links from the watching page.
     */
    private suspend fun extractDownloadLinks(
        config: FlowConfig
    ): List<DownloadInfo> {
        val steps = listOf(
            NavigationStep.ExecuteJs(
                javascript = JS_EXTRACT_DOWNLOADS,
                key = "download_links_raw"
            )
        )

        val result = navigationEngine.execute(
            steps = steps,
            userAgent = config.userAgent,
            mode = config.mode,
            overallTimeoutMs = 10_000L,
            allowedDomains = config.allowedDomains,
            destinationLockPatterns = config.destinationLockPatterns
        )

        val raw = result.extractedHtml["download_links_raw"] ?: return emptyList()
        val downloads = parseDownloadList(raw)

        ProviderLogger.i(TAG, "extractDownloadLinks", "Found ${downloads.size} download links")

        downloads.forEach { d ->
            ProviderLogger.d(TAG, "extractDownloadLinks", "  name='${d.name}' url=${d.url.take(80)}")
        }

        return downloads
    }

    // ──────────────────────────────────────────────
    //  PARSERS
    // ──────────────────────────────────────────────

    /**
     * Parse the JSON returned by JS_EXTRACT_SERVERS into ServerInfo list.
     */
    private fun parseServerList(raw: String): List<ServerInfo> {
        return try {
            val cleaned = JSONTokener(raw).nextValue().toString()
            val jsonArray = org.json.JSONArray(cleaned)
            (0 until jsonArray.length()).mapNotNull { i ->
                val obj = jsonArray.getJSONObject(i)
                ServerInfo(
                    name = obj.optString("name", ""),
                    index = obj.optString("index", ""),
                    id = obj.optString("id", "")
                )
            }
        } catch (e: Exception) {
            ProviderLogger.w(TAG, "parseServerList", "Failed to parse server list: ${e.message}", "raw" to raw.take(200))
            emptyList()
        }
    }

    /**
     * Parse the JSON returned by the iframe fetch into a map of index → iframe URL.
     */
    private fun parseIframeResults(raw: String): Map<Int, String> {
        return try {
            val cleaned = JSONTokener(raw).nextValue().toString()
            val jsonArray = org.json.JSONArray(cleaned)
            (0 until jsonArray.length()).associate { i ->
                val obj = jsonArray.getJSONObject(i)
                i to obj.optString("iframe", "")
            }
        } catch (e: Exception) {
            ProviderLogger.w(TAG, "parseIframeResults", "Failed to parse iframe results: ${e.message}", "raw" to raw.take(200))
            emptyMap()
        }
    }

    /**
     * Parse the JSON returned by JS_EXTRACT_DOWNLOADS into DownloadInfo list.
     */
    private fun parseDownloadList(raw: String): List<DownloadInfo> {
        return try {
            val cleaned = JSONTokener(raw).nextValue().toString()
            val jsonArray = org.json.JSONArray(cleaned)
            (0 until jsonArray.length()).mapNotNull { i ->
                val obj = jsonArray.getJSONObject(i)
                DownloadInfo(
                    name = obj.optString("name", ""),
                    url = obj.optString("url", "")
                )
            }
        } catch (e: Exception) {
            ProviderLogger.w(TAG, "parseDownloadList", "Failed to parse download list: ${e.message}", "raw" to raw.take(200))
            emptyList()
        }
    }

    // ──────────────────────────────────────────────
    //  JS SNIPPET BUILDERS
    // ──────────────────────────────────────────────

    /**
     * Build the JS that fires fetch() for each server and stores results
     * in window._serverResults as a JSON array of {name, index, iframe, responseLength}.
     */
    private fun buildServerFetchJs(
        servers: List<ServerInfo>,
        maxServers: Int
    ): String {
        val items = servers.take(maxServers)
        val sb = StringBuilder()
        sb.append("""(function(){
var items = """ + items.map { """{index:"${it.index}",id:"${it.id}",name:"${it.name.replace("\"", "\\\"")}"}""" }.joinToString(",") + """;
var baseUrl = window.location.origin;
var results = [];
var done = 0;
var total = items.length;
for (var i = 0; i < total; i++) {
    var url = baseUrl + '/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=' + items[i].index + '&id=' + items[i].id;
    console.log('[WFH] Fetching server ' + items[i].name + ': ' + url);
    (function(idx, srvName, ajaxUrl) {
        fetch(ajaxUrl, {credentials: 'include', headers: {'X-Requested-With': 'XMLHttpRequest', 'Referer': window.location.href}})
            .then(function(r) { return r.text(); })
            .then(function(html) {
                var iframeSrc = '';
                var match = html.match(/<iframe[^>]+src=["']([^"']+)["']/);
                if (match) iframeSrc = match[1];
                console.log('[WFH] Server ' + srvName + ' iframe: ' + iframeSrc);
                results[idx] = {name: srvName, index: items[idx].index, iframe: iframeSrc, responseLength: html.length};
                done++;
                if (done >= total) window._serverResults = JSON.stringify(results.filter(function(r){return r!=null;}));
            })
            .catch(function(err) {
                console.log('[WFH] Server ' + srvName + ' error: ' + err.message);
                results[idx] = {name: srvName, index: items[idx].index, iframe: '', error: err.message};
                done++;
            });
    })(i, items[i].name, url);
}
return 'fetching_' + total + '_servers';
})();
""")
        return sb.toString()
    }

    companion object {
        // ──────────────────────────────────────────────
        //  CONSENT/DISMISS JS
        // ──────────────────────────────────────────────

        /**
         * Dismisses common consent/overlay popups on freex2line pages.
         * Uses text-matching to find and click close buttons (more reliable
         * than CSS selectors which change frequently).
         */
        // ──────────────────────────────────────────────
        //  REDIRECTINGFREE RECOVERY JS
        // ──────────────────────────────────────────────

        /**
         * If the redirect chain gets stuck at /redirectingfree/,
         * navigate directly to the blog-post.html URL.
         */
        val JS_REDIRECTINGFREE_RECOVERY = """
            (function(){
                var url = window.location.href || '';
                if (url.indexOf('redirectingfree') !== -1 || url.indexOf('loadon') !== -1) {
                    console.log('[WFH] Stuck on intermediate page, navigating to blog-post...');
                    window.location.href = 'https://rm.freex2line.online/2020/02/blog-post.html/';
                    return 'navigating_to_blog_post';
                }
                if (url.indexOf('blog-post') !== -1) {
                    return 'already_on_blog_post';
                }
                return 'url:' + url;
            })();
        """.trimIndent()

        val JS_DISMISS_CONSENT = """
            (function(){
                var closed = 0;
                // Common overlay close button patterns (text-based, CSS changes less)
                var closePatterns = ['Close', 'close', 'إغلاق', 'اغلاق', '×', '✕', '✖', 'X', 'موافق', 'Allow', 'allow'];
                // Try clicking elements with text matching
                var allElements = document.querySelectorAll('button, a, span, div[role="button"], .close, .modal-close, .popup-close');
                for (var i = 0; i < allElements.length; i++) {
                    var el = allElements[i];
                    var text = (el.textContent || '').trim();
                    var ariaLabel = (el.getAttribute('aria-label') || '').trim();
                    var title = (el.getAttribute('title') || '').trim();
                    for (var p = 0; p < closePatterns.length; p++) {
                        if (text === closePatterns[p] || ariaLabel === closePatterns[p] || title === closePatterns[p]) {
                            el.click();
                            closed++;
                            console.log('[WFH] Dismissed consent element: text="' + text + '"');
                            break;
                        }
                    }
                }
                // Also try removing overlays by display:none
                var overlays = document.querySelectorAll('.modal, .popup, .overlay, [class*="modal"], [class*="popup"], [class*="overlay"], [id*="modal"], [id*="popup"]');
                for (var i = 0; i < overlays.length; i++) {
                    overlays[i].style.setProperty('display', 'none', 'important');
                    closed++;
                }
                console.log('[WFH] Consent dismissal: closed ' + closed + ' elements');
                return 'dismissed_' + closed;
            })();
        """.trimIndent()

        // ──────────────────────────────────────────────
        //  VISIBLE SERVER LINK CONDITION
        // ──────────────────────────────────────────────

        /**
         * JS condition that returns true when a visible server link is found
         * (has nonzero bounding rect, visible, not an ad link).
         * Used in WaitForDomCondition.
         *
         * Placeholder __SERVER_LINK_SELECTOR__ is replaced at runtime.
         */
        val JS_VISIBLE_SERVER_LINK_CONDITION = """
            (function(){
                try {
                    var links = document.querySelectorAll('__SERVER_LINK_SELECTOR__');
                    for (var i = 0; i < links.length; i++) {
                        var r = links[i].getBoundingClientRect();
                        var href = links[i].href || '';
                        if (r.width > 0 && r.height > 0 && links[i].offsetParent !== null
                            && href.indexOf('viiqkzqv') === -1 && href.indexOf('wildsino') === -1) {
                            console.log('[WFH] Found visible server link:', href);
                            return true;
                        }
                    }
                    return false;
                } catch(e) { return false; }
            })()
        """.trimIndent()

        // ──────────────────────────────────────────────
        //  SERVER EXTRACTION JS
        // ──────────────────────────────────────────────

        /**
         * Extracts server metadata from the watching page.
         * Returns JSON array of {index, id, name}.
         *
         * Placeholder __WATCH_LIST_SELECTOR__ is replaced at runtime.
         */
        val JS_EXTRACT_SERVERS = """
            (function(){
                var items = document.querySelectorAll('__WATCH_LIST_SELECTOR__');
                var servers = [];
                for (var i = 0; i < items.length; i++) {
                    var idx = items[i].getAttribute('data-index') || '';
                    var id = items[i].getAttribute('data-id') || '';
                    var name = (items[i].textContent || '').trim().slice(0, 60);
                    servers.push({index: idx, id: id, name: name});
                    console.log('[WFH] Server #'+i+': idx='+idx+' id='+id+' name="'+name+'"');
                }
                return JSON.stringify(servers);
            })();
        """.trimIndent()

        // ──────────────────────────────────────────────
        //  DOWNLOAD EXTRACTION JS
        // ──────────────────────────────────────────────

        /**
         * Extracts download links from the watching page.
         * Returns JSON array of {name, url}.
         */
        val JS_EXTRACT_DOWNLOADS = """
            (function(){
                var links = document.querySelectorAll('#download li a[href], a[href*="download"], a[href*="dl"], .download-links a[href]');
                var downloads = [];
                var seen = {};
                for (var i = 0; i < links.length; i++) {
                    var name = (links[i].textContent || '').trim().slice(0, 60);
                    var href = links[i].href || '';
                    if (href && name && !seen[href]) {
                        seen[href] = true;
                        downloads.push({name: name, url: href});
                        console.log('[WFH] Download #'+i+': name="'+name+'" url="'+href+'"');
                    }
                }
                return JSON.stringify(downloads);
            })();
        """.trimIndent()

        /**
         * Debug JS that logs all elements with data-index, data-id, #watch, etc.
         * Useful for debugging when servers aren't found.
         */
        val JS_DEBUG_DOM = """
            (function(){
                var results = [];
                var di = document.querySelectorAll('[data-index]');
                results.push('data-index els: ' + di.length);
                for(var i=0;i<Math.min(di.length,10);i++){
                    var e=di[i];
                    results.push('  ['+i+']: tag='+e.tagName+' data-index='+e.getAttribute('data-index')+' data-id='+e.getAttribute('data-id')+' text='+(e.textContent||'').trim().slice(0,40));
                }
                var watch = document.querySelectorAll('#watch, .watch, [id*=watch], [class*=watch]');
                results.push('watch els: ' + watch.length);
                for(var i=0;i<Math.min(watch.length,5);i++){
                    var e=watch[i];
                    results.push('  ['+i+']: tag='+e.tagName+' id='+e.id+' class='+e.className+' children='+e.children.length);
                }
                return results.join('\\n');
            })();
        """.trimIndent()
    }
}
